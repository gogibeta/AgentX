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
 * remains the default until this backend passes device testing.
 */
class WebViewBrowserBackend(
    private val appContext: Context,
    private val scope: CoroutineScope,
) {
    private val startMutex = Mutex()

    /** Strong reference: a GC'd WebView would kill the DevTools socket. */
    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var bridgePort: Int = 0

    /** Local TCP port of the 127.0.0.1 → abstract-socket bridge. 0 until started. */
    fun debugPort(): Int = bridgePort

    fun devtoolsSocketName(): String =
        devtoolsSocketNameForPid(Process.myPid())

    /**
     * Create the WebView (main thread), enable DevTools, and start the TCP
     * bridge. Idempotent; true when the bridge is accepting connections.
     */
    suspend fun ensureStarted(): Boolean = startMutex.withLock {
        if (bridgePort != 0 && webView != null && serverSocket?.isClosed == false) return true
        return try {
            val wv = createWebViewOnMainThread()
            webView = wv
            val socketName = devtoolsSocketName()
            if (!waitForAbstractSocket(socketName, SOCKET_WAIT_MS)) {
                DebugLog.w(TAG, "ensureStarted: DevTools socket @$socketName never appeared")
                return false
            }
            val server = ServerSocket(BRIDGE_PORT_ANY, BRIDGE_BACKLOG, InetAddress.getByName("127.0.0.1"))
            bridgePort = server.localPort
            serverSocket = server
            scope.launch(Dispatchers.IO) { acceptLoop(server, socketName) }
            DebugLog.d(TAG, "ensureStarted: CDP bridge 127.0.0.1:$bridgePort -> @$socketName")
            true
        } catch (e: Exception) {
            DebugLog.w(TAG, "ensureStarted failed: ${e.javaClass.simpleName}: ${e.message?.take(120)}")
            false
        }
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

        /**
         * Abstract DevTools socket name for [pid]. The WebView opens
         * `@webview_devtools_remote_<pid>` once debugging is enabled and a
         * WebView instance exists. Unit-testable separately from [Process.myPid].
         */
        internal fun devtoolsSocketNameForPid(pid: Int): String =
            "webview_devtools_remote_$pid"
    }
}
