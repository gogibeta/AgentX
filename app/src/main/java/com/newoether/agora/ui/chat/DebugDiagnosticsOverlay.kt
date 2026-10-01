package com.newoether.agora.ui.chat

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.newoether.agora.diagnostics.StructuredDiagnosticCategory
import com.newoether.agora.diagnostics.StructuredDiagnostics

/**
 * Floating debug overlay for agent runs: live tokens/s from the latest `llm`
 * structured event plus a last-action chip from the latest event of any
 * category. Fed by the §6.1 structured event pipeline, so it adds zero chat
 * pollution — the user can see "is Jev working / what is it doing" at a glance.
 *
 * WIRING (one call site, ~4 lines, in [ChatApp]'s BoxScope right after the
 * `ChatSwitchingOverlay(...)` invocation):
 *
 * ```
 * val debugOverlayEnabled by viewModel.settings.diagnosticsPreferenceStore
 *     .debugOverlayEnabled.collectAsState()
 * DebugDiagnosticsOverlay(enabled = debugOverlayEnabled)
 * ```
 *
 * The toggle lives in Settings → Diagnostics ("Debug Overlay").
 */
@Composable
fun BoxScope.DebugDiagnosticsOverlay(enabled: Boolean) {
    if (!enabled) return
    val events by StructuredDiagnostics.events.collectAsState()
    val lastEvent = remember(events) { events.lastOrNull() } ?: return
    val lastLlm = remember(events) {
        events.lastOrNull { it.category == StructuredDiagnosticCategory.LLM.wireName }
    }
    val tokensPerSecond = remember(lastLlm) {
        val outputTokens = lastLlm?.detail?.get("output_tokens")?.toIntOrNull()
        val durationMs = lastLlm?.durationMs
        if (outputTokens != null && durationMs != null && durationMs > 0) {
            outputTokens * 1000.0 / durationMs
        } else {
            null
        }
    }
    Surface(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(top = 12.dp, end = 12.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = tokensPerSecond?.let { "%.1f tok/s".format(it) } ?: "— tok/s",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            lastLlm?.detail?.get("model")?.let { model ->
                Text(
                    text = model.take(28),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                )
            }
            Text(
                text = "▸ ${lastEvent.category} ${lastEvent.name.take(24)} · ${lastEvent.outcome}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
