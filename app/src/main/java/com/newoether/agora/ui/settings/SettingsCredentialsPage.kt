package com.newoether.agora.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.security.CredentialMeta
import com.newoether.agora.ui.components.SecretVisibilityToggle
import com.newoether.agora.ui.components.rememberSecretVisible
import com.newoether.agora.ui.components.secretVisualTransformation
import com.newoether.agora.util.noOpBringIntoView
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

/**
 * Settings → Credentials: site logins for browser autofill (spec §1.3.5).
 *
 * Lists stored credentials (site + username ONLY — secrets are never shown),
 * with add / edit / delete. The password field is masked with the shared
 * secret-visibility pattern; when editing, the password starts blank and a
 * blank value keeps the existing secret. Deleting requires a confirm dialog.
 *
 * The agent never sees these values: it only gets a `cred_id` surrogate, and
 * the browser tool layer resolves + types the secret via the trusted
 * `Input.insertText` path. Every vault use is audit-logged (site, field,
 * timestamp — never the value).
 */
@Composable
fun SettingsCredentialsPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val vault = viewModel.settings.credentialVault
    val credentials by vault.credentials.collectAsState()
    val scope = rememberCoroutineScope()

    var showAddDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CredentialMeta?>(null) }
    var deleting by remember { mutableStateOf<CredentialMeta?>(null) }

    CollapsingSettingsScaffold(
        title = stringResource(R.string.cred_title),
        onBack = onBack,
    ) {
        SettingsGroupColumn {
            SettingsGroup(title = stringResource(R.string.cred_title), items = buildList {
                add {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.cred_desc)) },
                    )
                }
                if (credentials.isEmpty()) {
                    add {
                        SettingsItem(
                            headlineContent = {
                                Text(
                                    stringResource(R.string.cred_empty),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }
                } else {
                    credentials.forEach { meta ->
                        add {
                            SettingsItem(
                                headlineContent = { Text(meta.site) },
                                supportingContent = {
                                    Text(meta.username.ifBlank { "—" })
                                },
                                leadingContent = {
                                    Icon(
                                        Icons.Default.Key,
                                        null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                trailingContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        TextButton(onClick = { editing = meta }) {
                                            Text(stringResource(R.string.cred_edit))
                                        }
                                        TextButton(onClick = { deleting = meta }) {
                                            Text(
                                                stringResource(R.string.cred_delete),
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
                add {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Button(onClick = { showAddDialog = true }) {
                            Text(stringResource(R.string.cred_add))
                        }
                    }
                }
            })
        }
    }

    if (showAddDialog) {
        CredentialEditDialog(
            title = stringResource(R.string.cred_add),
            initial = null,
            onDismiss = { showAddDialog = false },
            onSave = { site, username, secret ->
                scope.launch { vault.storeCredential(site, username, secret) }
                showAddDialog = false
            },
        )
    }

    editing?.let { meta ->
        CredentialEditDialog(
            title = stringResource(R.string.cred_edit),
            initial = meta,
            onDismiss = { editing = null },
            onSave = { site, username, secret ->
                // Blank password keeps the existing secret.
                scope.launch {
                    vault.updateCredential(meta.credId, site, username, secret.ifBlank { null })
                }
                editing = null
            },
        )
    }

    deleting?.let { meta ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.cred_delete_title)) },
            text = {
                Text(stringResource(R.string.cred_delete_message, meta.site, meta.username))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch { vault.deleteCredential(meta.credId) }
                        deleting = null
                    },
                ) {
                    Text(
                        stringResource(R.string.cred_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.cred_cancel))
                }
            },
        )
    }
}

/**
 * Add/edit dialog. The stored secret is never displayed: the password field
 * always starts blank, masked by default with the shared visibility toggle.
 * Leaving the dialog drops the field state (the typed secret is GC'd).
 */
@Composable
private fun CredentialEditDialog(
    title: String,
    initial: CredentialMeta?,
    onDismiss: () -> Unit,
    onSave: (site: String, username: String, secret: String) -> Unit,
) {
    var site by remember(initial) { mutableStateOf(initial?.site.orEmpty()) }
    var username by remember(initial) { mutableStateOf(initial?.username.orEmpty()) }
    var secret by remember(initial) { mutableStateOf("") }
    var secretVisible by rememberSecretVisible()

    val isEdit = initial != null
    // Edit: blank secret keeps the existing one. Add: secret required.
    val valid = site.isNotBlank() && (isEdit || secret.isNotBlank())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = site,
                    onValueChange = { site = it },
                    label = { Text(stringResource(R.string.cred_site)) },
                    placeholder = { Text(stringResource(R.string.cred_site_hint)) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().noOpBringIntoView(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.cred_username)) },
                    placeholder = { Text(stringResource(R.string.cred_username_hint)) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().noOpBringIntoView(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = { Text(stringResource(R.string.cred_password)) },
                    singleLine = true,
                    visualTransformation = secretVisualTransformation(secretVisible),
                    trailingIcon = {
                        SecretVisibilityToggle(secretVisible) { secretVisible = !secretVisible }
                    },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().noOpBringIntoView(),
                )
                if (!valid) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.cred_validation_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(site.trim(), username.trim(), secret) },
                enabled = valid,
            ) {
                Text(stringResource(R.string.cred_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cred_cancel))
            }
        },
    )
}
