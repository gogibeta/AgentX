package com.newoether.agora.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Persistent, AI-friendly diagnostics log for on-device debugging.
 *
 * Goals (user request): the moment the app starts, a log file begins recording; it
 * survives closing the app or any screen; it captures the same signal a USB
 * `logcat` session would (lifecycle, requests, tool calls, errors, crashes) in a
 * structured single-line format an AI can parse without the cable.
 *
 * Design:
 * - One newline-delimited file at `filesDir/agentx-logs/session.log`, rotated to
 *   `session.1.log` when it passes [MAX_BYTES]; two files retained.
 * - Each line: `ISO8601Z LEVEL TAG | key=value ... | message`. Values are
 *   single-line (newlines escaped) so one event = one line = trivially greppable.
 * - Writes are queued and flushed on a single daemon thread; the queue is drained
 *   on every append and in a shutdown hook, so an app kill still persists the tail.
 * - Best-effort throughout: logging never throws into app code.
 */
object FileLog {
    private const val DIR = "agentx-logs"
    private const val FILE = "session.log"
    private const val ROTATED = "session.1.log"
    private const val MAX_BYTES = 4_000_000L
    private const val MAX_MESSAGE_CHARS = 8_000

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }

    private val queue = ConcurrentLinkedQueue<String>()
    private val started = AtomicBoolean(false)
    @Volatile private var logDir: File? = null
    @Volatile private var writer: Thread? = null
    private val lock = Object()

    /** Idempotent. Called from Application.onCreate before anything else logs. */
    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        try {
            val dir = File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }
            logDir = dir
            append(
                "SESSION", "start",
                mapOf(
                    "pkg" to context.packageName,
                    "sdk" to android.os.Build.VERSION.SDK_INT.toString(),
                    "device" to "${android.os.Build.MANUFACTURER}/${android.os.Build.MODEL}",
                    "abi" to (android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "?"),
                ),
                "AgentX diagnostics session started",
            )
            // Flush any buffered lines even on abrupt process death.
            Runtime.getRuntime().addShutdownHook(thread(start = false) { drain() })
            writer = thread(isDaemon = true, name = "agentx-filelog") {
                while (true) {
                    drain()
                    try {
                        Thread.sleep(500)
                    } catch (_: InterruptedException) {
                        drain(); return@thread
                    }
                }
            }.also { it.start() }
        } catch (_: Throwable) {
            // Logging must never break startup.
        }
    }

    fun logFilePath(context: Context): String =
        File(File(context.filesDir, DIR), FILE).absolutePath

    /**
     * Last [maxLines] lines of the session log, oldest-first — including the
     * rotated previous-session file when the current log alone is shorter, and
     * anything still queued but not yet flushed to disk. Bounded reads (never
     * loads multi-MB files whole) so the in-app live log viewer stays cheap.
     * The one-line-per-event format is already AI-friendly: no cable needed.
     */
    fun tailLines(maxLines: Int): List<String> {
        val lines = ArrayDeque<String>()
        try {
            val dir = logDir ?: return emptyList()
            // Oldest first: rotated (previous session), then current, then the
            // not-yet-flushed queue. Read each source fully (bounded by the
            // 256 KB window) and keep only the newest maxLines OVERALL, so a
            // long rotated log can never crowd out the current session's lines.
            for (file in listOf(File(dir, ROTATED), File(dir, FILE))) {
                if (file.exists()) readTailLines(file, Int.MAX_VALUE, lines)
            }
            queue.forEach { line -> lines.addLast(line) }
            while (lines.size > maxLines) lines.removeFirst()
        } catch (_: Throwable) {
            // ignore
        }
        return lines.toList()
    }

    /** Append up to [maxLines] trailing lines of [file] into [out], oldest-first. */
    private fun readTailLines(file: File, maxLines: Int, out: ArrayDeque<String>) {
        try {
            // Read at most a 256 KB window from the end — far more than the
            // viewer asks for — and drop the first partial line.
            val window = 256 * 1024L
            val lines: List<String> = if (file.length() <= window) {
                file.readLines()
            } else {
                java.io.RandomAccessFile(file, "r").use { raf ->
                    raf.seek(raf.length() - window)
                    val bytes = ByteArray(window.toInt())
                    raf.readFully(bytes)
                    String(bytes, Charsets.UTF_8).split("\n").drop(1)
                }
            }
            val tail = if (lines.size <= maxLines) lines else lines.subList(lines.size - maxLines, lines.size)
            for (raw in tail) {
                val line = raw.trimEnd('\r')
                if (line.isNotEmpty()) {
                    out.addLast(line)
                    while (out.size > maxLines) out.removeFirst()
                }
            }
        } catch (_: Throwable) {
            // ignore
        }
    }

    /**
     * Test-only: releases the singleton so a test can re-initialize [FileLog]
     * with its own context. Production code never calls this — the session log
     * is meant to be started once per process.
     */
    internal fun resetForTest() {
        writer?.interrupt()
        writer = null
        logDir = null
        queue.clear()
        started.set(false)
    }

    /** Structured event: level + tag + key/value fields + free message. */
    fun event(level: String, tag: String, fields: Map<String, String>, message: String) {
        if (!started.get()) return
        append(level, tag, fields, message)
    }

    private fun append(level: String, tag: String, fields: Map<String, String>, message: String) {
        try {
            val kv = if (fields.isEmpty()) "" else fields.entries.joinToString(" ") { (k, v) ->
                "$k=${sanitize(v)}"
            }
            val line = buildString {
                append(iso.format(Date()))
                append(' '); append(level.take(5).padEnd(5))
                append(' '); append(tag.take(28))
                append(" | "); append(kv)
                append(" | "); append(sanitize(message).take(MAX_MESSAGE_CHARS))
            }
            queue.add(line)
        } catch (_: Throwable) {
            // ignore
        }
    }

    private fun sanitize(value: String): String =
        value.replace('\n', '\u23CE').replace('\r', ' ')

    private fun drain() {
        val dir = logDir ?: return
        synchronized(lock) {
            try {
                val file = File(dir, FILE)
                if (file.length() > MAX_BYTES) {
                    val rotated = File(dir, ROTATED)
                    if (rotated.exists()) rotated.delete()
                    file.renameTo(rotated)
                }
                if (queue.isEmpty()) return
                // FileWriter(file, true) creates the file when absent; never open in
                // overwrite mode here — that would truncate the existing session log.
                java.io.FileWriter(file, true).use { fw ->
                    while (true) {
                        val line = queue.poll() ?: break
                        fw.append(line).append('\n')
                    }
                }
            } catch (_: Throwable) {
                // ignore
            }
        }
    }
}
