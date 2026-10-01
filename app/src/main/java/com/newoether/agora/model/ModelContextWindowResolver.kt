package com.newoether.agora.model

/**
 * Resolves the effective provider-visible token budget for one model.
 *
 * Priority: per-conversation override first, then the model's own configured window,
 * then the global setting. Every level passes through [ContextBudget.normalize] so
 * legacy and out-of-range values can never produce a nonsensical budget.
 */
object ModelContextWindowResolver {
    fun resolve(
        canonicalModelId: String?,
        modelWindows: Map<String, Int>,
        conversationOverride: Int?,
        globalWindow: Int,
    ): Int {
        if (conversationOverride != null && conversationOverride > 0) {
            return ContextBudget.normalize(conversationOverride)
        }
        val perModel = lookupModelWindow(canonicalModelId, modelWindows)
        if (perModel != null) return ContextBudget.normalize(perModel)
        return ContextBudget.normalize(globalWindow)
    }

    /**
     * Finds a stored window for [modelId]. Tries the exact key first, then falls back to
     * matching on the model-name suffix so entries survive provider renames and
     * canonicalization differences between the settings UI and the generation path.
     */
    fun lookupModelWindow(modelId: String?, modelWindows: Map<String, Int>): Int? {
        val id = modelId?.takeIf(String::isNotBlank) ?: return null
        modelWindows[id]?.takeIf { it > 0 }?.let { return it }
        val suffix = id.substringAfterLast(':').takeIf(String::isNotBlank) ?: return null
        return modelWindows.entries
            .firstOrNull { (key, tokens) ->
                tokens > 0 && key.substringAfterLast(':') == suffix
            }?.value
    }
}
