package com.newoether.agora.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newoether.agora.R
import com.newoether.agora.util.FileLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Live, AI-friendly diagnostics log viewer, shown in the tunnel/browser
 * settings section (user request): the log stream that used to be visible
 * only over USB logcat — including the *previous* session's rotated log —
 * readable right here, auto-refreshing, filterable, and copyable so its
 * contents can be handed to the in-app agent for diagnosis.
 *
 * One line per event (`ISO8601 LEVEL TAG | key=value … | message`), newest at
 * the bottom. The CDP/browser entries now carry the backend, the CDP method,
 * and the full error message, so the actual cause is visible instead of a
 * bare `cdp_error(-32000)`.
 */
@Composable
fun BrowserLiveLogCard() {
    var lines by remember { mutableStateOf(listOf<String>()) }
    var paused by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()

    LaunchedEffect(paused) {
        while (isActive) {
            if (!paused) {
                lines = withContext(Dispatchers.IO) { FileLog.tailLines(MAX_LIVE_LINES) }
            }
            delay(REFRESH_MS)
        }
    }

    val shown = remember(lines, filter) {
        if (filter.isBlank()) lines
        else lines.filter { it.contains(filter, ignoreCase = true) }
    }

    // Follow the tail while live.
    LaunchedEffect(shown.size, paused) {
        if (!paused && shown.isNotEmpty()) {
            runCatching { listState.scrollToItem(shown.size - 1) }
        }
    }

    SettingsIconContent(icon = Icons.Default.Description) {
        Text(
            text = stringResource(R.string.browser_live_log_title),
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
        )
        Text(
            text = stringResource(R.string.browser_live_log_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                placeholder = { Text(stringResource(R.string.browser_live_log_filter_hint)) },
                shape = RoundedCornerShape(16.dp),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = { paused = !paused }) {
                Icon(
                    if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    contentDescription = stringResource(
                        if (paused) R.string.browser_live_log_resume
                        else R.string.browser_live_log_pause,
                    ),
                )
            }
            IconButton(
                onClick = {
                    clipboard.setText(AnnotatedString(shown.joinToString("\n")))
                },
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.browser_live_log_copy),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(LOG_VIEW_HEIGHT)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(8.dp),
        ) {
            if (shown.isEmpty()) {
                Text(
                    text = stringResource(R.string.browser_live_log_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // Index keys: log lines repeat, so line content is not a safe key.
                    items(
                        count = shown.size,
                        key = { it },
                    ) { index ->
                        val line = shown[index]
                        Text(
                            text = line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            color = logLineColor(line),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun logLineColor(line: String) = when {
    "ERROR" in line -> MaterialTheme.colorScheme.error
    "WARN" in line -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private const val MAX_LIVE_LINES = 300
private const val REFRESH_MS = 2_000L
private val LOG_VIEW_HEIGHT = 240.dp
