package com.newoether.agora.browser

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val BROWSER_ENABLED = booleanPreferencesKey("browser_enabled")
private val BROWSER_BACKEND_MODE = stringPreferencesKey("browser_backend_mode")
private val BROWSER_TUNNEL_URL = stringPreferencesKey("browser_tunnel_url")
private val BROWSER_TUNNEL_TOKEN = stringPreferencesKey("browser_tunnel_token")
private val BROWSER_TUNNEL_VALIDATION_JSON = stringPreferencesKey("browser_tunnel_validation_json")
private val BROWSER_TUNNEL_VALIDATED_AT = longPreferencesKey("browser_tunnel_validated_at")

/** Reachability validation outcome for the user-supplied tunnel URL. */
@Serializable
enum class TunnelValidationState {
    NOT_VALIDATED,
    VALID,
    INVALID,
}

@Serializable
data class TunnelValidation(
    val state: TunnelValidationState = TunnelValidationState.NOT_VALIDATED,
    /** Machine-readable reason, e.g. "http_403", "timeout", "not_https". Never contains the token. */
    val error: String = "",
)

/**
 * Browser backend settings — own DataStore slice following the [JevPreferenceStore]
 * pattern, so the 800-line-capped SettingsManager/SettingsRepository stay untouched.
 *
 * - [browserEnabled]: master toggle — the browser tools are only offered when on.
 * - [backendMode]: LOCAL (default) or TUNNEL (§1.3.0).
 * - [tunnelUrl]: user-pasted CDP-over-HTTPS endpoint. The app ships no default.
 * - Client token: encrypted at rest via [SecretCrypto] (Android Keystore-backed),
 *   never logged, never placed in diagnostic output.
 * - [tunnelValidation]: last reachability check (`GET /json/version` → 200) plus
 *   [tunnelValidatedAtMillis] timestamp, written by the settings UI after validating.
 *
 * §1.3.0 rule: TUNNEL with no tunnel URL falls back to [BrowserBackendMode.LOCAL]
 * (see [effectiveMode]); tunnel mode cannot be meaningfully enabled without a URL.
 */
class BrowserPreferenceStore(
    private val store: DataStore<Preferences>,
    private val json: Json,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val browserEnabled: StateFlow<Boolean> = store.data
        .map { it[BROWSER_ENABLED] ?: false }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val backendMode: StateFlow<BrowserBackendMode> = store.data
        .map { BrowserBackendMode.fromPersisted(it[BROWSER_BACKEND_MODE]) }
        .stateIn(scope, SharingStarted.Eagerly, BrowserBackendMode.LOCAL)

    /** Trimmed user-supplied tunnel URL. Blank = not provided. */
    val tunnelUrl: StateFlow<String> = store.data
        .map { (it[BROWSER_TUNNEL_URL] ?: "").trim() }
        .stateIn(scope, SharingStarted.Eagerly, "")

    /** Decrypted client token. Blank = not set. Never log this value. */
    val tunnelClientToken: StateFlow<String> = store.data
        .map { raw -> if (raw[BROWSER_TUNNEL_TOKEN].isNullOrBlank()) "" else SecretCrypto.decrypt(raw[BROWSER_TUNNEL_TOKEN]!!) }
        .stateIn(scope, SharingStarted.Eagerly, "")

    val tunnelValidation: StateFlow<TunnelValidation> = store.data
        .map { decodeValidation(it[BROWSER_TUNNEL_VALIDATION_JSON], json) }
        .stateIn(scope, SharingStarted.Eagerly, TunnelValidation())

    val tunnelValidatedAtMillis: StateFlow<Long> = store.data
        .map { it[BROWSER_TUNNEL_VALIDATED_AT] ?: 0L }
        .stateIn(scope, SharingStarted.Eagerly, 0L)

    /**
     * The backend that will actually be used: TUNNEL requires a non-blank tunnel
     * URL, otherwise we fall back to LOCAL (§1.3.0).
     */
    fun effectiveMode(): BrowserBackendMode {
        val mode = backendMode.value
        return if (mode == BrowserBackendMode.TUNNEL && tunnelUrl.value.isBlank()) {
            BrowserBackendMode.LOCAL
        } else {
            mode
        }
    }

    fun setBrowserEnabled(enabled: Boolean) = scope.launch {
        store.edit { it[BROWSER_ENABLED] = enabled }
    }

    fun setBackendMode(mode: BrowserBackendMode) = scope.launch {
        store.edit { it[BROWSER_BACKEND_MODE] = mode.persisted }
    }

    fun setTunnelUrl(url: String) = scope.launch {
        // Normalize: trim whitespace AND trailing slashes. connectTunnel()
        // builds "$url/json/version" by string concat — a trailing slash would
        // produce "//json/version" → 404 while Validate (OkHttp path builder)
        // still passes. This was the "validate ok, connect fails" bug.
        val trimmed = url.trim().trimEnd('/')
        store.edit { prefs ->
            if (trimmed.isEmpty()) prefs.remove(BROWSER_TUNNEL_URL)
            else prefs[BROWSER_TUNNEL_URL] = trimmed
            // URL changed → previous validation no longer applies.
            prefs.remove(BROWSER_TUNNEL_VALIDATION_JSON)
            prefs.remove(BROWSER_TUNNEL_VALIDATED_AT)
        }
    }

    /** Stores the token encrypted via [SecretCrypto]; blank clears it. */
    fun setTunnelClientToken(token: String) = scope.launch {
        val trimmed = token.trim()
        store.edit { prefs ->
            if (trimmed.isEmpty()) prefs.remove(BROWSER_TUNNEL_TOKEN)
            else prefs[BROWSER_TUNNEL_TOKEN] = SecretCrypto.encrypt(trimmed)
        }
    }

    /** Records a reachability validation outcome; called by the settings UI after probing. */
    fun markTunnelValidation(state: TunnelValidationState, error: String = "") = scope.launch {
        val validation = TunnelValidation(state, error.take(120))
        store.edit { prefs ->
            prefs[BROWSER_TUNNEL_VALIDATION_JSON] = json.encodeToString(validation)
            prefs[BROWSER_TUNNEL_VALIDATED_AT] = System.currentTimeMillis()
        }
    }

    fun clearTunnelValidation() = scope.launch {
        store.edit { prefs ->
            prefs.remove(BROWSER_TUNNEL_VALIDATION_JSON)
            prefs.remove(BROWSER_TUNNEL_VALIDATED_AT)
        }
    }
}

private fun decodeValidation(raw: String?, json: Json): TunnelValidation {
    if (raw.isNullOrBlank()) return TunnelValidation()
    return try {
        json.decodeFromString<TunnelValidation>(raw)
    } catch (_: Exception) {
        TunnelValidation()
    }
}
