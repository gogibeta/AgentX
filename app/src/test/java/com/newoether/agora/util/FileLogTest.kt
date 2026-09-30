package com.newoether.agora.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the always-on diagnostics log contract:
 * - lines are appended (never truncated) across drain cycles,
 * - structured one-line format: ISO8601 LEVEL TAG | k=v ... | message,
 * - free text stays on one line (no raw newlines).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileLogTest {

    @Before
    fun resetFileLogSingleton() {
        // Other test classes start the FileLog singleton with mock contexts.
        // Reset it so this test owns the session log directory.
        FileLog.resetForTest()
    }

    private fun logFile(): File {
        val context = ApplicationProvider.getApplicationContext<Context>()
        FileLog.start(context)
        return File(FileLog.logFilePath(context))
    }

    private fun waitForFlush() {
        // Writer thread drains every 500 ms.
        Thread.sleep(1_300)
    }

    @Test
    fun appendsAcrossDrainCycles_withoutTruncation() {
        val file = logFile()
        FileLog.event("INFO", "DiagTest", mapOf("cycle" to "one"), "first line")
        waitForFlush()
        FileLog.event("INFO", "DiagTest", mapOf("cycle" to "two"), "second line")
        waitForFlush()

        val text = file.readText()
        assertTrue("first line must survive a second drain", text.contains("first line"))
        assertTrue("second line must be appended", text.contains("second line"))
        assertTrue(
            "structured format",
            text.lines().any {
                it.contains("INFO") && it.contains("DiagTest") && it.contains("cycle=two")
            },
        )
    }

    @Test
    fun freeTextStaysOnOneLine() {
        val file = logFile()
        FileLog.event("INFO", "DiagTest", emptyMap(), "line one\nline two\rcarriage")
        waitForFlush()

        val matching = file.readLines().filter { it.contains("line one") }
        assertTrue("exactly one log line for the event", matching.size == 1)
        assertTrue("no raw newline inside the line", !matching.first().contains('\n'))
    }
}
