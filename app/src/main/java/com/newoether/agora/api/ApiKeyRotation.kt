package com.newoether.agora.api

import com.newoether.agora.data.ApiKeyEntry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Multi-key rotation for provider API keys (Claude Code / OpenCode style).
 *
 * - [orderedKeys]: active key first, then the provider's other non-blank keys
 *   (deduped). Pure — unit-tested.
 * - [pickForRequest]: round-robin across fresh requests so parallel work
 *   (ensemble fan-out, deep-research batches) spreads over keys instead of
 *   hammering one rate limit.
 * - [keyForAttempt]: failover order for in-request retries (attempt 1 = picked
 *   key, then alternates round-robin). A dead/rate-limited key auto-moves to
 *   the next without user action.
 */
object ApiKeyRotation {
    private val cursors = ConcurrentHashMap<String, AtomicInteger>()

    fun orderedKeys(
        entries: List<ApiKeyEntry>,
        activeIds: Map<String, String>,
        provider: String,
    ): List<String> {
        val mine = entries.filter { it.provider == provider && it.key.isNotBlank() }
        if (mine.isEmpty()) return emptyList()
        val activeId = activeIds[provider]
        val active = mine.firstOrNull { it.id == activeId }
        return (listOfNotNull(active) + mine.filter { it.id != activeId })
            .map { it.key }
            .distinct()
    }

    fun pickForRequest(
        entries: List<ApiKeyEntry>,
        activeIds: Map<String, String>,
        provider: String,
    ): String? {
        val ordered = orderedKeys(entries, activeIds, provider)
        if (ordered.isEmpty()) return null
        if (ordered.size == 1) return ordered[0]
        val cursor = cursors.getOrPut(provider) { AtomicInteger(0) }
        return ordered[(cursor.getAndIncrement() and Int.MAX_VALUE) % ordered.size]
    }

    fun keyForAttempt(
        pickedKey: String,
        alternates: List<String>,
        attempt: Int,
    ): String {
        if (attempt <= 1 || alternates.isEmpty()) return pickedKey
        return alternates[(attempt - 2) % alternates.size]
    }

    /** Alternates for [pickedKey] in failover order (picked key excluded). */
    fun alternatesFor(
        entries: List<ApiKeyEntry>,
        activeIds: Map<String, String>,
        provider: String,
        pickedKey: String?,
    ): List<String> =
        orderedKeys(entries, activeIds, provider).filter { it != pickedKey }
}
