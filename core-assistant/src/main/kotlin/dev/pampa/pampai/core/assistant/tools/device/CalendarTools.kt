package dev.pampa.pampai.core.assistant.tools.device

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.provider.CalendarContract
import dev.antigravity.fluidengine.ai.tools.AiTool
import dev.antigravity.fluidengine.ai.tools.AiToolGroup
import dev.antigravity.fluidengine.ai.tools.Args.bool
import dev.antigravity.fluidengine.ai.tools.Args.int
import dev.antigravity.fluidengine.ai.tools.Args.str
import dev.antigravity.fluidengine.ai.tools.ConfirmationText
import dev.antigravity.fluidengine.ai.tools.Schema
import dev.antigravity.fluidengine.ai.tools.ToolOutput
import dev.antigravity.fluidengine.ai.tools.ToolText
import dev.pampa.pampai.core.assistant.tools.Dates
import dev.pampa.pampai.core.assistant.tools.PampaiGroup
import dev.pampa.pampai.core.assistant.tools.PampaiToolContext
import dev.pampa.pampai.core.assistant.tools.Text
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** Un'occorrenza del calendario, in parole. */
internal data class CalendarEvent(val eventId: Long, val title: String, val begin: Long, val end: Long, val allDay: Boolean, val location: String?, val calendar: String?)

internal object CalendarAccess {
  suspend fun instances(ctx: PampaiToolContext, from: Long, to: Long, query: String?): List<CalendarEvent> = withContext(Dispatchers.IO) {
    val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().let { b ->
      ContentUris.appendId(b, from)
      ContentUris.appendId(b, to)
      b.build()
    }
    val projection = arrayOf(
      CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
      CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
    )
    val out = mutableListOf<CalendarEvent>()
    ctx.app.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
      while (c.moveToNext()) {
        val title = c.getString(1)?.takeIf { it.isNotBlank() } ?: "(senza titolo)"
        if (query != null && !Text.matches(query, title)) continue
        out += CalendarEvent(c.getLong(0), title, c.getLong(2), c.getLong(3), c.getInt(4) == 1, c.getString(5)?.takeIf { it.isNotBlank() }, c.getString(6))
      }
    }
    out
  }

  /** Il calendario in cui scrivere: quello primario, altrimenti il primo in cui si puo'. */
  suspend fun writableCalendarId(ctx: PampaiToolContext): Long? = withContext(Dispatchers.IO) {
    val projection = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.VISIBLE)
    var best: Long? = null
    ctx.app.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)?.use { c ->
      while (c.moveToNext()) {
        val id = c.getLong(0)
        val primary = c.getInt(1) == 1
        val level = c.getInt(2)
        val visible = c.getInt(3) == 1
        if (level < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue
        if (primary && visible) return@withContext id
        if (best == null && visible) best = id
      }
    }
    best
  }

  fun label(ctx: PampaiToolContext, e: CalendarEvent): String {
    val begin = ZonedDateTime.ofInstant(Instant.ofEpochMilli(e.begin), ctx.zone)
    val end = ZonedDateTime.ofInstant(Instant.ofEpochMilli(e.end), ctx.zone)
    val time = if (e.allDay) "${Dates.label(begin.toLocalDate())} tutto il giorno" else "${Dates.label(begin)}–${"%02d:%02d".format(end.hour, end.minute)}"
    return "#${e.eventId} $time · ${e.title}" + (e.location?.let { " · $it" } ?: "") + (e.calendar?.let { " · $it" } ?: "")
  }
}

class EventiCalendarioTool : AiTool<PampaiToolContext> {
  override val name = "eventi_calendario"
  override val group: AiToolGroup = PampaiGroup.CALENDARIO
  override val description = "Gli eventi del calendario del telefono in un periodo (default: da oggi a 7 giorni), con id, orario, luogo. Per \"cosa ho domani?\", \"quando ho il dentista?\", \"sono libero venerdi' pomeriggio?\"."
  override val parameters = Schema.obj(
    mapOf(
      "da" to Schema.str("dal giorno (\"oggi\", \"domani\", \"lunedi'\", \"2026-09-12\")"),
      "a" to Schema.str("al giorno incluso"),
      "cerca" to Schema.str("parole nel titolo (facoltativo)"),
    ),
  )

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    Device.permission(ctx, "leggere il calendario", Manifest.permission.READ_CALENDAR)?.let { return it }
    val range = Dates.range(args.str("da"), args.str("a"), ctx.today, defaultDays = 7)
    val from = range.start.atStartOfDay(ctx.zone).toInstant().toEpochMilli()
    val to = range.endInclusive.plusDays(1).atStartOfDay(ctx.zone).toInstant().toEpochMilli()
    val events = CalendarAccess.instances(ctx, from, to, args.str("cerca"))
    return ToolText.output {
      line("periodo", "${Dates.label(range.start)} → ${Dates.label(range.endInclusive)}")
      if (events.isEmpty()) line("eventi", "nessuno") else {
        line("eventi", events.size)
        events.take(40).forEach { line(CalendarAccess.label(ctx, it)) }
        if (events.size > 40) line("altri", events.size - 40)
      }
    }
  }
}

class EventoCreaTool : AiTool<PampaiToolContext> {
  override val name = "evento_crea"
  override val group: AiToolGroup = PampaiGroup.CALENDARIO
  override val description = "Crea un evento nel calendario del telefono: titolo, inizio, durata o fine, luogo, promemoria. Chiede conferma."
  override val parameters = Schema.obj(
    mapOf(
      "titolo" to Schema.str("il titolo"),
      "inizio" to Schema.str("giorno e ora: \"domani alle 15\", \"venerdi' 9:30\", \"2026-09-12 08:30\"; solo il giorno = tutto il giorno"),
      "durata_minuti" to Schema.int("durata in minuti (default 60)", minimum = 5),
      "fine" to Schema.str("in alternativa alla durata: giorno e ora di fine"),
      "luogo" to Schema.str("il luogo (facoltativo)"),
      "note" to Schema.str("descrizione (facoltativo)"),
      "promemoria_minuti" to Schema.int("un avviso N minuti prima (facoltativo)"),
    ),
    required = listOf("titolo", "inizio"),
  )
  override val isAction = true
  override val needsConfirmation = true

  private fun plan(args: JsonObject, ctx: PampaiToolContext): Triple<Long, Long, Boolean>? {
    val raw = args.str("inizio") ?: return null
    val allDay = Dates.parseTime(Regex("\\d{1,2}[:.]\\d{2}|\\balle\\b|\\bore\\b|mezzogiorno|mezzanotte").find(Text.normalize(raw))?.value) == null && Dates.parse(raw, ctx.today) != null && !Regex("\\d{1,2}[:.]\\d{2}|alle|ore").containsMatchIn(Text.normalize(raw))
    if (allDay) {
      val day = Dates.parse(raw, ctx.today) ?: return null
      val start = day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
      return Triple(start, start + 86_400_000L, true)
    }
    val start = Dates.parseDateTime(raw, ctx.nowDateTime, rollForward = false) ?: return null
    val startMillis = start.atZone(ctx.zone).toInstant().toEpochMilli()
    val endMillis = args.str("fine")?.let { Dates.parseDateTime(it, start, rollForward = false)?.atZone(ctx.zone)?.toInstant()?.toEpochMilli() }
      ?: (startMillis + (args.int("durata_minuti") ?: 60) * 60_000L)
    return Triple(startMillis, endMillis, false)
  }

  override suspend fun describe(args: JsonObject, ctx: PampaiToolContext): ConfirmationText? {
    val (start, _, allDay) = plan(args, ctx) ?: return null
    val when_ = if (allDay) Dates.label(Instant.ofEpochMilli(start).atZone(java.time.ZoneOffset.UTC).toLocalDate()) + " (tutto il giorno)" else Dates.label(ZonedDateTime.ofInstant(Instant.ofEpochMilli(start), ctx.zone))
    return ConfirmationText("Creare \"${args.str("titolo")}\" $when_?", args.str("luogo"))
  }

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    Device.permission(ctx, "scrivere nel calendario", Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_CALENDAR)?.let { return it }
    val title = args.str("titolo") ?: return ToolOutput.error("manca il titolo")
    val (start, end, allDay) = plan(args, ctx) ?: return ToolOutput.error("non capisco l'inizio: usa \"domani alle 15\", \"venerdi' 9:30\"")
    if (end <= start) return ToolOutput.error("la fine deve venire dopo l'inizio")
    val confirmation = describe(args, ctx)!!
    ctx.confirm(name, confirmation.title, confirmation.detail)?.let { return it }
    val calendarId = CalendarAccess.writableCalendarId(ctx) ?: return ToolOutput.error("nessun calendario in cui scrivere sul telefono")
    val values = ContentValues().apply {
      put(CalendarContract.Events.CALENDAR_ID, calendarId)
      put(CalendarContract.Events.TITLE, title)
      put(CalendarContract.Events.DTSTART, start)
      put(CalendarContract.Events.DTEND, end)
      put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
      put(CalendarContract.Events.EVENT_TIMEZONE, if (allDay) "UTC" else TimeZone.getDefault().id)
      args.str("luogo")?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
      args.str("note")?.let { put(CalendarContract.Events.DESCRIPTION, it) }
    }
    val uri = withContext(Dispatchers.IO) { runCatching { ctx.app.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) }.getOrNull() }
      ?: return ToolOutput.error("il calendario ha rifiutato l'evento")
    val id = ContentUris.parseId(uri)
    args.int("promemoria_minuti")?.let { minutes ->
      val reminder = ContentValues().apply {
        put(CalendarContract.Reminders.EVENT_ID, id)
        put(CalendarContract.Reminders.MINUTES, minutes)
        put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
      }
      withContext(Dispatchers.IO) { runCatching { ctx.app.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, reminder) } }
    }
    return ToolText.output {
      line("fatto", "evento creato")
      line("id", id)
      line("titolo", title)
      line("inizio", if (allDay) "tutto il giorno" else Dates.label(ZonedDateTime.ofInstant(Instant.ofEpochMilli(start), ctx.zone)))
      if (!allDay) line("fine", Dates.label(ZonedDateTime.ofInstant(Instant.ofEpochMilli(end), ctx.zone)))
    }
  }
}

class EventoModificaTool : AiTool<PampaiToolContext> {
  override val name = "evento_modifica"
  override val group: AiToolGroup = PampaiGroup.CALENDARIO
  override val description = "Modifica un evento del calendario (per id da eventi_calendario): nuovo titolo, inizio, durata, luogo. Chiede conferma."
  override val parameters = Schema.obj(
    mapOf(
      "id" to Schema.int("l'id dell'evento"),
      "titolo" to Schema.str("nuovo titolo (facoltativo)"),
      "inizio" to Schema.str("nuovo inizio, giorno e ora (facoltativo)"),
      "durata_minuti" to Schema.int("nuova durata in minuti (facoltativo)"),
      "luogo" to Schema.str("nuovo luogo (facoltativo)"),
    ),
    required = listOf("id"),
  )
  override val isAction = true
  override val needsConfirmation = true

  override suspend fun describe(args: JsonObject, ctx: PampaiToolContext): ConfirmationText? {
    val changes = listOfNotNull(args.str("titolo")?.let { "titolo → $it" }, args.str("inizio")?.let { "inizio → $it" }, args.int("durata_minuti")?.let { "durata → $it min" }, args.str("luogo")?.let { "luogo → $it" })
    return ConfirmationText("Modificare l'evento #${args.int("id")}?", changes.joinToString(", ").ifEmpty { null })
  }

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    Device.permission(ctx, "scrivere nel calendario", Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_CALENDAR)?.let { return it }
    val id = args.int("id")?.toLong() ?: return ToolOutput.error("manca l'id")
    val current = withContext(Dispatchers.IO) {
      ctx.app.contentResolver.query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), arrayOf(CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND, CalendarContract.Events.TITLE), null, null, null)?.use { c ->
        if (c.moveToFirst()) Triple(c.getLong(0), c.getLong(1), c.getString(2)) else null
      }
    } ?: return ToolOutput.error("evento #$id non trovato")
    val confirmation = describe(args, ctx)!!
    ctx.confirm(name, confirmation.title, confirmation.detail)?.let { return it }
    val values = ContentValues()
    args.str("titolo")?.let { values.put(CalendarContract.Events.TITLE, it) }
    args.str("luogo")?.let { values.put(CalendarContract.Events.EVENT_LOCATION, it) }
    val duration = args.int("durata_minuti")?.let { it * 60_000L } ?: (current.second - current.first)
    val newStart = args.str("inizio")?.let { Dates.parseDateTime(it, ctx.nowDateTime, rollForward = false)?.atZone(ctx.zone)?.toInstant()?.toEpochMilli() }
    if (newStart != null || args.int("durata_minuti") != null) {
      val start = newStart ?: current.first
      values.put(CalendarContract.Events.DTSTART, start)
      values.put(CalendarContract.Events.DTEND, start + duration)
    }
    if (values.size() == 0) return ToolOutput.error("niente da modificare: dai titolo, inizio, durata o luogo")
    val rows = withContext(Dispatchers.IO) { runCatching { ctx.app.contentResolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), values, null, null) }.getOrDefault(0) }
    if (rows == 0) return ToolOutput.error("il calendario non ha accettato la modifica")
    return ToolText.output { line("fatto", "evento \"${args.str("titolo") ?: current.third}\" modificato") }
  }
}

class EventoEliminaTool : AiTool<PampaiToolContext> {
  override val name = "evento_elimina"
  override val group: AiToolGroup = PampaiGroup.CALENDARIO
  override val description = "Elimina un evento del calendario per id (da eventi_calendario). Chiede sempre conferma."
  override val parameters = Schema.obj(mapOf("id" to Schema.int("l'id dell'evento")), required = listOf("id"))
  override val isAction = true
  override val needsConfirmation = true

  override suspend fun describe(args: JsonObject, ctx: PampaiToolContext): ConfirmationText? {
    val id = args.int("id")?.toLong() ?: return null
    val title = withContext(Dispatchers.IO) {
      runCatching {
        ctx.app.contentResolver.query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), arrayOf(CalendarContract.Events.TITLE), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
      }.getOrNull()
    }
    return ConfirmationText("Eliminare l'evento \"${title ?: "#$id"}\"?", "Sparisce dal calendario del telefono.")
  }

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    Device.permission(ctx, "scrivere nel calendario", Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_CALENDAR)?.let { return it }
    val id = args.int("id")?.toLong() ?: return ToolOutput.error("manca l'id")
    val confirmation = describe(args, ctx) ?: return ToolOutput.error("evento non trovato")
    ctx.confirm(name, confirmation.title, confirmation.detail)?.let { return it }
    val rows = withContext(Dispatchers.IO) { runCatching { ctx.app.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null) }.getOrDefault(0) }
    return if (rows > 0) ToolText.output { line("fatto", "evento eliminato") } else ToolOutput.error("evento #$id non trovato")
  }
}

fun calendarTools(): List<AiTool<PampaiToolContext>> = listOf(EventiCalendarioTool(), EventoCreaTool(), EventoModificaTool(), EventoEliminaTool())

@Suppress("unused")
private val keepImports = listOf(LocalTime.NOON)

@Suppress("unused")
private fun JsonObject.unusedBool() = bool("x")
