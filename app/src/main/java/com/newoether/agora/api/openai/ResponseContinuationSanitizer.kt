package com.newoether.agora.api.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Repairs raw Responses API output items retained for stateless tool continuations.
 *
 * Two provider quirks broke the next request's fail-closed validation:
 *
 * - Interleaved duplicates: some Responses-compatible relays (e.g. the Nara router) emit the
 *   assistant `message` item once per text block, so the retained items can read
 *   `[message, function_call, message, function_call]`. Replaying that order puts a `message`
 *   after pending `function_call` items, and the request validator rejects it with
 *   "input[N] interrupts pending tool results" on every later request, permanently bricking
 *   the chat for that provider.
 * - Content-less messages: other relays omit `content` from `response.output_item.done`
 *   (the text arrived via deltas only). Replaying the bare item trips the validator's
 *   "input[N] content is empty".
 * - Reasoning items: Responses-compatible relays in the wild (proven live against
 *   discovery-api.intern-ai.org.cn for Atria-Dawn-Preview and kimi-k2.6, 2026-10-25)
 *   validate `input` against message/function_call/function_call_output only and reject
 *   a replayed `reasoning` item with HTTP 400 `upstream_request_rejected` (176 validation
 *   errors, starting at the reasoning item: `reasoning_text` is not `input_text`/`input_image`
 *   and `reasoning` is not `message`). The first turn works (messages only); every turn
 *   after a tool call then fails forever, and switching models in the same chat does not
 *   help because the poisoned reasoning item is part of the shared history. Reasoning is
 *   regenerable model-internal thinking, not user content, so it is safe to omit: the
 *   model re-thinks on the next turn, while function calls and their outputs (the items
 *   a continuation actually needs) are kept verbatim.
 *
 * The repair is conservative: drop `reasoning` items outright, drop exact duplicates by
 * (type, id), drop `message` items that carry no text (their text already lives in the
 * assistant history row when it is non-blank), and stable-partition `function_call` items
 * after every other item so provider item order can never again interrupt pending calls.
 * Hosted tool calls are preserved untouched. Because this sanitizer runs both at capture
 * (ToolCallTextParser.completeResponse) and at replay (toResponsesInput), chats persisted
 * before this fix heal on the next message without deleting history.
 */
internal fun sanitizeResponseContinuationItems(items: List<JsonObject>): List<JsonObject> {
    val seen = mutableSetOf<String>()
    val head = mutableListOf<JsonObject>()
    val calls = mutableListOf<JsonObject>()
    for (item in items) {
        val type = (item["type"] as? JsonPrimitive)?.contentOrNull
        // Reasoning items are never replayed: relays reject them (see KDoc above) and the
        // model regenerates its thinking on the next turn. Drop before dedupe so a reasoning
        // item can never consume an id slot or poison a continuation.
        if (type == "reasoning") continue
        // Drop blank messages before dedupe so a content-less duplicate never consumes the id slot.
        if (type == "message" && item.responseMessageText() == null) continue
        val id = (item["id"] as? JsonPrimitive)?.contentOrNull
        if (!id.isNullOrBlank() && !seen.add("$type\u0000$id")) continue
        (if (type == "function_call") calls else head).add(item)
    }
    return head + calls
}

/** Non-blank text of a retained `message` item's content parts, or null when there is none. */
internal fun JsonObject.responseMessageText(): String? {
    val parts = this["content"] as? JsonArray ?: return null
    return parts
        .mapNotNull { (it as? JsonObject)?.get("text") as? JsonPrimitive }
        .joinToString("") { it.contentOrNull.orEmpty() }
        .takeIf { it.isNotBlank() }
}

/** Returns a copy of this `message` item with `content` set to [text]. */
internal fun JsonObject.withResponseMessageText(text: String): JsonObject =
    JsonObject(
        toMap() + (
            "content" to JsonArray(
                listOf(
                    buildJsonObject {
                        putJsonArray("annotations") {}
                        put("type", "output_text")
                        put("text", text)
                    },
                ),
            )
        ),
    )
