package com.newoether.agora.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.ui.components.ProjectFolderPickerDialog
import com.newoether.agora.viewmodel.ChatViewModel

/**
 * Project-folder scoping glue for the chat screen. Extracted from ChatApp to
 * keep that file under the 800-line cap.
 *
 * When the user picks plan/build mode they must first choose a project folder
 * for the current conversation; the mode only switches after confirmation.
 */

/** Active project folder for [agentMode] in [settingsOwnerId], or "" when none. */
fun activeProjectFolderFor(
    conversationSettingsMap: Map<String, ConversationSettings>,
    settingsOwnerId: String?,
    agentMode: String,
): String = settingsOwnerId
    ?.let { conversationSettingsMap[it]?.agentProjectFolders?.get(agentMode) }
    .orEmpty()

/**
 * Returns the mode to prompt a folder for, or null when selecting [mode] can
 * switch immediately (not plan/build, no conversation, or a folder is set).
 */
fun pendingFolderPrompt(
    mode: String,
    settingsOwnerId: String?,
    conversationSettingsMap: Map<String, ConversationSettings>,
): String? {
    if (mode != "plan" && mode != "build") return null
    if (settingsOwnerId == null) return null
    val existing = conversationSettingsMap[settingsOwnerId]?.agentProjectFolders?.get(mode)
    return if (existing.isNullOrBlank()) mode else null
}

@Stable
class ProjectFolderScope internal constructor(
    /** Active folder for the current agent mode ("" when none). */
    val activeFolder: String,
    /** Current agent mode (plan/build/chat/off). */
    val agentMode: String,
    /** Route a mode selection through the folder prompt when needed. */
    val requestModeChange: (String) -> Unit,
    /** Reopen the picker for the current mode (folder chip tap). */
    val openFolderPicker: () -> Unit,
)

/**
 * Remembers the project-folder scope for the chat screen, hosting the picker
 * dialog and the once-per-conversation auto-prompt (covers mode switches made
 * from Settings, which has no conversation scope).
 */
@Composable
fun rememberProjectFolderScope(
    viewModel: ChatViewModel,
    settingsOwnerId: String?,
): ProjectFolderScope {
    val agentMode by viewModel.settings.agentSettings.agentMode.collectAsState()
    val conversationSettingsMap by viewModel.settings.conversationSettings.collectAsState()
    var promptMode by remember { mutableStateOf<String?>(null) }
    val activeFolder = activeProjectFolderFor(conversationSettingsMap, settingsOwnerId, agentMode)

    val promptedKeys = remember { mutableSetOf<String>() }
    LaunchedEffect(agentMode, activeFolder, settingsOwnerId) {
        val owner = settingsOwnerId
        if ((agentMode == "plan" || agentMode == "build") &&
            activeFolder.isBlank() &&
            owner != null &&
            promptedKeys.add("$owner:$agentMode")
        ) {
            promptMode = agentMode
        }
    }

    promptMode?.let { mode ->
        ProjectFolderPickerDialog(
            modeLabel = stringResource(
                if (mode == "plan") R.string.agent_mode_plan else R.string.agent_mode_build,
            ),
            initialFolder = activeProjectFolderFor(conversationSettingsMap, settingsOwnerId, mode),
            onConfirm = { folder ->
                viewModel.updateConversationSetting(settingsOwnerId) {
                    it.copy(
                        agentProjectFolders = it.agentProjectFolders.orEmpty() + (mode to folder),
                    )
                }
                viewModel.settings.agentSettings.setAgentMode(mode)
                promptMode = null
            },
            onDismiss = { promptMode = null },
        )
    }

    return remember(activeFolder, agentMode, settingsOwnerId, conversationSettingsMap) {
        ProjectFolderScope(
            activeFolder = activeFolder,
            agentMode = agentMode,
            requestModeChange = { mode ->
                val pending = pendingFolderPrompt(mode, settingsOwnerId, conversationSettingsMap)
                if (pending != null) {
                    promptMode = pending
                } else {
                    viewModel.settings.agentSettings.setAgentMode(mode)
                }
            },
            openFolderPicker = { promptMode = agentMode },
        )
    }
}
