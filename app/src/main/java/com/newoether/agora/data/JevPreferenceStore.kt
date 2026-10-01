package com.newoether.agora.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.newoether.agora.util.SecretCrypto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger

private val JEV_ENABLED = booleanPreferencesKey("jev_enabled")
private val JEV_BASE_URL = stringPreferencesKey("jev_base_url")
private val JEV_MODEL = stringPreferencesKey("jev_model")
private val JEV_API_KEYS_JSON = stringPreferencesKey("jev_api_keys_json")

/**
 * Jev (TypeSafe decision model) settings — own DataStore slice following the
 * [SettingsAgentPreferenceStore] pattern, so the 800-line-capped
 * [SettingsManager]/[SettingsRepository] stay untouched apart from one accessor
 * line each.
 *
 * Jev is a *decision* model, not a chat model: it answers typed Choice / Score /
 * Noul questions via `POST /v1/systemone`. Settings here:
 * - [jevEnabled]: master toggle — Jev features only run when this is on.
 * - [jevBaseUrl]: custom base URL (proxy/gateway/self-hosted Jev-compatible).
 * - [jevModel]: model name (default `jev-latest`). No provider model picker —
 *   the user types the name.
 * - [jevApiKeys]: multiple keys; [pickKey] rotates round-robin so load spreads
 *   and a single rate-limited key doesn't stall decisions.
 *
 * Keys are encrypted at rest via [SecretCrypto]. Key rotation is in-memory
 * round-robin (per process); failover across keys on retry is the caller's job.
 */
/**
 * Pluggable encryption for Jev API keys at rest. Production uses [SecretCrypto]
 * (Android Keystore); tests inject the identity implementation to avoid
 * Android-only code and JVM-global MockK object mocks.
 */
interface JevKeyCrypto {
    fun encrypt(plaintext: String): String
    fun decrypt(stored: String): String

    companion object {
        val Default: JevKeyCrypto = object : JevKeyCrypto {
            override fun encrypt(plaintext: String) = SecretCrypto.encrypt(plaintext)
            override fun decrypt(stored: String) = SecretCrypto.decrypt(stored)
        }
        val Identity: JevKeyCrypto = object : JevKeyCrypto {
            override fun encrypt(plaintext: String) = plaintext
            override fun decrypt(stored: String) = stored
        }
    }
}

class JevPreferenceStore(
    private val store: DataStore<Preferences>,
    private val json: Json,
    private val crypto: JevKeyCrypto = JevKeyCrypto.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rotationCursor = AtomicInteger(0)

    val jevEnabled: StateFlow<Boolean> = store.data
        .map { it[JEV_ENABLED] ?: false }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val jevBaseUrl: StateFlow<String> = store.data
        .map { (it[JEV_BASE_URL] ?: "").trim() }
        .stateIn(scope, SharingStarted.Eagerly, "")

    val jevModel: StateFlow<String> = store.data
        .map { (it[JEV_MODEL] ?: "").trim().ifBlank { DEFAULT_JEV_MODEL } }
        .stateIn(scope, SharingStarted.Eagerly, DEFAULT_JEV_MODEL)

    /** All configured keys (decrypted). Empty = not configured. */
    val jevApiKeys: StateFlow<List<String>> = store.data
        .map { decodeJevKeys(it[JEV_API_KEYS_JSON], json, crypto) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** True when the toggle is on AND at least one key is present. */
    val jevConfigured: StateFlow<Boolean> = store.data
        .map { (it[JEV_ENABLED] ?: false) && decodeJevKeys(it[JEV_API_KEYS_JSON], json, crypto).isNotEmpty() }
        .stateIn(scope, SharingStarted.Eagerly, false)

    fun setJevEnabled(enabled: Boolean) = scope.launch {
        store.edit { it[JEV_ENABLED] = enabled }
    }

    fun setJevBaseUrl(url: String) = scope.launch {
        store.edit { it[JEV_BASE_URL] = url.trim() }
    }

    fun setJevModel(model: String) = scope.launch {
        store.edit { it[JEV_MODEL] = model.trim() }
    }

    /** Replace the whole key list (used by bulk import). Blank entries dropped, deduped. */
    fun setJevApiKeys(keys: List<String>) = scope.launch {
        val cleaned = keys.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        store.edit { prefs ->
            if (cleaned.isEmpty()) prefs.remove(JEV_API_KEYS_JSON)
            else prefs[JEV_API_KEYS_JSON] = crypto.encrypt(json.encodeToString(cleaned))
        }
    }

    fun addJevApiKey(key: String) = scope.launch {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return@launch
        store.edit { prefs ->
            val current = decodeJevKeys(prefs[JEV_API_KEYS_JSON], json, crypto).toMutableList()
            if (trimmed !in current) current.add(trimmed)
            prefs[JEV_API_KEYS_JSON] = crypto.encrypt(json.encodeToString(current))
        }
    }

    fun removeJevApiKey(key: String) = scope.launch {
        store.edit { prefs ->
            val current = decodeJevKeys(prefs[JEV_API_KEYS_JSON], json, crypto).filter { it != key }
            if (current.isEmpty()) prefs.remove(JEV_API_KEYS_JSON)
            else prefs[JEV_API_KEYS_JSON] = crypto.encrypt(json.encodeToString(current))
        }
    }

    /**
     * Pick a key for one decision call — round-robin across keys so parallel
     * decisions spread load instead of hammering one rate limit. Returns null
     * when Jev is disabled or has no keys.
     */
    fun pickKey(): String? {
        if (!jevEnabled.value) return null
        val keys = jevApiKeys.value
        if (keys.isEmpty()) return null
        if (keys.size == 1) return keys[0]
        val idx = (rotationCursor.getAndIncrement() and Int.MAX_VALUE) % keys.size
        return keys[idx]
    }

    /** Keys other than [picked], for failover order on retry. */
    fun alternateKeys(picked: String?): List<String> =
        jevApiKeys.value.filter { it != picked }

    fun effectiveBaseUrl(): String =
        jevBaseUrl.value.ifBlank { DEFAULT_JEV_BASE_URL }

    companion object {
        const val DEFAULT_JEV_MODEL = "jev-latest"
        const val DEFAULT_JEV_BASE_URL = "https://api.typesafe.ai"
        const val MAX_KEYS = 20
    }
}

private fun decodeJevKeys(raw: String?, json: Json, crypto: JevKeyCrypto): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return try {
        val decrypted = crypto.decrypt(raw)
        json.decodeFromString<List<String>>(decrypted)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(JevPreferenceStore.MAX_KEYS)
    } catch (_: Exception) {
        emptyList()
    }
}
