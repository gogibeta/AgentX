package com.newoether.agora.diagnostics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StructuredDiagnosticsTest {
    companion object {
        private lateinit var tempDir: File
        private lateinit var scope: CoroutineScope
        private val json = Json { ignoreUnknownKeys = true }

        @BeforeClass
        @JvmStatic
        fun setup() {
            tempDir = Files.createTempDirectory("structured-diagnostics-test").toFile()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            StructuredDiagnostics.initialize(tempDir, scope)
        }

        @AfterClass
        @JvmStatic
        fun teardown() {
            scope.cancel()
            tempDir.deleteRecursively()
        }
    }

    @Before
    fun clearEvents() {
        StructuredDiagnostics.resetForTest()
    }

    @Test
    fun `emit records event in memory ring without throwing`() {
        StructuredDiagnostics.emit(
            category = StructuredDiagnosticCategory.TOOL,
            name = "web_search",
            outcome = "ok",
            durationMs = 123L,
            detail = mapOf("count" to "3"),
        )
        val event = StructuredDiagnostics.events.value.last()
        assertEquals("tool", event.category)
        assertEquals("web_search", event.name)
        assertEquals("ok", event.outcome)
        assertEquals(123L, event.durationMs)
        assertEquals("3", event.detail["count"])
        assertTrue(event.ts > 0)
        assertTrue(event.sessionId.isNotBlank())
    }

    @Test
    fun `write site redacts credential shaped values from detail`() {
        StructuredDiagnostics.emit(
            category = StructuredDiagnosticCategory.LLM,
            name = "provider_pass",
            detail = mapOf(
                "note" to "api_key=sk-secret-value-1234567890",
                "auth" to "Bearer secret-bearer-token",
                "count" to "3",
            ),
        )
        val event = StructuredDiagnostics.events.value.last()
        assertFalse(event.detail.getValue("note").contains("sk-secret-value-1234567890"))
        assertFalse(event.detail.getValue("auth").contains("secret-bearer-token"))
        // Ordinary metadata passes through untouched.
        assertEquals("3", event.detail.getValue("count"))
    }

    @Test
    fun `write site redacts secret urls from event name`() {
        StructuredDiagnostics.emit(
            category = StructuredDiagnosticCategory.NET,
            name = "fetch https://example.com/?token=topsecretvalue",
        )
        val event = StructuredDiagnostics.events.value.last()
        assertFalse(event.name.contains("topsecretvalue"))
    }

    @Test
    fun `key fingerprint never contains key material`() {
        val key = "sk-very-secret-key-value-1234567890"
        val first = StructuredDiagnostics.keyFingerprint(key)
        val second = StructuredDiagnostics.keyFingerprint(key)
        assertEquals(first, second)
        assertTrue(first.startsWith("…"))
        assertFalse(first.contains(key))
        assertFalse(first.contains("very-secret"))
        assertEquals("…unset", StructuredDiagnostics.keyFingerprint("short"))
        assertEquals("…unset", StructuredDiagnostics.keyFingerprint(""))
    }

    @Test
    fun `jsonl file stays bounded and newest event survives rotation`() {
        val marker = "rotation-marker-event"
        val total = StructuredDiagnostics.MAX_FILE_EVENTS + 500
        repeat(total) { index ->
            StructuredDiagnostics.emit(
                category = StructuredDiagnosticCategory.SYS,
                name = if (index == total - 1) marker else "filler-$index",
                outcome = "ok",
            )
        }
        runBlocking { StructuredDiagnostics.flush() }

        val file = File(File(tempDir, "agentx-logs"), "diagnostic-events.jsonl")
        assertTrue(file.isFile)
        val lines = file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        assertTrue(
            "expected bounded file, got ${lines.size} lines",
            lines.size <= StructuredDiagnostics.MAX_FILE_EVENTS,
        )
        // The newest event is never dropped by the bounded queue.
        assertTrue(lines.last().contains(marker))
        // Every line is a well-formed event object with the §6.1 fields.
        lines.forEach { line ->
            val obj = json.parseToJsonElement(line).jsonObject
            assertTrue(obj["ts"]?.jsonPrimitive?.content?.isNotBlank() == true)
            assertTrue(obj["category"]?.jsonPrimitive?.content?.isNotBlank() == true)
            assertTrue(obj["name"]?.jsonPrimitive?.content?.isNotBlank() == true)
            assertTrue(obj["session_id"]?.jsonPrimitive?.content?.isNotBlank() == true)
            assertTrue(obj["outcome"]?.jsonPrimitive?.content?.isNotBlank() == true)
            assertTrue(obj["detail"]?.jsonObject != null)
        }
    }

    @Test
    fun `custom session id is carried on the event`() {
        StructuredDiagnostics.emit(
            category = StructuredDiagnosticCategory.BROWSER,
            name = "navigate",
            sessionId = "run-123",
        )
        val event = StructuredDiagnostics.events.value.last()
        assertEquals("run-123", event.sessionId)
    }
}
