package com.newoether.agora.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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

/**
 * Agent-mode preferences (mode, workspace folder, ensemble models), following the
 * [SettingsModelPreferenceStore] pattern: owns its slice of the shared settings
 * DataStore so the 800-line-capped [SettingsManager]/[SettingsRepository] stay
 * untouched apart from one accessor line each.
 *
 * Process-lifetime singleton like its owner; the internal scope only feeds the
 * hot StateFlows and setter launches.
 */
class SettingsAgentPreferenceStore(
    private val store: DataStore<Preferences>,
    private val json: Json,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** "off" (chat as today), "plan" (read-only tools), "build" (all tools + artifacts). */
    val agentMode: StateFlow<String> = store.data
        .map { normalizeAgentMode(it[AGENT_MODE]) }
        .stateIn(scope, SharingStarted.Eagerly, "off")

    /** Persisted SAF tree URI (from OpenDocumentTree) for agent artifacts. Empty = unset. */
    val agentWorkspaceUri: StateFlow<String> = store.data
        .map { it[AGENT_WORKSPACE_URI] ?: "" }
        .stateIn(scope, SharingStarted.Eagerly, "")

    /** Ensemble models "Provider:modelId", max 5, in preference order. */
    val agentModels: StateFlow<List<String>> = store.data
        .map { decodeAgentModels(it[AGENT_MODELS_JSON]) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Agent environment variables (name -> secret). Encrypted at rest. */
    val agentEnv: StateFlow<Map<String, String>> = store.data
        .map { decodeAgentEnv(it[AGENT_ENV_JSON], json) }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /**
     * v2.4 auto-compact master switch. Default ON: every [autoCompactIntervalTurns]
     * tool turns the engine asks Jev to drop low-value tool outputs from the model's
     * view (Room history is never touched). Visible notice on every compaction.
     */
    val autoCompactEnabled: StateFlow<Boolean> = store.data
        .map { it[AGENT_AUTO_COMPACT_ENABLED] ?: true }
        .stateIn(scope, SharingStarted.Eagerly, true)

    /** Tool turns between auto-compact checkpoints (default 25). */
    val autoCompactIntervalTurns: StateFlow<Int> = store.data
        .map { (it[AGENT_AUTO_COMPACT_INTERVAL_TURNS] ?: 25).coerceIn(5, 100) }
        .stateIn(scope, SharingStarted.Eagerly, 25)

    fun setAutoCompactEnabled(enabled: Boolean) = scope.launch {
        store.edit { it[AGENT_AUTO_COMPACT_ENABLED] = enabled }
    }

    fun setAutoCompactIntervalTurns(turns: Int) = scope.launch {
        store.edit { it[AGENT_AUTO_COMPACT_INTERVAL_TURNS] = turns.coerceIn(5, 100) }
    }

    fun setAgentMode(mode: String) = scope.launch {
        store.edit { it[AGENT_MODE] = normalizeAgentMode(mode) }
    }

    fun setAgentWorkspaceUri(uri: String) = scope.launch {
        store.edit { it[AGENT_WORKSPACE_URI] = uri }
    }

    fun setAgentModels(models: List<String>) = scope.launch {
        val cleaned = models.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(5)
        store.edit { it[AGENT_MODELS_JSON] = json.encodeToString(cleaned) }
    }

    fun setAgentEnvVar(name: String, value: String) = scope.launch {
        val key = name.trim()
        if (!isValidAgentEnvName(key)) return@launch
        store.edit { prefs ->
            val current = decodeAgentEnv(prefs[AGENT_ENV_JSON], json).toMutableMap()
            if (value.isBlank()) current.remove(key) else current[key] = value
            prefs[AGENT_ENV_JSON] = encodeAgentEnv(current, json)
        }
    }

    fun removeAgentEnvVar(name: String) = scope.launch {
        store.edit { prefs ->
            val current = decodeAgentEnv(prefs[AGENT_ENV_JSON], json).toMutableMap()
            if (current.remove(name.trim()) != null) {
                prefs[AGENT_ENV_JSON] = encodeAgentEnv(current, json)
            }
        }
    }
}
