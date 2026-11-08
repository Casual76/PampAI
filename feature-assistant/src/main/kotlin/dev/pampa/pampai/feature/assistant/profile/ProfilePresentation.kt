package dev.pampa.pampai.feature.assistant.profile

import dev.pampa.pampai.core.assistant.durable.*
import dev.pampa.pampai.core.assistant.research.*
import dev.pampa.pampai.core.assistant.runtime.*
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Labels are presentation only; canonical identities remain untouched on every mutation. */
internal fun fieldLabel(field: String): String = when (field) {
  "fluidify.history_records", "fluidify.history_aggregate" -> "Cronologia degli ascolti"
  "fluidify.history_track_counts" -> "Brani più ascoltati"
  "fluidify.history_artist_counts" -> "Artisti più ascoltati"
  "fluidify.history_coverage" -> "Copertura della cronologia"
  "fluidify.playlists", "fluidify.playlist_details", "fluidify.playlist_tracks" -> "Playlist"
  "cv.grades" -> "Voti"
  "cv.absences" -> "Assenze"
  "cv.agenda" -> "Agenda scolastica"
  "cv.lessons" -> "Lezioni"
  "cv.communications" -> "Comunicazioni"
  "cv.notes" -> "Note"
  "cv.materials" -> "Materiali"
  "cv.documents" -> "Documenti"
  "music.listening", "music.listening_minutes" -> "Ascolti"
  "music.artist", "music.favorite_artist", "music.top_artists" -> "Artisti ascoltati"
  "music.track", "music.top_tracks" -> "Brani ascoltati"
  "music.playlists" -> "Playlist"
  "school.grades", "school.average" -> "Risultati scolastici"
  "school.absences" -> "Assenze"
  "school.agenda" -> "Agenda scolastica"
  else -> "Informazione del profilo"
}

internal fun kindLabel(kind: String) = when (kind) {
  "USER_STATEMENT" -> "Detto da te"
  "OBSERVATION" -> "Dalle app"
  else -> "Deduzione · da verificare"
}
internal fun statusLabel(status: String) = when (status) {
  "ACTIVE" -> "Disponibile · controlla periodo e copertura"
  "SUPERSEDED" -> "Sostituito da una correzione"
  "FORGOTTEN" -> "Dimenticato"
  else -> "Da aggiornare · non utilizzabile"
}
internal fun dateLabel(value: Long?, zone: ZoneId = ZoneId.systemDefault()): String = value?.let {
  DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm").withZone(zone).format(Instant.ofEpochMilli(it))
} ?: "Non disponibile"

internal data class ProfileTimeZone(val zone: ZoneId, val label: String)
internal fun profileTimeZone(source: String): ProfileTimeZone = runCatching { ZoneId.of(source) }.fold(
  onSuccess = { ProfileTimeZone(it, it.id) },
  onFailure = { ZoneId.systemDefault().let { ProfileTimeZone(it, "${it.id} (fuso del dispositivo; fonte non disponibile)") } },
)

internal fun scheduleLabel(status: AreaScheduleStatus?, config: AreaConfiguration?): String {
  if (status == null) return if (config?.enabled == true) "Autorizzata · programmazione da verificare" else "Spenta · configurazione esplicita richiesta"
  return when (status.state) {
    ScheduleState.OFF -> "Aggiornamenti spenti"
    ScheduleState.RUNNING -> "Aggiornamento in corso"
    ScheduleState.ENQUEUED -> "In attesa di Android, rete e batteria" + (status.reason?.let { " · ${scheduleReason(it)}" } ?: "")
    ScheduleState.WAITING -> "Programmazione in verifica"
    ScheduleState.COMPLETED -> "Aggiornamento completato"
    ScheduleState.SUSPENDED -> "Sospesa · ${scheduleReason(status.reason)}"
  }
}
internal fun scheduleReason(reason: String?): String = when (reason) {
  "FOREGROUND_PRIORITY" -> "precedenza alla richiesta in chat"
  "COOLDOWN" -> "attesa prima di una nuova richiesta"
  "FUTURE_WINDOW", "NO_BUDGET_WINDOW", "NO_GLOBAL_WINDOW", "NO_BACKGROUND_WINDOW", "WINDOW_EXPIRED", "BACKGROUND_LIMITS_REQUIRED" -> "controlla le finestre e i limiti autorizzati"
  "CAP_EXCEEDED", "MONEY_CAP_EXCEEDED" -> "limite raggiunto"
  "UNTRUSTED_OR_UNBOUNDED_TARIFF" -> "tariffa e valuta non verificate"
  "UNBOUNDED_INPUT", "UNBOUNDED_OUTPUT", "BOUNDS_UNSUPPORTED", "MODEL_NOT_ALLOWED" -> "limiti del modello non verificati"
  "SOURCE_REVOKED", "LOCAL_REVOKED", "AUTHORIZATION_CHANGED" -> "fonte scollegata o autorizzazione cambiata"
  "EXCLUDED" -> "esclusione dall’apprendimento attiva"
  "SETUP_REQUIRED", "INVALID_PLAN" -> "configurazione esplicita richiesta"
  "RESULT_UNCERTAIN", "ELIGIBILITY_UNKNOWN", "INTERRUPTED" -> "esito da verificare; nessuna ripetizione automatica"
  "UNKNOWN_PRIOR_TOKENS" -> "utilizzo precedente sconosciuto; controlla i limiti"
  "INTERRUPTED_SAFE" -> "ripresa dal punto salvato dopo un’interruzione"
  "CLAIMED" -> "ripresa dopo il lavoro già assegnato"
  else -> "fonte, accesso o disponibilità da verificare"
}

internal fun moneyUnits(value: String): Long = value.replace(',', '.').toBigDecimal().movePointRight(6).longValueExact().also { require(it > 0) }

/** Structured source records are evidence, never raw JSON in normal product UI. */
internal fun readableValue(value: String, zone: ZoneId = ZoneId.systemDefault()): String {
  val parsed = runCatching { Json.parseToJsonElement(value) }.getOrNull()
  if (parsed !is JsonObject && parsed !is JsonArray) return (parsed as? JsonPrimitive)?.contentOrNull ?: value
  val labels = mapOf("title" to "Titolo", "name" to "Nome", "artist" to "Artista", "artistName" to "Artista",
    "trackName" to "Brano", "subject" to "Materia", "materia" to "Materia", "grade" to "Voto", "voto" to "Voto",
    "value" to "Valore", "description" to "Descrizione", "date" to "Data", "count" to "Conteggio", "durationMs" to "Durata in millisecondi")
  val rows = if (parsed is JsonArray) parsed.take(8) else listOf(parsed)
  val descriptions = rows.mapNotNull { row ->
    val record = row as? JsonObject ?: return@mapNotNull null
    musicFact(record, zone) ?: record.entries.mapNotNull { (key, v) ->
      labels[key]?.let { label -> (v as? JsonPrimitive)?.contentOrNull?.let { "$label: $it" } }
    }.take(8).joinToString(" · ").takeIf { it.isNotBlank() }
  }
  return descriptions.joinToString("\n").ifBlank { "Dato strutturato disponibile dalla fonte. Dettagli leggibili non disponibili." } +
    if (parsed is JsonArray && parsed.size > rows.size) "\nAnteprima di ${rows.size} elementi su ${parsed.size}." else ""
}

internal val schoolSections = linkedMapOf("grades" to "Voti", "absences" to "Assenze", "agenda" to "Agenda", "lessons" to "Lezioni", "communications" to "Comunicazioni", "notes" to "Note", "materials" to "Materiali", "documents" to "Documenti")
internal val musicSections = linkedMapOf("history_records" to "Cronologia ascolti", "history_aggregate" to "Riepilogo ascolti", "history_track_counts" to "Brani più ascoltati", "history_artist_counts" to "Artisti più ascoltati", "history_coverage" to "Copertura della cronologia", "playlists" to "Elenco playlist", "playlist_details" to "Dettagli di una playlist", "playlist_tracks" to "Brani di una playlist")

internal data class SourceDraft(
  val candidate: SourceCandidate,
  val account: String = "", val student: String = "", val year: String = "",
  val section: String = "", val from: String = "", val until: String = "",
  val refresh: Boolean = false,
  val playlistId: String = "",
) {
  fun read(): ResearchRead {
    require(account.isNotBlank() && account == candidate.account) { "Conferma l’account mostrato dalla fonte." }
    val school = candidate.domain == "cv"
    require(candidate.supported) { "Questa fonte non supporta il collegamento verificato." }
    require(section in if (school) schoolSections else musicSections) { "Scegli una sezione." }
    if (school) require(student.isNotBlank() && student == candidate.student && year.matches(Regex("[0-9]{4}-[0-9]{4}")) && year.takeLast(4).toInt() == year.take(4).toInt() + 1) { "Conferma studente e anno scolastico." }
    val selection = SourceSelection(candidate.packageName, candidate.authority, candidate.domain, account,
      student.takeIf { school }, year.takeIf { school }, section)
    val args = buildJsonObject {
      put("account", account)
      if (school) {
        put("studente", student); put("anno", year); put("sezione", section); put("limite", 20)
        put("aggiorna", refresh)
      } else {
        if (section != "history_aggregate" && section != "playlist_details") put("limit", 50)
        if (section.startsWith("history_") && section != "history_coverage") {
          val start = LocalDate.parse(from).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
          val end = LocalDate.parse(until).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
          require(start < end) { "La fine del periodo deve seguire l’inizio." }
          put("from", start.toString()); put("until", end.toString())
        }
        if (section.startsWith("playlist_")) {
          require(playlistId.matches(Regex("[A-Za-z0-9]{1,100}"))) { "Indica la playlist esatta da leggere." }
          put("playlistId", playlistId)
        }
      }
    }
    return ResearchRead(selection, if (school) "registro_dati" else section, args, refresh)
  }
}

/** Unconfigured defaults deliberately cannot authorize even a single autonomous request. */
internal data class AreaDraft(
  val area: String, val policy: ModelPolicy? = null, val reads: List<ResearchRead> = emptyList(),
  val requests: String = "0", val tokens: String = "0", val money: String = "0",
  val moneyDisabled: Boolean = false, val cadenceHours: String = "24",
  val wifiOnly: Boolean = true, val batteryNotLow: Boolean = true, val enabled: Boolean = false,
  val startDate: String = "", val endDate: String = "",
  val maxRecords: String = "100",
) {
  fun configuration(): AreaConfiguration {
    require(policy != null) { "Scegli esplicitamente un modello per quest’area." }
    require(reads.isNotEmpty() && reads.all { it.selection.area == area }) { "Collega e seleziona almeno una lettura per quest’area." }
    val records = maxRecords.toIntOrNull()
    require(records != null && records in 1..10000) { "Indica un limite di elementi tra 1 e 10000." }
    val requestCap = requests.toLongOrNull(); val tokenCap = tokens.toLongOrNull()
    require(requestCap != null && requestCap > 0 && tokenCap != null && tokenCap > 0) { "Indica limiti positivi per richieste e token." }
    val moneyCap = if (moneyDisabled) null else moneyUnits(money)
    require(moneyDisabled || moneyCap != null) { "Indica un tetto di spesa positivo." }
    val hours = cadenceHours.toLongOrNull()
    require(hours != null && hours in 1..8760) { "Indica una frequenza tra 1 e 8760 ore." }
    val start = LocalDate.parse(startDate).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val end = LocalDate.parse(endDate).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    require(start < end) { "La finestra deve avere inizio e fine espliciti." }
    return AreaConfiguration(area, enabled, true, hours * 3_600_000, wifiOnly, batteryNotLow,
      requestCap, tokenCap, moneyCap, start, end)
  }
}
