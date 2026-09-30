package com.newoether.agora.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure string-level tests for the physics/siunitx normalizer (no Android needed). */
class LatexPhysicsTest {

    @Test
    fun testSiunitx() {
        assertEquals(
            "9.8\\,\\mathrm{m/s^2}",
            normalizePhysics("\\SI{9.8}{\\meter\\per\\second\\squared}"),
        )
        assertEquals("5\\,\\mathrm{kg}", normalizePhysics("\\qty{5}{\\kilo\\gram}"))
        assertEquals("\\mathrm{kg}", normalizePhysics("\\si{\\kilo\\gram}"))
        assertEquals("\\mathrm{3.0 m/s}", normalizePhysics("\\pu{3.0 m/s}"))
        assertEquals("\\mathrm{1.602e-19}", normalizePhysics("\\num{1.602e-19}"))
        assertEquals("30^{\\circ}", normalizePhysics("\\ang{30}"))
    }

    @Test
    fun testBraket() {
        assertEquals("\\langle \\psi |", normalizePhysics("\\bra{\\psi}"))
        assertEquals("| \\phi \\rangle", normalizePhysics("\\ket{\\phi}"))
        assertEquals("\\langle a|b \\rangle", normalizePhysics("\\braket{a|b}"))
    }

    @Test
    fun testDerivatives() {
        assertEquals("\\frac{d x}{d t}", normalizePhysics("\\dv{x}{t}"))
        assertEquals(
            "\\frac{\\partial f}{\\partial x}",
            normalizePhysics("\\pdv{f}{x}"),
        )
        assertEquals("\\frac{d}{d x}", normalizePhysics("\\dv{x}"))
    }

    @Test
    fun testAbsNorm() {
        assertEquals("|x|", normalizePhysics("\\abs{x}"))
        assertEquals("\\|v\\|", normalizePhysics("\\norm{v}"))
        assertEquals("|\\frac{a}{b}|", normalizePhysics("\\abs*{\\frac{a}{b}}"))
    }

    @Test
    fun testOperatorsAndFonts() {
        assertEquals("\\frac{1}{2}", normalizePhysics("\\tfrac{1}{2}"))
        assertEquals("\\mathrm{erf}(x)", normalizePhysics("\\operatorname{erf}(x)"))
        assertEquals("\\mathbf{F}", normalizePhysics("\\boldsymbol{F}"))
        assertEquals("\\mathbf{v}", normalizePhysics("\\bm{v}"))
        assertEquals("\\mathcal{F}", normalizePhysics("\\mathscr{F}"))
        assertEquals("link", normalizePhysics("\\href{https://a.com}{link}"))
    }

    @Test
    fun testArrowsAndMisc() {
        assertEquals("P \\Rightarrow Q", normalizePhysics("P \\implies Q"))
        assertEquals("\\overset{heat}{\\rightarrow}", normalizePhysics("\\xrightarrow{heat}"))
        assertEquals("a : B", normalizePhysics("a \\colon B"))
        assertEquals("a\\times b", normalizePhysics("a\\cross b"))
        assertFalse(normalizePhysics("x = 1 \\tag{1}").contains("\\tag"))
        assertFalse(normalizePhysics("\\begin{equation} x \\end{equation}").contains("equation"))
    }

    @Test
    fun testModAndCancel() {
        assertEquals("a \\equiv b (\\mathrm{mod}\\ n)", normalizePhysics("a \\equiv b \\pmod{n}"))
        assertEquals("x", normalizePhysics("\\cancel{x}"))
    }

    @Test
    fun testConditionedChemArrow() {
        val out = normalizeChemistry("\\ce{A ->[heat][cat] B}")
        assertTrue(out.contains("\\rightarrow"))
        assertTrue(out.contains("heat"))
        assertTrue(out.contains("cat"))
        assertFalse(out.contains("->["))
    }

    @Test
    fun testExtractBraceArg() {
        assertEquals("a{b}c" to 7, extractBraceArg("{a{b}c}rest", 0))
        assertEquals(null, extractBraceArg("{unclosed", 0))
        assertEquals(null, extractBraceArg("nope", 0))
    }
}
