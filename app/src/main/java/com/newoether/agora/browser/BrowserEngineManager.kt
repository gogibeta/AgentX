package com.newoether.agora.browser

import android.content.Context
import com.newoether.agora.sandbox.SandboxManagerFactory
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
    private val sandboxFactory: SandboxManagerFactory? = null,
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
                    description = "Full Chromium in the Alpine sandbox (also powers the shell tool). Heavy; needs ~1 GB free.",
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
     * Install (or reinstall) the Chromium sandbox: downloads and extracts the
     * Alpine rootfs via the shared SandboxManager, then installs the chromium
     * package and smoke-tests it before reporting ok.
     *
     * chromium lives in the Alpine **community** repo, not main (III.3) — the
     * repo must be added and `apk update` run first. apk's stderr is surfaced
     * in the diagnostics; ok=true is only reported when the binary exists
     * AND answers --version.
     */
    suspend fun installChromium(): Boolean = withContext(Dispatchers.IO) {
        setStatus(BrowserBackendMode.LOCAL, EngineStatus.WORKING)
        var failureReason = ""
        val ok = runCatching {
            val mgr = sandboxFactory?.takeIf { it.isAvailable() }?.create()
                ?: return@runCatching false.also { failureReason = "sandbox unavailable" }
            mgr.installRootfs()
            // Wait for the fire-and-forget install to finish (up to ~10 min).
            val deadline = System.currentTimeMillis() + 10 * 60 * 1000L
            while (mgr.isInstallingRootfs.value && System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(1000)
            }
            // Community repo first: chromium is not in main.
            val repoCmd = "grep -q 'alpine/v3.21/community' /etc/apk/repositories || " +
                "echo 'https://dl-cdn.alpinelinux.org/alpine/v3.21/community' >> /etc/apk/repositories"
            val repo = mgr.executeCommand(repoCmd, "", 30_000)
            if (repo.exitCode != 0) {
                failureReason = "apk repo setup failed: ${repo.stderr.take(200)}"
                return@runCatching false
            }
            val update = mgr.executeCommand("apk update", "", 120_000)
            if (update.exitCode != 0) {
                failureReason = "apk update failed: ${update.stderr.take(200)}"
                return@runCatching false
            }
            val install = mgr.executeCommand("apk add chromium", "", 600_000)
            if (install.exitCode != 0) {
                failureReason = "apk add chromium failed (exit=${install.exitCode}): " +
                    "${install.stderr.take(300)}"
                DebugLog.w(TAG, "installChromium: $failureReason")
                return@runCatching false
            }
            if (!isChromiumInstalled()) {
                failureReason = "apk reported success but /usr/bin/chromium is missing"
                return@runCatching false
            }
            // Smoke test: the binary must answer --version.
            val smoke = mgr.executeCommand("chromium --version", "", 30_000)
            if (smoke.exitCode != 0) {
                failureReason = "chromium smoke test failed: ${smoke.stderr.take(200)}"
                return@runCatching false
            }
            true
        }.getOrDefault(false)
        DebugLog.d(
            TAG,
            "installChromium: ok=$ok" +
                (if (failureReason.isNotBlank()) " reason=$failureReason" else ""),
        )
        setStatus(
            BrowserBackendMode.LOCAL,
            if (ok) EngineStatus.READY else EngineStatus.ERROR,
        )
        refresh()
        ok
    }

    /**
     * Uninstall Chromium only (`apk del`). NEVER deletes the shared Alpine
     * rootfs — it is also the shell tool's sandbox; wiping it destroyed the
     * user's installed packages (III.3 / Bug 6).
     */
    suspend fun uninstallChromium(): Boolean = withContext(Dispatchers.IO) {
        setStatus(BrowserBackendMode.LOCAL, EngineStatus.WORKING)
        val ok = runCatching {
            val mgr = sandboxFactory?.takeIf { it.isAvailable() }?.create()
                ?: return@runCatching false
            val del = mgr.executeCommand("apk del chromium", "", 120_000)
            if (del.exitCode != 0) {
                DebugLog.w(TAG, "uninstallChromium: apk del failed: ${del.stderr.take(200)}")
            }
            !isChromiumInstalled()
        }.getOrDefault(false)
        DebugLog.d(TAG, "uninstallChromium: ok=$ok")
        refresh()
        ok
    }

    /** True when the chromium binary is actually present (not just bin/sh). */
    fun isChromiumInstalled(): Boolean {
        val rootfs = File(appContext.filesDir, "alpine-rootfs")
        return File(rootfs, "usr/bin/chromium").exists()
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
