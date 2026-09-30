package com.newoether.agora.api.typesafe

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Fail-open Jev decision helpers over [TypeSafeClient].
 *
 * Every function returns null when Jev is unavailable (no key, network error,
 * undecodable answer) — callers must treat null as "no signal" and fall back to
 * their non-Jev behavior. Jev never breaks a user-visible flow; it only sharpens
 * ranking/routing/compaction when it answers. Thresholds always belong to the
 * caller (kojev rule: the number is yours, written where the decision is made).
 */
object JevDecisions {
    private val json = Json { ignoreUnknownKeys = true }

    fun isConfigured(apiKey: String?): Boolean = !apiKey.isNullOrBlank()

    /**
     * One batched `decide` call with a Noul "does this document answer the query?"
     * per candidate. Returns query-keyed probabilities in the same order, or null.
     * Used to re-rank fused web-search hits.
     */
    suspend fun relevanceScores(
        apiKey: String,
        baseUrl: String?,
        model: String = TypeSafeClient.DEFAULT_MODEL,
        query: String,
        documents: List<String>,
    ): List<Double>? {
        if (documents.isEmpty()) return emptyList()
        return try {
            val questions = documents.mapIndexed { index, _ ->
                "doc_$index" to TypeSafeClient.NoulQuestion(
                    key = "doc_$index",
                    instructions = "Does this search result answer the query: \"$query\"?",
                )
            }.toMap()
            val state = buildJsonObject {
                documents.forEachIndexed { index, doc ->
                    put("doc_$index", doc.take(1500))
                }
            }
            val decision = TypeSafeClient.decide(apiKey, baseUrl, model, state, questions)
            documents.indices.map { index ->
                (decision.answers["doc_$index"] as? TypeSafeClient.JevAnswer.Noul)
                    ?.probability
            }.let { probs ->
                if (probs.any { it == null }) null else probs.filterNotNull()
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Single Choice decision. Returns (winner, confidence) or null. */
    suspend fun choose(
        apiKey: String,
        baseUrl: String?,
        model: String = TypeSafeClient.DEFAULT_MODEL,
        state: String,
        questionKey: String,
        instructions: String,
        options: Map<String, String?>,
    ): Pair<String, Double>? {
        return try {
            val questions = mapOf(
                questionKey to TypeSafeClient.ChoiceQuestion(questionKey, instructions, options),
            )
            val decision = TypeSafeClient.decide(
                apiKey, baseUrl, model, JsonPrimitive(state), questions,
            )
            val ans = decision.answers[questionKey] as? TypeSafeClient.JevAnswer.Choice
                ?: return null
            ans.value to ans.confidence
        } catch (_: Exception) {
            null
        }
    }

    /** Score a single item against an ordered rubric. Returns (meanLevel, confidence) or null. */
    suspend fun score(
        apiKey: String,
        baseUrl: String?,
        model: String = TypeSafeClient.DEFAULT_MODEL,
        state: String,
        questionKey: String,
        instructions: String,
        levels: List<String>,
    ): Pair<Double, Double>? {
        return try {
            val questions = mapOf(
                questionKey to TypeSafeClient.ScoreQuestion(questionKey, instructions, levels),
            )
            val decision = TypeSafeClient.decide(
                apiKey, baseUrl, model, JsonPrimitive(state), questions,
            )
            val ans = decision.answers[questionKey] as? TypeSafeClient.JevAnswer.Score
                ?: return null
            ans.score to ans.confidence
        } catch (_: Exception) {
            null
        }
    }

    fun stateOf(text: String): JsonElement = JsonPrimitive(text)
}
