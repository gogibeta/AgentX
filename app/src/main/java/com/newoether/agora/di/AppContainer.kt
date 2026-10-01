package com.newoether.agora.di

import android.app.Application
import android.content.Context
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.data.SkillManager
import com.newoether.agora.data.SettingsManager
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.data.local.semanticModelSnapshot
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.ConversationSettingsTransferCoordinator
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.data.repository.TaskRepository
import com.newoether.agora.data.AutoBackupManager
import com.newoether.agora.api.LocalModelRuntime
import com.newoether.agora.api.local.LocalProvider
import com.newoether.agora.automation.AutomationScheduler
import com.newoether.agora.automation.AutomationExecutionGate
import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.automation.LoopManager
import com.newoether.agora.automation.TaskExecutionEngine
import com.newoether.agora.automation.TaskManager
import com.newoether.agora.tool.AutomationToolProvider
import com.newoether.agora.tool.McpToolProvider
import com.newoether.agora.mcp.McpRegistry
import com.newoether.agora.sandbox.SandboxManagerFactory
import com.newoether.agora.service.MaintenanceDebtWorker
import com.newoether.agora.service.AskUserNotifier
import com.newoether.agora.service.ShellConfirmationNotifier
import com.newoether.agora.service.TaskWorker
import com.newoether.agora.viewmodel.ChatRuntime
import com.newoether.agora.viewmodel.ChatViewModel
import com.newoether.agora.viewmodel.ChatViewModelFactory
import com.newoether.agora.viewmodel.ConversationStateRegistry
import com.newoether.agora.viewmodel.ProviderRegistry
import com.newoether.agora.viewmodel.ShellConfirmationController
import com.newoether.agora.api.HttpClient
import com.newoether.agora.browser.BrowserPreferenceStore
import com.newoether.agora.browser.BrowserSession
import com.newoether.agora.browser.BrowserToolProvider
import com.newoether.agora.browser.ChromiumLauncher
import com.newoether.agora.browser.WebViewBrowserBackend
import com.newoether.agora.browser.cdp.CdpClient
import com.newoether.agora.security.ApprovalGate
import com.newoether.agora.security.CredentialVault
import com.newoether.agora.social.SocialPreferenceStore
import com.newoether.agora.tool.SocialToolProvider
import com.newoether.agora.ui.browser.DefaultBrowserWatchController
import com.newoether.agora.data.dataStore
import com.newoether.agora.tool.ToolImageStore
import java.util.concurrent.TimeUnit
import com.newoether.agora.webui.WebUiController
import com.newoether.agora.webui.WebUiSettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Centralized dependency container (manual DI).
 *
 * Replaces the ad-hoc dependency creation previously spread across MainActivity.
 * The validated database is injected by AgentXApplication's startup gate; all shared
 * dependencies are then created once and reused.
 *
 * This is a stepping stone toward a full DI framework (Hilt/Koin);
 * for a single-module project it provides sufficient decoupling and
 * testability without annotation processing overhead.
 */
class AppContainer(
    private val appContext: Context,
    val database: ChatDatabase,
) {
    private val application = appContext.applicationContext as Application

    private fun currentAppVersion(): String =
        try {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }

    init {
        LocalModelRuntime.initialize(application.applicationInfo.nativeLibraryDir)
    }

    /** App-lifetime scope that backs the shared settings StateFlows.
     *  The handler is the last line of defense: children launched directly on this scope
     *  (settings sync, scheduler, task runners) have no other parent to report to, and an
     *  uncaught exception here would otherwise kill the whole process. */
    private val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default +
            kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
                com.newoether.agora.util.DebugLog.e("AppContainer", "Uncaught in appScope", e)
            }
    )

    // ── Data Layer ────────────────────────────────────────────

    val settingsManager: SettingsManager by lazy { SettingsManager(appContext) }
    val memoryManager: MemoryManager by lazy { MemoryManager(appContext) }
    val skillManager: SkillManager by lazy { SkillManager(appContext) }
    val chatDao: ChatDao by lazy { database.chatDao() }

    // ── Repositories ──────────────────────────────────────────

    val conversationRepository: ConversationRepository by lazy {
        ConversationRepository(
            chatDao = chatDao,
            database = database,
            semanticModelSnapshotProvider = {
                settingsRepository.awaitInitialLoad()
                semanticModelSnapshot(
                    activeModelId = settingsRepository.activeEmbeddingModelId.value,
                    configuredModelIds = settingsRepository.embeddingModels.value.map { it.id },
                )
            },
        )
    }

    @Volatile
    private var processServicesStarted = false

    /** Starts necessary process work after the narrow conversation list has published. */
    @Synchronized
    fun startProcessServices() {
        if (processServicesStarted) return
        providerRegistry.ensureStarted()
        taskManager.start()
        automationScheduler.start()
        processServicesStarted = true
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                if (database.maintenanceDebtDao().hasDebt()) MaintenanceDebtWorker.schedule()
            } catch (error: Exception) {
                com.newoether.agora.util.DebugLog.e(
                    "AppContainer",
                    "Failed to schedule maintenance debt",
                    error,
                )
            }
        }
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            conversationSettingsTransfers.replayPending()
        }
    }
    val taskRepository: TaskRepository by lazy {
        TaskRepository(chatDao)
    }
    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(
            settingsManager,
            appScope,
            touchConversationData = { conversationId ->
                conversationRepository.touchConversationData(conversationId)
            },
        ).also {
            LocalModelRuntime.bindIdleRetention(it.localModelIdleRetentionMinutes, appScope)
        }
    }
    val conversationSettingsTransfers: ConversationSettingsTransferCoordinator by lazy {
        ConversationSettingsTransferCoordinator(conversationRepository, settingsRepository)
    }

    /** One process-wide ask_user queue, so a background run can reach the same interaction bar. */
    val askUserController: com.newoether.agora.viewmodel.AskUserController by lazy {
        com.newoether.agora.viewmodel.AskUserController().also {
            // The interaction bar only exists inside the chat screen, so a question asked from
            // anywhere else needs the notification to stay answerable.
            AskUserNotifier.start(appScope, appContext, it)
            // Answers to non-blocking questions leave through the send queue. The controller is
            // process-wide, so its answers get exactly one process-wide collector; one per chat
            // screen delivered each answer once per live screen.
            com.newoether.agora.viewmodel.DeferredAskUserAnswerDelivery(
                askUser = it,
                registry = conversationStateRegistry,
                scope = appScope,
                conversationModelId = { id -> conversationRepository.getConversation(id)?.modelId },
                fallbackModelId = { settingsRepository.selectedModel.value },
            ).start()
        }
    }

    /** One process-wide confirmation queue shared by Chat, Task, and Loop generation. */
    val shellConfirmationController: ShellConfirmationController by lazy {
        ShellConfirmationController(settingsRepository).also {
            // Observe from the moment the queue exists: a background automation run must be able
            // to offer its decision on the notification shade without any Activity having started.
            ShellConfirmationNotifier.start(appScope, appContext, it)
        }
    }

    // ── Generation singletons (process-scoped) ────────────────
    // Shared by both the foreground ChatViewModel and background task execution.
    // [localProvider] must be unique per process; LocalModelRuntime owns the one embedded model
    // lifecycle. [providerRegistry] holds the live provider map the
    // generation pipeline reads and runs the long-lived credential/model sync jobs.

    val localProvider: LocalProvider by lazy { LocalProvider(appContext, settingsRepository) }

    val providerRegistry: ProviderRegistry by lazy {
        ProviderRegistry(
            settingsRepository,
            conversationRepository,
            localProvider,
            appScope,
            currentAppVersion(),
        )
    }

    /** Serializes every foreground/background generation touching the same conversation. */
    val conversationExecutionCoordinator: ConversationExecutionCoordinator by lazy {
        ConversationExecutionCoordinator()
    }

    /** Foreground generation slots survive Activity/ViewModel recreation within this process. */
    val conversationStateRegistry: ConversationStateRegistry by lazy {
        ConversationStateRegistry()
    }

    val mcpRegistry: McpRegistry by lazy {
        McpRegistry(appContext, settingsRepository, appScope)
    }

    val mcpToolProvider: McpToolProvider by lazy {
        McpToolProvider(mcpRegistry)
    }

    // ── Browser automation (Workstream A) ─────────────────────────
    // Own DataStore slice (BrowserPreferenceStore), so the 800-line-capped
    // SettingsManager/SettingsRepository stay untouched. The tunnel client
    // token is encrypted at rest via SecretCrypto (Android Keystore-backed).

    val browserPreferenceStore: BrowserPreferenceStore by lazy {
        BrowserPreferenceStore(
            appContext.dataStore,
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
        )
    }

    val chromiumLauncher: ChromiumLauncher by lazy {
        ChromiumLauncher(appContext, HttpClient.client, appScope)
    }

    val browserCdpClient: CdpClient by lazy {
        CdpClient(
            // ~20s WebSocket ping: an idle CDP socket is the normal state,
            // never a dead one — keep it alive, don't cut it.
            HttpClient.client.newBuilder()
                .pingInterval(20, TimeUnit.SECONDS)
                .build(),
            appScope,
        )
    }

    val browserSession: BrowserSession by lazy {
        BrowserSession(
            browserPreferenceStore,
            chromiumLauncher,
            webViewBrowserBackend,
            browserCdpClient,
            HttpClient.client,
            appScope,
        )
    }

    /** v2.4 WebView CDP backend: System WebView + 127.0.0.1 bridge (no sandbox). */
    val webViewBrowserBackend: WebViewBrowserBackend by lazy {
        WebViewBrowserBackend(appContext, appScope)
    }

    val browserToolProvider: BrowserToolProvider by lazy {
        BrowserToolProvider(
            browserSession,
            browserPreferenceStore,
            ToolImageStore(appContext),
            // Credential vault (stream C): resolve cred_id → secret through the
            // Keystore-backed vault; the CharArray is zeroed immediately after
            // copying. The secret never reaches logs, diagnostics, or tool results.
            credentialResolver = { credId ->
                credentialVault.resolveSecret(credId)
                    ?.let { chars -> String(chars).also { chars.fill('\u0000') } }
            },
        )
    }

    /** Keystore-backed credential vault (AES-256-GCM); agent sees only cred_id surrogates. */
    val credentialVault: CredentialVault
        get() = settingsManager.credentialVault

    /** Fail-closed approval policy engine for the browser tool layer. */
    val approvalGate: ApprovalGate by lazy {
        ApprovalGate(appContext)
    }

    /** Watch-panel controller: feeds the Compose mini-browser from the CDP session. */
    val browserWatchController: DefaultBrowserWatchController by lazy {
        DefaultBrowserWatchController(browserSession, approvalGate, appScope)
    }

    // ── Social read (Workstream D) ───────────────────────────────
    // User-provided FxEmbed worker; the app ships no default URL.

    val socialPreferenceStore: SocialPreferenceStore by lazy {
        SocialPreferenceStore(appContext.dataStore)
    }

    val socialToolProvider: SocialToolProvider by lazy {
        SocialToolProvider()
    }

    /** Lets native import quiesce Task/Loop generation without serializing ordinary executions. */
    val automationExecutionGate: AutomationExecutionGate by lazy { AutomationExecutionGate() }

    // ── Sandbox (flavor-specific) ─────────────────────────────

    val sandboxManagerFactory: SandboxManagerFactory? by lazy {
        try {
            // fdroid flavor provides FdroidSandboxManagerFactory
            Class.forName("com.newoether.agora.sandbox.FdroidSandboxManagerFactory")
                .getDeclaredConstructor(
                    android.content.Context::class.java,
                    com.newoether.agora.data.repository.SettingsRepository::class.java,
                )
                .newInstance(appContext, settingsRepository) as SandboxManagerFactory
        } catch (_: ClassNotFoundException) {
            // play flavor provides PlaySandboxManagerFactory
            try {
                Class.forName("com.newoether.agora.sandbox.PlaySandboxManagerFactory")
                    .getDeclaredConstructor()
                    .newInstance() as SandboxManagerFactory
            } catch (_: ClassNotFoundException) {
                null
            } catch (e: Exception) {
                // Class exists but failed to construct — this is a real error, not a flavor miss.
                com.newoether.agora.util.DebugLog.e("AppContainer", "PlaySandboxManagerFactory init failed", e)
                null
            }
        } catch (e: Exception) {
            // FdroidSandboxManagerFactory exists but failed to construct.
            com.newoether.agora.util.DebugLog.e("AppContainer", "FdroidSandboxManagerFactory init failed", e)
            null
        }
    }

    // ── Headless task execution (process-scoped) ──────────────
    // Drives a full generation with no ViewModel/UI, reusing the shared generation
    // singletons above. Background Task/Loop runners call its runOnce(...).

    val taskExecutionEngine: TaskExecutionEngine by lazy {
        TaskExecutionEngine(
            application = application,
            appContext = appContext,
            convRepo = conversationRepository,
            settings = settingsRepository,
            memoryManager = memoryManager,
            skillManager = skillManager,
            providerRegistry = providerRegistry,
            localProvider = localProvider,
            sandboxFactory = sandboxManagerFactory,
            appScope = appScope,
            executionCoordinator = conversationExecutionCoordinator,
            shellConfirmation = shellConfirmationController,
            automationExecutionGate = automationExecutionGate,
            mcpToolProvider = mcpToolProvider,
            askUser = askUserController,
            generationRegistry = conversationStateRegistry,
            pauseConversationLoop = { conversationId -> loopManager.stopLoop(conversationId) },
        )
    }

    val taskManager: TaskManager by lazy {
        TaskManager(
            taskRepository = taskRepository,
            conversationRepository = conversationRepository,
            engine = taskExecutionEngine,
            scope = appScope,
            cancelScheduledExecution = { taskId ->
                TaskWorker.cancel(appContext, taskId)
                automationScheduler.cancelTask(taskId)
            },
            cancelConversationLoop = { conversationId ->
                loopManager.stopLoop(conversationId)
            },
            refreshScheduling = { automationScheduler.refresh() },
            conversationExecutionCoordinator = conversationExecutionCoordinator,
            automationExecutionGate = automationExecutionGate,
            titleExecutionConversation = taskExecutionEngine::updateTaskExecutionTitle,
        )
    }

    val loopManager: LoopManager by lazy {
        LoopManager(
            taskRepository = taskRepository,
            conversationRepository = conversationRepository,
            engine = taskExecutionEngine,
            cancelWork = { conversationId ->
                com.newoether.agora.service.LoopWorker.cancel(appContext, conversationId)
            },
            cancelAlarm = { conversationId -> automationScheduler.cancelLoop(conversationId) },
            executionCoordinator = conversationExecutionCoordinator,
            executionGate = automationExecutionGate,
        )
    }

    /** Foreground-only provider: headless automation cannot recursively create automation. */
    val automationToolProvider: AutomationToolProvider by lazy {
        AutomationToolProvider(taskManager, loopManager, { settingsManager.systemPrompts.first() }) {
            settingsManager.automationToolsEnabled.first()
        }
    }

    val automationScheduler: AutomationScheduler by lazy {
        AutomationScheduler(appContext, taskRepository, settingsRepository, appScope)
    }

    // ── Auto Backup ───────────────────────────────────────────

    val autoBackupManager: AutoBackupManager by lazy {
        AutoBackupManager(appContext, settingsManager, memoryManager, skillManager)
    }

    // Process-scoped chat runtime shared by every client (phone UI now, WebUI later).
    val chatRuntime: ChatRuntime by lazy {
        ChatRuntime(
            application = application,
            appContext = appContext,
            conversations = conversationRepository,
            settings = settingsRepository,
            memoryManager = memoryManager,
            skillManager = skillManager,
            sandboxFactory = sandboxManagerFactory,
            automationToolProvider = automationToolProvider,
            mcpToolProvider = mcpToolProvider,
            browserToolProvider = browserToolProvider,
            socialToolProvider = socialToolProvider,
            askUser = askUserController,
            shellConfirmation = shellConfirmationController,
            registry = conversationStateRegistry,
            providerRegistry = providerRegistry,
            localProvider = localProvider,
            executionCoordinator = conversationExecutionCoordinator,
            loopManager = loopManager,
            taskExecutionEngine = taskExecutionEngine,
            scope = appScope,
        )
    }
    // WebUI remote control: settings, authentication and the embedded server.
    internal val webUi: WebUiController by lazy {
        WebUiController(
            appContext = appContext,
            store = WebUiSettingsStore(appContext.dataStore),
            scope = appScope,
            // No-backup storage: the TLS key never leaves the device.
            certificates = com.newoether.agora.webui.WebUiCertificateStore(
                directory = java.io.File(appContext.noBackupFilesDir, "webui"),
                seal = com.newoether.agora.util.SecretCrypto::encrypt,
                unseal = com.newoether.agora.util.SecretCrypto::decrypt,
                addresses = WebUiController::interfaceAddresses,
            ),
        )
    }
    // ── ViewModel Factory ─────────────────────────────────────

    fun chatViewModelFactory(): ChatViewModelFactory =
        ChatViewModelFactory(
            application, database, chatDao, settingsManager, memoryManager, skillManager, appContext, sandboxManagerFactory,
            autoBackupManager, conversationRepository, settingsRepository, conversationSettingsTransfers,
            ::startProcessServices, providerRegistry,
            taskManager, loopManager, conversationExecutionCoordinator,
            automationExecutionGate, conversationStateRegistry, shellConfirmationController,
            askUserController,
            mcpRegistry, chatRuntime,
        )
}
