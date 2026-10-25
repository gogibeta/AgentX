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
import com.newoether.agora.viewmodel.NEW_CHAT_WORKSPACE_ID

/**
 * Project-folder scoping glue for the chat screen. Extracted from ChatApp to
 * keep that file under the 800-line cap.
 *
 * When the user picks plan/build mode they must first choose a project folder
 * for the current conversation; the mode only switches after confirmation.
 *
 * The settings owner is canonicalized once: a null conversation (new chat)
 * reads and writes through [NEW_CHAT_WORKSPACE_ID], matching
 * [ChatViewModel.updateConversationSetting].
 */
fun canonicalSettingsOwnerId(settingsOwnerId: String?): String =
    settingsOwnerId ?: NEW_CHAT_WORKSPACE_ID

/** Active project folder for [agentMode] in [ownerId], or "" when none. */
fun activeProjectFolderFor(
    conversationSettingsMap: Map<String, ConversationSettings>,
    ownerId: String,
    agentMode: String,
): String =
    conversationSettingsMap[ownerId]?.agentProjectFolders?.get(agentMode).orEmpty()

/**
 * Returns the mode to prompt a folder for, or null when selecting [mode] can
 * switch immediately (not plan/build, or a folder is already set).
 */
fun pendingFolderPrompt(
    mode: String,
    ownerId: String,
    conversationSettingsMap: Map<String, ConversationSettings>,
): String? {
    if (mode != "plan" && mode != "build") return null
    val existing = conversationSettingsMap[ownerId]?.agentProjectFolders?.get(mode)
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
    // Canonical owner: new chat reads/writes through NEW_CHAT_WORKSPACE_ID.
    val ownerId = canonicalSettingsOwnerId(settingsOwnerId)
    var promptMode by remember { mutableStateOf<String?>(null) }
    val activeFolder = activeProjectFolderFor(conversationSettingsMap, ownerId, agentMode)

    val promptedKeys = remember { mutableSetOf<String>() }
    LaunchedEffect(agentMode, activeFolder, ownerId) {
        if ((agentMode == "plan" || agentMode == "build") &&
            activeFolder.isBlank() &&
            promptedKeys.add("$ownerId:$agentMode")
        ) {
            promptMode = agentMode
        }
    }

    // A stored folder can be deleted or renamed outside the app. Verify the
    // single folder (never scan the workspace); when it is gone, clear it and
    // re-prompt instead of failing silently or falling back to wider access.
    val existenceChecked = remember { mutableSetOf<String>() }
    LaunchedEffect(agentMode, activeFolder, ownerId) {
        val folder = activeFolder
        val checkKey = "$ownerId:$agentMode:$folder"
        if ((agentMode == "plan" || agentMode == "build") &&
            folder.isNotBlank() &&
            existenceChecked.add(checkKey) &&
            !viewModel.projectFolderExists(folder)
        ) {
            viewModel.updateConversationSetting(ownerId) {
                it.copy(agentProjectFolders = it.agentProjectFolders.orEmpty() + (agentMode to ""))
            }
            promptedKeys.remove("$ownerId:$agentMode")
        }
    }

    promptMode?.let { mode ->
        ProjectFolderPickerDialog(
            modeLabel = stringResource(
                if (mode == "plan") R.string.agent_mode_plan else R.string.agent_mode_build,
            ),
            initialFolder = activeProjectFolderFor(conversationSettingsMap, ownerId, mode),
            onConfirm = { folder ->
                viewModel.updateConversationSetting(ownerId) {
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

    return remember(activeFolder, agentMode, ownerId, conversationSettingsMap) {
        ProjectFolderScope(
            activeFolder = activeFolder,
            agentMode = agentMode,
            requestModeChange = { mode ->
                val pending = pendingFolderPrompt(mode, ownerId, conversationSettingsMap)
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
