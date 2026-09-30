package com.newoether.agora.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.tool.takeWorkspaceGrant
import com.newoether.agora.ui.components.optionClickable
import com.newoether.agora.util.Constants
import com.newoether.agora.viewmodel.ChatViewModel

private fun workspaceDisplayName(uri: String): String {
    if (uri.isBlank()) return ""
    val decoded = android.net.Uri.decode(uri)
    return decoded.substringAfterLast("/").substringAfter(":").takeIf { it.isNotBlank() }
        ?: "Selected folder"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsAgentPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val agentMode by viewModel.settings.agentSettings.agentMode.collectAsState()
    val agentWorkspaceUri by viewModel.settings.agentSettings.agentWorkspaceUri.collectAsState()
    val agentModels by viewModel.settings.agentSettings.agentModels.collectAsState()
    val agentEnv by viewModel.settings.agentSettings.agentEnv.collectAsState()
    val availableModels by viewModel.settings.availableModels.collectAsState()
    val apiKeys by viewModel.settings.apiKeys.collectAsState()
    val context = LocalContext.current
    var showModelDialog by remember { mutableStateOf(false) }
    var showEnvDialog by remember { mutableStateOf(false) }

    val workspacePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            takeWorkspaceGrant(context, uri)
            viewModel.settings.agentSettings.setAgentWorkspaceUri(uri.toString())
        }
    }

    val modeOptions = listOf(
        Triple("off", R.string.agent_mode_off, R.string.agent_mode_off_desc),
        Triple("plan", R.string.agent_mode_plan, R.string.agent_mode_plan_desc),
        Triple("build", R.string.agent_mode_build, R.string.agent_mode_build_desc),
    )
    val jevConfigured = apiKeys.any { it.provider == Constants.PROVIDER_TYPESAFE }

    CollapsingSettingsScaffold(
        title = stringResource(R.string.settings_agent),
        onBack = onBack,
    ) {
        SettingsGroupColumn {
            SettingsGroup(title = stringResource(R.string.agent_mode_title), items = modeOptions.map { (key, labelRes, descRes) ->
                {
                    SettingsItem(
                        headlineContent = {
                            Text(
                                stringResource(labelRes),
                                fontWeight = if (agentMode == key) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                        supportingContent = { Text(stringResource(descRes)) },
                        leadingContent = {
                            RadioButton(
                                selected = agentMode == key,
                                onClick = { viewModel.settings.agentSettings.setAgentMode(key) },
                            )
                        },
                        modifier = Modifier.optionClickable { viewModel.settings.agentSettings.setAgentMode(key) },
                    )
                }
            })

            SettingsGroup(title = stringResource(R.string.agent_workspace_title), items = listOf {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.agent_workspace_title)) },
                    supportingContent = {
                        Text(
                            if (agentWorkspaceUri.isBlank()) stringResource(R.string.agent_workspace_not_set)
                            else workspaceDisplayName(agentWorkspaceUri),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    leadingContent = {
                        Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier.clickable { workspacePicker.launch(null) },
                )
            })

            SettingsGroup(
                title = stringResource(R.string.agent_models_title),
                items = buildList {
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.agent_models_title)) },
                            supportingContent = { Text(stringResource(R.string.agent_models_desc)) },
                            leadingContent = {
                                Icon(Icons.Default.Psychology, null, tint = MaterialTheme.colorScheme.primary)
                            },
                        )
                    }
                    agentModels.forEach { modelId ->
                        add {
                            SettingsItem(
                                headlineContent = { Text(modelId.substringAfterLast(":")) },
                                supportingContent = { Text(modelId) },
                                trailingContent = {
                                    IconButton(onClick = {
                                        viewModel.settings.agentSettings.setAgentModels(agentModels - modelId)
                                    }) {
                                        Icon(Icons.Default.Delete, null)
                                    }
                                },
                            )
                        }
                    }
                    if (agentModels.size < 5) {
                        add {
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.agent_models_add)) },
                                leadingContent = {
                                    Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
                                },
                                modifier = Modifier.clickable { showModelDialog = true },
                            )
                        }
                    }
                },
            )

            SettingsGroup(title = stringResource(R.string.agent_jev_title), items = listOf {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.agent_jev_title)) },
                    supportingContent = {
                        Text(
                            stringResource(
                                if (jevConfigured) R.string.agent_jev_configured
                                else R.string.agent_jev_missing,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            })

            SettingsGroup(
                title = stringResource(R.string.agent_env_title),                items = buildList {
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.agent_env_title)) },
                            supportingContent = { Text(stringResource(R.string.agent_env_desc)) },
                        )
                    }
                    if (agentEnv.isEmpty()) {
                        add {
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.agent_env_empty)) },
                            )
                        }
                    }
                    agentEnv.keys.sorted().forEach { name ->
                        add {
                            SettingsItem(
                                headlineContent = { Text(name) },
                                supportingContent = { Text("••••••••") },
                                trailingContent = {
                                    IconButton(onClick = {
                                        viewModel.settings.agentSettings.removeAgentEnvVar(name)
                                    }) {
                                        Icon(Icons.Default.Delete, null)
                                    }
                                },
                            )
                        }
                    }
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.agent_env_add)) },
                            leadingContent = {
                                Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
                            },
                            modifier = Modifier.clickable { showEnvDialog = true },
                        )
                    }
                },
            )

            SettingsGroup(title = stringResource(R.string.agent_diaglog_title), items = listOf {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.agent_diaglog_title)) },
                    supportingContent = { Text(stringResource(R.string.agent_diaglog_desc)) },
                    leadingContent = {
                        Icon(Icons.Default.BugReport, null, tint = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier.clickable {
                        val path = com.newoether.agora.util.FileLog.logFilePath(context)
                        val file = java.io.File(path)
                        if (file.exists()) {
                            com.newoether.agora.util.DebugLog.event(
                                "Diagnostics",
                                mapOf("action" to "share_log"),
                                "diagnostics log shared",
                            )
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                context, context.packageName + ".fileprovider", file,
                            )
                            val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(
                                android.content.Intent.createChooser(share, "AgentX diagnostics log"),
                            )
                        }
                    },
                )
            })
        }
    }

    if (showModelDialog) {
        // availableModels values are already complete stored IDs
        // ("providerId:model"); the map key is only a display label, so store
        // the value as-is — prefixing it again produced unusable triples.
        val candidates = availableModels
            .flatMap { (provider, models) ->
                models.map { stored -> stored to "$provider: ${stored.substringAfterLast(":")}" }
            }
            .filter { (stored, _) -> stored !in agentModels }
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showModelDialog = false },
            title = { Text(stringResource(R.string.agent_models_add), fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (candidates.isEmpty()) {
                        Text(
                            stringResource(R.string.agent_models_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    candidates.forEach { (stored, label) ->
                        SettingsItem(
                            headlineContent = { Text(label) },
                            supportingContent = { Text(stored) },
                            modifier = Modifier.optionClickable {
                                viewModel.settings.agentSettings.setAgentModels(agentModels + stored)
                                showModelDialog = false
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showModelDialog = false }) {
                    Text(stringResource(R.string.provider_cancel))
                }
            },
        )
    }

    if (showEnvDialog) {
        var envName by remember { mutableStateOf("") }
        var envValue by remember { mutableStateOf("") }
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showEnvDialog = false },
            title = { Text(stringResource(R.string.agent_env_add), fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = envName,
                        onValueChange = { envName = it.trim().uppercase() },
                        label = { Text(stringResource(R.string.agent_env_name_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = envValue,
                        onValueChange = { envValue = it },
                        label = { Text(stringResource(R.string.agent_env_value_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (envName.isNotBlank() && envValue.isNotBlank()) {
                            viewModel.settings.agentSettings.setAgentEnvVar(envName, envValue)
                            showEnvDialog = false
                        }
                    },
                ) {
                    Text(stringResource(R.string.provider_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showEnvDialog = false }) {
                    Text(stringResource(R.string.provider_cancel))
                }
            },
        )
    }
}
