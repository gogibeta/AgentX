package com.newoether.agora.browser.cdp

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

/** Lifecycle callbacks for the CDP socket; the session layer forwards these to diagnostics. */
interface CdpConnectionListener {
    fun onConnectionLost(reason: String) {}
    fun onReconnectAttempt(attempt: Int, nextDelayMs: Long) {}
    fun onReconnected(reattached: Boolean) {}
}

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

    private val json = Json { ignoreUnknownKeys = true }
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
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

    fun isConnected(): Boolean = socket != null

    /** Open the debugger WebSocket. Idempotent; replaces any previous socket. */
    suspend fun connect(url: String): Boolean = socketMutex.withLock {
        connectLocked(url)
    }

    /**
     * Send one JSON-RPC command and await its response.
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
        return invokeDirect(method, params, sessionId, timeoutMs)
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
        pending[id] = deferred
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
        val createResult = invokeDirect(
            method = "Target.createTarget",
            params = buildJsonObject { put("url", url) },
            sessionId = null,
            timeoutMs = INVOKE_TIMEOUT_MS,
        )
        val newTargetId = (createResult["targetId"] as? JsonPrimitive)?.contentOrNull
            ?: throw CdpException("createTarget_missing_targetId")
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
     * Reconnect with backoff and reattach to the stored target (scry: an idle
     * socket is normal — only reconnect on genuine failure). Returns true when
     * commands can be sent again.
     */
    suspend fun ensureConnected(): Boolean {
        if (isConnected()) return true
        val url = wsUrl ?: return false
        return socketMutex.withLock {
            if (socket != null) return true
            for ((attempt, delayMs) in RECONNECT_DELAYS_MS.withIndex()) {
                if (attempt > 0) {
                    connectionListener?.onReconnectAttempt(attempt, delayMs)
                    DebugLog.d(TAG, "CDP reconnect attempt ${attempt + 1} in ${delayMs}ms")
                    delay(delayMs)
                }
                if (!connectLocked(url)) continue
                val reattached = reattachLocked()
                connectionListener?.onReconnected(reattached)
                DebugLog.d(TAG, "CDP reconnected (reattached=$reattached)")
                return true
            }
            DebugLog.w(TAG, "CDP reconnect gave up after ${RECONNECT_DELAYS_MS.size} attempts")
            false
        }
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
            handleSocketGone("closed(code=$code)")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!opened.complete(false)) {
                handleSocketGone(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /** Reattach to the stored target after a fresh socket; recreate if it is gone. */
    private suspend fun reattachLocked(): Boolean {
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
                        return true
                    }
                }
            }
            // Target is gone (browser restarted): recreate the page target.
            openPageDirect(pageUrl ?: "about:blank")
            true
        } catch (e: Exception) {
            DebugLog.w(TAG, "CDP reattach failed: ${e.javaClass.simpleName}")
            false
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
            val deferred = pending.remove(id) ?: return
            val error = root["error"] as? JsonObject
            if (error != null) {
                val message = (error["message"] as? JsonPrimitive)?.contentOrNull ?: "unknown"
                val code = (error["code"] as? JsonPrimitive)?.intOrNull
                deferred.completeExceptionally(CdpException("cdp_error($code):$message"))
            } else {
                deferred.complete((root["result"] as? JsonObject) ?: buildJsonObject {})
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

    private fun handleSocketGone(reason: String) {
        if (socket == null) return
        socket = null
        val stale = pending.values.toList()
        pending.clear()
        val failure = CdpException("cdp_disconnected:$reason")
        stale.forEach { it.completeExceptionally(failure) }
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
        stale.forEach { it.completeExceptionally(failure) }
    }

    companion object {
        private const val TAG = "CdpClient"
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val INVOKE_TIMEOUT_MS = 30_000L

        /** Backoff ladder for reconnect attempts (delays before attempts 2..6). */
        private val RECONNECT_DELAYS_MS = longArrayOf(0L, 1_000L, 2_000L, 4_000L, 8_000L, 15_000L)

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
