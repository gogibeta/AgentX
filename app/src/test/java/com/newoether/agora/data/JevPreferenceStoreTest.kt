package com.newoether.agora.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Jev settings store (Part 0): bulk key import parsing (trim/dedupe/blank
 * drop), add/remove, round-robin key rotation, and the enabled+has-keys
 * configured gate. DataStore is in-memory; SecretCrypto is identity-mocked
 * (Android KeyStore unavailable on JVM) — same pattern as AgentSettingsTest.
 */
class JevPreferenceStoreTest {

    private val testJson = Json { ignoreUnknownKeys = true }

    private fun store(dataStore: InMemoryPrefs = InMemoryPrefs()): JevPreferenceStore =
        JevPreferenceStore(dataStore, testJson)

    @Test
    fun bulkImport_trimsDedupesAndDropsBlanks() = runTest {
        mockSecretCrypto()
        try {
            val s = store()
            s.setJevApiKeys(listOf("  k1  ", "", "k2", "k1", "   "))
            val keys = s.jevApiKeys.first { it == listOf("k1", "k2") }
            assertEquals(listOf("k1", "k2"), keys)
        } finally {
            unmockSecretCrypto()
        }
    }

    @Test
    fun bulkImport_emptyListClearsKeys() = runTest {
        mockSecretCrypto()
        try {
            val s = store()
            s.setJevApiKeys(listOf("k1"))
            assertTrue(s.jevApiKeys.first { it.isNotEmpty() }.isNotEmpty())
            s.setJevApiKeys(emptyList())
            assertTrue(s.jevApiKeys.first { it.isEmpty() }.isEmpty())
        } finally {
            unmockSecretCrypto()
        }
    }

    @Test
    fun addKey_dedupesAndIgnoresBlank() = runTest {
        mockSecretCrypto()
        try {
            val s = store()
            s.addJevApiKey("k1")
            s.addJevApiKey("k1")
            s.addJevApiKey("   ")
            s.addJevApiKey("k2")
            assertEquals(listOf("k1", "k2"), s.jevApiKeys.first { it.size == 2 })
        } finally {
            unmockSecretCrypto()
        }
    }

    @Test
    fun removeKey_removesExactlyOne() = runTest {
        mockSecretCrypto()
        try {
            val s = store()
            s.setJevApiKeys(listOf("k1", "k2"))
            assertEquals(listOf("k1", "k2"), s.jevApiKeys.first { it.size == 2 })
            s.removeJevApiKey("k1")
            assertEquals(listOf("k2"), s.jevApiKeys.first { it == listOf("k2") })
        } finally {
            unmockSecretCrypto()
        }
    }

    @Test
    fun keyList_cappedAtMaxKeys() = runTest {
        mockSecretCrypto()
        try {
            val s = store()
            s.setJevApiKeys((1..(JevPreferenceStore.MAX_KEYS + 5)).map { "k$it" })
            val keys = s.jevApiKeys.first { it.size == JevPreferenceStore.MAX_KEYS }
            assertEquals(JevPreferenceStore.MAX_KEYS, keys.size)
        } finally {
            unmockSecretCrypto()
        }
    }

    @Test
    fun pickKey_roundRobinsAcrossKeys() = runTest {
        mockSecretCrypto()
        try {
            val s = store()
            s.setJevEnabled(true)
            s.setJevApiKeys(listOf("k1", "k2", "k3"))
            // Wait for both flows to settle before picking.
            s.jevApiKeys.first { it.size == 3 }
            s.jevEnabled.first { it }
            assertEquals("k1", s.pickKey())
            assertEquals("k2", s.pickKey())
            assertEquals("k3", s.pickKey())
            assertEquals("k1", s.pickKey())
        } finally {
            unmockSecretCrypto()
        }
    }

    @Test
    fun pickKey_nullWhenDisabledOrEmpty() = runTest {
        mockSecretCrypto()
        try {
            val s = store()
            s.setJevApiKeys(listOf("k1"))
            s.jevApiKeys.first { it.isNotEmpty() }
            // Disabled by default → null even with keys present.
            assertEquals(null, s.pickKey())
            s.setJevEnabled(true)
            s.jevEnabled.first { it }
            assertEquals("k1", s.pickKey())
        } finally {
            unmockSecretCrypto()
        }
    }

    @Test
    fun jevConfigured_requiresEnabledAndKeys() = runTest {
        mockSecretCrypto()
        try {
            val s = store()
            assertFalse(s.jevConfigured.value)
            s.setJevApiKeys(listOf("k1"))
            s.jevApiKeys.first { it.isNotEmpty() }
            // Keys but not enabled → not configured.
            s.jevConfigured.first { !it }
            s.setJevEnabled(true)
            assertTrue(s.jevConfigured.first { it })
            s.setJevEnabled(false)
            assertFalse(s.jevConfigured.first { !it })
        } finally {
            unmockSecretCrypto()
        }
    }

    @Test
    fun baseUrlAndModel_trimmedWithDefaults() = runTest {
        mockSecretCrypto()
        try {
            val s = store()
            s.setJevBaseUrl("  https://proxy.example  ")
            assertEquals("https://proxy.example", s.jevBaseUrl.first { it.isNotEmpty() })
            assertEquals("https://proxy.example", s.effectiveBaseUrl())
            s.setJevBaseUrl("")
            s.jevBaseUrl.first { it.isEmpty() }
            assertEquals(JevPreferenceStore.DEFAULT_JEV_BASE_URL, s.effectiveBaseUrl())
            assertEquals(JevPreferenceStore.DEFAULT_JEV_MODEL, s.jevModel.value)
            s.setJevModel("  jev-beta  ")
            assertEquals("jev-beta", s.jevModel.first { it == "jev-beta" })
        } finally {
            unmockSecretCrypto()
        }
    }

    private fun mockSecretCrypto() {
        // Idempotent: unmock first in case a previous test leaked the mock
        // (MockK object mocks are JVM-global and can conflict under parallel runs).
        runCatching { io.mockk.unmockkObject(com.newoether.agora.util.SecretCrypto) }
        io.mockk.mockkObject(com.newoether.agora.util.SecretCrypto)
        io.mockk.every { com.newoether.agora.util.SecretCrypto.encrypt(any()) } answers { firstArg<String>() }
        io.mockk.every { com.newoether.agora.util.SecretCrypto.decrypt(any()) } answers { firstArg<String>() }
    }

    private fun unmockSecretCrypto() {
        runCatching { io.mockk.unmockkObject(com.newoether.agora.util.SecretCrypto) }
    }

    private class InMemoryPrefs(
        initial: Preferences = emptyPreferences(),
    ) : DataStore<Preferences> {
        private val mutex = Mutex()
        private val state = MutableStateFlow(initial)

        override val data: Flow<Preferences> = state

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences = mutex.withLock {
            transform(state.value).also { state.value = it }
        }
    }
}
