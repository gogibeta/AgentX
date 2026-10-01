package com.newoether.agora.viewmodel

import com.newoether.agora.api.typesafe.JevDecisions
import com.newoether.agora.diagnostics.StructuredDiagnosticCategory
import com.newoether.agora.diagnostics.StructuredDiagnostics
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.tool.CompactAssistToolProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v2.4 active Jev projection compaction.
 *
 * After every [GenerationContext.autoCompactIntervalTurns] tool turns, asks Jev which
 * older tool call+result pairs are still needed and drops the low-value ones from the
 * IN-MEMORY model projection only. Room history is never touched — this is not the
 * automatic Compact summarizer and it never rewrites kept content (verbatim keep).
 *
 * Safety rules:
 * - Only runs when Jev is enabled AND a TypeSafe key is configured.
 * - Never drops the latest tool round (the model is about to reason about it).
 * - Never drops non-tool messages (user/assistant text stays).
 * - Drops whole call+result units, never orphaned results.
 * - Jev failure or any exception → projection returned unchanged (fail-open).
 * - Diagnostics record counts only — never task text, tool args, or results.
 */
class AutoCompactCheckpoint {
    /** Called with the number of dropped tool outputs after each compaction. */
    var onCompacted: ((dropped: Int) -> Unit)? = null

    suspend fun maybeCompact(
        toolPath: List<ChatMessage>,
        ctx: GenerationContext,
        toolRound: Int,
    ): List<ChatMessage> = withContext(Dispatchers.IO) {
        if (!ctx.autoCompactEnabled) return@withContext toolPath
        if (!ctx.jevEnabled || !JevDecisions.isConfigured(ctx.typeSafeApiKey)) return@withContext toolPath
        val interval = ctx.autoCompactIntervalTurns.coerceIn(5, 100)
        if (toolRound <= 0 || toolRound % interval != 0) return@withContext toolPath
        return@withContext compactTail(toolPath, ctx)
    }

    private suspend fun compactTail(
        toolPath: List<ChatMessage>,
        ctx: GenerationContext,
    ): List<ChatMessage> {
        return try {
            val pairs = findToolPairs(toolPath)
            // Keep the latest round: the model needs the freshest tool outputs.
            val candidates = pairs.dropLast(1).filter { it.second.isNotEmpty() }
            if (candidates.isEmpty()) return toolPath

            val task = toolPath.firstOrNull {
                it.participant == Participant.USER && it.toolCall == null
            }?.text?.take(300)?.takeIf { it.isNotBlank() }
            val query = if (task != null) "Still needed for: $task"
            else "Still needed to continue the current task"

            val scored = candidates.take(MAX_DOCUMENTS)
            val jevStartNanos = System.nanoTime()
            val scores = JevDecisions.relevanceScoresWithError(
                apiKey = ctx.typeSafeApiKey,
                baseUrl = ctx.typeSafeBaseUrl,
                model = ctx.jevModel,
                query = query,
                documents = scored.map { describePair(it) },
            ).getOrElse { return toolPath }
            val durationMs = (System.nanoTime() - jevStartNanos) / 1_000_000L
            StructuredDiagnostics.emit(
                category = StructuredDiagnosticCategory.LLM,
                name = "auto_compact",
                outcome = "ok",
                durationMs = durationMs,
                detail = mapOf(
                    "docs" to scored.size.toString(),
                    "kept" to CompactAssistToolProvider.partitionKept(
                        scored.indices.map { it.toString() }, scores, THRESHOLD,
                    ).size.toString(),
                    "key_fingerprint" to StructuredDiagnostics.keyFingerprint(ctx.typeSafeApiKey),
                ),
            )
            val compacted = applyScores(toolPath, scored, scores)
            val dropped = toolPath.size - compacted.size
            if (dropped > 0) onCompacted?.invoke(dropped)
            return compacted
        } catch (e: Exception) {
            StructuredDiagnostics.emit(
                category = StructuredDiagnosticCategory.LLM,
                name = "auto_compact",
                outcome = "error",
                detail = mapOf("error" to (e::class.simpleName ?: "Exception")),
            )
            toolPath
        }
    }

    /** Pure score application — drops whole low-scoring call+result units. Unit-tested. */
    internal fun applyScores(
        toolPath: List<ChatMessage>,
        scored: List<Pair<ChatMessage, List<ChatMessage>>>,
        scores: List<Double>,
    ): List<ChatMessage> {
        val keys = scored.indices.map { it.toString() }
        val kept = CompactAssistToolProvider.partitionKept(keys, scores, THRESHOLD).toSet()
        val dropIds = scored.filterIndexed { index, _ -> index.toString() !in kept }
            .flatMap { (call, results) -> listOf(call.id) + results.map { it.id } }
            .toSet()
        if (dropIds.isEmpty()) return toolPath
        return toolPath.filter { it.id !in dropIds }
    }

    /** Whole tool call+result units, in path order. Results chain from the call message. */
    internal fun findToolPairs(
        toolPath: List<ChatMessage>,
    ): List<Pair<ChatMessage, List<ChatMessage>>> {
        val pairs = mutableListOf<Pair<ChatMessage, List<ChatMessage>>>()
        var i = 0
        while (i < toolPath.size) {
            val msg = toolPath[i]
            if (msg.toolCall == null) {
                i++
                continue
            }
            val results = mutableListOf<ChatMessage>()
            var j = i + 1
            while (j < toolPath.size) {
                val next = toolPath[j]
                if (next.toolCall != null || next.participant != Participant.USER) break
                results.add(next)
                j++
            }
            pairs.add(msg to results)
            i = j
        }
        return pairs
    }

    /** Scoring text for one pair — counts and structure only reach diagnostics. */
    private fun describePair(pair: Pair<ChatMessage, List<ChatMessage>>): String {
        val (call, results) = pair
        val toolCall = call.toolCall
        val resultText = results.joinToString("\n") {
            it.toolCall?.resultText ?: it.toolCall?.result ?: it.text
        }
        return buildString {
            append("tool: ")
            append(toolCall?.toolName ?: call.text.take(120))
            append("\nresult: ")
            append(resultText.take(MAX_RESULT_CHARS))
        }.take(MAX_DOCUMENT_CHARS)
    }

    companion object {
        internal const val MAX_DOCUMENTS = 20
        internal const val THRESHOLD = 0.5
        private const val MAX_RESULT_CHARS = 1500
        private const val MAX_DOCUMENT_CHARS = 2000
    }
}
