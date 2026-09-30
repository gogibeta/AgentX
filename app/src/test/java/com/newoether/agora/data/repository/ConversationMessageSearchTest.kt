package com.newoether.agora.data.repository

import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.Participant
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Multi-word scored search for `search_conversations` (Part 0):
 * each word is searched via the DAO, messages must contain ALL words,
 * results are scored by word coverage (0..1) and ordered by score then recency.
 * Pure JVM — the DAO is faked in memory.
 */
class ConversationMessageSearchTest {

    private val m1 = message("m1", "red apple pie", timestamp = 200L)
    private val m2 = message("m2", "red car", timestamp = 100L)
    private val m3 = message("m3", "apple juice", timestamp = 300L)
    // Text has no query word — simulates a DAO title-only hit, which the
    // scorer must demote rather than drop silently.
    private val m4 = message("m4", "blue sky", timestamp = 400L)

    private fun message(id: String, text: String, timestamp: Long) = MessageEntity(
        id = id,
        conversationId = "c",
        text = text,
        participant = Participant.USER,
        timestamp = timestamp,
        runId = "run",
        runSequence = 0L,
    )

    /** Fake DAO: behaves like the LIKE query — returns messages whose text contains the word. */
    private fun fakeDao(all: List<MessageEntity>, extras: (String) -> List<MessageEntity> = { emptyList() }): ChatDao {
        val dao = mockk<ChatDao>()
        coEvery { dao.searchMessages(any(), any()) } answers {
            val pattern = firstArg<String>()
            all.filter { it.text.lowercase().contains(pattern) } + extras(pattern)
        }
        coEvery { dao.getMessagesWithCitationSegmentsPage(any(), any()) } returns emptyList()
        return dao
    }

    @Test
    fun multiWord_keepsOnlyMessagesWithAllWords() = runTest {
        val results = searchConversationMessages(fakeDao(listOf(m1, m2, m3)), "red apple", 10)
        assertEquals(listOf("m1"), results.map { it.message.id })
        assertEquals(1.0f, results.single().score)
    }

    @Test
    fun multiWord_newestFirstAmongEqualScores() = runTest {
        val results = searchConversationMessages(fakeDao(listOf(m1, m2, m3)), "red apple", 10)
        // All scores equal (1.0); single result here — order check via single word below.
        assertEquals(1.0f, results.single().score)
    }

    @Test
    fun singleWord_matchesOrderedNewestFirstWithFullScore() = runTest {
        val results = searchConversationMessages(fakeDao(listOf(m1, m2)), "red", 10)
        assertEquals(listOf("m1", "m2"), results.map { it.message.id })
        assertTrue(results.all { it.score == 1.0f })
    }

    @Test
    fun singleWord_titleOnlyHitIsDemotedNotDropped() = runTest {
        val results = searchConversationMessages(
            fakeDao(listOf(m1), extras = { if (it == "red") listOf(m4) else emptyList() }),
            "red",
            10,
        )
        assertEquals(listOf("m1", "m4"), results.map { it.message.id })
        assertEquals(1.0f, results[0].score)
        assertEquals(0.0f, results[1].score)
    }

    @Test
    fun query_wordsAreLowercasedAndPunctuationTrimmed() = runTest {
        val results = searchConversationMessages(fakeDao(listOf(m1)), "RED, APPLE!", 10)
        assertEquals(listOf("m1"), results.map { it.message.id })
    }

    @Test
    fun query_blankOrPunctuationOnlyReturnsEmpty() = runTest {
        val dao = fakeDao(listOf(m1))
        assertTrue(searchConversationMessages(dao, "", 10).isEmpty())
        assertTrue(searchConversationMessages(dao, "   ", 10).isEmpty())
        assertTrue(searchConversationMessages(dao, "!!!", 10).isEmpty())
    }

    @Test
    fun limit_zeroOrLessReturnsEmpty() = runTest {
        val dao = fakeDao(listOf(m1))
        assertTrue(searchConversationMessages(dao, "red", 0).isEmpty())
        assertTrue(searchConversationMessages(dao, "red", -1).isEmpty())
    }

    @Test
    fun limit_capsResults() = runTest {
        val results = searchConversationMessages(fakeDao(listOf(m1, m2, m3)), "red", 1)
        assertEquals(1, results.size)
        assertEquals("m1", results.single().message.id)
    }

    @Test
    fun shortTokens_droppedFromMultiWord() = runTest {
        // "a" is dropped by the 2-char minimum, so only "red" matters.
        val results = searchConversationMessages(fakeDao(listOf(m1, m2, m3)), "a red", 10)
        assertEquals(listOf("m1", "m2"), results.map { it.message.id })
    }
}
