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
                file.bufferedWriter(Charsets.UTF_8).let { /* create if absent */ it.close() }
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
