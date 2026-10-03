package com.newoether.agora.diagnostics

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Structured diagnostic event categories (§6.1 of the v2.3.0 architecture plan).
 *
 * Every event carries `ts, category, name, session_id, duration_ms, outcome, detail`
 * where `detail` is a flat string map serialized as the `detail_json` object.
 * Never put prompts, conversation text, tool arguments/results, URLs, bodies, or
 * secrets into [StructuredDiagnostics.emit]: the write site masks credential-shaped
 * values as defense-in-depth, but the caller owns the contract.
 */
enum class StructuredDiagnosticCategory(val wireName: String) {
    BROWSER("browser"),
    LLM("llm"),
    TOOL("tool"),
    NET("net"),
    SYS("sys"),
    ;

    companion object {
        fun fromWireName(wireName: String): StructuredDiagnosticCategory? =
            entries.firstOrNull { it.wireName == wireName }
    }
}

/**
 * One structured diagnostic event. `detail` values are already redacted at the
 * write site ([StructuredDiagnostics.emit]); the map is serialized verbatim as
 * the `detail_json` object in the JSONL file.
 */
@Serializable
data class StructuredDiagnosticEvent(
    val ts: Long,
    val category: String,
    val name: String,
    // §6.1 wire contract uses snake_case; the test asserts `session_id`/`duration_ms`.
    @SerialName("session_id")
    val sessionId: String,
    @SerialName("duration_ms")
    val durationMs: Long? = null,
    val outcome: String,
    val detail: Map<String, String> = emptyMap(),
)

/**
 * Process-wide facade for the §6.1 structured event pipeline.
 *
 * This is the API other streams emit through:
 * - Stream A (browser) emits [StructuredDiagnosticCategory.BROWSER] events
 *   (navigate/click/fill/screenshot, backend mode, reconnects).
 * - Stream D (social) emits [StructuredDiagnosticCategory.TOOL] events.
 *
 * Events are appended to a bounded JSONL file
 * (`filesDir/agentx-logs/diagnostic-events.jsonl`, capped at [MAX_FILE_EVENTS]
 * with rotation) so the Live tail and Share work offline, and mirrored into an
 * in-memory ring ([events], capped at [MAX_MEMORY_EVENTS]) for the Live tail UI
 * and the floating debug overlay.
 *
 * Every method is best-effort and never throws into app code; [emit] is safe to
 * call from any thread, including hot generation paths and unit tests (before
 * [initialize] it only feeds the in-memory ring).
 */
object StructuredDiagnostics {
    /** Process-scoped session id used when a caller passes no explicit session. */
    val sessionId: String = UUID.randomUUID().toString()

    private val mutableEvents = MutableStateFlow<List<StructuredDiagnosticEvent>>(emptyList())

    /** Newest-last ring of recent events for the Live tail UI and debug overlay. */
    val events: StateFlow<List<StructuredDiagnosticEvent>> = mutableEvents.asStateFlow()

    private val droppedWrites = AtomicLong(0L)

    /** Events dropped because the bounded disk queue overflowed. */
    val droppedEventCount: Long get() = droppedWrites.get()

    private val initialized = AtomicBoolean(false)
    private val writeChannel = AtomicReference<Channel<WriteCommand>?>(null)
    private val eventsFile = AtomicReference<File?>(null)

    private val json = Json { encodeDefaults = true }

    /**
     * Starts the JSONL writer. Idempotent; safe to call from Application.onCreate.
     * Never touches `applicationContext` — only the supplied [filesDir].
     */
    fun initialize(filesDir: File, scope: CoroutineScope) {
        if (!initialized.compareAndSet(false, true)) return
        try {
            val dir = File(filesDir, LOG_DIRECTORY).apply { if (!exists()) mkdirs() }
            val file = File(dir, EVENTS_FILE_NAME)
            eventsFile.set(file)
            val channel = Channel<WriteCommand>(
                capacity = WRITE_QUEUE_CAPACITY,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
                onUndeliveredElement = { droppedWrites.incrementAndGet() },
            )
            writeChannel.set(channel)
            scope.launch(Dispatchers.IO) { consumeWrites(file, channel) }
        } catch (_: Exception) {
            writeChannel.set(null)
            eventsFile.set(null)
        }
    }

    /**
     * Records one structured event.
     *
     * Redaction happens HERE, at the write site: every field is passed through
     * [DiagnosticRedactor] so credential-shaped values, secret URL parts, and
     * private-key blocks can never reach memory or the JSONL file, even if a
     * caller accidentally passes something sensitive. Callers must still never
     * pass prompts, conversation text, tool arguments/results, or raw secrets —
     * only counts, identifiers, timings, and outcome labels belong in [detail].
     */
    fun emit(
        category: StructuredDiagnosticCategory,
        name: String,
        outcome: String = "ok",
        durationMs: Long? = null,
        sessionId: String? = null,
        detail: Map<String, String> = emptyMap(),
    ) {
        val event = try {
            StructuredDiagnosticEvent(
                ts = System.currentTimeMillis(),
                category = category.wireName,
                name = DiagnosticRedactor.safeIdentifier(name).take(MAX_NAME_CHARS),
                sessionId = DiagnosticRedactor.safeIdentifier(
                    sessionId?.ifBlank { null } ?: this.sessionId,
                ).take(MAX_SESSION_ID_CHARS),
                durationMs = durationMs?.coerceAtLeast(0L),
                outcome = DiagnosticRedactor.safeIdentifier(outcome).take(MAX_OUTCOME_CHARS),
                detail = detail.redacted(),
            )
        } catch (_: Exception) {
            return
        }
        mutableEvents.update { current -> (current + event).takeLast(MAX_MEMORY_EVENTS) }
        val line = try {
            json.encodeToString(StructuredDiagnosticEvent.serializer(), event) + "\n"
        } catch (_: Exception) {
            return
        }
        val channel = writeChannel.get()
        if (channel == null || channel.trySend(WriteCommand.Append(line)).isFailure) {
            if (channel != null) droppedWrites.incrementAndGet()
        }
    }

    /**
     * Masked API-key fingerprint for `llm` events: a SHA-256 prefix rendered like
     * `…a3f9c1`. Contains zero key material — never the key, never its prefix or
     * suffix. Keys shorter than 8 chars are reported as unset rather than hashed.
     */
    fun keyFingerprint(apiKey: String): String {
        if (apiKey.length < MIN_KEY_LENGTH_FOR_FINGERPRINT) return "…unset"
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(apiKey.toByteArray(Charsets.UTF_8))
            "…" + digest.take(FINGERPRINT_BYTES)
                .joinToString("") { "%02x".format(it) }
        }.getOrDefault("…error")
    }

    /** Best-effort drain of the pending disk queue. */
    suspend fun flush() {
        val channel = writeChannel.get() ?: return
        val done = CompletableDeferred<Unit>()
        if (channel.trySend(WriteCommand.Flush(done)).isSuccess) {
            runCatching { done.await() }
        }
    }

    /** Test-only: clears the in-memory ring. Does not touch the JSONL file. */
    internal fun resetForTest() {
        mutableEvents.value = emptyList()
    }

    /**
     * Manual reset from Settings: clear the in-memory event ring and truncate
     * the JSONL file so browser diagnostics start fresh (e.g. right after an
     * app update). The writer keeps appending new events afterwards.
     */
    fun clearAll() {
        mutableEvents.value = emptyList()
        runCatching { eventsFile.get()?.writeText("", Charsets.UTF_8) }
        emit(
            category = StructuredDiagnosticCategory.BROWSER,
            name = "diagnostics_reset",
            outcome = "ok",
        )
    }

    /** Path of the JSONL event file, or null before [initialize]. */
    internal fun eventsFilePath(): File? = eventsFile.get()

    private fun Map<String, String>.redacted(): Map<String, String> {
        if (isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>(size.coerceAtMost(MAX_DETAIL_ENTRIES))
        for ((key, value) in entries.take(MAX_DETAIL_ENTRIES)) {
            val safeKey = runCatching {
                DiagnosticRedactor.safeIdentifier(key).take(MAX_DETAIL_KEY_CHARS)
            }.getOrDefault(key.take(MAX_DETAIL_KEY_CHARS))
            val safeValue = runCatching {
                DiagnosticRedactor.safeIdentifier(value).take(MAX_DETAIL_VALUE_CHARS)
            }.getOrDefault("<redacted>")
            if (safeKey.isNotBlank()) {
                result[safeKey] = safeValue
            }
        }
        return result
    }

    private suspend fun consumeWrites(file: File, channel: Channel<WriteCommand>) {
        var retainedLines = countLines(file)
        if (retainedLines > MAX_FILE_EVENTS) {
            retainedLines = compact(file)
        }
        for (command in channel) {
            when (command) {
                is WriteCommand.Append -> {
                    val ok = appendLine(file, command.line)
                    if (ok) {
                        retainedLines++
                        if (retainedLines > MAX_FILE_EVENTS) {
                            retainedLines = compact(file)
                        }
                    }
                }
                is WriteCommand.Flush -> {
                    command.done.complete(Unit)
                }
            }
        }
    }

    private fun appendLine(file: File, line: String): Boolean = runCatching {
        file.parentFile?.mkdirs()
        file.appendText(line, Charsets.UTF_8)
        true
    }.getOrDefault(false)

    private fun countLines(file: File): Long = runCatching {
        if (!file.isFile) return 0L
        var count = 0L
        file.bufferedReader(Charsets.UTF_8).use { reader ->
            while (reader.readLine() != null) count++
        }
        count
    }.getOrDefault(0L)

    /**
     * Rotation: keep the newest [COMPACT_RETAINED_EVENTS] lines, drop the rest.
     * Returns the retained line count.
     */
    private fun compact(file: File): Long = runCatching {
        val lines = file.readLines(Charsets.UTF_8)
        val retained = lines.takeLast(COMPACT_RETAINED_EVENTS)
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(retained.joinToString(separator = "\n", postfix = "\n"), Charsets.UTF_8)
        if (!temporary.renameTo(file)) {
            temporary.delete()
            return lines.size.toLong()
        }
        retained.size.toLong()
    }.getOrDefault(0L)

    private sealed interface WriteCommand {
        data class Append(val line: String) : WriteCommand
        data class Flush(val done: CompletableDeferred<Unit>) : WriteCommand
    }

    // Log-file constants (object members; no companion allowed in an object).
    const val LOG_DIRECTORY = "agentx-logs"
    const val EVENTS_FILE_NAME = "diagnostic-events.jsonl"
    const val MAX_FILE_EVENTS = 2_000
    const val MAX_MEMORY_EVENTS = 300
    private const val COMPACT_RETAINED_EVENTS = 1_000
    private const val WRITE_QUEUE_CAPACITY = 512
    private const val MAX_NAME_CHARS = 96
    private const val MAX_OUTCOME_CHARS = 48
    private const val MAX_SESSION_ID_CHARS = 64
    private const val MAX_DETAIL_ENTRIES = 32
    private const val MAX_DETAIL_KEY_CHARS = 64
    private const val MAX_DETAIL_VALUE_CHARS = 512
    private const val MIN_KEY_LENGTH_FOR_FINGERPRINT = 8
    private const val FINGERPRINT_BYTES = 3
}
