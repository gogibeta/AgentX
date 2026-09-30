package com.newoether.agora.ui.components

// ── Chemistry pre-normalizer ─────────────────────────────────────────
// JLaTeXMath has no mhchem support: \ce{...} always throws, and the bitmap
// fallback then paints the raw source ($2H2 + O2...$). Rewrite chemistry
// into plain math JLaTeXMath understands BEFORE parsing/rendering.
// (Split out of LatexRenderer.kt: handwritten files are capped at 800 lines.)

/** Matches \ce{...} / \mhchem{...} with balanced-brace inner content. */
private fun extractChemBody(latex: String, start: Int): Pair<String, Int>? {
    val commands = listOf("\\ce{", "\\mhchem{")
    val cmd = commands.firstOrNull { latex.startsWith(it, start) } ?: return null
    var depth = 1
    var i = start + cmd.length
    while (i < latex.length && depth > 0) {
        when (latex[i]) {
            '{' -> depth++
            '}' -> depth--
        }
        i++
    }
    if (depth != 0) return null
    return latex.substring(start + cmd.length, i - 1) to i
}

/** mhchem conditioned arrows: ->[above] or ->[above][below]. */
private val CHEM_COND_ARROW = Regex("""->\[([^\]]*)\](?:\[([^\]]*)\])?""")

internal fun normalizeChemistry(latex: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < latex.length) {
        val extracted = extractChemBody(latex, i)
        if (extracted != null) {
            var body = extracted.first
            // mhchem reaction conditions: \ce{A ->[heat][cat] B} keeps the
            // conditions as a trailing note (built with plain splicing — never
            // Regex.replace, whose replacement syntax eats backslashes).
            val cond = CHEM_COND_ARROW.find(body)
            if (cond != null) {
                val note = listOf(cond.groupValues[1], cond.groupValues[2])
                    .filter { it.isNotEmpty() }.joinToString("; ")
                body = body.substring(0, cond.range.first) + "\\rightarrow" +
                    body.substring(cond.range.last + 1) +
                    (if (note.isNotEmpty()) " ($note)" else "")
            }
            // Literal String.replace below: Regex.replace would eat the backslash
            // in "\rightarrow" (\r = escaped "r" in replacement syntax).
            body = body.replace("<=>", "\\leftrightarrow ")
                .replace("⇌", "\\leftrightarrow ")
                .replace("⇋", "\\leftrightarrow ")
                .replace("->", "\\rightarrow ")
                .replace("→", "\\rightarrow ")
                .replace("⟶", "\\rightarrow ")
                .replace("<-", "\\leftarrow ")
                .replace("←", "\\leftarrow ")
                .replace("⟵", "\\leftarrow ")
            out.append("\\mathrm{").append(body).append("}")
            i = extracted.second
            continue
        }
        out.append(latex[i])
        i++
    }
    var s = out.toString()
    // \require{mhchem} is a KaTeX/MathJax directive — meaningless (and fatal) here.
    s = s.replace(Regex("""\\require\{[^}]*\}"""), "")
    return s
}

/**
 * Readable plain-text stand-in for a formula JLaTeXMath cannot render.
 * Never returns raw LaTeX with delimiters/commands (the old "$...$" paint).
 */
internal fun plainTextFallback(latex: String): String {
    var s = normalizeChemistry(latex)
    s = s.replace(Regex("""\\[a-zA-Z]+"""), " ")
    s = s.replace(Regex("""[\\{}$^_~&]"""), "")
    s = s.replace(Regex("""\s+"""), " ").trim()
    if (s.isEmpty()) s = "formula"
    return s.take(120)
}

/** True when the message already carries explicit LaTeX/chemistry markers. */
internal fun looksLikeLatexDocument(text: String): Boolean {
    // NOTE: deliberately excludes "\(" — toggle-off must keep single-$ literal even
    // next to \(...\) (pinned by LiteralAngleBracketMarkdownTest).
    if ("$$" in text || "\\[" in text) return true
    if ("\\ce{" in text || "\\mhchem{" in text) return true
    if ("\\require{" in text) return true
    if ("\\frac" in text || "\\begin{" in text || "\\text{" in text) return true
    return false
}

/**
 * Full pre-render pipeline: chemistry first, then physics/siunitx/braket and the
 * long tail of model-emitted macros JLaTeXMath cannot parse. Everything downstream
 * (bitmap render, canRenderLatex) must call this, never raw input.
 */
internal fun normalizeLatexForRender(latex: String): String =
    normalizePhysics(normalizeChemistry(latex))
