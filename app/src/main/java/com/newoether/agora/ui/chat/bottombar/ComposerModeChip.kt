package com.newoether.agora.ui.chat.bottombar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.components.AgentXDropdownMenu
import com.newoether.agora.ui.components.AgentXDropdownMenuItem

/**
 * In-chat Chat / Plan / Build switch bound to the same `agentMode` setting as
 * Settings → Agent. Build mode unlocks agent tools (shell, artifacts,
 * multi-model); Plan is read-only research; Chat answers normally.
 */
@Composable
internal fun ComposerModeChip(
    agentMode: String,
    onAgentModeChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val labelRes = when (agentMode) {
        "plan" -> R.string.agent_mode_plan
        "build" -> R.string.agent_mode_build
        else -> R.string.agent_mode_off
    }
    Box(modifier = modifier.padding(start = 12.dp, top = 4.dp)) {
        Row {
            AssistChip(
                onClick = { expanded = true },
                label = { Text(stringResource(labelRes)) },
                leadingIcon = {
                    Icon(
                        Icons.Default.Psychology,
                        contentDescription = null,
                        modifier = Modifier.size(AssistChipDefaults.IconSize),
                    )
                },
                trailingIcon = {
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                },
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        AgentXDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val options = listOf(
                "off" to R.string.agent_mode_off,
                "plan" to R.string.agent_mode_plan,
                "build" to R.string.agent_mode_build,
            )
            options.forEach { (key, res) ->
                AgentXDropdownMenuItem(
                    text = { Text(stringResource(res)) },
                    onClick = {
                        onAgentModeChange(key)
                        expanded = false
                    },
                )
            }
        }
    }
}
