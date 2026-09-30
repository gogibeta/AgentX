package com.newoether.agora.api.monid

import com.newoether.agora.api.HttpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Minimal client for the Monid gateway (`https://api.monid.ai`), used for the free
 * TinyFish live web `/search` and browser-rendered `/fetch` endpoints.
 *
 * REST contract reverse-engineered from the official `@monid-ai/cli` source
 * (`MonidAPI.run/getRun`) and verified live:
 * - `POST /v1/run` with `{provider, endpoint, input?: {body?, queryParams?}}`,
 *   `Authorization: Bearer <monid_live_...>` → `{runId, status, output?}`.
 * - Terminal statuses: `COMPLETED` / `FAILED` / `BLOCKED`; otherwise poll
 *   `GET /v1/runs/{runId}` until terminal.
 */
object MonidClient {
    const val DEFAULT_BASE_URL = "https://api.monid.ai"
    const val PROVIDER_TINYFISH = "tinyfish"
    const val ENDPOINT_SEARCH = "/search"
    const val ENDPOINT_FETCH = "/fetch"

    private const val REQUEST_TIMEOUT_MS = 30_000L
    private const val POLL_INTERVAL_MS = 2_000L
    private const val POLL_TIMEOUT_MS = 90_000L

    private val json = Json { ignoreUnknownKeys = true }

    data class WebHit(
        val title: String,
        val url: String,
        val snippet: String,
        /** "tinyfish" or "duckduckgo" — set by the fusion layer. */
        val source: String = PROVIDER_TINYFISH,
    )

    open class MonidException(message: String) : java.io.IOException(message)
    class MonidApiException(val status: String, message: String) : MonidException("$status: $message")
    class MonidAuthException : MonidException("Invalid Monid API key (HTTP 401)")

    fun canonicalBaseUrl(baseUrl: String?): String =
        baseUrl?.trim()?.trimEnd('/')?.ifBlank { null } ?: DEFAULT_BASE_URL

    private fun authHeaders(apiKey: String): Map<String, String> = mapOf(
        "Authorization" to "Bearer $apiKey",
        "X-Monid-Client" to "agentx",
    )

    /**
     * TinyFish live `/search`. [purpose] is a short statement of what the results are
     * for — TinyFish uses it as a ranking signal.
     */
    suspend fun search(
        apiKey: String,
        query: String,
        numResults: Int,
        purpose: String? = null,
        baseUrl: String? = null,
    ): List<WebHit> = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("provider", PROVIDER_TINYFISH)
            put("endpoint", ENDPOINT_SEARCH)
            putJsonObject("input") {
                putJsonObject("queryParams") {
                    put("query", query)
                    if (!purpose.isNullOrBlank()) put("purpose", purpose.take(2000))
                }
            }
        }.toString()
        val output = runAndWait(apiKey, body, canonicalBaseUrl(baseUrl))
        parseSearchOutput(output).take(numResults.coerceIn(1, 10))
    }

    /**
     * TinyFish browser-rendered `/fetch` for 1..10 URLs. Returns url → markdown text
     * for the URLs that succeeded; failures appear nowhere (per-URL errors never fail
     * the batch, they land in `errors[]`).
     */
    suspend fun fetch(
        apiKey: String,
        urls: List<String>,
        purpose: String? = null,
        baseUrl: String? = null,
    ): Map<String, String> = withContext(Dispatchers.IO) {
        require(urls.size in 1..10) { "TinyFish /fetch takes 1..10 URLs" }
        val body = buildJsonObject {
            put("provider", PROVIDER_TINYFISH)
            put("endpoint", ENDPOINT_FETCH)
            putJsonObject("input") {
                putJsonObject("body") {
                    put("urls", buildJsonArray(urls))
                    put("format", "markdown")
                    if (!purpose.isNullOrBlank()) put("purpose", purpose.take(2000))
                }
            }
        }.toString()
        val output = runAndWait(apiKey, body, canonicalBaseUrl(baseUrl))
        parseFetchOutput(output)
    }

    private fun buildJsonArray(urls: List<String>): JsonArray =
        JsonArray(urls.map { Json.parseToJsonElement("\"$it\"") })

    private suspend fun runAndWait(apiKey: String, body: String, base: String): JsonObject {
        val headers = authHeaders(apiKey)
        val first = HttpClient.postTextResponse(
            "$base/v1/run", body, headers, callTimeoutMillis = REQUEST_TIMEOUT_MS,
        )
        if (first.code == 401) throw MonidAuthException()
        if (!first.isSuccessful) throw MonidException("Monid POST /v1/run HTTP ${first.code}")
        var run = json.parseToJsonElement(first.body).jsonObject
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
        while (!isTerminal(run)) {
            if (System.currentTimeMillis() > deadline) {
                throw MonidException("Monid run timed out")
            }
            delay(POLL_INTERVAL_MS)
            val runId = run["runId"]?.jsonPrimitive?.contentOrNull
                ?: throw MonidException("Monid run response missing runId")
            val polled = HttpClient.getTextResponse("$base/v1/runs/$runId", headers)
            if (polled.code == 401) throw MonidAuthException()
            if (!polled.isSuccessful) throw MonidException("Monid poll HTTP ${polled.code}")
            run = json.parseToJsonElement(polled.body).jsonObject
        }
        val status = run["status"]?.jsonPrimitive?.contentOrNull
        if (status != "COMPLETED") {
            throw MonidApiException(status ?: "unknown", extractRunError(run))
        }
        return run["output"]?.jsonObject ?: JsonObject(emptyMap())
    }

    internal fun isTerminal(run: JsonObject): Boolean {
        val status = run["status"]?.jsonPrimitive?.contentOrNull
        return status == "COMPLETED" || status == "FAILED" || status == "BLOCKED"
    }

    internal fun extractRunError(run: JsonObject): String {
        val error = run["error"] as? JsonObject
        return error?.get("message")?.jsonPrimitive?.contentOrNull
            ?: run["message"]?.jsonPrimitive?.contentOrNull
            ?: "run did not complete"
    }

    internal fun parseSearchOutput(output: JsonObject): List<WebHit> {
        val results = output["results"] as? JsonArray ?: return emptyList()
        return results.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val url = obj["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            WebHit(
                title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                url = url,
                snippet = obj["snippet"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }
    }

    internal fun parseFetchOutput(output: JsonObject): Map<String, String> {
        val results = output["results"] as? JsonArray ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (element in results) {
            val obj = element as? JsonObject ?: continue
            val url = obj["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: continue
            val text = obj["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: continue
            out[url] = text
        }
        return out
    }

    /**
     * Fusion de-duplication: interleave TinyFish-first with DuckDuckGo hits, dropping
     * URL duplicates (normalized: lowercase host, no trailing slash, no tracking
     * query params). Pure — unit-tested.
     */
    internal fun fuseDedupe(
        tinyFish: List<WebHit>,
        duckDuckGo: List<WebHit>,
        maxResults: Int,
    ): List<WebHit> {
        val seen = LinkedHashSet<String>()
        val fused = ArrayList<WebHit>(maxResults)
        val tiny = tinyFish.iterator()
        val ddg = duckDuckGo.iterator()
        // Alternate starting with TinyFish (live, ranked); always fill from TinyFish first
        // when one side runs dry.
        while (fused.size < maxResults && (tiny.hasNext() || ddg.hasNext())) {
            if (tiny.hasNext()) {
                val hit = tiny.next()
                if (seen.add(normalizeUrl(hit.url))) fused.add(hit)
            }
            if (fused.size >= maxResults) break
            if (ddg.hasNext()) {
                val hit = ddg.next()
                if (seen.add(normalizeUrl(hit.url))) fused.add(hit.copy(source = "duckduckgo"))
            }
        }
        return fused
    }

    internal fun normalizeUrl(url: String): String {
        var u = url.trim().lowercase()
        // Strip tracking params but keep the path + meaningful query.
        val queryStart = u.indexOf('?')
        if (queryStart >= 0) {
            val base = u.substring(0, queryStart)
            val kept = u.substring(queryStart + 1).split('&')
                .filter { param ->
                    val key = param.substringBefore('=')
                    !key.startsWith("utm_") && key != "fbclid" && key != "gclid" &&
                        key != "si" && key != "ref"
                }
            u = if (kept.isEmpty()) base else "$base?${kept.joinToString("&")}"
        }
        u = u.removePrefix("https://").removePrefix("http://").removePrefix("www.")
        return u.trimEnd('/')
    }
}
