package dev.pampa.pampai.core.assistant.voice

import dev.pampa.pampai.core.assistant.chat.MathText

/**
 * Il Markdown ridotto a testo per la voce: **grassetto**, *corsivo*, `codice`, titoli, elenchi,
 * tabelle, blocchi di codice, link. Il sintetizzatore non deve leggere gli asterischi.
 */
object PlainText {

  fun of(markdown: String): String {
    val out = StringBuilder()
    var inCode = false
    // Le formule in testo prima di tutto: "$	ext{CO}_2$" letto ad alta voce sarebbe una sfilza di simboli.
    MathText.rewrite(markdown).lines().forEach { raw ->
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

  /**
   * La fine di frase mentre la risposta scorre: solo se dopo c'e' dell'altro. Il punto in fondo a un
   * pezzo di stream puo' essere un "3." che diventera' "3.5 gradi": letto subito, la voce diceva
   * "3" e poi "5 gradi" come due frasi.
   */
  private val SENTENCE_END_STREAMING = Regex("[.!?…]+(?=\\s)|\\n")

  /** Le frasi nuove di [fullText] oltre [spokenChars]: quelle chiuse, piu' la coda se [final]. */
  fun newSentences(fullText: String, spokenChars: Int, final: Boolean): Pair<List<String>, Int> =
    sentencesFrom(of(fullText), spokenChars, final)

  /**
   * Come [newSentences], ma ricordando *cosa* si e' gia' letto ([spoken], il testo semplice) e non
   * solo quanto: se il testo nuovo non comincia piu' con quello (un preambolo "controllo il
   * meteo.", poi uno strumento, poi la risposta vera; o un testo che si accorcia) e' un'altra
   * risposta, e si legge da capo invece che da meta'.
   *
   * @return le frasi da dire e il nuovo testo gia' letto.
   */
  fun advance(fullText: String, spoken: String, final: Boolean): Pair<List<String>, String> {
    val plain = of(fullText)
    val from = if (plain.startsWith(spoken)) spoken.length else 0
    val (sentences, consumed) = sentencesFrom(plain, from, final)
    return sentences to plain.substring(0, consumed.coerceAtMost(plain.length))
  }

  private fun sentencesFrom(plain: String, spokenChars: Int, final: Boolean): Pair<List<String>, Int> {
    if (plain.length <= spokenChars) return emptyList<String>() to spokenChars
    val tail = plain.substring(spokenChars)
    val sentences = mutableListOf<String>()
    var start = 0
    (if (final) SENTENCE_END else SENTENCE_END_STREAMING).findAll(tail).forEach { match ->
      val end = match.range.last + 1
      sentences += tail.substring(start, end).trim()
      start = end
    }
    if (final && start < tail.length) sentences += tail.substring(start).trim()
    val consumed = if (final) tail.length else start
    return sentences.filter { it.isNotBlank() } to spokenChars + consumed
  }
}
