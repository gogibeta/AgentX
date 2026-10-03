package com.newoether.agora.social

import com.newoether.agora.api.HttpClient
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * HTTP client for the user's own FxEmbed worker (docs: docs.fxembed.com).
 *
 * Calling rules (v2.3.0 spec §2.3), encoded in code:
 * - `User-Agent: AgentX/<version>` is MANDATORY on every request; without it
 *   the worker answers 401. Supplied via [userAgent].
 * - Prefer `/ai/2/...` Markdown twins (token-lean, full text never truncated);
 *   JSON only where the plan documents no Markdown twin (Threads/Mastodon
 *   search live under `/atmosphere/2/...`).
 * - Custom domains drop the `/ai` realm prefix: when [useBareRealm] is true
 *   (recorded by the Validate hit), requests use `{base}/2/...` and
 *   `{base}/version` instead of `{base}/ai/2/...` / `{base}/ai/version`.
 * - Errors arrive as `{"code":N,"message":"…"}` envelopes with a matching
 *   HTTP status. A 404 envelope is a valid "upstream has nothing"
 *   (deleted/private post) — NOT a bug, never retried.
 * - Read-only: only GET is ever issued. This client cannot post.
 *
 * Hard limits (§2.4), encoded in code:
 * - TikTok: numeric IDs only. A TikTok URL whose ID segment is not numeric is
 *   refused locally (no request made).
 * - TikTok rate limits surface as 429/404 flaps: NEVER retry-loop. Every call
 *   here is a single attempt; failures are surfaced, not retried.
 * - Instagram: logged-out HTTP 500s are NORMAL (mirror production); missing
 *   IG account pools surface as 501. Both are reported gracefully.
 */
class FxEmbedClient(
    private val baseUrl: String,
    private val userAgent: String,
    private val useBareRealm: Boolean = false,
) {

    /** Outcome of one worker request — never throws for worker-side errors. */
    sealed interface ResolveResult {
        /** Content payload (Markdown from /ai, JSON where documented). */
        data class Success(val content: String, val truncated: Boolean) : ResolveResult
        /** 404 envelope: upstream has nothing. Not a bug — do not retry. */
        data class UpstreamEmpty(val message: String) : ResolveResult
        /** Any other failure (network, HTTP error, bad arguments). */
        data class Failure(
            val error: String,
            val httpStatus: Int? = null,
            val workerCode: Int? = null,
            val hint: String? = null,
        ) : ResolveResult
    }

    /** Result of the Validate hit: version string + which prefix form answered. */
    data class ValidateResult(
        val version: String,
        val bareRealm: Boolean,
    )

    /** Detailed validation outcome, preserving the failure reason for the UI. */
    sealed interface ValidateDetailed {
        data class Ok(val version: String, val bareRealm: Boolean) : ValidateDetailed
        data class Failed(val reason: String) : ValidateDetailed
    }

    // ── Universal resolver ─────────────────────────────────────────────

    /**
     * ONE endpoint resolving any post or profile URL on all 6 networks
     * (X/Twitter, Bluesky, TikTok, Instagram, Threads, Mastodon/ActivityPub).
     * Returns the worker's Markdown (`/ai/2/post?url=`).
     */
    suspend fun resolvePost(url: String): ResolveResult = withContext(Dispatchers.IO) {
        val gateError = gateTargetUrl(url) ?: gateTikTokNumericId(url)
        if (gateError != null) return@withContext gateError
        get(aiPath("/2/post?url=") + encode(url), "post")
    }

    // ── Per-network search (documented paths only) ───────────────────

    /**
     * Network search, per the worker's llms.txt (2026-10-03 refresh).
     * - `x`: RELAY-SERVED (§C8): RSS discovery + per-post hydration, all X
     *   operators pass through (from:, filter:, lang:, since:, min_faves:).
     *   404 envelope = relay found nothing (valid answer, not a bug).
     *   `feed` = latest|top|media. People search stays removed (no relay).
     * - `bluesky`: works (`/ai/2/bsky/search?q=`; the worker retries the
     *   public AppView when datacenter IPs are edge-403d).
     * - `threads`: proxy-only — 501 without an account pool on the worker.
     * - `mastodon`: people search only (`search/users?q=` returns hits;
     *   status search is always empty upstream for anonymous callers).
     *   Requires [mastodonDomain].
     * TikTok and Instagram have no search — refused honestly.
     */
    suspend fun search(
        network: String,
        query: String,
        mastodonDomain: String = "",
        feed: String = "",
    ): ResolveResult = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) {
            return@withContext ResolveResult.Failure("no_query", hint = "Pass a non-empty search query.")
        }
        val path = when (network.lowercase()) {
            "x", "twitter" -> {
                val f = feed.lowercase().takeIf { it in setOf("latest", "top", "media") } ?: "latest"
                aiPath("/2/search?q=") + encode(q) + "&feed=" + f
            }
            "bluesky", "bsky" -> aiPath("/2/bsky/search?q=") + encode(q)
            // Documented under /atmosphere/2 in §2.4 (no /ai twin): JSON result.
            "threads" -> realmPath("atmosphere", "/2/threads/search?q=") + encode(q)
            "mastodon" -> {
                val domain = mastodonDomain.trim().lowercase()
                if (domain.isEmpty() || domain.contains('/')) {
                    return@withContext ResolveResult.Failure(
                        "no_domain",
                        hint = "Mastodon search needs the instance domain, e.g. mastodon.social.",
                    )
                }
                // NOTE: /search?q= (statuses) always returns results:[] for anonymous
                // callers — Mastodon only serves status search to authenticated users.
                // search/users?q= DOES return hits, so people search is the useful one.
                realmPath("atmosphere", "/2/mastodon/") + domain + "/search/users?q=" + encode(q)
            }
            "tiktok" -> return@withContext ResolveResult.Failure(
                "search_not_supported",
                hint = "TikTok has no search route — this is a hard worker limit, not a bug. Use resolvePost with a video URL (numeric IDs only).",
            )
            "instagram" -> return@withContext ResolveResult.Failure(
                "search_not_supported",
                hint = "Instagram has no documented search route. Use resolvePost with a post/reel URL.",
            )
            else -> return@withContext ResolveResult.Failure(
                "unknown_network",
                hint = "Searchable: x (relay), bluesky, threads (needs account pool), mastodon (people only).",
            )
        }
        get(path, "search:$network")
    }

    /**
     * Profile timeline: recent posts by a known handle, no login needed.
     *
     * Params (llms.txt §C6/A4-A6): `count` 1-100, `cursor` = opaque
     * `cursor.bottom` from the previous page's "Next:" line, `withReplies`
     * includes replies, `since` polls for new posts (204 = nothing new),
     * `lang` translates inline (ISO 639-1).
     *
     * HARD LIMITS: media/articles/followers/following tabs need a credential
     * pool on the user's own worker (500 here) — reported honestly.
     */
    suspend fun timeline(
        network: String,
        handle: String,
        mastodonDomain: String = "",
        count: Int = 20,
        cursor: String = "",
        withReplies: Boolean = false,
        since: String = "",
        lang: String = "",
    ): ResolveResult = withContext(Dispatchers.IO) {
        val h = handle.trim().trimStart('@')
        if (h.isEmpty()) {
            return@withContext ResolveResult.Failure(
                "no_handle",
                hint = "Pass the account handle, e.g. ice7887.",
            )
        }
        val params = buildString {
            append("?count=").append(count.coerceIn(1, 100))
            if (cursor.isNotBlank()) append("&cursor=").append(encode(cursor))
            if (withReplies) append("&with_replies=1")
            if (since.isNotBlank()) append("&since=").append(encode(since))
            if (lang.isNotBlank()) append("&lang=").append(encode(lang))
        }
        val path = when (network.lowercase()) {
            "x", "twitter" -> aiPath("/2/profile/") + encode(h) + "/statuses" + params
            "bluesky", "bsky" -> aiPath("/2/bsky/profile/") + encode(h) + "/statuses" + params
            "threads" -> realmPath("atmosphere", "/2/threads/profile/") + encode(h) + "/statuses" + params
            "mastodon" -> {
                val domain = mastodonDomain.trim().lowercase()
                if (domain.isEmpty() || domain.contains('/')) {
                    return@withContext ResolveResult.Failure(
                        "no_domain",
                        hint = "Mastodon timelines need the instance domain, e.g. mastodon.social.",
                    )
                }
                realmPath("atmosphere", "/2/mastodon/") + domain +
                    "/profile/" + encode(h) + "/statuses" + params
            }
            else -> return@withContext ResolveResult.Failure(
                "unknown_network",
                hint = "Timelines supported for: x, bluesky, threads, mastodon.",
            )
        }
        get(path, "timeline:$network")
    }

    /**
     * Unroll an X author's whole thread (§C2): the connected posts in order.
     * Numeric tweet id only (the digits in x.com/.../status/123).
     */
    suspend fun thread(tweetId: String, lang: String = ""): ResolveResult =
        withContext(Dispatchers.IO) {
            val id = tweetId.trim()
            if (id.isEmpty() || !id.all { it.isDigit() }) {
                return@withContext ResolveResult.Failure(
                    "bad_tweet_id",
                    hint = "Pass the numeric tweet id (digits from x.com/.../status/123).",
                )
            }
            val params = if (lang.isNotBlank()) "?lang=" + encode(lang) else ""
            get(aiPath("/2/thread/") + id + params, "thread")
        }

    /**
     * Quote posts of an X tweet (§C7). Works when upstream has data, else a
     * 404 envelope (valid). Paginate with the opaque `cursor`.
     */
    suspend fun quotes(
        tweetId: String,
        count: Int = 20,
        cursor: String = "",
        lang: String = "",
    ): ResolveResult = withContext(Dispatchers.IO) {
        val id = tweetId.trim()
        if (id.isEmpty() || !id.all { it.isDigit() }) {
            return@withContext ResolveResult.Failure(
                "bad_tweet_id",
                hint = "Pass the numeric tweet id (digits from x.com/.../status/123).",
            )
        }
        val params = buildString {
            append("?count=").append(count.coerceIn(1, 100))
            if (cursor.isNotBlank()) append("&cursor=").append(encode(cursor))
            if (lang.isNotBlank()) append("&lang=").append(encode(lang))
        }
        get(aiPath("/2/status/") + id + "/quotes" + params, "quotes")
    }

    /**
     * X conversation: post + ancestors + paginated direct replies (§C3).
     * NEEDS a credential pool on the worker (404 here, 200 on production) —
     * surfaced honestly via the normal error path.
     */
    suspend fun conversation(
        tweetId: String,
        rankingMode: String = "",
        cursor: String = "",
        lang: String = "",
    ): ResolveResult = withContext(Dispatchers.IO) {
        val id = tweetId.trim()
        if (id.isEmpty() || !id.all { it.isDigit() }) {
            return@withContext ResolveResult.Failure(
                "bad_tweet_id",
                hint = "Pass the numeric tweet id (digits from x.com/.../status/123).",
            )
        }
        val params = buildString {
            val mode = rankingMode.lowercase().takeIf { it in setOf("likes", "recency") }
            if (mode != null) append("?ranking_mode=").append(mode) else append("?")
            if (cursor.isNotBlank()) append("&cursor=").append(encode(cursor))
            if (lang.isNotBlank()) append("&lang=").append(encode(lang))
        }.toString().replace("?&", "?").trimEnd('?')
        get(aiPath("/2/conversation/") + id + params, "conversation")
    }

    /**
     * Per-user X search (§C8): search WITHIN one account's posts via the
     * relay. Same feed/count/cursor params as global search.
     */
    suspend fun profileSearch(
        handle: String,
        query: String,
        feed: String = "",
        count: Int = 20,
        cursor: String = "",
    ): ResolveResult = withContext(Dispatchers.IO) {
        val h = handle.trim().trimStart('@')
        val q = query.trim()
        if (h.isEmpty() || q.isEmpty()) {
            return@withContext ResolveResult.Failure(
                "bad_arguments",
                hint = "Pass both the handle (e.g. ice7887) and a non-empty query.",
            )
        }
        val f = feed.lowercase().takeIf { it in setOf("latest", "top", "media") } ?: "latest"
        val params = buildString {
            append("?q=").append(encode(q))
            append("&feed=").append(f)
            append("&count=").append(count.coerceIn(1, 100))
            if (cursor.isNotBlank()) append("&cursor=").append(encode(cursor))
        }
        get(aiPath("/2/profile/") + encode(h) + "/search" + params, "profile_search")
    }

    /**
     * X profile media tab (§C6): posts with photos/video. NEEDS a credential
     * pool on the worker (500 here) — surfaced honestly.
     */
    suspend fun profileMedia(
        handle: String,
        count: Int = 20,
        cursor: String = "",
    ): ResolveResult = withContext(Dispatchers.IO) {
        val h = handle.trim().trimStart('@')
        if (h.isEmpty()) {
            return@withContext ResolveResult.Failure(
                "no_handle",
                hint = "Pass the account handle, e.g. ice7887.",
            )
        }
        val params = buildString {
            append("?count=").append(count.coerceIn(1, 100))
            if (cursor.isNotBlank()) append("&cursor=").append(encode(cursor))
        }
        get(aiPath("/2/profile/") + encode(h) + "/media" + params, "profile_media")
    }

    /**
     * X profile RSS feed (§D2): the best polling primitive — any UA, poll
     * with If-None-Match / If-Modified-Since. Returns raw RSS XML.
     */
    suspend fun rssFeed(handle: String): ResolveResult = withContext(Dispatchers.IO) {
        val h = handle.trim().trimStart('@')
        if (h.isEmpty()) {
            return@withContext ResolveResult.Failure(
                "no_handle",
                hint = "Pass the account handle, e.g. ice7887.",
            )
        }
        // /twitter realm has no /ai twin; custom domains keep the path as-is.
        val path = (if (useBareRealm) "" else "/twitter") + "/" + encode(h) + "/feed.xml"
        get(path, "rss")
    }

    /**
     * Version / health-check hit used by the Validate button.
     * Tries `{base}/ai/version`, then `{base}/version` (custom domains).
     */
    suspend fun getVersion(): String? = withContext(Dispatchers.IO) {
        validate(baseUrl, userAgent)?.version
    }

    // ── Core GET ─────────────────────────────────────────────────────

    /**
     * Single GET attempt — there are deliberately NO retry loops anywhere in
     * this client (TikTok 429/404 flaps must not be retried; 404 envelopes and
     * IG logged-out 500s are normal and must not be retried either).
     */
    private suspend fun get(path: String, realm: String): ResolveResult {
        val url = baseUrl + path
        val started = System.nanoTime()
        return try {
            val response = HttpClient.fetchModelsResponse(
                url,
                mapOf("User-Agent" to userAgent, "Accept" to "text/markdown,application/json"),
                callTimeoutMillis = Constants.NETWORK_TOOL_TIMEOUT_MS,
            )
            val elapsedMs = (System.nanoTime() - started) / 1_000_000L
            val outcome = classify(response.code, response.body, url)
            DebugLog.d(
                TAG,
                "realm=$realm status=${response.code} outcome=${outcome::class.simpleName} ms=$elapsedMs",
            )
            outcome
        } catch (e: Exception) {
            val elapsedMs = (System.nanoTime() - started) / 1_000_000L
            DebugLog.w(TAG, "realm=$realm status=network_error ms=$elapsedMs kind=${e.javaClass.simpleName}")
            ResolveResult.Failure("network_error", hint = classifyNetworkError(e))
        }
    }

    /**
     * Map HTTP status + optional error envelope to the result model.
     * 404 envelope = "upstream empty" (NOT a bug, NOT retried).
     * 500 = upstream failure OR missing credential pool OR logged-out IG block
     * (normal, mirrors production) — reported gracefully, not retried.
     * 401 = missing/invalid User-Agent; 403 = edge challenge; 501 = route
     * needs an account pool the user never added to their worker.
     */
    private fun classify(status: Int, body: String, url: String): ResolveResult {
        val envelope = parseEnvelope(body)
        val workerCode = envelope?.first
        val workerMessage = envelope?.second.orEmpty()
        if (status == 404 || workerCode == 404) {
            return ResolveResult.UpstreamEmpty(
                workerMessage.ifBlank { "Upstream has nothing for this URL (deleted or private post). This is not a bug — do not retry." },
            )
        }
        if (status == 200) {
            val clipped = body.take(MAX_CONTENT_CHARS)
            return ResolveResult.Success(clipped, truncated = body.length > clipped.length)
        }
        val hint = when (status) {
            401 -> "Missing or invalid User-Agent — the client must send AgentX/<version>."
            403 -> "Edge challenge from the worker host. Check the worker deployment."
            500 -> "Upstream failure, missing credential pool, or logged-out Instagram block — all normal, mirrors production. Do not retry."
            501 -> "This route needs an account pool on the user's worker (wrangler secret put). Surface honestly; do not fake results."
            503 -> "Upstream (e.g. Bluesky) temporarily unavailable."
            else -> null
        }
        DebugLog.w(
            TAG,
            "status=$status workerCode=$workerCode host=${hostOf(url)} msg=${workerMessage.take(120)}",
        )
        return ResolveResult.Failure(
            error = if (status in 400..499) "http_client_error" else "http_server_error",
            httpStatus = status,
            workerCode = workerCode,
            hint = hint,
        )
    }

    // ── Guards ───────────────────────────────────────────────────────

    /** https only; never send credentials in a URL. */
    private fun gateTargetUrl(url: String): ResolveResult.Failure? {
        if (url.isBlank()) return ResolveResult.Failure("no_url")
        if (!SocialPreferenceStore.isValidWorkerUrl(url) && !isValidTargetUrl(url)) {
            return ResolveResult.Failure(
                "bad_url",
                hint = "Pass a full https:// post or profile URL.",
            )
        }
        return null
    }

    private fun isValidTargetUrl(url: String): Boolean {
        return try {
            val parsed = java.net.URI(url.trim())
            parsed.scheme == "https" && !parsed.host.isNullOrBlank() && parsed.userInfo == null
        } catch (_: Exception) {
            false
        }
    }

    /**
     * TikTok hard limit: numeric IDs only. Refuse non-numeric TikTok video IDs
     * locally instead of burning a rate-limited request.
     */
    private fun gateTikTokNumericId(url: String): ResolveResult.Failure? {
        val host = try { java.net.URI(url.trim()).host?.lowercase().orEmpty() } catch (_: Exception) { "" }
        if (!host.endsWith("tiktok.com")) return null
        val id = url.trim().trimEnd('/').substringAfterLast('/')
        val numeric = id.all { it.isDigit() } && id.isNotEmpty()
        if (!numeric) {
            return ResolveResult.Failure(
                "tiktok_id_not_numeric",
                hint = "TikTok only accepts numeric video IDs — this is a hard worker limit. Find the numeric ID from the share URL (e.g. /video/7123456789012345678). Do not retry with the same URL.",
            )
        }
        return null
    }

    private fun aiPath(suffix: String): String = (if (useBareRealm) "" else "/ai") + suffix

    private fun realmPath(realm: String, suffix: String): String =
        (if (useBareRealm) "" else "/$realm") + suffix

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun hostOf(url: String): String =
        try { java.net.URI(url).host.orEmpty() } catch (_: Exception) { "" }

    private fun classifyNetworkError(e: Exception): String {
        val msg = (e.message.orEmpty()).lowercase()
        return when {
            "timeout" in msg || "timed out" in msg -> "The worker did not answer in time. Check the worker URL; do not retry-loop."
            "unable to resolve" in msg || "unknownhost" in msg -> "DNS could not resolve the worker host. Check the URL."
            "connection refused" in msg -> "The worker refused the connection. Is the deployment live?"
            else -> "Network error reaching the worker. Check the URL and connectivity."
        }
    }

    /** `{"code":N,"message":"…"}` error envelope, or null when not present. */
    private fun parseEnvelope(body: String): Pair<Int, String>? {
        val trimmed = body.trim()
        if (!trimmed.startsWith("{")) return null
        return try {
            val obj = Json.parseToJsonElement(trimmed) as? JsonObject ?: return null
            val code = (obj["code"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
            val message = (obj["message"] as? JsonPrimitive)?.content.orEmpty()
            code to message
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val TAG = "FxEmbed"
        const val MAX_CONTENT_CHARS = 50_000

        /** Mandatory User-Agent form: `AgentX/<version>` (missing = HTTP 401). */
        fun userAgentFor(version: String): String = "AgentX/${version.ifBlank { "?" }}"

        /**
         * Validate hit for Settings → Social: GET `{base}/ai/version`, then
         * `{base}/version` (custom domains drop prefixes). Returns the version
         * plus which prefix form answered, or null when unreachable/invalid.
         * Single attempts only — no retries.
         */
        suspend fun validate(rawBaseUrl: String, userAgent: String): ValidateResult? =
            withContext(Dispatchers.IO) {
                when (val d = validateDetailed(rawBaseUrl, userAgent)) {
                    is ValidateDetailed.Ok -> ValidateResult(d.version, d.bareRealm)
                    is ValidateDetailed.Failed -> null
                }
            }

        /**
         * Validate with a human-readable failure reason. Tries `{base}/ai/version`,
         * then `{base}/version`. Reports the actual HTTP status when the worker
         * answers with an error (e.g. 401 = bad/missing User-Agent).
         */
        suspend fun validateDetailed(rawBaseUrl: String, userAgent: String): ValidateDetailed =
            withContext(Dispatchers.IO) {
                val base = rawBaseUrl.trim().trimEnd('/')
                if (!SocialPreferenceStore.isValidWorkerUrl(base))
                    return@withContext ValidateDetailed.Failed("The URL must be a full https:// address.")
                val headers = mapOf("User-Agent" to userAgent, "Accept" to "application/json")
                var lastStatus = -1
                for ((path, bare) in listOf("/ai/version" to false, "/version" to true)) {
                    try {
                        val response = HttpClient.fetchModelsResponse(
                            base + path, headers,
                            callTimeoutMillis = Constants.NETWORK_TOOL_TIMEOUT_MS,
                        )
                        lastStatus = response.code
                        if (response.code != 200) continue
                        // NOTE: some workers (e.g. the FxEmbed /ai realm) answer
                        // 200 with Markdown here, not {"version":"..."} JSON.
                        // A 200 with a non-empty body counts as reachable.
                        val version = parseVersion(response.body)
                            ?: response.body.trim().takeIf { it.isNotEmpty() }?.let { "unknown" }
                            ?: continue
                        DebugLog.d(TAG, "validate ok path=$path version=$version")
                        return@withContext ValidateDetailed.Ok(version, bare)
                    } catch (e: Exception) {
                        DebugLog.w(TAG, "validate path=$path network_error ${e.javaClass.simpleName}")
                        return@withContext ValidateDetailed.Failed(
                            "Network error reaching $base$path: ${e.javaClass.simpleName}. Check your connection and the worker URL.",
                        )
                    }
                }
                val reason = when (lastStatus) {
                    401 -> "Worker returned 401: it rejected the User-Agent ($userAgent). Update the worker to accept it."
                    403 -> "Worker returned 403: edge challenge from the worker host. Check the worker deployment."
                    404 -> "Worker returned 404 at both /ai/version and /version. The worker may be outdated — redeploy it."
                    else -> "No version answered at /ai/version or /version (HTTP $lastStatus). Check the URL and that the worker is deployed."
                }
                DebugLog.w(TAG, "validate failed host=${hostOfStatic(base)} status=$lastStatus")
                ValidateDetailed.Failed(reason)
            }

        private fun parseVersion(body: String): String? {
            return try {
                val obj = Json.parseToJsonElement(body.trim()) as? JsonObject ?: return null
                (obj["version"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
        }

        private fun hostOfStatic(url: String): String =
            try { java.net.URI(url).host.orEmpty() } catch (_: Exception) { "" }
    }
}
