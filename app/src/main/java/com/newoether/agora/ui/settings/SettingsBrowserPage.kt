package com.newoether.agora.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.browser.BrowserBackendMode
import com.newoether.agora.browser.BrowserPreferenceStore
import com.newoether.agora.browser.TunnelValidation
import com.newoether.agora.browser.TunnelValidationState
import com.newoether.agora.ui.browser.LocalBrowserDataController
import com.newoether.agora.ui.browser.LocalBrowserPreferenceStore
import com.newoether.agora.ui.browser.LocalBrowserWatchController
import com.newoether.agora.ui.components.AgentXDropdownMenuItem
import com.newoether.agora.ui.components.AgentXExposedDropdownMenu
import com.newoether.agora.ui.components.SecretVisibilityToggle
import com.newoether.agora.ui.components.rememberSecretVisible
import com.newoether.agora.ui.components.secretVisualTransformation
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import com.newoether.agora.util.Constants
import com.newoether.agora.util.noOpBringIntoView
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Settings → Browser: backend mode selection (§1.3.0), tunnel URL + client
 * token, reachability validation, clear-browser-data, and a link to the live
 * watch panel.
 *
 * State lives in stream A's [BrowserPreferenceStore] (provided through
 * [LocalBrowserPreferenceStore] once registered in AppContainer); the wipe
 * button calls stream A's launcher through [LocalBrowserDataController]. Until
 * those are registered the page renders an unavailable note — nothing here
 * depends on the engine existing.
 *
 * Security: the client token is stored encrypted (SecretCrypto) and is never
 * logged. The validation probe sends it as a `?token=` query parameter (the
 * verified Cloudflare relay contract, §1.6) over HTTPS only; probe outcomes
 * recorded in the store carry machine-readable reason codes, never the token.
 */
@Composable
fun SettingsBrowserPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val store = LocalBrowserPreferenceStore.current
    CollapsingSettingsScaffold(
        title = stringResource(R.string.browser_title),
        onBack = onBack,
    ) {
        if (store == null) {
            SettingsGroupColumn {
                SettingsGroup(title = stringResource(R.string.browser_title), items = listOf {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.browser_unavailable)) },
                        leadingContent = {
                            Icon(Icons.Default.Web, null, tint = MaterialTheme.colorScheme.primary)
                        },
                    )
                })
            }
        } else {
            BrowserSettingsContent(store, viewModel, onBack)
        }
    }
}

@Composable
private fun BrowserSettingsContent(
    store: BrowserPreferenceStore,
    viewModel: ChatViewModel,
    onBack: () -> Unit,
) {
    val dataController = LocalBrowserDataController.current
    val watchController = LocalBrowserWatchController.current
    val browserEnabled by store.browserEnabled.collectAsState()
    val backendMode by store.backendMode.collectAsState()
    val tunnelUrl by store.tunnelUrl.collectAsState()
    val tunnelToken by store.tunnelClientToken.collectAsState()
    val validation by store.tunnelValidation.collectAsState()
    val validatedAt by store.tunnelValidatedAtMillis.collectAsState()
    val sessionActive by (watchController?.sessionActive ?: emptyFlow<Boolean>())
        .collectAsState(initial = false)
    var tokenVisible by rememberSecretVisible()
    var probeState by remember { mutableStateOf<TunnelProbeState>(TunnelProbeState.Idle) }
    var showClearConfirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val noUrlMessage = stringResource(R.string.browser_no_url)
    val clearDoneMessage = stringResource(R.string.browser_clear_data_done)
    val clearFailedMessage = stringResource(R.string.browser_clear_data_failed)

    SettingsGroupColumn {
        SettingsGroup(title = stringResource(R.string.browser_title), items = buildList {
            add {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.browser_enable)) },
                    supportingContent = { Text(stringResource(R.string.browser_enable_desc)) },
                    leadingContent = {
                        Icon(Icons.Default.Web, null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = {
                        Switch(
                            checked = browserEnabled,
                            onCheckedChange = { store.setBrowserEnabled(it) },
                        )
                    },
                )
            }
            add {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    BackendModeDropdown(
                        current = backendMode,
                        onSelect = { mode ->
                            if (mode == BrowserBackendMode.TUNNEL && tunnelUrl.isBlank()) {
                                viewModel.emitSnackbar(noUrlMessage)
                            } else {
                                store.setBackendMode(mode)
                            }
                        },
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.browser_backend_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (backendMode == BrowserBackendMode.TUNNEL && tunnelUrl.isBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.browser_backend_fallback_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        })

        // The tunnel group is always visible (not only in TUNNEL mode): the mode
        // dropdown refuses to switch to TUNNEL while the URL is blank, so hiding
        // the fields behind the mode made the URL impossible to enter.
        SettingsGroup(title = stringResource(R.string.browser_tunnel_group), items = buildList {
                add {
                    SettingsIconContent(icon = Icons.Default.Link) {
                        Text(
                            text = stringResource(R.string.browser_tunnel_url),
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        )
                        Text(
                            text = stringResource(R.string.browser_tunnel_url_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Box(Modifier.noOpBringIntoView().padding(top = 8.dp)) {
                            OutlinedTextField(
                                value = tunnelUrl,
                                onValueChange = { store.setTunnelUrl(it) },
                                placeholder = { Text(stringResource(R.string.browser_tunnel_url_hint)) },
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                        }
                    }
                }
                add {
                    SettingsIconContent(icon = Icons.Default.Key) {
                        Text(
                            text = stringResource(R.string.browser_client_token),
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        )
                        Text(
                            text = stringResource(R.string.browser_client_token_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Box(Modifier.noOpBringIntoView().padding(top = 8.dp)) {
                            OutlinedTextField(
                                value = tunnelToken,
                                onValueChange = { store.setTunnelClientToken(it) },
                                placeholder = { Text(stringResource(R.string.browser_client_token_hint)) },
                                visualTransformation = secretVisualTransformation(tokenVisible),
                                trailingIcon = {
                                    SecretVisibilityToggle(tokenVisible) { tokenVisible = !tokenVisible }
                                },
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                        }
                    }
                }
                add {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Button(
                            onClick = {
                                probeState = TunnelProbeState.Running
                                scope.launch {
                                    val result = probeTunnel(tunnelUrl, tunnelToken)
                                    when (result) {
                                        is TunnelProbeResult.Ok -> {
                                            store.markTunnelValidation(TunnelValidationState.VALID)
                                            probeState = TunnelProbeState.Ok(result.browser)
                                        }
                                        is TunnelProbeResult.Fail -> {
                                            store.markTunnelValidation(
                                                TunnelValidationState.INVALID,
                                                result.reason,
                                            )
                                            probeState = TunnelProbeState.Failed(result.reason)
                                        }
                                    }
                                }
                            },
                            enabled = tunnelUrl.isNotBlank() && probeState != TunnelProbeState.Running,
                        ) {
                            Text(stringResource(R.string.browser_validate))
                        }
                        Spacer(Modifier.height(8.dp))
                        TunnelValidationStatus(
                            probeState = probeState,
                            validation = validation,
                            validatedAt = validatedAt,
                        )
                    }
                }
            })

        SettingsGroup(title = stringResource(R.string.browser_data_group), items = listOf {
            SettingsItem(
                headlineContent = { Text(stringResource(R.string.browser_clear_data)) },
                supportingContent = {
                    Text(
                        stringResource(
                            if (dataController == null) R.string.browser_clear_data_unavailable
                            else R.string.browser_clear_data_desc,
                        ),
                    )
                },
                leadingContent = {
                    Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.primary)
                },
                trailingContent = {
                    TextButton(
                        onClick = { showClearConfirm = true },
                        enabled = dataController != null,
                    ) {
                        Text(stringResource(R.string.browser_clear_data))
                    }
                },
            )
        })

        SettingsGroup(title = stringResource(R.string.browser_watch_group), items = listOf {
            SettingsItem(
                headlineContent = { Text(stringResource(R.string.browser_watch_open)) },
                supportingContent = { Text(stringResource(R.string.browser_watch_open_desc)) },
                leadingContent = {
                    Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
                },
                trailingContent = {
                    Button(
                        onClick = onBack,
                        enabled = sessionActive,
                    ) {
                        Text(stringResource(R.string.browser_watch_open))
                    }
                },
            )
        })
    }

    if (showClearConfirm) {
        val controller = dataController
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.browser_clear_data_confirm_title)) },
            text = { Text(stringResource(R.string.browser_clear_data_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    if (controller != null) {
                        scope.launch {
                            val ok = runCatching { controller.clearBrowserData() }.getOrDefault(false)
                            viewModel.emitSnackbar(if (ok) clearDoneMessage else clearFailedMessage)
                        }
                    }
                }) {
                    Text(
                        stringResource(R.string.browser_clear_data_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/** Backend mode selector. Raw DropdownMenu is banned — this uses the AgentX wrappers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackendModeDropdown(
    current: BrowserBackendMode,
    onSelect: (BrowserBackendMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = stringResource(
                when (current) {
                    BrowserBackendMode.LOCAL -> R.string.browser_backend_local
                    BrowserBackendMode.TUNNEL -> R.string.browser_backend_tunnel
                    BrowserBackendMode.WEBVIEW -> R.string.browser_backend_webview
                },
            ),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.browser_backend)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true),
        )
        AgentXExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            AgentXDropdownMenuItem(
                text = {
                    Column {
                        Text(
                            stringResource(R.string.browser_backend_local),
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        )
                        Text(
                            stringResource(R.string.browser_backend_local_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                onClick = {
                    expanded = false
                    onSelect(BrowserBackendMode.LOCAL)
                },
            )
            AgentXDropdownMenuItem(
                text = {
                    Column {
                        Text(
                            stringResource(R.string.browser_backend_tunnel),
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        )
                        Text(
                            stringResource(R.string.browser_backend_tunnel_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                onClick = {
                    expanded = false
                    onSelect(BrowserBackendMode.TUNNEL)
                },
            )
            AgentXDropdownMenuItem(
                text = {
                    Column {
                        Text(
                            stringResource(R.string.browser_backend_webview),
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        )
                        Text(
                            stringResource(R.string.browser_backend_webview_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                onClick = {
                    expanded = false
                    onSelect(BrowserBackendMode.WEBVIEW)
                },
            )
        }
    }
}

@Composable
private fun TunnelValidationStatus(
    probeState: TunnelProbeState,
    validation: TunnelValidation,
    validatedAt: Long,
) {
    when (val s = probeState) {
        TunnelProbeState.Idle -> {
            // Fall back to the last persisted validation, if any.
            when (validation.state) {
                TunnelValidationState.NOT_VALIDATED -> {
                    Text(
                        stringResource(R.string.browser_validated_never),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TunnelValidationState.VALID -> {
                    ValidationRow(
                        ok = true,
                        text = stringResource(
                            R.string.browser_validated_at,
                            formatTime(validatedAt),
                        ),
                    )
                }
                TunnelValidationState.INVALID -> {
                    ValidationRow(
                        ok = false,
                        text = stringResource(R.string.browser_validate_fail, validation.error),
                    )
                }
            }
        }
        TunnelProbeState.Running -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.browser_validate_running))
            }
        }
        is TunnelProbeState.Ok -> {
            ValidationRow(
                ok = true,
                text = stringResource(R.string.browser_validate_ok, s.browser),
            )
        }
        is TunnelProbeState.Failed -> {
            ValidationRow(
                ok = false,
                text = stringResource(R.string.browser_validate_fail, s.reason),
            )
        }
    }
}

@Composable
private fun ValidationRow(ok: Boolean, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            if (ok) Icons.Default.CheckCircle else Icons.Default.Error,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun formatTime(millis: Long): String =
    if (millis <= 0L) ""
    else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

private sealed interface TunnelProbeState {
    data object Idle : TunnelProbeState
    data object Running : TunnelProbeState
    data class Ok(val browser: String) : TunnelProbeState
    data class Failed(val reason: String) : TunnelProbeState
}

private sealed interface TunnelProbeResult {
    data class Ok(val browser: String) : TunnelProbeResult
    data class Fail(val reason: String) : TunnelProbeResult
}

/**
 * Reachability check: `GET {url}/json/version` → 200. HTTPS only; the token (if
 * set) travels as a `?token=` query parameter per the verified relay contract
 * (§1.6) and is NEVER logged — [TunnelProbeResult.Fail.reason] carries only
 * machine-readable codes (`bad_url`, `http_403`, `timeout`, `network`), never
 * the URL or the token. Browser-like UA: Cloudflare's edge 403-blocks bare
 * library user-agents (§1.6).
 */
private suspend fun probeTunnel(url: String, token: String): TunnelProbeResult =
    withContext(Dispatchers.IO) {
        val httpUrl = url.trim().toHttpUrlOrNull()
        if (httpUrl == null || httpUrl.scheme != "https" || httpUrl.host.isBlank()) {
            return@withContext TunnelProbeResult.Fail("bad_url")
        }
        // NOTE: the worker only serves CDP under /json/* — probing the bare
        // root path always 404s even with a correct token (v2.3.1 bug).
        val target = httpUrl.newBuilder()
            .addPathSegment("json")
            .addPathSegment("version")
            .apply {
                if (token.isNotBlank()) addQueryParameter("token", token)
            }.build()
        val client = OkHttpClient.Builder()
            .callTimeout(10, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()
        try {
            val request = Request.Builder()
                .url(target)
                .header("User-Agent", Constants.WEB_FETCH_USER_AGENT)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext TunnelProbeResult.Fail("http_${response.code}")
                }
                val browser = runCatching {
                    JSONObject(response.body?.string().orEmpty()).optString("Browser", "")
                }.getOrDefault("").ifBlank { "CDP" }
                TunnelProbeResult.Ok(browser)
            }
        } catch (_: SocketTimeoutException) {
            TunnelProbeResult.Fail("timeout")
        } catch (_: IOException) {
            TunnelProbeResult.Fail("network")
        }
    }
