package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.social.FxEmbedClient
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Social read tools — all 6 networks (X/Twitter, Bluesky, TikTok, Instagram,
 * Threads, Mastodon/ActivityPub) through the user's OWN FxEmbed worker
 * (Settings → Social). Read-only: the worker cannot post and this provider
 * never issues anything but GET.
 *
 * Hard limits every caller MUST respect (also baked into the tool
 * descriptions so the model sees them):
 * - TikTok: numeric video IDs only; strict rate limits (429 surfaces as 404
 *   flaps). NEVER retry-loop TikTok calls — one attempt, then report.
 * - Instagram: logged-out HTTP 500s are NORMAL (mirror production); 501 =
 *   the user's worker lacks the IG account pool. Report gracefully, no retry.
 * - 404 envelope = "upstream empty" (deleted/private post). Not a bug, not
 *   retried. 8 X routes need a credential pool the user adds to their OWN
 *   worker — surface honestly, never fake.
 *
 * Tools are only defined when social is enabled AND a worker URL is set; the
 * app ships no default worker URL.
 */
class SocialToolProvider : ToolProvider {

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.socialEnabled || ctx.socialWorkerBaseUrl.isBlank()) return emptyList()
        return listOf(
            ToolDefinition(function = ToolFunction(
                name = "social_resolve",
                description = "Resolve ANY social post or profile URL (X/Twitter, Bluesky, TikTok, " +
                    "Instagram, Threads, any Mastodon/ActivityPub instance) to readable Markdown " +
                    "via the user's FxEmbed worker. HARD LIMITS: TikTok URLs must carry a NUMERIC " +
                    "video ID (find it in the share URL, e.g. /video/7123…); TikTok is strictly " +
                    "rate-limited — make ONE attempt and report failure, NEVER retry-loop. " +
                    "Instagram logged-out HTTP 500s are NORMAL (not a bug). A 404 envelope means " +
                    "\"upstream has nothing\" (deleted/private post) — not a bug, do not retry. " +
                    "Read-only: nothing is ever posted.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "url" to ToolProperty(
                            "string",
                            "Full https:// URL of the post or profile to read.",
                        ),
                    ),
                    required = listOf("url"),
                ),
            )),
            ToolDefinition(function = ToolFunction(
                name = "social_search",
                description = "Search a social network via the user's FxEmbed worker. Supported " +
                    "networks: x (X/Twitter — relay-served: all X operators like from:, " +
                    "filter:, lang:, since:, min_faves: pass through; feed=latest|top|media), " +
                    "bluesky, threads, mastodon. mastodon REQUIRES the instance domain " +
                    "(e.g. mastodon.social). HARD LIMITS: TikTok has NO search and " +
                    "Instagram has no documented search route — both refuse honestly; do not " +
                    "work around them. X people search stays removed (no relay). Some X routes " +
                    "need a credential pool on the user's own worker (reported as 501) — " +
                    "surface honestly, never fake results. Never retry-loop: one attempt per call.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "network" to ToolProperty(
                            "string",
                            "One of: x, bluesky, threads, mastodon.",
                        ),
                        "query" to ToolProperty("string", "The search query."),
                        "domain" to ToolProperty(
                            "string",
                            "Mastodon instance domain (required when network=mastodon).",
                        ),
                        "feed" to ToolProperty(
                            "string",
                            "X only: latest|top|media (default latest).",
                        ),
                        "lang" to ToolProperty(
                            "string",
                            "Translate results inline, e.g. es.",
                        ),
                    ),
                    required = listOf("network", "query"),
                ),
            )),
            ToolDefinition(function = ToolFunction(
                name = "social_timeline",
                description = "Read a social account's recent posts (tweets/statuses) via the user's " +
                    "FxEmbed worker — no login needed. Use this when the user asks what an " +
                    "account posts about: pass network=x and the handle, then summarize the " +
                    "timeline. Supported networks: x (X/Twitter), bluesky, threads, mastodon " +
                    "(REQUIRES the instance domain, e.g. mastodon.social). Params: count " +
                    "(1-100, default 20), cursor (opaque page token from the previous call's " +
                    "Next: line — pass it back for the next page), with_replies (include " +
                    "replies), since (poll for posts newer than this), lang (translate inline, " +
                    "e.g. es). HARD LIMITS: some X routes need a credential pool on the user's " +
                    "own worker and surface as 401/403/501 — report that honestly, never fake " +
                    "results. Never retry-loop: one attempt per call.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "network" to ToolProperty(
                            "string",
                            "One of: x, bluesky, threads, mastodon.",
                        ),
                        "handle" to ToolProperty(
                            "string",
                            "The account handle without @, e.g. ice7887.",
                        ),
                        "domain" to ToolProperty(
                            "string",
                            "Mastodon instance domain (required when network=mastodon).",
                        ),
                        "count" to ToolProperty(
                            "string",
                            "Posts per page, 1-100 (default 20).",
                        ),
                        "cursor" to ToolProperty(
                            "string",
                            "Opaque page token from the previous call's Next: line.",
                        ),
                        "with_replies" to ToolProperty(
                            "string",
                            "\"true\" to include the account's replies.",
                        ),
                        "since" to ToolProperty(
                            "string",
                            "Only posts newer than this (polling).",
                        ),
                        "lang" to ToolProperty(
                            "string",
                            "Translate post text inline, e.g. es.",
                        ),
                        "group_threads" to ToolProperty(
                            "string",
                            "\"true\" to cluster X timeline items into threads.",
                        ),
                    ),
                    required = listOf("network", "handle"),
                ),
            )),
            ToolDefinition(function = ToolFunction(
                name = "social_thread",
                description = "Unroll an X thread: returns the author's connected posts in order " +
                    "as Markdown — for reading long threads or archiving them. Pass the numeric " +
                    "tweet id (the digits in x.com/.../status/123).",
                parameters = ToolParameters(
                    properties = mapOf(
                        "tweet_id" to ToolProperty(
                            "string",
                            "Numeric tweet id, e.g. 20.",
                        ),
                        "lang" to ToolProperty(
                            "string",
                            "Translate post text inline, e.g. es.",
                        ),
                    ),
                    required = listOf("tweet_id"),
                ),
            )),
            ToolDefinition(function = ToolFunction(
                name = "social_quotes",
                description = "Quote posts of an X tweet — who quoted it and what they said. " +
                    "Pass the numeric tweet id. Paginate with cursor from the previous call's " +
                    "Next: line. Returns 404/upstream_empty when nothing quotes it (valid).",
                parameters = ToolParameters(
                    properties = mapOf(
                        "tweet_id" to ToolProperty(
                            "string",
                            "Numeric tweet id, e.g. 20.",
                        ),
                        "count" to ToolProperty(
                            "string",
                            "Posts per page, 1-100 (default 20).",
                        ),
                        "cursor" to ToolProperty(
                            "string",
                            "Opaque page token from the previous call's Next: line.",
                        ),
                        "lang" to ToolProperty(
                            "string",
                            "Translate post text inline, e.g. es.",
                        ),
                    ),
                    required = listOf("tweet_id"),
                ),
            )),
            ToolDefinition(function = ToolFunction(
                name = "social_conversation",
                description = "X conversation: the post plus its ancestors and paginated direct " +
                    "replies — for reading both sides of a debate. ranking_mode=likes|recency. " +
                    "HARD LIMIT: X shut the guest conversation path — this endpoint is DEAD " +
                    "upstream (404 on every worker, no credential pool can fix it). Prefer " +
                    "social_thread (author chain) + social_quotes instead.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "tweet_id" to ToolProperty(
                            "string",
                            "Numeric tweet id, e.g. 20.",
                        ),
                        "ranking_mode" to ToolProperty(
                            "string",
                            "likes or recency (default recency).",
                        ),
                        "cursor" to ToolProperty(
                            "string",
                            "Opaque page token from the previous call's Next: line.",
                        ),
                    ),
                    required = listOf("tweet_id"),
                ),
            )),
            ToolDefinition(function = ToolFunction(
                name = "social_profile_search",
                description = "Search WITHIN one X account's posts (relay-served). Use when the " +
                    "user asks e.g. 'what has this account said about AI'. feed=latest|top|media. " +
                    "Paginate with cursor.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "handle" to ToolProperty(
                            "string",
                            "The account handle without @, e.g. ice7887.",
                        ),
                        "query" to ToolProperty("string", "The search query."),
                        "feed" to ToolProperty(
                            "string",
                            "latest|top|media (default latest).",
                        ),
                        "count" to ToolProperty(
                            "string",
                            "Posts per page, 1-100 (default 20).",
                        ),
                        "cursor" to ToolProperty(
                            "string",
                            "Opaque page token from the previous call's Next: line.",
                        ),
                        "lang" to ToolProperty(
                            "string",
                            "Translate post text inline, e.g. es.",
                        ),
                    ),
                    required = listOf("handle", "query"),
                ),
            )),
            ToolDefinition(function = ToolFunction(
                name = "social_profile_media",
                description = "An X account's media tab: posts with photos/video. Served via the " +
                    "worker's relay fallback when the guest flow misses — works on most " +
                    "deployments; if it 500s the worker needs a credential pool.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "handle" to ToolProperty(
                            "string",
                            "The account handle without @, e.g. ice7887.",
                        ),
                        "count" to ToolProperty(
                            "string",
                            "Posts per page, 1-100 (default 20).",
                        ),
                        "cursor" to ToolProperty(
                            "string",
                            "Opaque page token from the previous call's Next: line.",
                        ),
                    ),
                    required = listOf("handle"),
                ),
            )),
            ToolDefinition(function = ToolFunction(
                name = "social_rss",
                description = "A social account's RSS/Atom feed — the cheapest way to monitor an " +
                    "account for new posts or poll for changes. feed: feed.xml (X RSS, " +
                    "default), feed.atom.xml (X Atom), media.xml (X media-only RSS), " +
                    "media.atom.xml (X media-only Atom). network=bluesky gives the Bluesky " +
                    "profile RSS. Returns raw feed XML.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "handle" to ToolProperty(
                            "string",
                            "The account handle without @, e.g. ice7887.",
                        ),
                        "feed" to ToolProperty(
                            "string",
                            "feed.xml, feed.atom.xml, media.xml, or media.atom.xml (default feed.xml).",
                        ),
                        "network" to ToolProperty(
                            "string",
                            "x (default) or bluesky.",
                        ),
                    ),
                    required = listOf("handle"),
                ),
            )),
            ToolDefinition(function = ToolFunction(
                name = "social_oembed",
                description = "oEmbed for any X post URL — returns title/author/html for rich link " +
                    "previews in chat. Works without a credential pool. Pass the full " +
                    "https://x.com/.../status/... URL.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "url" to ToolProperty(
                            "string",
                            "Full https:// URL of the X post.",
                        ),
                    ),
                    required = listOf("url"),
                ),
            )),
        )
    }

    override suspend fun execute(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): String = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val result = when (name) {
            "social_resolve" -> executeResolve(arguments, ctx)
            "social_search" -> executeSearch(arguments, ctx)
            "social_timeline" -> executeTimeline(arguments, ctx)
            "social_thread" -> executeThread(arguments, ctx)
            "social_quotes" -> executeQuotes(arguments, ctx)
            "social_conversation" -> executeConversation(arguments, ctx)
            "social_profile_search" -> executeProfileSearch(arguments, ctx)
            "social_profile_media" -> executeProfileMedia(arguments, ctx)
            "social_rss" -> executeRss(arguments, ctx)
            "social_oembed" -> executeOembed(arguments, ctx)
            else -> errorJson(name, "unknown_tool", "Unknown tool: $name")
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        // Tool diagnostic event: name + duration + outcome class. No URLs, no
        // bodies, no secrets (social has none).
        DebugLog.d(TAG, "tool=$name outcome=${toolOutcome(result)} ms=$elapsedMs")
        result
    }

    override fun handles(name: String): Boolean = name in setOf(
        "social_resolve", "social_search", "social_timeline", "social_thread",
        "social_quotes", "social_conversation", "social_profile_search",
        "social_profile_media", "social_rss", "social_oembed",
    )

    override fun presentationMetadata(name: String): ToolPresentationMetadata? =
        when (name) {
            "social_resolve" -> ToolPresentationMetadata(displayName = "Social post")
            "social_search" -> ToolPresentationMetadata(displayName = "Social search")
            "social_timeline" -> ToolPresentationMetadata(displayName = "Social timeline")
            "social_thread" -> ToolPresentationMetadata(displayName = "Social thread")
            "social_quotes" -> ToolPresentationMetadata(displayName = "Social quotes")
            "social_conversation" -> ToolPresentationMetadata(displayName = "Social conversation")
            "social_profile_search" -> ToolPresentationMetadata(displayName = "Social profile search")
            "social_profile_media" -> ToolPresentationMetadata(displayName = "Social media")
            "social_rss" -> ToolPresentationMetadata(displayName = "Social RSS feed")
            "social_oembed" -> ToolPresentationMetadata(displayName = "Social link preview")
            else -> null
        }

    private fun clientFor(ctx: GenerationContext): FxEmbedClient? {
        val base = ctx.socialWorkerBaseUrl.trim().trimEnd('/')
        if (base.isBlank()) return null
        return FxEmbedClient(
            baseUrl = base,
            userAgent = FxEmbedClient.userAgentFor(ctx.appVersion),
            useBareRealm = ctx.socialUseBareRealm,
        )
    }

    private suspend fun executeResolve(arguments: String, ctx: GenerationContext): String {
        val args = parseArgs(arguments) ?: return errorJson("social_resolve", "bad_arguments", "Arguments must be a JSON object.")
        val url = (args["url"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val client = clientFor(ctx)
            ?: return errorJson("social_resolve", "not_configured", "Social is not configured: set your worker URL in Settings → Social and validate it.")
        return when (val r = client.resolvePost(url)) {
            is FxEmbedClient.ResolveResult.Success -> buildJsonObject {
                put("type", "social_resolve")
                put("url", url)
                put("markdown", r.content)
                put("truncated", r.truncated)
            }.toString()
            is FxEmbedClient.ResolveResult.UpstreamEmpty -> buildJsonObject {
                put("type", "social_resolve")
                put("url", url)
                put("error", "upstream_empty")
                put("message", r.message + " Do not retry — this is a valid outcome, not a bug.")
            }.toString()
            is FxEmbedClient.ResolveResult.Failure -> failureJson("social_resolve", url, r)
        }
    }

    private suspend fun executeSearch(arguments: String, ctx: GenerationContext): String {
        val args = parseArgs(arguments) ?: return errorJson("social_search", "bad_arguments", "Arguments must be a JSON object.")
        val network = (args["network"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val query = (args["query"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val domain = (args["domain"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val feed = (args["feed"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val lang = (args["lang"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val client = clientFor(ctx)
            ?: return errorJson("social_search", "not_configured", "Social is not configured: set your worker URL in Settings → Social and validate it.")
        return when (val r = client.search(network, query, domain, feed, lang)) {
            is FxEmbedClient.ResolveResult.Success -> buildJsonObject {
                put("type", "social_search")
                put("network", network)
                put("query", query)
                put("results", r.content)
                put("truncated", r.truncated)
            }.toString()
            is FxEmbedClient.ResolveResult.UpstreamEmpty -> buildJsonObject {
                put("type", "social_search")
                put("network", network)
                put("query", query)
                put("error", "upstream_empty")
                put("message", r.message + " Do not retry — this is a valid outcome, not a bug.")
            }.toString()
            is FxEmbedClient.ResolveResult.Failure -> buildJsonObject {
                put("type", "social_search")
                put("network", network)
                put("query", query)
                put("error", r.error)
                r.httpStatus?.let { put("http_status", it) }
                r.workerCode?.let { put("worker_code", it) }
                r.hint?.let { put("hint", it) }
            }.toString()
        }
    }

    private fun failureJson(type: String, url: String, r: FxEmbedClient.ResolveResult.Failure): String =
        buildJsonObject {
            put("type", type)
            put("url", url)
            put("error", r.error)
            r.httpStatus?.let { put("http_status", it) }
            r.workerCode?.let { put("worker_code", it) }
            r.hint?.let { put("hint", it) }
        }.toString()

    private suspend fun executeTimeline(arguments: String, ctx: GenerationContext): String {
        val args = parseArgs(arguments) ?: return errorJson("social_timeline", "bad_arguments", "Arguments must be a JSON object.")
        val network = (args["network"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val handle = (args["handle"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val domain = (args["domain"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val count = (args["count"] as? JsonPrimitive)?.content?.trim()?.toIntOrNull() ?: 20
        val cursor = (args["cursor"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val withReplies = (args["with_replies"] as? JsonPrimitive)?.content?.trim()
            .equals("true", ignoreCase = true)
        val since = (args["since"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val lang = (args["lang"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val groupThreads = (args["group_threads"] as? JsonPrimitive)?.content?.trim()
            .equals("true", ignoreCase = true)
        val client = clientFor(ctx)
            ?: return errorJson("social_timeline", "not_configured", "Social is not configured: set your worker URL in Settings → Social and validate it.")
        return when (val r = client.timeline(network, handle, domain, count, cursor, withReplies, since, lang, groupThreads)) {
            is FxEmbedClient.ResolveResult.Success -> buildJsonObject {
                put("type", "social_timeline")
                put("network", network)
                put("handle", handle)
                put("timeline", r.content)
                put("truncated", r.truncated)
            }.toString()
            is FxEmbedClient.ResolveResult.UpstreamEmpty -> buildJsonObject {
                put("type", "social_timeline")
                put("network", network)
                put("handle", handle)
                put("error", "upstream_empty")
                put("message", r.message + " Do not retry — this is a valid outcome, not a bug.")
            }.toString()
            is FxEmbedClient.ResolveResult.Failure -> buildJsonObject {
                put("type", "social_timeline")
                put("network", network)
                put("handle", handle)
                put("error", r.error)
                r.httpStatus?.let { put("http_status", it) }
                r.workerCode?.let { put("worker_code", it) }
                r.hint?.let { put("hint", it) }
            }.toString()
        }
    }

    private suspend fun executeThread(arguments: String, ctx: GenerationContext): String {
        val args = parseArgs(arguments) ?: return errorJson("social_thread", "bad_arguments", "Arguments must be a JSON object.")
        val tweetId = (args["tweet_id"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val lang = (args["lang"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val client = clientFor(ctx)
            ?: return errorJson("social_thread", "not_configured", "Social is not configured: set your worker URL in Settings → Social and validate it.")
        return when (val r = client.thread(tweetId, lang)) {
            is FxEmbedClient.ResolveResult.Success -> buildJsonObject {
                put("type", "social_thread")
                put("tweet_id", tweetId)
                put("thread", r.content)
                put("truncated", r.truncated)
            }.toString()
            is FxEmbedClient.ResolveResult.UpstreamEmpty -> buildJsonObject {
                put("type", "social_thread")
                put("tweet_id", tweetId)
                put("error", "upstream_empty")
                put("message", r.message + " Do not retry — this is a valid outcome, not a bug.")
            }.toString()
            is FxEmbedClient.ResolveResult.Failure -> buildJsonObject {
                put("type", "social_thread")
                put("tweet_id", tweetId)
                put("error", r.error)
                r.httpStatus?.let { put("http_status", it) }
                r.workerCode?.let { put("worker_code", it) }
                r.hint?.let { put("hint", it) }
            }.toString()
        }
    }

    private suspend fun executeQuotes(arguments: String, ctx: GenerationContext): String {
        val args = parseArgs(arguments) ?: return errorJson("social_quotes", "bad_arguments", "Arguments must be a JSON object.")
        val tweetId = (args["tweet_id"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val count = (args["count"] as? JsonPrimitive)?.content?.trim()?.toIntOrNull() ?: 20
        val cursor = (args["cursor"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val lang = (args["lang"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val client = clientFor(ctx)
            ?: return errorJson("social_quotes", "not_configured", "Social is not configured: set your worker URL in Settings → Social and validate it.")
        return when (val r = client.quotes(tweetId, count, cursor, lang)) {
            is FxEmbedClient.ResolveResult.Success -> buildJsonObject {
                put("type", "social_quotes")
                put("tweet_id", tweetId)
                put("quotes", r.content)
                put("truncated", r.truncated)
            }.toString()
            is FxEmbedClient.ResolveResult.UpstreamEmpty -> buildJsonObject {
                put("type", "social_quotes")
                put("tweet_id", tweetId)
                put("error", "upstream_empty")
                put("message", r.message + " Do not retry — this is a valid outcome, not a bug.")
            }.toString()
            is FxEmbedClient.ResolveResult.Failure -> buildJsonObject {
                put("type", "social_quotes")
                put("tweet_id", tweetId)
                put("error", r.error)
                r.httpStatus?.let { put("http_status", it) }
                r.workerCode?.let { put("worker_code", it) }
                r.hint?.let { put("hint", it) }
            }.toString()
        }
    }

    private suspend fun executeConversation(arguments: String, ctx: GenerationContext): String {
        val args = parseArgs(arguments) ?: return errorJson("social_conversation", "bad_arguments", "Arguments must be a JSON object.")
        val tweetId = (args["tweet_id"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val rankingMode = (args["ranking_mode"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val cursor = (args["cursor"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val client = clientFor(ctx)
            ?: return errorJson("social_conversation", "not_configured", "Social is not configured: set your worker URL in Settings → Social and validate it.")
        return when (val r = client.conversation(tweetId, rankingMode, cursor)) {
            is FxEmbedClient.ResolveResult.Success -> buildJsonObject {
                put("type", "social_conversation")
                put("tweet_id", tweetId)
                put("conversation", r.content)
                put("truncated", r.truncated)
            }.toString()
            is FxEmbedClient.ResolveResult.UpstreamEmpty -> buildJsonObject {
                put("type", "social_conversation")
                put("tweet_id", tweetId)
                put("error", "upstream_empty")
                put("message", r.message + " Do not retry — this is a valid outcome, not a bug.")
            }.toString()
            is FxEmbedClient.ResolveResult.Failure -> buildJsonObject {
                put("type", "social_conversation")
                put("tweet_id", tweetId)
                put("error", r.error)
                r.httpStatus?.let { put("http_status", it) }
                r.workerCode?.let { put("worker_code", it) }
                r.hint?.let { put("hint", it) }
            }.toString()
        }
    }

    private suspend fun executeProfileSearch(arguments: String, ctx: GenerationContext): String {
        val args = parseArgs(arguments) ?: return errorJson("social_profile_search", "bad_arguments", "Arguments must be a JSON object.")
        val handle = (args["handle"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val query = (args["query"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val feed = (args["feed"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val count = (args["count"] as? JsonPrimitive)?.content?.trim()?.toIntOrNull() ?: 20
        val cursor = (args["cursor"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val lang = (args["lang"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val client = clientFor(ctx)
            ?: return errorJson("social_profile_search", "not_configured", "Social is not configured: set your worker URL in Settings → Social and validate it.")
        return when (val r = client.profileSearch(handle, query, feed, count, cursor, lang)) {
            is FxEmbedClient.ResolveResult.Success -> buildJsonObject {
                put("type", "social_profile_search")
                put("handle", handle)
                put("query", query)
                put("results", r.content)
                put("truncated", r.truncated)
            }.toString()
            is FxEmbedClient.ResolveResult.UpstreamEmpty -> buildJsonObject {
                put("type", "social_profile_search")
                put("handle", handle)
                put("query", query)
                put("error", "upstream_empty")
                put("message", r.message + " Do not retry — this is a valid outcome, not a bug.")
            }.toString()
            is FxEmbedClient.ResolveResult.Failure -> buildJsonObject {
                put("type", "social_profile_search")
                put("handle", handle)
                put("query", query)
                put("error", r.error)
                r.httpStatus?.let { put("http_status", it) }
                r.workerCode?.let { put("worker_code", it) }
                r.hint?.let { put("hint", it) }
            }.toString()
        }
    }

    private suspend fun executeProfileMedia(arguments: String, ctx: GenerationContext): String {
        val args = parseArgs(arguments) ?: return errorJson("social_profile_media", "bad_arguments", "Arguments must be a JSON object.")
        val handle = (args["handle"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val count = (args["count"] as? JsonPrimitive)?.content?.trim()?.toIntOrNull() ?: 20
        val cursor = (args["cursor"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val client = clientFor(ctx)
            ?: return errorJson("social_profile_media", "not_configured", "Social is not configured: set your worker URL in Settings → Social and validate it.")
        return when (val r = client.profileMedia(handle, count, cursor)) {
            is FxEmbedClient.ResolveResult.Success -> buildJsonObject {
                put("type", "social_profile_media")
                put("handle", handle)
                put("media", r.content)
                put("truncated", r.truncated)
            }.toString()
            is FxEmbedClient.ResolveResult.UpstreamEmpty -> buildJsonObject {
                put("type", "social_profile_media")
                put("handle", handle)
                put("error", "upstream_empty")
                put("message", r.message + " Do not retry — this is a valid outcome, not a bug.")
            }.toString()
            is FxEmbedClient.ResolveResult.Failure -> buildJsonObject {
                put("type", "social_profile_media")
                put("handle", handle)
                put("error", r.error)
                r.httpStatus?.let { put("http_status", it) }
                r.workerCode?.let { put("worker_code", it) }
                r.hint?.let { put("hint", it) }
            }.toString()
        }
    }

    private suspend fun executeRss(arguments: String, ctx: GenerationContext): String {
        val args = parseArgs(arguments) ?: return errorJson("social_rss", "bad_arguments", "Arguments must be a JSON object.")
        val handle = (args["handle"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val feed = (args["feed"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val network = (args["network"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val client = clientFor(ctx)
            ?: return errorJson("social_rss", "not_configured", "Social is not configured: set your worker URL in Settings → Social and validate it.")
        return when (val r = client.rssFeed(handle, feed.ifBlank { "feed.xml" }, network.ifBlank { "x" })) {
            is FxEmbedClient.ResolveResult.Success -> buildJsonObject {
                put("type", "social_rss")
                put("handle", handle)
                put("feed", r.content)
                put("truncated", r.truncated)
            }.toString()
            is FxEmbedClient.ResolveResult.UpstreamEmpty -> buildJsonObject {
                put("type", "social_rss")
                put("handle", handle)
                put("error", "upstream_empty")
                put("message", r.message + " Do not retry — this is a valid outcome, not a bug.")
            }.toString()
            is FxEmbedClient.ResolveResult.Failure -> buildJsonObject {
                put("type", "social_rss")
                put("handle", handle)
                put("error", r.error)
                r.httpStatus?.let { put("http_status", it) }
                r.workerCode?.let { put("worker_code", it) }
                r.hint?.let { put("hint", it) }
            }.toString()
        }
    }

    private suspend fun executeOembed(arguments: String, ctx: GenerationContext): String {
        val args = parseArgs(arguments) ?: return errorJson("social_oembed", "bad_arguments", "Arguments must be a JSON object.")
        val url = (args["url"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val client = clientFor(ctx)
            ?: return errorJson("social_oembed", "not_configured", "Social is not configured: set your worker URL in Settings → Social and validate it.")
        return when (val r = client.oembed(url)) {
            is FxEmbedClient.ResolveResult.Success -> buildJsonObject {
                put("type", "social_oembed")
                put("url", url)
                put("oembed", r.content)
                put("truncated", r.truncated)
            }.toString()
            is FxEmbedClient.ResolveResult.UpstreamEmpty -> buildJsonObject {
                put("type", "social_oembed")
                put("url", url)
                put("error", "upstream_empty")
                put("message", r.message + " Do not retry — this is a valid outcome, not a bug.")
            }.toString()
            is FxEmbedClient.ResolveResult.Failure -> failureJson("social_oembed", url, r)
        }
    }

    private fun parseArgs(arguments: String): Map<String, JsonElement>? {
        return try {
            Json.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" })
        } catch (_: Exception) {
            null
        }
    }

    private fun errorJson(type: String, error: String, message: String): String =
        buildJsonObject {
            put("type", type)
            put("error", error)
            put("message", message)
        }.toString()

    /** Outcome class for the diagnostic event — never args or bodies. */
    private fun toolOutcome(resultJson: String): String {
        return try {
            val obj = Json.parseToJsonElement(resultJson) as? kotlinx.serialization.json.JsonObject
            (obj?.get("error") as? JsonPrimitive)?.content ?: "success"
        } catch (_: Exception) {
            "unknown"
        }
    }

    companion object {
        const val TAG = "SocialTool"
    }
}
