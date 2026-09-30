package com.newoether.agora.api.typesafe

import com.newoether.agora.api.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import kotlin.math.pow
import kotlin.random.Random

/**
 * Minimal Kotlin client for TypeSafe AI's System One API (Jev) — wire format verified
 * against the published OpenAPI spec via the kojev project's api-notes (base URL
 * `https://api.typesafe.ai`, Bearer auth, `POST /v1/systemone`, `GET /v1/models`).
 *
 * Jev is a *decision* model, not a chat model: it answers typed Choice / Score / Noul
 * questions about a state object and returns calibrated probabilities. This client
 * backs the TypeSafe provider entry (API key + custom base URL settings) and the
 * Jev decision layer (routing, guardrails, compaction scoring).
 *
 * Retry policy mirrors the official SDK defaults: retry 408/429/5xx + connection
 * errors/timeouts, max 2 retries, 0.5s initial backoff doubling to a 5s cap with
 * up-to-25% jitter.
 */
object TypeSafeClient {
    const val DEFAULT_BASE_URL = "https://api.typesafe.ai"
    const val DEFAULT_MODEL = "jev-latest"
    private const val REQUEST_TIMEOUT_MS = 10_000L
    private const val MAX_RETRIES = 2
    private const val INITIAL_BACKOFF_MS = 500L
    private const val MAX_BACKOFF_MS = 5_000L

    private val json = Json { ignoreUnknownKeys = true }

    sealed interface JevQuestion {
        val key: String
        val instructions: String
        fun toJson(): JsonObject
    }

    data class ChoiceQuestion(
        override val key: String,
        override val instructions: String,
        /** option name -> description (null description allowed by the API). */
        val criteria: Map<String, String?>,
    ) : JevQuestion {
        override fun toJson(): JsonObject = buildJsonObject {
            put("type", "choice")
            put("instructions", instructions)
            putJsonObject("criteria") {
                criteria.forEach { (name, desc) ->
                    if (desc == null) put(name, JsonNull) else put(name, desc)
                }
            }
        }
    }

    data class ScoreQuestion(
        override val key: String,
        override val instructions: String,
        /** ordered rubric levels, position = level number (0-based). 2..10 levels. */
        val criteria: List<String>,
    ) : JevQuestion {
        override fun toJson(): JsonObject = buildJsonObject {
            put("type", "score")
            put("instructions", instructions)
            putJsonArray("criteria") { criteria.forEach { add(JsonPrimitive(it)) } }
        }
    }

    data class NoulQuestion(
        override val key: String,
        override val instructions: String,
        val whenTrue: String? = null,
        val whenFalse: String? = null,
    ) : JevQuestion {
        override fun toJson(): JsonObject = buildJsonObject {
            put("type", "noul")
            put("instructions", instructions)
            if (whenTrue != null || whenFalse != null) {
                putJsonObject("criteria") {
                    whenTrue?.let { put("true", it) }
                    whenFalse?.let { put("false", it) }
                }
            }
        }
    }

    sealed interface JevAnswer {
        data class Choice(
            val value: String,
            val probabilities: Map<String, Double>,
            val confidence: Double,
        ) : JevAnswer

        /** [score] is the probability-weighted mean level — a float, not a level index. */
        data class Score(
            val score: Double,
            val confidence: Double,
            val probabilities: Map<Int, Double>,
        ) : JevAnswer

        /** Noul carries no confidence field — the probability IS the answer. */
        data class Noul(val probability: Double) : JevAnswer
    }

    data class JevDecision(
        val model: String,
        val answers: Map<String, JevAnswer>,
        val inputTokens: Int,
        val requestId: String?,
    )

    open class JevException(message: String, cause: Throwable? = null) : IOException(message, cause)
    class JevApiException(val status: Int, message: String, val requestId: String?) :
        JevException("TypeSafe API error $status: $message")
    class JevNetworkException(message: String, cause: Throwable? = null) :
        JevException(message, cause)
    class JevResponseException(message: String) : JevException(message)

    /**
     * Strips one trailing `/v1` so both base styles work: official
     * `https://api.typesafe.ai` and router bases like `https://host/v1`
     * (proven on device: nara serves systemone at `{base}/systemone`).
     */
    fun canonicalBaseUrl(baseUrl: String?): String {
        val trimmed = baseUrl?.trim()?.trimEnd('/')?.ifBlank { null } ?: DEFAULT_BASE_URL
        return trimmed.removeSuffix("/v1").ifBlank { DEFAULT_BASE_URL }
    }

    private fun authHeaders(apiKey: String): Map<String, String> =
        mapOf("Authorization" to "Bearer $apiKey")

    /** `GET {base}/v1/models` — model/alias names available to this key. */
    suspend fun listModels(apiKey: String, baseUrl: String?): List<String> =
        withContext(Dispatchers.IO) {
            val url = "${canonicalBaseUrl(baseUrl)}/v1/models"
            val response = HttpClient.fetchModelsResponse(
                url,
                authHeaders(apiKey),
                callTimeoutMillis = REQUEST_TIMEOUT_MS,
            )
            if (!response.isSuccessful) {
                throw JevApiException(
                    response.code,
                    extractErrorMessage(response.body),
                    requestId = null,
                )
            }
            parseModelNames(response.body)
        }

    internal fun parseModelNames(body: String): List<String> {
        val root = json.parseToJsonElement(body)
        val items: JsonArray = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["data"] as? JsonArray)
                ?: (root["models"] as? JsonArray)
                ?: throw JevResponseException("Unexpected /v1/models shape")
            else -> throw JevResponseException("Unexpected /v1/models shape")
        }
        return items.mapNotNull {
            val obj = it as? JsonObject ?: return@mapNotNull null
            (obj["name"] ?: obj["id"])?.jsonPrimitive?.contentOrNull
        }
    }

    /**
     * `POST {base}/v1/systemone` — answer [questions] about [state] with [model].
     * Never throws for low confidence: thresholds belong to the caller.
     */
    suspend fun decide(
        apiKey: String,
        baseUrl: String?,
        model: String = DEFAULT_MODEL,
        state: JsonElement,
        questions: Map<String, JevQuestion>,
    ): JevDecision {
        require(questions.isNotEmpty()) { "questions must have at least one entry" }
        val url = "${canonicalBaseUrl(baseUrl)}/v1/systemone"
        val body = buildJsonObject {
            put("state", state)
            put("model", model)
            putJsonObject("questions") {
                questions.forEach { (key, q) -> put(key, q.toJson()) }
            }
        }.toString()
        val (status, responseBody) = postWithRetry(url, body, authHeaders(apiKey))
        return parseDecision(status, responseBody, questions)
    }

    private suspend fun postWithRetry(
        url: String,
        body: String,
        headers: Map<String, String>,
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        var attempt = 0
        var lastFailure: Exception? = null
        val retryableStatuses: Set<Int> = (500..599).toSet() + setOf(408, 429)
        while (attempt <= MAX_RETRIES) {
            val attemptHeaders = if (attempt > 0) {
                headers + ("X-TypeSafe-Retry-Count" to attempt.toString())
            } else {
                headers
            }
            try {
                val response = HttpClient.postTextResponse(
                    url,
                    body,
                    attemptHeaders,
                    callTimeoutMillis = REQUEST_TIMEOUT_MS,
                )
                if (response.isSuccessful) return@withContext response.code to response.body
                if (response.code !in retryableStatuses) {
                    throw JevApiException(response.code, extractErrorMessage(response.body), null)
                }
                lastFailure =
                    JevApiException(response.code, extractErrorMessage(response.body), null)
            } catch (e: JevApiException) {
                throw e
            } catch (e: IOException) {
                lastFailure = JevNetworkException("TypeSafe request failed: ${e.message}", e)
            }
            if (attempt == MAX_RETRIES) break
            delay(computeBackoffMs(attempt))
            attempt++
        }
        throw lastFailure ?: JevNetworkException("TypeSafe request failed")
    }

    internal fun computeBackoffMs(retryIndex: Int): Long {
        val computed = (INITIAL_BACKOFF_MS * 2.0.pow(retryIndex)).toLong()
            .coerceAtMost(MAX_BACKOFF_MS)
        // Official SDKs subtract up to 25% jitter.
        return (computed * (0.75 + Random.nextDouble() * 0.25)).toLong()
    }

    internal fun extractErrorMessage(body: String): String {
        if (body.isBlank()) return "empty error body"
        return try {
            when (val root = json.parseToJsonElement(body)) {
                is JsonPrimitive ->
                    root.contentOrNull?.ifBlank { body } ?: body
                is JsonObject -> {
                    fun str(key: String): String? =
                        (root[key] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                            ?: root[key]?.jsonPrimitive?.contentOrNull
                    str("message") ?: str("error") ?: str("detail") ?: body.take(300)
                }
                else -> body.take(300)
            }
        } catch (_: Exception) {
            body.take(300)
        }
    }

    internal fun parseDecision(
        status: Int,
        body: String,
        questions: Map<String, JevQuestion>,
    ): JevDecision {
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (_: Exception) {
            throw JevResponseException("TypeSafe returned non-JSON body (HTTP $status)")
        }
        val model = root["model"]?.jsonPrimitive?.contentOrNull ?: DEFAULT_MODEL
        val answersObj = root["answers"]?.jsonObject
            ?: throw JevResponseException("TypeSafe response missing 'answers'")
        val usage = root["usage"]?.jsonObject
        val answers = questions.mapValues { (key, q) ->
            val ans = answersObj[key]?.jsonObject
                ?: throw JevResponseException("TypeSafe response missing answer '$key'")
            parseAnswer(key, q, ans)
        }
        return JevDecision(
            model = model,
            answers = answers,
            inputTokens = usage?.get("input_tokens")?.jsonPrimitive?.contentOrNull
                ?.toIntOrNull() ?: 0,
            requestId = null,
        )
    }

    private fun parseAnswer(key: String, question: JevQuestion, ans: JsonObject): JevAnswer {
        return when (question) {
            is ChoiceQuestion -> JevAnswer.Choice(
                value = ans["choice"]?.jsonPrimitive?.contentOrNull
                    ?: throw JevResponseException("Choice answer '$key' missing 'choice'"),
                probabilities = ans["probabilities"]?.jsonObject?.mapValues {
                    it.value.jsonPrimitive.doubleOrNull ?: 0.0
                } ?: emptyMap(),
                confidence = ans["confidence"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            )
            is ScoreQuestion -> JevAnswer.Score(
                score = ans["score"]?.jsonPrimitive?.doubleOrNull
                    ?: throw JevResponseException("Score answer '$key' missing 'score'"),
                confidence = ans["confidence"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                probabilities = ans["probabilities"]?.jsonObject?.mapValues {
                    it.value.jsonPrimitive.doubleOrNull ?: 0.0
                }?.mapKeys { it.key.toIntOrNull() ?: -1 } ?: emptyMap(),
            )
            is NoulQuestion -> JevAnswer.Noul(
                probability = ans["noul"]?.jsonPrimitive?.doubleOrNull
                    ?: throw JevResponseException("Noul answer '$key' missing 'noul'"),
            )
        }
    }
}
