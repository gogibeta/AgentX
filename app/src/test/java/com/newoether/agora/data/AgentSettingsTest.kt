package com.newoether.agora.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSettingsTest {

    @Test
    fun normalizeAgentMode() {
        assertEquals("off", normalizeAgentMode(null))
        assertEquals("off", normalizeAgentMode("bogus"))
        assertEquals("plan", normalizeAgentMode(" Plan "))
        assertEquals("build", normalizeAgentMode("BUILD"))
        assertEquals("off", normalizeAgentMode("off"))
    }

    @Test
    fun decodeAgentModels_capsAtFive_distinct() {
        assertEquals(emptyList<String>(), decodeAgentModels(null))
        assertEquals(emptyList<String>(), decodeAgentModels("not json"))
        assertEquals(
            listOf("A:m1", "B:m2"),
            decodeAgentModels("""["A:m1", "B:m2", "A:m1", "  "]"""),
        )
        val six = (1..6).map { "P:m$it" }
        val decoded = decodeAgentModels(
            six.joinToString(prefix = "[", postfix = "]", transform = { "\"$it\"" }),
        )
        assertEquals(5, decoded.size)
        assertTrue(decoded.contains("P:m1"))
    }

    @Test
    fun normalizeWebSearchProvider_acceptsTinyFishAndFusion() {
        assertEquals("tinyfish", normalizeWebSearchProvider(" TinyFish "))
        assertEquals("fusion", normalizeWebSearchProvider("FUSION"))
        assertEquals("duckduckgo", normalizeWebSearchProvider("unknown"))
    }

    @Test
    fun agentEnv_validationAndRoundTrip() {
        assertTrue(isValidAgentEnvName("MY_KEY_1"))
        assertTrue(!isValidAgentEnvName("has space"))
        assertTrue(!isValidAgentEnvName("9starts-digit"))
        assertTrue(!isValidAgentEnvName(""))
        // SecretCrypto needs Android; identity-mock it (same as PortableSettingsArchiveTest).
        // Unmock first for idempotence under parallel test execution.
        runCatching { io.mockk.unmockkObject(com.newoether.agora.util.SecretCrypto) }
        io.mockk.mockkObject(com.newoether.agora.util.SecretCrypto)
        io.mockk.every { com.newoether.agora.util.SecretCrypto.encrypt(any()) } answers { firstArg<String>() }
        io.mockk.every { com.newoether.agora.util.SecretCrypto.decrypt(any()) } answers { firstArg<String>() }
        try {
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val encoded = encodeAgentEnv(mapOf("A" to "1", "bad name" to "x"), json)
            val decoded = decodeAgentEnv(encoded, json)
            assertEquals(mapOf("A" to "1"), decoded)
            assertEquals(emptyMap<String, String>(), decodeAgentEnv("garbage", json))
            assertEquals(emptyMap<String, String>(), decodeAgentEnv(null, json))
        } finally {
            runCatching { io.mockk.unmockkObject(com.newoether.agora.util.SecretCrypto) }
        }
    }
}
