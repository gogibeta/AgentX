package com.newoether.agora.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.data.modelAliasDisplayName
import com.newoether.agora.model.ModelId
import com.newoether.agora.ui.components.clearFocusOnTap
import com.newoether.agora.util.noOpBringIntoView
import com.newoether.agora.viewmodel.ChatViewModel

/**
 * Add / edit dialog for a manually configured custom model, including its
 * per-model context-window override. Extracted from SettingsModelsPage to keep
 * that file under the 800-line cap.
 */
@Composable
internal fun CustomModelDialog(
    viewModel: ChatViewModel,
    originalModelId: String?,
    provider: String,
    onProviderChange: (String) -> Unit,
    providerChoices: List<String>,
    providerMenuExpanded: Boolean,
    onProviderMenuExpandedChange: (Boolean) -> Unit,
    modelId: String,
    onModelIdChange: (String) -> Unit,
    alias: String,
    onAliasChange: (String) -> Unit,
    rawAlias: String,
    contextWindow: String,
    onContextWindowChange: (String) -> Unit,
    modelAliases: Map<String, String>,
    modelProviderNames: Map<String, Boolean>,
    customProviders: List<CustomProviderConfig>,
    customModels: Set<String>,
    onDismiss: () -> Unit,
    onDeleteRequest: (String) -> Unit,
) {
    val normalizedProvider = provider.trim()
    val normalizedModelId = modelId.trim()
    val normalizedAlias = alias.trim()
    val unchangedDisplayAlias = originalModelId?.let { model ->
        modelAliasDisplayName(model, modelAliases, customProviders)
    }.orEmpty()
    var showProviderName by remember(originalModelId) {
        mutableStateOf(modelProviderNames[originalModelId] != false)
    }
    val aliasToPersist = if (originalModelId != null) {
        modelAliasToPersist(
            rawAlias = rawAlias,
            initialDisplayAlias = unchangedDisplayAlias,
            editedAlias = alias,
        )
    } else {
        normalizedAlias
    }
    val pendingModelId = if (
        normalizedProvider.isNotEmpty() &&
        normalizedModelId.isNotEmpty()
    ) {
        // Canonicalized exactly like SettingsRepository.addCustomModel stores it.
        ModelId(
            viewModel.settings.stableProviderReference(normalizedProvider),
            normalizedModelId,
        ).prefixed
    } else {
        ""
    }
    val modelAlreadyExists =
        pendingModelId in customModels && pendingModelId != originalModelId
    val canSaveModel = pendingModelId.isNotEmpty() && !modelAlreadyExists

    AlertDialog(
        modifier = Modifier.clearFocusOnTap(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = {
            onProviderMenuExpandedChange(false)
            onDismiss()
        },
        title = {
            Text(
                stringResource(
                    if (originalModelId == null) {
                        R.string.models_add_custom
                    } else {
                        R.string.models_edit_custom
                    }
                ),
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                CustomModelProviderPicker(
                    customModelProvider = provider,
                    customModelProviderMenuExpanded = providerMenuExpanded,
                    providerChoices = providerChoices,
                    onExpandedChange = onProviderMenuExpandedChange,
                    onProviderChange = onProviderChange,
                )

                Spacer(modifier = Modifier.height(12.dp))

                Box(modifier = Modifier.noOpBringIntoView()) {
                    OutlinedTextField(
                        value = modelId,
                        onValueChange = onModelIdChange,
                        singleLine = true,
                        label = { Text(stringResource(R.string.model_id_label)) },
                        isError = modelAlreadyExists,
                        supportingText = if (modelAlreadyExists) {
                            { Text(stringResource(R.string.models_custom_exists)) }
                        } else {
                            null
                        },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Box(modifier = Modifier.noOpBringIntoView()) {
                    OutlinedTextField(
                        value = alias,
                        onValueChange = onAliasChange,
                        singleLine = true,
                        label = { Text(stringResource(R.string.models_alias_hint)) },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Box(modifier = Modifier.noOpBringIntoView()) {
                    OutlinedTextField(
                        value = contextWindow,
                        onValueChange = {
                            onContextWindowChange(it.filter(Char::isDigit).take(9))
                        },
                        singleLine = true,
                        label = { Text(stringResource(R.string.models_context_window)) },
                        placeholder = {
                            Text(stringResource(R.string.models_context_window_blank_hint))
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                ModelProviderNameSwitch(showProviderName) { showProviderName = it }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (originalModelId != null) {
                    TextButton(
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                        onClick = {
                            onProviderMenuExpandedChange(false)
                            onDismiss()
                            onDeleteRequest(originalModelId)
                        },
                    ) {
                        Text(stringResource(R.string.delete))
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                TextButton(
                    onClick = {
                        onProviderMenuExpandedChange(false)
                        onDismiss()
                    }
                ) {
                    Text(stringResource(R.string.provider_cancel))
                }

                TextButton(
                    enabled = canSaveModel,
                    onClick = {
                        // Per-model window, keyed exactly like addCustomModel/updateModel
                        // store the model id (display name -> stable provider id).
                        val windowModelId = ModelId(
                            viewModel.settings.stableProviderReference(normalizedProvider),
                            normalizedModelId,
                        ).prefixed
                        val windowTokens = contextWindow.trim()
                            .toIntOrNull()?.takeIf { it > 0 }
                        if (originalModelId == null) {
                            viewModel.settings.addCustomModel(
                                provider = normalizedProvider,
                                modelName = normalizedModelId,
                                alias = aliasToPersist,
                                showProviderName = showProviderName,
                            )
                            viewModel.settings.saveModelContextWindow(
                                windowModelId,
                                windowTokens,
                            )
                        } else {
                            if (windowTokens == null && windowModelId != originalModelId) {
                                // Clearing while renaming: drop the old entry first so the
                                // rename hook has nothing stale to carry to the new id.
                                viewModel.settings.saveModelContextWindow(
                                    originalModelId,
                                    null,
                                )
                            }
                            viewModel.customModelConfiguration.updateModel(
                                oldModelId = originalModelId,
                                provider = normalizedProvider,
                                modelId = normalizedModelId,
                                alias = aliasToPersist,
                                showProviderName = showProviderName,
                            )
                            // Written under the new id; the rename hook never overwrites an
                            // existing new-id entry, so both DataStore write orders converge.
                            viewModel.settings.saveModelContextWindow(
                                windowModelId,
                                windowTokens,
                            )
                        }
                        onProviderMenuExpandedChange(false)
                        onDismiss()
                    },
                ) {
                    Text(
                        stringResource(
                            if (originalModelId == null) {
                                R.string.add
                            } else {
                                R.string.save
                            }
                        )
                    )
                }
            }
        },
    )
}
