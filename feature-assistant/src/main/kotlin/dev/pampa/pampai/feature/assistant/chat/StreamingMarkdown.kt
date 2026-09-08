package dev.pampa.pampai.feature.assistant.chat

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

/** Che blocco e': decide lo stile del paragrafo in [RevealingParagraphs]. */
internal enum class StreamBlockKind { Body, Heading1, Heading2, Code, Quote }

/**
 * Un blocco del testo in streaming: il tipo, il testo gia' con gli stili in linea e, per il
 * codice, il linguaggio (l'etichetta in testa al blocco, come in [AriaCodeBlock]).
 */
internal class StreamBlock(val kind: StreamBlockKind, val text: AnnotatedString, val language: String? = null)

/**
 * Il Markdown ridotto a blocchi, per il testo che sta arrivando.
 *
 * Il renderer Markdown vuole l'albero intero e non da' il layout del testo; la rivelazione per
 * parole ha bisogno di un `Text` solo per paragrafo, col suo `TextLayoutResult`. Qui basta un
 * reso leggero e a layout stabile, che a fine risposta lasci il posto al Markdown vero senza
 * un salto visibile: stessi stili, stessa spaziatura fra i blocchi.
 *
 * Blocchi separati da riga vuota. Dentro: `**grassetto**`, `*corsivo*`, `` `codice` ``,
 * `[testo](url)` come testo sottolineato, `- `/`* ` come "•  ", i numeri degli elenchi lasciati,
 * `#`/`##` titolo grande e dal `###` titolo piccolo, `> ` citazione, ``` blocco monospazio con
 * le righe conservate, tabelle grezze. Le righe di prosa consecutive si uniscono con uno spazio,
 * come fa il Markdown; gli elenchi e le tabelle tengono la riga. I marcatori `[[chip]]` spariscono:
 * a fine risposta diventano i chip sotto il testo, e mostrarli mentre arrivano non dice niente.
 */
internal fun streamingBlocks(markdown: String, scheme: ColorScheme, typography: Typography): List<StreamBlock> {
  val styles = InlineStyles(scheme, typography)
  val out = ArrayList<StreamBlock>()
  val lines = ArrayList<String>()
  var kind = StreamBlockKind.Body
  var lastPlain = false
  var code: StringBuilder? = null
  var codeLanguage: String? = null

  fun flush() {
    if (lines.isNotEmpty()) out += StreamBlock(kind, styles.paragraph(lines))
    lines.clear()
    kind = StreamBlockKind.Body
    lastPlain = false
  }

  fun add(line: String, plain: Boolean) {
    if (plain && lastPlain) lines[lines.lastIndex] = lines.last() + " " + line else lines += line
    lastPlain = plain
  }

  for (raw in markdown.lines()) {
    val line = raw.trimEnd()
    val trimmed = line.trimStart()
    val open = code
    if (open != null) {
      if (trimmed.startsWith("```")) {
        out += StreamBlock(StreamBlockKind.Code, AnnotatedString(open.toString()), codeLanguage)
        code = null
        codeLanguage = null
      } else {
        if (open.isNotEmpty()) open.append('\n')
        open.append(line)
      }
      continue
    }
    if (trimmed.startsWith("```")) {
      flush()
      code = StringBuilder()
      codeLanguage = trimmed.removePrefix("```").trim().substringBefore(' ').takeIf { it.isNotBlank() }
      continue
    }
    if (trimmed.isEmpty() || Rule.matches(trimmed)) {
      flush()
      continue
    }
    val heading = Heading.matchEntire(trimmed)
    if (heading != null) {
      flush()
      val level = heading.groupValues[1].length
      out += StreamBlock(if (level <= 2) StreamBlockKind.Heading1 else StreamBlockKind.Heading2, styles.of(heading.groupValues[2]))
      continue
    }
    val quote = Quote.matchEntire(trimmed)
    if (quote != null) {
      if (kind != StreamBlockKind.Quote) flush()
      kind = StreamBlockKind.Quote
      add(quote.groupValues[1], plain = true)
      continue
    }
    if (kind == StreamBlockKind.Quote) flush()
    val bullet = Bullet.matchEntire(line)
    when {
      bullet != null -> add(bullet.groupValues[1] + "•  " + bullet.groupValues[2], plain = false)
      Numbered.matches(line) || trimmed.startsWith("|") -> add(line, plain = false)
      else -> add(trimmed, plain = true)
    }
  }
  code?.let { out += StreamBlock(StreamBlockKind.Code, AnnotatedString(it.toString()), codeLanguage) }
  flush()
  return out
}

private val Heading = Regex("(#{1,6})\\s+(.*)")
private val Quote = Regex(">\\s?(.*)")
private val Bullet = Regex("(\\s*)[-*+]\\s+(.*)")
private val Numbered = Regex("\\s*\\d{1,3}[.)]\\s+.*")
private val Rule = Regex("(?:-\\s*){3,}|(?:\\*\\s*){3,}|(?:_\\s*){3,}")

/**
 * I marcatori in linea, in ordine di priorita': il grassetto prima del corsivo, cosi' `**` non
 * viene letto come due corsivi vuoti. Il punto non attraversa la riga: un `**` rimasto aperto
 * mentre il testo arriva resta un `**`, come fa il Markdown, e non si porta dietro tutto il
 * paragrafo. Gruppi: 1-2 grassetto, 3-4 corsivo, 5 codice, 6 testo del link (7 l'indirizzo),
 * 8 barrato; l'ultimo ramo e' il chip, senza gruppo, che sparisce.
 */
private val InlineMark = Regex(
  "\\*\\*(.+?)\\*\\*" +
    "|__(.+?)__" +
    "|(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])" +
    "|(?<!\\w)_(?!\\s)(.+?)(?<!\\s)_(?!\\w)" +
    "|`([^`\\n]*)`" +
    "|!?\\[([^\\]\\n]*)]\\(([^)\\n]*)\\)" +
    "|~~(.+?)~~" +
    "|\\[\\[[^\\]\\n]*]]",
)

/** Gli stili in linea, gli stessi che il Markdown completo dara' a fine risposta. */
private class InlineStyles(scheme: ColorScheme, typography: Typography) {
  private val bold = SpanStyle(fontWeight = FontWeight.Bold)
  private val italic = SpanStyle(fontStyle = FontStyle.Italic)
  private val code = SpanStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = typography.bodySmall.fontSize,
    background = scheme.surfaceVariant.copy(alpha = 0.6f),
  )
  private val link = SpanStyle(color = scheme.primary, textDecoration = TextDecoration.Underline)
  private val strike = SpanStyle(textDecoration = TextDecoration.LineThrough)

  fun of(text: String): AnnotatedString = buildAnnotatedString { inline(text) }

  fun paragraph(lines: List<String>): AnnotatedString = buildAnnotatedString {
    lines.forEachIndexed { index, line ->
      if (index > 0) append('\n')
      inline(line)
    }
  }

  /** Ricorsivo dentro grassetto, corsivo, link e barrato: `**testo con `codice`**` viene giusto. */
  private fun AnnotatedString.Builder.inline(text: String) {
    var at = 0
    for (match in InlineMark.findAll(text)) {
      append(text, at, match.range.first)
      val g = match.groups
      when {
        g[1] != null -> withStyle(bold) { inline(g[1]!!.value) }
        g[2] != null -> withStyle(bold) { inline(g[2]!!.value) }
        g[3] != null -> withStyle(italic) { inline(g[3]!!.value) }
        g[4] != null -> withStyle(italic) { inline(g[4]!!.value) }
        g[5] != null -> withStyle(code) { append(g[5]!!.value) }
        g[6] != null -> withStyle(link) { inline(g[6]!!.value) }
        g[8] != null -> withStyle(strike) { inline(g[8]!!.value) }
      }
      at = match.range.last + 1
    }
    append(text, at, text.length)
  }
}
