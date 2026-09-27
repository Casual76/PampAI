package dev.pampa.pampai.core.assistant.prompt

import dev.pampa.pampai.core.assistant.tools.Dates
import dev.pampa.pampai.core.assistant.tools.Text
import dev.pampa.pampai.core.assistant.tools.device.Durations
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * I comandi che non hanno bisogno di un modello: "timer di 10 minuti", "accendi la torcia",
 * "che ore sono". Riconosciuti qui, con regole a frase intera, vanno dritti allo strumento e la
 * risposta e' un modello di frase: niente rete, niente chiavi, niente attesa.
 *
 * La regola d'oro e' la precisione, non la copertura: una frase che si capisce solo a meta' (o
 * che chiede due cose, o un ragionamento) torna `null` e la domanda prende la strada normale.
 * Sbagliare qui vuol dire fare una cosa che l'utente non ha chiesto; mancare un colpo costa solo
 * un secondo in piu'.
 */
object QuickCommands {

  enum class Kind { TIMER, ALARM, NEXT_ALARM, TORCH, VOLUME, RINGER, DND, OPEN_APP, BATTERY, TIME, DATE, CALC, MUSIC }

  /**
   * Un comando riconosciuto: lo strumento da chiamare (null per quelli che si rispondono da soli,
   * come l'ora) e i suoi argomenti, gia' nella forma che lo strumento si aspetta.
   */
  data class Match(val kind: Kind, val tool: String?, val args: Map<String, String> = emptyMap())

  /** Le frasi piu' lunghe di cosi' raramente sono solo un comando. */
  private const val MAX_CHARS = 70

  /** Parole che legano due richieste o chiedono un ragionamento: con queste decide il modello. */
  private val compound = Regex("\\b(poi|dopo|anche|inoltre|oppure|se|quando|perche|come mai|ma|pero|invece|mentre|finche|appena)\\b")

  private val politePrefix = Regex("^(?:(?:ehi|ei|hey|ok|ciao)\\s+)?(?:aria\\s+)?(?:(?:per favore|per piacere|perfavore)\\s+)?(?:(?:puoi|potresti|riesci a|mi puoi|me lo|dai|vorrei|voglio)\\s+)?(?:mi\\s+)?")
  private val vocative = Regex("^(?:(?:ehi|ei|hey|ok|ciao)\\s+)?aria\\s*,\\s*", RegexOption.IGNORE_CASE)
  private val politeSuffix = Regex("\\s+(?:per favore|per piacere|grazie|aria|subito|dai)$")

  // Le durate: "10 minuti", "un'ora e mezza", "1 ora e 30", "45 secondi", "1:30".
  private const val UNIT = "(?:ore|ora|h|minuti|minuto|min|m|secondi|secondo|sec|s)"
  private const val AMOUNT = "(?:\\d+(?:[.,]\\d+)?|un|una|un'|mezz'|mezza|mezzo)"
  private const val DURATION = "(?:\\d{1,2}:\\d{2}(?::\\d{2})?|$AMOUNT\\s*$UNIT(?:\\s+e\\s+(?:mezz[oa]|\\d{1,2}(?:\\s*$UNIT)?|$AMOUNT\\s*$UNIT))*)"

  private val timer = Regex(
    "^(?:(?:impost\\w*|mett\\w*|avvi\\w*|fai partire|far partire|fammi partire|cre\\w*|parti con|sett\\w*|fammi)\\s+)?(?:un\\s+|il\\s+)?(?:timer|conto alla rovescia|countdown)\\s+(?:di\\s+|da\\s+|per\\s+)?($DURATION)(?:\\s+per\\s+(?:la\\s+|il\\s+|lo\\s+|le\\s+|i\\s+|gli\\s+|l')?([a-z][a-z' ]{1,28}))?$",
  )
  private val timerTail = Regex("^(?:(?:impost\\w*|mett\\w*|avvi\\w*|fai partire|cre\\w*)\\s+)?($DURATION)\\s+di\\s+timer$")

  private const val CLOCK = "(\\d{1,2}(?:[:.]\\d{2})?(?:\\s+e\\s+(?:mezza|mezzo|un quarto))?|mezzogiorno|mezzanotte)"
  private val alarm = Regex(
    "^(?:svegliami|(?:impost\\w*|mett\\w*|cre\\w*|punt\\w*|sett\\w*)\\s+(?:una\\s+|la\\s+)?sveglia|sveglia)(?:\\s+(?:domani|domattina|domani mattina))?\\s+(?:alle|per le|a le|all')\\s*$CLOCK(?:\\s+(del mattino|di mattina|della mattina|del pomeriggio|di pomeriggio|di sera|della sera|stasera))?(?:\\s+(?:domani|domattina|domani mattina))?$",
  )

  private val nextAlarm = Regex(
    "^(?:a che ora (?:e|suona|ho) la (?:mia )?(?:prossima )?sveglia|quando suona la (?:prossima )?sveglia|(?:ho|c e|c'e) (?:una |delle |la )?svegli[ae](?: (?:per )?domani| impostat[ae])?|(?:qual e |dimmi )?(?:la )?prossima sveglia)$",
  )

  private val torch = Regex("^(accend\\w*|attiv\\w*|speg\\w*|disattiv\\w*)\\s+(?:la\\s+|il\\s+)?(?:torcia|flash|luce del telefono|lampada)$")
  private val torchState = Regex("^(?:torcia|flash)\\s+(on|off|accesa|spenta)$")

  private val volumeStep = Regex("^(alz\\w*|abbass\\w*|aument\\w*|diminui\\w*)\\s+(?:un po'?\\s+)?(?:il\\s+)?volume(?:\\s+(?:della musica|dei media|dei contenuti multimediali))?(?:\\s+un po'?)?$")
  private val volumeLevel = Regex("^(?:(?:mett\\w*|impost\\w*|port\\w*)\\s+)?(?:il\\s+)?volume\\s+(?:a|al|allo)\\s+(\\d{1,3}|massimo|zero|minimo)(?:\\s*(?:percento|per cento))?$")
  private val volumeShort = Regex("^volume\\s+(su|giu|piu|meno|\\d{1,3})$")

  private val ringer = Regex("^(?:(?:metti|mettimi|imposta)\\s+)?(?:il\\s+telefono\\s+)?(?:in|la|a)\\s+(silenzioso|vibrazione|modalita silenziosa|modalita vibrazione|suoneria normale)$|^(togli(?:\\s+il)?\\s+silenzioso|togli(?:\\s+la)?\\s+vibrazione|riattiva la suoneria)$")

  private val dnd = Regex("^(attiv\\w*|accend\\w*|mett\\w*|disattiv\\w*|speg\\w*|togli\\w*)\\s+(?:il\\s+|la\\s+modalita\\s+)?non disturbare$")

  private val openApp = Regex("^(?:apri|aprimi|aprire|apra|lancia|lanciami|lanciare|avvia|avviami|avviare)\\s+(?:l'app\\s+|l app\\s+|app\\s+|l'applicazione\\s+|la\\s+|il\\s+|lo\\s+|l')?([a-z0-9][a-z0-9 .'&+-]{1,28})$")

  /** Parole che dicono "non un'app": una pagina, un link, un file, un contatto. */
  private val notAnApp = Regex("\\b(pagina|sito|link|file|documento|foto|immagine|chat|conversazione|messaggio|mail|email|notific\\w*|impostazion\\w*|impostazioni|porta|finestra|questo|questa|quello|quella|http|www|un|una|il mio|la mia|mappa di|ultima|ultimo|musica|timer|sveglia|canzone|brano|playlist|radio|torcia|volume)\\b")

  private val battery = Regex("^(?:quanta\\s+)?batteria(?:\\s+(?:ho|mi resta|mi rimane|rimasta|c e|c'e|residua))?$|^quanta batteria (?:ho|mi (?:e |resta |rimane )?(?:rimasta)?|c'e|c e|resta|rimane)$|^(?:a )?quanto (?:sta|e|ho) (?:la )?batteria$|^(?:livello|stato) (?:della )?batteria$|^(?:quanto e|a che punto e) carico il telefono$")

  private val time = Regex("^(?:che ore sono|che ora e|che ora sono|dimmi l'ora|dimmi l ora|mi dici che ore sono|sai che ore sono|ora esatta|l'ora|che ore si sono fatte)$")
  private val date = Regex("^(?:che giorno e(?: oggi)?|oggi che giorno e|che data e(?: oggi)?|qual e la data di oggi|quanti ne abbiamo(?: oggi)?|che giorno e della settimana|la data di oggi|che giorno siamo(?: oggi)?)$")

  // La musica solo con un nome che dica "musica": "la prossima", "salta", "pausa" da soli in una
  // conversazione sono la prossima domanda di un quiz o una pausa dallo studio, non un brano.
  private const val TRACK = "(?:la musica|la canzone|il brano|la traccia|questa canzone|questo brano)"
  private val musicPause = Regex("^(?:metti\\s+)?in\\s+pausa\\s+$TRACK$|^(?:ferma|stoppa|interrompi)\\s+$TRACK$|^pausa\\s+$TRACK$")
  private val musicPlay = Regex("^(?:riprendi|fai ripartire|riparti con|rimetti)\\s+$TRACK$")
  private val musicNext = Regex("^(?:(?:metti|passa a|passa alla|vai alla|vai al)\\s+)?(?:la\\s+|il\\s+)?(?:prossim[oa]|successiv[oa])\\s+(?:canzone|brano|traccia)$|^(?:canzone|brano|traccia)\\s+successiv[oa]$|^(?:salta|cambia)\\s+(?:questa\\s+|questo\\s+)?(?:canzone|brano|traccia)$")
  private val musicPrevious = Regex("^(?:(?:metti|torna a|torna alla|torna al|vai alla|vai al)\\s+)?(?:la\\s+|il\\s+)?(?:canzone|brano|traccia)\\s+(?:precedente|di prima)$")

  private val calcPrefix = Regex("^(?:quanto fa|quanto e|calcola|calcolami|fammi il calcolo|risultato di)\\s+", RegexOption.IGNORE_CASE)
  private val calcBody = Regex("^[0-9+\\-*/x×÷^().,%\\s]+$")
  private val strongOperator = Regex("[+*×÷^]|\\d\\s*x\\s*\\d")

  /**
   * La domanda e' un comando rapido? `null` = no, se ne occupa il modello. [now] serve alle
   * sveglie: "svegliami domani alle 11" detto alle 10 non si puo' fare con l'app Orologio (che
   * imposta la prossima occorrenza di quell'ora, cioe' oggi), quindi lo decide il modello.
   */
  fun match(question: String, now: LocalTime = LocalTime.now()): Match? {
    // "Aria, ..." e' un vocativo, non una seconda richiesta: la virgola dopo il nome non conta.
    val raw = question.trim().trimEnd('?', '!', '.', ' ').replace(vocative, "")
    if (raw.isEmpty() || raw.length > MAX_CHARS || raw.contains('\n')) return null
    calc(raw)?.let { return it }
    if (raw.contains(',') || raw.contains(';')) return null
    val text = clean(raw) ?: return null
    if (compound.containsMatchIn(text)) return null

    timer.find(text)?.let { m ->
      val seconds = Durations.parseSeconds(m.groupValues[1])?.takeIf { it in 1..24 * 3600 } ?: return null
      val args = mutableMapOf("durata" to m.groupValues[1])
      m.groupValues[2].trim().takeIf { it.isNotEmpty() }?.let { args["etichetta"] = it }
      return Match(Kind.TIMER, "timer_crea", args).takeIf { seconds > 0 }
    }
    timerTail.find(text)?.let { m ->
      Durations.parseSeconds(m.groupValues[1])?.takeIf { it in 1..24 * 3600 } ?: return null
      return Match(Kind.TIMER, "timer_crea", mapOf("durata" to m.groupValues[1]))
    }
    alarm.find(text)?.let { m ->
      val spoken = listOf(m.groupValues[1], m.groupValues[2]).filter { it.isNotBlank() }.joinToString(" ")
      val parsed = Dates.parseTime(spoken) ?: return null
      // "alle 7 di sera" o "alle 3 del pomeriggio": parseTime lo sa gia'. "Stasera" no.
      val hour = if (m.groupValues[2] == "stasera" && parsed.hour < 12) parsed.hour + 12 else parsed.hour
      val tomorrow = Regex("\\b(domani|domattina)\\b").containsMatchIn(text)
      if (tomorrow && LocalTime.of(hour, parsed.minute).isAfter(now)) return null
      return Match(Kind.ALARM, "sveglia_crea", mapOf("ora" to "%02d:%02d".format(hour, parsed.minute)))
    }
    if (nextAlarm.matches(text)) return Match(Kind.NEXT_ALARM, "sveglia_prossima")

    torch.find(text)?.let { m ->
      val on = m.groupValues[1].startsWith("acc") || m.groupValues[1].startsWith("attiv")
      return Match(Kind.TORCH, "torcia", mapOf("stato" to if (on) "on" else "off"))
    }
    torchState.find(text)?.let { m ->
      return Match(Kind.TORCH, "torcia", mapOf("stato" to if (m.groupValues[1] in setOf("on", "accesa")) "on" else "off"))
    }

    volumeStep.find(text)?.let { m ->
      val up = m.groupValues[1].startsWith("alz") || m.groupValues[1].startsWith("aument")
      return Match(Kind.VOLUME, "volume", mapOf("livello" to if (up) "su" else "giu"))
    }
    volumeLevel.find(text)?.let { m ->
      val level = when (val v = m.groupValues[1]) {
        "massimo" -> "massimo"
        "zero", "minimo" -> "muto"
        else -> v.toIntOrNull()?.takeIf { it in 0..100 }?.toString() ?: return null
      }
      return Match(Kind.VOLUME, "volume", mapOf("livello" to level))
    }
    volumeShort.find(text)?.let { m ->
      val level = when (val v = m.groupValues[1]) {
        "su", "piu" -> "su"
        "giu", "meno" -> "giu"
        else -> v.toIntOrNull()?.takeIf { it in 0..100 }?.toString() ?: return null
      }
      return Match(Kind.VOLUME, "volume", mapOf("livello" to level))
    }

    ringer.find(text)?.let { m ->
      val phrase = m.groupValues[1].ifEmpty { m.groupValues[2] }
      val mode = when {
        phrase.startsWith("togli") || phrase.startsWith("riattiva") || phrase == "suoneria normale" -> "normale"
        phrase.contains("vibrazione") -> "vibrazione"
        else -> "silenzioso"
      }
      return Match(Kind.RINGER, "modalita_suoneria", mapOf("modo" to mode))
    }

    dnd.find(text)?.let { m ->
      val verb = m.groupValues[1]
      val on = verb.startsWith("attiv") || verb.startsWith("accend") || verb.startsWith("mett")
      return Match(Kind.DND, "non_disturbare", mapOf("stato" to if (on) "on" else "off"))
    }

    if (time.matches(text)) return Match(Kind.TIME, null)
    if (date.matches(text)) return Match(Kind.DATE, null)
    if (battery.matches(text)) return Match(Kind.BATTERY, "batteria")

    if (musicPause.matches(text)) return Match(Kind.MUSIC, "musica_controllo", mapOf("azione" to "pausa"))
    if (musicPlay.matches(text)) return Match(Kind.MUSIC, "musica_controllo", mapOf("azione" to "riproduci"))
    if (musicNext.matches(text)) return Match(Kind.MUSIC, "musica_controllo", mapOf("azione" to "avanti"))
    if (musicPrevious.matches(text)) return Match(Kind.MUSIC, "musica_controllo", mapOf("azione" to "indietro"))

    openApp.find(text)?.let { m ->
      val name = m.groupValues[1].trim()
      if (notAnApp.containsMatchIn(name) || name.split(' ').size > 3) return null
      return Match(Kind.OPEN_APP, "apri_app", mapOf("nome" to name))
    }
    return null
  }

  /** "quanto fa 12*7", "15% di 80" no (lo fa il modello), "3 + 4" si'. */
  private fun calc(raw: String): Match? {
    val prefixed = calcPrefix.find(raw)
    val body = (if (prefixed != null) raw.substring(prefixed.range.last + 1) else raw).trim().trimEnd('=').trim()
    if (body.isEmpty() || !calcBody.matches(body) || body.none { it.isDigit() }) return null
    // Senza "quanto fa" serve un operatore che non si confonda con una data (12/05) o un numero (-3).
    if (prefixed == null && !strongOperator.containsMatchIn(body)) return null
    if (prefixed != null && body.none { it in "+-*/x×÷^%" }) return null
    // Due passaggi: in "2x3x4" il secondo "x" condivide la cifra con il primo match.
    val expression = body.replace(Regex("(\\d)\\s*x\\s*(\\d)"), "$1*$2").replace(Regex("(\\d)\\s*x\\s*(\\d)"), "$1*$2")
    return Match(Kind.CALC, "calcola", mapOf("espressione" to expression))
  }

  private fun clean(raw: String): String? {
    var text = Text.normalize(raw).replace(" :", ":")
    text = text.replace(politePrefix, "").trim()
    repeat(2) { text = text.replace(politeSuffix, "").trim() }
    return text.takeIf { it.isNotEmpty() }
  }

  private val hhmm: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

  /**
   * La frase di risposta, dal risultato dello strumento. `null` se il risultato non e' un
   * successo pulito: allora la domanda torna al modello, che sa spiegare cosa e' andato storto.
   */
  fun reply(match: Match, output: String?, now: ZonedDateTime): String? {
    if (match.tool != null && (output == null || output.startsWith("errore") || !output.contains(':'))) return null
    val lines = output.orEmpty().lines().mapNotNull { line ->
      val i = line.indexOf(':')
      if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
    }.toMap()
    // Un'azione riuscita lo dice con la riga "fatto": senza (azioni spente, permesso mancante) la
    // frase la scrive il modello, che sa spiegare cosa fare.
    if (match.kind in ACTIONS && "fatto" !in lines) return null
    return when (match.kind) {
      Kind.TIME -> "Sono le ${now.format(hhmm)}."
      Kind.DATE -> "Oggi è ${dayName(now.dayOfWeek)} ${now.dayOfMonth} ${now.month.getDisplayName(TextStyle.FULL, Locale.ITALIAN)} ${now.year}."
      Kind.TIMER -> {
        val seconds = Durations.parseSeconds(match.args["durata"]) ?: return null
        val end = now.plusSeconds(seconds.toLong())
        val label = match.args["etichetta"]?.let { " per $it" } ?: ""
        "Timer di ${Durations.label(seconds)}$label avviato: suona alle ${end.format(hhmm)}."
      }
      Kind.ALARM -> {
        val at = match.args["ora"] ?: return null
        val time = Dates.parseTime(at) ?: return null
        val next = now.toLocalDate().atTime(time).let { if (it.isAfter(now.toLocalDateTime())) "oggi" else "domani" }
        "Sveglia impostata alle $at ($next)."
      }
      Kind.NEXT_ALARM -> {
        val next = lines["prossima sveglia"] ?: return null
        if (next == "nessuna impostata") "Non hai sveglie impostate." else "La prossima sveglia suona ${pretty(next, now)}, fra ${lines["fra"] ?: "poco"}."
      }
      Kind.BATTERY -> {
        val level = lines["batteria"] ?: return null
        val state = lines["stato"]?.takeIf { it != "sconosciuto" }?.let { ", $it" } ?: ""
        "Batteria al $level$state."
      }
      Kind.CALC -> {
        val result = lines["risultato"] ?: return null
        "${match.args["espressione"]?.replace("*", " × ")?.replace("/", " ÷ ")} = **$result**"
      }
      Kind.OPEN_APP -> lines["fatto"]?.removePrefix("aperta ")?.let { "Apro $it." }
      Kind.VOLUME -> lines["fatto"]?.let { sentence(it.replace("volume media", "volume")) }
      Kind.MUSIC -> {
        val track = lines["brano"]?.substringBefore(" · ")?.let { " — $it" } ?: ""
        when (match.args["azione"]) {
          "pausa" -> "Musica in pausa."
          "riproduci" -> "Riprendo la musica$track."
          "avanti" -> "Brano successivo$track."
          "indietro" -> "Brano precedente$track."
          else -> null
        }
      }
      else -> lines["fatto"]?.let { sentence(it) }
    }
  }

  private val ACTIONS = setOf(Kind.TIMER, Kind.ALARM, Kind.TORCH, Kind.VOLUME, Kind.RINGER, Kind.DND, Kind.OPEN_APP, Kind.MUSIC)

  /** "lunedi'" -> "lunedì": nelle frasi per l'utente, l'accento vero. */
  private fun dayName(day: java.time.DayOfWeek): String = Dates.longDay(day).trimEnd('\'').let { if (it.endsWith("di")) it.dropLast(1) + "ì" else it }

  /** "2026-09-28 (lun) 07:00" -> "domani alle 07:00" / "lunedì 28 alle 07:00". */
  private fun pretty(label: String, now: ZonedDateTime): String {
    val date = runCatching { java.time.LocalDate.parse(label.take(10)) }.getOrNull() ?: return label
    val clock = label.takeLast(5)
    return when (date) {
      now.toLocalDate() -> "oggi alle $clock"
      now.toLocalDate().plusDays(1) -> "domani alle $clock"
      else -> "${dayName(date.dayOfWeek)} ${date.dayOfMonth} alle $clock"
    }
  }

  private fun sentence(text: String): String = text.replaceFirstChar { it.uppercase() }.let { if (it.endsWith('.')) it else "$it." }
}
