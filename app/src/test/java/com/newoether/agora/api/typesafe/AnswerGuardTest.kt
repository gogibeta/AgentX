package com.newoether.agora.api.typesafe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerGuardTest {

    @Test
    fun cleanForSynthesis_collapsesAndCaps() {
        val cleaned = AnswerGuard.cleanForSynthesis("a   b\tc\n\n\n\nd", maxChars = 100)
        assertEquals("a b c\n\nd", cleaned)
        val long = "x".repeat(200)
        assertEquals(100, AnswerGuard.cleanForSynthesis(long, maxChars = 100).length)
        assertEquals("", AnswerGuard.cleanForSynthesis("   "))
    }

    @Test
    fun hasRawLatexLeak_detectsRemnants() {
        assertTrue(AnswerGuard.hasRawLatexLeak("value \$x + \\frac{a}{b}\$ here"))
        assertTrue(AnswerGuard.hasRawLatexLeak("see ![latex](latex://inline/abc/def)"))
        assertTrue(AnswerGuard.hasRawLatexLeak("balance \\ce{H2} now"))
        assertFalse(AnswerGuard.hasRawLatexLeak("The price is \$5 and \$10 today"))
        assertFalse(AnswerGuard.hasRawLatexLeak("plain answer with 2H2 + O2 equation"))
    }

    @Test
    fun jevDecisions_isConfigured() {
        assertFalse(JevDecisions.isConfigured(null))
        assertFalse(JevDecisions.isConfigured("  "))
        assertTrue(JevDecisions.isConfigured("typesafe_key"))
    }
}
