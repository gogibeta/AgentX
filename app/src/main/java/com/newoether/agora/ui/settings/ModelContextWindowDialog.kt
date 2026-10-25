package com.newoether.agora.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.model.ContextBudget
import com.newoether.agora.ui.components.clearFocusOnTap
import com.newoether.agora.util.noOpBringIntoView

/**
 * Per-model context window editor. Blank / "use global" clears the override so the
 * model falls back to the global default window; any positive value is stored for
 * this model id and picked up automatically when the model becomes active.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelContextWindowDialog(
    displayName: String,
    currentOverride: Int?,
    globalDefault: Int,
    onSave: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(currentOverride) { mutableStateOf(currentOverride?.toString().orEmpty()) }
    val parsed = draft.trim().toIntOrNull()?.takeIf { it > 0 }
    val draftValid = draft.isBlank() || parsed != null
    val effective = ContextBudget.normalize(parsed ?: currentOverride ?: globalDefault)

    AlertDialog(
        modifier = Modifier.clearFocusOnTap(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.models_context_window),
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    displayName,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(
                        R.string.models_context_window_effective,
                        ContextBudget.compactLabel(effective),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.models_context_window_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ContextBudget.PRESETS.forEach { preset ->
                        val selected = parsed == preset
                        FilterChip(
                            selected = selected,
                            onClick = { draft = if (selected) "" else preset.toString() },
                            label = { Text(ContextBudget.compactLabel(preset)) },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.filter(Char::isDigit).take(9) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.models_context_window_custom_hint)) },
                    placeholder = { Text(stringResource(R.string.models_context_window_blank_hint)) },
                    isError = !draftValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .noOpBringIntoView(),
                )
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(
                    onClick = {
                        onSave(null)
                        onDismiss()
                    },
                ) {
                    Text(
                        stringResource(
                            R.string.models_context_window_use_global,
                            ContextBudget.compactLabel(ContextBudget.normalize(globalDefault)),
                        ),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = draftValid && parsed != null,
                onClick = {
                    onSave(parsed)
                    onDismiss()
                },
            ) { Text(stringResource(R.string.provider_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.provider_cancel)) }
        },
    )
}
