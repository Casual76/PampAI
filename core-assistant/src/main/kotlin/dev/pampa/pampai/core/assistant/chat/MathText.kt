package dev.pampa.pampai.core.assistant.chat

/**
 * Le formule LaTeX delle risposte, riscritte in testo normale: `$\text{CO}_2$` diventa "CO₂",
 * `$x^2 + 1$` diventa "x² + 1", `\rightarrow` una freccia.
 *
 * Il Markdown della chat non disegna la matematica, e un pezzo fra dollari spariva intero: Gemini
 * scrive volentieri le formule in LaTeX, e "l'anidride carbonica ($\text{CO}_2$)" arrivava come
 * "l'anidride carbonica ()". Qui si tiene la parte che si legge — pedici e apici in Unicode dove
 * esistono, simboli comuni, frazioni come a/b — e si toglie il resto della sintassi. Non e' un
 * motore di impaginazione: una formula complicata esce leggibile, non bella, ma non sparisce.
 *
 * Un `$` da solo resta un dollaro: vale come formula solo una coppia `$…$` senza spazio dopo il
 * primo e prima del secondo ("costa $5 e $10" non e' matematica), oppure `$$…$$`, `\(…\)`, `\[…\]`.
 * Dentro i blocchi di codice (``` e `…`) non si tocca niente.
 */
object MathText {

  /** Il Markdown con le formule gia' riscritte. Veloce da rifare a ogni pezzo dello streaming. */
  fun rewrite(markdown: String): String {
    if (!markdown.contains('$') && !markdown.contains("\\(") && !markdown.contains("\\[")) return markdown
    val out = StringBuilder(markdown.length)
    var i = 0
    var fence = false
    while (i < markdown.length) {
      // I blocchi di codice restano come sono: dentro, un dollaro e' codice.
      if (markdown.startsWith("```", i)) {
        fence = !fence
        out.append("```")
        i += 3
        continue
      }
      if (fence) {
        out.append(markdown[i++])
        continue
      }
      if (markdown[i] == '`') {
        val end = markdown.indexOf('`', i + 1)
        if (end > i) {
          out.append(markdown, i, end + 1)
          i = end + 1
          continue
        }
      }
      val match = formulaAt(markdown, i)
      if (match != null) {
        out.append(toPlain(match.body))
        i = match.end
        continue
      }
      out.append(markdown[i++])
    }
    return out.toString()
  }

  private class Formula(val body: String, val end: Int)

  private fun formulaAt(text: String, i: Int): Formula? {
    fun closed(open: String, close: String): Formula? {
      if (!text.startsWith(open, i)) return null
      val end = text.indexOf(close, i + open.length)
      if (end < 0) return null
      return Formula(text.substring(i + open.length, end), end + close.length)
    }
    closed("$$", "$$")?.let { return it }
    closed("\\[", "\\]")?.let { return it }
    closed("\\(", "\\)")?.let { return it }
    if (text[i] != '$' || (i > 0 && text[i - 1] == '\\')) return null
    val end = text.indexOf('$', i + 1)
    if (end <= i + 1) return null
    val body = text.substring(i + 1, end)
    // Un dollaro vero: "$5 e $10", "$ 20". La formula sta attaccata ai suoi dollari e non va a capo.
    if (body.first().isWhitespace() || body.last().isWhitespace() || body.contains('\n')) return null
    // "$5" seguito da testo e poi da un altro "$...": se dopo il secondo dollaro c'e' una cifra e'
    // quasi sempre un prezzo ("da $5 a $10").
    if (end + 1 < text.length && text[end + 1].isDigit()) return null
    return Formula(body, end + 1)
  }

  /** Il corpo di una formula, senza i delimitatori, come testo. */
  fun toPlain(latex: String): String {
    var s = latex.trim()
    // \text{..}, \mathrm{..} e simili: resta il contenuto.
    s = Regex("""\\(?:text|mathrm|mathbf|mathit|operatorname|textbf|textit|mbox)\{([^{}]*)\}""").replace(s) { it.groupValues[1] }
    s = Regex("""\\frac\{([^{}]*)\}\{([^{}]*)\}""").replace(s) { m -> "${group(m.groupValues[1])}/${group(m.groupValues[2])}" }
    s = Regex("""\\sqrt\{([^{}]*)\}""").replace(s) { m -> "√${group(m.groupValues[1])}" }
    s = Regex("""\^\{\\circ\}|\^\\circ""").replace(s, "°")
    for ((command, symbol) in SYMBOLS) s = s.replace(command, symbol)
    s = Regex("""_\{([^{}]*)\}""").replace(s) { m -> script(m.groupValues[1], SUBSCRIPTS, "_") }
    s = Regex("""\^\{([^{}]*)\}""").replace(s) { m -> script(m.groupValues[1], SUPERSCRIPTS, "^") }
    s = Regex("""_([A-Za-z0-9+\-])""").replace(s) { m -> script(m.groupValues[1], SUBSCRIPTS, "_") }
    s = Regex("""\^([A-Za-z0-9+\-])""").replace(s) { m -> script(m.groupValues[1], SUPERSCRIPTS, "^") }
    // Spazi di LaTeX e comandi rimasti: via la barra, via le graffe.
    s = s.replace("\\,", " ").replace("\\;", " ").replace("\\:", " ").replace("\\!", "").replace("\\ ", " ")
    s = Regex("""\\([A-Za-z]+)""").replace(s) { it.groupValues[1] }
    s = s.replace("{", "").replace("}", "")
    return s.replace(Regex(" {2,}"), " ").trim()
  }

  /** Un pezzo di frazione o di radice: tra parentesi se e' piu' di un termine. */
  private fun group(part: String): String {
    val plain = toPlain(part)
    return if (plain.any { it == ' ' || it == '+' || it == '-' }) "($plain)" else plain
  }

  /** Pedice o apice in Unicode se ogni carattere ce l'ha, altrimenti la forma leggibile "_(..)". */
  private fun script(text: String, table: Map<Char, Char>, marker: String): String {
    val plain = toPlain(text)
    if (plain.isNotEmpty() && plain.all { it in table }) return plain.map { table.getValue(it) }.joinToString("")
    return if (plain.length == 1) "$marker$plain" else "$marker($plain)"
  }

  private val SUBSCRIPTS: Map<Char, Char> = "0123456789+-=()aehijklmnoprstuvx".zip("₀₁₂₃₄₅₆₇₈₉₊₋₌₍₎ₐₑₕᵢⱼₖₗₘₙₒₚᵣₛₜᵤᵥₓ").toMap()

  private val SUPERSCRIPTS: Map<Char, Char> = "0123456789+-=()inx".zip("⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁼⁽⁾ⁱⁿˣ").toMap()

  /** I comandi piu' comuni nelle risposte, dal piu' lungo (\longrightarrow prima di \rightarrow). */
  private val SYMBOLS: List<Pair<String, String>> = listOf(
    "\\rightleftharpoons" to "⇌",
    "\\longrightarrow" to "→",
    "\\Rightarrow" to "⇒",
    "\\rightarrow" to "→",
    "\\leftarrow" to "←",
    "\\to" to "→",
    "\\times" to "×",
    "\\cdot" to "·",
    "\\div" to "÷",
    "\\pm" to "±",
    "\\approx" to "≈",
    "\\neq" to "≠",
    "\\leq" to "≤",
    "\\geq" to "≥",
    "\\le" to "≤",
    "\\ge" to "≥",
    "\\infty" to "∞",
    "\\degree" to "°",
    "\\circ" to "°",
    "\\Delta" to "Δ",
    "\\alpha" to "α",
    "\\beta" to "β",
    "\\gamma" to "γ",
    "\\delta" to "δ",
    "\\lambda" to "λ",
    "\\mu" to "μ",
    "\\pi" to "π",
    "\\sigma" to "σ",
    "\\theta" to "θ",
    "\\omega" to "ω",
    "\\Omega" to "Ω",
    "\\sum" to "Σ",
    "\\%" to "%",
    "\\left" to "",
    "\\right" to "",
  ).sortedByDescending { it.first.length }
}
