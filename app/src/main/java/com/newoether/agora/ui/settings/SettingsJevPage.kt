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
import com.newoether.agora.ui.components.AgentXDropdownMenuItem
import com.newoether.agora.ui.components.AgentXExposedDropdownMenu
import com.newoether.agora.ui.components.SecretVisibilityToggle
import com.newoether.agora.ui.components.rememberSecretVisible
import com.newoether.agora.ui.components.secretVisualTransformation
import com.newoether.agora.util.noOpBringIntoView
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → Jev: the decision-model configuration.
 *
 * Two providers share this page (same wire format, POST /v1/systemone):
 * - **Jev** (TypeSafe): base https://api.typesafe.ai, model jev-latest (~32k ctx)
 * - **Drex** (Nace AI): base https://drex.nace.ai, selectable model
 *   (drex-v1.5 default, 131k context window — ~4x Jev), nace_sk_ keys (max 3)
 *
 * The agent's decision layer (search re-rank, context pruning, guardrails) uses
 * whichever provider is selected. Each provider keeps its own key list; both
 * rotate round-robin across keys. "Test connection" exercises the active one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsJevPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val jev = viewModel.settings.jevSettings
    val jevEnabled by jev.jevEnabled.collectAsState()
    val jevBaseUrl by jev.jevBaseUrl.collectAsState()
    val jevModel by jev.jevModel.collectAsState()
    val jevKeys by jev.jevApiKeys.collectAsState()
    val provider by jev.decisionProvider.collectAsState()
    val drexModel by jev.drexModel.collectAsState()
    val drexKeys by jev.drexApiKeys.collectAsState()
    val jevConfigured by jev.jevConfigured.collectAsState()

    var bulkText by remember { mutableStateOf("") }
    var keysVisible by rememberSecretVisible()
    var testState by remember { mutableStateOf<JevTestState>(JevTestState.Idle) }
    val scope = rememberCoroutineScope()
    val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()

    val isDrex = provider == JevPreferenceStore.PROVIDER_DREX
    val basePlaceholder = if (isDrex) JevPreferenceStore.DEFAULT_DREX_BASE_URL else JevPreferenceStore.DEFAULT_JEV_BASE_URL

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
                    // Provider selector: Jev (TypeSafe) or Drex (Nace AI)
                    add {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(
                                stringResource(R.string.jev_provider),
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                            )
                            Text(
                                stringResource(R.string.jev_provider_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                SegmentedButton(
                                    selected = !isDrex,
                                    onClick = { jev.setDecisionProvider(JevPreferenceStore.PROVIDER_JEV) },
                                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                                    label = { Text("Jev") },
                                )
                                SegmentedButton(
                                    selected = isDrex,
                                    onClick = { jev.setDecisionProvider(JevPreferenceStore.PROVIDER_DREX) },
                                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                                    label = { Text("Drex") },
                                )
                            }
                        }
                    }
                    // Base URL (placeholder follows the active provider)
                    add {
                        JevTextRow(
                            label = stringResource(R.string.jev_base_url),
                            value = jevBaseUrl,
                            placeholder = basePlaceholder,
                            onValueChange = { jev.setJevBaseUrl(it) },
                            leadingIcon = { Icon(Icons.Default.Link, null, tint = MaterialTheme.colorScheme.primary) },
                        )
                    }
                    if (isDrex) {
                        // Drex model dropdown
                        add {
                            DrexModelDropdown(
                                selected = drexModel,
                                onSelected = { jev.setDrexModel(it) },
                            )
                        }
                    } else {
                        // Jev model name (typed — no picker)
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
                    }
                    // API keys (bulk paste + list with remove) — per active provider
                    add {
                        if (isDrex) {
                            JevKeysSection(
                                count = drexKeys.size,
                                title = stringResource(R.string.drex_keys_title, drexKeys.size),
                                desc = stringResource(R.string.drex_keys_desc),
                                hint = stringResource(R.string.drex_keys_hint),
                                bulkText = bulkText,
                                onBulkTextChange = { bulkText = it },
                                keysVisible = keysVisible,
                                onToggleKeysVisible = { keysVisible = !keysVisible },
                                onAddKeys = {
                                    val parsed = bulkText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                                    if (parsed.isNotEmpty()) {
                                        jev.setDrexApiKeys(drexKeys + parsed)
                                        bulkText = ""
                                    }
                                },
                                keys = drexKeys,
                                onRemoveKey = { jev.removeDrexApiKey(it) },
                            )
                        } else {
                            JevKeysSection(
                                count = jevKeys.size,
                                title = stringResource(R.string.jev_keys_title, jevKeys.size),
                                desc = stringResource(R.string.jev_keys_desc),
                                hint = stringResource(R.string.jev_keys_hint),
                                bulkText = bulkText,
                                onBulkTextChange = { bulkText = it },
                                keysVisible = keysVisible,
                                onToggleKeysVisible = { keysVisible = !keysVisible },
                                onAddKeys = {
                                    val parsed = bulkText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                                    if (parsed.isNotEmpty()) {
                                        jev.setJevApiKeys(jevKeys + parsed)
                                        bulkText = ""
                                    }
                                },
                                keys = jevKeys,
                                onRemoveKey = { jev.removeJevApiKey(it) },
                            )
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
    val providerName = if (jev.decisionProvider.value == JevPreferenceStore.PROVIDER_DREX) "Drex" else "Jev"
    return@withContext try {
        val decision = TypeSafeClient.decide(
            apiKey = key,
            baseUrl = jev.effectiveDecisionBaseUrl().ifBlank { null },
            model = jev.effectiveDecisionModel(),
            state = kotlinx.serialization.json.JsonPrimitive("2 + 2 = 4"),
            questions = mapOf(
                "sanity" to TypeSafeClient.NoulQuestion(
                    key = "sanity",
                    instructions = "Is the arithmetic statement in the state correct?",
                ),
            ),
            timeoutMs = jev.effectiveDecisionTimeoutMs(),
        )
        val prob = (decision.answers["sanity"] as? TypeSafeClient.JevAnswer.Noul)?.probability
        if (prob != null && prob > 0.5) {
            JevTestState.Ok("$providerName is working — test decision answered ${"%.0f".format(prob * 100)}% (model: ${decision.model}).")
        } else {
            JevTestState.Failed("$providerName answered but the sanity check failed (probability ${prob}). Check the model name.")
        }
    } catch (e: Exception) {
        val err = JevDecisions.classifyError(key, e)
        JevTestState.Failed(JevDecisions.describeError(err))
    }
}

/** Drex model picker — drex-v1.5 default (131k ctx), v1.0, or the moving drex-latest alias. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DrexModelDropdown(selected: String, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = 2.dp)) {
                Icon(Icons.Default.Psychology, null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.drex_model),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                )
                Text(
                    stringResource(R.string.drex_model_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                    OutlinedTextField(
                        value = selected,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    AgentXExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        JevPreferenceStore.DREX_MODELS.forEach { model ->
                            AgentXDropdownMenuItem(
                                text = { Text(model) },
                                onClick = {
                                    onSelected(model)
                                    expanded = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Shared bulk-paste key manager, parametrized per decision provider. */
@Composable
private fun JevKeysSection(
    count: Int,
    title: String,
    desc: String,
    hint: String,
    bulkText: String,
    onBulkTextChange: (String) -> Unit,
    keysVisible: Boolean,
    onToggleKeysVisible: () -> Unit,
    onAddKeys: () -> Unit,
    keys: List<String>,
    onRemoveKey: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Key, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.noOpBringIntoView()) {
            OutlinedTextField(
                value = bulkText,
                onValueChange = onBulkTextChange,
                placeholder = { Text(hint) },
                visualTransformation = secretVisualTransformation(keysVisible),
                trailingIcon = { SecretVisibilityToggle(keysVisible) { onToggleKeysVisible() } },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onAddKeys, enabled = bulkText.isNotBlank()) {
            Text(stringResource(R.string.jev_keys_add))
        }
        keys.forEach { key ->
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
                TextButton(onClick = { onRemoveKey(key) }) {
                    Text(stringResource(R.string.jev_keys_remove))
                }
            }
        }
        if (count == 0) {
            Text(
                stringResource(R.string.jev_keys_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
