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
 * configured gate. DataStore is in-memory; JevKeyCrypto.Identity is injected
 * (Android KeyStore unavailable on JVM) — no MockK needed.
 */
class JevPreferenceStoreTest {

    private val testJson = Json { ignoreUnknownKeys = true }

    private fun store(dataStore: InMemoryPrefs = InMemoryPrefs()): JevPreferenceStore =
        JevPreferenceStore(dataStore, testJson, JevKeyCrypto.Identity)

    @Test
    fun bulkImport_trimsDedupesAndDropsBlanks() = runTest {
        val s = store()
        s.setJevApiKeys(listOf("  k1  ", "", "k2", "k1", "   "))
        val keys = s.jevApiKeys.first { it == listOf("k1", "k2") }
        assertEquals(listOf("k1", "k2"), keys)
    }

    @Test
    fun bulkImport_emptyListClearsKeys() = runTest {
        val s = store()
        s.setJevApiKeys(listOf("k1"))
        assertTrue(s.jevApiKeys.first { it.isNotEmpty() }.isNotEmpty())
        s.setJevApiKeys(emptyList())
        assertTrue(s.jevApiKeys.first { it.isEmpty() }.isEmpty())
    }

    @Test
    fun addKey_dedupesAndIgnoresBlank() = runTest {
        val s = store()
        s.addJevApiKey("k1")
        s.addJevApiKey("k1")
        s.addJevApiKey("   ")
        s.addJevApiKey("k2")
        // Order is not guaranteed under concurrent launches; check contents as a set.
        val keys = s.jevApiKeys.first { it.size == 2 }
        assertEquals(setOf("k1", "k2"), keys.toSet())
    }

    @Test
    fun removeKey_removesExactlyOne() = runTest {
        val s = store()
        s.setJevApiKeys(listOf("k1", "k2"))
        assertEquals(listOf("k1", "k2"), s.jevApiKeys.first { it.size == 2 })
        s.removeJevApiKey("k1")
        assertEquals(listOf("k2"), s.jevApiKeys.first { it == listOf("k2") })
    }

    @Test
    fun keyList_cappedAtMaxKeys() = runTest {
        val s = store()
        s.setJevApiKeys((1..(JevPreferenceStore.MAX_KEYS + 5)).map { "k$it" })
        val keys = s.jevApiKeys.first { it.size == JevPreferenceStore.MAX_KEYS }
        assertEquals(JevPreferenceStore.MAX_KEYS, keys.size)
    }

    @Test
    fun pickKey_roundRobinsAcrossKeys() = runTest {
        val s = store()
        s.setJevEnabled(true)
        s.setJevApiKeys(listOf("k1", "k2", "k3"))
        // Wait for both flows to settle before picking. pickKey() reads
        // decisionApiKeys (a separate hot flow on the IO scope), so wait on
        // THAT flow explicitly — jevApiKeys settling first does not imply
        // decisionApiKeys has observed the same emission (StateFlow race,
        // same as the pickKey_usesActiveProviderKeys fix).
        s.jevApiKeys.first { it.size == 3 }
        s.jevEnabled.first { it }
        s.decisionApiKeys.first { it == listOf("k1", "k2", "k3") }
        assertEquals("k1", s.pickKey())
        assertEquals("k2", s.pickKey())
        assertEquals("k3", s.pickKey())
        assertEquals("k1", s.pickKey())
    }

    @Test
    fun pickKey_nullWhenDisabledOrEmpty() = runTest {
        val s = store()
        s.setJevApiKeys(listOf("k1"))
        s.jevApiKeys.first { it.isNotEmpty() }
        // Disabled by default → null even with keys present.
        assertEquals(null, s.pickKey())
        s.setJevEnabled(true)
        s.jevEnabled.first { it }
        // pickKey() reads decisionApiKeys (separate hot flow); wait for it to
        // observe the keys before asserting, avoiding the StateFlow race.
        s.decisionApiKeys.first { it == listOf("k1") }
        assertEquals("k1", s.pickKey())
    }

    @Test
    fun jevConfigured_requiresEnabledAndKeys() = runTest {
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
    }

    @Test
    fun baseUrlAndModel_trimmedWithDefaults() = runTest {
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
    }

    @Test
    fun decisionProvider_defaultsToJevAndAcceptsDrex() = runTest {
        val s = store()
        assertEquals(JevPreferenceStore.PROVIDER_JEV, s.decisionProvider.first())
        s.setDecisionProvider("drex")
        assertEquals(JevPreferenceStore.PROVIDER_DREX, s.decisionProvider.first { it == "drex" })
        // Unknown values fall back to jev.
        s.setDecisionProvider("  DREX  ")
        assertEquals(JevPreferenceStore.PROVIDER_DREX, s.decisionProvider.first { it == "drex" })
        s.setDecisionProvider("bogus")
        assertEquals(JevPreferenceStore.PROVIDER_JEV, s.decisionProvider.first { it == "jev" })
    }

    @Test
    fun drexModel_dropdownValidatedWithDefault() = runTest {
        val s = store()
        assertEquals("drex-v1.5", s.drexModel.first())
        s.setDrexModel("drex-v1.0")
        assertEquals("drex-v1.0", s.drexModel.first { it == "drex-v1.0" })
        s.setDrexModel("drex-latest")
        assertEquals("drex-latest", s.drexModel.first { it == "drex-latest" })
        // Not in the dropdown → reset to default.
        s.setDrexModel("jev-latest")
        assertEquals("drex-v1.5", s.drexModel.first { it == "drex-v1.5" })
    }

    @Test
    fun drexKeys_cappedAtThreeAndSeparateFromJevKeys() = runTest {
        val s = store()
        s.setDrexApiKeys(listOf("d1", "d2", "d3", "d4"))
        assertEquals(listOf("d1", "d2", "d3"), s.drexApiKeys.first { it.size == 3 })
        s.setJevApiKeys(listOf("k1"))
        // Providers keep independent key lists.
        assertEquals(listOf("k1"), s.jevApiKeys.first { it.isNotEmpty() })
        assertEquals(listOf("d1", "d2", "d3"), s.drexApiKeys.first { it.size == 3 })
        s.addDrexApiKey("d5")
        assertEquals(3, s.drexApiKeys.first().size)
        s.removeDrexApiKey("d1")
        assertEquals(listOf("d2", "d3"), s.drexApiKeys.first { it == listOf("d2", "d3") })
    }

    @Test
    fun effectiveDecisionValues_followActiveProvider() = runTest {
        val s = store()
        // Jev defaults.
        assertEquals(JevPreferenceStore.DEFAULT_JEV_BASE_URL, s.effectiveDecisionBaseUrl())
        assertEquals("jev-latest", s.effectiveDecisionModel())
        assertEquals(10_000L, s.effectiveDecisionTimeoutMs())
        assertEquals(1500, s.effectiveDecisionMaxStateChars())
        // Drex selected → Drex defaults, 60s timeout, 4x state budget.
        s.setDecisionProvider("drex")
        s.decisionProvider.first { it == "drex" }
        assertEquals(JevPreferenceStore.DEFAULT_DREX_BASE_URL, s.effectiveDecisionBaseUrl())
        assertEquals("drex-v1.5", s.effectiveDecisionModel())
        assertEquals(60_000L, s.effectiveDecisionTimeoutMs())
        assertEquals(6000, s.effectiveDecisionMaxStateChars())
        s.setDrexModel("drex-v1.0")
        s.drexModel.first { it == "drex-v1.0" }
        assertEquals("drex-v1.0", s.effectiveDecisionModel())
        // Custom base URL overrides either provider default.
        s.setJevBaseUrl("https://proxy.example")
        s.jevBaseUrl.first { it == "https://proxy.example" }
        assertEquals("https://proxy.example", s.effectiveDecisionBaseUrl())
    }

    @Test
    fun pickKey_usesActiveProviderKeys() = runTest {
        val s = store()
        s.setJevEnabled(true)
        s.setJevApiKeys(listOf("k1"))
        s.setDrexApiKeys(listOf("d1", "d2"))
        s.jevApiKeys.first { it.isNotEmpty() }
        s.drexApiKeys.first { it.size == 2 }
        s.jevEnabled.first { it }
        // Jev active → Jev keys.
        assertEquals("k1", s.pickKey())
        // Drex active → Drex keys round-robin.
        s.setDecisionProvider("drex")
        s.decisionProvider.first { it == "drex" }
        // decisionApiKeys is a separate hot flow — wait for it to observe the switch,
        // otherwise pickKey() can still see the stale Jev key list.
        s.decisionApiKeys.first { it == listOf("d1", "d2") }
        assertEquals("d1", s.pickKey())
        assertEquals("d2", s.pickKey())
        assertEquals("d1", s.pickKey())
        // Configured gate follows the active provider's keys: on while Drex keys exist...
        s.jevConfigured.first { it }
        s.setDrexApiKeys(emptyList())
        s.drexApiKeys.first { it.isEmpty() }
        // ...and off once they're cleared (wait for the gate flow to observe it).
        s.jevConfigured.first { !it }
        assertFalse(s.jevConfigured.value)
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
