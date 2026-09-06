package dev.pampa.pampai.core.assistant.tools.device

import android.app.NotificationManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.provider.Settings
import dev.antigravity.fluidengine.ai.tools.AiTool
import dev.antigravity.fluidengine.ai.tools.AiToolGroup
import dev.antigravity.fluidengine.ai.tools.Args.int
import dev.antigravity.fluidengine.ai.tools.Args.str
import dev.antigravity.fluidengine.ai.tools.ConfirmationText
import dev.antigravity.fluidengine.ai.tools.Schema
import dev.antigravity.fluidengine.ai.tools.ToolOutput
import dev.antigravity.fluidengine.ai.tools.ToolText
import dev.pampa.pampai.core.assistant.permissions.SpecialAccess
import dev.pampa.pampai.core.assistant.tools.ACTIONS_OFF
import dev.pampa.pampai.core.assistant.tools.PampaiGroup
import dev.pampa.pampai.core.assistant.tools.PampaiToolContext
import kotlinx.serialization.json.JsonObject

private fun PampaiToolContext.actionsOrOff(): ToolOutput? = if (actionsEnabled) null else ToolOutput(ACTIONS_OFF)

private fun onOff(raw: String?): Boolean? = when (raw?.lowercase()?.trim()) {
  "on", "accendi", "acceso", "accesa", "attiva", "si", "sì", "true", "1" -> true
  "off", "spegni", "spento", "spenta", "disattiva", "no", "false", "0" -> false
  else -> null
}

class TorciaTool : AiTool<PampaiToolContext> {
  override val name = "torcia"
  override val group: AiToolGroup = PampaiGroup.SISTEMA
  override val description = "Accende o spegne la torcia (il flash). Per \"accendi la torcia\", \"spegni la luce\"."
  override val parameters = Schema.obj(mapOf("stato" to Schema.str("on o off", enum = listOf("on", "off"))), required = listOf("stato"))
  override val isAction = true
  override val needsConfirmation = true

  override suspend fun describe(args: JsonObject, ctx: PampaiToolContext): ConfirmationText? = ConfirmationText(if (onOff(args.str("stato")) == true) "Accendere la torcia?" else "Spegnere la torcia?", null)

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    val on = onOff(args.str("stato")) ?: return ToolOutput.error("stato: on o off")
    ctx.confirm(name, if (on) "Accendere la torcia?" else "Spegnere la torcia?", null)?.let { return it }
    val camera = ctx.app.getSystemService(CameraManager::class.java) ?: return ToolOutput.error("nessuna fotocamera")
    val id = runCatching { camera.cameraIdList.firstOrNull { camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } }.getOrNull()
      ?: return ToolOutput.error("questo telefono non ha un flash")
    return runCatching { camera.setTorchMode(id, on); ToolText.output { line("fatto", if (on) "torcia accesa" else "torcia spenta") } }
      .getOrElse { ToolOutput.error("la fotocamera e' in uso da un'altra app: torcia non disponibile") }
  }
}

class VolumeTool : AiTool<PampaiToolContext> {
  override val name = "volume"
  override val group: AiToolGroup = PampaiGroup.SISTEMA
  override val description = "Legge o imposta il volume: media (musica), suoneria, notifiche, sveglia. Livello in percento, oppure su/giu'/muto/massimo. Senza argomenti dice i livelli."
  override val parameters = Schema.obj(
    mapOf(
      "livello" to Schema.str("0-100, oppure \"su\", \"giu'\", \"muto\", \"massimo\""),
      "flusso" to Schema.str("quale volume (default media)", enum = listOf("media", "suoneria", "notifiche", "sveglia")),
    ),
  )
  override val isAction = true

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    val audio = ctx.app.getSystemService(AudioManager::class.java) ?: return ToolOutput.error("audio non disponibile")
    val streams = mapOf("media" to AudioManager.STREAM_MUSIC, "suoneria" to AudioManager.STREAM_RING, "notifiche" to AudioManager.STREAM_NOTIFICATION, "sveglia" to AudioManager.STREAM_ALARM)
    fun percent(stream: Int) = audio.getStreamVolume(stream) * 100 / audio.getStreamMaxVolume(stream).coerceAtLeast(1)
    val level = args.str("livello")
    if (level == null) return ToolText.output { streams.forEach { (k, s) -> line(k, "${percent(s)}%") } }
    ctx.actionsOrOff()?.let { return it }
    val streamName = args.str("flusso") ?: "media"
    val stream = streams[streamName] ?: return ToolOutput.error("flusso: media, suoneria, notifiche, sveglia")
    val max = audio.getStreamMaxVolume(stream)
    val current = audio.getStreamVolume(stream)
    val target = when (level.lowercase().trim()) {
      "su", "alza", "piu", "più" -> (current + maxOf(1, max / 6)).coerceAtMost(max)
      "giu", "giu'", "giù", "abbassa", "meno" -> (current - maxOf(1, max / 6)).coerceAtLeast(0)
      "muto", "zero", "silenzio" -> 0
      "massimo", "max", "tutto" -> max
      else -> level.filter { it.isDigit() }.toIntOrNull()?.let { it.coerceIn(0, 100) * max / 100 } ?: return ToolOutput.error("livello: 0-100, su, giu', muto, massimo")
    }
    return runCatching {
      audio.setStreamVolume(stream, target, 0)
      ToolText.output { line("fatto", "volume $streamName al ${percent(stream)}%") }
    }.getOrElse { ToolOutput.error("non posso cambiare questo volume adesso (Non disturbare attivo?)") }
  }
}

class ModalitaSuoneriaTool : AiTool<PampaiToolContext> {
  override val name = "modalita_suoneria"
  override val group: AiToolGroup = PampaiGroup.SISTEMA
  override val description = "Legge o imposta la modalita' della suoneria: normale, vibrazione, silenzioso."
  override val parameters = Schema.obj(mapOf("modo" to Schema.str("la modalita' (senza: dice quella attuale)", enum = listOf("normale", "vibrazione", "silenzioso"))))
  override val isAction = true

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    val audio = ctx.app.getSystemService(AudioManager::class.java) ?: return ToolOutput.error("audio non disponibile")
    fun label(mode: Int) = when (mode) { AudioManager.RINGER_MODE_SILENT -> "silenzioso"; AudioManager.RINGER_MODE_VIBRATE -> "vibrazione"; else -> "normale" }
    val mode = args.str("modo") ?: return ToolText.output { line("suoneria", label(audio.ringerMode)) }
    ctx.actionsOrOff()?.let { return it }
    val target = when (mode) { "normale" -> AudioManager.RINGER_MODE_NORMAL; "vibrazione" -> AudioManager.RINGER_MODE_VIBRATE; "silenzioso" -> AudioManager.RINGER_MODE_SILENT; else -> return ToolOutput.error("modo: normale, vibrazione, silenzioso") }
    if (target == AudioManager.RINGER_MODE_SILENT && !SpecialAccess.DND.granted(ctx.app)) return ToolOutput.error(SpecialAccess.DND.missingText())
    return runCatching { audio.ringerMode = target; ToolText.output { line("fatto", "suoneria: ${label(audio.ringerMode)}") } }
      .getOrElse { ToolOutput.error(SpecialAccess.DND.missingText()) }
  }
}

class LuminositaTool : AiTool<PampaiToolContext> {
  override val name = "luminosita"
  override val group: AiToolGroup = PampaiGroup.SISTEMA
  override val description = "Legge o imposta la luminosita' dello schermo: percento, oppure \"auto\" per quella automatica."
  override val parameters = Schema.obj(mapOf("livello" to Schema.str("0-100, \"auto\", \"su\", \"giu'\" (senza: dice quella attuale)")))
  override val isAction = true

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    val resolver = ctx.app.contentResolver
    val auto = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    val current = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128) * 100 / 255
    val level = args.str("livello") ?: return ToolText.output { line("luminosita'", "$current%" + if (auto) " (automatica)" else "") }
    ctx.actionsOrOff()?.let { return it }
    if (!SpecialAccess.WRITE_SETTINGS.granted(ctx.app)) return ToolOutput.error(SpecialAccess.WRITE_SETTINGS.missingText())
    return runCatching {
      when (level.lowercase().trim()) {
        "auto", "automatica", "automatico" -> {
          Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
          ToolText.output { line("fatto", "luminosita' automatica") }
        }
        else -> {
          val target = when (level.lowercase().trim()) {
            "su", "alza", "piu", "più" -> (current + 15).coerceAtMost(100)
            "giu", "giu'", "giù", "abbassa", "meno" -> (current - 15).coerceAtLeast(0)
            else -> level.filter { it.isDigit() }.toIntOrNull()?.coerceIn(0, 100) ?: return ToolOutput.error("livello: 0-100, auto, su, giu'")
          }
          Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
          Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, (target * 255 / 100).coerceIn(1, 255))
          ToolText.output { line("fatto", "luminosita' al $target%") }
        }
      }
    }.getOrElse { ToolOutput.error(SpecialAccess.WRITE_SETTINGS.missingText()) }
  }
}

class RotazioneTool : AiTool<PampaiToolContext> {
  override val name = "rotazione"
  override val group: AiToolGroup = PampaiGroup.SISTEMA
  override val description = "Legge o imposta la rotazione automatica dello schermo: auto o bloccata."
  override val parameters = Schema.obj(mapOf("modo" to Schema.str("auto o bloccata (senza: dice lo stato)", enum = listOf("auto", "bloccata"))))
  override val isAction = true

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    val resolver = ctx.app.contentResolver
    val auto = Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
    val mode = args.str("modo") ?: return ToolText.output { line("rotazione", if (auto) "automatica" else "bloccata") }
    ctx.actionsOrOff()?.let { return it }
    if (!SpecialAccess.WRITE_SETTINGS.granted(ctx.app)) return ToolOutput.error(SpecialAccess.WRITE_SETTINGS.missingText())
    val target = when (mode) { "auto" -> 1; "bloccata" -> 0; else -> return ToolOutput.error("modo: auto o bloccata") }
    return runCatching { Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, target); ToolText.output { line("fatto", if (target == 1) "rotazione automatica" else "rotazione bloccata") } }
      .getOrElse { ToolOutput.error(SpecialAccess.WRITE_SETTINGS.missingText()) }
  }
}

class NonDisturbareTool : AiTool<PampaiToolContext> {
  override val name = "non_disturbare"
  override val group: AiToolGroup = PampaiGroup.SISTEMA
  override val description = "Legge o imposta Non disturbare: on (solo priorita'), off, o silenzio totale."
  override val parameters = Schema.obj(mapOf("stato" to Schema.str("on, off, totale (senza: dice lo stato)", enum = listOf("on", "off", "totale"))))
  override val isAction = true

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    val manager = ctx.app.getSystemService(NotificationManager::class.java) ?: return ToolOutput.error("notifiche non disponibili")
    fun label(filter: Int) = when (filter) {
      NotificationManager.INTERRUPTION_FILTER_NONE -> "silenzio totale"
      NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "attivo (solo priorita')"
      NotificationManager.INTERRUPTION_FILTER_ALARMS -> "attivo (solo sveglie)"
      NotificationManager.INTERRUPTION_FILTER_ALL -> "spento"
      else -> "sconosciuto"
    }
    val state = args.str("stato") ?: return ToolText.output { line("non disturbare", label(manager.currentInterruptionFilter)) }
    ctx.actionsOrOff()?.let { return it }
    if (!SpecialAccess.DND.granted(ctx.app)) return ToolOutput.error(SpecialAccess.DND.missingText())
    val target = when (state) { "on" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY; "off" -> NotificationManager.INTERRUPTION_FILTER_ALL; "totale" -> NotificationManager.INTERRUPTION_FILTER_NONE; else -> return ToolOutput.error("stato: on, off, totale") }
    return runCatching { manager.setInterruptionFilter(target); ToolText.output { line("fatto", "non disturbare: ${label(manager.currentInterruptionFilter)}") } }
      .getOrElse { ToolOutput.error(SpecialAccess.DND.missingText()) }
  }
}

fun systemTools(): List<AiTool<PampaiToolContext>> = listOf(TorciaTool(), VolumeTool(), ModalitaSuoneriaTool(), LuminositaTool(), RotazioneTool(), NonDisturbareTool())

@Suppress("unused")
private fun JsonObject.unusedInt() = int("x")
