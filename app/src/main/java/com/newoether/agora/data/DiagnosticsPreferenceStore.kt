package com.newoether.agora.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val DIAGNOSTICS_DEBUG_OVERLAY_ENABLED = booleanPreferencesKey("diagnostics_debug_overlay_enabled")

/**
 * Diagnostics settings — own DataStore slice following the [JevPreferenceStore]
 * pattern, so the 800-line-capped [SettingsManager]/[SettingsRepository] stay
 * untouched apart from one accessor line each.
 *
 * - [debugOverlayEnabled]: floating debug overlay during agent runs — live
 *   tokens/s plus a last-action chip fed by the §6.1 structured event pipeline.
 *   Off by default; zero chat pollution when off.
 */
class DiagnosticsPreferenceStore(
    private val store: DataStore<Preferences>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val debugOverlayEnabled: StateFlow<Boolean> = store.data
        .map { it[DIAGNOSTICS_DEBUG_OVERLAY_ENABLED] ?: false }
        .stateIn(scope, SharingStarted.Eagerly, false)

    fun setDebugOverlayEnabled(enabled: Boolean) = scope.launch {
        store.edit { it[DIAGNOSTICS_DEBUG_OVERLAY_ENABLED] = enabled }
    }
}
