package com.newoether.agora.ui.browser

import com.newoether.agora.browser.BrowserSessionRegistry
import com.newoether.agora.diagnostics.StructuredDiagnosticCategory
import com.newoether.agora.diagnostics.StructuredDiagnostics
import com.newoether.agora.security.ApprovalGate
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Concrete [BrowserWatchController] bridging [BrowserSession] (CDP state, screenshot
 * frames, takeover flag), [ApprovalGate] (pending approvals), and the structured
 * browser diagnostic events (activity detection + one-line narration).
 *
 * Activity/narration are derived from the §6.1 event pipeline rather than a second
 * bookkeeping channel: any `browser`-category event marks the session active, and
 * the latest event formats the narration line. This keeps the audit trail and the
 * watch panel as two views of the same truth.
 */
class DefaultBrowserWatchController(
    private val registry: BrowserSessionRegistry,
    private val gate: ApprovalGate,
    private val scope: CoroutineScope,
) : BrowserWatchController {

    /**
     * The chat currently on screen. The chat UI sets this; the panel then
     * shows THAT chat's browser session. Switching chats switches the panel
     * to the other chat's browser — sessions never overlap.
     */
    override val activeConversationId = MutableStateFlow<String?>(null)

    /** The browser session for the currently visible chat. Null-safe read:
     * uses peek() so merely viewing a chat never creates a session (B6). */
    private fun session() = registry.peek(activeConversationId.value)

    /** The session, creating it if needed (for actions like open/stop). */
    private fun sessionOrCreate() = registry.get(activeConversationId.value)

    private val _sessionActive = MutableStateFlow(false)
    override val sessionActive: StateFlow<Boolean> = _sessionActive.asStateFlow()

    private val _pageUrl = MutableStateFlow("")
    override val pageUrl: StateFlow<String> = _pageUrl.asStateFlow()

    private val _pageTitle = MutableStateFlow("")
    override val pageTitle: StateFlow<String> = _pageTitle.asStateFlow()

    private val _narration = MutableStateFlow("")
    override val narration: StateFlow<String> = _narration.asStateFlow()

    private val _screenshot = MutableStateFlow<ByteArray?>(null)
    override val screenshot: StateFlow<ByteArray?> = _screenshot.asStateFlow()

    private val _liveWebView = MutableStateFlow<android.webkit.WebView?>(null)
    override val liveWebView: StateFlow<android.webkit.WebView?> = _liveWebView.asStateFlow()

    private val _takeoverActive = MutableStateFlow(false)
    override val takeoverActive: StateFlow<Boolean> = _takeoverActive.asStateFlow()

    private val _pendingApproval = MutableStateFlow<BrowserApprovalRequest?>(null)
    override val pendingApproval: StateFlow<BrowserApprovalRequest?> =
        _pendingApproval.asStateFlow()

    /**
     * Panel hidden by the user (Hide button). The session, screenshot frames,
     * live views, and takeover state all keep running — only the card is
     * gone, until the floating restore button brings it back.
     */
    private val _panelHidden = MutableStateFlow(false)
    override val panelHidden: StateFlow<Boolean> = _panelHidden.asStateFlow()

    private var frameJob: Job? = null

    init {
        // Activity + narration from the browser event stream, filtered to the
        // visible chat: browser events carry the conversation id as sessionId
        // (BrowserDiagnostics.record), so the panel only lights up for the
        // chat on screen. Re-evaluates both on new events AND on chat switch,
        // so switching chats immediately swaps the panel to that chat's
        // browser session.
        scope.launch(Dispatchers.Default) {
            kotlinx.coroutines.flow.combine(
                StructuredDiagnostics.events,
                activeConversationId,
            ) { events, conversationId -> events to conversationId }
                .collect { (events, conversationId) ->
                    val last = events.lastOrNull {
                        it.category == StructuredDiagnosticCategory.BROWSER.wireName &&
                            (conversationId == null || it.sessionId == conversationId)
                    }
                    // B2: gate visibility on ACTUAL session state, not just
                    // event age. An idle-but-connected browser (no events in
                    // 60s) must not look dead after a chat switch.
                    val liveSession = session()
                    val connected = liveSession?.currentBackendMode() != null
                    if (last == null && !connected) {
                        _sessionActive.value = false
                        _liveWebView.value = null
                        stopFrames()
                        return@collect
                    }
                    val ageMs = if (last != null) System.currentTimeMillis() - last.ts else Long.MAX_VALUE
                    // Active if recent event OR actually connected.
                    val active = ageMs < SESSION_ACTIVE_WINDOW_MS || connected
                    _sessionActive.value = active
                    if (last != null) {
                        _narration.value = narrate(last.name, last.outcome, last.detail)
                        (last.detail["host"] ?: last.detail["url"])?.let { _pageUrl.value = it }
                    }
                    // Live view (System WebView backend): the panel embeds it
                    // directly instead of the screenshot stream.
                    _liveWebView.value = runCatching { liveSession?.liveWebView() }.getOrNull()
                    if (active) startFrames() else stopFrames()
                }
        }
        // Takeover flag + pending approvals.
        scope.launch(Dispatchers.Default) {
            while (isActive) {
                _takeoverActive.value = runCatching { session()?.isTakeoverActive() ?: false }
                    .getOrDefault(false)
                delay(1_000L)
            }
        }
        scope.launch(Dispatchers.Default) {
            gate.pendingRequest.collect { req ->
                _pendingApproval.value = req?.let {
                    BrowserApprovalRequest(
                        id = it.id,
                        title = it.kind.name.lowercase().replace('_', ' '),
                        detail = it.detail,
                        url = it.domain,
                    )
                }
            }
        }
    }

    private fun narrate(
        action: String,
        outcome: String,
        detail: Map<String, String>,
    ): String {
        val host = detail["host"] ?: detail["url"] ?: ""
        val what = when (action) {
            "navigate" -> "Navigated to $host"
            "snapshot" -> "Read page at $host"
            "click" -> "Clicked ${detail["ref"] ?: "element"} on $host"
            "fill" -> "Filled ${detail["ref"] ?: "field"} on $host"
            "screenshot" -> "Captured screenshot of $host"
            "connect" -> "Connected (${detail["backend"] ?: "browser"})"
            "backend_switch" -> "Switched backend (${detail["backend"] ?: ""})"
            "takeover_start" -> "Take-over started"
            "takeover_end" -> "Take-over ended"
            else -> action.replace('_', ' ')
        }
        return if (outcome == "ok") what else "$what — $outcome"
    }

    private fun startFrames() {
        if (frameJob?.isActive == true) return
        frameJob = scope.launch(Dispatchers.IO) {
            var consecutiveFailures = 0
            while (isActive && _sessionActive.value) {
                // Gate on a real, committed CDP session — not just recent
                // diagnostic events. A failed tool call still records an
                // event (making the session look "active") while no session
                // exists; capturing then only adds -32001 noise (III.2).
                val liveSession = session()
                if (liveSession?.currentBackendMode() == null) {
                    delay(FRAME_INTERVAL_MS)
                    continue
                }
                // Skip screenshot polling while a live view is embedded —
                // it renders itself and the user can touch it directly.
                if (_liveWebView.value == null) {
                    runCatching {
                        val frame = liveSession.captureScreenshot(FRAME_TIMEOUT_MS)
                        _screenshot.value = frame
                    }.onSuccess {
                        consecutiveFailures = 0
                    }.onFailure {
                        consecutiveFailures++
                        // The CDP session is dead (e.g. -32001 "Session with
                        // given id not found" after the tab closed). Stop
                        // spamming and mark the session disconnected instead
                        // of retrying forever.
                        if (consecutiveFailures >= MAX_FRAME_FAILURES) {
                            DebugLog.w(
                                TAG,
                                "watch frame failed $consecutiveFailures times in a row; " +
                                    "stopping frame polling: ${it.message}",
                            )
                            _sessionActive.value = false
                            stopFrames()
                            return@launch
                        }
                        DebugLog.d(TAG, "watch frame failed: ${it.message}")
                    }
                } else {
                    consecutiveFailures = 0
                }
                delay(FRAME_INTERVAL_MS)
            }
        }
    }

    private fun stopFrames() {
        frameJob?.cancel()
        frameJob = null
    }

    override fun onStop() {
        scope.launch(Dispatchers.IO) {
            // Stop kills THIS chat's browser session only; other chats'
            // sessions keep running untouched.
            runCatching { registry.close(activeConversationId.value) }
            _sessionActive.value = false
            _panelHidden.value = false
            _liveWebView.value = null
            // B7: clear stale content so the panel never shows the previous
            // session's URL/title/frame after Stop.
            _screenshot.value = null
            _pageUrl.value = ""
            _pageTitle.value = ""
            _narration.value = ""
            _takeoverActive.value = false
            _pendingApproval.value = null
            stopFrames()
        }
    }

    /** History-back in the visible chat's browser; the session keeps running. */
    override fun onGoBack() {
        scope.launch(Dispatchers.IO) {
            val wentBack = runCatching {
                session()?.goBack(FRAME_TIMEOUT_MS) ?: false
            }.getOrDefault(false)
            DebugLog.d(TAG, "browser back: $wentBack")
        }
    }

    override fun onHidePanel() {
        // Hide only: the session, frame polling, and takeover state are
        // untouched, so the browser keeps running in the background.
        _panelHidden.value = true
        DebugLog.d(TAG, "Watch panel hidden (session keeps running)")
    }

    override fun onShowPanel() {
        _panelHidden.value = false
        DebugLog.d(TAG, "Watch panel restored")
    }

    override fun onOpenBrowser() {
        _panelHidden.value = false
        scope.launch(Dispatchers.IO) {
            // Connect the chat's session (no-op when already connected).
            // The connect reports a diagnostic event, which flips
            // sessionActive and makes the panel appear.
            // Use sessionOrCreate: opening the browser IS a creation action.
            val s = sessionOrCreate()
            val ok = runCatching { s.ensureConnected() }
                .onFailure { DebugLog.w(TAG, "openBrowser connect failed: ${it.message}") }
                .getOrDefault(false)
            if (ok) {
                // Manual open (no agent running): no diagnostic events will
                // arrive to refresh the live view, so push it directly.
                _liveWebView.value = runCatching { s.liveWebView() }.getOrNull()
                _sessionActive.value = true
                startFrames()
            } else {
                // B8: surface the failure instead of an eternal placeholder.
                // The dialog observes sessionActive=false + narration to show
                // the hint; record the connect failure for display.
                _narration.value = s.lastConnectFailure()
                    ?: "Could not start the browser. Check Settings → Browser."
            }
        }
        DebugLog.d(TAG, "Browser opened for chat")
    }

    override fun onTakeOver() {
        session()?.setTakeover(true)
    }

    override fun onResume() {
        session()?.setTakeover(false)
    }

    override fun onApprove(requestId: String) {
        gate.respond(requestId, true)
    }

    override fun onDeny(requestId: String) {
        gate.respond(requestId, false)
    }

    private companion object {
        const val TAG = "BrowserWatch"
        const val SESSION_ACTIVE_WINDOW_MS = 60_000L
        const val FRAME_INTERVAL_MS = 500L // ~2 fps live stream
        const val FRAME_TIMEOUT_MS = 8_000L
        /** Stop frame polling after this many consecutive failures (dead CDP session). */
        const val MAX_FRAME_FAILURES = 5
    }
}
