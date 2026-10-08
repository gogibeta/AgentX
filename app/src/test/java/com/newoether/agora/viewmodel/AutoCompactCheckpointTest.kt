package com.newoether.agora.viewmodel

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ToolCallData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCompactCheckpointTest {

    private val checkpoint = AutoCompactCheckpoint()

    private fun userMsg(id: String, text: String) = ChatMessage(
        id = id,
        text = text,
        participant = Participant.USER,
        timestamp = 0L,
    )

    private fun toolCallMsg(id: String, toolName: String, parentId: String? = null) = ChatMessage(
        id = id,
        parentId = parentId,
        text = "",
        participant = Participant.MODEL,
        timestamp = 0L,
        toolCall = ToolCallData(toolName = toolName, arguments = "{}", result = "ok"),
    )

    private fun resultMsg(id: String, parentId: String, text: String) = ChatMessage(
        id = id,
        parentId = parentId,
        text = text,
        participant = Participant.USER,
        timestamp = 0L,
    )

    private fun buildCtx(
        enabled: Boolean = true,
        jevEnabled: Boolean = true,
        apiKey: String = "key",
        interval: Int = 25,
    ) = GenerationContext(
        autoCompactEnabled = enabled,
        autoCompactIntervalTurns = interval,
        jevEnabled = jevEnabled,
        typeSafeApiKey = apiKey,
    )

    private fun samplePath(): List<ChatMessage> = listOf(
        userMsg("u0", "research task"),
        toolCallMsg("c1", "web_search"),
        resultMsg("r1", "c1", "result one"),
        toolCallMsg("c2", "file_read"),
        resultMsg("r2", "c2", "result two"),
        toolCallMsg("c3", "web_fetch"),
        resultMsg("r3", "c3", "fresh result"),
    )

    @Test
    fun findToolPairs_groupsWholeCallResultUnits() {
        val pairs = checkpoint.findToolPairs(samplePath())
        assertEquals(3, pairs.size)
        assertEquals("c1", pairs[0].first.id)
        assertEquals(listOf("r1"), pairs[0].second.map { it.id })
        assertEquals("c2", pairs[1].first.id)
        assertEquals(listOf("r2"), pairs[1].second.map { it.id })
    }

    @Test
    fun findToolPairs_stopsAtAssistantText() {
        val path = listOf(
            userMsg("u0", "task"),
            toolCallMsg("c1", "web_search"),
            resultMsg("r1", "c1", "result"),
            ChatMessage(
                id = "a1", text = "thinking", participant = Participant.MODEL, timestamp = 0L,
            ),
            toolCallMsg("c2", "file_read"),
        )
        val pairs = checkpoint.findToolPairs(path)
        assertEquals(2, pairs.size)
        assertEquals(listOf("r1"), pairs[0].second.map { it.id })
        assertTrue(pairs[1].second.isEmpty())
    }

    @Test
    fun applyScores_dropsWholeLowValueUnits_verbatimSurvivors() {
        val path = samplePath()
        val pairs = checkpoint.findToolPairs(path)
        // Score only the first two pairs (latest round c3 is never a candidate).
        val scored = pairs.dropLast(1)
        val compacted = checkpoint.applyScores(path, scored, listOf(0.1, 0.9))
        // c1+r1 dropped as one unit; c2+r2 kept verbatim; latest round untouched.
        assertEquals(listOf("u0", "c2", "r2", "c3", "r3"), compacted.map { it.id })
        assertEquals("result two", compacted.first { it.id == "r2" }.text)
    }

    @Test
    fun applyScores_allKept_returnsSameInstance() {
        val path = samplePath()
        val pairs = checkpoint.findToolPairs(path).dropLast(1)
        val compacted = checkpoint.applyScores(path, pairs, listOf(0.9, 0.8))
        assertSame(path, compacted)
    }

    @Test
    fun maybeCompact_gating_returnsPathUnchanged() = runBlocking {
        val path = samplePath()
        // Disabled.
        assertSame(path, checkpoint.maybeCompact(path, buildCtx(enabled = false), 25))
        // Jev not enabled.
        assertSame(path, checkpoint.maybeCompact(path, buildCtx(jevEnabled = false), 25))
        // No TypeSafe key configured.
        assertSame(path, checkpoint.maybeCompact(path, buildCtx(apiKey = ""), 25))
        // Not at an interval boundary.
        assertSame(path, checkpoint.maybeCompact(path, buildCtx(), 24))
        assertSame(path, checkpoint.maybeCompact(path, buildCtx(), 0))
    }

    @Test
    fun maybeCompact_atBoundary_withoutPairs_returnsUnchanged() = runBlocking {
        val path = listOf(userMsg("u0", "plain chat, no tools"))
        assertSame(path, checkpoint.maybeCompact(path, buildCtx(), 25))
    }
}
