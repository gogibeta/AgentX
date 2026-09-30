package com.newoether.agora.viewmodel

import com.newoether.agora.util.DebugLog

/**
 * Always-on diagnostics for the generation lifecycle. Emits one `generation start`
 * and one `generation end` event per [GenerationManager.generate] call into the
 * persistent session log. Only run-scoped metadata is recorded — never prompts,
 * messages, or API keys.
 */
internal fun generationStartEvent(
    runId: String,
    conversationId: String,
    providerName: String,
    modelName: String,
    pass: Int,
): Long {
    DebugLog.event(
        "Generation",
        mapOf(
            "run" to runId,
            "conversation" to conversationId,
            "provider" to providerName,
            "model" to modelName,
            "pass" to pass.toString(),
        ),
        "generation start",
    )
    return System.nanoTime()
}

internal fun generationEndEvent(runId: String, startNanos: Long, outcome: String) {
    DebugLog.event(
        "Generation",
        mapOf(
            "run" to runId,
            "outcome" to outcome,
            "elapsedMs" to ((System.nanoTime() - startNanos) / 1_000_000L).toString(),
        ),
        "generation end",
    )
}
