package com.newoether.agora.viewmodel

import com.newoether.agora.api.GenerationError
import com.newoether.agora.api.util.ContextTokenEstimator
import com.newoether.agora.api.util.tokens.FixedContextComposition
import com.newoether.agora.diagnostics.StructuredDiagnosticCategory
import com.newoether.agora.diagnostics.StructuredDiagnostics
import com.newoether.agora.model.ChatMessage

/**
 * §6.1 structured `llm` event for one provider pass: model, provider, masked
 * key fingerprint, token-bucket breakdown (system / toolDefinitions / history /
 * toolResults / output), latency, retry and 429 counts, Jev outcomes (emitted
 * separately by the Jev call sites as `llm` events). Best-effort and total:
 * never throws into the generation path. Only counts and identifiers are
 * recorded — never prompts, messages, or API keys.
 */
internal fun recordLlmPassEvent(
    config: GenerationConfig,
    composition: FixedContextComposition,
    messages: List<ChatMessage>,
    outcome: ProviderPassOutcome,
    startNanos: Long,
    retryCount: Int,
    outputTokens: Int?,
    runId: String,
    pass: Int,
) {
    runCatching {
        val outcomeName: String
        val extraDetail = mutableMapOf<String, String>()
        when (outcome) {
            is ProviderPassOutcome.CompletedText -> outcomeName = "completed_text"
            is ProviderPassOutcome.CompletedToolCalls -> {
                outcomeName = "completed_tool_calls"
                extraDetail["tool_calls"] = outcome.calls.size.toString()
            }
            is ProviderPassOutcome.Truncated -> outcomeName = "truncated"
            is ProviderPassOutcome.Failed -> {
                val error = outcome.error
                val rateLimited = error is GenerationError.Network && error.statusCode == 429 ||
                    error is GenerationError.Api &&
                    error.code?.contains("rate_limit", ignoreCase = true) == true
                outcomeName = if (rateLimited) "rate_limited_429" else "failed"
                extraDetail["error_class"] = error.javaClass.simpleName
                if (rateLimited) extraDetail["rate_limited"] = "true"
            }
            is ProviderPassOutcome.Cancelled -> outcomeName = "cancelled"
        }
        val estimator = ContextTokenEstimator.forModel(config.modelId)
        var historyTokens = 0
        var toolResultTokens = 0
        messages.forEach { message ->
            val toolResult = message.toolCall?.result
            if (toolResult == null) {
                historyTokens += estimator.estimateText(message.text)
            } else {
                toolResultTokens += estimator.estimateText(message.text + toolResult)
            }
        }
        StructuredDiagnostics.emit(
            category = StructuredDiagnosticCategory.LLM,
            name = "provider_pass",
            outcome = outcomeName,
            durationMs = (System.nanoTime() - startNanos) / 1_000_000L,
            sessionId = runId,
            detail = buildMap {
                put("provider", config.providerName)
                put("model", config.modelId)
                put("key_fingerprint", StructuredDiagnostics.keyFingerprint(config.apiKey))
                put("pass", pass.toString())
                put("system_tokens", composition.systemPromptTokens.toString())
                put("tool_definition_tokens", composition.toolTokens.toString())
                put("history_tokens", historyTokens.toString())
                put("tool_result_tokens", toolResultTokens.toString())
                outputTokens?.let { put("output_tokens", it.toString()) }
                put("retries", retryCount.toString())
                putAll(extraDetail)
            },
        )
    }
}
