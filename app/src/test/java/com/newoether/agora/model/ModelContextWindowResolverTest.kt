package com.newoether.agora.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelContextWindowResolverTest {
    @Test
    fun conversationOverrideWinsOverModelAndGlobal() {
        assertEquals(
            32_768,
            ModelContextWindowResolver.resolve(
                canonicalModelId = "OpenAI:gpt-4",
                modelWindows = mapOf("OpenAI:gpt-4" to 131_072),
                conversationOverride = 32_768,
                globalWindow = 262_144,
            ),
        )
    }

    @Test
    fun perModelWindowBeatsGlobalDefault() {
        assertEquals(
            131_072,
            ModelContextWindowResolver.resolve(
                canonicalModelId = "OpenAI:gpt-4",
                modelWindows = mapOf("OpenAI:gpt-4" to 131_072),
                conversationOverride = null,
                globalWindow = 262_144,
            ),
        )
    }

    @Test
    fun fallsBackToGlobalWhenModelHasNoEntry() {
        assertEquals(
            262_144,
            ModelContextWindowResolver.resolve(
                canonicalModelId = "OpenAI:gpt-4",
                modelWindows = mapOf("Anthropic:claude" to 65_536),
                conversationOverride = null,
                globalWindow = 262_144,
            ),
        )
    }

    @Test
    fun suffixFallbackSurvivesProviderRename() {
        // Stored under the old provider id; generation asks with the canonical one.
        assertEquals(
            65_536,
            ModelContextWindowResolver.resolve(
                canonicalModelId = "new-id:claude-3",
                modelWindows = mapOf("legacy-name:claude-3" to 65_536),
                conversationOverride = null,
                globalWindow = 262_144,
            ),
        )
    }

    @Test
    fun ambiguousSuffixFallsBackToGlobal() {
        // Two providers expose the same model name: guessing would apply the wrong
        // budget, so the lookup fails closed to the global window.
        assertEquals(
            262_144,
            ModelContextWindowResolver.resolve(
                canonicalModelId = "third:claude-3",
                modelWindows = mapOf("a:claude-3" to 65_536, "b:claude-3" to 131_072),
                conversationOverride = null,
                globalWindow = 262_144,
            ),
        )
    }

    @Test
    fun exactKeyBeatsSuffixFallback() {
        assertEquals(
            8_192,
            ModelContextWindowResolver.resolve(
                canonicalModelId = "new-id:claude-3",
                modelWindows = mapOf(
                    "legacy-name:claude-3" to 65_536,
                    "new-id:claude-3" to 8_192,
                ),
                conversationOverride = null,
                globalWindow = 262_144,
            ),
        )
    }

    @Test
    fun legacySmallValuesAreMigratedAtEveryLevel() {
        // Pre-token-budget builds stored logical message counts (<=100).
        assertEquals(
            20 * 1_024,
            ModelContextWindowResolver.resolve(
                canonicalModelId = "OpenAI:gpt-4",
                modelWindows = mapOf("OpenAI:gpt-4" to 20),
                conversationOverride = null,
                globalWindow = 262_144,
            ),
        )
    }

    @Test
    fun outOfRangeValuesAreClamped() {
        assertEquals(
            ContextBudget.MAX_TOKENS,
            ModelContextWindowResolver.resolve(
                canonicalModelId = "OpenAI:gpt-4",
                modelWindows = mapOf("OpenAI:gpt-4" to Int.MAX_VALUE),
                conversationOverride = null,
                globalWindow = 262_144,
            ),
        )
        assertEquals(
            ContextBudget.MIN_TOKENS,
            ModelContextWindowResolver.resolve(
                canonicalModelId = null,
                modelWindows = emptyMap(),
                conversationOverride = null,
                globalWindow = -5,
            ),
        )
    }

    @Test
    fun blankModelIdFallsBackToGlobal() {
        assertEquals(
            262_144,
            ModelContextWindowResolver.resolve(
                canonicalModelId = "   ",
                modelWindows = mapOf("OpenAI:gpt-4" to 131_072),
                conversationOverride = null,
                globalWindow = 262_144,
            ),
        )
    }

    @Test
    fun lookupIgnoresNonPositiveStoredValues() {
        assertNull(
            ModelContextWindowResolver.lookupModelWindow(
                "OpenAI:gpt-4",
                mapOf("OpenAI:gpt-4" to 0),
            ),
        )
        assertNull(ModelContextWindowResolver.lookupModelWindow(null, mapOf("a:b" to 1)))
    }

    @Test
    fun localModelNCtxCapsEveryLevel() {
        // On-device engine cannot address more than it was loaded with.
        assertEquals(
            2_048,
            ModelContextWindowResolver.resolve(
                canonicalModelId = "Local:llama",
                modelWindows = mapOf("Local:llama" to 131_072),
                conversationOverride = 65_536,
                globalWindow = 262_144,
                localModelNCtx = 2_048,
            ),
        )
        // A generous nCtx never inflates the budget.
        assertEquals(
            32_768,
            ModelContextWindowResolver.resolve(
                canonicalModelId = "Local:llama",
                modelWindows = mapOf("Local:llama" to 32_768),
                conversationOverride = null,
                globalWindow = 262_144,
                localModelNCtx = 131_072,
            ),
        )
    }
}
