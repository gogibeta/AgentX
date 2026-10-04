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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
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
 * - TUNNEL → `wss://<user-url>/devtools/...?token=...` (token from encrypted
 *   prefs, never logged). Health-checked with `GET /json/version` before connect.
 * - WEBVIEW → `ws://127.0.0.1:<port>/devtools/page/<id>` (System WebView via
 *   [WebViewBrowserBackend]'s 127.0.0.1 bridge to the app-owned abstract
 *   DevTools socket; page target picked from `GET /json/list`).
 *
 * Switching backends closes the old CDP session and starts a new one.
 */
class BrowserSession(
    private val prefs: BrowserPreferenceStore,
    private val webViewBackend: WebViewBrowserBackend,
    private val cdp: CdpClient,
    private val healthHttp: OkHttpClient,
    private val scope: CoroutineScope,
    /** Which chat owns this session ("default" outside a chat). One browser per chat. */
    val sessionKey: String = WebViewBrowserBackend.DEFAULT_SESSION_KEY,
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

    /**
     * Live WebView for the watch panel (AndroidView). Non-null only when the
     * connected backend is WEBVIEW and [WebViewBrowserBackend.ensureStarted]
     * has run. Attaching it makes the browser visible and touchable
     * (take-control); screenshots are skipped while it is attached.
     */
    fun liveWebView(): android.webkit.WebView? =
        if (connectedMode == BrowserBackendMode.WEBVIEW) webViewBackend.liveWebView(sessionKey) else null

    /** The currently connected backend, if any. */
    fun connectedBackend(): BrowserBackendMode? = connectedMode

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

    /**
     * Why the last [ensureConnected] attempt failed, in plain words
     * ("tunnel /json/version unreachable", "Chromium failed to start", …).
     * Surfaced to the agent on `not_connected` so a connect failure is never
     * a bare code with an empty message again.
     */
    @Volatile
    private var lastConnectFailure: String? = null

    /** Human-readable reason for the most recent connect failure, if any. */
    fun lastConnectFailure(): String? = lastConnectFailure

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

            override fun onPageRecreated() {
                // The remote page target was re-created (e.g. the runner was
                // replaced): every DOM node id from earlier snapshots is stale.
                // Drop the cached refs so the next click/fill fails fast with
                // "take a new snapshot" instead of a mystery node error.
                snapshotRefs.clear()
                report("page_recreated", 0L, "ok", mapOf("backend" to (connectedMode?.persisted ?: "-")))
                DebugLog.w(TAG, "Page target recreated; snapshot refs invalidated")
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
        // Tag the CDP client so every error and log line names the backend
        // that actually failed — no more guessing which of the four it was.
        cdp.sessionTag = mode.persisted
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val ok = when (mode) {
            BrowserBackendMode.TUNNEL -> connectTunnel()
            BrowserBackendMode.WEBVIEW -> connectWebView()
        }
        val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
        if (ok) {
            connectedMode = mode
            lastConnectFailure = null
            startDownloadEventCollection()
            report("connect", elapsed, "ok", mapOf("backend" to mode.persisted))
        } else {
            report(
                "connect", elapsed, "error:connect_failed",
                mapOf("backend" to mode.persisted, "reason" to (lastConnectFailure ?: "?").take(80)),
            )
        }
        ok
    }

    /** Record a connect failure with a human-readable reason; returns false for `?:` chains. */
    private fun connectFailed(reason: String): Boolean {
        lastConnectFailure = reason
        DebugLog.w(TAG, reason)
        return false
    }

    /** Currently connected backend, or null when not connected. */
    fun currentBackendMode(): BrowserBackendMode? = connectedMode

    /** Close the CDP session. */
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

    /**
     * History back without closing the browser. WebView: native goBack.
     * CDP backends: Page.getNavigationHistory + navigateToHistoryEntry.
     * Returns true when a back navigation actually happened.
     */
    suspend fun goBack(timeoutMs: Long): Boolean {
        if (!ensureConnected()) return false
        if (connectedMode == BrowserBackendMode.WEBVIEW) {
            val wv = webViewBackend.liveWebView(sessionKey) ?: return false
            if (!wv.canGoBack()) return false
            // Must run on the main thread; block this worker until done.
            val done = kotlinx.coroutines.CompletableDeferred<Boolean>()
            wv.post {
                runCatching {
                    wv.goBack()
                    done.complete(true)
                }.onFailure { done.complete(false) }
            }
            return done.await()
        }
        return runCatching {
            val history = cdp.invoke(
                method = "Page.getNavigationHistory",
                timeoutMs = timeoutMs,
            )
            val entries = history["entries"] as? JsonArray ?: return false
            val currentIndex = (history["currentIndex"] as? JsonPrimitive)?.intOrNull ?: return false
            if (currentIndex <= 0 || currentIndex >= entries.size) return false
            val prev = entries[currentIndex - 1] as? JsonObject ?: return false
            val id = (prev["id"] as? JsonPrimitive)?.longOrNull ?: return false
            cdp.invoke(
                method = "Page.navigateToHistoryEntry",
                params = buildJsonObject { put("entryId", id) },
                timeoutMs = timeoutMs,
            )
            true
        }.getOrDefault(false)
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

    /**
     * Lightweight watch-stream frame: lower quality (faster encode + smaller
     * payload over the tunnel). Best-effort — throws on timeout so the caller
     * can skip the frame without treating it as a session failure.
     */
    suspend fun captureFrame(timeoutMs: Long): ByteArray {
        val result = cdp.invoke(
            method = "Page.captureScreenshot",
            params = buildJsonObject {
                put("format", "jpeg")
                put("quality", 35)
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

    // ── action cursor (Jev engine + manual tools report where they acted) ──

    /**
     * Last agent action point in CSS pixels, for the UI cursor overlay so the
     * user can see what the agent is doing. Null when unknown/cleared.
     * The watch panel maps it proportionally onto the screenshot frame
     * (the frame IS the viewport: captureBeyondViewport=false).
     */
    val lastActionPointFlow = kotlinx.coroutines.flow.MutableStateFlow<Pair<Double, Double>?>(null)

    /** Convenience accessor for [lastActionPointFlow]. */
    var lastActionPoint: Pair<Double, Double>?
        get() = lastActionPointFlow.value
        set(value) { lastActionPointFlow.value = value }

    /**
     * Record an action cursor with viewport fractions. Captures the viewport
     * size so the UI can position the cursor accurately. Best-effort: falls
     * back to storing raw pixels when metrics are unavailable.
     */
    suspend fun setActionCursor(x: Double, y: Double, timeoutMs: Long) {
        val viewport = runCatching {
            val metrics = cdp.invoke(
                method = "Page.getLayoutMetrics",
                params = buildJsonObject {},
                timeoutMs = timeoutMs,
            )
            val vp = ((metrics["layoutViewport"] as? JsonObject)
                ?: (metrics["cssLayoutViewport"] as? JsonObject))
            val w = (vp?.get("clientWidth") as? JsonPrimitive)?.doubleOrNull
                ?: (vp?.get("width") as? JsonPrimitive)?.doubleOrNull
            val h = (vp?.get("clientHeight") as? JsonPrimitive)?.doubleOrNull
                ?: (vp?.get("height") as? JsonPrimitive)?.doubleOrNull
            if (w != null && h != null && w > 0 && h > 0) w to h else null
        }.getOrNull()
        lastActionViewport = viewport
        lastActionPoint = x to y
    }

    /** Viewport (CSS px) captured with the last action cursor. */
    @Volatile
    var lastActionViewport: Pair<Double, Double>? = null

    /** Current page URL via Target.getTargetInfo; empty when unavailable. */
    suspend fun currentUrl(): String = runCatching {
        val info = cdp.invoke(
            method = "Target.getTargetInfo",
            params = buildJsonObject {},
            timeoutMs = 5_000L,
        )
        ((info["targetInfo"] as? JsonObject)?.get("url")
            as? JsonPrimitive)?.contentOrNull.orEmpty()
    }.getOrDefault("")

    /**
     * User takeover tap: [fx]/[fy] are fractions (0..1) of the viewport.
     * Maps to CSS pixels via Page.getLayoutMetrics and dispatches a click.
     * Used by the watch panel when the user drives the browser in takeover
     * mode on backends without a live embeddable view (tunnel screenshots).
     */
    suspend fun userTap(fx: Double, fy: Double, timeoutMs: Long) {
        val metrics = cdp.invoke(
            method = "Page.getLayoutMetrics",
            params = buildJsonObject {},
            timeoutMs = timeoutMs,
        )
        val viewport = ((metrics["layoutViewport"] as? JsonObject)
            ?: (metrics["cssLayoutViewport"] as? JsonObject))
        val w = (viewport?.get("clientWidth") as? JsonPrimitive)?.doubleOrNull
            ?: (viewport?.get("width") as? JsonPrimitive)?.doubleOrNull
            ?: 1280.0
        val h = (viewport?.get("clientHeight") as? JsonPrimitive)?.doubleOrNull
            ?: (viewport?.get("height") as? JsonPrimitive)?.doubleOrNull
            ?: 800.0
        val x = (fx * w).coerceIn(0.0, w)
        val y = (fy * h).coerceIn(0.0, h)
        lastActionPoint = x to y
        mouseClick(x, y, timeoutMs)
    }

    // ── downloads ──

    fun downloadStatus(): List<BrowserDownloadState> = downloads.values.toList()

    // ── backend connect ──

    private suspend fun connectTunnel(): Boolean {
        // Normalize: a stored URL with a trailing slash would produce
        // "//json/version" → 404 (Validate uses OkHttp's path builder which is
        // slash-safe, so it passed while connect failed).
        val rawUrl = prefs.tunnelUrl.value.trim().trimEnd('/')
        if (rawUrl.isBlank()) {
            return connectFailed("tunnel: no tunnel URL configured (enter it in browser settings)")
        }
        val tunnelUri = runCatching { Uri.parse(rawUrl) }.getOrNull()
        val host = tunnelUri?.host
        if (tunnelUri?.scheme != "https" || host.isNullOrBlank()) {
            // Security (scry): tunnel backend requires a user-supplied HTTPS URL.
            return connectFailed("tunnel: tunnel URL must be https")
        }
        val token = prefs.tunnelClientToken.value
        if (token.isBlank()) {
            return connectFailed("tunnel: client token not set (enter it in browser settings)")
        }
        // Health check before connect (§1.3.0): GET /json/version → 200.
        // The relay 401s without the client token, so it must travel here too
        // (same contract as the settings Validate probe).
        val version = httpGetJson(
            "$rawUrl/json/version?token=" + URLEncoder.encode(token, Charsets.UTF_8.name()),
            HEALTH_CHECK_TIMEOUT_MS,
        )
        if (version == null) {
            // Host-only in the message: the stored URL may carry a pasted
            // token as a query param, which must never reach logs or tools.
            return connectFailed(
                "tunnel: endpoint unreachable at https://$host/json/version " +
                    "(relay down, wrong URL, or wrong client token — the relay 401s without the token)",
            )
        }
        // Derive the debugger WS path from the version payload, then pin it to
        // the user's host with the client token. The token is never logged.
        val debuggerUrl = (version["webSocketDebuggerUrl"] as? JsonPrimitive)?.contentOrNull
        val path = debuggerUrl?.let { extractWsPath(it) } ?: "/devtools/browser"
        val separator = if (path.contains("?")) "&" else "?"
        val wsUrl = "wss://$host$path$separator" + "token=" +
            URLEncoder.encode(token, Charsets.UTF_8.name())
        if (!cdp.connect(wsUrl)) {
            return connectFailed(
                "tunnel: CDP websocket failed (relay answered HTTP but refused the WS upgrade — " +
                    "check the relay logs; the runner may be offline)",
            )
        }
        cdp.openPage("about:blank")
        // Remote downloads stay on the remote end; /tmp always exists and is
        // writable on the Linux runner. This is an OPTIONAL post-connect step:
        // it must never abort the connect (III.2) — a -32602 here used to
        // poison every tool call on the tunnel backend.
        runCatching {
            cdp.invoke(
                method = "Browser.setDownloadBehavior",
                params = buildJsonObject {
                    put("behavior", "allow")
                    put("downloadPath", "/tmp/agentx-downloads")
                },
                sessionId = null,
            )
        }.onFailure {
            DebugLog.w(TAG, "tunnel: setDownloadBehavior failed (non-fatal): ${it.message?.take(120)}")
        }
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
        if (!webViewBackend.ensureStarted(sessionKey)) {
            return connectFailed("webview: System WebView backend failed to start")
        }
        val port = webViewBackend.debugPort()
        // Per-session page target: each chat owns its own WebView, so two
        // chats never share (or fight over) one page.
        val targetId = webViewBackend.targetIdFor(sessionKey)
            ?: return connectFailed("webview: no page target for this chat's browser")
        val wsUrl = webViewTargetWsUrl(port, targetId)
        if (!cdp.connect(wsUrl)) {
            return connectFailed("webview: CDP websocket to WebView bridge failed")
        }
        // The bridge URL is target-scoped (/devtools/page/<id>): the socket
        // IS the page session. Drop any stale target/session inherited from a
        // previous backend, otherwise every command fails with -32001
        // "Session with given id not found" and reattach cannot heal it
        // (Target.* is invalid on a target-scoped connection).
        cdp.clearPageSession()
        // The WebView profile is persistent; downloads use the default
        // behavior (files land in the WebView profile dir). Optional step:
        // must never abort the connect (III.2).
        runCatching {
            cdp.invoke(
                method = "Browser.setDownloadBehavior",
                params = buildJsonObject { put("behavior", "default") },
                sessionId = null,
            )
        }.onFailure {
            DebugLog.w(TAG, "webview: setDownloadBehavior failed (non-fatal): ${it.message?.take(120)}")
        }
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
