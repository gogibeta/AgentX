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
 *
 * The repair is conservative: drop exact duplicates by (type, id), drop `message` items that
 * carry no text (their text already lives in the assistant history row when it is non-blank),
 * and stable-partition `function_call` items after every other item so provider item order can
 * never again interrupt pending calls. Reasoning items (encrypted_content) and hosted tool
 * calls are preserved untouched.
 */
internal fun sanitizeResponseContinuationItems(items: List<JsonObject>): List<JsonObject> {
    val seen = mutableSetOf<String>()
    val head = mutableListOf<JsonObject>()
    val calls = mutableListOf<JsonObject>()
    for (item in items) {
        val type = (item["type"] as? JsonPrimitive)?.contentOrNull
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
