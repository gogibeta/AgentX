package com.newoether.agora.api.openai

import com.newoether.agora.api.OpenAiResponseEnvelope
import com.newoether.agora.api.OpenAiResponseOutputItem
import com.newoether.agora.api.StreamEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class ResponsesToolCallCompletionTest : ResponsesEventFixture() {
    @Test
    fun responsesReasoningOutputItemIsRetainedUntilCompletedCallRelease() {
        val router = responsesRouter()
        val reasoning = OpenAiResponseOutputItem(
            id = "rs_1",
            type = "reasoning",
            summary = JsonArray(listOf(JsonPrimitive("summary"))),
            encryptedContent = "opaque-reasoning-state",
        )
        assertTrue(
            router.route(
                responseEvent(
                    "response.output_item.added",
                    1,
                    outputIndex = 0,
                    item = reasoning,
                ),
            ).isEmpty(),
        )
        assertTrue(
            router.route(
                responseEvent(
                    "response.output_item.done",
                    2,
                    outputIndex = 0,
                    item = OpenAiResponseOutputItem(
                        id = " ",
                        type = " ",
                        summary = JsonArray(emptyList()),
                        encryptedContent = " ",
                    ),
                ),
            ).isEmpty(),
        )
        val callItem = responseCallItem("item_1", "call_1", "lookup", "{}")
        router.route(
            responseEvent(
                "response.output_item.added",
                3,
                outputIndex = 1,
                item = callItem,
            ),
        )
        router.route(
            responseEvent(
                "response.output_item.done",
                4,
                outputIndex = 1,
                item = callItem,
            ),
        )

        val call = router.route(
            responseEvent(
                "response.completed",
                5,
                response = OpenAiResponseEnvelope(status = "completed"),
            ),
        ).filterIsInstance<StreamEvent.ToolCallRequest>().single()

        assertEquals(
            listOf(responseItem(reasoning), responseItem(callItem)),
            call.responseOutputItems,
        )
        assertTrue(router.route(responseEvent("response.created", 6)).single() is StreamEvent.Error)
    }

    @Test
    fun responsesFinalArgumentsSnapshotOverridesEquivalentStreamFormatting() {
        val router = responsesRouter()
        val item = responseCallItem("item_1", "call_1", "lookup")
        router.route(responseEvent("response.output_item.added", 1, outputIndex = 0, item = item))
        router.route(
            responseEvent(
                "response.function_call_arguments.delta",
                2,
                delta = """{"q":"x"}""",
                itemId = "item_1",
                outputIndex = 0,
            ),
        )
        val finalArguments = """{ "q": "x" }"""
        val done = router.route(
            responseEvent(
                "response.function_call_arguments.done",
                3,
                arguments = finalArguments,
                name = "lookup",
                itemId = "item_1",
                outputIndex = 0,
            ),
        )
        val itemDone = router.route(
            responseEvent(
                "response.output_item.done",
                4,
                outputIndex = 0,
                item = item.copy(arguments = finalArguments),
            ),
        )
        val call = router.route(
            responseEvent(
                "response.completed",
                5,
                response = OpenAiResponseEnvelope(status = "completed"),
            ),
        ).filterIsInstance<StreamEvent.ToolCallRequest>().single()

        assertTrue((done + itemDone).none { it is StreamEvent.Error })
        assertEquals(finalArguments, call.arguments)
    }

    @Test
    fun responsesBlankCompletionMetadataCannotEraseEffectiveCallState() {
        val router = responsesRouter()
        val item = responseCallItem("item_1", "call_1", "lookup")
        router.route(responseEvent("response.output_item.added", 1, outputIndex = 0, item = item))
        router.route(
            responseEvent(
                "response.function_call_arguments.delta",
                2,
                delta = "{}",
                itemId = "item_1",
                outputIndex = 0,
            )
        )
        val argumentsDone = router.route(
            responseEvent(
                "response.function_call_arguments.done",
                3,
                arguments = " ",
                name = " ",
                itemId = " ",
                outputIndex = 0,
            )
        )
        val itemDone = router.route(
            responseEvent(
                "response.output_item.done",
                4,
                outputIndex = 0,
                item = responseCallItem(" ", " ", " ", " ").copy(type = " "),
            )
        )
        val call = router.route(
            responseEvent(
                "response.completed",
                5,
                response = OpenAiResponseEnvelope(status = "completed"),
            )
        ).filterIsInstance<StreamEvent.ToolCallRequest>().single()

        assertTrue((argumentsDone + itemDone).none { it is StreamEvent.Error })
        assertEquals("call_1", call.id)
        assertEquals("lookup", call.name)
        assertEquals("{}", call.arguments)
        val retainedItem = call.responseOutputItems.orEmpty().single()
        assertEquals("item_1", retainedItem["id"]?.jsonPrimitive?.content)
        assertEquals("call_1", retainedItem["call_id"]?.jsonPrimitive?.content)
        assertEquals("lookup", retainedItem["name"]?.jsonPrimitive?.content)
        assertEquals("{}", retainedItem["arguments"]?.jsonPrimitive?.content)
    }

    @Test
    fun responsesFunctionCallIsExecutableOnlyAfterCompleted() {
        val router = responsesRouter()
        val item = responseCallItem("item_1", "call_1", "lookup")

        val added = router.route(
            responseEvent("response.output_item.added", 1, outputIndex = 0, item = item)
        )
        val delta = router.route(
            responseEvent(
                "response.function_call_arguments.delta",
                2,
                delta = """{"q":"x"}""",
                itemId = "item_1",
                outputIndex = 0,
            )
        )
        val argumentsDone = router.route(
            responseEvent(
                "response.function_call_arguments.done",
                3,
                arguments = """{"q":"x"}""",
                name = "lookup",
                itemId = "item_1",
                outputIndex = 0,
            )
        )
        val itemDone = router.route(
            responseEvent(
                "response.output_item.done",
                4,
                outputIndex = 0,
                item = item.copy(arguments = """{"q":"x"}"""),
            )
        )

        assertTrue((added + delta + argumentsDone + itemDone).none { it is StreamEvent.ToolCallRequest })
        val call = router.route(
            responseEvent(
                "response.completed",
                5,
                response = OpenAiResponseEnvelope(status = "completed"),
            )
        ).filterIsInstance<StreamEvent.ToolCallRequest>().single()
        assertEquals("call_1", call.id)
        assertEquals("lookup", call.name)
        assertEquals("""{"q":"x"}""", call.arguments)
    }

    @Test
    fun responsesMultipleCallsReleaseAtomicallyOnCompleted() {
        val router = responsesRouter()
        repeat(2) { index ->
            val item = responseCallItem("item_$index", "call_$index", "tool_$index", "{}")
            assertTrue(
                router.route(
                    responseEvent(
                        "response.output_item.added",
                        index * 2 + 1,
                        outputIndex = index,
                        item = item,
                    )
                ).none { it is StreamEvent.ToolCallRequest || it is StreamEvent.ToolCallsRequest }
            )
            assertTrue(
                router.route(
                    responseEvent(
                        "response.output_item.done",
                        index * 2 + 2,
                        outputIndex = index,
                        item = item,
                    )
                ).isEmpty()
            )
        }

        val batch = router.route(
            responseEvent(
                "response.completed",
                5,
                response = OpenAiResponseEnvelope(status = "completed"),
            )
        ).filterIsInstance<StreamEvent.ToolCallsRequest>().single()
        assertEquals(listOf("call_0", "call_1"), batch.calls.map { it.id })
        assertEquals(2, batch.calls.first().responseOutputItems.size)
        assertTrue(batch.calls.drop(1).all { it.responseOutputItems.isEmpty() })
    }

    @Test
    fun responsesOutOfOrderCompletionStillReplaysOutputIndexOrder() {
        val router = responsesRouter()
        val first = responseCallItem("item_0", "call_0", "tool_0", "{}")
        val second = responseCallItem("item_1", "call_1", "tool_1", "{}")
        router.route(
            responseEvent(
                "response.output_item.added",
                1,
                outputIndex = 1,
                item = second,
            ),
        )
        router.route(
            responseEvent(
                "response.output_item.added",
                2,
                outputIndex = 0,
                item = first,
            ),
        )
        router.route(
            responseEvent(
                "response.output_item.done",
                3,
                outputIndex = 1,
                item = second,
            ),
        )
        router.route(
            responseEvent(
                "response.output_item.done",
                4,
                outputIndex = 0,
                item = first,
            ),
        )

        val batch = router.route(
            responseEvent(
                "response.completed",
                5,
                response = OpenAiResponseEnvelope(status = "completed"),
            ),
        ).filterIsInstance<StreamEvent.ToolCallsRequest>().single()

        assertEquals(listOf("call_0", "call_1"), batch.calls.map { it.id })
        assertEquals(
            listOf("item_0", "item_1"),
            batch.calls.first().responseOutputItems.map {
                it["id"]?.jsonPrimitive?.content
            },
        )
    }

    @Test
    fun retainedMessageItemRecoversContentFromTextDeltas() {
        // Some relays omit `content` from `response.output_item.done` for message items;
        // the text arrived via deltas only. The retained item must recover it so the next
        // request does not fail validation with "content is empty".
        val router = responsesRouter()
        val message = OpenAiResponseOutputItem(id = "msg_1", type = "message")
        router.route(
            responseEvent(
                "response.output_item.added",
                1,
                outputIndex = 0,
                item = message,
            ),
        )
        router.route(
            responseEvent(
                "response.output_text.delta",
                2,
                delta = "hello ",
                outputIndex = 0,
                itemId = "msg_1",
                contentIndex = 0,
            ),
        )
        router.route(
            responseEvent(
                "response.output_text.delta",
                3,
                delta = "world",
                outputIndex = 0,
                itemId = "msg_1",
                contentIndex = 0,
            ),
        )
        router.route(
            responseEvent(
                "response.output_item.done",
                4,
                outputIndex = 0,
                item = message,
            ),
        )
        val callItem = responseCallItem("fc_1", "call_1", "lookup", "{}")
        router.route(
            responseEvent(
                "response.output_item.added",
                5,
                outputIndex = 1,
                item = callItem,
            ),
        )
        router.route(
            responseEvent(
                "response.output_item.done",
                6,
                outputIndex = 1,
                item = callItem,
            ),
        )

        val call = router.route(
            responseEvent(
                "response.completed",
                7,
                response = OpenAiResponseEnvelope(status = "completed"),
            ),
        ).filterIsInstance<StreamEvent.ToolCallRequest>().single()

        val items = call.responseOutputItems
        assertEquals(
            listOf("message", "function_call"),
            items.map { it["type"]?.jsonPrimitive?.content },
        )
        val content = items[0]["content"]?.jsonArray
        assertEquals(1, content?.size)
        assertEquals(
            "hello world",
            content?.single()?.jsonObject?.get("text")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun interleavedDuplicateMessageItemsAreDedupedAndOrdered() {
        // The Nara router emits the assistant message item once per text block, interleaved
        // between function calls: [message, function_call, message, function_call]. The
        // retained items must collapse to [message, function_call, function_call] or the next
        // request fails with "interrupts pending tool results".
        val router = responsesRouter()
        val message = OpenAiResponseOutputItem(id = "msg_1", type = "message")
        router.route(
            responseEvent(
                "response.output_item.added",
                1,
                outputIndex = 0,
                item = message,
            ),
        )
        router.route(
            responseEvent(
                "response.output_text.delta",
                2,
                delta = "hi",
                outputIndex = 0,
                itemId = "msg_1",
                contentIndex = 0,
            ),
        )
        router.route(
            responseEvent(
                "response.output_item.done",
                3,
                outputIndex = 0,
                item = message,
            ),
        )
        val first = responseCallItem("fc_1", "call_1", "lookup", "{}")
        router.route(
            responseEvent(
                "response.output_item.added",
                4,
                outputIndex = 1,
                item = first,
            ),
        )
        router.route(
            responseEvent(
                "response.output_item.done",
                5,
                outputIndex = 1,
                item = first,
            ),
        )
        router.route(
            responseEvent(
                "response.output_item.added",
                6,
                outputIndex = 2,
                item = message,
            ),
        )
        router.route(
            responseEvent(
                "response.output_item.done",
                7,
                outputIndex = 2,
                item = message,
            ),
        )
        val second = responseCallItem("fc_2", "call_2", "lookup", "{}")
        router.route(
            responseEvent(
                "response.output_item.added",
                8,
                outputIndex = 3,
                item = second,
            ),
        )
        router.route(
            responseEvent(
                "response.output_item.done",
                9,
                outputIndex = 3,
                item = second,
            ),
        )

        val batch = router.route(
            responseEvent(
                "response.completed",
                10,
                response = OpenAiResponseEnvelope(status = "completed"),
            ),
        ).filterIsInstance<StreamEvent.ToolCallsRequest>().single()

        assertEquals(listOf("call_1", "call_2"), batch.calls.map { it.id })
        val items = batch.calls.first().responseOutputItems
        assertEquals(
            listOf("message", "function_call", "function_call"),
            items.map { it["type"]?.jsonPrimitive?.content },
        )
        assertEquals(
            listOf("msg_1", "fc_1", "fc_2"),
            items.map { it["id"]?.jsonPrimitive?.content },
        )
    }
}
