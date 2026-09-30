package com.newoether.agora.api

import com.newoether.agora.data.ApiKeyEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiKeyRotationTest {

    private fun entry(id: String, provider: String, key: String) =
        ApiKeyEntry(id = id, name = id, key = key, provider = provider)

    private val entries = listOf(
        entry("a1", "P", "k1"),
        entry("a2", "P", "k2"),
        entry("a3", "P", "k3"),
        entry("b1", "Q", "q1"),
        entry("blank", "P", ""),
    )
    private val active = mapOf("P" to "a2")

    @Test
    fun orderedKeys_activeFirst() {
        assertEquals(listOf("k2", "k1", "k3"), ApiKeyRotation.orderedKeys(entries, active, "P"))
        assertEquals(listOf("q1"), ApiKeyRotation.orderedKeys(entries, active, "Q"))
        assertEquals(emptyList<String>(), ApiKeyRotation.orderedKeys(entries, active, "Z"))
    }

    @Test
    fun pickForRequest_roundRobins() {
        val picks = (1..6).map { ApiKeyRotation.pickForRequest(entries, active, "P") }
        assertEquals(listOf("k2", "k1", "k3", "k2", "k1", "k3"), picks)
        assertEquals("q1", ApiKeyRotation.pickForRequest(entries, active, "Q"))
        assertNull(ApiKeyRotation.pickForRequest(entries, active, "Z"))
    }

    @Test
    fun keyForAttempt_failoverOrder() {
        assertEquals("k2", ApiKeyRotation.keyForAttempt("k2", listOf("k1", "k3"), 1))
        assertEquals("k1", ApiKeyRotation.keyForAttempt("k2", listOf("k1", "k3"), 2))
        assertEquals("k3", ApiKeyRotation.keyForAttempt("k2", listOf("k1", "k3"), 3))
        assertEquals("k1", ApiKeyRotation.keyForAttempt("k2", listOf("k1", "k3"), 4))
        assertEquals("k2", ApiKeyRotation.keyForAttempt("k2", emptyList(), 2))
    }

    @Test
    fun alternatesFor_excludesPicked() {
        assertEquals(listOf("k1", "k3"), ApiKeyRotation.alternatesFor(entries, active, "P", "k2"))
        assertTrue(ApiKeyRotation.alternatesFor(entries, active, "Z", null).isEmpty())
    }
}
