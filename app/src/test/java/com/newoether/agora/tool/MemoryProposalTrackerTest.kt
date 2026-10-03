package com.newoether.agora.tool

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class MemoryProposalTrackerTest {

    @Before
    fun setUp() = MemoryProposalTracker.resetForTest()

    @Test
    fun `acceptance rate is zero with no proposals`() {
        assertEquals(0.0, MemoryProposalTracker.acceptanceRate(), 0.001)
    }

    @Test
    fun `marking a proposal accepted updates the rate`() {
        MemoryProposalTracker.track("prop-0-0", "fact one")
        MemoryProposalTracker.track("prop-0-1", "fact two")
        MemoryProposalTracker.markAccepted("prop-0-0")
        assertEquals(0.5, MemoryProposalTracker.acceptanceRate(), 0.001)
        val (accepted, total) = MemoryProposalTracker.counts()
        assertEquals(1, accepted)
        assertEquals(2, total)
    }

    @Test
    fun `proposal ids are found in memory text`() {
        MemoryProposalTracker.track("prop-1-3", "fact")
        MemoryProposalTracker.markAcceptedInText("Committed from prop-1-3: durable fact.")
        assertEquals(1.0, MemoryProposalTracker.acceptanceRate(), 0.001)
    }

    @Test
    fun `unknown ids are ignored`() {
        MemoryProposalTracker.markAccepted("prop-9-9")
        assertEquals(0.0, MemoryProposalTracker.acceptanceRate(), 0.001)
    }
}
