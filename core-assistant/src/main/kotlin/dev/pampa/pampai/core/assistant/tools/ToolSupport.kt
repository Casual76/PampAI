package dev.pampa.pampai.core.assistant.tools

import dev.antigravity.fluidengine.ai.tools.AiTool
import dev.antigravity.fluidengine.ai.tools.ToolOutput
import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/**
 * Le date come le scrive il modello e come le legge l'utente. Il modello riceve sempre `yyyy-MM-dd`
 * piu' il giorno della settimana fra parentesi: la seconda parte e' quella che gli evita di dire
 * "venerdi'" di un sabato. Portato da ClasseViva Expressive, con le ore in piu'.
 */
object Dates {
  private val italian: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
  private val italianShort: DateTimeFormatter = DateTimeFormatter.ofPattern("d/M/yyyy")
  private val time: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

  private val weekdays: Map<String, DayOfWeek> = mapOf(
    "lunedi" to DayOfWeek.MONDAY, "martedi" to DayOfWeek.TUESDAY, "mercoledi" to DayOfWeek.WEDNESDAY,
    "giovedi" to DayOfWeek.THURSDAY, "venerdi" to DayOfWeek.FRIDAY, "sabato" to DayOfWeek.SATURDAY, "domenica" to DayOfWeek.SUNDAY,
  )

  /**
   * Una data scritta dal modello: ISO, italiana, o una parola ("oggi", "domani", "ieri", "lunedi'",
   * "lunedi' prossimo"). Un giorno della settimana e' la prossima occorrenza, oggi compreso.
   */
  fun parse(raw: String?, today: LocalDate): LocalDate? {
    val text = Text.normalize(raw ?: return null)
    if (text.isEmpty()) return null
    when (text) {
      "oggi" -> return today
      "domani" -> return today.plusDays(1)
      "dopodomani" -> return today.plusDays(2)
      "ieri" -> return today.minusDays(1)
      "l altro ieri", "l'altro ieri", "altro ieri" -> return today.minusDays(2)
    }
    runCatching { return LocalDate.parse(text.take(10)) }
    runCatching { return LocalDate.parse(text, italian) }
    runCatching { return LocalDate.parse(text, italianShort) }
    // "lunedi'" con l'apostrofo al posto dell'accento e' la grafia piu' comune del modello.
    val words = text.split(" ").map { it.trim('\'', '.') }
    val day = words.firstNotNullOfOrNull { weekdays[it] } ?: return null
    val next = words.any { it.startsWith("prossim") }
    val previous = words.any { it.startsWith("scors") || it.startsWith("passat") }
    return when {
      previous -> today.with(TemporalAdjusters.previous(day))
      next && today.dayOfWeek == day -> today.plusWeeks(1)
      else -> today.with(TemporalAdjusters.nextOrSame(day))
    }
  }

  /** Un'ora scritta dal modello: "18", "18:30", "18.30", "6 e mezza", "mezzogiorno", "mezzanotte". */
  fun parseTime(raw: String?): LocalTime? {
    val text = Text.normalize(raw ?: return null).replace(".", ":")
    if (text.isEmpty()) return null
    when {
      text.startsWith("mezzogiorno") -> return LocalTime.NOON
      text.startsWith("mezzanotte") -> return LocalTime.MIDNIGHT
    }
    val half = text.contains("e mezza") || text.contains("e mezzo")
    val quarter = text.contains("e un quarto")
    val digits = Regex("(\\d{1,2})(?::(\\d{2}))?").find(text) ?: return null
    val hour = digits.groupValues[1].toIntOrNull()?.takeIf { it in 0..23 } ?: return null
    val minute = digits.groupValues[2].toIntOrNull() ?: if (half) 30 else if (quarter) 15 else 0
    if (minute !in 0..59) return null
    val pm = text.contains("pomeriggio") || text.contains("sera") || text.contains("pm")
    val adjusted = if (pm && hour < 12) hour + 12 else hour
    return LocalTime.of(adjusted, minute)
  }

  /**
   * Una data con ora ("domani alle 18", "2026-09-07 08:30", "venerdi' 7:15"): la data con [parse],
   * l'ora con [parseTime]; senza ora, l'ora di [defaultTime]; senza data, oggi (o domani se l'ora
   * e' gia' passata e [rollForward]).
   */
  fun parseDateTime(raw: String?, now: LocalDateTime, defaultTime: LocalTime = LocalTime.of(9, 0), rollForward: Boolean = true): LocalDateTime? {
    val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val iso = runCatching { LocalDateTime.parse(text.replace(' ', 'T')) }.getOrNull()
    if (iso != null) return iso
    val parts = text.split(Regex("\\s+alle\\s+|\\s+ore\\s+|\\s+"), limit = 2)
    val timePart = Regex("(\\d{1,2}[:.]\\d{2}|\\b\\d{1,2}\\b(?=\\s*$)|mezzogiorno|mezzanotte)").find(text)?.value
    val time = parseTime(timePart)
    val datePart = if (timePart != null) text.replace(timePart, "").replace(Regex("\\b(alle|ore)\\b"), "").trim() else text
    val date = parse(datePart.ifBlank { null }, now.toLocalDate())
    return when {
      date != null && time != null -> LocalDateTime.of(date, time)
      date != null -> LocalDateTime.of(date, defaultTime)
      time != null -> {
        val candidate = LocalDateTime.of(now.toLocalDate(), time)
        if (rollForward && candidate.isBefore(now)) candidate.plusDays(1) else candidate
      }
      else -> if (parts.size == 1 && parts[0].isBlank()) null else null
    }
  }

  /** L'intervallo di una richiesta: [from]-[to] se dati, altrimenti [defaultDays] giorni da oggi in avanti. */
  fun range(from: String?, to: String?, today: LocalDate, defaultDays: Long): ClosedRange<LocalDate> {
    val start = parse(from, today)
    val end = parse(to, today)
    return when {
      start != null && end != null -> if (end < start) end..start else start..end
      start != null -> start..start.plusDays(defaultDays)
      end != null -> today..end
      else -> today..today.plusDays(defaultDays)
    }
  }

  fun label(date: LocalDate): String = "$date (${shortDay(date.dayOfWeek)})"

  fun label(dateTime: LocalDateTime): String = "${label(dateTime.toLocalDate())} ${dateTime.toLocalTime().format(time)}"

  fun label(dateTime: ZonedDateTime): String = label(dateTime.toLocalDateTime())

  fun shortDay(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "lun"
    DayOfWeek.TUESDAY -> "mar"
    DayOfWeek.WEDNESDAY -> "mer"
    DayOfWeek.THURSDAY -> "gio"
    DayOfWeek.FRIDAY -> "ven"
    DayOfWeek.SATURDAY -> "sab"
    DayOfWeek.SUNDAY -> "dom"
  }

  fun longDay(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "lunedi'"
    DayOfWeek.TUESDAY -> "martedi'"
    DayOfWeek.WEDNESDAY -> "mercoledi'"
    DayOfWeek.THURSDAY -> "giovedi'"
    DayOfWeek.FRIDAY -> "venerdi'"
    DayOfWeek.SATURDAY -> "sabato"
    DayOfWeek.SUNDAY -> "domenica"
  }
}

/** Confronti fra testi scritti da persone diverse: senza accenti, senza maiuscole, senza doppi spazi. */
object Text {
  fun normalize(text: String): String {
    val decomposed = Normalizer.normalize(text.lowercase(Locale.ITALIAN), Normalizer.Form.NFD)
    return decomposed.replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9/'\\-.:]+"), " ").trim().replace(Regex(" +"), " ")
  }

  /** Vero se tutte le parole di [query] compaiono in [candidate] (come prefissi di parola). */
  fun matches(query: String, candidate: String): Boolean {
    val words = normalize(query).split(" ").filter { it.length > 1 }
    if (words.isEmpty()) return false
    val target = normalize(candidate)
    val targetWords = target.split(" ")
    return words.all { w -> targetWords.any { it.startsWith(w) } || target.contains(w) }
  }

  /** Quante parole di [query] compaiono in [candidate]: zero se nessuna. Per la ricerca larga. */
  fun score(query: String, candidate: String): Int {
    val words = normalize(query).split(" ").filter { it.length > 2 }
    if (words.isEmpty()) return 0
    val target = normalize(candidate)
    return words.count { target.contains(it) }
  }

  /** Una riga sola, tagliata: per gli elenchi che tornano al modello. */
  fun oneLine(text: String, max: Int = 120): String {
    val flat = text.replace(Regex("\\s+"), " ").trim()
    return if (flat.length <= max) flat else flat.take(max - 1) + "…"
  }
}

/** Una chiamata a uno strumento com'e' andata davvero: argomenti, durata, esito, anteprima. */
data class PampaiToolTrace(
  val name: String,
  val args: String,
  val millis: Long,
  val ok: Boolean,
  val chars: Int,
  val preview: String,
  /** L'app collegata che l'ha eseguito, null se locale. */
  val app: String? = null,
)

/**
 * Avvolge uno strumento e annota ogni chiamata nel contesto della domanda. Le tracce finiscono
 * nella telemetria dello scambio e nei "passi" sotto la risposta: la diagnosi di una risposta
 * storta non deve richiedere un cavo.
 */
class TracedTool(private val inner: AiTool<PampaiToolContext>, private val app: String? = null) : AiTool<PampaiToolContext> by inner {

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    val started = System.currentTimeMillis()
    val output = try {
      inner.run(args, ctx)
    } catch (e: CancellationException) {
      ctx.trace(PampaiToolTrace(inner.name, compact(args), System.currentTimeMillis() - started, ok = false, chars = 0, preview = "fermato prima di finire", app = app))
      throw e
    } catch (e: Throwable) {
      ctx.trace(PampaiToolTrace(inner.name, compact(args), System.currentTimeMillis() - started, ok = false, chars = 0, preview = "eccezione: ${e.message ?: e::class.simpleName}", app = app))
      throw e
    }
    ctx.trace(
      PampaiToolTrace(
        name = inner.name,
        args = compact(args),
        millis = System.currentTimeMillis() - started,
        ok = !output.text.startsWith("errore"),
        chars = output.text.length,
        preview = output.text.take(PREVIEW_CHARS),
        app = app,
      ),
    )
    return output
  }

  private fun compact(args: JsonObject): String = args.toString().take(ARGS_CHARS)

  companion object {
    const val ARGS_CHARS = 160
    const val PREVIEW_CHARS = 240
  }
}
