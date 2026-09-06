package dev.pampa.pampai.core.assistant.tools.device

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import dev.antigravity.fluidengine.ai.tools.AiTool
import dev.antigravity.fluidengine.ai.tools.AiToolGroup
import dev.antigravity.fluidengine.ai.tools.Args.str
import dev.antigravity.fluidengine.ai.tools.Schema
import dev.antigravity.fluidengine.ai.tools.ToolOutput
import dev.antigravity.fluidengine.ai.tools.ToolText
import dev.pampa.pampai.core.assistant.tools.ACTIONS_OFF
import dev.pampa.pampai.core.assistant.tools.PampaiGroup
import dev.pampa.pampai.core.assistant.tools.PampaiToolContext
import dev.pampa.pampai.core.assistant.tools.Text
import kotlinx.serialization.json.JsonObject

class ApriAppTool : AiTool<PampaiToolContext> {
  override val name = "apri_app"
  override val group: AiToolGroup = PampaiGroup.APRI
  override val description = "Apre un'app per nome (\"apri WhatsApp\", \"lancia il meteo\"). Le app Pampa si chiamano classeviva, meteo, bus, convert, store, fluidify."
  override val parameters = Schema.obj(mapOf("nome" to Schema.str("il nome dell'app")), required = listOf("nome"))
  override val isAction = true

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    if (!ctx.actionsEnabled) return ToolOutput(ACTIONS_OFF)
    val name = args.str("nome") ?: return ToolOutput.error("manca il nome")
    val pm = ctx.app.packageManager
    val (label, packageName) = Device.findApp(pm, name) ?: run {
      val known = Device.KNOWN[Text.normalize(name)]
      return if (known != null && known.startsWith("dev.") || known?.startsWith("com.pampa") == true || known?.startsWith("com.p2r3") == true) {
        ToolOutput.error("$name non e' installata: si installa dal Pampa Store [[apri:store]]")
      } else {
        ToolOutput.error("nessuna app che si chiami \"$name\": chiedi app_installate per l'elenco")
      }
    }
    val intent = pm.getLaunchIntentForPackage(packageName) ?: return ToolOutput.error("$label non si puo' aprire da qui")
    if (!Device.launch(ctx, intent)) return ToolOutput.error("$label non si e' aperta")
    return ToolText.output { line("fatto", "aperta $label") }
  }
}

class AppInstallateTool : AiTool<PampaiToolContext> {
  override val name = "app_installate"
  override val group: AiToolGroup = PampaiGroup.APRI
  override val description = "L'elenco delle app installate con un'icona (nomi), con un filtro facoltativo. Per \"ho Spotify?\", \"quali app ho?\"."
  override val parameters = Schema.obj(mapOf("filtro" to Schema.str("parole nel nome (facoltativo)")))

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    val filter = args.str("filtro")
    val apps = Device.launchableApps(ctx.app.packageManager).filter { filter == null || Text.matches(filter, it.first) || it.second.contains(Text.normalize(filter)) }
    if (apps.isEmpty()) return ToolText.output { line("app", if (filter != null) "nessuna che somigli a \"$filter\"" else "nessuna") }
    return ToolText.output(3_000) {
      line("app", apps.size)
      line(apps.take(80).joinToString(", ") { it.first })
      if (apps.size > 80) line("altre", apps.size - 80)
    }
  }
}

class ApriUrlTool : AiTool<PampaiToolContext> {
  override val name = "apri_url"
  override val group: AiToolGroup = PampaiGroup.APRI
  override val description = "Apre un link nel browser (o nell'app che lo gestisce: mappe, YouTube…). Per \"apri google.it\", \"aprimi questa pagina\"."
  override val parameters = Schema.obj(mapOf("url" to Schema.str("l'indirizzo, con o senza https://")), required = listOf("url"))
  override val isAction = true

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    if (!ctx.actionsEnabled) return ToolOutput(ACTIONS_OFF)
    var url = args.str("url") ?: return ToolOutput.error("manca l'url")
    if (!url.contains("://")) url = "https://$url"
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return ToolOutput.error("url non valido")
    if (uri.scheme !in setOf("http", "https", "geo", "mailto", "tel")) return ToolOutput.error("apro solo link http/https (o geo, mailto, tel)")
    if (!Device.launch(ctx, Intent(Intent.ACTION_VIEW, uri))) return ToolOutput.error("nessuna app apre questo link")
    return ToolText.output { line("fatto", "aperto $url") }
  }
}

class ApriImpostazioniTool : AiTool<PampaiToolContext> {
  override val name = "apri_impostazioni"
  override val group: AiToolGroup = PampaiGroup.APRI
  override val description = "Apre una pagina delle impostazioni di Android: wifi, bluetooth, batteria, schermo, audio, notifiche, app, posizione, sicurezza, lingua, data e ora, accessibilita', assistente, o quelle di un'app (\"impostazioni di WhatsApp\")."
  override val parameters = Schema.obj(mapOf("sezione" to Schema.str("la sezione, o \"app:NOME\" per la pagina di un'app (default: la home delle impostazioni)")))
  override val isAction = true

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    if (!ctx.actionsEnabled) return ToolOutput(ACTIONS_OFF)
    val section = Text.normalize(args.str("sezione") ?: "")
    val intent: Intent = when {
      section.startsWith("app:") || section.startsWith("app ") -> {
        val appName = section.removePrefix("app:").removePrefix("app ").trim()
        val (_, pkg) = Device.findApp(ctx.app.packageManager, appName) ?: return ToolOutput.error("app \"$appName\" non trovata")
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
      }
      section.isEmpty() || section == "home" || section == "generali" -> Intent(Settings.ACTION_SETTINGS)
      else -> SECTIONS.entries.firstOrNull { (keys, _) -> keys.any { section.contains(it) } }?.value?.let { Intent(it) } ?: return ToolOutput.error("sezione sconosciuta; valide: ${SECTIONS.keys.joinToString { it.first() }}")
    }
    if (!Device.launch(ctx, intent)) return ToolOutput.error("questa pagina non esiste su questo telefono")
    return ToolText.output { line("fatto", "aperte le impostazioni${if (section.isNotEmpty()) " ($section)" else ""}") }
  }

  private companion object {
    val SECTIONS: Map<List<String>, String> = mapOf(
      listOf("wifi", "wi-fi", "rete") to Settings.ACTION_WIFI_SETTINGS,
      listOf("bluetooth") to Settings.ACTION_BLUETOOTH_SETTINGS,
      listOf("batteria", "risparmio") to Settings.ACTION_BATTERY_SAVER_SETTINGS,
      listOf("schermo", "display", "luminosita") to Settings.ACTION_DISPLAY_SETTINGS,
      listOf("audio", "suoni", "suono", "volume") to Settings.ACTION_SOUND_SETTINGS,
      listOf("notifiche") to Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
      listOf("app", "applicazioni") to Settings.ACTION_APPLICATION_SETTINGS,
      listOf("posizione", "gps", "localizzazione") to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
      listOf("sicurezza", "blocco") to Settings.ACTION_SECURITY_SETTINGS,
      listOf("lingua", "tastiera", "input") to Settings.ACTION_INPUT_METHOD_SETTINGS,
      listOf("data", "ora", "orario") to Settings.ACTION_DATE_SETTINGS,
      listOf("accessibilita") to Settings.ACTION_ACCESSIBILITY_SETTINGS,
      listOf("assistente", "voce", "assist") to Settings.ACTION_VOICE_INPUT_SETTINGS,
      listOf("memoria", "spazio", "archiviazione") to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
      listOf("aereo", "aeroplano") to Settings.ACTION_AIRPLANE_MODE_SETTINGS,
      listOf("non disturbare", "dnd") to Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS,
      listOf("predefinite", "default") to Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
      listOf("sviluppatore", "sviluppo") to Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
      listOf("informazioni", "telefono", "dispositivo") to Settings.ACTION_DEVICE_INFO_SETTINGS,
    )
  }
}

fun openTools(): List<AiTool<PampaiToolContext>> = listOf(ApriAppTool(), AppInstallateTool(), ApriUrlTool(), ApriImpostazioniTool())
