package com.newoether.agora.tool

import androidx.core.text.HtmlCompat
import com.newoether.agora.api.DuckDuckGoScraper
import com.newoether.agora.api.HttpClient
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.api.monid.MonidClient
import com.newoether.agora.api.typesafe.AnswerGuard
import com.newoether.agora.api.typesafe.JevDecisions
import com.newoether.agora.diagnostics.StructuredDiagnostics
import com.newoether.agora.diagnostics.StructuredDiagnosticCategory
import com.newoether.agora.util.Constants
import com.newoether.agora.data.normalizeWebSearchProvider
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.encodeToString
import java.util.concurrent.TimeUnit

internal fun searxngSearchUrl(configuredBaseUrl: String, query: String): String {
    val baseUrl = configuredBaseUrl.ifBlank { "https://searx.be" }.trimEnd('/')
    val encodedQuery = java.net.URLEncoder.encode(query, Charsets.UTF_8.name())
    return "$baseUrl/search?q=$encodedQuery&format=json"
}

internal fun kagiSearchRequestBody(query: String, numResults: Int): String =
    Json.encodeToString(
        buildJsonObject {
            put("query", query)
            put("workflow", "search")
            put("limit", numResults.coerceIn(1, 10))
        }
    )

internal fun normalizeKagiSearchResponse(
    responseBody: String,
    query: String,
    numResults: Int,
): String {
    val root = Json.parseToJsonElement(responseBody) as? JsonObject
    val data = root?.get("data") as? JsonObject
    val searchResults = data?.get("search") as? JsonArray
        ?: return buildJsonObject {
            put("type", "web_search")
            put("query", query)
            put("error", "no_results")
        }.toString()

    val normalizedResults = buildJsonArray {
        var added = 0
        for (element in searchResults) {
            if (added >= numResults.coerceIn(1, 10)) break
            val result = element as? JsonObject ?: continue
            val url = (result["url"] as? JsonPrimitive)?.content.orEmpty()
            if (url.isBlank()) continue
            add(
                buildJsonObject {
                    put("title", (result["title"] as? JsonPrimitive)?.content.orEmpty())
                    put("url", url)
                    put("description", (result["snippet"] as? JsonPrimitive)?.content.orEmpty())
                }
            )
            added++
        }
    }
    if (normalizedResults.isEmpty()) {
        return buildJsonObject {
            put("type", "web_search")
            put("query", query)
            put("error", "no_results")
        }.toString()
    }

    return buildJsonObject {
        put("type", "web_search")
        put("query", query)
        put("results", normalizedResults)
    }.toString()
}

internal fun webSearchProviderDisplayName(provider: String): String = when (provider) {
    "kagi" -> "Kagi"
    "serper" -> "Serper"
    "tavily" -> "Tavily"
    "searxng" -> "SearXNG"
    "duckduckgo" -> "DuckDuckGo"
    "tinyfish" -> "Monid TinyFish"
    "fusion" -> "Fusion (DuckDuckGo + TinyFish)"
    else -> "Brave Search"
}

class WebSearchToolProvider : ToolProvider {
    private val webClient = HttpClient.client.newBuilder()
        .callTimeout(Constants.NETWORK_TOOL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(Constants.NETWORK_TOOL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.webSearchEnabled) return emptyList()
        return listOf(
            ToolDefinition(function = ToolFunction(
                name = "web_search",
                description = "Search the web for current information. Use this to find facts, news, or data not in your training set.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "query" to ToolProperty("string", "The search query to execute."),
                        "num_results" to ToolProperty("integer", "Number of results to return (1-10, default 5).")
                    ),
                    required = listOf("query")
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = "web_fetch",
                description = "Fetch and read the full text content of a web page. Use this after web_search when you need more detail from a specific page.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "url" to ToolProperty("string", "The URL of the page to fetch."),
                        "maxChars" to ToolProperty("integer", "Maximum characters of text to return (default 8000, max 100000). If the result has \"truncated\": true, call again with a larger maxChars to get more.")
                    ),
                    required = listOf("url")
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = "browse_page",
                description = "Open a web page in a fast browser view: JavaScript-rendered, " +
                    "distilled to readable Markdown with links kept. Prefer over web_fetch " +
                    "for JS-heavy pages, docs and articles. Returns title, url and markdown.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "url" to ToolProperty("string", "The page URL to open."),
                        "goal" to ToolProperty(
                            "string",
                            "What to look for on the page (one line). Guides extraction " +
                                "and the relevance score.",
                        ),
                        "maxChars" to ToolProperty("integer", "Maximum characters of markdown to return (default 8000, max 30000)."),
                    ),
                    required = listOf("url")
                )
            ))
        )
    }

    override suspend fun execute(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): String = withContext(Dispatchers.IO) {
        when (name) {
            "web_search" -> executeWebSearch(arguments, ctx)
            "web_fetch" -> executeWebFetch(arguments, ctx)
            "browse_page" -> executeBrowsePage(arguments, ctx)
            else -> "Unknown tool: $name"
        }
    }

    override fun handles(name: String): Boolean = name in setOf("web_search", "web_fetch", "browse_page")

    /**
     * Fast in-app browser: TinyFish browser-rendered Markdown when its key is
     * set (JS pages work), plain scraper otherwise; optional Jev Noul relevance
     * score against [goal] when the TypeSafe key is set. Fail-open throughout.
     */
    private suspend fun executeBrowsePage(arguments: String, ctx: GenerationContext): String {
        val argsStr = arguments.ifBlank { "{}" }
        val args = try {
            Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(argsStr)
        } catch (_: Exception) {
            return buildJsonObject { put("type", "browse"); put("error", "bad_arguments") }.toString()
        }
        val url = (args["url"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: return buildJsonObject { put("type", "browse"); put("error", "no_url") }.toString()
        val goal = (args["goal"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        val maxChars = ((args["maxChars"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 8000)
            .coerceIn(500, 30000)
        return try {
            // Browser-rendered Markdown first (TinyFish), scraper fallback.
            var markdown: String? = null
            var renderedBy = "scraper"
            val monidKey = ctx.webSearchApiKeys["tinyfish"].orEmpty()
            if (monidKey.isNotBlank()) {
                markdown = try {
                    MonidClient.fetch(monidKey, listOf(url), purpose = goal).values.firstOrNull()
                } catch (_: Exception) {
                    null
                }
                if (!markdown.isNullOrBlank()) renderedBy = "tinyfish"
            }
            if (markdown.isNullOrBlank()) {
                markdown = try {
                    val html = HttpClient.fetchModels(
                        url,
                        mapOf(
                            "User-Agent" to Constants.WEB_FETCH_USER_AGENT,
                            "Accept" to "text/html,application/xhtml+xml,*/*",
                        ),
                        callTimeoutMillis = Constants.NETWORK_TOOL_TIMEOUT_MS,
                    )
                    html?.let { htmlToReadableText(it) }
                } catch (_: Exception) {
                    null
                }
            }
            if (markdown.isNullOrBlank()) {
                return buildJsonObject {
                    put("type", "browse")
                    put("url", url)
                    put("error", "fetch_failed")
                }.toString()
            }
            val clipped = AnswerGuard.cleanForSynthesis(markdown, maxChars)
            // Jev relevance vs goal (one batched Noul, fail-open).
            var relevance: Double? = null
            if (!goal.isNullOrBlank() && ctx.jevEnabled && JevDecisions.isConfigured(ctx.typeSafeApiKey)) {
                val jevStartNanos = System.nanoTime()
                relevance = try {
                    JevDecisions.relevanceScores(
                        apiKey = ctx.typeSafeApiKey,
                        baseUrl = ctx.typeSafeBaseUrl,
                        model = ctx.jevModel,
                        query = goal,
                        documents = listOf(clipped.take(ctx.decisionMaxStateChars)),
                        timeoutMs = ctx.decisionTimeoutMs,
                        maxStateChars = ctx.decisionMaxStateChars,
                    )?.firstOrNull()
                } catch (_: Exception) {
                    null
                }
                // §6.1 structured `llm` event for the Jev relevance check. Only the
                // outcome and doc count are recorded — never the query or document.
                StructuredDiagnostics.emit(
                    category = StructuredDiagnosticCategory.LLM,
                    name = "jev_relevance",
                    outcome = if (relevance != null) "ok" else "no_signal",
                    durationMs = (System.nanoTime() - jevStartNanos) / 1_000_000L,
                    detail = mapOf(
                        "model" to ctx.jevModel,
                        "key_fingerprint" to StructuredDiagnostics.keyFingerprint(ctx.typeSafeApiKey),
                        "docs" to "1",
                    ),
                )
            }
            buildJsonObject {
                put("type", "browse")
                put("url", url)
                put("rendered_by", renderedBy)
                if (relevance != null) put("relevance", relevance)
                put("markdown", clipped)
                put("truncated", markdown.length > clipped.length)
            }.toString()
        } catch (e: Exception) {
            buildJsonObject {
                put("type", "browse")
                put("url", url)
                put("error", "browse_error")
                put("message", e.message ?: "")
            }.toString()
        }
    }

    private suspend fun executeWebSearch(arguments: String, ctx: GenerationContext): String {
        val argsStr = arguments.ifBlank { "{}" }
        val args = Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(argsStr)
        val query = (args["query"] as? JsonPrimitive)?.content
            ?: return buildJsonObject { put("type", "web_search"); put("error", "no_query") }.toString()
        val numResults = ((args["num_results"] as? JsonPrimitive)?.content?.toIntOrNull() ?: ctx.webSearchNumResults).coerceIn(1, 10)
        val provider = normalizeWebSearchProvider(ctx.webSearchProvider)

        return try {
            // DuckDuckGo is a scraper, not an API — handle it separately.
            if (provider == "duckduckgo") {
                return duckDuckGoSearch(query, numResults)
            }

            // Monid TinyFish (free live search) and Fusion (DDG + TinyFish).
            if (provider == "tinyfish" || provider == "fusion") {
                return tinyFishSearch(query, numResults, ctx, fuseWithDuckDuckGo = provider == "fusion")
            }

            val apiKey = ctx.webSearchApiKeys[provider].orEmpty()
            if (provider != "searxng" && apiKey.isBlank()) {
                return buildJsonObject {
                    put("type", "web_search")
                    put("query", query)
                    put("error", "no_api_key")
                    put("provider", webSearchProviderDisplayName(provider))
                }.toString()
            }
            val body = when (provider) {
                "kagi" -> HttpClient.post(
                    "https://kagi.com/api/v1/search",
                    kagiSearchRequestBody(query, numResults),
                    mapOf(
                        "Accept" to "application/json",
                        "Authorization" to "Bearer $apiKey",
                    ),
                    callTimeoutMillis = Constants.NETWORK_TOOL_TIMEOUT_MS,
                )
                "serper" -> HttpClient.post(
                    "https://google.serper.dev/search",
                    Json.encodeToString(buildJsonObject { put("q", query); put("num", numResults) }),
                    mapOf("X-API-KEY" to apiKey),
                    callTimeoutMillis = Constants.NETWORK_TOOL_TIMEOUT_MS,
                )
                "tavily" -> HttpClient.post(
                    "https://api.tavily.com/search",
                    Json.encodeToString(buildJsonObject {
                        put("api_key", apiKey)
                        put("query", query)
                        put("max_results", numResults)
                        put("search_depth", "advanced")
                        put("include_answer", true)
                    }),
                    emptyMap(),
                    callTimeoutMillis = Constants.NETWORK_TOOL_TIMEOUT_MS,
                )
                "searxng" -> {
                    // Don't pin engines=google,brave: many public/self-hosted SearXNG instances
                    // disable those engines (rate-limited/require config), and pinning them yields
                    // an empty result set. Letting the instance use its own default-enabled engines
                    // matches how other SearXNG clients behave. Send a browser-like User-Agent so
                    // bot-filtering instances don't 403 us (same reason web_fetch sets one).
                    HttpClient.fetchModels(
                        searxngSearchUrl(ctx.webSearchBaseUrl, query),
                        mapOf("User-Agent" to Constants.WEB_FETCH_USER_AGENT),
                        callTimeoutMillis = Constants.NETWORK_TOOL_TIMEOUT_MS,
                    )
                }
                else -> HttpClient.fetchModels(
                    "https://api.search.brave.com/res/v1/web/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&count=$numResults",
                    mapOf("Accept" to "application/json", "X-Subscription-Token" to apiKey),
                    callTimeoutMillis = Constants.NETWORK_TOOL_TIMEOUT_MS,
                )
            } ?: return buildJsonObject { put("type", "web_search"); put("query", query); put("error", "no_response") }.toString()

            if (provider == "kagi") {
                return normalizeKagiSearchResponse(body, query, numResults)
            }

            val json: Map<String, kotlinx.serialization.json.JsonElement> = Json.decodeFromString(body)

            if (provider == "tavily") {
                val resultsArray = json["results"]?.jsonArray
                    ?: return buildJsonObject { put("type", "web_search"); put("query", query); put("error", "no_results") }.toString()
                if (resultsArray.isEmpty())
                    return buildJsonObject { put("type", "web_search"); put("query", query); put("error", "no_results") }.toString()
                val answer = (json["answer"] as? JsonPrimitive)?.content
                val rawResults = buildJsonArray {
                    for (element in resultsArray) {
                        val obj = element.jsonObject
                        add(buildJsonObject {
                            put("title", (obj["title"] as? JsonPrimitive)?.content ?: "")
                            put("url", (obj["url"] as? JsonPrimitive)?.content ?: "")
                            put("content", (obj["content"] as? JsonPrimitive)?.content ?: "")
                            val score = (obj["score"] as? JsonPrimitive)?.content?.toFloatOrNull()
                            if (score != null) put("score", score)
                        })
                    }
                }
                return buildJsonObject {
                    put("type", "web_search")
                    put("query", query)
                    if (!answer.isNullOrBlank()) put("answer", answer)
                    put("results", rawResults)
                }.toString()
            }

            val resultsArray = when {
                json.containsKey("organic") -> json["organic"]?.jsonArray
                json.containsKey("web") -> {
                    val web = json["web"]?.jsonObject
                    web?.get("results")?.jsonArray
                }
                json.containsKey("results") -> json["results"]?.jsonArray
                else -> null
            } ?: return buildJsonObject { put("type", "web_search"); put("query", query); put("error", "no_results") }.toString()

            if (resultsArray.isEmpty())
                return buildJsonObject { put("type", "web_search"); put("query", query); put("error", "no_results") }.toString()

            val rawResults = buildJsonArray {
                for (element in resultsArray) {
                    val obj = element.jsonObject
                    add(buildJsonObject {
                        put("title", (obj["title"] as? JsonPrimitive)?.content ?: "")
                        put("url", (obj["link"] as? JsonPrimitive)?.content ?: (obj["url"] as? JsonPrimitive)?.content ?: "")
                        put("description", (obj["snippet"] as? JsonPrimitive)?.content ?: (obj["content"] as? JsonPrimitive)?.content ?: (obj["description"] as? JsonPrimitive)?.content ?: "")
                    })
                }
            }
            buildJsonObject {
                put("type", "web_search")
                put("query", query)
                put("results", rawResults)
            }.toString()
        } catch (e: Exception) {
            buildJsonObject {
                put("type", "web_search")
                put("query", query)
                put("error", "search_error")
                put("message", e.message ?: "")
            }.toString()
        }
    }

    private fun duckDuckGoSearch(query: String, numResults: Int): String {
        val scraper = DuckDuckGoScraper(webClient)
        return when (val r = scraper.search(query, numResults)) {
            is DuckDuckGoScraper.SearchResponse.Success -> {
                hitsToJson(
                    query,
                    r.results.map {
                        MonidClient.WebHit(it.title, it.url, it.snippet, "duckduckgo")
                    },
                )
            }
            is DuckDuckGoScraper.SearchResponse.Error -> {
                buildJsonObject {
                    put("type", "web_search")
                    put("query", query)
                    put("error", r.type.name.lowercase())
                    put("message", r.message)
                }.toString()
            }
        }
    }

    /** DuckDuckGo hits for fusion; null when the scraper fails (fusion tolerates it). */
    private fun duckDuckGoHitsOrNull(query: String, numResults: Int): List<MonidClient.WebHit>? {
        return try {
            val scraper = DuckDuckGoScraper(webClient)
            when (val r = scraper.search(query, numResults)) {
                is DuckDuckGoScraper.SearchResponse.Success ->
                    r.results.map { MonidClient.WebHit(it.title, it.url, it.snippet, "duckduckgo") }
                is DuckDuckGoScraper.SearchResponse.Error -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun tinyFishSearch(
        query: String,
        numResults: Int,
        ctx: GenerationContext,
        fuseWithDuckDuckGo: Boolean,
    ): String {
        val apiKey = ctx.webSearchApiKeys["tinyfish"].orEmpty()
        if (apiKey.isBlank()) {
            // Fusion without a key degrades to plain DuckDuckGo instead of erroring.
            if (fuseWithDuckDuckGo) return duckDuckGoSearch(query, numResults)
            return buildJsonObject {
                put("type", "web_search")
                put("query", query)
                put("error", "no_api_key")
                put("provider", webSearchProviderDisplayName("tinyfish"))
            }.toString()
        }
        return try {
            val hits = if (!fuseWithDuckDuckGo) {
                MonidClient.search(apiKey, query, numResults)
            } else {
                // Fan out in parallel; TinyFish-first interleave with URL dedupe.
                coroutineScope {
                    val tiny = async {
                        runCatching { MonidClient.search(apiKey, query, numResults) }
                            .getOrNull().orEmpty()
                    }
                    val ddg = async { duckDuckGoHitsOrNull(query, numResults).orEmpty() }
                    val fused = MonidClient.fuseDedupe(tiny.await(), ddg.await(), numResults)
                    // Jev re-rank (one batched Noul call, fail-open): sort by answer
                    // probability, never drop — a Jev miss must not lose results.
                    if (ctx.jevEnabled && JevDecisions.isConfigured(ctx.typeSafeApiKey)) {
                        rerankWithJev(query, fused, ctx) ?: fused
                    } else {
                        fused
                    }
                }
            }
            if (hits.isEmpty()) {
                return buildJsonObject {
                    put("type", "web_search")
                    put("query", query)
                    put("error", "no_results")
                }.toString()
            }
            hitsToJson(query, hits)
        } catch (e: MonidClient.MonidAuthException) {
            buildJsonObject {
                put("type", "web_search")
                put("query", query)
                put("error", "invalid_api_key")
                put("provider", webSearchProviderDisplayName("tinyfish"))
            }.toString()
        } catch (e: Exception) {
            buildJsonObject {
                put("type", "web_search")
                put("query", query)
                put("error", "search_error")
                put("message", e.message ?: "")
            }.toString()
        }
    }

    /**
     * Jev re-rank of fused hits: one batched Noul call ("does this answer the
     * query?"), stable sort by probability. Returns null on any Jev failure so
     * the caller keeps fusion order. Never drops hits.
     */
    private suspend fun rerankWithJev(
        query: String,
        hits: List<MonidClient.WebHit>,
        ctx: GenerationContext,
    ): List<MonidClient.WebHit>? {
        if (hits.size < 2) return hits
        val jevStartNanos = System.nanoTime()
        fun emitJevRerank(outcome: String) {
            StructuredDiagnostics.emit(
                category = StructuredDiagnosticCategory.LLM,
                name = "jev_rerank",
                outcome = outcome,
                durationMs = (System.nanoTime() - jevStartNanos) / 1_000_000L,
                detail = mapOf(
                    "decision_provider" to ctx.decisionProvider,
                    "model" to ctx.jevModel,
                    "key_fingerprint" to StructuredDiagnostics.keyFingerprint(ctx.typeSafeApiKey),
                    "docs" to hits.size.toString(),
                ),
            )
        }
        return try {
            val scores = JevDecisions.relevanceScores(
                apiKey = ctx.typeSafeApiKey,
                baseUrl = ctx.typeSafeBaseUrl,
                model = ctx.jevModel,
                query = query,
                documents = hits.map { "${it.title}\n${it.snippet}" },
                timeoutMs = ctx.decisionTimeoutMs,
                maxStateChars = ctx.decisionMaxStateChars,
            ) ?: return null.also { emitJevRerank("no_signal") }
            if (scores.size != hits.size) return null.also { emitJevRerank("size_mismatch") }
            emitJevRerank("ok")
            hits.mapIndexed { index, hit -> hit to scores[index] }
                .sortedByDescending { (_, score) -> score }
                .map { (hit, _) -> hit }
        } catch (_: Exception) {
            emitJevRerank("error")
            null
        }
    }

    private fun hitsToJson(query: String, hits: List<MonidClient.WebHit>): String {        val rawResults = buildJsonArray {
            hits.forEach { result ->
                add(buildJsonObject {
                    put("title", result.title)
                    put("url", result.url)
                    put("description", result.snippet)
                    put("source", result.source)
                })
            }
        }
        return buildJsonObject {
            put("type", "web_search")
            put("query", query)
            put("results", rawResults)
        }.toString()
    }

    private suspend fun executeWebFetch(arguments: String, ctx: GenerationContext): String {
        val argsStr = arguments.ifBlank { "{}" }
        val args = Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(argsStr)
        val url = (args["url"] as? JsonPrimitive)?.content
            ?: return buildJsonObject { put("type", "web_fetch"); put("error", "no_url") }.toString()
        val maxChars = (try {
            (args["maxChars"] as? JsonPrimitive)?.content?.toIntOrNull()
        } catch (_: Exception) { null } ?: 8000).coerceIn(1, 100_000)

        return try {
            // TinyFish browser-rendered fetch first (handles JS pages the scraper
            // cannot); any failure falls through to the plain scraper below.
            val fetchProvider = normalizeWebSearchProvider(ctx.webSearchProvider)
            if (fetchProvider == "tinyfish" || fetchProvider == "fusion") {
                val monidKey = ctx.webSearchApiKeys["tinyfish"].orEmpty()
                if (monidKey.isNotBlank()) {
                    try {
                        val fetched = MonidClient.fetch(monidKey, listOf(url))
                        val text = fetched.values.firstOrNull()
                        if (!text.isNullOrBlank()) {
                            val clipped = text.take(maxChars)
                            return buildJsonObject {
                                put("type", "web_fetch")
                                put("url", url)
                                put("text", clipped)
                                put("truncated", text.length > clipped.length)
                                put("totalChars", text.length)
                            }.toString()
                        }
                    } catch (_: Exception) {
                        // Fall through to the scraper.
                    }
                }
            }
            val fetchResponse = HttpClient.fetchModelsResponse(url, mapOf(
                "User-Agent" to Constants.WEB_FETCH_USER_AGENT,
                "Accept" to "text/html,application/xhtml+xml,*/*"
            ), callTimeoutMillis = Constants.NETWORK_TOOL_TIMEOUT_MS)
            if (!fetchResponse.isSuccessful) {
                return buildJsonObject {
                    put("type", "web_fetch")
                    put("url", url)
                    put("error", "http_error")
                    put("http_status", fetchResponse.code)
                    put("message", "HTTP ${fetchResponse.code} ${fetchResponse.body.take(200)}")
                }.toString()
            }
            val html = fetchResponse.body
            if (html.isBlank()) {
                return buildJsonObject { put("type", "web_fetch"); put("url", url); put("error", "no_response") }.toString()
            }
            val fullText = htmlToReadableText(html)
            val text = fullText.take(maxChars)
            buildJsonObject {
                put("type", "web_fetch")
                put("url", url)
                put("text", text)
                put("truncated", fullText.length > text.length)
                put("totalChars", fullText.length)
            }.toString()
        } catch (e: Exception) {
            buildJsonObject {
                put("type", "web_fetch")
                put("url", url)
                put("error", "fetch_error")
                put("message", e.message ?: "")
            }.toString()
        }
    }

    /**
     * Extracts readable text from an HTML page.
     *
     * Strips non-content blocks (comments, script/style/noscript/svg/head), then lets
     * [HtmlCompat] decode entities and flatten the remaining markup while keeping block
     * elements as line breaks. Extraction runs over the whole (capped) HTML and the caller
     * truncates the resulting *text* — so article content past the page's boilerplate is no
     * longer cut off, and entities (—, ’, accents, numeric refs) are decoded correctly
     * instead of being dropped to spaces.
     */
    private fun htmlToReadableText(rawHtml: String): String {
        val stripped = rawHtml
            .take(Constants.MAX_WEB_FETCH_HTML_LENGTH)
            .replace(Regex("<!--[\\s\\S]*?-->"), " ")
            .replace(
                Regex("<(script|style|noscript|svg|head)\\b[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE),
                " "
            )
            // Drop common page chrome so navigation/menus/footers don't eat the text budget.
            .replace(
                Regex("<(nav|header|footer|aside)\\b[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE),
                " "
            )
        val text = HtmlCompat.fromHtml(stripped, HtmlCompat.FROM_HTML_MODE_COMPACT).toString()
        return text
            .replace(Regex("[ \\t\\x0B\\u000C\\r]+"), " ") // collapse intra-line whitespace
            .replace(Regex(" *\\n *"), "\n")               // trim around line breaks
            .replace(Regex("\\n{3,}"), "\n\n")             // collapse blank-line runs
            .trim()
    }
}
