package com.newoether.agora.diagnostics

import android.content.Context
import com.newoether.agora.util.FileLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds the one-tap Share Logs bundle (§6.1): `session.log` + structured events
 * JSONL + a redacted summary (counts by category/outcome, slowest tools and LLM
 * calls). Everything in the bundle is already redacted at its write site; the
 * session.log tail gets one more credential-shape pass here as defense-in-depth.
 *
 * The bundle never contains prompts, conversation text, tool arguments/results,
 * URLs with tokens, or secrets: structured events are redacted in
 * [StructuredDiagnostics.emit], and session.log lines are re-masked below.
 */
object DiagnosticShareBundle {
    private const val SESSION_LOG_TAIL_CHARS = 256 * 1024
    private const val TOP_SLOW_COUNT = 5

    private val json = Json { ignoreUnknownKeys = true }
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }

    data class Bundle(
        val text: String,
        val suggestedFileName: String = "agentx-logs-bundle.txt",
    )

    /** Best-effort; never throws. Returns an explanatory bundle when empty. */
    fun build(context: Context): Bundle {
        val events = readStructuredEvents()
        return Bundle(
            text = buildString {
                appendSummary(events)
                appendStructuredEvents(events)
                appendSessionLogTail(context)
            },
        )
    }

    private data class ParsedEvent(
        val rawLine: String,
        val category: String,
        val name: String,
        val outcome: String,
        val durationMs: Long?,
    )

    private fun readStructuredEvents(): List<ParsedEvent> {
        val file = StructuredDiagnostics.eventsFilePath() ?: return emptyList()
        if (!file.isFile) return emptyList()
        return runCatching {
            file.readLines(Charsets.UTF_8).mapNotNull { line ->
                runCatching {
                    val obj = json.parseToJsonElement(line).jsonObject
                    ParsedEvent(
                        rawLine = line,
                        category = obj["category"]?.jsonPrimitive?.content ?: "?",
                        name = obj["name"]?.jsonPrimitive?.content ?: "?",
                        outcome = obj["outcome"]?.jsonPrimitive?.content ?: "?",
                        durationMs = obj["durationMs"]?.jsonPrimitive?.longOrNull
                            ?: obj["durationMs"]?.jsonPrimitive?.doubleOrNull?.toLong(),
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    private fun StringBuilder.appendSummary(events: List<ParsedEvent>) {
        appendLine("===== AgentX Diagnostics Bundle =====")
        appendLine("generatedAt=${iso.format(Date())}")
        appendLine("sessionId=${StructuredDiagnostics.sessionId}")
        appendLine("structuredEventCount=${events.size}")
        appendLine("droppedEventCount=${StructuredDiagnostics.droppedEventCount}")
        appendLine()
        appendLine("--- Events by category ---")
        events.groupingBy { it.category }.eachCount()
            .toList().sortedByDescending { (_, count) -> count }
            .forEach { (category, count) -> appendLine("$category=$count") }
        appendLine()
        appendLine("--- Events by outcome ---")
        events.groupingBy { it.outcome }.eachCount()
            .toList().sortedByDescending { (_, count) -> count }
            .forEach { (outcome, count) -> appendLine("$outcome=$count") }
        appendLine()
        appendLine("--- Slowest tool events ---")
        appendSlowest(events, "tool")
        appendLine("--- Slowest LLM calls ---")
        appendSlowest(events, "llm")
        appendLine()
    }

    private fun StringBuilder.appendSlowest(events: List<ParsedEvent>, category: String) {
        val slowest = events
            .filter { it.category == category && it.durationMs != null }
            .sortedByDescending { it.durationMs }
            .take(TOP_SLOW_COUNT)
        if (slowest.isEmpty()) {
            appendLine("(none)")
            return
        }
        slowest.forEach { event ->
            appendLine("${event.name} · ${event.durationMs}ms · ${event.outcome}")
        }
    }

    private fun StringBuilder.appendStructuredEvents(events: List<ParsedEvent>) {
        appendLine("===== Structured Events (JSONL) =====")
        if (events.isEmpty()) {
            appendLine("(no structured events recorded)")
        } else {
            // Already redacted at the emit write site; serialized verbatim.
            events.forEach { appendLine(it.rawLine) }
        }
        appendLine()
    }

    private fun StringBuilder.appendSessionLogTail(context: Context) {
        appendLine("===== session.log (tail, redacted) =====")
        val path = runCatching { FileLog.logFilePath(context) }.getOrNull()
        val file = path?.let(::File)?.takeIf { it.isFile }
        if (file == null) {
            appendLine("(session.log not available)")
            return
        }
        val tail = runCatching {
            val text = file.readText(Charsets.UTF_8)
            val clipped = if (text.length > SESSION_LOG_TAIL_CHARS) {
                text.takeLast(SESSION_LOG_TAIL_CHARS).substringAfter('\n', "")
            } else {
                text
            }
            clipped
        }.getOrDefault("(could not read session.log)")
        // Defense-in-depth: session.log writers already avoid secrets, but a
        // credential-shaped value that slipped through is masked here before
        // the bundle leaves the device.
        tail.lineSequence().forEach { line ->
            appendLine(DiagnosticRedactor.captureContent(line).value)
        }
    }
}
