package com.newoether.agora.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.agent.AgentProjectScope

/**
 * Prompts for the project folder a plan/build agent mode is scoped to.
 *
 * The user types a folder name or path inside the shared workspace; it is
 * normalized with [AgentProjectScope.normalizeFolder] and rejected when it
 * escapes the workspace. Whole-workspace access is a separate explicit
 * checkbox — it can never be smuggled in via a `..` path.
 */
@Composable
fun ProjectFolderPickerDialog(
    modeLabel: String,
    initialFolder: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(initialFolder) { mutableStateOf(initialFolder) }
    var useAll by remember(initialFolder) {
        mutableStateOf(initialFolder == AgentProjectScope.WORKSPACE_ROOT)
    }
    val normalized = if (useAll) AgentProjectScope.WORKSPACE_ROOT
        else AgentProjectScope.normalizeFolder(draft)
    val invalid = !useAll && draft.isNotBlank() && normalized == null
    val canConfirm = useAll || normalized != null

    AlertDialog(
        modifier = Modifier.clearFocusOnTap(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.project_folder_title),
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.project_folder_prompt, modeLabel),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it; useAll = false },
                    enabled = !useAll,
                    label = { Text(stringResource(R.string.project_folder_hint)) },
                    singleLine = true,
                    isError = invalid,
                    supportingText = {
                        when {
                            invalid -> Text(
                                stringResource(R.string.project_folder_invalid),
                                color = MaterialTheme.colorScheme.error,
                            )
                            normalized != null -> Text(
                                normalized,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useAll, onCheckedChange = { useAll = it })
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.project_folder_use_all),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { normalized?.let(onConfirm) }, enabled = canConfirm) {
                Text(stringResource(R.string.project_folder_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
