package com.newoether.agora.ui.browser

import androidx.compose.runtime.compositionLocalOf
import com.newoether.agora.data.BrowserDataController
import kotlinx.coroutines.flow.StateFlow

/**
 * A browser action that needs explicit user approval (download, form submit,
 * payment, first-visit domain, …). Rendered as a system-style dialog OUTSIDE
 * the chat message list — page text must never be able to manufacture a fake
 * approval inside the conversation (anti-prompt-injection, §1.1).
 */
data class BrowserApprovalRequest(
    val id: String,
    val title: String,
    val detail: String,
    val url: String,
)

/**
 * UI contract for the browser watch panel ([BrowserWatchPanel]).
 *
 * Stream A implements this by bridging [com.newoether.agora.browser.BrowserSession]
 * (CDP state, screenshot frames, takeover flag) and the approval policy engine;
 * the panel only reads these flows and forwards user intent through the
 * callbacks. The actual agent-loop pause/resume behind [onTakeOver]/[onResume]
 * is the `browser_takeover` tool + stream E's hooks — this interface is UI only.
 */
interface BrowserWatchController {
    /** True while a browser session/tool is active; the panel is hidden otherwise. */
    val sessionActive: StateFlow<Boolean>
    val pageUrl: StateFlow<String>
    val pageTitle: StateFlow<String>
    /** One-line action narration, e.g. "Clicked 'Add to cart' on example.com". */
    val narration: StateFlow<String>
    /**
     * Latest viewport JPEG frame. Stream A feeds this at ~2–4 fps from
     * `Page.captureScreenshot` (JPEG q~60, viewport only). ByteArray uses
     * referential equality, so every new frame is delivered.
     */
    val screenshot: StateFlow<ByteArray?>
    /**
     * Live WebView to embed directly when the connected backend is System
     * WebView. The panel shows this INSTEAD of screenshots: the browser is
     * truly visible and the user can touch it (take-control). Null for the
     * Chromium/tunnel backends, which stay on the screenshot stream.
     */
    val liveWebView: StateFlow<android.webkit.WebView?>
    /**
     * Live GeckoView to embed when the connected backend is GeckoView.
     * Same visible + touchable behavior as [liveWebView]. Null otherwise.
     */
    val liveGeckoView: StateFlow<android.view.View?>
    /** Take-over mode: the user drives the browser, the agent loop is paused. */
    val takeoverActive: StateFlow<Boolean>
    /**
     * The user hid the watch panel WITHOUT stopping the session: screenshots,
     * the live view, and the agent's browser tools keep running underneath.
     * A floating restore button brings the panel back.
     */
    val panelHidden: StateFlow<Boolean>
    /** Hide the panel but keep the browser session running. */
    fun onHidePanel()
    /** Bring back a hidden panel. */
    fun onShowPanel()
    /** Non-null while a gated action awaits a decision. */
    val pendingApproval: StateFlow<BrowserApprovalRequest?>
    fun onStop()
    fun onTakeOver()
    fun onResume()
    fun onApprove(requestId: String)
    fun onDeny(requestId: String)
}

/**
 * Provided at the app root by stream A once the browser engine exists.
 * Null until then — every consumer treats null as "no browser engine" and
 * renders nothing, so the UI compiles and ships unwired.
 */
val LocalBrowserWatchController = compositionLocalOf<BrowserWatchController?> { null }

/** Stream A's [com.newoether.agora.browser.BrowserPreferenceStore], once registered. */
val LocalBrowserPreferenceStore =
    compositionLocalOf<com.newoether.agora.browser.BrowserPreferenceStore?> { null }

/** Wipe hook for the persistent profile, once stream A registers it. */
val LocalBrowserDataController = compositionLocalOf<BrowserDataController?> { null }

/** Engine registry (install/uninstall/select), once registered. */
val LocalBrowserEngineManager =
    compositionLocalOf<com.newoether.agora.browser.BrowserEngineManager?> { null }
