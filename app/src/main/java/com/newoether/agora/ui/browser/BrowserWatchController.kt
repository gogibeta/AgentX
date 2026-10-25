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
    /**
     * The chat currently on screen. The chat UI sets this; the panel then
     * shows THAT chat's browser session, so two chats never share one browser.
     */
    val activeConversationId: kotlinx.coroutines.flow.MutableStateFlow<String?>
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
     * tunnel backend, which stays on the screenshot stream.
     */
    val liveWebView: StateFlow<android.webkit.WebView?>
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
    /**
     * Open the browser for the current chat (chat composer button): connects
     * the chat's session if needed and unhides the panel. The browser keeps
     * whatever page the chat's session already had.
     */
    fun onOpenBrowser()
    /** Non-null while a gated action awaits a decision. */
    val pendingApproval: StateFlow<BrowserApprovalRequest?>
    fun onStop()
    fun onTakeOver()
    fun onResume()
    /** History-back in the visible chat's browser; the session keeps running. */
    fun onGoBack()
    fun onApprove(requestId: String)
    fun onDeny(requestId: String)
    /**
     * Takeover tap on the screenshot stream: [fx]/[fy] are fractions (0..1)
     * of the viewport. Forwards to the session as a CDP click so the user
     * can drive tunnel-backend browsers that have no live embeddable view.
     */
    fun onUserTap(fx: Double, fy: Double)

    /**
     * Last agent action point in CSS pixels, for the cursor overlay.
     * The panel maps it proportionally onto the screenshot frame
     * (frame == viewport). Null when unknown. Lets the user see what the
     * AI is doing.
     */
    val actionCursor: StateFlow<Pair<Double, Double>?>

    /** All tab ids with a live session for the visible chat, sorted. */
    val tabs: StateFlow<List<String>>
    /** The currently active tab id for the visible chat. */
    val activeTabId: StateFlow<String>
    /** Open a new tab for the visible chat and switch to it. */
    fun onNewTab()
    /** Switch the visible chat's browser to an existing tab. */
    fun onSwitchTab(tabId: String)
    /** Close a tab of the visible chat. */
    fun onCloseTab(tabId: String)
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
