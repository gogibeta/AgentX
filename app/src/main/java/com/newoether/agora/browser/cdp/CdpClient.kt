package com.newoether.agora.browser.cdp

import android.os.SystemClock
import com.newoether.agora.util.DebugLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
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
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Protocol/transport failure talking to the Chromium DevTools endpoint. */
class CdpException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A CDP event (a message with `method` and no `id`), e.g. `Page.loadEventFired`. */
data class CdpEvent(val method: String, val params: JsonObject)

/**
 * True when a CDP failure message means the page session died while the socket
 * may still be alive: stale/unknown session id, closed/destroyed target, or a
 * dead execution context. These are the errors that used to surface to the
 * agent as a bare `cdp_error(-32000)` on every subsequent tool call, forever —
 * the socket-level reconnect never ran because the socket was fine.
 */
internal fun isSessionInvalidError(message: String?): Boolean {
    if (message == null) return false
    if (message.startsWith("cdp_disconnected")) return true
    if (!message.startsWith("cdp_error(")) return false
    val code = message.substringAfter("cdp_error(", "").substringBefore(")", "").toIntOrNull()
    // -32001 is always session/target death ("Session with given id not found").
    if (code == -32001) return true
    if (code != -32000) return false
    val lower = message.lowercase()
    return SESSION_INVALID_MARKERS.any { it in lower }
}

private val SESSION_INVALID_MARKERS = listOf(
    "session", "target", "context", "not found", "no such", "closed",
    "destroyed", "crashed", "detached", "invalid id",
)

/** Lifecycle callbacks for the CDP socket; the session layer forwards these to diagnostics. */
interface CdpConnectionListener {
    fun onConnectionLost(reason: String) {}
    fun onReconnectAttempt(attempt: Int, nextDelayMs: Long) {}
    fun onReconnected(reattached: Boolean) {}

    /**
     * The page target had to be re-created (the old target is gone — e.g. the
     * remote Chromium was replaced). Every DOM node id from earlier snapshots
     * is stale; the session layer must drop cached snapshot refs so the agent
     * takes a fresh snapshot instead of clicking dead nodes.
     */
    fun onPageRecreated() {}
}

/** Outcome of healing a dead page session: re-attached to the same target, the
 * target was re-created (node ids are stale), or recovery failed entirely. */
enum class ReattachResult { REATTACHED, RECREATED, FAILED }

/**
 * OkHttp WebSocket JSON-RPC 2.0 client for the Chrome DevTools Protocol.
 *
 * - Socket keepalive: the [okHttp] client must be built with
 *   `pingInterval(~20s)` (see [defaultHttpClient]) — an idle CDP socket is the
 *   NORMAL state, never a dead one; it is never cut, only re-established on
 *   genuine failure (scry hardening).
 * - Auto-reconnect with backoff + target reattach: [ensureConnected] re-opens
 *   the socket and re-attaches to the stored target (or re-creates the page
 *   target when the browser went away).
 * - `sessionId` is sent as a top-level JSON-RPC field, per the CDP spec
 *   (it is NOT part of `params`).
 */
class CdpClient(
    private val okHttp: OkHttpClient,
    private val scope: CoroutineScope,
) {
    var connectionListener: CdpConnectionListener? = null

    /**
     * Backend that owns this client (`local`, `tunnel`, `webview`), set by
     * [BrowserSession] on connect. Attached to error messages and diagnostics
     * so a failure can be attributed without guessing.
     */
    @Volatile
    var sessionTag: String = "cdp"

    private val json = Json { ignoreUnknownKeys = true }
    private val nextId = AtomicLong(1)

    /** In-flight calls, keyed by JSON-RPC id. The method name is kept so error
     * messages can say WHICH command failed, not just the numeric code. */
    private val pending = ConcurrentHashMap<Long, PendingCall>()
    private data class PendingCall(val method: String, val deferred: CompletableDeferred<JsonObject>)
    private val socketMutex = Mutex()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var wsUrl: String? = null

    /** Last page target, for reattach after reconnect. */
    @Volatile
    private var targetId: String? = null

    @Volatile
    private var sessionId: String? = null

    @Volatile
    private var pageUrl: String? = null

    private val _events = MutableSharedFlow<CdpEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<CdpEvent> = _events.asSharedFlow()

    /** Timestamps of recent successful reconnects, for storm detection. */
    private val reconnectTimes = ArrayDeque<Long>()

    fun isConnected(): Boolean = socket != null

    /**
     * Forget any attached page target/session.
     *
     * Used when (re)connecting to a *target-scoped* DevTools endpoint
     * (`/devtools/page/<id>`, as served by the System WebView bridge): the
     * connection is already bound to the page, so commands must go out
     * sessionless. A stale session id inherited from a previous backend
     * (tunnel/local) would make every command fail with
     * `cdp_error(-32001): Session with given id not found`, and the reattach
     * path cannot heal it because `Target.*` is not valid on a target-scoped
     * connection. pageUrl is kept for diagnostics.
     */
    fun clearPageSession() {
        targetId = null
        sessionId = null
        DebugLog.d(TAG, "page session cleared (target-scoped endpoint)")
    }

    /** Open the debugger WebSocket. Idempotent; replaces any previous socket. */
    suspend fun connect(url: String): Boolean = socketMutex.withLock {
        connectLocked(url)
    }

    /**
     * Send one JSON-RPC command and await its response.
     *
     * Self-healing: when the failure signature says the page *session* died
     * (stale session id, closed target, dead execution context) the client
     * re-attaches to the stored target — recreating the page target when the
     * browser went away — and retries the command exactly once. This closes
     * the hole where every tool call after a renderer crash / tunnel-runner
     * restart / tab close failed forever with a bare `cdp_error(-32000)`
     * while the socket itself looked healthy.
     *
     * @param sessionId page-target session; null for browser-level domains
     *   (`Target.*`, `Browser.*`). Defaults to the attached page session.
     */
    suspend fun invoke(
        method: String,
        params: JsonObject = buildJsonObject {},
        sessionId: String? = this.sessionId,
        timeoutMs: Long = INVOKE_TIMEOUT_MS,
    ): JsonObject {
        if (!ensureConnected()) throw CdpException("cdp_not_connected")
        // Remember whether this call targeted the page session: the retry
        // must reuse a browser-level (null) session id verbatim instead of
        // forcing the page session onto `Target.*`/`Browser.*` commands.
        val usedPageSession = sessionId != null
        return try {
            invokeDirect(method, params, sessionId, timeoutMs)
        } catch (e: CdpException) {
            if (!isSessionInvalidError(e.message)) throw e
            DebugLog.w(
                TAG,
                "CDP session invalid during $method (backend=$sessionTag); " +
                    "re-attaching and retrying once: ${e.message?.take(160)}",
            )
            DebugLog.event(
                "CdpClient",
                mapOf(
                    "backend" to sessionTag,
                    "method" to method,
                    "stage" to "session_recover",
                    "error" to (e.message?.take(160) ?: ""),
                ),
                "CDP page session died; re-attaching",
            )
            val recovered = recoverSession()
            if (recovered == ReattachResult.FAILED) {
                DebugLog.event(
                    "CdpClient",
                    mapOf("backend" to sessionTag, "method" to method, "stage" to "recover_failed"),
                    "session recovery failed; surfacing original error",
                )
                throw e
            }
            if (recovered == ReattachResult.RECREATED) {
                // New page target: every DOM node id from earlier snapshots is
                // stale. Tell the session layer so cached refs are dropped.
                connectionListener?.onPageRecreated()
            }
            DebugLog.event(
                "CdpClient",
                mapOf("backend" to sessionTag, "method" to method, "stage" to "recovered_retry"),
                "session re-attached; retrying command once",
            )
            // Retry on the (possibly new) page session; a second failure
            // surfaces with full method + message detail.
            invokeDirect(
                method,
                params,
                if (usedPageSession) this.sessionId else null,
                timeoutMs,
            )
        }
    }

    /**
     * Heal a dead page session while the socket is alive: [ensureConnected]
     * already re-attaches when it had to reopen the socket; when the socket
     * was fine we re-attach (or recreate the page target) explicitly here.
     * Never throws — [ReattachResult.FAILED] means "could not heal".
     */
    private suspend fun recoverSession(): ReattachResult {
        if (!ensureConnected()) return ReattachResult.FAILED
        return try {
            socketMutex.withLock { reattachLocked() }
        } catch (_: Exception) {
            ReattachResult.FAILED
        }
    }

    /** invoke() without the connectivity check — for use inside the reconnect path. */
    private suspend fun invokeDirect(
        method: String,
        params: JsonObject,
        sessionId: String?,
        timeoutMs: Long,
    ): JsonObject {
        val id = nextId.getAndIncrement()
        val message = buildJsonObject {
            put("id", id)
            put("method", method)
            put("params", params)
            if (sessionId != null) put("sessionId", sessionId)
        }.toString()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = PendingCall(method, deferred)
        try {
            val current = socket ?: throw CdpException("cdp_not_connected")
            if (!current.send(message)) throw CdpException("cdp_send_failed")
            return withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            throw CdpException("cdp_timeout:$method", e)
        } finally {
            pending.remove(id)
        }
    }

    /**
     * Create a page target and attach, storing it for reconnect reattach.
     * Returns the new session id.
     */
    suspend fun openPage(url: String): String {
        if (!ensureConnected()) throw CdpException("cdp_not_connected")
        return openPageDirect(url)
    }

    /** openPage() without the connectivity check — for use inside the reconnect path. */
    private suspend fun openPageDirect(url: String): String {
        // Reuse a spare about:blank page when one exists instead of always
        // creating a target: a failed connect used to leak one about:blank
        // target per attempt on the remote end (III.2).
        val reusedTargetId = findSpareBlankPage()
        if (reusedTargetId != null) {
            DebugLog.d(TAG, "openPage: reusing existing about:blank target")
        }
        val newTargetId = reusedTargetId ?: run {
            val createResult = invokeDirect(
                method = "Target.createTarget",
                params = buildJsonObject { put("url", url) },
                sessionId = null,
                timeoutMs = INVOKE_TIMEOUT_MS,
            )
            (createResult["targetId"] as? JsonPrimitive)?.contentOrNull
                ?: throw CdpException("createTarget_missing_targetId")
        }
        val attachResult = invokeDirect(
            method = "Target.attachToTarget",
            params = buildJsonObject {
                put("targetId", newTargetId)
                put("flatten", true)
            },
            sessionId = null,
            timeoutMs = INVOKE_TIMEOUT_MS,
        )
        val newSessionId = (attachResult["sessionId"] as? JsonPrimitive)?.contentOrNull
            ?: throw CdpException("attach_missing_sessionId")
        targetId = newTargetId
        sessionId = newSessionId
        pageUrl = url
        return newSessionId
    }

    /**
     * Best-effort lookup of an unattached about:blank page target to reuse
     * instead of creating a new one. Returns null when the lookup fails or
     * no spare page exists — the caller then falls back to
     * Target.createTarget. Never throws.
     */
    private suspend fun findSpareBlankPage(): String? = runCatching {
        val result = invokeDirect(
            method = "Target.getTargets",
            params = buildJsonObject {},
            sessionId = null,
            timeoutMs = INVOKE_TIMEOUT_MS,
        )
        (result["targetInfos"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { info ->
                (info["type"] as? JsonPrimitive)?.contentOrNull == "page" &&
                    (info["url"] as? JsonPrimitive)?.contentOrNull == "about:blank" &&
                    (info["attached"] as? JsonPrimitive)?.contentOrNull != "true"
            }
            ?.let { (it["targetId"] as? JsonPrimitive)?.contentOrNull }
    }.getOrNull()

    /**
     * Reconnect with backoff and reattach to the stored target (scry: an idle
     * socket is normal — only reconnect on genuine failure). Returns true when
     * commands can be sent again.
     */
    suspend fun ensureConnected(): Boolean {
        if (isConnected()) return true
        val url = wsUrl ?: return false
        return socketMutex.withLock {
            if (socket != null) return true
            // Circuit breaker: if the socket keeps flapping (connect then
            // immediate close), stop the reconnect storm and report a stable
            // failure instead of hammering the relay.
            if (isReconnectStorm()) {
                DebugLog.w(TAG, "CDP reconnect storm detected; giving up to avoid hammering the relay")
                return false
            }
            for ((attempt, delayMs) in RECONNECT_DELAYS_MS.withIndex()) {
                if (attempt > 0) {
                    connectionListener?.onReconnectAttempt(attempt, delayMs)
                    DebugLog.d(TAG, "CDP reconnect attempt ${attempt + 1} in ${delayMs}ms")
                    delay(delayMs)
                }
                if (!connectLocked(url)) continue
                val reattachResult = reattachLocked()
                recordReconnect()
                connectionListener?.onReconnected(reattachResult != ReattachResult.FAILED)
                if (reattachResult == ReattachResult.RECREATED) {
                    connectionListener?.onPageRecreated()
                }
                DebugLog.d(TAG, "CDP reconnected (reattach=$reattachResult)")
                return true
            }
            DebugLog.w(TAG, "CDP reconnect gave up after ${RECONNECT_DELAYS_MS.size} attempts")
            false
        }
    }

    /**
     * True when too many reconnects happened too fast — the remote end is
     * closing the socket as fast as we open it. Back off entirely instead of
     * looping forever.
     */
    private fun isReconnectStorm(): Boolean {
        val now = SystemClock.elapsedRealtime()
        while (reconnectTimes.isNotEmpty() && now - reconnectTimes.first() > STORM_WINDOW_MS) {
            reconnectTimes.removeFirst()
        }
        return reconnectTimes.size >= STORM_MAX_RECONNECTS
    }

    private fun recordReconnect() {
        val now = SystemClock.elapsedRealtime()
        reconnectTimes.addLast(now)
        while (reconnectTimes.size > STORM_MAX_RECONNECTS) reconnectTimes.removeFirst()
    }

    suspend fun close() = socketMutex.withLock { closeLocked() }

    // ── internals ─────────────────────────────────────────────

    /** connect() without taking [socketMutex] (caller holds it). */
    private suspend fun connectLocked(url: String): Boolean {
        closeLocked()
        wsUrl = url
        val opened = CompletableDeferred<Boolean>()
        val request = Request.Builder().url(url).build()
        val webSocket = okHttp.newWebSocket(request, cdpListener(opened))
        return try {
            val ok = withTimeout(CONNECT_TIMEOUT_MS) { opened.await() }
            if (ok) {
                socket = webSocket
                DebugLog.d(TAG, "CDP socket open")
            } else {
                runCatching { webSocket.cancel() }
            }
            ok
        } catch (_: TimeoutCancellationException) {
            runCatching { webSocket.cancel() }
            DebugLog.w(TAG, "CDP connect timed out")
            false
        }
    }

    private fun cdpListener(opened: CompletableDeferred<Boolean>) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!opened.complete(true)) webSocket.close(1000, null)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleMessage(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            handleSocketGone(webSocket, "closed(code=$code)")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!opened.complete(false)) {
                handleSocketGone(webSocket, t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /** Reattach to the stored target after a fresh socket; recreate if it is gone. */
    private suspend fun reattachLocked(): ReattachResult {
        val knownTarget = targetId
        return try {
            if (knownTarget != null) {
                val targets = invokeDirect(
                    method = "Target.getTargets",
                    params = buildJsonObject {},
                    sessionId = null,
                    timeoutMs = 10_000,
                )
                val stillThere = (targets["targetInfos"] as? kotlinx.serialization.json.JsonArray)
                    ?.any {
                        ((it as? JsonObject)?.get("targetId") as? JsonPrimitive)?.contentOrNull == knownTarget
                    } == true
                if (stillThere) {
                    val attachResult = invokeDirect(
                        method = "Target.attachToTarget",
                        params = buildJsonObject {
                            put("targetId", knownTarget)
                            put("flatten", true)
                        },
                        sessionId = null,
                        timeoutMs = 10_000,
                    )
                    val newSession = (attachResult["sessionId"] as? JsonPrimitive)?.contentOrNull
                    if (newSession != null) {
                        sessionId = newSession
                        return ReattachResult.REATTACHED
                    }
                }
            }
            // Target is gone (browser restarted): recreate the page target.
            openPageDirect(pageUrl ?: "about:blank")
            ReattachResult.RECREATED
        } catch (e: Exception) {
            DebugLog.w(TAG, "CDP reattach failed: ${e.javaClass.simpleName}")
            ReattachResult.FAILED
        }
    }

    private fun handleMessage(text: String) {
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            return
        } ?: return
        val id = (root["id"] as? JsonPrimitive)?.longOrNull
        if (id != null) {
            val call = pending.remove(id) ?: return
            val error = root["error"] as? JsonObject
            if (error != null) {
                val message = (error["message"] as? JsonPrimitive)?.contentOrNull ?: "unknown"
                val code = (error["code"] as? JsonPrimitive)?.intOrNull
                // The method that failed travels with the error: the agent and
                // the log reader see WHAT failed, not just a numeric code.
                call.deferred.completeExceptionally(
                    CdpException("cdp_error($code):${call.method}: $message"),
                )
            } else {
                call.deferred.complete((root["result"] as? JsonObject) ?: buildJsonObject {})
            }
            return
        }
        val method = (root["method"] as? JsonPrimitive)?.contentOrNull ?: return
        val params = (root["params"] as? JsonObject) ?: buildJsonObject {}
        // Event fan-out must never block the socket reader.
        scope.launch(Dispatchers.Default) {
            _events.emit(CdpEvent(method, params))
        }
    }

    /**
     * Marks the socket dead and fails pending calls — but ONLY when the event
     * came from the currently-active socket. OkHttp may deliver a late
     * onClosed/onFailure from a previous socket after [connectLocked] already
     * installed its replacement; acting on it would kill the live connection
     * and fail the new socket's calls (stale-socket race).
     */
    private fun handleSocketGone(gone: WebSocket, reason: String) {
        if (socket !== gone) return
        socket = null
        val stale = pending.values.toList()
        pending.clear()
        val failure = CdpException("cdp_disconnected:$reason")
        stale.forEach { it.deferred.completeExceptionally(failure) }
        connectionListener?.onConnectionLost(reason)
        DebugLog.w(TAG, "CDP socket gone: $reason")
    }

    private fun closeLocked() {
        val current = socket
        socket = null
        if (current != null) {
            runCatching { current.close(1000, "client close") }
        }
        val stale = pending.values.toList()
        pending.clear()
        val failure = CdpException("cdp_closed")
        stale.forEach { it.deferred.completeExceptionally(failure) }
    }

    companion object {
        private const val TAG = "CdpClient"
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val INVOKE_TIMEOUT_MS = 30_000L

        /** Backoff ladder for reconnect attempts (delays before attempts 2..6). */
        private val RECONNECT_DELAYS_MS = longArrayOf(0L, 1_000L, 2_000L, 4_000L, 8_000L, 15_000L)

        /** Reconnect-storm circuit breaker: max reconnects per window before giving up. */
        private const val STORM_MAX_RECONNECTS = 5
        private const val STORM_WINDOW_MS = 30_000L

        /**
         * HTTP client tuned for CDP: WebSocket ping every ~20s keeps an idle
         * socket alive through NATs/proxies (an idle CDP socket is the NORMAL
         * state — never cut it).
         */
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket: no read timeout
            .build()
    }
}
