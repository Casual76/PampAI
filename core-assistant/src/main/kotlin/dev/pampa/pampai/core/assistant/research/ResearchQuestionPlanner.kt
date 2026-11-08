package dev.pampa.pampai.core.assistant.research

/** Deliberately small deterministic planner: sources/depth only, never model or permission changes. */
object ResearchQuestionPlanner {
  private data class Refusal(val profile: Boolean = false, val domains: Set<String> = emptySet(), val all: Boolean = false)
  /** Explicit restrictions are evaluated before any inferred positive intent. */
  fun onlyProvidedContent(question: String): Boolean {
    val text = question.lowercase()
    // A pasted payload is supplied content too. Keep plain "dati" out: an explicit
    // request to use only ClasseViva data still asks for that connected source.
    val deictic = "(?:questi|quei)\\s+(?:dati|valori|numeri)|(?:dati|valori|numeri)\\s+(?:(?:qui|qua)\\s+)?(?:sotto|sopra|forniti|incollati|riportati)|quanto\\s+(?:segue|riportato|fornito)|(?:these|the following|supplied|pasted)\\s+(?:data|values|numbers)"
    val supplied = "allegat[oaie]|tabella|immagine|screenshot|file|documento|testo fornito|testo incollato|attached|attachment|table|provided text|$deictic"
    return Regex("\\b(solo|soltanto|esclusivamente|unicamente|only)\\b[^.;!?\\n]{0,75}\\b($supplied)\\b").containsMatchIn(text) ||
      (Regex("\\b($supplied)\\b").containsMatchIn(text) &&
        Regex("\\b(solo|soltanto|esclusivamente|unicamente)\\s+(quell[oaie]|quest[oaie])\\b|\\b(e basta|senza altre fonti)\\b").containsMatchIn(text))
  }
  private fun refusal(question: String): Refusal {
    if (onlyProvidedContent(question)) return Refusal(profile = true, all = true)
    val text = question.lowercase()
    val directives = Regex("\\b(?:(?:non|senza)\\s+(?:(?:devi|puoi|dovresti|voglio(?:\\s+che(?:\\s+tu)?)?)\\s+)?(?:consultare|consultate|consulti|usare|usate|usi|utilizzare|utilizzi|leggere|accedere|recuperare|attingere)|ignora|escludi|(?:do not|don't|without)\\s+(?:use|consult|read|access))\\b[^.;!?\\n]{0,150}")
      .findAll(text).map { it.value.split(Regex("\\b(ma|invece|but|instead)\\b"), limit = 2).first() }.toList()
    val all = directives.any { Regex("\\b(fonti|app collegate|dati personali|dati privati|conoscenza personale|connected sources|private data)\\b").containsMatchIn(it) }
    val profile = all || directives.any { Regex("\\b(profilo|memoria|memorie|profile|memory)\\b").containsMatchIn(it) }
    val domains = buildSet {
      if (directives.any { Regex("\\b(classeviva|registro scolastico)\\b").containsMatchIn(it) }) add("cv")
      if (directives.any { Regex("\\b(fluidify|spotify)\\b").containsMatchIn(it) }) add("fluidify")
    }
    return Refusal(profile, domains, all)
  }
  fun storedKnowledgeAllowed(question: String): Boolean = refusal(question).let { !it.profile && !it.all }
  fun sourceAllowed(question: String, domain: String): Boolean = refusal(question).let { !it.all && domain !in it.domains }
  fun profileQuery(question: String): ProfileQuery? = if (storedKnowledgeAllowed(question)) sourceQuery(question) else null
  fun sourceQuery(question: String): ProfileQuery? {
    val policy = refusal(question)
    if (policy.all) return null
    val query = inferredQuery(question) ?: return null
    return query.copy(fields = query.fields.filter { it.substringBefore('.') !in policy.domains }).takeIf { it.fields.isNotEmpty() }
  }
  private fun inferredQuery(question: String): ProfileQuery? {
    val text = question.lowercase()
    if (Regex("^(apri|riproduci|metti|pausa|play|crea|aggiungi|elimina|rimuovi|rinomina|open)\\b").containsMatchIn(text.trim())) return null
    // Possession, advice and movement to a school do not request private records.
    val consult = Regex("\\b(consulta|controlla|leggi|cerca|guarda|analizza|confronta|check|read)\\b").containsMatchIn(text)
    val nouns = "voti|voto|medie|media|rendimento|agenda|compiti|verifiche|lezioni|assenze|ritardi|note|materiali|documenti|comunicazioni|circolari|musica|ascolti|artisti|canzoni|brani|playlist|gusti|cronologia|grades|lessons|music|artists|songs|listening"
    // The verb's object must be records, not (for example) advice about those records.
    val recordVerb = Regex("^\\s*(?:(?:per favore|puoi|potresti)\\s+)?(consulta(?:re)?|controlla(?:re)?|leggi|leggere|cerca(?:re)?|guarda(?:re)?|analizza(?:re)?|confronta(?:re)?|mostrami|mostra(?:re)?|elenca(?:re)?|riepiloga(?:re)?|riassumi|calcola(?:re)?|check|read|show)\\s+" +
      "(?:(?:in profondit[àa]|approfonditamente)\\s+)?(?:(?:in|su|da)\\s+(?:classeviva|fluidify)\\s+)?" +
      "(?:(?:il|lo|la|i|gli|le)\\s+)?(?:(?:miei|mie|mio|mia|my)\\s+)?(?:$nouns)\\b").containsMatchIn(text)
    val prefix = "(?:(?:dimmi|mostrami|vorrei sapere|mi dici)\\s+)?"
    val owner = "(?:(?:il|lo|la|i|gli|le|dei|delle)\\s+)?(?:(?:miei|mie|mio|mia|my)\\s+)?"
    val factualQuestion = Regex("^\\s*$prefix(?:che cosa|quale|quali|qual|quanti|quante|quanto|quando|che|cosa|what|which|how many)\\s+(?:(?:sono|è|era|erano|are|is)\\s+)?$owner(?:$nouns)\\b|" +
      "^\\s*$prefix(?:che cosa|che|cosa|quanto|quando)\\s+(?:(?:ho|avevo)\\s+)?(?:ascolto|ascoltavo|ascoltato|preferisco)\\b|" +
      "^\\s*${prefix}come\\s+(?:vanno|sono|stanno andando)\\s+$owner(?:$nouns)\\b").containsMatchIn(text)
    val advice = Regex("\\b(posso|potrei|consigli|consigliami|consiglia|migliorare|miglioro|imparare|imparo|insegnami|arrivare|raggiungere)\\b").containsMatchIn(text)
    val performance = Regex("^\\s*come (va|sta andando) il mio rendimento (scolastico|a scuola)[?!.\\s]*$").matches(text)
    val personalAdviceRequest = Regex("^\\s*(consigliami|suggeriscimi|raccomanda|dammi consigli|cosa devo studiare|(?:che|quali).{0,40}mi consigli)\\b").containsMatchIn(text)
    val basedOnRecords = personalAdviceRequest && Regex("\\b(in base|sulla base)\\s+(ai|alle|al|alla|a|dei|delle)\\s+(miei|mie|mio|mia)\\s+(?:$nouns)\\b").containsMatchIn(text)
    val requestingRecords = recordVerb || (factualQuestion && !advice) || performance || basedOnRecords
    val sourceMusic = consult && "fluidify" in text && requestingRecords
    val sourceSchool = consult && "classeviva" in text && requestingRecords
    val schoolContext = Regex("\\b(scolastic|scuola|registro|professor|insegnant|disciplinar)").containsMatchIn(text)
    val personalMusic = requestingRecords && (Regex("\\b(che|quale|quali|quanto|cosa|come)\\b.{0,80}\\b(ascolto|ascoltavo|preferisco)\\b|\\b(ho|avevo)\\s+ascoltato\\b|\\bmy listening\\b").containsMatchIn(text) ||
      Regex("\\b(mi[oaie]|miei|mie|my)\\s+(musica|ascolti|artisti|canzoni|brani|playlist|gusti|music|artists|songs)").containsMatchIn(text))
    val personalSchool = requestingRecords && (performance ||
      Regex("\\b(mi[oaie]|miei|mie|my)\\s+(voti|voto|medie|media|rendimento|agenda|compiti|verifiche|lezioni|assenze|ritardi|note|materiali|documenti|comunicazioni|circolari|grades|lessons)").containsMatchIn(text) ||
      Regex("\\b(che|quali|quanti|quante)\\s+(compiti|verifiche|lezioni|assenze|voti|voto|ritardi|note)\\b.{0,40}\\b(ho|avevo)\\b").containsMatchIn(text))
    val fields = linkedSetOf<String>()
    if (performance) fields += "cv.grades"
    if ((personalSchool || sourceSchool) && Regex("\\b(cosa|che) devo studiare\\b").containsMatchIn(text)) fields += "cv.agenda"
    if ((personalMusic || sourceMusic) && Regex("music|ascolt|artist|canzon|brani|playlist|listening").containsMatchIn(text)) {
      if ("playlist" in text) fields += listOf("fluidify.playlists", "fluidify.playlist_details", "fluidify.playlist_tracks")
      else fields += listOf("fluidify.history_artist_counts", "fluidify.history_track_counts", "fluidify.history_aggregate", "fluidify.history_records")
    }
    val school = mapOf("voti|voto|medie|media scolastic|rendimento scolast|grades" to "grades", "agenda|compiti|verifiche|impegni scolast" to "agenda",
      "lezion|lessons" to "lessons", "comunicazion|circolar" to "communications", "assenz|ritardi" to "absences",
      "note disciplin" to "notes", "materiali scolast" to "materials", "documenti scolast" to "documents")
    school.forEach { (pattern, section) ->
      val ambiguous = section in setOf("lessons", "communications", "materials", "documents", "notes") ||
        (section == "agenda" && !Regex("compiti|verifiche|scolast").containsMatchIn(text))
      if ((personalSchool || sourceSchool) && (!ambiguous || sourceSchool || schoolContext) &&
        !Regex("voto di fiducia|voto elettorale|diritto di voto").containsMatchIn(text) && Regex(pattern).containsMatchIn(text)) fields += "cv.$section"
    }
    return fields.takeIf { it.isNotEmpty() }?.let { ProfileQuery(it.toList(), limit = 8, maxCharacters = 6000) }
  }
  fun pertinent(read: ResearchRead, fields: List<String>): Boolean = if (read.selection.domain == "cv")
    "cv.${read.selection.area}" in fields else "fluidify.${read.tool}" in fields
}
