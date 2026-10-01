package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.api.typesafe.JevDecisions
import com.newoether.agora.api.typesafe.TypeSafeClient
import com.newoether.agora.data.JevPreferenceStore
import com.newoether.agora.ui.components.SecretVisibilityToggle
import com.newoether.agora.ui.components.rememberSecretVisible
import com.newoether.agora.ui.components.secretVisualTransformation
import com.newoether.agora.util.noOpBringIntoView
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → Jev: the TypeSafe decision-model configuration.
 *
 * Jev is NOT a chat provider — it answers typed Choice/Score/Noul questions
 * that sharpen the agent (search re-rank, context pruning, guardrails). It has
 * its own page (like Web Search) instead of living in the provider list:
 * - master enable toggle
 * - base URL (default https://api.typesafe.ai; proxy/gateway/self-hosted OK)
 * - model name (typed, default jev-latest — no provider model picker)
 * - multiple API keys with bulk paste (one per line); decisions rotate
 *   round-robin across keys to spread load and dodge rate limits
 * - "Test connection" runs one tiny Noul decision and reports exactly what
 *   failed (bad key, bad URL/model, network) instead of silent nulls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsJevPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val jev = viewModel.settings.jevSettings
    val jevEnabled by jev.jevEnabled.collectAsState()
    val jevBaseUrl by jev.jevBaseUrl.collectAsState()
    val jevModel by jev.jevModel.collectAsState()
    val jevKeys by jev.jevApiKeys.collectAsState()
    val jevConfigured by jev.jevConfigured.collectAsState()

    var bulkText by remember { mutableStateOf("") }
    var keysVisible by rememberSecretVisible()
    var testState by remember { mutableStateOf<JevTestState>(JevTestState.Idle) }
    val scope = rememberCoroutineScope()
    val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()

    CollapsingSettingsScaffold(
        title = stringResource(R.string.jev_title),
        onBack = onBack,
        floatingActionButton = { if (showDocFab) DocumentationFab("jev.md") }
    ) {
        SettingsGroupColumn {
            SettingsGroup(title = stringResource(R.string.jev_title), items = buildList {
                add {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.jev_enable)) },
                        supportingContent = { Text(stringResource(R.string.jev_enable_desc)) },
                        leadingContent = { Icon(Icons.Default.Psychology, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = {
                            Switch(checked = jevEnabled, onCheckedChange = { jev.setJevEnabled(it) })
                        },
                        modifier = Modifier.clickable { jev.setJevEnabled(!jevEnabled) }
                    )
                }

                if (jevEnabled) {
                    // Base URL
                    add {
                        JevTextRow(
                            label = stringResource(R.string.jev_base_url),
                            value = jevBaseUrl,
                            placeholder = JevPreferenceStore.DEFAULT_JEV_BASE_URL,
                            onValueChange = { jev.setJevBaseUrl(it) },
                            leadingIcon = { Icon(Icons.Default.Link, null, tint = MaterialTheme.colorScheme.primary) },
                        )
                    }
                    // Model name (typed — no picker)
                    add {
                        JevTextRow(
                            label = stringResource(R.string.jev_model),
                            value = if (jevModel == JevPreferenceStore.DEFAULT_JEV_MODEL) "" else jevModel,
                            placeholder = JevPreferenceStore.DEFAULT_JEV_MODEL,
                            onValueChange = { jev.setJevModel(it.ifBlank { JevPreferenceStore.DEFAULT_JEV_MODEL }) },
                            leadingIcon = { Icon(Icons.Default.Psychology, null, tint = MaterialTheme.colorScheme.primary) },
                            supporting = stringResource(R.string.jev_model_desc),
                        )
                    }
                    // API keys (bulk paste + list with remove)
                    add {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Key, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        stringResource(R.string.jev_keys_title, jevKeys.size),
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                    )
                                    Text(
                                        stringResource(R.string.jev_keys_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Box(Modifier.noOpBringIntoView()) {
                                OutlinedTextField(
                                    value = bulkText,
                                    onValueChange = { bulkText = it },
                                    placeholder = { Text(stringResource(R.string.jev_keys_hint)) },
                                    visualTransformation = secretVisualTransformation(keysVisible),
                                    trailingIcon = { SecretVisibilityToggle(keysVisible) { keysVisible = !keysVisible } },
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 2,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    val parsed = bulkText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                                    if (parsed.isNotEmpty()) {
                                        jev.setJevApiKeys(jevKeys + parsed)
                                        bulkText = ""
                                    }
                                },
                                enabled = bulkText.isNotBlank(),
                            ) {
                                Text(stringResource(R.string.jev_keys_add))
                            }
                            jevKeys.forEach { key ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = maskKey(key),
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    TextButton(onClick = { jev.removeJevApiKey(key) }) {
                                        Text(stringResource(R.string.jev_keys_remove))
                                    }
                                }
                            }
                        }
                    }
                    // Test connection
                    add {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Button(
                                onClick = {
                                    testState = JevTestState.Running
                                    scope.launch {
                                        testState = runJevTest(jev)
                                    }
                                },
                                enabled = testState != JevTestState.Running,
                            ) {
                                Text(stringResource(R.string.jev_test))
                            }
                            Spacer(Modifier.height(8.dp))
                            when (val s = testState) {
                                JevTestState.Idle -> {}
                                JevTestState.Running -> {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(R.string.jev_test_running))
                                    }
                                }
                                is JevTestState.Ok -> {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(8.dp))
                                        Text(s.message, color = MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                                is JevTestState.Failed -> {
                                    Row(verticalAlignment = Alignment.Top) {
                                        Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error)
                                        Spacer(Modifier.width(8.dp))
                                        Text(s.message, color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            })

            if (jevEnabled && jevConfigured) {
                SettingsGroup(title = stringResource(R.string.jev_powers_title), items = buildList {
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.jev_powers_desc)) },
                        )
                    }
                })
            }
        }
        if (showDocFab) { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

private sealed interface JevTestState {
    data object Idle : JevTestState
    data object Running : JevTestState
    data class Ok(val message: String) : JevTestState
    data class Failed(val message: String) : JevTestState
}

/** One tiny Noul decision ("is 2+2=4 true?") — proves key + URL + model all work. */
private suspend fun runJevTest(jev: JevPreferenceStore): JevTestState = withContext(Dispatchers.IO) {
    val key = jev.pickKey()
        ?: return@withContext JevTestState.Failed("No API key — paste at least one key above.")
    return@withContext try {
        val decision = TypeSafeClient.decide(
            apiKey = key,
            baseUrl = jev.effectiveBaseUrl().ifBlank { null },
            model = jev.jevModel.value,
            state = kotlinx.serialization.json.JsonPrimitive("2 + 2 = 4"),
            questions = mapOf(
                "sanity" to TypeSafeClient.NoulQuestion(
                    key = "sanity",
                    instructions = "Is the arithmetic statement in the state correct?",
                ),
            ),
        )
        val prob = (decision.answers["sanity"] as? TypeSafeClient.JevAnswer.Noul)?.probability
        if (prob != null && prob > 0.5) {
            JevTestState.Ok("Jev is working — test decision answered ${"%.0f".format(prob * 100)}% (model: ${decision.model}).")
        } else {
            JevTestState.Failed("Jev answered but the sanity check failed (probability ${prob}). Check the model name.")
        }
    } catch (e: Exception) {
        val err = JevDecisions.classifyError(key, e)
        JevTestState.Failed(JevDecisions.describeError(err))
    }
}

private fun maskKey(key: String): String =
    if (key.length <= 8) "••••" else key.take(4) + "••••" + key.takeLast(4)

@Composable
private fun JevTextRow(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    leadingIcon: @Composable () -> Unit,
    supporting: String? = null,
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
                    )
                }
            }
        }
    }
}
