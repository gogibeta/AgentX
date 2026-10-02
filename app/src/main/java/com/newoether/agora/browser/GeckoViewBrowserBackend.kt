package com.newoether.agora.browser

import android.content.Context
import android.graphics.Bitmap
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * GECKOVIEW backend — Mozilla's Gecko engine (non-Chromium) for AgentX.
 *
 * The fast, screenshot-free agent loop (browser-use / jev-ultrafast pattern):
 *
 * 1. A bundled WebExtension (`assets/geckoview/`) runs a content script in
 *    every page. The script builds an indexed element table
 *    (`[ref] role "name" · value · state`) and PUSHES it to native on page
 *    load and on debounced DOM mutations via `sendNativeMessage`.
 * 2. Native caches the latest table per session. [snapshot] returns it with
 *    ZERO round trips — no CDP, no screenshots, ever, in the agent loop.
 * 3. Actions (click/type/scroll) go native → background port →
 *    `tabs.sendMessage` → content script → DOM. Responses correlate by reqId.
 * 4. Screenshots exist ONLY for the user's watch panel thumbnails, via
 *    `GeckoView.capturePixels()` — never fed to the model.
 *
 * Stealth posture: natively-driven GeckoView sets no `navigator.webdriver`
 * flag and carries a genuine Gecko TLS fingerprint (unlike CDP-driven
 * Chromium, which leaks automation artifacts).
 */
class GeckoViewBrowserBackend(
    private val appContext: Context,
    private val scope: CoroutineScope,
) {
    private val startMutex = Mutex()

    private var runtime: GeckoRuntime? = null
    private var session: GeckoSession? = null

    /** Strong reference: GC of the view kills rendering. Main thread only. */
    @Volatile
    private var geckoView: GeckoView? = null

    private var extension: WebExtension? = null
    private val nativePort = AtomicReference<WebExtension.Port?>()

    /** Latest pushed element table; the agent loop reads this (no round trip). */
    private val latestTable = AtomicReference("")
    private val latestUrl = AtomicReference("")
    private val latestTitle = AtomicReference("")

    /** reqId → pending action response. */
    private val pendingActions = ConcurrentHashMap<String, CompletableDeferred<JSONObject?>>()

    @Volatile
    private var started = false

    /** Run [block] on the main thread (Gecko API requirement). */
    private suspend fun <T> withMain(block: suspend CoroutineScope.() -> T): T =
        withContext(Dispatchers.Main, block)

    /** The live GeckoView, for embedding in the watch panel via AndroidView. */
    fun liveView(): GeckoView? = geckoView

    /** Current page URL (from the last pushed table or navigation delegate). */
    fun currentUrl(): String = latestUrl.get()

    /** Current page title. */
    fun currentTitle(): String = latestTitle.get()

    /**
     * Idempotent start: runtime + session + WebExtension + view.
     * True when the session is open and the bridge extension is installed.
     *
     * All GeckoView calls run on the main thread — GeckoRuntime.create(),
     * GeckoSession.open(), and WebExtensionController.install() all require
     * it and throw (or deadlock) otherwise.
     */
    suspend fun ensureStarted(): Boolean = startMutex.withLock {
        if (started && session?.isOpen == true) return true
        return try {
            withContext(Dispatchers.Main) {
                val rt = runtime ?: GeckoRuntime.create(appContext).also { runtime = it }
                val sess = GeckoSession().also { session = it }
                sess.navigationDelegate = navigationDelegate
                sess.progressDelegate = progressDelegate
                sess.contentDelegate = contentDelegate
                sess.open(rt)
                installBridge(rt)
                geckoView = GeckoView(appContext).also { it.setSession(sess) }
            }
            started = true
            DebugLog.d(TAG, "ensureStarted: GeckoView backend ready")
            true
        } catch (e: Exception) {
            DebugLog.w(TAG, "ensureStarted failed: ${e.javaClass.simpleName}: ${e.message?.take(200)}")
            false
        }
    }

    /**
     * FAST observation: the latest pushed element table. Zero round trips —
     * the content script pushes on load + DOM mutations. Never a screenshot.
     */
    fun snapshot(): String = latestTable.get()

    /** Navigate to a URL. */
    fun navigate(url: String): Boolean {
        val sess = session ?: return false
        return try {
            sess.loadUri(url)
            true
        } catch (e: Exception) {
            DebugLog.w(TAG, "navigate failed: ${e.message?.take(80)}")
            false
        }
    }

    /** Go back / forward in history. */
    fun goBack(): Boolean = runCatching {
        session?.goBack(); true
    }.getOrDefault(false)

    fun goForward(): Boolean = runCatching {
        session?.goForward(); true
    }.getOrDefault(false)

    /** Reload the current page. */
    fun reload(): Boolean = runCatching {
        session?.reload(); true
    }.getOrDefault(false)

    // ── Actions (native → content script, correlated by reqId) ────────────

    /** Click the element with the given ref from the last snapshot. */
    suspend fun click(ref: Int): Boolean =
        action(JSONObject().put("type", "click").put("ref", ref))?.optBoolean("ok") == true

    /** Type text into the element with the given ref. */
    suspend fun typeText(ref: Int, text: String): Boolean =
        action(
            JSONObject().put("type", "type").put("ref", ref).put("text", text),
        )?.optBoolean("ok") == true

    /** Scroll the element into view, or the page by [dy] when ref is null. */
    suspend fun scroll(ref: Int? = null, dy: Int = 600): Boolean =
        action(
            JSONObject().put("type", "scroll")
                .put("ref", ref ?: JSONObject.NULL)
                .put("dy", dy),
        )?.optBoolean("ok") == true

    /**
     * Request/response action through the extension port.
     * Fast: one message round trip, ~ms. Null on timeout/disconnect.
     */
    private suspend fun action(msg: JSONObject): JSONObject? {
        val port = nativePort.get() ?: run {
            DebugLog.w(TAG, "action: bridge port not connected")
            return null
        }
        val reqId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<JSONObject?>()
        pendingActions[reqId] = deferred
        try {
            val envelope = JSONObject()
                .put("msg", msg)
                .put("reqId", reqId)
            // Port messaging must happen on the main thread.
            withMain { port.postMessage(envelope) }
            return withTimeoutOrNull(ACTION_TIMEOUT_MS) { deferred.await() }
        } finally {
            pendingActions.remove(reqId)
        }
    }

    // ── Watch-panel thumbnails (user eyes only, never the model) ──────────

    /** JPEG screenshot for the watch panel. Null on failure. */
    suspend fun captureThumbnail(): ByteArray? {
        // capturePixels lives on GeckoView (the compositor surface), not on
        // GeckoSession, and must be called on the UI thread.
        val view = geckoView ?: return null
        return try {
            val pixels = CompletableDeferred<GeckoResult<Bitmap>>()
            withMain { pixels.complete(view.capturePixels()) }
            val bitmap = pixels.await().await()
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 60, out)
            out.toByteArray().takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            DebugLog.d(TAG, "captureThumbnail failed: ${e.message?.take(60)}")
            null
        }
    }

    /** Close the session; the runtime is kept (process-wide singleton). */
    fun close() {
        runCatching { session?.close() }
        session = null
        geckoView = null
        started = false
        latestTable.set("")
    }

    // ── Internals ────────────────────────────────────────────────────────

    private suspend fun installBridge(rt: GeckoRuntime) {
        val result: GeckoResult<WebExtension> =
            rt.webExtensionController.install("resource://android/assets/geckoview/")
        val ext = result.await()
        ext.setMessageDelegate(bridgeDelegate, "agentx")
        extension = ext
        DebugLog.d(TAG, "installBridge: AgentX bridge extension installed")
    }

    /** Receives pushed tables (sendNativeMessage) and the background port. */
    private val bridgeDelegate = object : WebExtension.MessageDelegate {
        override fun onConnect(port: WebExtension.Port) {
            DebugLog.d(TAG, "bridgeDelegate: native port connected")
            nativePort.set(port)
            port.setDelegate(object : WebExtension.PortDelegate {
                override fun onPortMessage(message: Any, port: WebExtension.Port) {
                    handlePortMessage(message)
                }

                override fun onDisconnect(port: WebExtension.Port) {
                    DebugLog.w(TAG, "bridgeDelegate: native port disconnected")
                    nativePort.compareAndSet(port, null)
                }
            })
        }

        override fun onMessage(
            nativeApp: String,
            message: Any,
            sender: WebExtension.MessageSender,
        ): GeckoResult<Any>? {
            // Pushed element tables from the content script.
            handlePushedTable(message)
            return null
        }
    }

    private fun handlePushedTable(msg: Any) {
        val obj = (msg as? JSONObject) ?: return
        if (obj.optString("type") != "table") return
        latestTable.set(obj.optString("table"))
        latestUrl.set(obj.optString("url"))
        latestTitle.set(obj.optString("title"))
    }

    private fun handlePortMessage(message: Any) {
        val obj = (message as? JSONObject) ?: return
        val reqId = obj.optString("reqId")
        val deferred = pendingActions[reqId] ?: return
        val resp = obj.optJSONObject("resp")
        val error = obj.optString("error", null)
        if (error != null) {
            DebugLog.w(TAG, "action $reqId failed: ${error.take(80)}")
        }
        scope.launch { deferred.complete(resp) }
    }

    private val navigationDelegate = object : GeckoSession.NavigationDelegate {
        override fun onLocationChange(
            session: GeckoSession,
            url: String?,
            perms: List<PermissionDelegate.ContentPermission>,
            hasUserGesture: Boolean,
        ) {
            url?.let { latestUrl.set(it) }
        }
    }

    private val contentDelegate = object : GeckoSession.ContentDelegate {
        override fun onTitleChange(session: GeckoSession, title: String?) {
            title?.let { latestTitle.set(it) }
        }
    }

    private val progressDelegate = object : GeckoSession.ProgressDelegate {
        override fun onPageStart(session: GeckoSession, url: String) {
            // The content script pushes a fresh table on load; nothing to do.
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
            // Table push arrives via the content script's load handler.
        }
    }

    companion object {
        private const val TAG = "GeckoViewBackend"
        private const val ACTION_TIMEOUT_MS = 10_000L
    }
}

/** Await a GeckoResult as a suspend function. */
private suspend fun <T : Any> GeckoResult<T>.await(): T =
    suspendCancellableCoroutine { cont ->
        then(
            { value ->
                // OnValueListener receives a nullable value; fail loudly on null.
                if (value == null) {
                    cont.resumeWithException(IllegalStateException("GeckoResult completed with null"))
                } else {
                    cont.resume(value)
                }
                GeckoResult.fromValue(value)
            },
            { e ->
                cont.resumeWithException(e ?: RuntimeException("GeckoResult failed"))
                GeckoResult.fromException(e)
            },
        )
    }
