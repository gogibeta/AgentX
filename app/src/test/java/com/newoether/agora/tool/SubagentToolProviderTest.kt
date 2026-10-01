package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.automation.ChildRequest
import com.newoether.agora.automation.ChildResult
import com.newoether.agora.viewmodel.GenerationContext
import com.newoether.agora.viewmodel.GenerationToolExecutor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentToolProviderTest {

    private fun provider(
        onChild: suspend (ChildRequest) -> ChildResult = { req ->
            ChildResult(modelId = req.modelId)
        },
        snapshot: () -> String = { "" },
    ) = SubagentToolProvider(runChild = onChild, memorySnapshot = snapshot)

    private fun buildCtx() = GenerationContext(agentMode = "build")

    @Test
    fun delegateTask_onlyInBuildMode_ignoresModelPreset() {
        val p = provider()
        assertTrue(p.definitions(GenerationContext(agentMode = "off")).isEmpty())
        assertTrue(p.definitions(GenerationContext(agentMode = "plan")).isEmpty())
        // Unlike ask_models, per-call `models` are first-class: the tool must be
        // visible in build mode even when the ensemble preset is empty.
        val defs = p.definitions(GenerationContext(agentMode = "build", agentModels = emptyList()))
        assertEquals(1, defs.size)
        assertEquals("delegate_task", defs[0].function.name)
        assertTrue(p.handles("delegate_task"))
    }

    @Test
    fun delegateTask_noTaskOrModels_errors() {
        val p = provider()
        runBlocking {
            val noTask = Json.parseToJsonElement(p.execute("delegate_task", "{}", buildCtx())).jsonObject
            assertEquals("no_task", noTask.getValue("error").jsonPrimitive.content)
            val noModels = Json.parseToJsonElement(
                p.execute(
                    "delegate_task",
                    """{"task":"verify this","models":[]}""",
                    GenerationContext(agentMode = "build", agentModels = emptyList()),
                ),
            ).jsonObject
            assertEquals("no_models", noModels.getValue("error").jsonPrimitive.content)
        }
    }

    @Test
    fun delegateTask_modelsParam_overridesPreset_runsConcurrently() {
        val captured = mutableListOf<ChildRequest>()
        val p = provider(
            onChild = { req ->
                captured.add(req)
                ChildResult(
                    modelId = req.modelId,
                    report = buildJsonObject {
                        put(
                            "findings",
                            kotlinx.serialization.json.buildJsonArray {
                                add(buildJsonObject {
                                    put("claim", "missing caption")
                                    put("evidence", "page 3")
                                    put("confidence", 0.9)
                                })
                            },
                        )
                        put("summary", "done")
                    },
                )
            },
            snapshot = { "ACTIVE MEMORY:\nuser likes brevity" },
        )
        val ctx = GenerationContext(
            agentMode = "build",
            agentModels = listOf("OpenAI:a", "OpenAI:b", "OpenAI:c"),
        )
        val res = runBlocking {
            Json.parseToJsonElement(
                p.execute(
                    "delegate_task",
                    """{"task":"verify this PDF","models":["OpenAI:b","OpenAI:c"],"tools":["file_read","delegate_task","ask_user"]}""",
                    ctx,
                ),
            ).jsonObject
        }
        val reports = res.getValue("reports").jsonArray
        assertEquals(2, reports.size)
        // Reports follow the requested model order even though children run concurrently.
        assertEquals(
            listOf("OpenAI:b", "OpenAI:c"),
            reports.map { it.jsonObject.getValue("model").jsonPrimitive.content },
        )
        // delegate_task and ask_user are always stripped from the child allow-list.
        assertEquals(2, captured.size)
        captured.forEach { req ->
            assertEquals(setOf("file_read"), req.toolAllowList)
            assertEquals("ACTIVE MEMORY:\nuser likes brevity", req.memorySnapshot)
        }
        // Structured report fields are surfaced.
        val first = reports[0].jsonObject
        assertEquals("done", first.getValue("summary").jsonPrimitive.content)
        assertEquals(1, first.getValue("findings").jsonArray.size)
    }

    @Test
    fun delegateTask_defaultAllowList_isReadOnly() {
        var captured: ChildRequest? = null
        val p = provider(onChild = { req ->
            captured = req
            ChildResult(modelId = req.modelId)
        })
        runBlocking {
            p.execute(
                "delegate_task",
                """{"task":"inspect","models":["OpenAI:a"]}""",
                GenerationContext(agentMode = "build", agentModels = listOf("OpenAI:a")),
            )
        }
        assertEquals(SubagentToolProvider.DEFAULT_CHILD_TOOLS.toSet(), captured!!.toolAllowList)
    }

    @Test
    fun delegateTask_perModelError_doesNotFailBatch() {
        val p = provider(onChild = { req ->
            if (req.modelId == "OpenAI:bad") throw RuntimeException("boom")
            ChildResult(modelId = req.modelId, rawText = "plain text answer")
        })
        val res = runBlocking {
            Json.parseToJsonElement(
                p.execute(
                    "delegate_task",
                    """{"task":"inspect","models":["OpenAI:bad","OpenAI:good"]}""",
                    buildCtx(),
                ),
            ).jsonObject
        }
        val reports = res.getValue("reports").jsonArray
        assertEquals(2, reports.size)
        assertTrue(reports[0].jsonObject.getValue("error").jsonPrimitive.content.isNotBlank())
        // Unparseable report falls back to raw text instead of failing.
        assertEquals(
            "plain text answer",
            reports[1].jsonObject.getValue("raw_text").jsonPrimitive.content,
        )
    }

    @Test
    fun delegateTask_allChildrenFail_reportsAllFailed() {
        val p = provider(onChild = { req -> ChildResult(modelId = req.modelId, error = "timeout") })
        val res = runBlocking {
            Json.parseToJsonElement(
                p.execute(
                    "delegate_task",
                    """{"task":"inspect","models":["OpenAI:a"]}""",
                    buildCtx(),
                ),
            ).jsonObject
        }
        assertEquals("all_failed", res.getValue("error").jsonPrimitive.content)
    }

    @Test
    fun toolAllowList_filtersDefinitionsCentrally() {
        val fakeProvider = object : ToolProvider {
            override fun definitions(ctx: GenerationContext): List<ToolDefinition> = listOf(
                ToolDefinition(function = ToolFunction("file_read", "read", ToolParameters(properties = emptyMap()))),
                ToolDefinition(function = ToolFunction("execute_shell_command", "shell", ToolParameters(properties = emptyMap()))),
            )

            override suspend fun execute(name: String, arguments: String, ctx: GenerationContext) = "{}"
            override fun handles(name: String) = true
        }
        val executor = GenerationToolExecutor.forTest(listOf(fakeProvider))
        val unrestricted = executor.definitions(GenerationContext(agentMode = "build"))
        assertEquals(2, unrestricted.size)
        val restricted = executor.definitions(
            GenerationContext(agentMode = "build", toolAllowList = setOf("file_read")),
        )
        assertEquals(listOf("file_read"), restricted.map { it.function.name })
        // Unknown names in the allow-list enable nothing.
        val empty = executor.definitions(
            GenerationContext(agentMode = "build", toolAllowList = setOf("nope")),
        )
        assertTrue(empty.isEmpty())
    }
}
