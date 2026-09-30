package com.newoether.agora.util

import android.content.Context

object DebugLog {
    @Volatile
    var forceEnabled = false
    @Volatile
    private var enabled = true

    fun init(context: Context) {
        enabled = forceEnabled || (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        // Persistent on-device diagnostics so USB-less debugging has the same signal.
        // Pass the context through untouched: strict test mocks stub
        // applicationInfo only, and FileLog is best-effort anyway.
        FileLog.start(context)
    }

    private val active: Boolean get() = forceEnabled || enabled

    private fun file(level: String, tag: String, msg: String) {
        FileLog.event(level, tag, emptyMap(), msg)
    }

    // Release diagnostics accept only generated run IDs, fixed stage labels and timings.
    internal fun sendStage(
        runId: String,
        component: String,
        stage: String,
        elapsedMs: Long,
        previous: String? = null,
        previousMs: Long? = null,
    ) {
        runCatching {
            val timing = if (previous != null && previousMs != null) {
                "previous=$previous previousMs=$previousMs "
            } else {
                ""
            }
            android.util.Log.i(
                "SendDiagnostics",
                "run=$runId component=$component stage=$stage ${timing}elapsedMs=$elapsedMs",
            )
            FileLog.event(
                "INFO", "SendDiagnostics",
                buildMap {
                    put("run", runId); put("component", component)
                    put("stage", stage); put("elapsedMs", elapsedMs.toString())
                    if (previous != null) put("previous", previous)
                    if (previousMs != null) put("previousMs", previousMs.toString())
                },
                "send stage",
            )
        }
    }

    // Recovery diagnostics accept only the generated conversation ID, fixed stage labels and timings.
    internal fun recoveryStage(
        conversationId: String,
        stage: String,
        elapsedMs: Long,
        rows: Int? = null,
    ) {
        runCatching {
            val count = if (rows != null) "rows=$rows " else ""
            android.util.Log.i(
                "RecoveryDiagnostics",
                "conversation=$conversationId stage=$stage ${count}elapsedMs=$elapsedMs",
            )
        }
    }

    fun d(tag: String, msg: String) { if (active) android.util.Log.d(tag, msg); file("DEBUG", tag, msg) }
    fun d(tag: String, msg: String, tr: Throwable) {
        val full = "$msg ${safeThrowableSummary(tr)}"
        if (active) android.util.Log.d(tag, full); file("DEBUG", tag, full)
    }
    fun e(tag: String, msg: String) { if (active) android.util.Log.e(tag, msg); file("ERROR", tag, msg) }
    fun e(tag: String, msg: String, tr: Throwable) {
        val full = "$msg ${safeThrowableSummary(tr)}"
        if (active) android.util.Log.e(tag, full); file("ERROR", tag, full)
    }
    fun w(tag: String, msg: String) { if (active) android.util.Log.w(tag, msg); file("WARN", tag, msg) }
    fun w(tag: String, msg: String, tr: Throwable) {
        val full = "$msg ${safeThrowableSummary(tr)}"
        if (active) android.util.Log.w(tag, full); file("WARN", tag, full)
    }

    /** Explicit info event with structured fields (requests, tool calls, lifecycle). */
    fun event(tag: String, fields: Map<String, String>, msg: String) {
        FileLog.event("INFO", tag, fields, msg)
    }

    /**
     * Throwable messages and cause chains frequently contain request URLs, response excerpts,
     * local paths, or user content. Keep the exception type and application stack locations for
     * diagnostics without forwarding those uncontrolled strings to Logcat.
     */
    internal fun safeThrowableSummary(tr: Throwable): String = buildString {
        append("exception=")
        append(tr.javaClass.name)
        tr.stackTrace
            .asSequence()
            .take(MAX_SAFE_STACK_FRAMES)
            .forEach { frame ->
                append("\n\tat ")
                append(frame)
            }
    }

    private const val MAX_SAFE_STACK_FRAMES = 24
}
