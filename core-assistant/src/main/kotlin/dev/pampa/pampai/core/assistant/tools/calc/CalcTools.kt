package dev.pampa.pampai.core.assistant.tools.calc

import dev.antigravity.fluidengine.ai.tools.AiTool
import dev.antigravity.fluidengine.ai.tools.AiToolGroup
import dev.antigravity.fluidengine.ai.tools.Args.double
import dev.antigravity.fluidengine.ai.tools.Args.str
import dev.antigravity.fluidengine.ai.tools.Schema
import dev.antigravity.fluidengine.ai.tools.ToolOutput
import dev.antigravity.fluidengine.ai.tools.ToolText
import dev.pampa.pampai.core.assistant.tools.Dates
import dev.pampa.pampai.core.assistant.tools.PampaiGroup
import dev.pampa.pampai.core.assistant.tools.PampaiToolContext
import dev.pampa.pampai.core.assistant.tools.Text
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import net.objecthunter.exp4j.ExpressionBuilder

/** Un numero come lo legge un italiano: senza zeri inutili, con la virgola. */
internal fun Double.pretty(decimals: Int = 6): String {
  if (this.isNaN() || this.isInfinite()) return this.toString()
  val rounded = String.format(Locale.US, "%.${decimals}f", this).trimEnd('0').trimEnd('.')
  val plain = if (rounded == "-0") "0" else rounded
  return plain.replace('.', ',')
}

class CalcolaTool : AiTool<PampaiToolContext> {
  override val name = "calcola"
  override val group: AiToolGroup = PampaiGroup.CALCOLO
  override val description = "Calcola un'espressione matematica esatta: + - * / ^ %, parentesi, sqrt, sin, cos, tan, log, ln, abs, pi, e. Le percentuali si scrivono come frazioni (15% di 340 = 340*0.15)."
  override val parameters = Schema.obj(mapOf("espressione" to Schema.str("l'espressione, con il punto come separatore decimale")), required = listOf("espressione"))

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    val raw = args.str("espressione") ?: return ToolOutput.error("manca l'espressione")
    val expression = raw.replace(",", ".").replace("×", "*").replace("÷", "/").replace("√", "sqrt").replace(Regex("(\\d)\\s*%"), "$1/100")
    return try {
      val value = ExpressionBuilder(expression).build().evaluate()
      ToolText.output { line("espressione", expression); line("risultato", value.pretty()) }
    } catch (e: Exception) {
      ToolOutput.error("espressione non valida (${e.message ?: "sintassi"}); usa il punto per i decimali e * per la moltiplicazione")
    }
  }
}

class ConvertiUnitaTool : AiTool<PampaiToolContext> {
  override val name = "converti_unita"
  override val group: AiToolGroup = PampaiGroup.CALCOLO
  override val description = "Converte un valore fra unita' di misura: lunghezza, massa, temperatura, velocita', area, volume, dati, tempo, energia. Per le valute usa `valuta`."
  override val parameters = Schema.obj(
    mapOf("valore" to Schema.str("il numero"), "da" to Schema.str("unita' di partenza (km, m, cm, mi, ft, in, kg, g, lb, oz, °C, °F, K, km/h, m/s, mph, nodi, m2, ha, l, ml, gal, GB, MB, h, min, s, kcal, kWh…)"), "a" to Schema.str("unita' di arrivo")),
    required = listOf("valore", "da", "a"),
  )

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    val value = args.double("valore") ?: return ToolOutput.error("manca il valore")
    val from = args.str("da") ?: return ToolOutput.error("manca l'unita' di partenza")
    val to = args.str("a") ?: return ToolOutput.error("manca l'unita' di arrivo")
    return when (val result = UnitConverter.convert(value, from, to)) {
      is UnitConverter.Result.Ok -> ToolText.output { line("da", "${value.pretty()} ${result.fromLabel}"); line("a", "${result.value.pretty()} ${result.toLabel}") }
      is UnitConverter.Result.UnknownUnit -> ToolOutput.error("unita' sconosciuta: ${result.unit}; conosco ${UnitConverter.knownSample()}")
      is UnitConverter.Result.Incompatible -> ToolOutput.error("${result.from} e ${result.to} misurano cose diverse")
    }
  }
}

class FusoOrarioTool : AiTool<PampaiToolContext> {
  override val name = "fuso_orario"
  override val group: AiToolGroup = PampaiGroup.CALCOLO
  override val description = "Che ore sono in una citta' o zona (es. Tokyo, New York, Europe/London) e la differenza con qui; con `ora` converte un orario di qui in quello di la' (o viceversa con `da_li`)."
  override val parameters = Schema.obj(mapOf("luogo" to Schema.str("citta' o zona"), "ora" to Schema.str("un orario da convertire, es. 18:30 (facoltativo)"), "da_li" to Schema.bool("vero se `ora` e' l'ora di la' da portare qui")), required = listOf("luogo"))

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    val place = args.str("luogo") ?: return ToolOutput.error("manca il luogo")
    val zone = TimeZones.resolve(place) ?: return ToolOutput.error("non conosco il fuso di \"$place\"; prova con la capitale o la zona (Europe/London)")
    val nowHere = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(ctx.now()), ctx.zone)
    val nowThere = nowHere.withZoneSameInstant(zone)
    val offset = (nowThere.offset.totalSeconds - nowHere.offset.totalSeconds) / 3600.0
    val time = Dates.parseTime(args.str("ora"))
    val fromThere = (args["da_li"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
    return ToolText.output {
      line("luogo", "$place (${zone.id})")
      line("ora la'", nowThere.format(DateTimeFormatter.ofPattern("HH:mm, EEEE d MMMM", Locale.ITALIAN)))
      line("ora qui", nowHere.format(DateTimeFormatter.ofPattern("HH:mm")))
      line("differenza", if (offset == 0.0) "nessuna" else (if (offset > 0) "+" else "") + offset.pretty(1) + " ore")
      if (time != null) {
        val converted = if (fromThere) nowThere.with(time).withZoneSameInstant(ctx.zone) else nowHere.with(time).withZoneSameInstant(zone)
        line(if (fromThere) "le ${time.format(DateTimeFormatter.ofPattern("HH:mm"))} la' sono qui" else "le ${time.format(DateTimeFormatter.ofPattern("HH:mm"))} qui sono la'", converted.format(DateTimeFormatter.ofPattern("HH:mm (EEEE)", Locale.ITALIAN)))
      }
    }
  }
}

class DataCalcolaTool : AiTool<PampaiToolContext> {
  override val name = "data_calcola"
  override val group: AiToolGroup = PampaiGroup.CALCOLO
  override val description = "Conti fra date: quanti giorni/settimane fra due date, che giorno della settimana e' una data, che data sara' fra N giorni, quanti giorni mancano a una data."
  override val parameters = Schema.obj(mapOf("data" to Schema.str("una data (aaaa-mm-gg, gg/mm/aaaa, oggi, domani, lunedi'...)"), "a" to Schema.str("la seconda data, per una differenza (facoltativa)"), "aggiungi_giorni" to Schema.int("giorni da aggiungere a `data` (negativi per togliere)")))

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    val today = ctx.today
    val date = Dates.parse(args.str("data"), today) ?: today
    val other = Dates.parse(args.str("a"), today)
    val add = args.double("aggiungi_giorni")?.toLong()
    return ToolText.output {
      line("data", "${Dates.label(date)}, ${Dates.longDay(date.dayOfWeek)} ${date.dayOfMonth} ${date.month.getDisplayName(java.time.format.TextStyle.FULL, Locale.ITALIAN)} ${date.year}")
      if (other != null) {
        val days = ChronoUnit.DAYS.between(date, other)
        line("fino a", Dates.label(other))
        line("differenza", "${days} giorni (${(days / 7)} settimane e ${days % 7} giorni)")
      } else {
        val fromToday = ChronoUnit.DAYS.between(today, date)
        if (fromToday != 0L) line(if (fromToday > 0) "mancano" else "passati", "${kotlin.math.abs(fromToday)} giorni")
      }
      if (add != null) line("piu' $add giorni", Dates.label(date.plusDays(add)))
      line("giorno dell'anno", "${date.dayOfYear} di ${date.lengthOfYear()}, settimana ${date.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear())}")
    }
  }
}

/** Le zone note per nome di citta' (italiano e inglese), oltre agli id ufficiali. */
object TimeZones {
  private val cities: Map<String, String> = mapOf(
    "roma" to "Europe/Rome", "milano" to "Europe/Rome", "italia" to "Europe/Rome", "londra" to "Europe/London", "london" to "Europe/London",
    "parigi" to "Europe/Paris", "paris" to "Europe/Paris", "berlino" to "Europe/Berlin", "berlin" to "Europe/Berlin", "madrid" to "Europe/Madrid",
    "lisbona" to "Europe/Lisbon", "atene" to "Europe/Athens", "mosca" to "Europe/Moscow", "istanbul" to "Europe/Istanbul", "dubai" to "Asia/Dubai",
    "nuova delhi" to "Asia/Kolkata", "delhi" to "Asia/Kolkata", "mumbai" to "Asia/Kolkata", "india" to "Asia/Kolkata", "bangkok" to "Asia/Bangkok",
    "singapore" to "Asia/Singapore", "hong kong" to "Asia/Hong_Kong", "pechino" to "Asia/Shanghai", "shanghai" to "Asia/Shanghai", "cina" to "Asia/Shanghai",
    "tokyo" to "Asia/Tokyo", "giappone" to "Asia/Tokyo", "seoul" to "Asia/Seoul", "sydney" to "Australia/Sydney", "melbourne" to "Australia/Melbourne",
    "auckland" to "Pacific/Auckland", "new york" to "America/New_York", "nuova york" to "America/New_York", "miami" to "America/New_York",
    "toronto" to "America/Toronto", "chicago" to "America/Chicago", "denver" to "America/Denver", "los angeles" to "America/Los_Angeles",
    "san francisco" to "America/Los_Angeles", "california" to "America/Los_Angeles", "vancouver" to "America/Vancouver", "citta del messico" to "America/Mexico_City",
    "san paolo" to "America/Sao_Paulo", "buenos aires" to "America/Argentina/Buenos_Aires", "santiago" to "America/Santiago", "lima" to "America/Lima",
    "il cairo" to "Africa/Cairo", "cairo" to "Africa/Cairo", "johannesburg" to "Africa/Johannesburg", "nairobi" to "Africa/Nairobi", "lagos" to "Africa/Lagos",
    "honolulu" to "Pacific/Honolulu", "anchorage" to "America/Anchorage", "reykjavik" to "Atlantic/Reykjavik", "tel aviv" to "Asia/Jerusalem", "teheran" to "Asia/Tehran",
    "utc" to "UTC", "gmt" to "UTC",
  )

  fun resolve(raw: String): ZoneId? {
    runCatching { return ZoneId.of(raw.trim()) }
    val key = Text.normalize(raw)
    cities[key]?.let { return ZoneId.of(it) }
    val match = ZoneId.getAvailableZoneIds().firstOrNull { id -> Text.normalize(id.substringAfterLast('/').replace('_', ' ')) == key }
    return match?.let { ZoneId.of(it) }
  }
}

/** Le unita' di misura, a famiglie: ogni unita' e' un fattore verso la base della famiglia (la temperatura fa storia a se'). */
object UnitConverter {
  sealed interface Result {
    data class Ok(val value: Double, val fromLabel: String, val toLabel: String) : Result
    data class UnknownUnit(val unit: String) : Result
    data class Incompatible(val from: String, val to: String) : Result
  }

  private class Unit(val family: String, val label: String, val factor: Double, vararg val aliases: String)

  private val units: List<Unit> = listOf(
    Unit("lunghezza", "m", 1.0, "m", "metri", "metro"), Unit("lunghezza", "km", 1000.0, "km", "chilometri", "kilometri", "chilometro"),
    Unit("lunghezza", "cm", 0.01, "cm", "centimetri"), Unit("lunghezza", "mm", 0.001, "mm", "millimetri"), Unit("lunghezza", "mi", 1609.344, "mi", "miglia", "miglio", "mile", "miles"),
    Unit("lunghezza", "ft", 0.3048, "ft", "piedi", "piede", "feet", "foot"), Unit("lunghezza", "in", 0.0254, "in", "pollici", "pollice", "inch", "inches"), Unit("lunghezza", "yd", 0.9144, "yd", "iarde", "yard"),
    Unit("lunghezza", "nmi", 1852.0, "nmi", "miglia nautiche"),
    Unit("massa", "kg", 1.0, "kg", "chili", "chilogrammi", "kilogrammi", "chilo"), Unit("massa", "g", 0.001, "g", "grammi", "grammo"), Unit("massa", "mg", 1e-6, "mg", "milligrammi"),
    Unit("massa", "t", 1000.0, "t", "tonnellate", "tonnellata"), Unit("massa", "lb", 0.45359237, "lb", "libbre", "libbra", "pounds", "pound"), Unit("massa", "oz", 0.028349523, "oz", "once", "oncia", "ounces"),
    Unit("velocita'", "km/h", 1.0, "km/h", "kmh", "chilometri orari"), Unit("velocita'", "m/s", 3.6, "m/s", "ms", "metri al secondo"), Unit("velocita'", "mph", 1.609344, "mph", "miglia orarie"),
    Unit("velocita'", "nodi", 1.852, "nodi", "nodo", "kn", "knots"),
    Unit("area", "m²", 1.0, "m2", "m²", "metri quadri", "metri quadrati", "mq"), Unit("area", "km²", 1e6, "km2", "km²", "chilometri quadrati"), Unit("area", "ha", 10_000.0, "ha", "ettari", "ettaro"),
    Unit("area", "ft²", 0.09290304, "ft2", "ft²", "piedi quadrati", "sqft"), Unit("area", "acri", 4046.8564224, "acri", "acro", "acres", "acre"),
    Unit("volume", "l", 1.0, "l", "litri", "litro"), Unit("volume", "ml", 0.001, "ml", "millilitri"), Unit("volume", "m³", 1000.0, "m3", "m³", "metri cubi"),
    Unit("volume", "gal", 3.785411784, "gal", "galloni", "gallone", "gallons"), Unit("volume", "cup", 0.2365882365, "cup", "tazze", "tazza", "cups"), Unit("volume", "fl oz", 0.0295735, "floz", "fl oz", "once liquide"),
    Unit("dati", "B", 1.0, "b", "byte", "bytes"), Unit("dati", "KB", 1024.0, "kb", "kilobyte"), Unit("dati", "MB", 1024.0 * 1024, "mb", "megabyte"), Unit("dati", "GB", 1024.0 * 1024 * 1024, "gb", "gigabyte"),
    Unit("dati", "TB", 1024.0 * 1024 * 1024 * 1024, "tb", "terabyte"), Unit("dati", "Mbit", 1024.0 * 1024 / 8, "mbit", "megabit"), Unit("dati", "Gbit", 1024.0 * 1024 * 1024 / 8, "gbit", "gigabit"),
    Unit("tempo", "s", 1.0, "s", "secondi", "secondo", "sec"), Unit("tempo", "min", 60.0, "min", "minuti", "minuto"), Unit("tempo", "h", 3600.0, "h", "ore", "ora"),
    Unit("tempo", "giorni", 86_400.0, "giorni", "giorno", "d", "days"), Unit("tempo", "settimane", 604_800.0, "settimane", "settimana"), Unit("tempo", "ms", 0.001, "ms", "millisecondi"),
    Unit("energia", "J", 1.0, "j", "joule"), Unit("energia", "kJ", 1000.0, "kj", "kilojoule"), Unit("energia", "cal", 4.184, "cal", "calorie", "caloria"), Unit("energia", "kcal", 4184.0, "kcal", "chilocalorie", "kilocalorie"),
    Unit("energia", "Wh", 3600.0, "wh", "wattora"), Unit("energia", "kWh", 3_600_000.0, "kwh", "chilowattora", "kilowattora"),
    Unit("temperatura", "°C", 1.0, "c", "°c", "celsius", "gradi", "gradi celsius"), Unit("temperatura", "°F", 1.0, "f", "°f", "fahrenheit", "gradi fahrenheit"), Unit("temperatura", "K", 1.0, "k", "kelvin"),
  )

  private fun find(raw: String): Unit? {
    val key = Text.normalize(raw).replace(" ", "")
    return units.firstOrNull { unit -> unit.aliases.any { Text.normalize(it).replace(" ", "") == key } || Text.normalize(unit.label).replace(" ", "") == key }
  }

  fun convert(value: Double, from: String, to: String): Result {
    val a = find(from) ?: return Result.UnknownUnit(from)
    val b = find(to) ?: return Result.UnknownUnit(to)
    if (a.family != b.family) return Result.Incompatible(a.label, b.label)
    if (a.family == "temperatura") {
      val celsius = when (a.label) { "°F" -> (value - 32) * 5 / 9; "K" -> value - 273.15; else -> value }
      val out = when (b.label) { "°F" -> celsius * 9 / 5 + 32; "K" -> celsius + 273.15; else -> celsius }
      return Result.Ok(out, a.label, b.label)
    }
    return Result.Ok(value * a.factor / b.factor, a.label, b.label)
  }

  fun knownSample(): String = units.groupBy { it.family }.entries.joinToString("; ") { (family, list) -> "$family: ${list.joinToString(", ") { it.label }}" }
}

fun calcTools(): List<AiTool<PampaiToolContext>> = listOf(CalcolaTool(), ConvertiUnitaTool(), FusoOrarioTool(), DataCalcolaTool())

/** Il lunedi' di una settimana ISO, per i conti sui giorni. */
internal fun LocalDate.startOfWeek(): LocalDate = with(DayOfWeek.MONDAY)
