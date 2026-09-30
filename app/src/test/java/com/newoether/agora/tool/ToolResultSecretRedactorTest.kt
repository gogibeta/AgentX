package com.newoether.agora.tool

import com.newoether.agora.viewmodel.GenerationContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolResultSecretRedactorTest {

    private val githubToken = "ghp_" + "a".repeat(36)
    private val cloudflareKey = "cf-" + "b".repeat(40)

    private fun ctx() = GenerationContext(
        agentEnv = mapOf("GITHUB" to githubToken, "CF_API_KEY" to cloudflareKey),
        embeddingApiKey = "sk-" + "c".repeat(48),
    )

    @Test
    fun redact_exportEcho_hidesSecretValues() {
        val output = "export GITHUB='$githubToken' ; export CF_API_KEY='$cloudflareKey' ; done"
        val redacted = ToolResultSecretRedactor.redact(output, ToolResultSecretRedactor.secretsFrom(ctx()))
        assertFalse(redacted.contains(githubToken))
        assertFalse(redacted.contains(cloudflareKey))
        assertTrue(redacted.contains("export GITHUB='[REDACTED_SECRET]'"))
    }

    @Test
    fun redact_apiKeysFromContext_areHidden() {
        val output = "Authorization failed for key sk-" + "c".repeat(48)
        val redacted = ToolResultSecretRedactor.redact(output, ToolResultSecretRedactor.secretsFrom(ctx()))
        assertFalse(redacted.contains("sk-" + "c".repeat(48)))
        assertTrue(redacted.contains("[REDACTED_SECRET]"))
    }

    @Test
    fun redact_shortValues_areNeverTouched() {
        // A 3-char value must not nuke ordinary output containing those chars.
        val output = "the cat sat on the mat"
        val redacted = ToolResultSecretRedactor.redact(output, setOf("cat"))
        assertEquals(output, redacted)
    }

    @Test
    fun redact_overlappingSecrets_longestFirst() {
        val long = "x".repeat(20)
        val short = "x".repeat(10)
        val redacted = ToolResultSecretRedactor.redact("token=$long", setOf(short, long))
        assertEquals("token=[REDACTED_SECRET]", redacted)
    }

    @Test
    fun redact_emptyText_returnsEmpty() {
        assertEquals("", ToolResultSecretRedactor.redact("", setOf(githubToken)))
    }

    @Test
    fun redactResult_redactsAllTextFields() {
        val result = ToolExecutionResult(
            text = "key=$githubToken",
            displayText = "showing $cloudflareKey",
            structuredContent = "{\"token\":\"$githubToken\"}",
        )
        val safe = ToolResultSecretRedactor.redactResult(result, ctx())
        assertFalse(safe.text.contains(githubToken))
        assertFalse(safe.displayText!!.contains(cloudflareKey))
        assertFalse(safe.structuredContent!!.contains(githubToken))
        assertTrue(safe.text.contains("[REDACTED_SECRET]"))
    }

    @Test
    fun redactResult_noSecrets_returnsSameInstance() {
        val result = ToolExecutionResult(text = "hello")
        val emptyCtx = GenerationContext()
        assertTrue(ToolResultSecretRedactor.redactResult(result, emptyCtx) === result)
    }

    @Test
    fun secretsFrom_collectsEnvAndApiKeys() {
        val secrets = ToolResultSecretRedactor.secretsFrom(ctx())
        assertTrue(secrets.contains(githubToken))
        assertTrue(secrets.contains(cloudflareKey))
        assertTrue(secrets.contains("sk-" + "c".repeat(48)))
    }
}
