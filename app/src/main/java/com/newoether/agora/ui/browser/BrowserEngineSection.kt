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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.browser.BrowserBackendMode
import com.newoether.agora.browser.BrowserEngineManager
import com.newoether.agora.ui.settings.SettingsGroup
import kotlinx.coroutines.launch

/**
 * Browser engine registry UI (Settings → Browser → Engines).
 *
 * Lists every engine the app can drive: built-ins (System WebView, GeckoView),
 * the downloadable Chromium sandbox (install/uninstall to free ~1 GB), and the
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
    val scope = rememberCoroutineScope()

    SettingsGroup(
        title = "Browser engines",
        items = buildList {
            engines.forEach { engine ->
                add {
                    EngineRow(
                        engine = engine,
                        active = engine.mode == activeMode,
                        onSelect = { onSelectMode(engine.mode) },
                        onUninstall = {
                            scope.launch { manager.uninstallChromium() }
                        },
                        onInstall = {
                            scope.launch { manager.installChromium() }
                        },
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
    onUninstall: () -> Unit,
    onInstall: () -> Unit,
) {
    val icon = when (engine.mode) {
        BrowserBackendMode.WEBVIEW -> Icons.Default.Language
        BrowserBackendMode.GECKOVIEW -> Icons.Default.Language
        BrowserBackendMode.LOCAL -> Icons.Default.Download
        BrowserBackendMode.TUNNEL -> Icons.Default.Cloud
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onSelect, enabled = engine.status == BrowserEngineManager.EngineStatus.READY) {
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
            if (engine.sizeBytes > 0) {
                Text(
                    text = formatBytes(engine.sizeBytes) + " on disk",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when (engine.status) {
            BrowserEngineManager.EngineStatus.READY -> {
                // Uninstall only for the downloadable Chromium sandbox.
                if (engine.kind == BrowserEngineManager.EngineKind.DOWNLOADABLE) {
                    TextButton(onClick = onUninstall) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Uninstall")
                    }
                }
            }
            BrowserEngineManager.EngineStatus.NOT_INSTALLED -> {
                if (engine.kind == BrowserEngineManager.EngineKind.DOWNLOADABLE) {
                    TextButton(onClick = onInstall) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.browser_engine_install))
                    }
                } else {
                    Text(
                        text = when (engine.kind) {
                            BrowserEngineManager.EngineKind.CONFIGURED -> "Not configured"
                            else -> ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            BrowserEngineManager.EngineStatus.WORKING -> {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            BrowserEngineManager.EngineStatus.ERROR -> {
                Text(
                    text = "Error — retry",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
    Spacer(Modifier.height(2.dp))
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}
