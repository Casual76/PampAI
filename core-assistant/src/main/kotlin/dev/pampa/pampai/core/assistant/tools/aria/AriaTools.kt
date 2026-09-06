package dev.pampa.pampai.core.assistant.tools.aria

import dev.antigravity.fluidengine.ai.tools.AiTool
import dev.antigravity.fluidengine.ai.tools.AiToolGroup
import dev.antigravity.fluidengine.ai.tools.Args.int
import dev.antigravity.fluidengine.ai.tools.Args.str
import dev.antigravity.fluidengine.ai.tools.ConfirmationText
import dev.antigravity.fluidengine.ai.tools.Schema
import dev.antigravity.fluidengine.ai.tools.ToolOutput
import dev.antigravity.fluidengine.ai.tools.ToolText
import dev.pampa.pampai.core.assistant.tools.Dates
import dev.pampa.pampai.core.assistant.tools.PampaiGroup
import dev.pampa.pampai.core.assistant.tools.PampaiToolContext
import java.time.Instant
import kotlinx.serialization.json.JsonObject

/** Cosa sa fare Aria: le categorie, i gruppi, le app collegate. Per "cosa puoi fare?". */
class AiutoTool : AiTool<PampaiToolContext> {
  override val name = "aiuto"
  override val group: AiToolGroup = PampaiGroup.ARIA
  override val description = "Cosa sa fare Aria: le aree, le app collegate, esempi di domande. Da usare quando l'utente chiede cosa puoi fare o come funzioni."
  override val parameters = Schema.obj(emptyMap())

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput = ToolText.output(4_000) {
    line("assistente", "Aria (PampAI)")
    line(ctx.capabilitiesSummary())
    line("esempi", "\"che tempo fa domani?\" · \"quando passa il 23?\" · \"che voti ho preso?\" · \"metti una sveglia alle 7\" · \"ricordami alle 18 di chiamare la nonna\" · \"quanto fa il 15% di 340?\" · \"cosa c'e' scritto sullo schermo?\"")
    line("nota", "le app collegate rispondono solo se installate; le azioni che contano chiedono conferma")
  }
}

class RicordaTool : AiTool<PampaiToolContext> {
  override val name = "ricorda"
  override val group: AiToolGroup = PampaiGroup.ARIA
  override val description = "Salva un fatto sull'utente nella memoria a lungo termine (preferenze, nomi, posti, abitudini): entrera' in ogni conversazione futura. Solo se l'utente chiede di ricordare o dice qualcosa di sé che vale la pena tenere."
  override val parameters = Schema.obj(mapOf("testo" to Schema.str("il fatto, in una frase breve in terza persona (es. \"la sua fermata di casa e' Piazza Dalmazia\")")), required = listOf("testo"))
  override val isAction = true

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    val text = args.str("testo") ?: return ToolOutput.error("manca il testo")
    ctx.memory.add(text, ctx.conversationId, ctx.now())
    return ToolText.output { line("fatto", "ricordato: $text") }
  }
}

class DimenticaTool : AiTool<PampaiToolContext> {
  override val name = "dimentica"
  override val group: AiToolGroup = PampaiGroup.ARIA
  override val description = "Cancella dalla memoria a lungo termine i fatti che contengono queste parole (o tutto, con tutto=true). Chiede conferma."
  override val parameters = Schema.obj(mapOf("testo" to Schema.str("le parole del fatto da dimenticare"), "tutto" to Schema.bool("cancella tutta la memoria")))
  override val needsConfirmation = true
  override val isAction = true

  override suspend fun describe(args: JsonObject, ctx: PampaiToolContext): ConfirmationText? {
    val all = (args["tutto"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
    return ConfirmationText(if (all) "Dimenticare tutta la memoria?" else "Dimenticare \"${args.str("testo")}\"?", null)
  }

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    val all = (args["tutto"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
    val text = args.str("testo")
    if (!all && text == null) return ToolOutput.error("di' cosa dimenticare, o tutto=true")
    val confirmation = describe(args, ctx)!!
    ctx.confirm(name, confirmation.title, confirmation.detail)?.let { return it }
    val removed = if (all) ctx.memory.list().size.also { ctx.memory.clear() } else ctx.memory.removeMatching(text!!)
    return ToolText.output { line("fatto", if (removed == 0) "nessun fatto corrispondeva" else "dimenticati $removed fatti") }
  }
}

class MemoriaElencoTool : AiTool<PampaiToolContext> {
  override val name = "memoria_elenco"
  override val group: AiToolGroup = PampaiGroup.ARIA
  override val description = "Elenca tutto cio' che Aria ricorda dell'utente."
  override val parameters = Schema.obj(emptyMap())

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput = ToolText.output {
    val all = ctx.memory.list()
    if (all.isEmpty()) {
      line("memoria", "vuota")
    } else {
      line("fatti", all.size)
      all.forEach { line("${Dates.label(Instant.ofEpochMilli(it.createdAtMillis).atZone(ctx.zone).toLocalDate())}", it.text) }
    }
  }
}

class ConversazioniCercaTool : AiTool<PampaiToolContext> {
  override val name = "conversazioni_cerca"
  override val group: AiToolGroup = PampaiGroup.ARIA
  override val description = "Cerca nelle conversazioni passate con l'utente: torna i passaggi che contengono le parole, con l'id della conversazione (proponibile come chip [[conversazione:ID]])."
  override val parameters = Schema.obj(mapOf("testo" to Schema.str("le parole da cercare"), "limite" to Schema.int("quanti risultati", 1, 20)), required = listOf("testo"))

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    val query = args.str("testo") ?: return ToolOutput.error("manca il testo")
    val hits = ctx.conversations.search(query, args.int("limite") ?: 8)
    return ToolText.output(3_000) {
      if (hits.isEmpty()) {
        line("risultati", "nessuno per \"$query\"; prova con una parola sola o un sinonimo")
      } else {
        line("risultati", hits.size)
        hits.forEach { hit ->
          line("[${hit.conversationId}] ${hit.conversationTitle.take(40)} · ${Dates.label(Instant.ofEpochMilli(hit.atMillis).atZone(ctx.zone).toLocalDate())} · ${if (hit.role.name == "USER") "utente" else "Aria"}", "…${hit.snippet}…")
        }
      }
    }
  }
}

/** Tutti i tool del gruppo `aria` disponibili in questa fase. */
fun ariaTools(): List<AiTool<PampaiToolContext>> = listOf(AiutoTool(), RicordaTool(), DimenticaTool(), MemoriaElencoTool(), ConversazioniCercaTool()) + ariaExtraTools()
