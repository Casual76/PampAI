package dev.pampa.pampai.core.assistant.tools.device

import dev.antigravity.fluidengine.ai.tools.AiTool
import dev.antigravity.fluidengine.ai.tools.AiToolGroup
import dev.antigravity.fluidengine.ai.tools.Args.int
import dev.antigravity.fluidengine.ai.tools.Args.str
import dev.antigravity.fluidengine.ai.tools.ConfirmationText
import dev.antigravity.fluidengine.ai.tools.Schema
import dev.antigravity.fluidengine.ai.tools.ToolOutput
import dev.antigravity.fluidengine.ai.tools.ToolText
import dev.pampa.pampai.core.assistant.reminders.ReminderRepeat
import dev.pampa.pampai.core.assistant.tools.Dates
import dev.pampa.pampai.core.assistant.tools.PampaiGroup
import dev.pampa.pampai.core.assistant.tools.PampaiToolContext
import dev.pampa.pampai.core.assistant.tools.Text
import java.time.Instant
import java.time.ZonedDateTime
import kotlinx.serialization.json.JsonObject

class PromemoriaCreaTool : AiTool<PampaiToolContext> {
  override val name = "promemoria_crea"
  override val group: AiToolGroup = PampaiGroup.PROMEMORIA
  override val description = "Crea un promemoria di PampAI: una notifica a un giorno e un'ora, anche ricorrente. Per \"ricordami alle 18 di chiamare la nonna\", \"ogni lunedi' alle 8 ricordami la palestra\"."
  override val parameters = Schema.obj(
    mapOf(
      "testo" to Schema.str("cosa ricordare, come lo direbbe l'utente a se stesso (\"chiamare la nonna\")"),
      "quando" to Schema.str("giorno e ora: \"domani alle 18\", \"alle 7\", \"lunedi' 8:00\", \"2026-09-12 08:30\""),
      "ripeti" to Schema.str("come si ripete", enum = listOf("no", "ogni giorno", "feriali", "ogni settimana")),
    ),
    required = listOf("testo", "quando"),
  )
  override val isAction = true

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    val text = args.str("testo") ?: return ToolOutput.error("manca il testo")
    val at = Dates.parseDateTime(args.str("quando"), ctx.nowDateTime) ?: return ToolOutput.error("non capisco quando: usa \"domani alle 18\", \"alle 7\", \"lunedi' 8:00\"")
    val millis = at.atZone(ctx.zone).toInstant().toEpochMilli()
    if (millis < ctx.now() - 60_000) return ToolOutput.error("quel momento e' gia' passato (adesso: ${Dates.label(ctx.nowDateTime)})")
    val repeat = ReminderRepeat.parse(args.str("ripeti"))
    val entity = ctx.reminders.add(text, millis, repeat)
    return ToolText.output {
      line("fatto", "promemoria creato")
      line("id", entity.id)
      line("testo", text)
      line("quando", Dates.label(at))
      line("ripeti", repeat.label)
      if (!ctx.reminders.exactAllowed) line("nota", "senza il permesso \"sveglie precise\" puo' suonare con qualche minuto di ritardo [[impostazioni:permessi]]")
    }
  }
}

class PromemoriaElencoTool : AiTool<PampaiToolContext> {
  override val name = "promemoria_elenco"
  override val group: AiToolGroup = PampaiGroup.PROMEMORIA
  override val description = "I promemoria attivi di PampAI, in ordine di tempo, con il loro id."
  override val parameters = Schema.obj(emptyMap())

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    val list = ctx.reminders.list()
    if (list.isEmpty()) return ToolText.output { line("promemoria", "nessuno attivo") }
    return ToolText.output {
      line("promemoria attivi", list.size)
      list.take(30).forEach { r ->
        val at = ZonedDateTime.ofInstant(Instant.ofEpochMilli(r.atMillis), ctx.zone)
        line("#${r.id} ${Dates.label(at)} · ${r.text}" + (ReminderRepeat.valueOf(r.repeat).takeIf { it != ReminderRepeat.NONE }?.let { " · ${it.label}" } ?: ""))
      }
    }
  }
}

class PromemoriaEliminaTool : AiTool<PampaiToolContext> {
  override val name = "promemoria_elimina"
  override val group: AiToolGroup = PampaiGroup.PROMEMORIA
  override val description = "Cancella un promemoria di PampAI, per id o per le parole del testo. Chiede conferma."
  override val parameters = Schema.obj(mapOf("id" to Schema.int("l'id del promemoria (da promemoria_elenco)"), "testo" to Schema.str("parole del promemoria, se non hai l'id")))
  override val isAction = true
  override val needsConfirmation = true

  private suspend fun find(args: JsonObject, ctx: PampaiToolContext) = args.int("id")?.let { ctx.reminders.get(it.toLong()) }
    ?: args.str("testo")?.let { q -> ctx.reminders.list().filter { Text.matches(q, it.text) }.maxByOrNull { Text.score(q, it.text) } }

  override suspend fun describe(args: JsonObject, ctx: PampaiToolContext): ConfirmationText? {
    val target = find(args, ctx) ?: return null
    return ConfirmationText("Cancellare il promemoria \"${target.text}\"?", Dates.label(ZonedDateTime.ofInstant(Instant.ofEpochMilli(target.atMillis), ctx.zone)))
  }

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    val target = find(args, ctx) ?: return ToolOutput.error("promemoria non trovato: chiedi promemoria_elenco per gli id")
    val confirmation = describe(args, ctx)!!
    ctx.confirm(name, confirmation.title, confirmation.detail)?.let { return it }
    ctx.reminders.remove(target.id)
    return ToolText.output { line("fatto", "promemoria \"${target.text}\" cancellato") }
  }
}

fun reminderTools(): List<AiTool<PampaiToolContext>> = listOf(PromemoriaCreaTool(), PromemoriaElencoTool(), PromemoriaEliminaTool())
