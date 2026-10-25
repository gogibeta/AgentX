package com.newoether.agora.model

/**
 * Resolves the effective provider-visible token budget for one model.
 *
 * Priority: per-conversation override first, then the model's own configured window,
 * then the global setting. Every level passes through [ContextBudget.normalize] so
 * legacy and out-of-range values can never produce a nonsensical budget.
 * [localModelNCtx], when set, is a hard physical cap: the on-device engine cannot
 * address more context than it was loaded with, no matter what the budget says.
 */
object ModelContextWindowResolver {
    fun resolve(
        canonicalModelId: String?,
        modelWindows: Map<String, Int>,
        conversationOverride: Int?,
        globalWindow: Int,
        localModelNCtx: Int? = null,
    ): Int {
        val resolved = if (conversationOverride != null && conversationOverride > 0) {
            ContextBudget.normalize(conversationOverride)
        } else {
            val perModel = lookupModelWindow(canonicalModelId, modelWindows)
            if (perModel != null) {
                ContextBudget.normalize(perModel)
            } else {
                ContextBudget.normalize(globalWindow)
            }
        }
        // Local nCtx is a hard device cap (what the model was loaded with), not a
        // UI budget: apply it raw so a small on-device context isn't inflated to
        // MIN_TOKENS, and a generous one never inflates the resolved budget.
        return if (localModelNCtx != null && localModelNCtx > 0) {
            minOf(resolved, localModelNCtx)
        } else {
            resolved
        }
    }

    /**
     * Finds a stored window for [modelId]. Tries the exact key first, then falls back to
     * matching on the model-name suffix so entries survive provider renames and
     * canonicalization differences between the settings UI and the generation path.
     * The suffix fallback only applies when exactly one stored entry matches —
     * with two providers exposing the same model name, guessing would silently
     * apply the wrong budget, so no fallback is used (fail closed to global).
     */
    fun lookupModelWindow(modelId: String?, modelWindows: Map<String, Int>): Int? {
        val id = modelId?.takeIf(String::isNotBlank) ?: return null
        modelWindows[id]?.takeIf { it > 0 }?.let { return it }
        val suffix = id.substringAfterLast(':').takeIf(String::isNotBlank) ?: return null
        val matches = modelWindows.entries
            .filter { (key, tokens) -> tokens > 0 && key.substringAfterLast(':') == suffix }
        return matches.singleOrNull()?.value
    }
}
