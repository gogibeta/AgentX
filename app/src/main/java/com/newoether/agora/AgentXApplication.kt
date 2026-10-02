package com.newoether.agora

import android.app.Application
import com.newoether.agora.api.util.tokens.bpe.O200kBase
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.di.AppContainer
import com.newoether.agora.diagnostics.DeveloperDiagnostics
import com.newoether.agora.util.CrashReporter
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Application entry point. Installs the crash reporter before any other component runs so
 * that crashes occurring during startup are captured as well.
 *
 * Owns the process-scoped AppContainer, but publishes it only after the durable database has
 * passed compatibility checks, supported migrations, and Room schema validation.
 */
class AgentXApplication : Application() {
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val startupGate = DatabaseStartupGate(
        inspectDatabase = {
            withContext(Dispatchers.IO) {
                ChatDatabase.inspectCompatibility(this@AgentXApplication)
            }
        },
        openResource = {
            withContext(Dispatchers.IO) {
                val database = ChatDatabase.build(this@AgentXApplication)
                AppContainer(this@AgentXApplication, database)
            }
        },
        closeResource = { container -> container.database.close() },
        deleteDatabase = {
            withContext(Dispatchers.IO) {
                val databasePath = getDatabasePath(ChatDatabase.DB_NAME)
                !databasePath.exists() ||
                    this@AgentXApplication.deleteDatabase(ChatDatabase.DB_NAME)
            }
        },
        reportFailure = { error ->
            DebugLog.e(
                "AgentXApplication",
                "Database startup gate failed closed",
                error,
            )
        },
    )

    val databaseStartupState: StateFlow<DatabaseStartupState>
        get() = startupGate.state

    override fun onCreate() {
        super.onCreate()
        // Start the persistent on-device diagnostics log first so every later
        // step (crash install, DB gate, requests) is captured from t=0.
        com.newoether.agora.util.FileLog.start(this)
        CrashReporter.install(this)
        registerActivityLifecycleCallbacks(DiagnosticsActivityCallbacks)
        // The context indicator counts remote models' text with a real vocabulary once it is in
        // memory. Loading it in its own job keeps the first estimate off the heuristic without
        // delaying the database gate behind it.
        startupScope.launch { O200kBase.ensureLoaded(this@AgentXApplication) }
        // Pre-flight the GeckoView runtime early so a broken GeckoView install
        // fails fast with a clear log instead of at the first browser tool
        // call (III.3). Best-effort: never blocks startup.
        startupScope.launch {
            runCatching { awaitContainer()?.geckoViewBrowserBackend?.preflight() }
                .onFailure { DebugLog.w("AgentXApplication", "GeckoView preflight failed: ${it.message}") }
        }
        startupScope.launch {
            try {
                DeveloperDiagnostics.initialize(noBackupFilesDir, startupScope)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DebugLog.e(
                    "AgentXApplication",
                    "Diagnostic capture initialization failed closed",
                    error,
                )
            }
            try {
                com.newoether.agora.diagnostics.StructuredDiagnostics.initialize(
                    filesDir,
                    startupScope,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DebugLog.e(
                    "AgentXApplication",
                    "Structured diagnostics initialization failed closed",
                    error,
                )
            }
            startupGate.initialize()
        }
    }

    suspend fun awaitDatabaseStartup(): DatabaseStartupState =
        startupGate.awaitState()

    suspend fun awaitContainer(): AppContainer? =
        startupGate.awaitReadyResource()

    fun requireContainer(): AppContainer =
        startupGate.requireReadyResource()

    suspend fun clearIncompatibleDatabase(): Boolean =
        startupGate.clearBlockedDatabase()

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        DebugLog.event(
            "AppLifecycle",
            mapOf("level" to level.toString()),
            "memory pressure",
        )
    }
}

/**
 * Always-on activity lifecycle trail for the diagnostics log. Records only the
 * activity class name and transition — no intent extras, no view content.
 */
private object DiagnosticsActivityCallbacks :
    android.app.Application.ActivityLifecycleCallbacks {
    private fun note(activity: android.app.Activity, transition: String) {
        DebugLog.event(
            "AppLifecycle",
            mapOf(
                "activity" to (activity.javaClass.simpleName ?: "?"),
                "transition" to transition,
            ),
            "activity $transition",
        )
    }

    override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) =
        note(a, "created")
    override fun onActivityStarted(a: android.app.Activity) = note(a, "started")
    override fun onActivityResumed(a: android.app.Activity) = note(a, "resumed")
    override fun onActivityPaused(a: android.app.Activity) = note(a, "paused")
    override fun onActivityStopped(a: android.app.Activity) = note(a, "stopped")
    override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) =
        note(a, "save_state")
    override fun onActivityDestroyed(a: android.app.Activity) = note(a, "destroyed")
}
