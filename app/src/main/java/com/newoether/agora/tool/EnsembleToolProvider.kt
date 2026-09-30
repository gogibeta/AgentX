package com.newoether.agora.tool

import com.newoether.agora.api.LlmProvider
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.api.typesafe.AnswerGuard
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Multi-model fan-out as an agent tool (`ask_models`), ZCode planner-fanout style.
 *
 * In agent `build` mode the user picks 1..5 ensemble models (`Settings -> Agent`).
 * The primary model calls `ask_models` with a question; each selected model answers
 * the same question concurrently (tools disabled, bounded wait); the tool returns
 * labeled candidates and the primary model synthesizes the best final answer in
 * its own turn. No generation-pipeline surgery: fan-out lives entirely in one tool
 * call, persisted like any other tool result.
 *
 * Failures are per-model (recorded as `error`, never failing the batch); an empty
 * candidate set returns `all_failed` so the primary model falls back to its own
 * knowledge.
 */
class EnsembleToolProvider(
    private val providerForModel: (String) -> String,
    private val getProvider: (String) -> LlmProvider?,
    private val activeKey: (String) -> String,
    private val baseUrl: (String) -> String?,
    private val apiModelName: (String) -> String,
    /**
     * Rewrites UI-concatenated triples ("Display Name:providerId:model" as once
     * stored by the Agent settings dialog) back to the stored form
     * ("providerId:model"). Normal IDs pass through untouched.
     */
    private val storedModelId: (String) -> String = { it },
    /** Failover keys per provider (active key excluded). Empty = single-key. */
    private val alternateKeys: (String) -> List<String> = { emptyList() },
) : ToolProvider {

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (ctx.agentMode != "build" || ctx.agentModels.isEmpty()) return emptyList()
        return listOf(
            ToolDefinition(function = ToolFunction(
                name = "ask_models",
                description = "Ask ${ctx.agentModels.size} selected models the same question in parallel " +
                    "and get their labeled answers back. Use for hard questions, deep research, " +
                    "or when models disagree — then synthesize the best final answer yourself. " +
                    "Each answer is capped; failures come back as per-model errors.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "question" to ToolProperty("string", "The self-contained question to ask every model (include all needed context)."),
                    ),
                    required = listOf("question"),
                ),
            )),
        )
    }

    override fun handles(name: String): Boolean = name == "ask_models"

    override suspend fun execute(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): String = withContext(Dispatchers.IO) {
        if (name != "ask_models") return@withContext "Unknown tool: $name"
        val args = runCatching {
            Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(
                arguments.ifBlank { "{}" },
            )
        }.getOrNull()
        val question = (args?.get("question") as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: return@withContext buildJsonObject {
                put("type", "ensemble")
                put("error", "no_question")
            }.toString()
        val models = ctx.agentModels.take(5)
        if (models.isEmpty()) {
            return@withContext buildJsonObject {
                put("type", "ensemble")
                put("error", "no_models")
            }.toString()
        }
        val candidates = coroutineScope {
            models.map { modelId ->
                async {
                    modelId to runCatching {
                        withTimeout(ctx.toolTimeoutMs) { askOneModel(modelId, question) }
                    }.getOrElse { error ->
                        val reason = if (error is TimeoutCancellationException) "timeout" else "error"
                        Candidate(modelId = modelId, answer = null, error = reason)
                    }
                }
            }.map { it.await().second }
        }
        val answers = buildJsonArray {
            candidates.forEach { candidate ->
                add(buildJsonObject {
                    put("model", candidate.modelId)
                    if (candidate.answer != null) put("answer", candidate.answer)
                    else put("error", candidate.error ?: "error")
                })
            }
        }
        buildJsonObject {
            put("type", "ensemble")
            put("question", question.take(500))
            put("answers", answers)
            if (candidates.none { it.answer != null }) put("error", "all_failed")
        }.toString()
    }

    private data class Candidate(
        val modelId: String,
        val answer: String?,
        val error: String? = null,
    )

    private suspend fun askOneModel(modelId: String, question: String): Candidate {
        // Normalize once: UI triples -> stored form; everything else untouched.
        val storedId = runCatching { storedModelId(modelId) }.getOrNull() ?: modelId
        val providerName = runCatching { providerForModel(storedId) }.getOrNull()
            ?: return Candidate(modelId, null, "unknown_provider")
        val provider = getProvider(providerName)
            ?: return Candidate(modelId, null, "provider_not_registered")
        val key = activeKey(providerName)
        if (key.isBlank() && providerName != "Local") {
            return Candidate(modelId, null, "no_api_key")
        }
        val config = ProviderConfig(
            apiKey = key,
            modelId = runCatching { apiModelName(storedId) }.getOrNull() ?: storedId,
            baseUrl = baseUrl(providerName),
            tools = null,
            thinkingEnabled = false,
            alternateApiKeys = runCatching { alternateKeys(providerName) }.getOrNull().orEmpty(),
        )
        val messages = listOf(ChatMessage(text = question, participant = Participant.USER))
        val text = StringBuilder()
        var failure: String? = null
        provider.generateResponse(messages, config).collect { event ->
            when (event) {
                is StreamEvent.TextChunk -> text.append(event.text)
                is StreamEvent.Error -> failure = "provider_error"
                else -> Unit
            }
            if (failure != null) return@collect
        }
        if (failure != null) return Candidate(modelId, null, failure)
        val answer = AnswerGuard.cleanForSynthesis(text.toString(), MAX_CANDIDATE_CHARS)
        if (answer.isBlank()) return Candidate(modelId, null, "empty_answer")
        return Candidate(modelId, answer)
    }

    companion object {
        internal const val MAX_CANDIDATE_CHARS = 3000
    }
}
