package com.newoether.agora.social

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val SOCIAL_ENABLED = booleanPreferencesKey("social_enabled")
private val SOCIAL_WORKER_BASE_URL = stringPreferencesKey("social_worker_base_url")
private val SOCIAL_VALIDATED = booleanPreferencesKey("social_worker_validated")
private val SOCIAL_VALIDATED_AT = longPreferencesKey("social_worker_validated_at")
private val SOCIAL_BARE_REALM = booleanPreferencesKey("social_worker_bare_realm")
private val SOCIAL_WORKER_VERSION = stringPreferencesKey("social_worker_version")

/**
 * Social settings — own DataStore slice following the [JevPreferenceStore]
 * pattern, so the 800-line-capped [SettingsManager]/[SettingsRepository] stay
 * untouched apart from one accessor line each.
 *
 * The user pastes their OWN FxEmbed worker base URL in Settings → Social.
 * There is deliberately NO default URL anywhere in the app: reading social
 * content requires a worker the user deploys themselves (free Cloudflare tier).
 * The app ships with the field empty and tools disabled until the user
 * enables them and validates their worker.
 *
 * - [socialEnabled]: master toggle — social tools only run when this is on
 *   AND a worker URL is set.
 * - [socialWorkerBaseUrl]: user-entered base URL, normalized (trimmed, no
 *   trailing slash). Empty = not configured.
 * - [socialValidated]: true when the last Validate hit returned a version.
 * - [socialValidatedAt]: epoch millis of the last successful validation.
 * - [socialBareRealm]: which realm-prefix form validated — true when
 *   `{base}/version` answered (custom domains drop the `/ai/2` prefixes),
 *   false when `{base}/ai/version` answered (default `*.workers.dev` form).
 * - [socialWorkerVersion]: the FxEmbed version string from last validation.
 */
class SocialPreferenceStore(
    private val store: DataStore<Preferences>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val socialEnabled: StateFlow<Boolean> = store.data
        .map { it[SOCIAL_ENABLED] ?: false }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val socialWorkerBaseUrl: StateFlow<String> = store.data
        .map { normalizeBaseUrl(it[SOCIAL_WORKER_BASE_URL].orEmpty()) }
        .stateIn(scope, SharingStarted.Eagerly, "")

    val socialValidated: StateFlow<Boolean> = store.data
        .map { it[SOCIAL_VALIDATED] ?: false }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val socialValidatedAt: StateFlow<Long> = store.data
        .map { it[SOCIAL_VALIDATED_AT] ?: 0L }
        .stateIn(scope, SharingStarted.Eagerly, 0L)

    val socialBareRealm: StateFlow<Boolean> = store.data
        .map { it[SOCIAL_BARE_REALM] ?: false }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val socialWorkerVersion: StateFlow<String> = store.data
        .map { it[SOCIAL_WORKER_VERSION].orEmpty() }
        .stateIn(scope, SharingStarted.Eagerly, "")

    /** True when the toggle is on AND a valid-looking worker URL is set. */
    val socialConfigured: StateFlow<Boolean> = store.data
        .map { (it[SOCIAL_ENABLED] ?: false) && isValidWorkerUrl(it[SOCIAL_WORKER_BASE_URL]) }
        .stateIn(scope, SharingStarted.Eagerly, false)

    fun setSocialEnabled(enabled: Boolean) = scope.launch {
        store.edit { it[SOCIAL_ENABLED] = enabled }
    }

    /**
     * Store the user-entered worker URL. Any change clears the validated flag:
     * the URL must be re-validated before the tools trust it.
     */
    fun setSocialWorkerBaseUrl(url: String) = scope.launch {
        store.edit { prefs ->
            val normalized = normalizeBaseUrl(url)
            if (normalized.isEmpty()) prefs.remove(SOCIAL_WORKER_BASE_URL)
            else prefs[SOCIAL_WORKER_BASE_URL] = normalized
            prefs[SOCIAL_VALIDATED] = false
            prefs[SOCIAL_VALIDATED_AT] = 0L
        }
    }

    /** Record a successful Validate hit: version, prefix form, and timestamp. */
    fun recordValidation(version: String, bareRealm: Boolean) = scope.launch {
        store.edit { prefs ->
            prefs[SOCIAL_VALIDATED] = true
            prefs[SOCIAL_VALIDATED_AT] = System.currentTimeMillis()
            prefs[SOCIAL_BARE_REALM] = bareRealm
            prefs[SOCIAL_WORKER_VERSION] = version
        }
    }

    fun clearValidation() = scope.launch {
        store.edit { prefs ->
            prefs[SOCIAL_VALIDATED] = false
            prefs[SOCIAL_VALIDATED_AT] = 0L
        }
    }

    companion object {
        /**
         * Strict worker-URL gate: https only, no credentials in the URL, no
         * whitespace. The app never talks to a social worker over cleartext.
         */
        fun isValidWorkerUrl(raw: String?): Boolean {
            val url = raw?.trim().orEmpty()
            if (url.isBlank()) return false
            if (url.contains('@') || url.contains(' ') || url.contains('\\')) return false
            return try {
                val parsed = java.net.URI(url)
                parsed.scheme == "https" && !parsed.host.isNullOrBlank() && parsed.userInfo == null
            } catch (_: Exception) {
                false
            }
        }

        /** Trim + drop trailing slashes. Empty in = empty out (no default). */
        fun normalizeBaseUrl(raw: String): String = raw.trim().trimEnd('/')
    }
}
