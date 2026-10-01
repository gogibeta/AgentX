package com.newoether.agora.tool

import android.app.Application
import com.newoether.agora.api.LlmProvider
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.viewmodel.GenerationContext
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AgentModesTest {

    private fun shellCtx(mode: String) = GenerationContext(
        shellEnabled = true,
        shellDevices = listOf(
            com.newoether.agora.data.ShellDeviceConfig(name = "s1", serverUrl = "http://a"),
        ),
        agentMode = mode,
    )

    @Test
    fun planMode_hidesMutations_keepsReads() {
        val names = ShellToolDefinitions.build(shellCtx("plan")).map { it.function.name }.toSet()
        assertFalse(names.contains("execute_shell_command"))
        assertFalse(names.contains("stop_shell_job"))
        assertFalse(names.contains("file_write"))
        assertFalse(names.contains("file_edit"))
        assertTrue(names.contains("file_read"))
        assertTrue(names.contains("file_glob"))
        assertTrue(names.contains("file_grep"))
        assertTrue(names.contains("list_shells"))
        assertTrue(names.contains("wait_for_job"))
    }

    @Test
    fun offAndBuildModes_keepFullToolset() {
        val off = ShellToolDefinitions.build(shellCtx("off")).map { it.function.name }.toSet()
        val build = ShellToolDefinitions.build(shellCtx("build")).map { it.function.name }.toSet()
        assertEquals(off, build)
        assertTrue(off.contains("execute_shell_command"))
        assertTrue(off.contains("file_write"))
    }

    @Test
    fun artifactTool_onlyInBuildMode() {
        val provider = ArtifactToolProvider(mockk(relaxed = true))
        assertTrue(provider.definitions(GenerationContext(agentMode = "off")).isEmpty())
        assertTrue(provider.definitions(GenerationContext(agentMode = "plan")).isEmpty())
        val defs = provider.definitions(GenerationContext(agentMode = "build"))
        assertEquals(2, defs.size)
        assertEquals("save_artifact", defs[0].function.name)
        assertEquals("fetch_image", defs[1].function.name)
        assertTrue(provider.handles("save_artifact"))
        assertTrue(provider.handles("fetch_image"))
    }

    @Test
    fun artifactTool_rejectsBadInput() {
        val provider = ArtifactToolProvider(mockk(relaxed = true))
        val ctx = GenerationContext(agentMode = "build")
        runBlocking {
            val noTitle = Json.parseToJsonElement(provider.execute("save_artifact", "{}", ctx)).jsonObject
            assertEquals("no_title", noTitle.getValue("error").jsonPrimitive.content)
            val badFormat = Json.parseToJsonElement(
                provider.execute("save_artifact", """{"title":"T","format":"docx","content":"x"}""", ctx),
            ).jsonObject
            assertEquals("bad_format", badFormat.getValue("error").jsonPrimitive.content)
            val unknown = provider.execute("nope", "{}", ctx)
            assertTrue(unknown.contains("Unknown tool"))
        }
    }

    @Test
    fun ensembleTool_onlyInBuildModeWithModels() {
        val provider = EnsembleToolProvider(
            providerForModel = { "OpenAI" },
            getProvider = { null },
            activeKey = { "" },
            baseUrl = { null },
            apiModelName = { it },
        )
        assertTrue(provider.definitions(GenerationContext(agentMode = "off")).isEmpty())
        assertTrue(
            provider.definitions(GenerationContext(agentMode = "build", agentModels = emptyList())).isEmpty(),
        )
        val defs = provider.definitions(
            GenerationContext(agentMode = "build", agentModels = listOf("OpenAI:gpt-4o", "Google:gemini-2.0")),
        )
        assertEquals(1, defs.size)
        assertEquals("ask_models", defs[0].function.name)
    }

    @Test
    fun ensembleTool_noQuestionOrModels_errors() {
        val provider = EnsembleToolProvider(
            providerForModel = { "OpenAI" },
            getProvider = { null },
            activeKey = { "" },
            baseUrl = { null },
            apiModelName = { it },
        )
        val ctx = GenerationContext(agentMode = "build", agentModels = listOf("OpenAI:gpt-4o"))
        runBlocking {
            val noQ = Json.parseToJsonElement(provider.execute("ask_models", "{}", ctx)).jsonObject
            assertEquals("no_question", noQ.getValue("error").jsonPrimitive.content)
            val noModels = Json.parseToJsonElement(
                provider.execute("ask_models", """{"question":"hi"}""", GenerationContext(agentMode = "build")),
            ).jsonObject
            assertEquals("no_models", noModels.getValue("error").jsonPrimitive.content)
        }
    }

    @Test
    fun compactAssist_onlyWithJevKeyInBuildMode() {
        val provider = CompactAssistToolProvider()
        assertTrue(provider.definitions(GenerationContext(agentMode = "build")).isEmpty())
        assertTrue(
            provider.definitions(GenerationContext(agentMode = "off", typeSafeApiKey = "k")).isEmpty(),
        )
        val defs = provider.definitions(
            GenerationContext(agentMode = "build", typeSafeApiKey = "k", jevEnabled = true),
        )
        assertEquals(1, defs.size)
        assertEquals("prune_context", defs[0].function.name)
    }

    @Test
    fun withAgentEnv_prefixesExports() {
        assertEquals("echo hi", withAgentEnv("echo hi", emptyMap()))
        assertEquals(
            "export KEY='v' ; echo hi",
            withAgentEnv("echo hi", mapOf("KEY" to "v")),
        )
        // Single quotes inside secrets are escaped for sh.
        assertEquals(
            "export K='a'\\''b' ; echo hi",
            withAgentEnv("echo hi", mapOf("K" to "a'b")),
        )
    }

    @Test
    fun partitionKept_verbatimThreshold() {
        val keys = listOf("a", "b", "c")
        assertEquals(listOf("a", "c"), CompactAssistToolProvider.partitionKept(keys, listOf(0.9, 0.1, 0.5), 0.5))
        assertEquals(keys, CompactAssistToolProvider.partitionKept(keys, listOf(0.9), 0.5))
        assertEquals(emptyList<String>(), CompactAssistToolProvider.partitionKept(keys, listOf(0.1, 0.2, 0.3), 0.5))
    }

    @Test
    fun artifactTool_sourcePath_rejectsTraversalAndMissing() {
        val provider = ArtifactToolProvider(mockk(relaxed = true))
        val ctx = GenerationContext(agentMode = "build")
        runBlocking {
            for (bad in listOf("../evil.pdf", "/abs/report.pdf", "sub/dir.pdf", "..")) {
                val r = Json.parseToJsonElement(
                    provider.execute("save_artifact", """{"title":"T","source_path":"$bad"}""", ctx),
                ).jsonObject
                assertEquals("bad_source_path", r.getValue("error").jsonPrimitive.content)
            }
            val missing = Json.parseToJsonElement(
                provider.execute("save_artifact", """{"title":"T","source_path":"ghost.pdf"}""", ctx),
            ).jsonObject
            assertEquals("source_not_found", missing.getValue("error").jsonPrimitive.content)
        }
    }

    @Test
    fun artifactTool_sourcePath_registersPrebuiltFile() {
        val tmp = Files.createTempDirectory("agentx-artifact-test").toFile()
        try {
            val dir = File(tmp, "artifacts").also { it.mkdirs() }
            File(dir, "report.pdf").writeBytes(ByteArray(2048) { it.toByte() })
            val app: Application = mockk(relaxed = true)
            every { app.filesDir } returns tmp
            val provider = ArtifactToolProvider(app)
            val res = runBlocking {
                Json.parseToJsonElement(
                    provider.execute(
                        "save_artifact",
                        """{"title":"R","source_path":"report.pdf"}""",
                        GenerationContext(agentMode = "build"),
                    ),
                ).jsonObject
            }
            assertEquals("artifact", res.getValue("type").jsonPrimitive.content)
            assertEquals("pdf", res.getValue("format").jsonPrimitive.content)
            assertEquals(2048, res.getValue("sizeBytes").jsonPrimitive.int)
            assertEquals("source_path", res.getValue("source").jsonPrimitive.content)
            assertTrue(res.getValue("saved_to").jsonPrimitive.content.startsWith("app storage"))
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun ensembleTool_modelsParam_overridesPreset() {
        val asked = mutableListOf<String>()
        val fake: LlmProvider = mockk()
        every { fake.generateResponse(any(), any()) } answers {
            asked.add(secondArg<ProviderConfig>().modelId)
            flowOf(StreamEvent.TextChunk("ok"))
        }
        val provider = EnsembleToolProvider(
            providerForModel = { "OpenAI" },
            getProvider = { fake },
            activeKey = { "k" },
            baseUrl = { null },
            apiModelName = { it },
        )
        val ctx = GenerationContext(
            agentMode = "build",
            agentModels = listOf("OpenAI:a", "OpenAI:b", "OpenAI:c"),
        )
        val res = runBlocking {
            Json.parseToJsonElement(
                provider.execute(
                    "ask_models",
                    """{"question":"verify this","models":["OpenAI:b","OpenAI:c"]}""",
                    ctx,
                ),
            ).jsonObject
        }
        val answers = res.getValue("answers").jsonArray
        assertEquals(2, answers.size)
        assertEquals(listOf("OpenAI:b", "OpenAI:c"), asked)
    }

    @Test
    fun ensembleTool_surfacesProviderErrorMessage() {
        val fake: LlmProvider = mockk()
        every { fake.generateResponse(any(), any()) } answers {
            flowOf(
                StreamEvent.Error(
                    com.newoether.agora.api.GenerationError.Api(null, null, "boom-detail"),
                ),
            )
        }
        val provider = EnsembleToolProvider(
            providerForModel = { "OpenAI" },
            getProvider = { fake },
            activeKey = { "k" },
            baseUrl = { null },
            apiModelName = { it },
        )
        val ctx = GenerationContext(agentMode = "build", agentModels = listOf("OpenAI:a"))
        val res = runBlocking {
            Json.parseToJsonElement(
                provider.execute("ask_models", """{"question":"hi"}""", ctx),
            ).jsonObject
        }
        val answers = res.getValue("answers").jsonArray
        assertEquals(1, answers.size)
        val err = answers[0].jsonObject.getValue("error").jsonPrimitive.content
        assertTrue(err.contains("boom-detail"))
    }
}
