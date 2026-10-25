package com.newoether.agora.browser

import com.newoether.agora.diagnostics.StructuredDiagnosticCategory
import com.newoether.agora.diagnostics.StructuredDiagnostics
import com.newoether.agora.viewmodel.GenerationContext

/**
 * Sink for browser audit-trail events (session-level: reconnects, keepalive
 * failures, backend switches). Tool-level actions report through
 * [BrowserDiagnostics.record] directly.
 */
fun interface BrowserEventReporter {
    fun report(
        action: String,
        elapsedMs: Long,
        outcome: String,
        extra: Map<String, String>,
    )
}

/**
 * Emits structured `browser` diagnostic events through the app's §6.1
 * diagnostics pipeline ([StructuredDiagnostics.emit] with the BROWSER
 * category — the same event store the Live tail, Share bundle, and floating
 * debug overlay read). This is the browser audit trail (§1.3.6 / Part 6):
 * every CDP action carries name + duration + outcome + sanitized detail.
 *
 * Privacy contract (footer law): never record full URLs (they may carry tokens
 * or session ids — host only), credentials, page text, or tool arguments. The
 * detail map only carries "domain" (host only), "ref" (e.g. "@e3"), "backend"
 * ("webview"/"tunnel"), and sizes ("bytes"/"chars"); the write site redacts
 * credential-shaped values as defense-in-depth.
 */
internal object BrowserDiagnostics {
    /**
     * @param ctx the current generation context, or null for session-level events
     *   (reconnects) where no generation is in flight.
     * @param action e.g. "navigate", "click", "reconnect".
     * @param outcome "ok" or "error:<ErrorClass>".
     * @param extra small sanitized attributes: "domain" (host only), "ref"
     *   (e.g. "@e3"), "backend" ("webview"/"tunnel"), "bytes", "chars".
     */
    fun record(
        ctx: GenerationContext?,
        action: String,
        elapsedMs: Long,
        outcome: String,
        extra: Map<String, String> = emptyMap(),
    ) {
        try {
            StructuredDiagnostics.emit(
                category = StructuredDiagnosticCategory.BROWSER,
                name = action,
                outcome = outcome,
                durationMs = elapsedMs.coerceAtLeast(0L),
                sessionId = ctx?.conversationId,
                detail = extra,
            )
        } catch (_: Exception) {
            // Diagnostics must never break the browser path.
        }
    }
}
