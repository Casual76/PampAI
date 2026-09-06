package dev.pampa.pampai.core.assistant.tools.aria

import dev.antigravity.fluidengine.ai.provider.ModelTier
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.antigravity.fluidengine.ai.tools.AiTool
import dev.antigravity.fluidengine.ai.tools.AiToolGroup
import dev.antigravity.fluidengine.ai.tools.Args.str
import dev.antigravity.fluidengine.ai.tools.Schema
import dev.antigravity.fluidengine.ai.tools.ToolOutput
import dev.antigravity.fluidengine.ai.tools.ToolText
import dev.pampa.pampai.core.assistant.permissions.SpecialAccess
import dev.pampa.pampai.core.assistant.tools.PampaiGroup
import dev.pampa.pampai.core.assistant.tools.PampaiToolContext
import java.time.ZonedDateTime
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject

/** Come e' impostata PampAI adesso, in parole: per "che modello usi?", "le azioni sono attive?". */
class ImpostazioniPampaiTool : AiTool<PampaiToolContext> {
  override val name = "impostazioni_pampai"
  override val group: AiToolGroup = PampaiGroup.ARIA
  override val description = "Legge le impostazioni di PampAI: servizi e modelli in uso, azioni, lettura ad alta voce, riconoscimento vocale, azioni fidate, permessi. Solo lettura: per cambiarle l'utente apre le impostazioni [[impostazioni:aria]]."
  override val parameters = Schema.obj(mapOf("sezione" to Schema.str("una sezione sola (facoltativo)", enum = listOf("servizi", "voce", "azioni", "permessi"))))

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    val ai = ctx.aiSettings.settings.first()
    val pampai = ctx.settings.current()
    val section = args.str("sezione")
    return ToolText.output(3_000) {
      if (section == null || section == "servizi") {
        line("assistente", if (ai.enabled) "accesa" else "spenta")
        line("ordine servizi", ai.chatOrder.joinToString(" → ") { it.label })
        ai.chatOrder.forEach { p ->
          line("modelli ${p.label}", "router ${ai.classifierModel(p) ?: "—"} · chat ${ai.chatModel(p) ?: "—"} · profondo ${ai.deepModel(p) ?: ai.chatModel(p) ?: "—"}")
        }
        line("ragionamento", ai.thinking.name.lowercase())
      }
      if (section == null || section == "voce") {
        line("riconoscimento", when (pampai.sttMode.name) { "DUAL" -> "doppio (sistema + Whisper)"; "WHISPER" -> "solo Whisper"; else -> "solo sistema" })
        line("trascrizione", ai.sttOrder.joinToString(" → ") { "${it.label} ${ai.sttModel(it)}" })
        line("parti in testo", if (pampai.startInText) "si'" else "no")
        line("lettura ad alta voce", if (ai.speakReplies) "si'" else "no")
        line("voce", when (pampai.ttsEngine.name) { "GEMINI" -> "Gemini"; "GROQ_EN" -> "Groq (inglese)"; else -> "sistema" })
      }
      if (section == null || section == "azioni") {
        line("azioni", if (ai.actionsEnabled) "abilitate" else "disabilitate")
        line("azioni fidate (senza conferma)", pampai.trustedActions.sorted().joinToString(", ").ifEmpty { "nessuna" })
      }
      if (section == null || section == "permessi") {
        SpecialAccess.entries.forEach { line(it.label, if (it.granted(ctx.app)) "attivo" else "non attivo") }
        val runtime = mapOf("calendario" to arrayOf(android.Manifest.permission.READ_CALENDAR), "contatti" to arrayOf(android.Manifest.permission.READ_CONTACTS), "telefonate" to arrayOf(android.Manifest.permission.CALL_PHONE), "posizione" to arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION), "microfono" to arrayOf(android.Manifest.permission.RECORD_AUDIO))
        runtime.forEach { (label, perms) -> line("permesso $label", if (ctx.permissions.has(*perms)) "concesso" else "non concesso") }
      }
      line("nota", "per cambiare qualcosa: [[impostazioni:aria]]")
    }
  }
}

/** Quanto ha consumato Aria: richieste, token, costo stimato, per servizio e modello. */
class ConsumoTool : AiTool<PampaiToolContext> {
  override val name = "consumo"
  override val group: AiToolGroup = PampaiGroup.ARIA
  override val description = "I consumi di Aria oggi, questa settimana o questo mese: richieste, token, costo stimato, errori di limite, per servizio. Per \"quanto ho speso?\", \"quante richieste ho fatto oggi?\"."
  override val parameters = Schema.obj(mapOf("periodo" to Schema.str("il periodo (default oggi)", enum = listOf("oggi", "settimana", "mese"))))

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    val now = ZonedDateTime.now(ctx.zone)
    val period = args.str("periodo") ?: "oggi"
    val from = when (period) {
      "settimana" -> now.toLocalDate().minusDays(6).atStartOfDay(ctx.zone)
      "mese" -> now.toLocalDate().withDayOfMonth(1).atStartOfDay(ctx.zone)
      else -> now.toLocalDate().atStartOfDay(ctx.zone)
    }.toInstant().toEpochMilli()
    val events = ctx.usage.observeSince(from).first()
    if (events.isEmpty()) return ToolText.output { line("consumo ($period)", "nessuna richiesta") }
    return ToolText.output(3_000) {
      line("periodo", period)
      line("richieste", events.size)
      line("token", events.sumOf { it.tokens })
      val cost = events.sumOf { it.costUsd ?: 0.0 }
      line("costo stimato", if (cost > 0) String.format(Locale.US, "%.4f $", cost) else "0 (piani gratuiti o prezzi non noti)")
      val limited = events.count { it.rateLimited }
      if (limited > 0) line("errori di limite (429)", limited)
      val errors = events.count { it.error != null && !it.rateLimited }
      if (errors > 0) line("altri errori", errors)
      events.groupBy { it.provider }.forEach { (provider, list) ->
        line("${provider.label}", "${list.size} richieste · ${list.sumOf { it.tokens }} token" + (list.sumOf { it.costUsd ?: 0.0 }.takeIf { it > 0 }?.let { " · ${String.format(Locale.US, "%.4f $", it)}" } ?: ""))
        list.groupBy { it.model }.entries.sortedByDescending { it.value.size }.take(5).forEach { (model, byModel) ->
          line("  $model", "${byModel.size} · ${byModel.sumOf { it.tokens }} token" + (byModel.mapNotNull { it.audioSeconds }.sum().takeIf { it > 0 }?.let { " · ${it.toInt()} s audio" } ?: ""))
        }
        list.lastOrNull { it.remainingRequests != null }?.let { line("  limite rimasto", "${it.remainingRequests} richieste" + (it.remainingTokens?.let { t -> ", $t token" } ?: "")) }
      }
      line("dettagli", "[[impostazioni:consumi]]")
    }
  }
}

/** Le app Pampa: installate o no, e se Aria ci parla gia'. */
class AppCollegateTool : AiTool<PampaiToolContext> {
  override val name = "app_collegate"
  override val group: AiToolGroup = PampaiGroup.ARIA
  override val description = "Quali app Pampa sono installate e quali Aria sa gia' usare (registro, meteo, bus, convertitore, store, Fluidify). Per \"cosa puoi fare con le mie app?\"."
  override val parameters = Schema.obj(emptyMap())

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    val pm = ctx.app.packageManager
    fun installed(pkg: String) = runCatching { pm.getPackageInfo(pkg, 0); true }.getOrDefault(false)
    val apps = listOf(
      Triple("ClasseViva Expressive", "dev.antigravity.classevivaexpressive", "registro scolastico: voti, compiti, orario, bacheca, assenze"),
      Triple("Fluid Weather", "dev.pampa.fluidweather", "meteo: adesso, previsioni, pioggia, radar, aria, sole e luna"),
      Triple("Fluid Transit", "dev.antigravity.fluidtransit", "autobus: prossimi passaggi, linee, bus dal vivo, come arrivo"),
      Triple("Convert to it!", "com.p2r3.convert", "convertitore di file: formati e conversioni"),
      Triple("Pampa Store", "com.pampa.store", "lo store delle app Pampa: catalogo, aggiornamenti, installazioni"),
      Triple("Fluidify", "dev.pampa.fluidify", "musica: riproduzione, ricerca, playlist, recenti"),
    )
    val bridged = ctx.connectedPackages()
    return ToolText.output {
      apps.forEach { (label, pkg, what) ->
        val state = when {
          !installed(pkg) -> "non installata (si installa dal Pampa Store)"
          pkg == "dev.pampa.fluidify" -> "installata · collegata (categoria musica)"
          pkg in bridged -> "installata · collegata"
          else -> "installata · collegamento in arrivo con un aggiornamento dell'app"
        }
        line(label, "$what · $state")
      }
      line("store", if (installed("com.pampa.store")) "[[apri:store]]" else "il Pampa Store si scarica da github.com/Casual76/Pampa-store")
    }
  }
}

fun ariaExtraTools(): List<AiTool<PampaiToolContext>> = listOf(ImpostazioniPampaiTool(), ConsumoTool(), AppCollegateTool())

@Suppress("unused")
private val keepTierImport = ModelTier.CHAT

@Suppress("unused")
private val keepProviderImport = ProviderId.GROQ
