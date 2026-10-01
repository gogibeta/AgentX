package com.newoether.agora.api.typesafe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Error classification for Jev failures (Part 0): the agent and the
 * prune_context tool must explain WHY Jev failed instead of returning a bare
 * "jev_unavailable". Pure JVM — no Android needed.
 */
class JevDecisionsErrorTest {

    @Test
    fun classifyError_noKeyBeatsEverything() {
        assertEquals(JevDecisions.JevError.NoKey, JevDecisions.classifyError(null, IOException("dns")))
        assertEquals(JevDecisions.JevError.NoKey, JevDecisions.classifyError("", IOException("dns")))
        assertEquals(JevDecisions.JevError.NoKey, JevDecisions.classifyError("   ", IOException("dns")))
    }

    @Test
    fun classifyError_apiExceptionKeepsStatusAndMessage() {
        val err = JevDecisions.classifyError(
            "key",
            TypeSafeClient.JevApiException(401, "invalid credentials", null),
        )
        assertTrue(err is JevDecisions.JevError.Api)
        assertEquals(401, (err as JevDecisions.JevError.Api).status)
        assertTrue(err.message.contains("invalid credentials"))
    }

    @Test
    fun classifyError_networkAndResponseVariants() {
        assertTrue(JevDecisions.classifyError("k", TypeSafeClient.JevNetworkException("dns")) is JevDecisions.JevError.Network)
        assertTrue(JevDecisions.classifyError("k", TypeSafeClient.JevResponseException("weird")) is JevDecisions.JevError.BadResponse)
        // Plain IOExceptions (timeouts, DNS) also read as network failures.
        assertTrue(JevDecisions.classifyError("k", IOException("timeout")) is JevDecisions.JevError.Network)
        // Unknown exceptions degrade to Network with the exception name, never crash.
        val fallback = JevDecisions.classifyError("k", IllegalStateException("boom"))
        assertTrue(fallback is JevDecisions.JevError.Network)
    }

    @Test
    fun describeError_messagesAreActionable() {
        assertTrue(JevDecisions.describeError(JevDecisions.JevError.NoKey).contains("Settings"))
        assertTrue(JevDecisions.describeError(JevDecisions.JevError.Api(401, "x")).contains("invalid or revoked"))
        assertTrue(JevDecisions.describeError(JevDecisions.JevError.Api(403, "x")).contains("403"))
        assertTrue(JevDecisions.describeError(JevDecisions.JevError.Api(404, "x")).contains("base URL"))
        assertTrue(JevDecisions.describeError(JevDecisions.JevError.Api(429, "x")).contains("rate-limited"))
        assertTrue(JevDecisions.describeError(JevDecisions.JevError.Api(503, "x")).contains("server error"))
        assertTrue(JevDecisions.describeError(JevDecisions.JevError.Network("dns down")).contains("network error"))
        assertTrue(JevDecisions.describeError(JevDecisions.JevError.BadResponse).contains("bad response"))
    }

    @Test
    fun jevFailureException_carriesHumanReadableMessage() {
        val ex = JevDecisions.JevFailureException(JevDecisions.JevError.NoKey)
        assertEquals(JevDecisions.describeError(JevDecisions.JevError.NoKey), ex.message)
        assertEquals(JevDecisions.JevError.NoKey, ex.error)
    }

    @Test
    fun isConfigured_blankKeyMeansDisabled() {
        assertFalse(JevDecisions.isConfigured(null))
        assertFalse(JevDecisions.isConfigured(""))
        assertFalse(JevDecisions.isConfigured("   "))
        assertTrue(JevDecisions.isConfigured("k"))
    }
}
