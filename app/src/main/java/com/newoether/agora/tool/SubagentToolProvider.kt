package com.newoether.agora.tool

import com.newoether.agora.automation.ChildGenerationRunner
import com.newoether.agora.automation.ChildRequest
import com.newoether.agora.automation.ChildResult
import com.newoether.agora.diagnostics.StructuredDiagnosticCategory
import com.newoether.agora.diagnostics.StructuredDiagnostics
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * True tool-using subagents (`delegate_task`), v2.4.
 *
 * The parent agent fans work out to 1..5 bounded child generations that run
 * concurrently, each with its own model, a restricted tool allow-list, and a
 * read-only memory snapshot. Children return structured reports
 * (findings + confidence + memory proposals); the PARENT validates every
 * finding and applies the fixes itself, and commits memory proposals through
 * the ordinary memory tools. Single writer, no drift, no recursion.
 *
 * Typical uses: "use these two models only to verify this PDF and report every
 * factual or layout error", or parallel research/file inspection for speed.
 * This is real work (tools), unlike `ask_models` which is Q&A only.
 *
 * No generation-pipeline surgery: orchestration lives in this one tool call,
 * persisted as an ordinary tool result. One child's failure never fails the batch.
 */
class SubagentToolProvider(
    private val runChild: suspend (ChildRequest) -> ChildResult,
    private val memorySnapshot: () -> String = { "" },
    /** Normalizes UI model IDs to the stored "provider:model" form (same as ask_models). */
    private val normalizeModelId: (String) -> String = { it },
) : ToolProvider {

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        // Build-mode only. Unlike ask_models, this does NOT require the agentModels
        // preset: per-call `models` are first-class, so gating on the preset would
        // hide the tool exactly when per-call selection is wanted.
        if (ctx.agentMode != "build") return emptyList()
        return listOf(
            ToolDefinition(function = ToolFunction(
                name = "delegate_task",
                description = "Delegate a self-contained task to 1-5 subagents that run " +
                    "concurrently, each with real tools. Use to verify generated work " +
                    "(e.g. have two models independently inspect a PDF/report and list every " +
                    "error they find), or to parallelize research and file inspection for speed. " +
                    "Each child returns a structured report with findings, confidence scores, " +
                    "and memory proposals. YOU must validate every finding against the actual " +
                    "artifact before acting on it, and apply the fixes yourself — never trust " +
                    "a child's claim blindly. Commit worthy memory proposals with the memory " +
                    "tools. Children cannot delegate further and cannot ask the user anything.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "task" to ToolProperty(
                            "string",
                            "Self-contained task brief for the child (include all needed context: " +
                                "file paths, what to check, what a good report looks like).",
                        ),
                        "models" to ToolProperty(
                            "array",
                            "Exact model IDs to run as subagents for THIS call " +
                                "(e.g. [\"OpenAI:gpt-4o\", \"Anthropic:claude-opus-4-6\"]). " +
                                "Falls back to the Settings ensemble preset when omitted. Max 5.",
                        ),
                        "tools" to ToolProperty(
                            "array",
                            "Tool allow-list for the children. Defaults to read-only " +
                                "verification tools. Common names: file_read, file_glob, file_grep, " +
                                "view_image, web_search, execute_shell_command, list_shells, " +
                                "save_artifact, ask_models, prune_context. " +
                                "delegate_task and ask_user are always stripped (no recursion).",
                        ),
                        "max_turns" to ToolProperty(
                            "integer",
                            "Advisory cap on child tool rounds (default 8). " +
                                "timeout_ms is the hard bound.",
                        ),
                        "timeout_ms" to ToolProperty(
                            "integer",
                            "Wall-clock budget per child in milliseconds " +
                                "(default: the agent tool timeout).",
                        ),
                    ),
                    required = listOf("task"),
                ),
            )),
        )
    }

    override fun handles(name: String): Boolean = name == "delegate_task"

    override suspend fun execute(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): String = withContext(Dispatchers.IO) {
        if (name != "delegate_task") return@withContext "Unknown tool: $name"
        val args = runCatching {
            Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(
                arguments.ifBlank { "{}" },
            )
        }.getOrNull()
        val task = (args?.get("task") as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: return@withContext errorResult("no_task")
        val requestedModels = (args?.get("models") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { s -> s.isNotBlank() } }
            ?.distinct()
            .orEmpty()
        val models = (if (requestedModels.isNotEmpty()) requestedModels else ctx.agentModels)
            .map { runCatching { normalizeModelId(it) }.getOrNull() ?: it }
            .distinct()
            .take(MAX_CHILDREN)
        if (models.isEmpty()) return@withContext errorResult("no_models")
        val requestedTools = (args?.get("tools") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { s -> s.isNotBlank() } }
            ?.distinct()
            .orEmpty()
        // No recursion, no user prompts — enforced here AND structurally (the child
        // engine never wires delegate_task, and the child context disables ask_user).
        val allowList = (if (requestedTools.isNotEmpty()) requestedTools else DEFAULT_CHILD_TOOLS)
            .filter { it != "delegate_task" && it != "ask_user" }
            .toSet()
        val maxTurns = (args?.get("max_turns") as? JsonPrimitive)?.content?.toIntOrNull()
            ?.coerceIn(1, 50) ?: DEFAULT_MAX_TURNS
        val timeoutMs = (args?.get("timeout_ms") as? JsonPrimitive)?.content?.toLongOrNull()
            ?.coerceIn(10_000L, 600_000L) ?: ctx.toolTimeoutMs
        val snapshot = runCatching { memorySnapshot() }.getOrNull().orEmpty()

        val results = coroutineScope {
            models.map { modelId ->
                async {
                    modelId to runCatching {
                        runChild(
                            ChildRequest(
                                task = task,
                                modelId = modelId,
                                toolAllowList = allowList,
                                maxTurns = maxTurns,
                                timeoutMs = timeoutMs,
                                memorySnapshot = snapshot,
                            ),
                        )
                    }.getOrElse { error ->
                        ChildResult(modelId = modelId, error = "error: ${error.message}")
                    }
                }
            }.map { it.await().second }
        }
        // Measurable self-improvement: per-batch outcome counts (children, per-model
        // success/failure, proposals surfaced) so future delegation can learn which
        // models and task shapes pay off. No task text or report content is recorded.
        val succeeded = results.count { it.error == null }
        val proposals = results.sumOf { result ->
            (result.report?.get("memory_proposals") as? JsonArray)?.size ?: 0
        }
        StructuredDiagnostics.emit(
            category = StructuredDiagnosticCategory.TOOL,
            name = "delegate_task",
            outcome = when {
                succeeded == results.size -> "ok"
                succeeded == 0 -> "error"
                else -> "partial"
            },
            detail = mapOf(
                "children" to results.size.toString(),
                "succeeded" to succeeded.toString(),
                "failed" to (results.size - succeeded).toString(),
                "proposals" to proposals.toString(),
            ),
        )
        buildJsonObject {
            put("type", "delegate_task")
            put("task", task.take(500))
            put("reports", buildJsonArray {
                results.forEach { result -> add(reportJson(result)) }
            })
            if (results.none { it.error == null }) put("error", "all_failed")
            // Reflection nudge: the parent decides what becomes a durable lesson.
            // memory_proposals are suggestions only — commit the reusable ones
            // with the memory tools, discard the rest.
            put(
                "reflection",
                "Review the reports: promote genuinely reusable findings to memory " +
                    "with the memory tools; ignore one-off observations.",
            )
        }.toString()
    }

    private fun errorResult(code: String): String = buildJsonObject {
        put("type", "delegate_task")
        put("error", code)
    }.toString()

    private fun reportJson(result: ChildResult): JsonObject = buildJsonObject {
        put("model", result.modelId)
        val report = result.report
        if (report != null) {
            (report["findings"] as? JsonArray)?.let { put("findings", it) }
            (report["memory_proposals"] as? JsonArray)?.let { put("memory_proposals", it) }
            (report["summary"] as? JsonPrimitive)?.let { put("summary", it.content.take(2000)) }
            // Pass through anything else the child reported without bloating the parent.
            report.filterKeys { it != "findings" && it != "memory_proposals" && it != "summary" }
                .forEach { (key, value) -> put(key.take(64), value.toString().take(500)) }
        } else if (result.rawText.isNotBlank()) {
            put("raw_text", result.rawText.take(ChildGenerationRunner.MAX_RAW_FALLBACK_CHARS))
        }
        result.error?.let { put("error", it.take(300)) }
    }

    companion object {
        internal const val MAX_CHILDREN = 5
        internal const val DEFAULT_MAX_TURNS = 8

        /** Read-only default: safe for verification passes without shell mutations. */
        internal val DEFAULT_CHILD_TOOLS: List<String> = listOf(
            "file_read",
            "file_glob",
            "file_grep",
            "view_image",
            "web_search",
        )
    }
}
