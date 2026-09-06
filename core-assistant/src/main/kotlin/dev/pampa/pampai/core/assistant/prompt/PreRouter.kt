package dev.pampa.pampai.core.assistant.prompt

import dev.antigravity.fluidengine.ai.tools.AiToolGroup
import dev.pampa.pampai.core.assistant.tools.PampaiGroup
import dev.pampa.pampai.core.assistant.tools.Text

/**
 * Lo stadio zero: una tabella di parole che decide i gruppi senza chiamare nessuno. Se la domanda
 * nomina un gruppo solo, e lo nomina chiaramente, il router remoto si salta e si risparmiano una
 * chiamata e un secondo; se ne sfiora piu' d'uno, i candidati vanno al router come suggerimento.
 * Sbagliare qui costa poco: il modello ha `apri_categoria` per chiedere cio' che manca.
 *
 * Le app collegate (Fase B) aggiungono le loro regole con [extraRules], dai loro cataloghi.
 */
class PreRouter(private val extraRules: List<Rule> = emptyList()) {

  data class Verdict(
    /** I gruppi decisi (se [confident]) o suggeriti. */
    val groups: Set<AiToolGroup>,
    val confident: Boolean,
    /** La domanda chiede di leggere un allegato, uno schermo o un testo lungo: si parte dal livello profondo. */
    val deep: Boolean,
  )

  class Rule(val group: AiToolGroup, pattern: String, val weight: Int = 2, val action: Boolean = false) {
    val regex = Regex(pattern)
  }

  private val rules: List<Rule> = listOf(
    Rule(PampaiGroup.ARIA, "\\b(ricord\\w*|dimentic\\w*|memoria|memorizz\\w*|cosa sai di me|conversazion[ei] (passat|precedent|vecchi)\\w*|avevi detto|mi avevi|cosa (sai|puoi) fare|aiuto|consum[oi]|token|quanto ho speso|app collegat\\w*)\\b"),
    Rule(PampaiGroup.OROLOGIO, "\\b(svegli[ae]|timer|conto alla rovescia|svegliami|allarm[ei])\\b", action = true),
    Rule(PampaiGroup.PROMEMORIA, "\\b(ricordami|promemoria|avvisami|ricordati di dirmi)\\b", action = true),
    Rule(PampaiGroup.CALENDARIO, "\\b(calendario|appuntament[oi]|riunion[ei]|event[oi]|impegn[oi] (di|del|per)|che ho (oggi|domani)|sono libero|libera)\\b"),
    Rule(PampaiGroup.CONTATTI, "\\b(chiam\\w*|telefon\\w*|contatt[oi]|numero di|messaggi[oa]?|whatsapp|sms|scrivi a|mail|email|condivid\\w*|manda a)\\b", action = true),
    Rule(PampaiGroup.NOTIFICHE, "\\b(notific\\w*|mi ha scritto|nuovi messaggi|cosa (e'|e|c e|c'e') arrivato)\\b"),
    Rule(PampaiGroup.NOTIFICHE, "\\b(spotify|youtube|podcast|sta suonando su)\\b", 1),
    Rule(PampaiGroup.SISTEMA, "\\b(torcia|flash|volume|alza|abbassa|muto|silenzios[oa]|vibrazion[ei]|luminosit\\w*|schermo (piu|meno)|ruota|rotazion[ei]|non disturbare|dnd|suoneria)\\b", action = true),
    Rule(PampaiGroup.APRI, "\\b(apri|aprimi|lancia|avvia|vai su|impostazioni (di|del)|app installat\\w*|quali app)\\b", action = true),
    Rule(PampaiGroup.INFO, "\\b(batteria|carica|autonomia|spazio|memoria del telefono|quanto spazio|dove sono|posizione|coordinate|che telefono|versione di android)\\b"),
    Rule(PampaiGroup.WEB, "\\b(cerca|google|notizi[ae]|ultime|wikipedia|significa|definizion[ei]|cos'?e'?|cos e|chi e'|chi e|leggi (la pagina|il sito|questo link|l'articolo)|https?://|www\\.|sito)\\b"),
    Rule(PampaiGroup.CALCOLO, "\\b(calcol\\w*|quanto fa|percentual\\w*|radice|potenza|converti|conversion[ei]|quanti (metri|chilometri|km|litri|grammi|chili|pollici|piedi|miglia|gradi)|in (euro|dollari|sterline|yen|franchi)|cambio|valut[ae]|fuso|che ore sono a|che ora e' a|quanti giorni|quante settimane|che giorno (era|sara'|sara)|fra quanto|mancano)\\b"),
    Rule(PampaiGroup.SCHERMO, "\\b(schermo|sullo schermo|questa pagina|questa schermata|cosa c'?e'? scritto|cosa vedi|cosa (e'|e) questo|traduci (questo|quello che|lo schermo|la pagina)|riassumi (questo|questa|lo schermo)|quest[ao] app)\\b"),
    Rule(PampaiGroup.RIPRODUZIONE, "\\b(metti (su|la musica|un brano|una canzone|qualcosa|un po' di|della musica|un pezzo)|suona|riproduci|play|pausa|ferma la musica|salta|prossim[ao] (brano|canzone)|canzone|brano|musica|volume della musica|shuffle|casuale|ripeti|radio|salva (il brano|la canzone)|cosa sta suonando|che canzone e')\\b", action = true),
    Rule(PampaiGroup.LIBRERIA, "\\b(playlist|ascoltat[oi] di recente|recenti|scaricat[oi]|download|fluidify|dispositiv[oi]|cerca (il brano|la canzone|un brano|un artista)|artista|album)\\b"),
  ) + extraRules

  /** Domande che vogliono un ragionamento su molto materiale: si parte gia' dal modello piu' capace. */
  private val heavySignals = Regex("\\b(analizz\\w*|confront\\w*|tutt[oi] (i|gli|le) |conviene|consigl\\w*|spiegami (bene|in dettaglio)|passo per passo)\\b")

  private val deepSignals = Regex("\\b(allegat[oi]|pdf|document[oi]|screenshot|foto|immagine|cosa c'?e'? scritto|cosa vedi|leggi|riassum\\w*|traduci)\\b")

  fun decide(question: String, actionsEnabled: Boolean, hasAttachments: Boolean = false): Verdict {
    val text = Text.normalize(question)
    val scores = linkedMapOf<AiToolGroup, Int>()
    rules.forEach { rule ->
      val hits = rule.regex.findAll(text).count()
      if (hits > 0) scores[rule.group] = (scores[rule.group] ?: 0) + rule.weight * hits
    }
    val deep = hasAttachments || deepSignals.containsMatchIn(text) && scores.keys.any { it == PampaiGroup.SCHERMO || it == PampaiGroup.WEB } ||
      heavySignals.containsMatchIn(text) || question.length > 400
    if (scores.isEmpty()) return Verdict(emptySet(), confident = false, deep = deep)
    val ranked = scores.entries.sortedByDescending { it.value }
    val top = ranked.first()
    val second = ranked.getOrNull(1)
    val actionRule = rules.any { it.group == top.key && it.action }
    val confident = top.value >= 2 && (second == null || second.value == 0) && !(actionRule && !actionsEnabled)
    val groups = if (confident) setOf(top.key) else ranked.map { it.key }.take(4).toSet()
    return Verdict(groups, confident, deep)
  }
}
