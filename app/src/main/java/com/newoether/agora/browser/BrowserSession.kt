package com.newoether.agora.browser

import android.net.Uri
import com.newoether.agora.browser.cdp.CdpClient
import com.newoether.agora.browser.cdp.CdpConnectionListener
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.GenerationContext
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request

/** One compact `@eN` reference from the latest AX snapshot, bound to a DOM node. */
data class BrowserSnapshotRef(
    val ref: String,
    val backendNodeId: Long,
    val role: String,
    val name: String,
)

data class BrowserDownloadState(
    val guid: String,
    val state: String,
    val receivedBytes: Long,
    val totalBytes: Long,
)

/** Host-only, for diagnostics (full URLs may carry tokens/session ids — never logged). */
internal fun hostOf(url: String): String =
    runCatching { Uri.parse(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: "unknown"

/**
 * Unified browser session over all backends (§1.3.0).
 *
 * - LOCAL → `ws://127.0.0.1:<port>` (sandbox Chromium via [ChromiumLauncher]).
 * - TUNNEL → `wss://<user-url>/devtools/...?token=...` (token from encrypted
 *   prefs, never logged). Health-checked with `GET /json/version` before connect.
 * - WEBVIEW → `ws://127.0.0.1:<port>/devtools/page/<id>` (System WebView via
 *   [WebViewBrowserBackend]'s 127.0.0.1 bridge to the app-owned abstract
 *   DevTools socket; page target picked from `GET /json/list`).
 *
 * Switching backends closes the old CDP session and starts a new one. The
 * local Chromium process itself is never killed on a backend switch (scry:
 * never kill a healthy browser; its lifecycle is not tied to a task).
 */
class BrowserSession(
    private val prefs: BrowserPreferenceStore,
    private val launcher: ChromiumLauncher,
    private val webViewBackend: WebViewBrowserBackend,
    private val cdp: CdpClient,
    private val healthHttp: OkHttpClient,
    private val scope: CoroutineScope,
) {
    /**
     * Set by the tool provider on every execution so session-level events
     * (reconnects, keepalive failures) can be attributed to a generation when
     * one is in flight. Best-effort; null outside tool calls.
     */
    @Volatile
    var diagnosticContext: GenerationContext? = null

    /** Receives session-level audit events; wired by [BrowserToolProvider]. */
    var eventReporter: BrowserEventReporter? = null

    val cdpClient: CdpClient get() = cdp

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    @Volatile
    private var connectedMode: BrowserBackendMode? = null

    private val snapshotRefs = ConcurrentHashMap<String, BrowserSnapshotRef>()
    private val downloads = ConcurrentHashMap<String, BrowserDownloadState>()

    @Volatile
    private var takeoverActive = false

    @Volatile
    private var downloadEventsCollecting = false

    init {
        cdp.connectionListener = object : CdpConnectionListener {
            override fun onConnectionLost(reason: String) {
                report("connection_lost", 0L, "error:disconnected", mapOf("reason" to reason.take(60)))
            }

            override fun onReconnectAttempt(attempt: Int, nextDelayMs: Long) {
                report("reconnect_attempt", nextDelayMs, "ok", mapOf("attempt" to attempt.toString()))
            }

            override fun onReconnected(reattached: Boolean) {
                report(
                    "reconnected", 0L, "ok",
                    mapOf("backend" to (connectedMode?.persisted ?: "-"), "reattached" to reattached.toString()),
                )
            }
        }
    }

    private fun report(action: String, elapsedMs: Long, outcome: String, extra: Map<String, String>) {
        val reporter = eventReporter
        if (reporter != null) {
            runCatching { reporter.report(action, elapsedMs, outcome, extra) }
        } else {
            DebugLog.d(TAG, "$action outcome=$outcome")
        }
    }

    /** Connect (or re-validate) the effective backend. True when CDP commands can be sent. */
    suspend fun ensureConnected(): Boolean = mutex.withLock {
        val mode = prefs.effectiveMode()
        if (cdp.isConnected() && connectedMode == mode) return true
        if (connectedMode != null && connectedMode != mode) {
            DebugLog.d(TAG, "Backend switch $connectedMode -> $mode: closing old CDP session")
            report("backend_switch", 0L, "ok", mapOf("backend" to mode.persisted))
            cdp.close()
            connectedMode = null
        }
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val ok = when (mode) {
            BrowserBackendMode.LOCAL -> connectLocal()
            BrowserBackendMode.TUNNEL -> connectTunnel()
            BrowserBackendMode.WEBVIEW -> connectWebView()
        }
        val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
        if (ok) {
            connectedMode = mode
            startDownloadEventCollection()
            report("connect", elapsed, "ok", mapOf("backend" to mode.persisted))
        } else {
            report("connect", elapsed, "error:connect_failed", mapOf("backend" to mode.persisted))
        }
        ok
    }

    /** Currently connected backend, or null when not connected. */
    fun currentBackendMode(): BrowserBackendMode? = connectedMode

    /** Close the CDP session. The local Chromium process keeps running (scry). */
    suspend fun close() = mutex.withLock {
        cdp.close()
        connectedMode = null
    }

    // ── page primitives (all require ensureConnected() first) ──

    /** Navigate and wait for load (fail-open: returns loaded=false on timeout). */
    suspend fun navigate(url: String, timeoutMs: Long): Boolean {
        // Subscribe before navigating so the load event cannot be missed.
        val loadEvent = scope.async(Dispatchers.Default) {
            withTimeoutOrNull(LOAD_WAIT_MS) {
                cdp.events.filter { it.method == "Page.loadEventFired" }.first()
            }
        }
        cdp.invoke(
            method = "Page.navigate",
            params = buildJsonObject { put("url", url) },
            timeoutMs = timeoutMs,
        )
        return loadEvent.await() != null
    }

    suspend fun axSnapshot(timeoutMs: Long): JsonObject =
        cdp.invoke(
            method = "Accessibility.getFullAXTree",
            params = buildJsonObject {
                put("max_depth", 12)
            },
            timeoutMs = timeoutMs,
        )

    /** DOM.resolveNode → objectId for a backend node id. Null when unresolvable. */
    suspend fun resolveNode(backendNodeId: Long, timeoutMs: Long): String? {
        val result = cdp.invoke(
            method = "DOM.resolveNode",
            params = buildJsonObject { put("backendNodeId", backendNodeId) },
            timeoutMs = timeoutMs,
        )
        return ((result["object"] as? JsonObject)?.get("objectId") as? JsonPrimitive)?.contentOrNull
    }

    suspend fun boxModel(objectId: String, timeoutMs: Long): JsonObject? {
        val result = cdp.invoke(
            method = "DOM.getBoxModel",
            params = buildJsonObject { put("objectId", objectId) },
            timeoutMs = timeoutMs,
        )
        return result["model"] as? JsonObject
    }

    suspend fun focusObject(objectId: String, timeoutMs: Long) {
        cdp.invoke(
            method = "DOM.focus",
            params = buildJsonObject { put("objectId", objectId) },
            timeoutMs = timeoutMs,
        )
    }

    /** Trusted-path typing: types into the focused element (vault JIT injection uses this). */
    suspend fun insertText(text: String, timeoutMs: Long) {
        cdp.invoke(
            method = "Input.insertText",
            params = buildJsonObject { put("text", text) },
            timeoutMs = timeoutMs,
        )
    }

    suspend fun mouseClick(x: Double, y: Double, timeoutMs: Long) {
        val base = buildJsonObject {
            put("type", "mousePressed")
            put("x", x)
            put("y", y)
            put("button", "left")
            put("clickCount", 1)
        }
        cdp.invoke(method = "Input.dispatchMouseEvent", params = base, timeoutMs = timeoutMs)
        cdp.invoke(
            method = "Input.dispatchMouseEvent",
            params = buildJsonObject {
                put("type", "mouseReleased")
                put("x", x)
                put("y", y)
                put("button", "left")
                put("clickCount", 1)
            },
            timeoutMs = timeoutMs,
        )
    }

    suspend fun mouseWheel(x: Double, y: Double, deltaX: Double, deltaY: Double, timeoutMs: Long) {
        cdp.invoke(
            method = "Input.dispatchMouseEvent",
            params = buildJsonObject {
                put("type", "mouseWheel")
                put("x", x)
                put("y", y)
                put("deltaX", deltaX)
                put("deltaY", deltaY)
            },
            timeoutMs = timeoutMs,
        )
    }

    suspend fun dispatchKey(type: String, key: String, windowsVirtualKeyCode: Int, timeoutMs: Long) {
        cdp.invoke(
            method = "Input.dispatchKeyEvent",
            params = buildJsonObject {
                put("type", type)
                put("key", key)
                put("windowsVirtualKeyCode", windowsVirtualKeyCode)
                put("nativeVirtualKeyCode", windowsVirtualKeyCode)
            },
            timeoutMs = timeoutMs,
        )
    }

    /** Viewport JPEG screenshot; returns raw bytes (base64-decoded). */
    suspend fun captureScreenshot(timeoutMs: Long): ByteArray {
        val result = cdp.invoke(
            method = "Page.captureScreenshot",
            params = buildJsonObject {
                put("format", "jpeg")
                put("quality", 60)
                put("captureBeyondViewport", false)
            },
            timeoutMs = timeoutMs,
        )
        val data = (result["data"] as? JsonPrimitive)?.contentOrNull
            ?: throw com.newoether.agora.browser.cdp.CdpException("screenshot_missing_data")
        return android.util.Base64.decode(data, android.util.Base64.DEFAULT)
    }

    suspend fun setFileInputFiles(objectId: String, files: List<String>, timeoutMs: Long) {
        cdp.invoke(
            method = "DOM.setFileInputFiles",
            params = buildJsonObject {
                put("objectId", objectId)
                put("files", buildJsonArray(files))
            },
            timeoutMs = timeoutMs,
        )
    }

    // ── snapshot refs ──

    fun storeSnapshotRefs(refs: Map<String, BrowserSnapshotRef>) {
        snapshotRefs.clear()
        snapshotRefs.putAll(refs)
    }

    fun resolveRef(ref: String): BrowserSnapshotRef? = snapshotRefs[ref]

    // ── takeover ──

    /**
     * Take-over mode flag (Phase 3): pause the agent loop, stream screenshots,
     * forward touch as CDP input. The UI stream implements the actual
     * streaming/overlay; the tool layer only tracks the requested state.
     */
    fun setTakeover(active: Boolean) {
        takeoverActive = active
        DebugLog.d(TAG, "Takeover ${if (active) "started" else "stopped"}")
    }

    fun isTakeoverActive(): Boolean = takeoverActive

    // ── downloads ──

    fun downloadStatus(): List<BrowserDownloadState> = downloads.values.toList()

    // ── backend connect ──

    private suspend fun connectLocal(): Boolean {
        if (!launcher.ensureStarted()) {
            DebugLog.w(TAG, "connectLocal: Chromium failed to start")
            return false
        }
        val version = httpGetJson(
            "http://127.0.0.1:${launcher.debugPort()}/json/version",
            HEALTH_CHECK_TIMEOUT_MS,
        ) ?: return false
        val wsUrl = (version["webSocketDebuggerUrl"] as? JsonPrimitive)?.contentOrNull
            ?: return false
        if (!cdp.connect(wsUrl)) return false
        cdp.openPage("about:blank")
        // Downloads land in the app-owned dir (bound into the sandbox), never auto-cleaned.
        cdp.invoke(
            method = "Browser.setDownloadBehavior",
            params = buildJsonObject {
                put("behavior", "allow")
                put("downloadPath", ChromiumLauncher.SANDBOX_DOWNLOAD_PATH)
            },
            sessionId = null,
        )
        return true
    }

    private suspend fun connectTunnel(): Boolean {
        val rawUrl = prefs.tunnelUrl.value.trim()
        if (rawUrl.isBlank()) {
            DebugLog.w(TAG, "connectTunnel: no tunnel URL configured")
            return false
        }
        val tunnelUri = runCatching { Uri.parse(rawUrl) }.getOrNull()
        val host = tunnelUri?.host
        if (tunnelUri?.scheme != "https" || host.isNullOrBlank()) {
            // Security (scry): tunnel backend requires a user-supplied HTTPS URL.
            DebugLog.w(TAG, "connectTunnel: tunnel URL must be https")
            return false
        }
        val token = prefs.tunnelClientToken.value
        if (token.isBlank()) {
            DebugLog.w(TAG, "connectTunnel: tunnel client token not set")
            return false
        }
        // Health check before connect (§1.3.0): GET /json/version → 200.
        // The relay 401s without the client token, so it must travel here too
        // (same contract as the settings Validate probe).
        val version = httpGetJson(
            "$rawUrl/json/version?token=" + URLEncoder.encode(token, Charsets.UTF_8.name()),
            HEALTH_CHECK_TIMEOUT_MS,
        )
        if (version == null) {
            DebugLog.w(TAG, "connectTunnel: tunnel endpoint unreachable (/json/version)")
            return false
        }
        // Derive the debugger WS path from the version payload, then pin it to
        // the user's host with the client token. The token is never logged.
        val debuggerUrl = (version["webSocketDebuggerUrl"] as? JsonPrimitive)?.contentOrNull
        val path = debuggerUrl?.let { extractWsPath(it) } ?: "/devtools/browser"
        val separator = if (path.contains("?")) "&" else "?"
        val wsUrl = "wss://$host$path$separator" + "token=" +
            URLEncoder.encode(token, Charsets.UTF_8.name())
        if (!cdp.connect(wsUrl)) {
            DebugLog.w(TAG, "connectTunnel: CDP websocket to tunnel endpoint failed")
            return false
        }
        cdp.openPage("about:blank")
        // Remote downloads stay on the remote end (default dir); the local
        // download dir only applies to the LOCAL backend.
        cdp.invoke(
            method = "Browser.setDownloadBehavior",
            params = buildJsonObject { put("behavior", "allow") },
            sessionId = null,
        )
        DebugLog.d(TAG, "connectTunnel: connected via tunnel endpoint")
        return true
    }

    /**
     * WEBVIEW backend: System WebView via the 127.0.0.1 bridge. The
     * `webSocketDebuggerUrl` served over the abstract socket has no usable
     * host, so the page target is picked from `GET /json/list` and the WS URL
     * is built against the bridge port.
     */
    private suspend fun connectWebView(): Boolean {
        if (!webViewBackend.ensureStarted()) {
            DebugLog.w(TAG, "connectWebView: WebView backend failed to start")
            return false
        }
        val port = webViewBackend.debugPort()
        val targets = httpGetJsonArray(
            "http://127.0.0.1:$port/json/list",
            HEALTH_CHECK_TIMEOUT_MS,
        ) ?: return false
        val page = targets.mapNotNull { it as? JsonObject }
            .firstOrNull { (it["type"] as? JsonPrimitive)?.contentOrNull == "page" }
            ?: return false
        val targetId = (page["id"] as? JsonPrimitive)?.contentOrNull
        if (targetId.isNullOrBlank()) return false
        val wsUrl = webViewTargetWsUrl(port, targetId)
        if (!cdp.connect(wsUrl)) {
            DebugLog.w(TAG, "connectWebView: CDP websocket to WebView bridge failed")
            return false
        }
        // The WebView profile is persistent; downloads use the default
        // behavior (files land in the WebView profile dir).
        cdp.invoke(
            method = "Browser.setDownloadBehavior",
            params = buildJsonObject { put("behavior", "allow") },
            sessionId = null,
        )
        DebugLog.d(TAG, "connectWebView: connected via System WebView bridge")
        return true
    }

    /** `ws://anything/devtools/browser/x` → `/devtools/browser/x`. Null when unparseable. */
    private fun extractWsPath(debuggerUrl: String): String? {
        val withoutScheme = debuggerUrl.substringAfter("://", "")
        if (withoutScheme.isEmpty()) return null
        val path = "/" + withoutScheme.substringAfter("/", "")
        return path.takeIf { it.length > 1 }
    }

    private suspend fun httpGetJson(url: String, timeoutMs: Long): JsonObject? =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", Constants.WEB_FETCH_USER_AGENT)
                    .build()
                // Per-call timeouts: never mutate the shared client.
                val callClient = healthHttp.newBuilder()
                    .connectTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .readTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .build()
                callClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val body = response.body?.string().orEmpty()
                    json.parseToJsonElement(body) as? JsonObject
                }
            } catch (_: Exception) {
                null
            }
        }

    private suspend fun httpGetJsonArray(url: String, timeoutMs: Long): kotlinx.serialization.json.JsonArray? =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", Constants.WEB_FETCH_USER_AGENT)
                    .build()
                val callClient = healthHttp.newBuilder()
                    .connectTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .readTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .build()
                callClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val body = response.body?.string().orEmpty()
                    json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonArray
                }
            } catch (_: Exception) {
                null
            }
        }

    private fun startDownloadEventCollection() {
        if (downloadEventsCollecting) return
        downloadEventsCollecting = true
        scope.launch(Dispatchers.Default) {
            cdp.events.collect { event ->
                when (event.method) {
                    "Browser.downloadWillBegin" -> {
                        val guid = event.stringParam("guid") ?: return@collect
                        downloads[guid] = BrowserDownloadState(guid, "started", 0L, 0L)
                    }
                    "Browser.downloadProgress" -> {
                        val guid = event.stringParam("guid") ?: return@collect
                        val state = event.stringParam("state") ?: "inProgress"
                        downloads[guid] = BrowserDownloadState(
                            guid = guid,
                            state = state,
                            receivedBytes = event.longParam("receivedBytes") ?: 0L,
                            totalBytes = event.longParam("totalBytes") ?: 0L,
                        )
                    }
                }
            }
        }
    }

    private fun com.newoether.agora.browser.cdp.CdpEvent.stringParam(name: String): String? =
        (params[name] as? JsonPrimitive)?.contentOrNull

    private fun com.newoether.agora.browser.cdp.CdpEvent.longParam(name: String): Long? =
        (params[name] as? JsonPrimitive)?.longOrNull

    private fun buildJsonArray(values: List<String>): kotlinx.serialization.json.JsonArray =
        kotlinx.serialization.json.buildJsonArray {
            values.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
        }

    companion object {
        private const val TAG = "BrowserSession"
        private const val HEALTH_CHECK_TIMEOUT_MS = 10_000L
        private const val LOAD_WAIT_MS = 20_000L

        /**
         * CDP WebSocket URL for a WebView page target through the 127.0.0.1
         * bridge. The `webSocketDebuggerUrl` served over the abstract socket
         * has no usable host, so the URL is built from the `/json/list`
         * target id instead. Unit-tested.
         */
        internal fun webViewTargetWsUrl(bridgePort: Int, targetId: String): String =
            "ws://127.0.0.1:$bridgePort/devtools/page/$targetId"
    }
}
