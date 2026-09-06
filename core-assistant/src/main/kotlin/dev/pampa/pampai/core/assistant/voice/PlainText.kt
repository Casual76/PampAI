package dev.pampa.pampai.core.assistant.voice

/**
 * Il Markdown ridotto a testo per la voce: **grassetto**, *corsivo*, `codice`, titoli, elenchi,
 * tabelle, blocchi di codice, link. Il sintetizzatore non deve leggere gli asterischi.
 */
object PlainText {

  fun of(markdown: String): String {
    val out = StringBuilder()
    var inCode = false
    markdown.lines().forEach { raw ->
      val line = raw.trimEnd()
      if (line.trimStart().startsWith("```")) {
        inCode = !inCode
        return@forEach
      }
      if (inCode) {
        out.append(line).append('\n')
        return@forEach
      }
      val trimmed = line.trimStart()
      if (trimmed.startsWith("|")) {
        if (trimmed.replace("|", "").replace("-", "").replace(":", "").isBlank()) return@forEach
        out.append(trimmed.trim('|').split("|").joinToString(", ") { inline(it.trim()) }).append('\n')
        return@forEach
      }
      val text = trimmed
        .replace(Regex("^#{1,6}\\s+"), "")
        .replace(Regex("^[-*•]\\s+"), "")
        .replace(Regex("^\\d{1,2}[.)]\\s+"), "")
        .replace(Regex("^>\\s?"), "")
      out.append(inline(text)).append('\n')
    }
    return out.toString().replace(Regex("\\n{3,}"), "\n\n").trim()
  }

  private fun inline(text: String): String = text
    .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
    .replace(Regex("__(.+?)__"), "$1")
    .replace(Regex("(?<!\\w)[*_](.+?)[*_](?!\\w)"), "$1")
    .replace(Regex("`([^`]*)`"), "$1")
    .replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")
    .replace(Regex("~~(.+?)~~"), "$1")
    .replace(Regex("\\[\\[[^\\]]*]]"), "")

  /** Fine di frase: punto, punto esclamativo, interrogativo o a capo, seguiti da spazio o fine. */
  val SENTENCE_END = Regex("[.!?…]+(?=\\s|$)|\\n")

  /** Le frasi nuove di [fullText] oltre [spokenChars]: quelle chiuse, piu' la coda se [final]. */
  fun newSentences(fullText: String, spokenChars: Int, final: Boolean): Pair<List<String>, Int> {
    val plain = of(fullText)
    if (plain.length <= spokenChars) return emptyList<String>() to spokenChars
    val tail = plain.substring(spokenChars)
    val sentences = mutableListOf<String>()
    var start = 0
    SENTENCE_END.findAll(tail).forEach { match ->
      val end = match.range.last + 1
      sentences += tail.substring(start, end).trim()
      start = end
    }
    if (final && start < tail.length) sentences += tail.substring(start).trim()
    val consumed = if (final) tail.length else start
    return sentences.filter { it.isNotBlank() } to spokenChars + consumed
  }
}
