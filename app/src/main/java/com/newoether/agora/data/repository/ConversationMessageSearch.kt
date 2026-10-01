package com.newoether.agora.data.repository

import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.citationRecords
import com.newoether.agora.model.matchesCitationTitle
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

internal fun MessageEntity.matchesCitationTitle(query: String): Boolean {
    val segments = toolCallJson?.let { raw ->
        runCatching { Json.decodeFromString<List<MessageSegment>>(raw) }.getOrNull()
    }.orEmpty()
    return segments.citationRecords(text).matchesCitationTitle(query)
}
private val citationSearchNewestFirst =
    compareByDescending<MessageEntity>(MessageEntity::timestamp).thenByDescending(MessageEntity::id)

internal suspend fun boundedCitationTitleMatches(
    query: String,
    limit: Int,
    pageSize: Int = 128,
    loadPage: suspend (afterId: String, pageSize: Int) -> List<MessageEntity>,
): List<MessageEntity> {
    if (query.isBlank() || limit <= 0) return emptyList()
    val boundedPageSize = pageSize.coerceIn(1, 256)
    val newestMatches = mutableListOf<MessageEntity>()
    var afterId = ""
    while (true) {
        val page = loadPage(afterId, boundedPageSize)
        if (page.isEmpty()) break
        page.asSequence()
            .filter { it.id > afterId && it.matchesCitationTitle(query) }
            .forEach { candidate ->
                if (newestMatches.none { it.id == candidate.id }) {
                    newestMatches += candidate
                    newestMatches.sortWith(citationSearchNewestFirst)
                    if (newestMatches.size > limit) newestMatches.removeAt(newestMatches.lastIndex)
                }
            }
        val nextAfterId = page.maxOf(MessageEntity::id)
        if (nextAfterId <= afterId) break
        afterId = nextAfterId
        if (page.size < boundedPageSize) break
    }
    return newestMatches
}

/** A message with its keyword-search relevance score (0..1). */
data class ScoredMessage(
    val message: MessageEntity,
    /** Fraction of query words found in the message (1.0 = all words matched). */
    val score: Float,
)

internal suspend fun searchConversationMessages(chatDao: ChatDao, query: String, limit: Int): List<ScoredMessage> {
    if (limit <= 0) return emptyList()
    val words = tokenizeQuery(query)
    if (words.isEmpty()) return emptyList()

    // Multi-word AND: search each word, keep messages containing ALL words.
    // Single-word: direct DAO query (unchanged behavior).
    val candidates: List<MessageEntity> = if (words.size == 1) {
        chatDao.searchMessages(escapeLikePattern(words[0]), limit * 2)
    } else {
        val perWord = words.map { word ->
            chatDao.searchMessages(escapeLikePattern(word), limit * 2).associateBy { it.id }
        }
        // Intersect: message must appear in every word's result set.
        val commonIds = perWord.first().keys.intersect(
            perWord.drop(1).fold(perWord.first().keys) { acc, m -> acc.intersect(m.keys) }
        )
        // Also verify all words actually appear (DAO LIKE may match title only).
        commonIds.mapNotNull { perWord.first()[it] }
            .filter { msg -> words.all { word -> messageContainsWord(msg, word) } }
    }

    val citationMatches = boundedCitationTitleMatches(query, limit) { afterId, pageSize ->
        chatDao.getMessagesWithCitationSegmentsPage(afterId, pageSize)
    }

    // Score by word coverage; citation matches get a fixed mid score.
    val scored = candidates.map { msg ->
        val matched = words.count { word -> messageContainsWord(msg, word) }
        ScoredMessage(msg, (matched.toFloat() / words.size).coerceIn(0f, 1f))
    } + citationMatches.map { ScoredMessage(it, 0.5f) }

    return scored
        .distinctBy { it.message.id }
        .sortedWith(
            compareByDescending<ScoredMessage> { it.score }
                .thenByDescending { it.message.timestamp }
                .thenByDescending { it.message.id }
        )
        .take(limit)
}

/** Splits a query into lowercase search words, dropping blanks and punctuation-only tokens. */
private fun tokenizeQuery(query: String): List<String> =
    query.lowercase()
        .split(Regex("\\s+"))
        .map { it.trim().trim { c -> !c.isLetterOrDigit() } }
        .filter { it.length >= 2 }

private fun messageContainsWord(msg: MessageEntity, word: String): Boolean =
    msg.text.lowercase().contains(word)

/** Escapes LIKE wildcards so a literal "%"/"_" in the user's query matches itself
 *  instead of matching everything (paired with ESCAPE '\' in the DAO query). */
private fun escapeLikePattern(query: String): String =
    query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
