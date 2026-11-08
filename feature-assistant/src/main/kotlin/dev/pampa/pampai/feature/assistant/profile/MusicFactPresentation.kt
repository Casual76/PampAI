package dev.pampa.pampai.feature.assistant.profile

import kotlinx.serialization.json.*
import java.time.ZoneId

/** The source's measured time and track length have different meanings. */
internal fun musicFact(record: JsonObject, zone: ZoneId = ZoneId.systemDefault()): String? {
  fun text(key: String, row: JsonObject = record) = (row[key] as? JsonPrimitive)?.contentOrNull?.take(300)
  fun number(key: String) = (record[key] as? JsonPrimitive)?.longOrNull
  val track = record["track"] as? JsonObject
  val history = track != null || "countedPlays" in record || "measuredLocalMs" in record ||
    ("artist" in record && "plays" in record)
  if (!history) return null
  val lines = mutableListOf<String>()
  track?.let { row ->
    text("name", row)?.let { lines += "Brano: $it" }
    val artist = text("artist", row)?.takeIf { it.isNotBlank() } ?: (row["artists"] as? JsonArray)
      ?.take(5)?.mapNotNull { (it as? JsonObject)?.let { artist -> text("name", artist) } }?.joinToString(", ")
    artist?.takeIf { it.isNotBlank() }?.let { lines += "Artista: $it" }
  }
  if (track == null) text("artist")?.let { lines += "Artista: $it" }
  listOf("startedAt" to "Inizio", "from" to "Dal", "until" to "Fino al").forEach { (key, label) ->
    number(key)?.let { lines += "$label: ${dateLabel(it, zone)} (${zone.id})" }
  }
  listOf("countedPlays" to "Ascolti conteggiati", "plays" to "Ascolti", "localCountedPlays" to "Ascolti locali",
    "localPlays" to "Ascolti locali", "importedOnlyPlays" to "Ascolti solo importati", "trackCount" to "Brani distinti",
    "artistCount" to "Artisti distinti").forEach { (key, label) -> number(key)?.let { lines += "$label: $it" } }
  if ("measuredLocalMs" in record) lines += "Tempo locale misurato: ${number("measuredLocalMs")?.let(::listeningTime) ?: "sconosciuto"}"
  if ("importedDurationMs" in record) lines += "Durata degli ascolti importati: ${number("importedDurationMs")?.let(::listeningTime) ?: "sconosciuta"}"
  val qualified = (record["qualified"] as? JsonPrimitive)?.booleanOrNull
  if (qualified == true) lines += "Ascolto qualificato per il conteggio (locale o Spotify)"
  else if (qualified == false && number("measuredLocalMs") != null && text("provenance") == "local_measurement")
    lines += "Soglia locale di ascolto non raggiunta"
  text("provenance")?.let { value ->
    lines += "Provenienza: " + when (value) {
      "local_measurement" -> "misura locale"
      "spotify_observation" -> "import Spotify"
      "local_measurement_and_spotify_observation" -> "misura locale e import Spotify riconciliati"
      else -> value
    }
  }
  // Import coverage is incomplete even when all records in this page were delivered.
  if ("importedDurationMs" in record && record["importedDurationMs"] == JsonNull ||
    "measuredLocalMs" in record && record["measuredLocalMs"] == JsonNull) {
    lines += "I minuti sconosciuti non sono inclusi nel tempo misurato."
  }
  return lines.joinToString(" · ").takeIf { it.isNotBlank() }
}

private fun listeningTime(milliseconds: Long): String {
  if (milliseconds < 0) return "sconosciuto"
  val seconds = milliseconds / 1_000
  return if (seconds < 60) "$seconds s" else "${seconds / 60} min ${seconds % 60} s"
}
