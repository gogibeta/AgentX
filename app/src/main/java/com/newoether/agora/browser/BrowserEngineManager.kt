package com.newoether.agora.browser

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Browser engine registry: every engine the app can drive, in one place.
 *
 * - SYSTEM_WEBVIEW: Android System WebView, built in, 0 MB. Visible +
 *   touchable in the watch panel.
 * - TUNNEL: cloud browser via the user's relay URL + token. Configured, not
 *   downloaded.
 *
 * (GeckoView and the sandbox Chromium were removed from this page: GeckoView
 * kept failing to start on-device, and the sandbox Chromium is managed only
 * as the shell tool's runtime, not as a browser engine.)
 *
 * The user picks the active engine in Settings → Browser.
 */
class BrowserEngineManager(
    private val prefs: BrowserPreferenceStore,
    private val scope: CoroutineScope,
) {
    /** Install state of one engine. */
    data class EngineState(
        val mode: BrowserBackendMode,
        val displayName: String,
        val description: String,
        val kind: EngineKind,
        val status: EngineStatus,
    )

    enum class EngineKind { BUILT_IN, CONFIGURED }

    enum class EngineStatus {
        /** Ready to select. */
        READY,
        /** Configured engine not yet set up (tunnel URL missing). */
        NOT_INSTALLED,
    }

    private val _engines = MutableStateFlow<List<EngineState>>(emptyList())
    val engines: StateFlow<List<EngineState>> = _engines.asStateFlow()

    init {
        refresh()
    }

    /** Recompute every engine's status. */
    fun refresh() {
        scope.launch(Dispatchers.IO) {
            _engines.value = listOf(
                EngineState(
                    mode = BrowserBackendMode.WEBVIEW,
                    displayName = "System WebView",
                    description = "Built into Android. Lightest, visible, touchable. Recommended.",
                    kind = EngineKind.BUILT_IN,
                    status = EngineStatus.READY,
                ),
                EngineState(
                    mode = BrowserBackendMode.TUNNEL,
                    displayName = "Cloud tunnel",
                    description = "Your own browser relay. Configure URL + token below.",
                    kind = EngineKind.CONFIGURED,
                    status = if (prefs.tunnelUrl.value.isNotBlank()) EngineStatus.READY else EngineStatus.NOT_INSTALLED,
                ),
            )
        }
    }

    /** Select the active engine. Takes effect on the next connect. */
    fun setActive(mode: BrowserBackendMode) {
        prefs.setBackendMode(mode)
    }
}
