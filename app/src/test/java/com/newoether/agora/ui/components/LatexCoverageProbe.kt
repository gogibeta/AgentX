package com.newoether.agora.ui.components

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Coverage probe: render a battery of real-world model-output formulas through the
 * FULL pipeline ([normalizeLatexForRender]) and report which still fail. The goal is
 * zero failures — every symbol family (math, physics, chemistry) renders as math.
 * Run with `./gradlew :app:testFdroidDebugUnitTest --tests "*LatexCoverageProbe*"`
 * and read stdout for the FAIL list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LatexCoverageProbe {
    @Before
    fun initLatex() {
        ru.noties.jlatexmath.JLatexMathAndroid.init(ApplicationProvider.getApplicationContext())
    }

    private val battery: List<Pair<String, String>> = listOf(
        // ── core math ──
        "frac" to "\\frac{a}{b}",
        "sqrt" to "\\sqrt{x^2 + y^2}",
        "nthroot" to "\\sqrt[3]{x}",
        "sum" to "\\sum_{i=1}^{n} i = \\frac{n(n+1)}{2}",
        "int" to "\\int_0^\\infty e^{-x} dx",
        "lim" to "\\lim_{x \\to 0} \\frac{\\sin x}{x} = 1",
        "matrix" to "\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}",
        "bmatrix" to "\\begin{bmatrix} 1 & 0 \\\\ 0 & 1 \\end{bmatrix}",
        "cases" to "f(x) = \\begin{cases} 1 & x > 0 \\\\ 0 & x \\le 0 \\end{cases}",
        "aligned" to "\\begin{aligned} x &= 1 \\\\ y &= 2 \\end{aligned}",
        "align" to "\\begin{align} x &= 1 \\\\ y &= 2 \\end{align}",
        "array" to "\\begin{array}{cc} a & b \\\\ c & d \\end{array}",
        "arrayPipes" to "\\begin{array}{|c|c|} \\hline a & b \\\\ \\hline c & d \\\\ \\hline \\end{array}",
        "binom" to "\\binom{n}{k}",
        "dbinom" to "\\dbinom{n}{k}",
        "tfrac" to "\\tfrac{1}{2} + \\dfrac{3}{4}",
        "underbrace" to "\\underbrace{a+b}_{\\text{sum}}",
        "overbrace" to "\\overbrace{x+y}^{S}",
        "boxed" to "\\boxed{x = 5}",
        "boldsymbol" to "\\boldsymbol{F} = m\\boldsymbol{a}",
        "bm" to "\\bm{v}",
        "mathbb" to "x \\in \\mathbb{R}, z \\in \\mathbb{C}",
        "mathcal" to "\\mathcal{L}\\{f(t)\\}",
        "mathscr" to "\\mathscr{F}",
        "operatorname" to "\\operatorname{erf}(x)",
        "trig" to "\\sin^2 x + \\cos^2 x = 1, \\tan x, \\log_{10} x, \\ln x",
        "minmax" to "\\min(a,b), \\max(a,b), \\sup S, \\inf S, \\gcd(m,n)",
        "mod" to "a \\equiv b \\pmod{n}, x \\bmod 2",
        "accents" to "\\hat{x} + \\tilde{y} + \\bar{z} + \\vec{v} + \\dot{q} + \\ddot{q}",
        "xrightarrow" to "A \\xrightarrow{heat} B, A \\xleftarrow[k]{slow} B",
        "implies" to "P \\implies Q \\iff R \\impliedby S",
        "cancel" to "\\cancel{x} + \\bcancel{y}",
        "substack" to "\\sum_{\\substack{i=1 \\\\ j=2}} x",
        "middle" to "\\left\\langle x \\middle| y \\middle| z \\right\\rangle",
        "colon" to "f \\colon A \\to B",
        "spacing" to "a\\,b\\;c\\:d\\!e \\quad f \\qquad g",
        "styles" to "\\displaystyle\\sum x, \\textstyle\\sum x",
        "nonumber" to "\\begin{equation} x = 1 \\nonumber \\end{equation}",
        "tag" to "x = 1 \\tag{1}",
        "href" to "\\href{https://a.com}{link}",
        "color" to "\\textcolor{red}{x} + {\\color{blue} y}",
        "leftRight" to "\\left( \\frac{a}{b} \\right), \\left. F \\right|_0^1",
        "dots" to "a_1, \\dots, a_n \\cdots \\vdots \\ddots",
        "greek" to "\\alpha\\beta\\gamma\\Gamma\\Delta\\Omega\\varepsilon\\phi\\Phi",
        "relations" to "a \\le b \\ge c \\ne d \\approx e \\propto f \\in g \\notin h \\subset i",
        "arrows" to "\\to \\leftarrow \\Rightarrow \\Leftarrow \\mapsto \\hookrightarrow",
        // ── physics ──
        "si" to "\\SI{9.8}{\\meter\\per\\second\\squared}",
        "qty" to "\\qty{5}{\\kilo\\gram}, \\pu{3.0 m/s}",
        "num" to "\\num{1.602e-19}",
        "ang" to "\\ang{30}",
        "braket" to "\\bra{\\psi} \\ket{\\phi}, \\braket{a|b}, \\expectationvalue{H}",
        "dv" to "\\dv{x}{t} + \\pdv{f}{x} + \\fdv{F}{g}",
        "abs" to "\\abs{x} + \\norm{v} + \\abs*{\\frac{a}{b}}",
        "order" to "\\order{x^2}",
        "vecPhys" to "\\va{F} = m\\va{a}",
        "cross" to "\\va{a} \\cross \\va{b}, E = mc^2, F = G\\frac{m_1 m_2}{r^2}",
        "qm" to "i\\hbar\\pdv{\\psi}{t} = \\hat{H}\\psi",
        // ── chemistry ──
        "ceSimple" to "\\ce{2H2 + O2 -> 2H2O}",
        "ceEquil" to "\\ce{A <=> B}",
        "ceCharge" to "\\ce{Fe^{2+} + 2e- -> Fe}",
        "ceState" to "\\ce{H2O_{(l)} -> H2O_{(g)}}",
        "mhchem" to "\\mhchem{CO2 + H2O -> H2CO3}",
        "require" to "\\require{mhchem}\\ce{NaCl}",
        "isotope" to "\\ce{^{14}C}, K_a = \\frac{[H^+][A^-]}{[HA]}",
        "reaction cond" to "\\ce{A ->[heat][cat] B}",
    )

    @Test
    fun probeCoverage() {
        val failures = ArrayList<String>()
        for ((name, latex) in battery) {
            val normalized = normalizeLatexForRender(latex)
            val bitmap = try {
                renderLatexToBitmap(normalized, textSize = 48f, color = 0xFF000000.toInt())
            } catch (_: Throwable) {
                null
            }
            if (bitmap == null) {
                failures.add(name)
                println("COVERAGE-FAIL [$name]: '$latex' -> '$normalized'")
            } else {
                println("COVERAGE-OK   [$name]")
            }
        }
        println("COVERAGE: ${battery.size - failures.size}/${battery.size} render; failures=$failures")
        assertTrue("Unrenderable formulas: $failures", failures.isEmpty())
    }
}
