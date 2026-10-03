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
                    "networks: x (X/Twitter), bluesky, threads, mastodon. mastodon REQUIRES the " +
                    "instance domain (e.g. mastodon.social). HARD LIMITS: TikTok has NO search and " +
                    "Instagram has no documented search route — both refuse honestly; do not " +
                    "work around them. Some X routes need a credential pool on the user's own " +
                    "worker (reported as 501) — surface honestly, never fake results. Never " +
                    "retry-loop: one attempt per call.",
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
                    "(REQUIRES the instance domain, e.g. mastodon.social). HARD LIMITS: some " +
                    "X routes need a credential pool on the user's own worker and surface as " +
                    "401/403/501 — report that honestly, never fake results. Never retry-loop: " +
                    "one attempt per call.",
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
                    ),
                    required = listOf("network", "handle"),
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
            else -> errorJson(name, "unknown_tool", "Unknown tool: $name")
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        // Tool diagnostic event: name + duration + outcome class. No URLs, no
        // bodies, no secrets (social has none).
        DebugLog.d(TAG, "tool=$name outcome=${toolOutcome(result)} ms=$elapsedMs")
        result
    }

    override fun handles(name: String): Boolean = name in setOf("social_resolve", "social_search", "social_timeline")

    override fun presentationMetadata(name: String): ToolPresentationMetadata? =
        when (name) {
            "social_resolve" -> ToolPresentationMetadata(displayName = "Social post")
            "social_search" -> ToolPresentationMetadata(displayName = "Social search")
            "social_timeline" -> ToolPresentationMetadata(displayName = "Social timeline")
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
        val client = clientFor(ctx)
            ?: return errorJson("social_search", "not_configured", "Social is not configured: set your worker URL in Settings → Social and validate it.")
        return when (val r = client.search(network, query, domain)) {
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
        val client = clientFor(ctx)
            ?: return errorJson("social_timeline", "not_configured", "Social is not configured: set your worker URL in Settings → Social and validate it.")
        return when (val r = client.timeline(network, handle, domain)) {
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
