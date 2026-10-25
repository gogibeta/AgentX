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
 *
 * Use [JevError] variants when the caller needs to explain WHY Jev failed
 * (e.g. the prune_context tool surfacing diagnostics to the user).
 */
object JevDecisions {
    private val json = Json { ignoreUnknownKeys = true }

    fun isConfigured(apiKey: String?): Boolean = !apiKey.isNullOrBlank()

    /** Why a Jev call failed — for user-visible diagnostics. Never contains secrets. */
    sealed interface JevError {
        /** No API key configured. */
        data object NoKey : JevError
        /** HTTP error from the Jev endpoint (status + safe message). */
        data class Api(val status: Int, val message: String) : JevError
        /** Network failure (DNS, timeout, connection). */
        data class Network(val message: String) : JevError
        /** Response decoded but answers were unusable. */
        data object BadResponse : JevError
    }

    /** Classify a Jev failure for diagnostics. Returns null only for non-Jev exceptions. */
    fun classifyError(apiKey: String?, e: Exception): JevError = when {
        apiKey.isNullOrBlank() -> JevError.NoKey
        e is TypeSafeClient.JevApiException -> JevError.Api(e.status, e.message?.take(200) ?: "")
        e is TypeSafeClient.JevNetworkException -> JevError.Network(e.message?.take(200) ?: "")
        e is TypeSafeClient.JevResponseException -> JevError.BadResponse
        e is java.io.IOException -> JevError.Network(e.message?.take(200) ?: "")
        else -> JevError.Network(e.message?.take(200) ?: e.javaClass.simpleName)
    }

    /** Human-readable, secret-free description of a [JevError]. */
    fun describeError(error: JevError): String = when (error) {
        JevError.NoKey -> "no decision-model API key configured — add one in Settings → Jev to enable"
        is JevError.Api -> when (error.status) {
            401 -> "rejected (401): API key invalid or revoked — check the key in Settings → Jev"
            402 -> "out of credit (402): the account has no spendable credit — top up (Drex: drex.nace.ai dashboard)"
            403 -> "forbidden (403): key lacks decision-model access"
            404 -> "not found (404): base URL or model wrong — check Settings → Jev (POST /v1/systemone must exist)"
            429 -> "rate-limited (429): too many requests — try again shortly"
            in 500..599 -> "server error (${error.status}): decision endpoint is down — try again later"
            else -> "HTTP ${error.status}: ${error.message}"
        }
        is JevError.Network -> "network error: ${error.message} — check connectivity and base URL"
        JevError.BadResponse -> "bad response: decision endpoint answered but the decision could not be decoded"
    }

    /**
     * One batched `decide` call with a Noul "does this document answer the query?"
     * per candidate. Returns query-keyed probabilities in the same order, or null.
     * Used to re-rank fused web-search hits.
     *
     * [timeoutMs] / [maxStateChars] follow the active provider: Drex allows a
     * 60s timeout and ~4x the state budget (131k vs 32k tokens).
     */
    suspend fun relevanceScores(
        apiKey: String,
        baseUrl: String?,
        model: String = TypeSafeClient.DEFAULT_MODEL,
        query: String,
        documents: List<String>,
        timeoutMs: Long = TypeSafeClient.DEFAULT_TIMEOUT_MS,
        maxStateChars: Int = 1500,
    ): List<Double>? =
        relevanceScoresWithError(apiKey, baseUrl, model, query, documents, timeoutMs, maxStateChars).getOrNull()

    /**
     * Like [relevanceScores] but returns the [JevError] on failure instead of
     * swallowing it — for callers that must explain the failure to the user
     * (e.g. the prune_context tool).
     */
    suspend fun relevanceScoresWithError(
        apiKey: String,
        baseUrl: String?,
        model: String = TypeSafeClient.DEFAULT_MODEL,
        query: String,
        documents: List<String>,
        timeoutMs: Long = TypeSafeClient.DEFAULT_TIMEOUT_MS,
        maxStateChars: Int = 1500,
    ): Result<List<Double>> {
        if (documents.isEmpty()) return Result.success(emptyList())
        return try {
            val questions = documents.mapIndexed { index, _ ->
                "doc_$index" to TypeSafeClient.NoulQuestion(
                    key = "doc_$index",
                    instructions = "Does this search result answer the query: \"$query\"?",
                )
            }.toMap()
            val state = buildJsonObject {
                documents.forEachIndexed { index, doc ->
                    put("doc_$index", doc.take(maxStateChars))
                }
            }
            val decision = TypeSafeClient.decide(apiKey, baseUrl, model, state, questions, timeoutMs)
            val probs = documents.indices.map { index ->
                (decision.answers["doc_$index"] as? TypeSafeClient.JevAnswer.Noul)
                    ?.probability
            }
            if (probs.any { it == null }) {
                Result.failure(JevFailureException(JevError.BadResponse))
            } else {
                Result.success(probs.filterNotNull())
            }
        } catch (e: Exception) {
            Result.failure(JevFailureException(classifyError(apiKey, e)))
        }
    }

    /** Wraps a [JevError] so it can travel through [Result]. Never holds secrets. */
    class JevFailureException(val error: JevError) : Exception(describeError(error))

    /** Single Choice decision. Returns (winner, confidence) or null. */
    suspend fun choose(
        apiKey: String,
        baseUrl: String?,
        model: String = TypeSafeClient.DEFAULT_MODEL,
        state: String,
        questionKey: String,
        instructions: String,
        options: Map<String, String?>,
        timeoutMs: Long = TypeSafeClient.DEFAULT_TIMEOUT_MS,
    ): Pair<String, Double>? {
        return try {
            val questions = mapOf(
                questionKey to TypeSafeClient.ChoiceQuestion(questionKey, instructions, options),
            )
            val decision = TypeSafeClient.decide(
                apiKey, baseUrl, model, JsonPrimitive(state), questions, timeoutMs,
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
        timeoutMs: Long = TypeSafeClient.DEFAULT_TIMEOUT_MS,
    ): Pair<Double, Double>? {
        return try {
            val questions = mapOf(
                questionKey to TypeSafeClient.ScoreQuestion(questionKey, instructions, levels),
            )
            val decision = TypeSafeClient.decide(
                apiKey, baseUrl, model, JsonPrimitive(state), questions, timeoutMs,
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
