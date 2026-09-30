package com.newoether.agora.ui.settings

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.newoether.agora.R
import com.newoether.agora.diagnostics.DiagnosticShareBundle
import com.newoether.agora.diagnostics.StructuredDiagnosticCategory
import com.newoether.agora.diagnostics.StructuredDiagnosticEvent
import com.newoether.agora.diagnostics.StructuredDiagnostics
import com.newoether.agora.viewmodel.ChatViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Visible Settings → Diagnostics page (§6.1): one-tap Share Logs, the debug
 * overlay toggle, and the live structured event tail (filterable by category,
 * tap an event for its detail JSON). Works offline — events persist to a
 * bounded JSONL file.
 */
@Composable
fun SettingsDiagnosticsPage(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val events by StructuredDiagnostics.events.collectAsState()
    var selectedCategory by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedEvent by remember { mutableStateOf<StructuredDiagnosticEvent?>(null) }
    var sharing by remember { mutableStateOf(false) }
    val overlayEnabled by viewModel.settings.diagnosticsPreferenceStore
        .debugOverlayEnabled.collectAsState()
    val chooserTitle = stringResource(R.string.developer_options_export_share_title)
    val exportFailedMessage = stringResource(R.string.developer_options_export_failed)

    selectedEvent?.let { event ->
        AlertDialog(
            onDismissRequest = { selectedEvent = null },
            title = {
                Text(
                    text = "#${event.sequenceLabel()} ${event.name}",
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                SelectionContainer {
                    Text(
                        text = remember(event) { event.prettyJson() },
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedEvent = null }) {
                    Text(stringResource(R.string.ok))
                }
            },
        )
    }

    CollapsingSettingsLazyScaffold(
        title = stringResource(R.string.diagnostics_title),
        onBack = onBack,
    ) {
        item(key = "diagnostics-share") {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.diagnostics_share_logs),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.diagnostics_share_logs_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            if (!sharing) {
                                sharing = true
                                scope.launch {
                                    try {
                                        shareLogsBundle(
                                            context = context,
                                            chooserTitle = chooserTitle,
                                            onExportFailed = {
                                                viewModel.emitSnackbar(exportFailedMessage)
                                            },
                                        )
                                    } finally {
                                        sharing = false
                                    }
                                }
                            }
                        },
                        enabled = !sharing,
                    ) {
                        if (sharing) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 8.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                        Text(stringResource(R.string.diagnostics_share_logs))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        item(key = "diagnostics-overlay") {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                SettingsItem(
                    modifier = Modifier.clickable {
                        viewModel.settings.diagnosticsPreferenceStore
                            .setDebugOverlayEnabled(!overlayEnabled)
                    },
                    headlineContent = {
                        Text(stringResource(R.string.diagnostics_debug_overlay))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.diagnostics_debug_overlay_desc))
                    },
                    leadingContent = {
                        Icon(Icons.Default.BugReport, contentDescription = null)
                    },
                    trailingContent = {
                        Switch(
                            checked = overlayEnabled,
                            onCheckedChange = {
                                viewModel.settings.diagnosticsPreferenceStore
                                    .setDebugOverlayEnabled(it)
                            },
                        )
                    },
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        item(key = "diagnostics-summary") {
            DiagnosticsSummaryCard(events)
            Spacer(Modifier.height(12.dp))
        }

        item(key = "diagnostics-tail-header") {
            Text(
                text = stringResource(R.string.diagnostics_live_tail),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.diagnostics_live_tail_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            DiagnosticsCategoryChips(
                selectedCategory = selectedCategory,
                onSelect = { selectedCategory = it },
            )
            Spacer(Modifier.height(8.dp))
        }

        val visibleEvents = remember(events, selectedCategory) {
            events.filter { selectedCategory == null || it.category == selectedCategory }
                .asReversed()
        }
        if (visibleEvents.isEmpty()) {
            item(key = "diagnostics-empty") {
                Text(
                    text = stringResource(R.string.diagnostics_events_empty),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            items(visibleEvents, key = { it.stableKey() }) { event ->
                DiagnosticsEventRow(
                    event = event,
                    onClick = { selectedEvent = event },
                )
            }
        }

        item(key = "diagnostics-bottom-spacer") {
            Spacer(Modifier.height(80.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiagnosticsCategoryChips(
    selectedCategory: String?,
    onSelect: (String?) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FilterChip(
            selected = selectedCategory == null,
            onClick = { onSelect(null) },
            label = { Text(stringResource(R.string.diagnostics_filter_all)) },
        )
        StructuredDiagnosticCategory.entries.forEach { category ->
            FilterChip(
                selected = selectedCategory == category.wireName,
                onClick = { onSelect(category.wireName) },
                label = { Text(stringResource(category.labelRes())) },
            )
        }
    }
}

@Composable
private fun DiagnosticsSummaryCard(events: List<StructuredDiagnosticEvent>) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.diagnostics_summary),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(8.dp))
            if (events.isEmpty()) {
                Text(
                    text = stringResource(R.string.diagnostics_events_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val byCategory = events.groupingBy { it.category }.eachCount()
                StructuredDiagnosticCategory.entries.forEach { category ->
                    val count = byCategory[category.wireName] ?: 0
                    if (count > 0) {
                        DiagnosticsSummaryRow(
                            label = stringResource(category.labelRes()),
                            value = count.toString(),
                        )
                    }
                }
                val errors = events.count {
                    it.outcome != "ok" && it.outcome != "completed_text" &&
                        it.outcome != "completed_tool_calls"
                }
                DiagnosticsSummaryRow(
                    label = stringResource(R.string.diagnostics_summary_errors),
                    value = errors.toString(),
                )
                val dropped = StructuredDiagnostics.droppedEventCount
                if (dropped > 0) {
                    DiagnosticsSummaryRow(
                        label = stringResource(R.string.diagnostics_summary_dropped),
                        value = dropped.toString(),
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsSummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun DiagnosticsEventRow(
    event: StructuredDiagnosticEvent,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        SettingsItem(
            modifier = Modifier.clickable(onClick = onClick),
            headlineContent = {
                Text(
                    text = "#${event.sequenceLabel()} ${event.name}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            },
            supportingContent = {
                Text(
                    text = "${event.categoryLabel()} · ${event.outcome} · " +
                        (event.durationMs?.let { "${it}ms · " } ?: "") +
                        event.timeLabel(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
    }
}

private fun StructuredDiagnosticCategory.labelRes(): Int = when (this) {
    StructuredDiagnosticCategory.BROWSER -> R.string.diagnostics_category_browser
    StructuredDiagnosticCategory.LLM -> R.string.diagnostics_category_llm
    StructuredDiagnosticCategory.TOOL -> R.string.diagnostics_category_tool
    StructuredDiagnosticCategory.NET -> R.string.diagnostics_category_net
    StructuredDiagnosticCategory.SYS -> R.string.diagnostics_category_sys
}

@Composable
private fun StructuredDiagnosticEvent.categoryLabel(): String =
    stringResource(
        StructuredDiagnosticCategory.fromWireName(category)?.labelRes()
            ?: R.string.diagnostics_filter_all,
    )

private fun StructuredDiagnosticEvent.sequenceLabel(): String =
    (ts % 100_000).toString()

private fun StructuredDiagnosticEvent.stableKey(): String = "$ts-$name-$outcome"

private fun StructuredDiagnosticEvent.timeLabel(): String =
    diagnosticsTimeFormat.format(Date(ts))

private val diagnosticsTimeFormat =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault())

private val diagnosticsPrettyJson = Json { prettyPrint = true }

private fun StructuredDiagnosticEvent.prettyJson(): String =
    diagnosticsPrettyJson.encodeToString(StructuredDiagnosticEvent.serializer(), this)

private suspend fun shareLogsBundle(
    context: Context,
    chooserTitle: String,
    onExportFailed: () -> Unit,
) {
    try {
        StructuredDiagnostics.flush()
        val bundle = withContext(Dispatchers.Default) {
            DiagnosticShareBundle.build(context)
        }
        val sendIntent = withContext(Dispatchers.IO) {
            val shareDirectory = File(context.cacheDir, "shared").apply { mkdirs() }
            val file = File(shareDirectory, bundle.suggestedFileName).apply {
                writeText(bundle.text, Charsets.UTF_8)
            }
            val uri = FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                file,
            )
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("AgentX diagnostics", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        withContext(Dispatchers.Main.immediate) {
            val chooser = Intent.createChooser(sendIntent, chooserTitle)
            if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        onExportFailed()
    }
}
