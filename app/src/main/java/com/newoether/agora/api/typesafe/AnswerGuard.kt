package com.newoether.agora.api.typesafe

/**
 * Deterministic guardrails for model text (jev-fidelity spirit, no network).
 *
 * - [cleanForSynthesis]: normalize whitespace + enforce a char budget before a
 *   candidate answer is fed to another model (ensemble synthesis, re-rank state).
 * - [hasRawLatexLeak]: detect unrendered LaTeX remnants (`$...$` with backslash
 *   commands, `latex://` URLs, `\ce{`) in FINAL user-facing markdown. The render
 *   pipeline should never emit these; the check is a tripwire for logging/tests.
 */
object AnswerGuard {
    private val LATEX_COMMAND_IN_DOLLARS = Regex("""\$[^$\n]*\\[a-zA-Z]+[^$\n]*\$""")
    private val WHITESPACE_RUN = Regex("""[ \t\x0B\u000C\r]+""")
    private val BLANK_LINE_RUN = Regex("""\n{3,}""")

    fun cleanForSynthesis(text: String, maxChars: Int = 6000): String {
        var s = text.replace(WHITESPACE_RUN, " ")
        s = s.replace(Regex(""" *\n *"""), "\n")
        s = BLANK_LINE_RUN.replace(s, "\n\n")
        s = s.trim()
        return if (s.length <= maxChars) s else s.take(maxChars)
    }

    fun hasRawLatexLeak(renderedMarkdown: String): Boolean {
        if (LATEX_COMMAND_IN_DOLLARS.containsMatchIn(renderedMarkdown)) return true
        if ("latex://" in renderedMarkdown) return true
        if ("\\ce{" in renderedMarkdown || "\\mhchem{" in renderedMarkdown) return true
        return false
    }
}
