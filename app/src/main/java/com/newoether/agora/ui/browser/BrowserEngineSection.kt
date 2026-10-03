package com.newoether.agora.ui.browser

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.browser.BrowserBackendMode
import com.newoether.agora.browser.BrowserEngineManager
import com.newoether.agora.ui.settings.SettingsGroup

/**
 * Browser engine registry UI (Settings → Browser → Engines).
 *
 * Lists every engine the app can drive: System WebView (built-in) and the
 * cloud tunnel (configured via URL + token). The radio selects the ACTIVE
 * engine — the agent uses whichever the user picked.
 */
@Composable
fun BrowserEngineSection(
    manager: BrowserEngineManager?,
    activeMode: BrowserBackendMode,
    onSelectMode: (BrowserBackendMode) -> Unit,
) {
    if (manager == null) return
    val engines by manager.engines.collectAsState()

    SettingsGroup(
        title = "Browser engines",
        items = buildList {
            engines.forEach { engine ->
                add {
                    EngineRow(
                        engine = engine,
                        active = engine.mode == activeMode,
                        onSelect = { onSelectMode(engine.mode) },
                    )
                }
            }
        },
    )
}

@Composable
private fun EngineRow(
    engine: BrowserEngineManager.EngineState,
    active: Boolean,
    onSelect: () -> Unit,
) {
    val icon = when (engine.mode) {
        BrowserBackendMode.WEBVIEW -> Icons.Default.Language
        BrowserBackendMode.TUNNEL -> Icons.Default.Cloud
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onSelect,
            enabled = engine.status == BrowserEngineManager.EngineStatus.READY,
        ) {
            Icon(
                if (active) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = engine.displayName,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                )
                if (active) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Text(
                text = engine.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (engine.status == BrowserEngineManager.EngineStatus.NOT_INSTALLED) {
            Text(
                text = "Not configured",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(2.dp))
}
