package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.social.FxEmbedClient
import com.newoether.agora.social.SocialPreferenceStore
import com.newoether.agora.util.noOpBringIntoView
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → Social: read-only social content (X/Twitter, Bluesky, TikTok,
 * Instagram, Threads, Mastodon/ActivityPub) through the user's OWN FxEmbed
 * worker.
 *
 * The app ships with NO default worker URL — the user deploys their own free
 * FxEmbed instance (see the deploy note on the page) and pastes its URL here.
 * The Validate button hits `{base}/ai/version` (falling back to `{base}/version`
 * on custom domains) with the mandatory `AgentX/<version>` User-Agent and
 * records which prefix form answered.
 *
 * Nothing here posts, stores, or asks for social API keys — reading needs
 * none. The write path is a separate, later build.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSocialPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val social = viewModel.settings.socialSettings
    val socialEnabled by social.socialEnabled.collectAsState()
    val workerUrl by social.socialWorkerBaseUrl.collectAsState()
    val validated by social.socialValidated.collectAsState()
    val validatedAt by social.socialValidatedAt.collectAsState()
    val workerVersion by social.socialWorkerVersion.collectAsState()

    var urlDraft by remember { mutableStateOf("") }
    var urlDirty by remember { mutableStateOf(false) }
    var validateState by remember { mutableStateOf<SocialValidateState>(SocialValidateState.Idle) }
    val scope = rememberCoroutineScope()
    val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()
    // Saved URL until the user edits; the Validate button works on either.
    val effectiveUrl = if (urlDirty) urlDraft else workerUrl

    CollapsingSettingsScaffold(
        title = stringResource(R.string.social_title),
        onBack = onBack,
        floatingActionButton = { if (showDocFab) DocumentationFab("social.md") }
    ) {
        SettingsGroupColumn {
            SettingsGroup(title = stringResource(R.string.social_title), items = buildList {
                add {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.social_enable)) },
                        supportingContent = { Text(stringResource(R.string.social_enable_desc)) },
                        leadingContent = { Icon(Icons.Default.Share, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = {
                            Switch(checked = socialEnabled, onCheckedChange = { social.setSocialEnabled(it) })
                        },
                        modifier = Modifier.clickable { social.setSocialEnabled(!socialEnabled) }
                    )
                }

                if (socialEnabled) {
                    // Worker URL (user-entered; no default shipped)
                    add {
                        SocialTextRow(
                            label = stringResource(R.string.social_worker_url),
                            value = effectiveUrl,
                            placeholder = stringResource(R.string.social_worker_url_hint),
                            onValueChange = { urlDraft = it; urlDirty = true },
                            leadingIcon = { Icon(Icons.Default.Link, null, tint = MaterialTheme.colorScheme.primary) },
                            supporting = stringResource(R.string.social_worker_url_desc),
                            isError = effectiveUrl.isNotBlank() &&
                                !SocialPreferenceStore.isValidWorkerUrl(effectiveUrl),
                        )
                    }
                    // Save + Validate
                    add {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Row {
                                Button(
                                    onClick = {
                                        val normalized = SocialPreferenceStore.normalizeBaseUrl(effectiveUrl)
                                        social.setSocialWorkerBaseUrl(normalized)
                                        urlDraft = normalized
                                        urlDirty = true
                                        validateState = SocialValidateState.Running
                                        scope.launch {
                                            validateState = runSocialValidation(
                                                baseUrl = normalized,
                                                userAgent = FxEmbedClient.userAgentFor(viewModel.getCurrentVersion()),
                                                store = social,
                                            )
                                        }
                                    },
                                    enabled = SocialPreferenceStore.isValidWorkerUrl(effectiveUrl) &&
                                        validateState != SocialValidateState.Running,
                                ) {
                                    Text(stringResource(R.string.social_validate))
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            when (val s = validateState) {
                                SocialValidateState.Idle -> {
                                    if (validated) {
                                        ValidatedRow(stringResource(R.string.social_validated, workerVersion))
                                    } else {
                                        Text(
                                            stringResource(R.string.social_not_validated),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                SocialValidateState.Running -> {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(R.string.social_validate_running))
                                    }
                                }
                                is SocialValidateState.Ok -> {
                                    ValidatedRow(stringResource(R.string.social_validated, s.version))
                                }
                                is SocialValidateState.Failed -> {
                                    Row(verticalAlignment = Alignment.Top) {
                                        Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error)
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            stringResource(R.string.social_validation_failed, s.message),
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                            if (validated && validatedAt > 0L) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    java.text.DateFormat.getDateTimeInstance()
                                        .format(java.util.Date(validatedAt)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    // Deploy-your-own note
                    add {
                        SettingsItem(
                            headlineContent = {
                                Text(
                                    stringResource(R.string.social_deploy_note),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }
                }
            })
        }
        if (showDocFab) { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

@Composable
private fun ValidatedRow(message: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurface)
    }
}

private sealed interface SocialValidateState {
    data object Idle : SocialValidateState
    data object Running : SocialValidateState
    data class Ok(val version: String, val bareRealm: Boolean) : SocialValidateState
    data class Failed(val message: String) : SocialValidateState
}

/**
 * Validate hit: `GET {base}/ai/version` then `{base}/version` with
 * `User-Agent: AgentX/<version>`. Single attempts, no retries — a worker that
 * cannot answer either form is reported, not hammered.
 */
private suspend fun runSocialValidation(
    baseUrl: String,
    userAgent: String,
    store: SocialPreferenceStore,
): SocialValidateState = withContext(Dispatchers.IO) {
    if (!SocialPreferenceStore.isValidWorkerUrl(baseUrl)) {
        return@withContext SocialValidateState.Failed(
            "The URL must be a full https:// address with no credentials in it.",
        )
    }
    store.clearValidation()
    when (val result = FxEmbedClient.validateDetailed(baseUrl, userAgent)) {
        is FxEmbedClient.ValidateDetailed.Failed -> SocialValidateState.Failed(result.reason)
        is FxEmbedClient.ValidateDetailed.Ok -> {
            store.recordValidation(result.version, result.bareRealm)
            SocialValidateState.Ok(result.version, result.bareRealm)
        }
    }
}

@Composable
private fun SocialTextRow(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    leadingIcon: @Composable () -> Unit,
    supporting: String? = null,
    isError: Boolean = false,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = 2.dp)) { leadingIcon() }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
                supporting?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(Modifier.noOpBringIntoView().padding(top = 8.dp)) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = onValueChange,
                        placeholder = { Text(placeholder) },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = isError,
                    )
                }
            }
        }
    }
}
