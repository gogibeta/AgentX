package com.newoether.agora.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Browser credential vault: site logins (site, username, password/TOTP secret)
 * stored in Android Keystore-backed [EncryptedSharedPreferences] (AES-256-GCM
 * values, AES-256-SIV key names, non-exportable master key).
 *
 * Surrogate contract (spec §1.3.5): the agent NEVER sees real values. The model
 * only ever sees a `cred_id` placeholder; the browser tool layer resolves it
 * here and types the secret via the trusted `Input.insertText` CDP path.
 *
 * Hard guarantees:
 * - [getCredential]/[resolveSecret] results live ONLY in memory — never
 *   persisted, never logged, never placed in diagnostics or model context.
 * - [CredentialMeta] (what the UI lists) carries site + username only.
 * - Every use emits an audit event via [DebugLog.event] with (site, field
 *   type, timestamp) — NEVER the secret value.
 * - Fail-closed: if the Keystore/encrypted prefs cannot be opened, every
 *   operation fails and logs; nothing is ever written in plaintext.
 *
 * Thread-safe: all storage access is [Mutex]-guarded and runs on
 * [Dispatchers.IO]; construction does no I/O (lazy open + background load),
 * so it is safe to create on the main thread (e.g. inside SettingsManager).
 */
class CredentialVault(appContext: Context) {

    private val context: Context = appContext.applicationContext
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    /** Lazy so construction never touches disk/Keystore (main-thread safe). */
    private val prefsResult: Result<SharedPreferences> by lazy { runCatching { openVault() } }

    private val _credentials = MutableStateFlow<List<CredentialMeta>>(emptyList())

    /** Safe-to-display metadata (site + username only). Observed by Settings UI. */
    val credentials: StateFlow<List<CredentialMeta>> = _credentials.asStateFlow()

    init {
        scope.launch { refresh() }
    }

    // ── Public API ────────────────────────────────────────────────

    /**
     * Store a new credential. Returns the `cred_id` surrogate placeholder the
     * model may reference, or null when validation fails or the vault is
     * unavailable (fail-closed).
     */
    suspend fun storeCredential(site: String, username: String, secret: String): String? =
        withContext(Dispatchers.IO) {
            val cleanSite = site.trim()
            if (cleanSite.isEmpty() || secret.isEmpty()) return@withContext null
            val prefs = prefsOrNull() ?: return@withContext null
            val credId = newCredId()
            val stored = StoredCredential(
                site = cleanSite,
                username = username.trim(),
                secret = secret,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            )
            mutex.withLock {
                prefs.edit().putString(keyFor(credId), json.encodeToString(stored)).apply()
                refreshLocked(prefs)
            }
            audit("store", credId, cleanSite, "secret")
            credId
        }

    /**
     * Update site/username, and the secret when [newSecret] is non-null and
     * non-empty (blank keeps the existing secret). Returns false when the id
     * is unknown or the vault is unavailable.
     */
    suspend fun updateCredential(
        credId: String,
        site: String,
        username: String,
        newSecret: String?,
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanSite = site.trim()
        if (cleanSite.isEmpty()) return@withContext false
        val prefs = prefsOrNull() ?: return@withContext false
        val updated = mutex.withLock {
            val raw = prefs.getString(keyFor(credId), null) ?: return@withLock false
            val existing = runCatching { json.decodeFromString<StoredCredential>(raw) }.getOrNull()
                ?: return@withLock false
            val merged = existing.copy(
                site = cleanSite,
                username = username.trim(),
                secret = if (!newSecret.isNullOrEmpty()) newSecret else existing.secret,
                updatedAt = System.currentTimeMillis(),
            )
            prefs.edit().putString(keyFor(credId), json.encodeToString(merged)).apply()
            refreshLocked(prefs)
            true
        }
        if (updated) audit("update", credId, cleanSite, "secret")
        updated
    }

    /**
     * Resolve a `cred_id` to its secret — IN MEMORY ONLY. The returned
     * [Credential.secret] must never be persisted, logged, or sent anywhere
     * except the trusted `Input.insertText` path; call [Credential.clear] in a
     * `finally` block when done.
     *
     * [fieldType] describes the field being filled ("password", "totp",
     * "username") and is recorded in the audit log — never the value.
     */
    suspend fun getCredential(credId: String, fieldType: String = "secret"): Credential? =
        withContext(Dispatchers.IO) {
            val prefs = prefsOrNull() ?: return@withContext null
            val raw = mutex.withLock { prefs.getString(keyFor(credId), null) }
                ?: return@withContext null
            val stored = runCatching { json.decodeFromString<StoredCredential>(raw) }.getOrNull()
                ?: return@withContext null
            audit("resolve", credId, stored.site, fieldType)
            Credential(
                credId = credId,
                site = stored.site,
                username = stored.username,
                secret = stored.secret.toCharArray(),
            )
        }

    /**
     * Minimal resolver for the browser tool layer (`browser_fill` with a
     * `cred_id`): `suspend (credId: String, fieldType: String) -> CharArray?`.
     * The caller MUST zero the returned array (`fill('\u0000')`) in a `finally`
     * block immediately after typing it via `Input.insertText`, and MUST NOT
     * log it, include it in tool results, or let it reach model context.
     */
    suspend fun resolveSecret(credId: String, fieldType: String = "secret"): CharArray? =
        getCredential(credId, fieldType)?.secret

    /** Delete a credential by id. */
    suspend fun deleteCredential(credId: String): Boolean = withContext(Dispatchers.IO) {
        val prefs = prefsOrNull() ?: return@withContext false
        var site = ""
        mutex.withLock {
            site = readStoredLocked(prefs, credId)?.site.orEmpty()
            prefs.edit().remove(keyFor(credId)).apply()
            refreshLocked(prefs)
        }
        audit("delete", credId, site, "secret")
        true
    }

    /**
     * Current metadata snapshot: site + username only, NEVER secrets.
     * (Reactive UI should collect [credentials] instead.)
     */
    fun listCredentials(): List<CredentialMeta> = _credentials.value

    // ── Internals ─────────────────────────────────────────────────

    private fun openVault(): SharedPreferences {
        // security-crypto 1.0.0 API: MasterKeys (plural) + (fileName, alias, context) order.
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            PREFS_NAME,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** Fail-closed accessor: null when the vault cannot be opened. Never logs values. */
    private fun prefsOrNull(): SharedPreferences? =
        prefsResult.getOrElse { e ->
            DebugLog.e(TAG, "credential vault unavailable", e)
            null
        }

    private suspend fun refresh() {
        val prefs = prefsOrNull() ?: return
        mutex.withLock { refreshLocked(prefs) }
    }

    private fun refreshLocked(prefs: SharedPreferences) {
        _credentials.value = prefs.all.keys
            .filter { it.startsWith(KEY_PREFIX) }
            .mapNotNull { key -> readStoredLocked(prefs, key.removePrefix(KEY_PREFIX))?.toMeta(key) }
            .sortedBy { it.site.lowercase() }
    }

    private fun readStoredLocked(prefs: SharedPreferences, credId: String): StoredCredential? {
        val raw = prefs.getString(keyFor(credId), null) ?: return null
        return runCatching { json.decodeFromString<StoredCredential>(raw) }.getOrNull()
    }

    private fun StoredCredential.toMeta(prefsKey: String): CredentialMeta =
        CredentialMeta(
            credId = prefsKey.removePrefix(KEY_PREFIX),
            site = site,
            username = username,
        )

    /**
     * Audit event: (site, field type, timestamp) — NEVER the secret value.
     * Goes to the diagnostic log via DebugLog.event (structured fields).
     */
    private fun audit(event: String, credId: String, site: String, fieldType: String) {
        DebugLog.event(
            TAG,
            mapOf(
                "event" to "vault.$event",
                "cred_id" to credId,
                "site" to site,
                "field" to fieldType,
                "ts" to System.currentTimeMillis().toString(),
            ),
            "credential vault $event",
        )
    }

    private fun keyFor(credId: String): String = KEY_PREFIX + credId

    private fun newCredId(): String =
        CRED_ID_PREFIX + UUID.randomUUID().toString().replace("-", "").take(16)

    companion object {
        private const val TAG = "CredentialVault"
        private const val PREFS_NAME = "credential_vault"
        private const val KEY_PREFIX = "cred:"
        private const val CRED_ID_PREFIX = "cred_"
    }
}

/** Encrypted-at-rest form. Never constructed outside the vault. */
@Serializable
private data class StoredCredential(
    val site: String,
    val username: String,
    val secret: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * In-memory resolved credential. The [secret] array must be zeroed with
 * [clear] (ideally in `finally`) as soon as it has been typed; it must never
 * be persisted, logged, or forwarded to model context.
 */
data class Credential(
    val credId: String,
    val site: String,
    val username: String,
    val secret: CharArray,
) {
    /** Zero the secret chars. Call in a finally block when done filling. */
    fun clear() {
        secret.fill('\u0000')
    }
}

/** Safe-to-display metadata: site + username only — NEVER the secret. */
data class CredentialMeta(
    val credId: String,
    val site: String,
    val username: String,
)
