package com.newoether.agora.browser

import android.app.ActivityManager
import android.content.Context
import com.newoether.agora.data.BrowserDataController
import com.newoether.agora.util.DebugLog
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Sandbox Chromium runtime for the SHELL tool (no longer a browser backend —
 * the browser page offers only System WebView + cloud tunnel since beta11).
 * One long-lived Chromium with a persistent profile.
 *
 * Scry-model rules (§1.3.1, adopted 2026-10-01):
 * - ONE persistent `--user-data-dir` under app-private storage
 *   (`files/browser-profile/`), never per-session temp dirs. Cookies, logins,
 *   localStorage and IndexedDB survive app restarts.
 * - On startup, clear a stale `SingletonLock`/`SingletonSocket` left by an
 *   unclean shutdown so Chromium re-opens the existing profile instead of failing.
 * - Exactly ONE long-lived Chromium process; its lifecycle is never tied to a
 *   task or UI session. Never restarted per task, never wiped except via the
 *   explicit, confirm-gated [clearBrowserData] (Settings → Browser → Clear
 *   browser data, user-initiated only).
 * - Session-scoped cookies live in memory — mitigation is avoiding restarts:
 *   lenient health checks, never kill a healthy browser.
 * - Downloads go to an app-owned dir (`files/browser-downloads/`) that is never
 *   auto-cleaned.
 *
 * CDP-stability hardening (scry, trace-learned): `--disable-background-timer-throttling`,
 * `--disable-backgrounding-occluded-windows`, `--disable-renderer-backgrounding`,
 * `--disable-dev-shm-usage` (+ `--disable-gpu`); a headful-but-never-foreground
 * browser would otherwise be throttled/suspended and look unstable to the agent.
 *
 * Security (scry): CDP is remote code execution — binds `127.0.0.1` ONLY, never
 * `0.0.0.0`. proot shares the network namespace, so the debug port is reachable
 * from the app process on loopback.
 *
 * Process model: the launcher owns its own long-lived proot invocation via
 * [ProcessBuilder] (mirroring `ProotSandboxManager.executeRaw`'s argument shape)
 * rather than `SandboxManager.executeCommand`, because the sandbox manager
 * serializes every command on its rootfs mutation mutex — a browser living for
 * hours must not block the shell tool. The extra `--bind` mounts expose the
 * host profile/downloads dirs inside the sandbox.
 */
class ChromiumLauncher(
    private val appContext: Context,
    private val healthHttp: OkHttpClient,
    private val scope: CoroutineScope,
    private val debugPort: Int = DEFAULT_DEBUG_PORT,
) : BrowserDataController {
    /** Host-side persistent profile dir (bound into the sandbox at [/SANDBOX_PROFILE_PATH]). */
    val profileDir: File = File(appContext.filesDir, PROFILE_DIR_NAME)

    /** Host-side app-owned download dir (bound at [/SANDBOX_DOWNLOAD_PATH], never auto-cleaned). */
    val downloadsDir: File = File(appContext.filesDir, DOWNLOAD_DIR_NAME)

    fun debugPort(): Int = debugPort

    private val startMutex = Mutex()

    @Volatile
    private var process: Process? = null

    @Volatile
    private var monitorJob: Job? = null

    /**
     * Idempotent start. Returns true when a healthy Chromium answers on the
     * debug port — adopting an already-running one (e.g. after an app restart)
     * rather than killing it. Never kills a healthy browser.
     */
    suspend fun ensureStarted(): Boolean = startMutex.withLock {
        val current = process
        if (current != null && current.isAlive && isHealthy()) return true
        if (current == null && isHealthy()) {
            // Chromium outlived the app process (or was started externally):
            // adopt it. Session cookies survive because we never restart.
            DebugLog.d(TAG, "Adopting already-running Chromium on 127.0.0.1:$debugPort")
            return true
        }
        stopLocked()
        lastStartFailure = null
        if (!hasEnoughMemory()) {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val info = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(info)
            val freeMb = (info.availMem / (1024 * 1024)).toString()
            lastStartFailure = "only ${freeMb}MB free RAM (< 1536MB): Chromium under proot is " +
                "not viable on this device right now (LMK would SIGKILL it)"
            DebugLog.w(TAG, "ensureStarted: $lastStartFailure")
            return false
        }
        if (!isSandboxReady()) {
            lastStartFailure = "sandbox rootfs not installed"
            DebugLog.w(TAG, "ensureStarted: sandbox rootfs not installed; browser unavailable")
            return false
        }
        clearStaleProfileLocks()
        if (!launchLocked()) {
            lastStartFailure = "proot launch failed (see log)"
            return false
        }
        // Lenient startup: poll for the debug endpoint instead of failing fast.
        // Liveness-aware: a live-but-slow process keeps its full 90s window;
        // only a dead process aborts early.
        repeat(STARTUP_POLL_ATTEMPTS) { attempt ->
            delay(STARTUP_POLL_INTERVAL_MS)
            if (isHealthy()) return true
            val p = process
            if (p == null || !p.isAlive) {
                lastStartFailure = "Chromium process died during startup (exit=${p?.exitValue()})"
                DebugLog.w(TAG, lastStartFailure!!)
                stopLocked()
                return false
            }
            if (attempt > 0 && attempt % 60 == 0) {
                DebugLog.d(TAG, "Chromium still starting (${attempt * STARTUP_POLL_INTERVAL_MS}ms elapsed, process alive)")
            }
        }
        lastStartFailure = "Chromium did not answer /json/version within 90s"
        DebugLog.w(TAG, lastStartFailure!!)
        // Do NOT leave a half-started browser running: a process that cannot
        // bind DevTools in the window never becomes usable, and orphans pile
        // up and strain the device. Kill it; the next attempt starts clean.
        stopLocked()
        return false
    }

    /** Stops the Chromium process. The persistent profile is left untouched. */
    suspend fun stop() = startMutex.withLock { stopLocked() }

    /**
     * Explicit, confirm-gated wipe of the persistent profile. Implements
     * [BrowserDataController] — called only from Settings → Browser → Clear
     * browser data (user-initiated). The tunnel backend's remote profile is
     * out of scope — this only wipes local data.
     */
    override suspend fun clearBrowserData(): Boolean = startMutex.withLock {
        stopLocked()
        return try {
            if (profileDir.exists() && !profileDir.deleteRecursively()) {
                DebugLog.e(TAG, "clearBrowserData: could not delete ${profileDir.absolutePath}")
                return false
            }
            profileDir.mkdirs()
            DebugLog.d(TAG, "Browser profile cleared")
            true
        } catch (e: Exception) {
            DebugLog.e(TAG, "clearBrowserData failed", e)
            false
        }
    }

    /** Lightweight liveness probe: GET /json/version must answer 200 with a debugger URL. */
    suspend fun isHealthy(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("http://127.0.0.1:$debugPort/json/version")
                .header("User-Agent", BROWSER_HEALTH_USER_AGENT)
                .build()
            healthHttp.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                val body = response.body?.string().orEmpty()
                body.contains("webSocketDebuggerUrl")
            }
        } catch (_: Exception) {
            false
        }
    }

    // ── internals ─────────────────────────────────────────────

    private fun isSandboxReady(): Boolean {
        val rootfsDir = File(appContext.filesDir, "alpine-rootfs")
        return File(rootfsDir, "bin/sh").exists()
    }

    /**
     * Remove stale Chromium singleton locks from an unclean shutdown so the
     * existing profile re-opens instead of failing. The profile dir lives on
     * the host filesystem, so plain [File] ops suffice — no sandbox round-trip.
     */
    private fun clearStaleProfileLocks() {
        if (!profileDir.exists()) {
            profileDir.mkdirs()
            downloadsDir.mkdirs()
            return
        }
        var cleared = 0
        for (name in STALE_LOCK_FILES) {
            if (File(profileDir, name).delete()) cleared++
        }
        if (cleared > 0) DebugLog.d(TAG, "Cleared $cleared stale profile lock file(s)")
        downloadsDir.mkdirs()
    }

    /**
     * Kill orphaned Chromium processes left in the sandbox by previous crashes.
     * A half-started Chromium can hold the debug port without ever answering
     * /json/version (ProcessSingleton also swallows relaunches); clearing them
     * first is what makes the next launch actually bind.
     */
    private fun killStaleSandboxChromium(filesDir: File, rootfsDir: File) {
        try {
            val libDir = appContext.applicationInfo.nativeLibraryDir
            val prootBin = "$libDir/libproot_exec.so"
            if (!File(prootBin).exists() || !rootfsDir.exists()) return
            val args = listOf(
                prootBin,
                "--rootfs=" + rootfsDir.absolutePath,
                "-0", "-L",
                "/bin/sh", "-c",
                "pkill -f 'chromium.*remote-debugging-port' 2>/dev/null; exit 0",
            )
            val builder = ProcessBuilder(args).redirectErrorStream(true)
            val proc = builder.start()
            proc.waitFor(10, TimeUnit.SECONDS)
            proc.destroyForcibly()
        } catch (_: Exception) {
            // Best-effort only; launch proceeds regardless.
        }
    }

    private fun launchLocked(): Boolean {
        return try {
            val filesDir = appContext.filesDir
            val rootfsDir = File(filesDir, "alpine-rootfs")
            killStaleSandboxChromium(filesDir, rootfsDir)
            val libDir = appContext.applicationInfo.nativeLibraryDir
            val prootBin = "$libDir/libproot_exec.so"
            if (!File(prootBin).exists()) {
                DebugLog.e(TAG, "launch: proot binary missing at $prootBin")
                return false
            }
            // Mirror ProotSandboxManager.ensureTalloc: the Android linker resolves
            // by exact filename, but proot's DT_NEEDED is libtalloc.so.2.
            val tallocDir = File(filesDir, "lib").apply { mkdirs() }
            val tallocSrc = File(libDir, "libtalloc.so")
            val tallocDst = File(tallocDir, "libtalloc.so.2")
            if (!tallocDst.exists() && tallocSrc.exists()) {
                runCatching { tallocSrc.copyTo(tallocDst) }
            }

            val chromiumCmd = buildString {
                append("command -v chromium >/dev/null 2>&1 || { echo CHROMIUM_MISSING >&2; exit 3; }; ")
                append("exec chromium --headless=new ")
                append("--no-sandbox ") // proot runs as root (-0); Chromium's own sandbox needs namespaces unavailable here
                append("--disable-setuid-sandbox ")
                append("--no-zygote ") // proot cannot trap the zygote's clone(); without this the browser dies on spawn
                append("--disable-gpu ")
                append("--disable-gpu-process-crash-limit ") // GPU child crash-loops FATAL after 6 respawns under proot
                append("--disable-software-rasterizer ")
                // NOTE: --remote-debugging-port takes a bare port number ONLY.
                // "127.0.0.1:9333" fails to parse and DevTools never binds
                // (local browser was completely unusable until this was fixed).
                append("--remote-debugging-port=").append(debugPort).append(' ')
                append("--remote-allow-origins=* ") // Chrome 111+ gates WS origins; loopback bind keeps this local-only
                append("--user-data-dir=").append(SANDBOX_PROFILE_PATH).append(' ')
                // Scry stability flags: a never-foreground browser must not be throttled/suspended.
                append("--disable-background-timer-throttling ")
                append("--disable-backgrounding-occluded-windows ")
                append("--disable-renderer-backgrounding ")
                append("--disable-dev-shm-usage ")
                append("--no-first-run --no-default-browser-check ")
                append("about:blank")
            }
            val args = listOf(
                prootBin,
                "--rootfs=" + rootfsDir.absolutePath,
                "--bind=/dev",
                "--bind=/proc",
                "--bind=/sys",
                "--bind=/dev/urandom:/dev/random",
                "--bind=" + File(filesDir, "sandbox-home").absolutePath + ":/home/agora",
                "--bind=" + profileDir.absolutePath + ":" + SANDBOX_PROFILE_PATH,
                "--bind=" + downloadsDir.absolutePath + ":" + SANDBOX_DOWNLOAD_PATH,
                "-w", "/home/agora",
                // NOTE: --link2symlink intentionally NOT passed. It emulates
                // symlinks as regular files, which breaks Chromium's
                // ProcessSingleton lock protocol (SingletonSocket is a
                // symlink) → "Failed to create socket directory" abort (III.3).
                "-0", "--kill-on-exit", "-L",
                "/bin/sh", "-c", chromiumCmd,
            )
            val builder = ProcessBuilder(args).redirectErrorStream(true)
            val env = builder.environment()
            env["LD_LIBRARY_PATH"] = tallocDir.absolutePath + ":" + libDir
            env["PROOT_LOADER"] = "$libDir/libproot_loader.so"
            env["PROOT_TMP_DIR"] = File(rootfsDir, "tmp").apply { mkdirs() }.absolutePath
            env["HOME"] = "/home/agora"
            env["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            val started = builder.start()
            process = started
            // Drain the combined output so a chatty Chromium can never block on a
            // full pipe. Content is discarded: it may contain page URLs.
            monitorJob = scope.launch(Dispatchers.IO) {
                try {
                    val buffer = ByteArray(8192)
                    val stream = started.inputStream
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                    }
                } catch (_: Exception) {
                    // Process gone; fall through to the exit note below.
                }
                val exit = runCatching { started.waitFor() }.getOrDefault(-1)
                if (process === started) {
                    process = null
                    DebugLog.w(TAG, "Chromium exited unexpectedly (exit=$exit); next use will relaunch")
                }
            }
            DebugLog.d(TAG, "Chromium launched (pid via proot), debug port 127.0.0.1:$debugPort")
            true
        } catch (e: Exception) {
            DebugLog.e(TAG, "launch failed", e)
            false
        }
    }

    private fun stopLocked() {
        monitorJob?.cancel()
        monitorJob = null
        val p = process
        process = null
        if (p != null && p.isAlive) {
            p.destroy()
            try {
                if (!p.waitFor(PROCESS_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly()
                }
            } catch (_: Exception) {
                runCatching { p.destroyForcibly() }
            }
            DebugLog.d(TAG, "Chromium stopped")
        }
    }

    companion object {
        const val DEFAULT_DEBUG_PORT = 9333
        const val PROFILE_DIR_NAME = "browser-profile"
        const val DOWNLOAD_DIR_NAME = "browser-downloads"

        /** Sandbox-side mount point of [profileDir] (see the extra --bind in [launchLocked]). */
        const val SANDBOX_PROFILE_PATH = "/browser-profile"

        /** Sandbox-side mount point of [downloadsDir]; used for Browser.setDownloadBehavior. */
        const val SANDBOX_DOWNLOAD_PATH = "/browser-downloads"

        private const val TAG = "ChromiumLauncher"
        private const val BROWSER_HEALTH_USER_AGENT = "AgentX/1.0"
        private val STALE_LOCK_FILES = arrayOf("SingletonLock", "SingletonSocket", "SingletonCookie")
        private const val STARTUP_POLL_ATTEMPTS = 180 // 180 × 500ms ≈ 90s startup window
        private const val STARTUP_POLL_INTERVAL_MS = 500L
        private const val PROCESS_STOP_TIMEOUT_MS = 5000L
        /** Below this free RAM, Chromium under proot is not viable (LMK kills). */
        private const val MIN_FREE_MEMORY_BYTES = 1_536L * 1024L * 1024L // 1.5 GB
    }

    /** Human-readable reason for the last ensureStarted() failure, if any. */
    var lastStartFailure: String? = null
        private set

    /** False when the device cannot plausibly run Chromium under proot. */
    private fun hasEnoughMemory(): Boolean {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return true // unknown: don't block, let the launch attempt speak
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem >= MIN_FREE_MEMORY_BYTES
    }
}
