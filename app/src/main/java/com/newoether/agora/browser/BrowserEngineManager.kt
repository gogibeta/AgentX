package com.newoether.agora.browser

import android.content.Context
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Browser engine registry: every engine the app can drive, in one place.
 *
 * - SYSTEM_WEBVIEW: Android System WebView, built in, 0 MB. Visible +
 *   touchable in the watch panel.
 * - GECKOVIEW: Mozilla Gecko, built into the APK. Fast element-table
 *   observation (no screenshots in the agent loop), no automation flags.
 * - CHROMIUM: proot/Alpine Chromium sandbox. Downloadable — the Alpine
 *   rootfs + `apk add chromium`; uninstalling deletes the rootfs to free
 *   space. Heavy (~1 GB); kept until the lighter backends pass device tests.
 * - TUNNEL: cloud browser via the user's relay URL + token. Configured, not
 *   downloaded.
 *
 * The user picks the active engine in Settings → Browser; the manager tracks
 * install state so half-installed engines are never offered.
 */
class BrowserEngineManager(
    private val appContext: Context,
    private val prefs: BrowserPreferenceStore,
    private val chromiumLauncher: ChromiumLauncher,
    private val scope: CoroutineScope,
) {
    /** Install state of one engine. */
    data class EngineState(
        val mode: BrowserBackendMode,
        val displayName: String,
        val description: String,
        val kind: EngineKind,
        val status: EngineStatus,
        val sizeBytes: Long = 0L,
    )

    enum class EngineKind { BUILT_IN, DOWNLOADABLE, CONFIGURED }

    enum class EngineStatus {
        /** Ready to select. */
        READY,
        /** Downloadable engine not yet installed. */
        NOT_INSTALLED,
        /** Install/uninstall in progress. */
        WORKING,
        /** Last operation failed; retry is safe. */
        ERROR,
    }

    private val _engines = MutableStateFlow<List<EngineState>>(emptyList())
    val engines: StateFlow<List<EngineState>> = _engines.asStateFlow()

    init {
        refresh()
    }

    /** Recompute every engine's status. Call after install/uninstall. */
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
                    mode = BrowserBackendMode.GECKOVIEW,
                    displayName = "GeckoView",
                    description = "Mozilla engine (non-Chromium). Fast, no screenshots in the agent loop.",
                    kind = EngineKind.BUILT_IN,
                    status = EngineStatus.READY,
                ),
                EngineState(
                    mode = BrowserBackendMode.LOCAL,
                    displayName = "Chromium (sandbox)",
                    description = "Full Chromium in an Alpine sandbox. Heavy; needs ~1 GB free.",
                    kind = EngineKind.DOWNLOADABLE,
                    status = if (isChromiumInstalled()) EngineStatus.READY else EngineStatus.NOT_INSTALLED,
                    sizeBytes = chromiumInstallSize(),
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

    /**
     * Uninstall the Chromium sandbox (deletes the Alpine rootfs).
     * Frees ~1 GB. The engine becomes NOT_INSTALLED; reinstall re-downloads.
     * Never touches the WebView/Gecko profiles or the tunnel config.
     */
    suspend fun uninstallChromium(): Boolean = withContext(Dispatchers.IO) {
        setStatus(BrowserBackendMode.LOCAL, EngineStatus.WORKING)
        val ok = runCatching {
            val rootfs = File(appContext.filesDir, "alpine-rootfs")
            if (rootfs.exists()) rootfs.deleteRecursively()
            !File(appContext.filesDir, "alpine-rootfs").exists()
        }.getOrDefault(false)
        DebugLog.d(TAG, "uninstallChromium: ok=$ok")
        refresh()
        ok
    }

    /** True when the Alpine rootfs + Chromium are present. */
    fun isChromiumInstalled(): Boolean {
        val rootfs = File(appContext.filesDir, "alpine-rootfs")
        return File(rootfs, "bin/sh").exists()
    }

    /** Approximate on-disk size of the Chromium sandbox, 0 when absent. */
    fun chromiumInstallSize(): Long {
        val rootfs = File(appContext.filesDir, "alpine-rootfs")
        if (!rootfs.exists()) return 0L
        return runCatching { rootfs.walkTopDown().sumOf { it.length() } }.getOrDefault(0L)
    }

    private fun setStatus(mode: BrowserBackendMode, status: EngineStatus) {
        _engines.value = _engines.value.map {
            if (it.mode == mode) it.copy(status = status) else it
        }
    }

    companion object {
        private const val TAG = "BrowserEngineManager"
    }
}
