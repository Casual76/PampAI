package dev.pampa.pampai.core.assistant.tools.music

import android.media.AudioManager
import android.os.Bundle
import androidx.media3.common.Player
import dev.antigravity.fluidengine.ai.tools.AiTool
import dev.antigravity.fluidengine.ai.tools.AiToolGroup
import dev.antigravity.fluidengine.ai.tools.Args.bool
import dev.antigravity.fluidengine.ai.tools.Args.int
import dev.antigravity.fluidengine.ai.tools.Args.str
import dev.antigravity.fluidengine.ai.tools.ConfirmationText
import dev.antigravity.fluidengine.ai.tools.Schema
import dev.antigravity.fluidengine.ai.tools.ToolOutput
import dev.antigravity.fluidengine.ai.tools.ToolText
import dev.pampa.pampai.core.assistant.music.FluidifyClient
import dev.pampa.pampai.core.assistant.music.MusicItem
import dev.pampa.pampai.core.assistant.tools.ACTIONS_OFF
import dev.pampa.pampai.core.assistant.tools.PampaiGroup
import dev.pampa.pampai.core.assistant.tools.PampaiToolContext
import dev.pampa.pampai.core.assistant.tools.Text
import kotlinx.serialization.json.JsonObject

private const val NOT_INSTALLED = "Fluidify non e' installata: si installa dal Pampa Store [[apri:store]]"
private const val NOT_RESPONDING = "Fluidify non risponde: l'utente puo' aprirla una volta [[apri:fluidify]] e riprovare"

private fun PampaiToolContext.fluidifyOrError(): ToolOutput? = if (!fluidify.installed) ToolOutput.error(NOT_INSTALLED) else null
private fun PampaiToolContext.actionsOrOff(): ToolOutput? = if (actionsEnabled) null else ToolOutput(ACTIONS_OFF)

private fun MusicItem.line(): String = listOfNotNull(title, artist).joinToString(" — ") + (album?.let { " ($it)" } ?: "") + (durationMillis?.takeIf { it > 0 }?.let { " · ${it / 60000}:${"%02d".format(it / 1000 % 60)}" } ?: "")

private fun ToolText.Builder.items(label: String, list: List<MusicItem>, max: Int = 30) {
  line(label, list.size)
  list.take(max).forEachIndexed { i, item -> line("${i + 1}. ${item.line()}${if (item.browsable) " [raccolta]" else ""} · id=${item.mediaId}") }
  if (list.size > max) line("altri", list.size - max)
}

class MusicaRiproduciTool : AiTool<PampaiToolContext> {
  override val name = "musica_riproduci"
  override val group: AiToolGroup = PampaiGroup.RIPRODUZIONE
  override val description = "Fa partire la musica su Fluidify: un brano, un artista, un album o una playlist per nome (cerca e suona il primo risultato), un id preciso (da musica_cerca / musica_playlist), oppure riprende quello che c'era. Per \"metti su i Pink Floyd\", \"suona la playlist palestra\", \"riprendi la musica\"."
  override val parameters = Schema.obj(mapOf("cosa" to Schema.str("cosa suonare (brano, artista, album, playlist), vuoto per riprendere"), "id" to Schema.str("l'id di un elemento gia' trovato")))
  override val isAction = true

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    ctx.fluidifyOrError()?.let { return it }
    ctx.actionsOrOff()?.let { return it }
    val id = args.str("id")
    val what = args.str("cosa")
    if (id != null) {
      return if (ctx.fluidify.playItem(id)) ToolText.output { line("fatto", "in riproduzione: $id") } else ToolOutput.error(NOT_RESPONDING)
    }
    if (what == null) {
      return if (ctx.fluidify.play()) ToolText.output { line("fatto", "musica ripresa"); ctx.fluidify.nowPlaying()?.item?.let { line("brano", it.line()) } } else ToolOutput.error(NOT_RESPONDING)
    }
    val results = ctx.fluidify.search(what) ?: return ToolOutput.error(NOT_RESPONDING)
    val playlists = ctx.fluidify.children(FluidifyClient.ID_PLAYLISTS).orEmpty().filter { Text.matches(what, it.title) }
    val chosen = playlists.maxByOrNull { Text.score(what, it.title) } ?: results.firstOrNull()
    if (chosen == null) {
      // Il ripiego: la ricerca di Fluidify da fuori, che sa aprire anche cio' che l'albero non elenca.
      return if (ctx.fluidify.playFromSearch(what)) ToolText.output { line("fatto", "chiesto a Fluidify di suonare \"$what\" (ricerca aperta nell'app)") } else ToolOutput.error("niente trovato per \"$what\"")
    }
    return if (ctx.fluidify.playItem(chosen.mediaId)) ToolText.output { line("fatto", "in riproduzione: ${chosen.line()}"); if (results.size > 1) line("altri risultati", results.drop(1).take(3).joinToString("; ") { it.line() }) }
    else ToolOutput.error(NOT_RESPONDING)
  }
}

class MusicaControlloTool : AiTool<PampaiToolContext> {
  override val name = "musica_controllo"
  override val group: AiToolGroup = PampaiGroup.RIPRODUZIONE
  override val description = "Pausa, riproduci, avanti, indietro, stop, oppure vai a un punto del brano (secondi). Su Fluidify."
  override val parameters = Schema.obj(mapOf("azione" to Schema.str("l'azione", enum = listOf("pausa", "riproduci", "avanti", "indietro", "stop", "vai_a")), "secondi" to Schema.int("per vai_a: la posizione in secondi")), required = listOf("azione"))
  override val isAction = true

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    ctx.fluidifyOrError()?.let { return it }
    ctx.actionsOrOff()?.let { return it }
    val ok = when (val action = args.str("azione")) {
      "pausa" -> ctx.fluidify.pause()
      "riproduci" -> ctx.fluidify.play()
      "avanti" -> ctx.fluidify.next()
      "indietro" -> ctx.fluidify.previous()
      "stop" -> ctx.fluidify.stop()
      "vai_a" -> ctx.fluidify.seekTo((args.int("secondi") ?: 0) * 1000L)
      else -> return ToolOutput.error("azione sconosciuta: $action")
    }
    if (!ok) return ToolOutput.error(NOT_RESPONDING)
    val now = ctx.fluidify.nowPlaying()
    return ToolText.output {
      line("fatto", args.str("azione")!!)
      now?.item?.let { line("brano", it.line() + if (now.playing) " · in riproduzione" else " · in pausa") }
    }
  }
}

class MusicaAdessoTool : AiTool<PampaiToolContext> {
  override val name = "musica_adesso"
  override val group: AiToolGroup = PampaiGroup.RIPRODUZIONE
  override val description = "Cosa sta suonando su Fluidify: brano, artista, posizione, shuffle e ripeti. Per \"cosa sto ascoltando?\", \"che canzone e'?\"."
  override val parameters = Schema.obj(emptyMap())

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    ctx.fluidifyOrError()?.let { return it }
    val now = ctx.fluidify.nowPlaying() ?: return ToolOutput.error(NOT_RESPONDING)
    val item = now.item ?: return ToolText.output { line("fluidify", "niente in coda") }
    return ToolText.output {
      line("brano", item.title)
      item.artist?.let { line("artista", it) }
      item.album?.let { line("album", it) }
      line("stato", if (now.playing) "in riproduzione" else "in pausa")
      if (now.durationMillis > 0) line("posizione", "${now.positionMillis / 60000}:${"%02d".format(now.positionMillis / 1000 % 60)} di ${now.durationMillis / 60000}:${"%02d".format(now.durationMillis / 1000 % 60)}")
      line("coda", "${now.queueIndex + 1} di ${now.queueSize}")
      line("shuffle", if (now.shuffle) "on" else "off")
      line("ripeti", FluidifyClient.REPEAT_LABELS[now.repeat] ?: "—")
    }
  }
}

class MusicaCodaTool : AiTool<PampaiToolContext> {
  override val name = "musica_coda"
  override val group: AiToolGroup = PampaiGroup.RIPRODUZIONE
  override val description = "I prossimi brani in coda su Fluidify."
  override val parameters = Schema.obj(mapOf("quanti" to Schema.int("quanti (default 15)", minimum = 1, maximum = 50)))

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    ctx.fluidifyOrError()?.let { return it }
    val queue = ctx.fluidify.queue(args.int("quanti") ?: 15) ?: return ToolOutput.error(NOT_RESPONDING)
    if (queue.isEmpty()) return ToolText.output { line("coda", "vuota") }
    return ToolText.output { items("in coda (dal brano attuale)", queue, 50) }
  }
}

class MusicaShuffleRipetiTool : AiTool<PampaiToolContext> {
  override val name = "musica_shuffle_ripeti"
  override val group: AiToolGroup = PampaiGroup.RIPRODUZIONE
  override val description = "Accende o spegne lo shuffle (casuale) e imposta ripeti (spento, un brano, tutto) su Fluidify."
  override val parameters = Schema.obj(mapOf("shuffle" to Schema.bool("casuale on/off"), "ripeti" to Schema.str("modalita' ripeti", enum = listOf("spento", "brano", "tutto"))))
  override val isAction = true

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    ctx.fluidifyOrError()?.let { return it }
    ctx.actionsOrOff()?.let { return it }
    val shuffle = args.bool("shuffle")
    val repeat = args.str("ripeti")?.let { when (it) { "spento" -> Player.REPEAT_MODE_OFF; "brano" -> Player.REPEAT_MODE_ONE; "tutto" -> Player.REPEAT_MODE_ALL; else -> null } }
    if (shuffle == null && repeat == null) return ToolOutput.error("di' shuffle (true/false) o ripeti (spento, brano, tutto)")
    if (shuffle != null && !ctx.fluidify.setShuffle(shuffle)) return ToolOutput.error(NOT_RESPONDING)
    if (repeat != null && !ctx.fluidify.setRepeat(repeat)) return ToolOutput.error(NOT_RESPONDING)
    val now = ctx.fluidify.nowPlaying()
    return ToolText.output {
      line("fatto", "impostato")
      now?.let { line("shuffle", if (it.shuffle) "on" else "off"); line("ripeti", FluidifyClient.REPEAT_LABELS[it.repeat] ?: "—") }
    }
  }
}

class MusicaRadioTool : AiTool<PampaiToolContext> {
  override val name = "musica_radio"
  override val group: AiToolGroup = PampaiGroup.RIPRODUZIONE
  override val description = "Avvia la radio di Fluidify dal brano in corso: brani simili, senza fine. Per \"metti la radio di questa canzone\", \"fammi sentire cose simili\"."
  override val parameters = Schema.obj(emptyMap())
  override val isAction = true

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    ctx.fluidifyOrError()?.let { return it }
    ctx.actionsOrOff()?.let { return it }
    val now = ctx.fluidify.nowPlaying()?.item ?: return ToolOutput.error("nessun brano in corso da cui partire: prima musica_riproduci")
    if (!ctx.fluidify.custom(FluidifyClient.CMD_RADIO)) return ToolOutput.error(NOT_RESPONDING)
    return ToolText.output { line("fatto", "radio avviata da \"${now.title}\"") }
  }
}

class MusicaVolumeTool : AiTool<PampaiToolContext> {
  override val name = "musica_volume"
  override val group: AiToolGroup = PampaiGroup.RIPRODUZIONE
  override val description = "Il volume della musica (quello dei media del telefono): legge o imposta in percento, o su/giu'/muto."
  override val parameters = Schema.obj(mapOf("livello" to Schema.str("0-100, su, giu', muto, massimo (senza: dice il livello)")))
  override val isAction = true

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    val audio = ctx.app.getSystemService(AudioManager::class.java) ?: return ToolOutput.error("audio non disponibile")
    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    val level = args.str("livello") ?: return ToolText.output { line("volume musica", "${current * 100 / max}%") }
    ctx.actionsOrOff()?.let { return it }
    val target = when (level.lowercase().trim()) {
      "su", "alza", "piu", "più" -> (current + maxOf(1, max / 6)).coerceAtMost(max)
      "giu", "giu'", "giù", "abbassa", "meno" -> (current - maxOf(1, max / 6)).coerceAtLeast(0)
      "muto", "zero" -> 0
      "massimo", "max" -> max
      else -> level.filter { it.isDigit() }.toIntOrNull()?.let { it.coerceIn(0, 100) * max / 100 } ?: return ToolOutput.error("livello: 0-100, su, giu', muto, massimo")
    }
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
    return ToolText.output { line("fatto", "volume musica al ${audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max}%") }
  }
}

class MusicaSalvaTool : AiTool<PampaiToolContext> {
  override val name = "musica_salva"
  override val group: AiToolGroup = PampaiGroup.RIPRODUZIONE
  override val description = "Salva (mette il cuore a) il brano in corso nei preferiti di Fluidify, o lo toglie. Chiede conferma."
  override val parameters = Schema.obj(mapOf("togli" to Schema.bool("true per togliere dai preferiti invece di aggiungere")))
  override val isAction = true
  override val needsConfirmation = true

  override suspend fun describe(args: JsonObject, ctx: PampaiToolContext): ConfirmationText? {
    val now = ctx.fluidify.nowPlaying()?.item ?: return null
    return ConfirmationText(if (args.bool("togli") == true) "Togliere \"${now.title}\" dai preferiti?" else "Salvare \"${now.title}\" nei preferiti?", now.artist)
  }

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    ctx.fluidifyOrError()?.let { return it }
    val now = ctx.fluidify.nowPlaying()?.item ?: return ToolOutput.error("nessun brano in corso")
    val remove = args.bool("togli") == true
    ctx.confirm(name, if (remove) "Togliere \"${now.title}\" dai preferiti?" else "Salvare \"${now.title}\" nei preferiti?", now.artist)?.let { return it }
    val ok = ctx.fluidify.custom(FluidifyClient.CMD_LIKE, Bundle().apply { putBoolean("remove", remove); putString("uri", now.mediaId) })
    if (!ok) return ToolOutput.error("questa versione di Fluidify non ha ancora il comando \"salva\": aggiornala dal Pampa Store [[apri:store]]")
    return ToolText.output { line("fatto", if (remove) "tolto dai preferiti: ${now.title}" else "salvato nei preferiti: ${now.title}") }
  }
}

class MusicaCercaTool : AiTool<PampaiToolContext> {
  override val name = "musica_cerca"
  override val group: AiToolGroup = PampaiGroup.LIBRERIA
  override val description = "Cerca brani, artisti e album su Fluidify e da' i risultati con il loro id (da passare a musica_riproduci)."
  override val parameters = Schema.obj(mapOf("cosa" to Schema.str("cosa cercare")), required = listOf("cosa"))

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    ctx.fluidifyOrError()?.let { return it }
    val query = args.str("cosa") ?: return ToolOutput.error("manca cosa cercare")
    val results = ctx.fluidify.search(query) ?: return ToolOutput.error(NOT_RESPONDING)
    if (results.isEmpty()) return ToolText.output { line("risultati", "nessuno per \"$query\"") }
    return ToolText.output { items("risultati per \"$query\"", results, 20) }
  }
}

class MusicaPlaylistTool : AiTool<PampaiToolContext> {
  override val name = "musica_playlist"
  override val group: AiToolGroup = PampaiGroup.LIBRERIA
  override val description = "Le playlist dell'utente su Fluidify, con id."
  override val parameters = Schema.obj(emptyMap())

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    ctx.fluidifyOrError()?.let { return it }
    val playlists = ctx.fluidify.children(FluidifyClient.ID_PLAYLISTS) ?: return ToolOutput.error(NOT_RESPONDING)
    if (playlists.isEmpty()) return ToolText.output { line("playlist", "nessuna") }
    return ToolText.output { items("playlist", playlists, 40) }
  }
}

class PlaylistBraniTool : AiTool<PampaiToolContext> {
  override val name = "playlist_brani"
  override val group: AiToolGroup = PampaiGroup.LIBRERIA
  override val description = "I brani di una playlist di Fluidify (per nome o id)."
  override val parameters = Schema.obj(mapOf("nome" to Schema.str("il nome della playlist"), "id" to Schema.str("oppure l'id")))

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    ctx.fluidifyOrError()?.let { return it }
    val id = args.str("id") ?: args.str("nome")?.let { n ->
      val playlists = ctx.fluidify.children(FluidifyClient.ID_PLAYLISTS) ?: return ToolOutput.error(NOT_RESPONDING)
      playlists.filter { Text.matches(n, it.title) }.maxByOrNull { Text.score(n, it.title) }?.mediaId ?: return ToolOutput.error("playlist \"$n\" non trovata; ci sono: ${playlists.joinToString { it.title }}")
    } ?: return ToolOutput.error("dai il nome o l'id")
    val tracks = ctx.fluidify.children(id, 100) ?: return ToolOutput.error(NOT_RESPONDING)
    if (tracks.isEmpty()) return ToolText.output { line("brani", "nessuno") }
    return ToolText.output(3_000) { items("brani", tracks, 40) }
  }
}

class MusicaRecentiTool : AiTool<PampaiToolContext> {
  override val name = "musica_recenti"
  override val group: AiToolGroup = PampaiGroup.LIBRERIA
  override val description = "Gli ascolti recenti su Fluidify."
  override val parameters = Schema.obj(emptyMap())

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    ctx.fluidifyOrError()?.let { return it }
    val recent = ctx.fluidify.children(FluidifyClient.ID_RECENT) ?: return ToolOutput.error(NOT_RESPONDING)
    if (recent.isEmpty()) return ToolText.output { line("recenti", "niente") }
    return ToolText.output { items("ascoltati di recente", recent, 25) }
  }
}

class MusicaDownloadTool : AiTool<PampaiToolContext> {
  override val name = "musica_download"
  override val group: AiToolGroup = PampaiGroup.LIBRERIA
  override val description = "I brani scaricati (disponibili offline) su Fluidify."
  override val parameters = Schema.obj(emptyMap())

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    ctx.fluidifyOrError()?.let { return it }
    val downloads = ctx.fluidify.children(FluidifyClient.ID_DOWNLOADS, 100) ?: return ToolOutput.error(NOT_RESPONDING)
    if (downloads.isEmpty()) return ToolText.output { line("download", "nessuno") }
    return ToolText.output(3_000) { items("scaricati", downloads, 40) }
  }
}

class MusicaDispositiviTool : AiTool<PampaiToolContext> {
  override val name = "musica_dispositivi"
  override val group: AiToolGroup = PampaiGroup.LIBRERIA
  override val description = "I dispositivi su cui Fluidify puo' suonare (Connect). Sperimentale: la scelta si fa nell'app."
  override val parameters = Schema.obj(emptyMap())

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    ctx.fluidifyOrError()?.let { return it }
    return ToolText.output {
      line("dispositivi", "l'elenco dei dispositivi Connect si vede in Fluidify: apri l'app e tocca l'icona dei dispositivi [[apri:fluidify]]")
      line("nota", "il telefono suona su se stesso o sull'uscita audio attiva (cuffie, Bluetooth)")
    }
  }
}

class MusicaApriTool : AiTool<PampaiToolContext> {
  override val name = "musica_apri"
  override val group: AiToolGroup = PampaiGroup.LIBRERIA
  override val description = "Apre l'app Fluidify."
  override val parameters = Schema.obj(emptyMap())
  override val isAction = true

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    ctx.fluidifyOrError()?.let { return it }
    ctx.actionsOrOff()?.let { return it }
    ctx.fluidify.open()
    return ToolText.output { line("fatto", "Fluidify aperta") }
  }
}

fun musicTools(): List<AiTool<PampaiToolContext>> = listOf(
  MusicaRiproduciTool(), MusicaControlloTool(), MusicaAdessoTool(), MusicaCodaTool(), MusicaShuffleRipetiTool(), MusicaRadioTool(), MusicaVolumeTool(), MusicaSalvaTool(),
  MusicaCercaTool(), MusicaPlaylistTool(), PlaylistBraniTool(), MusicaRecentiTool(), MusicaDownloadTool(), MusicaDispositiviTool(), MusicaApriTool(),
)
