package dev.pampa.pampai.core.assistant.receipts

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import dev.antigravity.fluidengine.ai.tools.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

object CalendarReceipts {
  fun uncertain(message: String, id: Long? = null) = ToolOutput(message, result = ToolResultDetails(
    ToolOutcome.UNCERTAIN, entities = id?.let { listOf(entity(it)) }.orEmpty(),
    sources = listOf(ToolSourceRef("android.calendar")), error = ToolError("calendar_unverified", message)))
  private fun entity(id: Long) = ToolEntityRef("android.calendar", "event", id.toString(), ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id).toString())

  /** Only an actual cursor establishes absence; null cursor/exception never means deleted. */
  suspend fun verify(context: Context, id: Long, expected: ContentValues = ContentValues(), deleted: Boolean = false): ToolOutput = withContext(Dispatchers.IO) {
    try {
      val fields = (listOf(CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND, CalendarContract.Events.EVENT_LOCATION, CalendarContract.Events.DELETED) + expected.keySet()).distinct()
      val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
      val cursor = context.contentResolver.query(uri, fields.toTypedArray(), null, null, null)
        ?: return@withContext uncertain("Calendario non disponibile per rilettura", id)
      cursor.use { c ->
        val present = c.moveToFirst()
        val removed = !present || c.getInt(fields.indexOf(CalendarContract.Events.DELETED)) == 1
        val matches = if (deleted) removed else !removed && expected.keySet().all { field -> c.getString(fields.indexOf(field)) == expected.getAsString(field) }
        val snapshot = buildJsonObject {
          put("deleted", removed)
          if (present) fields.forEachIndexed { i, key -> if (!c.isNull(i)) put(key, c.getString(i)) }
          put("expected", buildJsonObject { expected.keySet().forEach { put(it, expected.getAsString(it)) } })
        }
        ToolOutput(if (matches) "Calendario: stato riletto per evento #$id" else "Calendario: stato richiesto non confermato per evento #$id",
          result = ToolResultDetails(if (matches) ToolOutcome.COMPLETED else ToolOutcome.UNCERTAIN,
            data = snapshot, entities = listOf(entity(id)), sources = listOf(ToolSourceRef("android.calendar")),
            freshness = ToolFreshness(observedAtMillis = System.currentTimeMillis()),
            error = if (matches) null else ToolError("readback_mismatch", "Stato richiesto non confermato")))
      }
    } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; uncertain("Rilettura calendario non riuscita", id) }
  }
}
