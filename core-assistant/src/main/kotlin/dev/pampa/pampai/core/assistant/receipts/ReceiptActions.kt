package dev.pampa.pampai.core.assistant.receipts

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.antigravity.fluidengine.ai.tools.*
import dev.pampa.pampai.core.assistant.db.*
import dev.pampa.pampai.core.assistant.music.FluidifyClient
import dev.pampa.pampai.core.assistant.bridge.SignedPlaylistReader
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReceiptActions @Inject constructor(@ApplicationContext private val context: Context,
  private val db: PampaiDatabase, private val fluidify: FluidifyClient,
  private val signedPlaylists: SignedPlaylistReader) {
  /** Read-only reconciliation. Missing ID or expected snapshot cannot prove an interrupted write. */
  suspend fun reconcile(row: OperationEntity): ToolOutput? {
    val receipt = row.toReceipt()
    if (receipt.result.entities.any { it.source == "spotify" && it.type == "playlist" }) {
      val fresh = signedPlaylists.read(receipt.result, row.operationId)
      return ToolOutput(row.outputText.orEmpty(), result = fresh)
    }
    val event = receipt.result.entities.firstOrNull { it.source == "android.calendar" && it.type == "event" }
    val args = runCatching { Json.parseToJsonElement(row.argumentsJson).jsonObject }.getOrNull()
    val id = event?.id?.toLongOrNull() ?: args?.get("id")?.jsonPrimitive?.longOrNull
    if (row.toolName.startsWith("evento_") && id != null) {
      if (row.toolName == "evento_elimina") return CalendarReceipts.verify(context, id, deleted = true)
      val expected = receipt.result.data?.get("expected") as? JsonObject ?: return CalendarReceipts.uncertain("Campi attesi non salvati; serve verifica manuale", id)
      val values = ContentValues().apply { expected.forEach { (k,v) -> put(k, v.jsonPrimitive.content) } }
      if (values.size() == 0) return CalendarReceipts.uncertain("Impossibile provare la scrittura originale", id)
      val readback = CalendarReceipts.verify(context, id, values)
      return if (row.state == "PARTIAL" && readback.result?.outcome == ToolOutcome.COMPLETED) readback.copy(result = readback.result?.copy(outcome = ToolOutcome.PARTIAL)) else readback
    }
    return null
  }

  /** Revalidate on opening, without replaying the operation. UI calls in its coroutine. */
  suspend fun open(receipt: ToolReceipt): String {
    val row = db.operations().get(receipt.operationId) ?: return "Ricevuta non disponibile"
    val current = row.toReceipt()
    val entity = current.result.entities.firstOrNull()
    if (entity?.source == "spotify" && entity.type == "playlist") {
      val fresh = signedPlaylists.read(current.result, row.operationId)
      db.operations().finish(row.operationId, fresh.outcome.name, row.outputText.orEmpty(), ReceiptCodec.encode(fresh).toString(), System.currentTimeMillis())
      val opened = context.packageManager.getLaunchIntentForPackage(FluidifyClient.PACKAGE)?.let { launch(it) } ?: "Fluidify non installata"
      return fresh.error?.let { "${it.message}. $opened" } ?: opened
    }
    if (entity?.source == "android.calendar") {
      val readback = reconcile(row) ?: if (!row.isWrite) CalendarReceipts.verify(context, entity.id.toLongOrNull() ?: return "ID non valido") else return "Stato non verificabile"
      val fresh = if (row.state == "PARTIAL" && readback.result?.outcome == ToolOutcome.COMPLETED) readback.copy(result = readback.result?.copy(outcome = ToolOutcome.PARTIAL)) else readback
      db.operations().finish(row.operationId, fresh.result!!.outcome.name, fresh.text, ReceiptCodec.encode(fresh.result!!).toString(), System.currentTimeMillis())
      if (fresh.result!!.data?.get("deleted")?.jsonPrimitive?.booleanOrNull == true) return "Evento eliminato: stato riletto"
      val eventUri = Uri.parse("content://com.android.calendar/events/${entity.id.toLongOrNull() ?: return "ID non valido"}")
      return launch(Intent(Intent.ACTION_VIEW, eventUri))
    }
    if (receipt.result.sources.any { it.id == "android.clock" }) {
      // SHOW_TIMERS opens the source; it cannot certify the status of this particular request.
      return launch(Intent(AlarmClock.ACTION_SHOW_TIMERS))
    }
    if (entity?.source == "fluidify" || receipt.result.sources.any { it.id == "fluidify" }) {
      val observed = fluidify.nowPlaying()
      if (entity?.type == "playlist") {
        val item = fluidify.item(entity.id) ?: return "Playlist non disponibile per rilettura"
        val tracks = fluidify.children(item.mediaId) ?: return "Brani non disponibili per rilettura"
        val refreshed = receipt.result.copy(data = buildJsonObject {
          put("title", item.title); put("items", buildJsonArray { tracks.forEach { add(buildJsonObject { put("id", it.mediaId); put("title", it.title) }) } })
        }, freshness = ToolFreshness(observedAtMillis = System.currentTimeMillis()))
        db.operations().finish(row.operationId, row.state, row.outputText.orEmpty(), ReceiptCodec.encode(refreshed).toString(), System.currentTimeMillis())
      } else {
        if (observed == null) return "Fluidify non disponibile per rilettura"
        val refreshed = receipt.result.copy(data = buildJsonObject {
          put("playing", observed.playing); put("positionMillis", observed.positionMillis)
          observed.item?.let { put("title", it.title); put("mediaId", it.mediaId) }
        }, freshness = ToolFreshness(observedAtMillis = System.currentTimeMillis()))
        db.operations().finish(row.operationId, row.state, row.outputText.orEmpty(), ReceiptCodec.encode(refreshed).toString(), System.currentTimeMillis())
      }
      // Existing provider exposes playback, no supported playlist detail deep link. Open the app.
      return context.packageManager.getLaunchIntentForPackage(FluidifyClient.PACKAGE)?.let { launch(it) } ?: "Fluidify non installata"
    }
    return "Apertura non disponibile per questa fonte"
  }
  private fun launch(intent: Intent): String = runCatching {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "App sorgente aperta"
  }.getOrDefault("Nessuna app disponibile per aprire il risultato")
}
