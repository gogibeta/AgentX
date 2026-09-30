package com.newoether.agora.ui.components

// ── Physics / siunitx / braket / long-tail macro normalizer ────────────
// Models emit macros from packages JLaTeXMath never implemented (siunitx,
// physics, braket, cancel, ...). Each mapping below rewrites one macro into
// core math JLaTeXMath parses. Coverage is proven by LatexCoverageProbe:
// every entry here renders a real bitmap, never the text fallback.

/** Extracts the balanced-brace argument starting at [openBrace] ('{' index). */
internal fun extractBraceArg(text: String, openBrace: Int): Pair<String, Int>? {
    if (openBrace < 0 || openBrace >= text.length || text[openBrace] != '{') return null
    var depth = 1
    var i = openBrace + 1
    while (i < text.length && depth > 0) {
        when (text[i]) {
            '{' -> depth++
            '}' -> depth--
        }
        i++
    }
    if (depth != 0) return null
    return text.substring(openBrace + 1, i - 1) to i
}

/** Reads up to [count] consecutive {args} starting at [from]. */
internal fun readBraceArgs(text: String, from: Int, count: Int): Triple<List<String>, Int, Boolean> {
    val args = ArrayList<String>()
    var i = from
    while (args.size < count) {
        while (i < text.length && text[i].isWhitespace()) i++
        val extracted = extractBraceArg(text, i) ?: return Triple(args, i, false)
        args.add(extracted.first)
        i = extracted.second
    }
    return Triple(args, i, true)
}

private val SI_UNITS = mapOf(
    "\\meter" to "m", "\\second" to "s", "\\gram" to "g", "\\mole" to "mol",
    "\\liter" to "L", "\\litre" to "L", "\\kelvin" to "K", "\\ampere" to "A",
    "\\newton" to "N", "\\joule" to "J", "\\watt" to "W", "\\volt" to "V",
    "\\pascal" to "Pa", "\\hertz" to "Hz", "\\ohm" to "\\Omega",
    "\\kilo" to "k", "\\milli" to "m", "\\micro" to "\\mu", "\\nano" to "n",
    "\\mega" to "M", "\\giga" to "G", "\\centi" to "c",
    "\\per" to "/", "\\squared" to "^2", "\\cubed" to "^3",
    "\\celsius" to "^{\\circ}C", "\\degree" to "^{\\circ}",
)

internal fun cleanSiUnits(units: String): String {
    var u = units
    for ((macro, replacement) in SI_UNITS) u = u.replace(macro, replacement)
    u = u.replace(Regex("""\\[a-zA-Z]+"""), "")
    u = u.replace(Regex("""[{}]"""), "")
    return u.trim()
}

internal fun normalizePhysics(latex: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < latex.length) {
        if (latex[i] != '\\') {
            out.append(latex[i])
            i++
            continue
        }
        val rest = latex.substring(i)
        fun command(name: String): Boolean = rest.startsWith(name) &&
            (rest.length == name.length || !rest[name.length].isLetter())

        var consumed = false
        // Multi-arg commands first (longest names win). Two-arg forms are tried
        // before one-arg forms (e.g. \dv{x}{t} before \dv{x}).
        for ((name, argc) in PHYSICS_COMMANDS) {
            if (!command(name)) continue
            var argStart = i + name.length
            // Skip starred forms (\abs*{...}) and [...] optional args (\dv[n]{x}).
            while (argStart < latex.length && latex[argStart] == '*') argStart++
            if (argStart < latex.length && latex[argStart] == '[') {
                val close = latex.indexOf(']', argStart)
                if (close >= 0) argStart = close + 1
            }
            val (args, end, ok) = readBraceArgs(latex, argStart, argc)
            if (!ok) continue
            out.append(applyPhysicsCommand(name, args))
            i = end
            consumed = true
            break
        }
        if (consumed) continue
        // Single-token replacements.
        val single = PHYSICS_SINGLETONS.entries.firstOrNull { (name, _) -> command(name) }
        if (single != null) {
            out.append(single.value)
            i += single.key.length
            continue
        }
        out.append(latex[i])
        i++
    }
    var s = out.toString()
    // Environments JLaTeXMath lacks -> closest supported shape.
    s = s.replace("\\begin{align}", "\\begin{aligned}")
        .replace("\\end{align}", "\\end{aligned}")
        .replace("\\begin{align*}", "\\begin{aligned}")
        .replace("\\end{align*}", "\\end{aligned}")
        .replace("\\begin{equation}", "").replace("\\end{equation}", "")
        .replace("\\begin{equation*}", "").replace("\\end{equation*}", "")
    // Display/style/numbering directives: meaningless or fatal here.
    s = s.replace("\\nonumber", "")
    s = s.replace(Regex("""\\color\{[^{}]*\}"""), "")
    s = s.replace(Regex("""\\tag\{[^{}]*\}"""), "")
    s = s.replace(Regex("""\\label\{[^{}]*\}"""), "")
    return s
}

/** command -> arg count. */
private val PHYSICS_COMMANDS: List<Pair<String, Int>> = listOf(
    "\\SI" to 2,
    "\\qty" to 2,
    "\\pu" to 1,
    "\\si" to 1,
    "\\num" to 1,
    "\\ang" to 1,
    "\\bra" to 1,
    "\\ket" to 1,
    "\\braket" to 1,
    "\\expectationvalue" to 1,
    "\\dv" to 2,
    "\\pdv" to 2,
    "\\fdv" to 2,
    "\\dv" to 1,
    "\\pdv" to 1,
    "\\fdv" to 1,
    "\\pmod" to 1,
    "\\abs" to 1,
    "\\norm" to 1,
    "\\order" to 1,
    "\\va" to 1,
    "\\cancel" to 1,
    "\\bcancel" to 1,
    "\\xrightarrow" to 1,
    "\\xleftarrow" to 1,
    "\\substack" to 1,
    "\\tfrac" to 2,
    "\\dfrac" to 2,
    "\\operatorname" to 1,
    "\\boldsymbol" to 1,
    "\\bm" to 1,
    "\\mathscr" to 1,
    "\\href" to 2,
    "\\textcolor" to 2,
)

private fun applyPhysicsCommand(name: String, args: List<String>): String = when (name) {
    "\\SI", "\\qty" -> "${args[0]}\\,\\mathrm{${cleanSiUnits(args[1])}}"
    "\\pu" -> "\\mathrm{${cleanSiUnits(args[0])}}"
    "\\si" -> "\\mathrm{${cleanSiUnits(args[0])}}"
    "\\num" -> "\\mathrm{${args[0]}}"
    "\\ang" -> "${args[0]}^{\\circ}"
    "\\bra" -> "\\langle ${args[0]} |"
    "\\ket" -> "| ${args[0]} \\rangle"
    "\\braket" -> "\\langle ${args[0]} \\rangle"
    "\\expectationvalue" -> "\\langle ${args[0]} \\rangle"
    "\\dv" -> if (args.size == 2) "\\frac{d ${args[0]}}{d ${args[1]}}" else "\\frac{d}{d ${args[0]}}"
    "\\pdv" -> if (args.size == 2) "\\frac{\\partial ${args[0]}}{\\partial ${args[1]}}" else "\\frac{\\partial}{\\partial ${args[0]}}"
    "\\fdv" -> if (args.size == 2) "\\frac{\\delta ${args[0]}}{\\delta ${args[1]}}" else "\\frac{\\delta}{\\delta ${args[0]}}"
    "\\pmod" -> "(\\mathrm{mod}\\ ${args[0]})"
    "\\abs" -> "|${args[0]}|"
    "\\norm" -> "\\|${args[0]}\\|"
    "\\order" -> "\\mathcal{O}(${args[0]})"
    "\\va" -> "\\vec{${args[0]}}"
    "\\cancel", "\\bcancel" -> args[0]
    "\\xrightarrow" -> "\\overset{${args[0]}}{\\rightarrow}"
    "\\xleftarrow" -> "\\overset{${args[0]}}{\\leftarrow}"
    "\\substack" -> "\\begin{array}{c} ${args[0]} \\end{array}"
    "\\tfrac", "\\dfrac" -> "\\frac{${args[0]}}{${args[1]}}"
    "\\operatorname" -> "\\mathrm{${args[0]}}"
    "\\boldsymbol", "\\bm" -> "\\mathbf{${args[0]}}"
    "\\mathscr" -> "\\mathcal{${args[0]}}"
    "\\href" -> args[1]
    "\\textcolor" -> args[1]
    else -> args.joinToString(" ")
}

private val PHYSICS_SINGLETONS: Map<String, String> = mapOf(
    "\\bmod" to "\\mathrm{mod}\\ ",
    "\\implies" to "\\Rightarrow",
    "\\impliedby" to "\\Leftarrow",
    "\\middle|" to "|",
    "\\middle\\|" to "\\|",
    "\\colon" to ":",
    "\\cross" to "\\times",
    "\\!" to "",
)
