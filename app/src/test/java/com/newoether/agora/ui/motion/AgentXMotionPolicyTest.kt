package com.newoether.agora.ui.motion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentXMotionPolicyTest {
    @Test
    fun fullMotionRemainsAvailableWhenNeitherPreferenceRequestsReduction() {
        val policy = resolveAgentXMotionPolicy(
            appReduceMotion = false,
            systemAnimationsDisabled = false,
        )

        assertFalse(policy.reduceMotion)
        assertTrue(policy.allowContinuousMotion)
        assertTrue(policy.allowSpatialTransitions)
        assertTrue(policy.allowProgrammaticScrollMotion)
    }

    @Test
    fun appPreferenceDisablesMotionSensitiveCapabilities() {
        val policy = resolveAgentXMotionPolicy(
            appReduceMotion = true,
            systemAnimationsDisabled = false,
        )

        assertReduced(policy)
    }

    @Test
    fun systemRemoveAnimationsAlsoDisablesMotionSensitiveCapabilities() {
        val policy = resolveAgentXMotionPolicy(
            appReduceMotion = false,
            systemAnimationsDisabled = true,
        )

        assertReduced(policy)
    }

    private fun assertReduced(policy: AgentXMotionPolicy) {
        assertTrue(policy.reduceMotion)
        assertFalse(policy.allowContinuousMotion)
        assertFalse(policy.allowSpatialTransitions)
        assertFalse(policy.allowProgrammaticScrollMotion)
    }
}
