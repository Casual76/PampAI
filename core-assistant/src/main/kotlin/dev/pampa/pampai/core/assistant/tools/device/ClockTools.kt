package dev.pampa.pampai.core.assistant.tools.device

import android.app.AlarmManager
import android.content.Intent
import android.provider.AlarmClock
import dev.antigravity.fluidengine.ai.tools.AiTool
import dev.antigravity.fluidengine.ai.tools.AiToolGroup
import dev.antigravity.fluidengine.ai.tools.Args.int
import dev.antigravity.fluidengine.ai.tools.Args.list
import dev.antigravity.fluidengine.ai.tools.Args.str
import dev.antigravity.fluidengine.ai.tools.ConfirmationText
import dev.antigravity.fluidengine.ai.tools.Schema
import dev.antigravity.fluidengine.ai.tools.ToolOutput
import dev.antigravity.fluidengine.ai.tools.ToolText
import dev.pampa.pampai.core.assistant.tools.Dates
import dev.pampa.pampai.core.assistant.tools.PampaiGroup
import dev.pampa.pampai.core.assistant.tools.PampaiToolContext
import java.time.Instant
import java.time.ZonedDateTime
import java.util.Calendar
import kotlinx.serialization.json.JsonObject

private val WEEKDAY_NAMES = mapOf(
  "lunedi" to Calendar.MONDAY, "martedi" to Calendar.TUESDAY, "mercoledi" to Calendar.WEDNESDAY, "giovedi" to Calendar.THURSDAY,
  "venerdi" to Calendar.FRIDAY, "sabato" to Calendar.SATURDAY, "domenica" to Calendar.SUNDAY,
)

private fun days(raw: List<String>): ArrayList<Int>? {
  val expanded = raw.flatMap { item ->
    when (dev.pampa.pampai.core.assistant.tools.Text.normalize(item).trim('\'')) {
      "feriali", "lun-ven", "settimana" -> listOf("lunedi", "martedi", "mercoledi", "giovedi", "venerdi")
      "weekend", "fine settimana" -> listOf("sabato", "domenica")
      "tutti", "ogni giorno", "sempre" -> WEEKDAY_NAMES.keys.toList()
      else -> listOf(dev.pampa.pampai.core.assistant.tools.Text.normalize(item).trim('\''))
    }
  }
  val ids = expanded.mapNotNull { WEEKDAY_NAMES[it] }.distinct()
  return if (ids.isEmpty()) null else ArrayList(ids)
}

/** Una sveglia nell'app Orologio, senza aprirla. */
class SvegliaCreaTool : AiTool<PampaiToolContext> {
  override val name = "sveglia_crea"
  override val group: AiToolGroup = PampaiGroup.OROLOGIO
  override val description = "Imposta una sveglia nell'app Orologio del telefono: ora, etichetta, e i giorni se deve ripetersi. Per \"svegliami alle 7\", \"sveglia alle 6 e mezza nei giorni feriali\"."
  override val parameters = Schema.obj(
    mapOf(
      "ora" to Schema.str("l'ora, es. \"7\", \"6:30\", \"6 e mezza\", \"3 del pomeriggio\""),
      "etichetta" to Schema.str("il nome della sveglia (facoltativo)"),
      "giorni" to Schema.strArray("i giorni in cui ripeterla: lunedi'…domenica, oppure \"feriali\", \"weekend\", \"tutti\" (vuoto = una volta sola)"),
    ),
    required = listOf("ora"),
  )
  override val isAction = true
  override val needsConfirmation = true

  override suspend fun describe(args: JsonObject, ctx: PampaiToolContext): ConfirmationText? {
    val time = Dates.parseTime(args.str("ora")) ?: return null
    val repeat = days(args.list("giorni"))?.let { " (si ripete)" } ?: ""
    return ConfirmationText("Sveglia alle ${"%02d:%02d".format(time.hour, time.minute)}$repeat?", args.str("etichetta"))
  }

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    val time = Dates.parseTime(args.str("ora")) ?: return ToolOutput.error("ora non capita: usa \"7\", \"6:30\", \"6 e mezza\"")
    val confirmation = describe(args, ctx)!!
    ctx.confirm(name, confirmation.title, confirmation.detail)?.let { return it }
    val intent = Intent(AlarmClock.ACTION_SET_ALARM)
      .putExtra(AlarmClock.EXTRA_HOUR, time.hour)
      .putExtra(AlarmClock.EXTRA_MINUTES, time.minute)
      .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
      .putExtra(AlarmClock.EXTRA_VIBRATE, true)
    args.str("etichetta")?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
    days(args.list("giorni"))?.let { intent.putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, it) }
    if (!Device.launch(ctx, intent)) return ToolOutput.error("nessuna app Orologio risponde alle sveglie su questo telefono")
    return ToolText.output {
      line("fatto", "sveglia impostata alle ${"%02d:%02d".format(time.hour, time.minute)}")
      args.str("etichetta")?.let { line("etichetta", it) }
      days(args.list("giorni"))?.let { line("giorni", args.list("giorni").joinToString(", ")) }
    }
  }
}

class SvegliaProssimaTool : AiTool<PampaiToolContext> {
  override val name = "sveglia_prossima"
  override val group: AiToolGroup = PampaiGroup.OROLOGIO
  override val description = "La prossima sveglia impostata sul telefono (quando suona). Per \"a che ora ho la sveglia?\", \"ho una sveglia domani?\"."
  override val parameters = Schema.obj(emptyMap())

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    val next = ctx.app.getSystemService(AlarmManager::class.java)?.nextAlarmClock
      ?: return ToolText.output { line("prossima sveglia", "nessuna impostata") }
    val at = ZonedDateTime.ofInstant(Instant.ofEpochMilli(next.triggerTime), ctx.zone)
    val minutes = (next.triggerTime - ctx.now()) / 60_000
    return ToolText.output {
      line("prossima sveglia", Dates.label(at))
      line("fra", if (minutes < 60) "$minutes minuti" else "${minutes / 60} ore e ${minutes % 60} minuti")
      line("nota", "per l'elenco completo l'utente apre l'app Orologio")
    }
  }
}

class SvegliaDisattivaTool : AiTool<PampaiToolContext> {
  override val name = "sveglia_disattiva"
  override val group: AiToolGroup = PampaiGroup.OROLOGIO
  override val description = "Spegne una sveglia dell'app Orologio: quella a un'ora, quella con un'etichetta, la prossima, o tutte. Per \"togli la sveglia delle 7\", \"spegni tutte le sveglie\"."
  override val parameters = Schema.obj(
    mapOf(
      "ora" to Schema.str("l'ora della sveglia da spegnere (facoltativo)"),
      "etichetta" to Schema.str("l'etichetta della sveglia (facoltativo)"),
      "quale" to Schema.str("se non dai ora ne' etichetta", enum = listOf("prossima", "tutte")),
    ),
  )
  override val isAction = true
  override val needsConfirmation = true

  override suspend fun describe(args: JsonObject, ctx: PampaiToolContext): ConfirmationText? {
    val time = Dates.parseTime(args.str("ora"))
    val label = args.str("etichetta")
    return ConfirmationText(
      when {
        time != null -> "Spegnere la sveglia delle ${"%02d:%02d".format(time.hour, time.minute)}?"
        label != null -> "Spegnere la sveglia \"$label\"?"
        args.str("quale") == "tutte" -> "Spegnere tutte le sveglie?"
        else -> "Spegnere la prossima sveglia?"
      },
      null,
    )
  }

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    val confirmation = describe(args, ctx)!!
    ctx.confirm(name, confirmation.title, confirmation.detail)?.let { return it }
    val time = Dates.parseTime(args.str("ora"))
    val label = args.str("etichetta")
    val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
    when {
      time != null -> intent.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME).putExtra(AlarmClock.EXTRA_HOUR, time.hour).putExtra(AlarmClock.EXTRA_MINUTES, time.minute)
      label != null -> intent.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_LABEL).putExtra(AlarmClock.EXTRA_MESSAGE, label)
      args.str("quale") == "tutte" -> intent.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_ALL)
      else -> intent.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT)
    }
    if (!Device.launch(ctx, intent)) return ToolOutput.error("l'app Orologio di questo telefono non permette di spegnere le sveglie da fuori: apri l'app")
    return ToolText.output { line("fatto", "richiesta inviata all'app Orologio (se la sveglia esisteva, e' spenta)") }
  }
}

class SvegliaPosticipaTool : AiTool<PampaiToolContext> {
  override val name = "sveglia_posticipa"
  override val group: AiToolGroup = PampaiGroup.OROLOGIO
  override val description = "Posticipa la sveglia che sta suonando di N minuti (default 10). Solo mentre suona."
  override val parameters = Schema.obj(mapOf("minuti" to Schema.int("di quanti minuti (default 10)", minimum = 1, maximum = 120)))
  override val isAction = true

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    val minutes = args.int("minuti") ?: 10
    val intent = Intent(AlarmClock.ACTION_SNOOZE_ALARM).putExtra(AlarmClock.EXTRA_ALARM_SNOOZE_DURATION, minutes).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
    if (!Device.launch(ctx, intent)) return ToolOutput.error("l'app Orologio non accetta il posticipo da fuori")
    return ToolText.output { line("fatto", "sveglia posticipata di $minutes minuti (se stava suonando)") }
  }
}

class TimerCreaTool : AiTool<PampaiToolContext> {
  override val name = "timer_crea"
  override val group: AiToolGroup = PampaiGroup.OROLOGIO
  override val description = "Fa partire un timer (conto alla rovescia) nell'app Orologio. Per \"timer di 10 minuti\", \"conto alla rovescia di un'ora e mezza per la pasta\"."
  override val parameters = Schema.obj(
    mapOf(
      "durata" to Schema.str("la durata, es. \"10 minuti\", \"1 ora e 30\", \"45 secondi\", \"1:30:00\""),
      "etichetta" to Schema.str("a cosa serve (facoltativo)"),
    ),
    required = listOf("durata"),
  )
  override val isAction = true
  override val needsConfirmation = true

  override suspend fun describe(args: JsonObject, ctx: PampaiToolContext): ConfirmationText? {
    val seconds = Durations.parseSeconds(args.str("durata")) ?: return null
    return ConfirmationText("Timer di ${Durations.label(seconds)}?", args.str("etichetta"))
  }

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    val seconds = Durations.parseSeconds(args.str("durata")) ?: return ToolOutput.error("durata non capita: usa \"10 minuti\", \"1 ora e 30\", \"45 secondi\"")
    if (seconds <= 0 || seconds > 24 * 3600) return ToolOutput.error("la durata deve stare fra 1 secondo e 24 ore")
    val confirmation = describe(args, ctx)!!
    ctx.confirm(name, confirmation.title, confirmation.detail)?.let { return it }
    val intent = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, seconds).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
    args.str("etichetta")?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
    if (!Device.launch(ctx, intent)) return ToolOutput.error("nessuna app Orologio risponde ai timer su questo telefono")
    return ToolText.output {
      line("fatto", "timer di ${Durations.label(seconds)} avviato")
      args.str("etichetta")?.let { line("etichetta", it) }
      line("suona alle", Dates.label(ZonedDateTime.ofInstant(Instant.ofEpochMilli(ctx.now() + seconds * 1000L), ctx.zone)))
    }
  }
}

class TimerEliminaTool : AiTool<PampaiToolContext> {
  override val name = "timer_elimina"
  override val group: AiToolGroup = PampaiGroup.OROLOGIO
  override val description = "Ferma e cancella un timer in corso nell'app Orologio (quello con un'etichetta, o l'unico attivo). Per \"ferma il timer\", \"togli il timer della pasta\"."
  override val parameters = Schema.obj(mapOf("etichetta" to Schema.str("l'etichetta del timer (facoltativo: senza, quello attivo)")))
  override val isAction = true
  override val needsConfirmation = true

  override suspend fun describe(args: JsonObject, ctx: PampaiToolContext): ConfirmationText? =
    ConfirmationText(args.str("etichetta")?.let { "Fermare il timer \"$it\"?" } ?: "Fermare il timer?", null)

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    val confirmation = describe(args, ctx)!!
    ctx.confirm(name, confirmation.title, confirmation.detail)?.let { return it }
    val intent = Intent(AlarmClock.ACTION_DISMISS_TIMER).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
    args.str("etichetta")?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
    if (!Device.launch(ctx, intent)) return ToolOutput.error("l'app Orologio di questo telefono non permette di fermare i timer da fuori: apri l'app")
    return ToolText.output { line("fatto", "richiesta inviata all'app Orologio (il timer, se c'era, e' fermo)") }
  }
}

/** Le durate come le dice la gente: "10 minuti", "1 ora e 30", "45 s", "1:30:00". */
internal object Durations {
  fun parseSeconds(raw: String?): Int? {
    // "un'ora", "mezz'ora": l'apostrofo diventa uno spazio, cosi' numero e unita' si separano.
    val text = dev.pampa.pampai.core.assistant.tools.Text.normalize(raw ?: return null).replace("'", " ").replace(Regex(" +"), " ").trim()
    if (text.isEmpty()) return null
    Regex("^(\\d{1,2}):(\\d{2})(?::(\\d{2}))?$").find(text)?.let { m ->
      val a = m.groupValues[1].toInt()
      val b = m.groupValues[2].toInt()
      val c = m.groupValues[3].toIntOrNull()
      return if (c != null) a * 3600 + b * 60 + c else a * 60 + b
    }
    var total = 0
    var found = false
    val words = mapOf("un" to 1, "una" to 1, "mezz" to 0, "mezza" to 0)
    Regex("(\\d+(?:[.,]\\d+)?|un|una|mezz|mezza)\\s*(ore|ora|h|minuti|minuto|min|m|secondi|secondo|sec|s)\\b").findAll(text).forEach { m ->
      val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: words[m.groupValues[1]]?.toDouble() ?: 0.0
      val value = if (m.groupValues[1].startsWith("mezz")) 0.5 else n
      total += when (m.groupValues[2]) {
        "ore", "ora", "h" -> (value * 3600).toInt()
        "minuti", "minuto", "min", "m" -> (value * 60).toInt()
        else -> value.toInt()
      }
      found = true
    }
    // "1 ora e 30" senza unita' dopo il 30: sono minuti.
    Regex("(ore|ora)\\s+e\\s+(\\d{1,2})$").find(text)?.let { total += it.groupValues[2].toInt() * 60 }
    if (Regex("(ore|ora)\\s+e\\s+mezz").containsMatchIn(text)) total += 1800
    if (Regex("(minuti|minuto)\\s+e\\s+mezz").containsMatchIn(text)) total += 30
    if (!found) text.toIntOrNull()?.let { return it * 60 }
    return if (found) total else null
  }

  fun label(seconds: Int): String {
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return listOfNotNull(
      h.takeIf { it > 0 }?.let { if (it == 1) "1 ora" else "$it ore" },
      m.takeIf { it > 0 }?.let { if (it == 1) "1 minuto" else "$it minuti" },
      s.takeIf { it > 0 }?.let { if (it == 1) "1 secondo" else "$it secondi" },
    ).joinToString(" e ").ifEmpty { "0 secondi" }
  }
}

fun clockTools(): List<AiTool<PampaiToolContext>> = listOf(SvegliaCreaTool(), SvegliaProssimaTool(), SvegliaDisattivaTool(), SvegliaPosticipaTool(), TimerCreaTool(), TimerEliminaTool())
