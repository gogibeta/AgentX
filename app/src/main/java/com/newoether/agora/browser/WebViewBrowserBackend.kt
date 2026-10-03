package com.newoether.agora.browser

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.webkit.WebView
import com.newoether.agora.util.DebugLog
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * v2.4 WebView CDP backend — the lightest on-device browser (§1.3.0 extension).
 *
 * Instead of the proot/Alpine Chromium sandbox, this drives the Android System
 * WebView that is already on the device:
 *
 * 1. `WebView.setWebContentsDebuggingEnabled(true)` — opens the app-owned
 *    `@webview_devtools_remote_<pid>` abstract socket (device-local only, like
 *    the existing 127.0.0.1:9333 sandbox port; never exposed publicly).
 * 2. A tiny 127.0.0.1 TCP bridge forwards bytes to that abstract socket, so
 *    the existing HTTP /json endpoints + WebSocket CDP machinery work unchanged.
 * 3. The WebView uses the app's persistent WebView profile — cookies, storage
 *    and logins survive restarts with no extra download.
 *
 * Debugging is enabled lazily on first [ensureStarted], never unconditionally
 * at app start. The sandbox Chromium ([ChromiumLauncher]) is untouched and
 * remains available to the shell sandbox; System WebView is the browser
 * default.
 */
class WebViewBrowserBackend(
    private val appContext: Context,
    private val scope: CoroutineScope,
) {
    private val startMutex = Mutex()

    /**
     * One WebView per browser session (keyed by conversation id, "default"
     * when the agent runs outside a chat). Strong references: a GC'd WebView
     * would kill its DevTools target. This is what lets two chats drive two
     * independent browsers with no overlap.
     */
    private val webViews = ConcurrentHashMap<String, WebView>()

    /** DevTools page-target id per session, resolved at WebView creation. */
    private val targetIds = ConcurrentHashMap<String, String>()

    /**
     * The live WebView for a session, for embedding in the watch panel via
     * AndroidView. Attaching it to the view hierarchy makes the browser
     * VISIBLE (it is created headless) and touchable (take-control). Null
     * until [ensureStarted] for that session. Must only be attached/detached
     * on the main thread (AndroidView handles this).
     */
    fun liveWebView(sessionKey: String = DEFAULT_SESSION_KEY): WebView? =
        webViews[sessionKey]

    /** DevTools page-target id for a session's WebView. Null until created. */
    fun targetIdFor(sessionKey: String): String? = targetIds[sessionKey]

    /**
     * Destroy a session's WebView and drop its DevTools target (B1).
     * Called on Stop so the page is REALLY closed — without this, stopped
     * sessions leaked one WebView (+ renderer) each for the process lifetime.
     * WebView.destroy() must run on the main thread.
     */
    suspend fun destroySession(sessionKey: String) {
        val view = webViews.remove(sessionKey)
        targetIds.remove(sessionKey)
        if (view != null) {
            withContext(Dispatchers.Main) {
                try {
                    (view.parent as? android.view.ViewGroup)?.removeView(view)
                    view.destroy()
                } catch (e: Exception) {
                    DebugLog.w(TAG, "destroySession: ${e.javaClass.simpleName}")
                }
            }
            DebugLog.d(TAG, "destroySession: destroyed WebView for $sessionKey")
        }
    }

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var bridgePort: Int = 0

    /** Local TCP port of the 127.0.0.1 → abstract-socket bridge. 0 until started. */
    fun debugPort(): Int = bridgePort

    fun devtoolsSocketName(): String =
        devtoolsSocketNameForPid(Process.myPid())

    /**
     * Start the TCP bridge (once) and create the session's WebView (main
     * thread) with DevTools enabled. Idempotent per session; true when the
     * session's WebView has a DevTools page target.
     *
     * Order matters: the WebView comes first (it creates the
     * `@webview_devtools_remote_<pid>` abstract socket), then the bridge
     * (which waits for that socket), then the target diff. The mutex
     * serializes concurrent sessions so two chats can never grab each
     * other's page target.
     */
    suspend fun ensureStarted(sessionKey: String = DEFAULT_SESSION_KEY): Boolean =
        startMutex.withLock {
            try {
                if (webViews[sessionKey] != null && targetIds[sessionKey] != null) return true
                val ownedBefore = targetIds.values.toSet()
                if (webViews[sessionKey] == null) {
                    webViews[sessionKey] = createWebViewOnMainThread()
                }
                if (!ensureBridgeLocked()) return false
                val targetId = waitForNewPageTarget(ownedBefore, TARGET_WAIT_MS)
                if (targetId == null) {
                    DebugLog.w(TAG, "ensureStarted: no new page target for session $sessionKey")
                    return false
                }
                targetIds[sessionKey] = targetId
                DebugLog.d(TAG, "ensureStarted: session $sessionKey -> target $targetId")
                true
            } catch (e: Exception) {
                DebugLog.w(TAG, "ensureStarted failed: ${e.javaClass.simpleName}: ${e.message?.take(120)}")
                false
            }
        }

    /** Start the 127.0.0.1 → abstract-socket bridge once. */
    private fun ensureBridgeLocked(): Boolean {
        if (bridgePort != 0 && serverSocket?.isClosed == false) return true
        val socketName = devtoolsSocketName()
        if (!waitForAbstractSocket(socketName, SOCKET_WAIT_MS)) {
            DebugLog.w(TAG, "ensureBridge: DevTools socket @$socketName never appeared")
            return false
        }
        val server = ServerSocket(BRIDGE_PORT_ANY, BRIDGE_BACKLOG, InetAddress.getByName("127.0.0.1"))
        bridgePort = server.localPort
        serverSocket = server
        scope.launch(Dispatchers.IO) { acceptLoop(server, socketName) }
        DebugLog.d(TAG, "ensureBridge: CDP bridge 127.0.0.1:$bridgePort -> @$socketName")
        return true
    }

    /** Page-target ids currently served by the bridge. Empty on failure. */
    private fun listPageTargetIds(): Set<String> = runCatching {
        val port = bridgePort
        if (port == 0) return emptySet()
        val conn = URL("http://127.0.0.1:$port/json/list").openConnection()
        conn.connectTimeout = 5_000
        conn.readTimeout = 5_000
        val body = conn.getInputStream().bufferedReader().readText()
        // Minimal parse: every target id appears as "id":"<id>".
        val typePage = Regex("\"type\"\\s*:\\s*\"page\"")
        val idRe = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"")
        body.split(Regex("\\},\\s*\\{"))
            .filter { typePage.containsMatchIn(it) }
            .mapNotNull { idRe.find(it)?.groupValues?.get(1) }
            .toSet()
    }.getOrDefault(emptySet())

    /** Wait for a page target that was not in [before]; null on timeout. */
    private fun waitForNewPageTarget(before: Set<String>, timeoutMs: Long): String? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val fresh = listPageTargetIds() - before
            if (fresh.isNotEmpty()) return fresh.first()
            Thread.sleep(TARGET_POLL_MS)
        }
        return null
    }

    /**
     * WebView must be constructed on the main thread. Debugging is enabled
     * first so the abstract socket exists as soon as the instance does.
     */
    private suspend fun createWebViewOnMainThread(): WebView =
        suspendCancellableCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                cont.resumeWith(
                    runCatching {
                        WebView.setWebContentsDebuggingEnabled(true)
                        WebView(appContext).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            // Desktop view: sites serve their desktop layout
                            // (no "rotate your device" overlays) and the agent
                            // sees the full page, muse.ai-style.
                            settings.userAgentString = DESKTOP_UA
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            loadUrl("about:blank")
                        }
                    },
                )
            }
        }

    private fun waitForAbstractSocket(name: String, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                LocalSocket().use { probe ->
                    probe.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT))
                    return true
                }
            } catch (_: Exception) {
                // Not up yet.
            }
            Thread.sleep(SOCKET_POLL_MS)
        }
        return false
    }

    private fun acceptLoop(server: ServerSocket, socketName: String) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: Exception) {
                break
            }
            thread(name = "wv-cdp-bridge", isDaemon = true) {
                forwardConnection(client, socketName)
            }
        }
    }

    /**
     * Bidirectional byte forward between one accepted TCP client and a fresh
     * connection to the WebView DevTools abstract socket. HTTP and WebSocket
     * both pass through untouched.
     */
    private fun forwardConnection(client: Socket, socketName: String) {
        val devtools = LocalSocket()
        try {
            devtools.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
            val c2d = thread(name = "wv-c2d", isDaemon = true) {
                copyStream(client.getInputStream(), devtools.outputStream)
            }
            val d2c = thread(name = "wv-d2c", isDaemon = true) {
                copyStream(devtools.inputStream, client.getOutputStream())
            }
            // Either direction ending tears the pair down; the survivor's
            // blocked read unblocks when we close both sockets below.
            while (c2d.isAlive && d2c.isAlive) Thread.sleep(FORWARD_POLL_MS)
        } catch (_: Exception) {
            // Bridge best-effort; failures surface as CDP connect errors.
        } finally {
            runCatching { client.close() }
            runCatching { devtools.close() }
        }
    }

    private fun copyStream(input: InputStream, output: OutputStream) {
        try {
            val buf = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                output.flush()
            }
        } catch (_: Exception) {
            // Peer went away; the forwardConnection teardown handles the rest.
        }
    }

    companion object {
        private const val TAG = "WebViewBrowserBackend"
        private const val BRIDGE_PORT_ANY = 0
        private const val BRIDGE_BACKLOG = 16
        private const val SOCKET_WAIT_MS = 10_000L
        private const val SOCKET_POLL_MS = 200L
        private const val FORWARD_POLL_MS = 50L
        private const val COPY_BUFFER_BYTES = 8192
        private const val TARGET_WAIT_MS = 10_000L
        private const val TARGET_POLL_MS = 200L
        const val DEFAULT_SESSION_KEY = "default"

        /**
         * Desktop Chrome UA so sites serve the desktop layout inside the
         * on-device browser popup (avoids mobile "rotate your device"
         * interstitials that intercept the agent's input).
         */
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        /**
         * Abstract DevTools socket name for [pid]. The WebView opens
         * `@webview_devtools_remote_<pid>` once debugging is enabled and a
         * WebView instance exists. Unit-testable separately from [Process.myPid].
         */
        internal fun devtoolsSocketNameForPid(pid: Int): String =
            "webview_devtools_remote_$pid"
    }
}
