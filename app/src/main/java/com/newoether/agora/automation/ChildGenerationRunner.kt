package com.newoether.agora.automation

import com.newoether.agora.data.MemoryManager
import com.newoether.agora.data.repository.ConversationRepository
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Runs one bounded child generation for `delegate_task` (v2.4 subagents).
 *
 * Each child gets a fresh temporary conversation, a task-specific system prompt
 * (task brief + read-only memory snapshot + mandated JSON report format), and a
 * restricted tool allow-list enforced centrally by [GenerationToolExecutor].
 * The temp conversation is deleted afterwards so child runs never pollute the
 * user's history or the RAG/embedding index (deletion also drops embeddings).
 *
 * Children never see `delegate_task` itself (it is a ChatRuntime-only provider,
 * while the engine's child GenerationManager only wires mcp + ask_user) and run
 * with `askUserEnabled=false` plus no memory-tool access: they may *propose*
 * memory changes in their report, but only the parent commits them via the
 * ordinary memory tools. Single writer, no drift.
 */
class ChildGenerationRunner(
    private val engine: TaskExecutionEngine,
    private val conversations: ConversationRepository,
) {
    suspend fun runChild(request: ChildRequest): ChildResult {
        val conversationId = runCatching {
            conversations.createConversation(
                title = "Subagent task",
                modelId = request.modelId,
            )
        }.getOrElse {
            return ChildResult(request.modelId, rawText = "", error = "no_conversation")
        }
        return try {
            val outcome = try {
                withTimeout(request.timeoutMs) {
                    engine.runOnce(
                        conversationId = conversationId,
                        userText = request.task,
                        modelId = request.modelId,
                        systemPromptOverride = buildChildSystemPrompt(request),
                        toolAllowList = request.toolAllowList,
                        childProjectFolder = request.projectFolder,
                        requestKind = "subagent",
                    )
                }
            } catch (timeout: TimeoutCancellationException) {
                return ChildResult(request.modelId, rawText = "", error = "timeout")
            }
            when (outcome) {
                is TaskExecutionEngine.Result.Success ->
                    parseReport(request.modelId, outcome.text)
                is TaskExecutionEngine.Result.Busy ->
                    ChildResult(request.modelId, rawText = "", error = "busy: ${outcome.reason}")
                is TaskExecutionEngine.Result.Failure ->
                    ChildResult(request.modelId, rawText = "", error = "failure: ${outcome.reason}")
            }
        } finally {
            runCatching { conversations.deleteConversation(conversationId) }
        }
    }

    private fun buildChildSystemPrompt(request: ChildRequest): String = buildString {
        appendLine("You are a focused subagent. Do exactly one task, then stop.")
        appendLine("Work autonomously: finish within about ${request.maxTurns} tool rounds.")
        appendLine("You have a limited tool set; use only what you were given.")
        appendLine("Never ask the user anything — there is no user to ask.")
        if (request.projectFolder.isNotBlank()) {
            appendLine("PROJECT FOLDER: stay inside ${request.projectFolder}; file tools outside it are rejected.")
        }
        if (request.memorySnapshot.isNotBlank()) {
            appendLine()
            appendLine("SHARED MEMORY (read-only — you cannot write memory).")
            appendLine("If you learn a durable fact, propose it in memory_proposals below;")
            appendLine("the parent agent decides what to commit.")
            appendLine(request.memorySnapshot)
        }
        appendLine()
        appendLine("YOUR FINAL MESSAGE must be exactly one JSON object, nothing else:")
        appendLine(
            """{"findings":[{"claim":"...","evidence":"...","confidence":0.0-1.0}],""" +
                """"memory_proposals":[{"fact":"...","why":"..."}],""" +
                """"summary":"..."}""",
        )
        appendLine("findings: concrete issues or results you verified, with evidence.")
        appendLine("confidence: 0.0 (guess) to 1.0 (verified). Omit findings you could not verify.")
        appendLine("If this is not a verification task, leave findings empty and put your work in summary.")
    }

    private fun parseReport(modelId: String, text: String): ChildResult {
        val json = extractJsonObject(text) ?: return ChildResult(
            modelId = modelId,
            rawText = text.take(MAX_RAW_FALLBACK_CHARS),
            error = "unparseable_report",
        )
        return ChildResult(modelId = modelId, report = json, rawText = text.take(MAX_RAW_FALLBACK_CHARS))
    }

    /** Extracts the first {...} span so a child that adds prose around the JSON still parses. */
    private fun extractJsonObject(text: String): JsonObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            Json.parseToJsonElement(text.substring(start, end + 1)).jsonObject
        }.getOrNull()
    }

    companion object {
        internal const val MAX_RAW_FALLBACK_CHARS = 2000
    }
}

/** One bounded child generation request. */
data class ChildRequest(
    val task: String,
    val modelId: String,
    val toolAllowList: Set<String>,
    val maxTurns: Int,
    val timeoutMs: Long,
    val memorySnapshot: String = "",
    /** Project-folder scope inherited from the parent ("" = none). */
    val projectFolder: String = "",
)

/** Structured outcome of one child generation. */
data class ChildResult(
    val modelId: String,
    /** Parsed JSON report when the child's final message was well-formed. */
    val report: JsonObject? = null,
    /** Final message text (truncated); fallback when the report did not parse. */
    val rawText: String = "",
    /** Set when the child failed before producing a report. */
    val error: String? = null,
)

/**
 * Builds a read-only memory snapshot for a child brief: active memory plus a
 * bounded slice of memory files. Never throws; returns "" when memory is empty.
 */
fun buildMemorySnapshot(memoryManager: MemoryManager, maxChars: Int = 4000): String {
    val snapshot = buildString {
        val active = runCatching { memoryManager.getActiveMemory() }.getOrNull().orEmpty()
        if (active.isNotBlank()) {
            appendLine("ACTIVE MEMORY:")
            appendLine(active.take(2000))
            appendLine()
        }
        val files = runCatching { memoryManager.listFiles() }.getOrNull().orEmpty()
        for (file in files.take(10)) {
            val content = runCatching { memoryManager.readFile(file.name) }.getOrNull().orEmpty()
            if (content.isBlank()) continue
            appendLine("MEMORY FILE ${file.name}:")
            appendLine(content.take(1000))
            appendLine()
            if (length >= maxChars) break
        }
    }
    return snapshot.take(maxChars)
}
