package com.newoether.agora.ui.browser

import com.newoether.agora.browser.BrowserSession
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
    private val session: BrowserSession,
    private val gate: ApprovalGate,
    private val scope: CoroutineScope,
) : BrowserWatchController {

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

    private val _liveGeckoView = MutableStateFlow<android.view.View?>(null)
    override val liveGeckoView: StateFlow<android.view.View?> = _liveGeckoView.asStateFlow()

    private val _takeoverActive = MutableStateFlow(false)
    override val takeoverActive: StateFlow<Boolean> = _takeoverActive.asStateFlow()

    private val _pendingApproval = MutableStateFlow<BrowserApprovalRequest?>(null)
    override val pendingApproval: StateFlow<BrowserApprovalRequest?> =
        _pendingApproval.asStateFlow()

    private var frameJob: Job? = null

    init {
        // Activity + narration from the browser event stream.
        scope.launch(Dispatchers.Default) {
            StructuredDiagnostics.events.collect { events ->
                val last = events.lastOrNull {
                    it.category == StructuredDiagnosticCategory.BROWSER.wireName
                }
                if (last == null) {
                    _sessionActive.value = false
                    return@collect
                }
                val ageMs = System.currentTimeMillis() - last.ts
                val active = ageMs < SESSION_ACTIVE_WINDOW_MS
                _sessionActive.value = active
                _narration.value = narrate(last.name, last.outcome, last.detail)
                (last.detail["host"] ?: last.detail["url"])?.let { _pageUrl.value = it }
                // Live views (System WebView / GeckoView backends): the panel embeds
                // them directly instead of the screenshot stream.
                _liveWebView.value = runCatching { session.liveWebView() }.getOrNull()
                _liveGeckoView.value = runCatching { session.liveGeckoView() }.getOrNull()
                if (active) startFrames() else stopFrames()
            }
        }
        // Takeover flag + pending approvals.
        scope.launch(Dispatchers.Default) {
            while (isActive) {
                _takeoverActive.value = runCatching { session.isTakeoverActive() }
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
            while (isActive && _sessionActive.value) {
                // Skip screenshot polling while a live view is embedded —
                // it renders itself and the user can touch it directly.
                if (_liveWebView.value == null && _liveGeckoView.value == null) {
                    runCatching {
                        val frame = session.captureScreenshot(FRAME_TIMEOUT_MS)
                        _screenshot.value = frame
                    }.onFailure {
                        DebugLog.d(TAG, "watch frame failed: ${it.message}")
                    }
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
            runCatching { session.close() }
            _sessionActive.value = false
            stopFrames()
        }
    }

    override fun onTakeOver() {
        session.setTakeover(true)
    }

    override fun onResume() {
        session.setTakeover(false)
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
    }
}
