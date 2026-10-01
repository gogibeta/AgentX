package com.newoether.agora.tool

import com.newoether.agora.api.typesafe.JevDecisions
import com.newoether.agora.diagnostics.StructuredDiagnostics
import com.newoether.agora.diagnostics.StructuredDiagnosticCategory
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Jev auto-compact as an agent tool (`prune_context`), fast-jev-compaction /
 * jev-pruner pattern: score each blob for still-needed vs drop with one batched
 * Noul call, keep survivors VERBATIM (never rewritten — no hallucinated
 * summaries), drop the rest behind a count.
 *
 * Offered in agent `build` mode only when a TypeSafe key is configured (Jev does
 * the judging). Use it on long tool outputs, retrieved history chunks, or search
 * dumps before continuing a deep-research run.
 */
class CompactAssistToolProvider : ToolProvider {

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (ctx.agentMode != "build") return emptyList()
        if (!ctx.jevEnabled || !JevDecisions.isConfigured(ctx.typeSafeApiKey)) return emptyList()
        return listOf(
            ToolDefinition(function = ToolFunction(
                name = "prune_context",
                description = "Shrink a large blob (tool output, history chunk, search dump) by asking Jev " +
                    "whether each item is still needed for the task. Kept items come back VERBATIM; " +
                    "dropped ones are gone from the RETURNED TEXT ONLY — this does not remove " +
                    "anything from the conversation history, so the context counter will not " +
                    "change. Use before continuing when context is getting long.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "task" to ToolProperty("string", "What the remaining work needs (one line). Items are judged against this."),
                        "items" to ToolProperty(
                            "array",
                            "Objects {key, text}. Key is your label (e.g. an index); text is the chunk to judge.",
                        ),
                        "min_keep_probability" to ToolProperty(
                            "number",
                            "Keep items scoring at/above this (0-1, default 0.5). Lower keeps more.",
                        ),
                    ),
                    required = listOf("task", "items"),
                ),
            )),
        )
    }

    override fun handles(name: String): Boolean = name == "prune_context"

    override suspend fun execute(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): String = withContext(Dispatchers.IO) {
        if (name != "prune_context") return@withContext "Unknown tool: $name"
        try {
            val args = Json.parseToJsonElement(arguments.ifBlank { "{}" }).jsonObject
            val task = args["task"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@withContext errorJson("no_task")
            val items = args["items"]?.jsonArray
                ?.mapNotNull { element ->
                    val obj = element as? JsonObject ?: return@mapNotNull null
                    val key = obj["key"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    val text = obj["text"]?.jsonPrimitive?.contentOrNull ?: ""
                    key to text
                }
                ?.take(MAX_ITEMS)
                .orEmpty()
            if (items.isEmpty()) return@withContext errorJson("no_items")
            val threshold = (args["min_keep_probability"] as? JsonPrimitive)
                ?.contentOrNull?.toDoubleOrNull()?.coerceIn(0.0, 1.0)
                ?: DEFAULT_THRESHOLD

            val jevStartNanos = System.nanoTime()
            val result = JevDecisions.relevanceScoresWithError(
                apiKey = ctx.typeSafeApiKey,
                baseUrl = ctx.typeSafeBaseUrl,
                model = ctx.jevModel,
                query = "Still needed for: $task",
                documents = items.map { it.second },
            )
            // §6.1 structured `llm` event for the Jev prune decision. Only counts
            // and the outcome are recorded — never the task or item text.
            fun emitJevPrune(outcome: String, kept: Int? = null) {
                StructuredDiagnostics.emit(
                    category = StructuredDiagnosticCategory.LLM,
                    name = "jev_prune",
                    outcome = outcome,
                    durationMs = (System.nanoTime() - jevStartNanos) / 1_000_000L,
                    detail = buildMap {
                        put("model", ctx.jevModel)
                        put(
                            "key_fingerprint",
                            StructuredDiagnostics.keyFingerprint(ctx.typeSafeApiKey),
                        )
                        put("docs", items.size.toString())
                        kept?.let {
                            put("kept", it.toString())
                            put("dropped", (items.size - it).toString())
                        }
                    },
                )
            }
            val scores = result.getOrElse { e ->
                val jevError = (e as? JevDecisions.JevFailureException)?.error
                    ?: JevDecisions.classifyError(ctx.typeSafeApiKey, e as? Exception ?: Exception(e.message))
                emitJevPrune("error")
                return@withContext errorJson(
                    "jev_unavailable",
                    JevDecisions.describeError(jevError),
                )
            }

            val kept = partitionKept(items.map { it.first }, scores, threshold)
            emitJevPrune("ok", kept.size)
            buildJsonObject {
                put("type", "prune")
                put("kept_count", kept.size)
                put("dropped_count", items.size - kept.size)
                // Raw per-item keep probabilities (same order as the input
                // items) so callers can see the score distribution and pick a
                // working threshold empirically — Noul probabilities cluster,
                // so the effective operating point varies by content.
                put("scores", buildJsonObject {
                    items.forEachIndexed { index, (key, _) ->
                        put(key, scores.getOrElse(index) { -1.0 })
                    }
                })
                put("kept", buildJsonObject {
                    kept.forEach { key ->
                        put(key, items.first { it.first == key }.second)
                    }
                })
            }.toString()
        } catch (e: Exception) {
            errorJson("prune_error", e.message.orEmpty())
        }
    }

    companion object {
        internal const val MAX_ITEMS = 20
        internal const val DEFAULT_THRESHOLD = 0.5

        /** Pure keep/drop partition — unit-tested. Verbatim keep, no rewriting. */
        internal fun partitionKept(
            keys: List<String>,
            scores: List<Double>,
            threshold: Double,
        ): List<String> {
            if (scores.size != keys.size) return keys
            return keys.filterIndexed { index, _ -> scores[index] >= threshold }
        }
    }

    private fun errorJson(code: String, message: String = ""): String = buildJsonObject {
        put("type", "prune")
        put("error", code)
        if (message.isNotBlank()) put("message", message.take(300))
    }.toString()
}
