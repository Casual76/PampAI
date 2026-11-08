package dev.pampa.pampai.core.assistant.durable

import dev.pampa.pampai.core.assistant.research.ResearchRead
import kotlinx.serialization.json.*

/** App-owned read identities. Aggregates and raw records can reveal the same forgotten fact. */
internal object ExclusionScopes {
  private val history = setOf("history_records", "history_aggregate", "history_track_counts", "history_artist_counts", "history_coverage").map { "fluidify.$it" }.toSet()
  private val playlists = setOf("playlists", "playlist_details", "playlist_tracks").map { "fluidify.$it" }.toSet()
  private val school = setOf("grades", "agenda", "lessons", "communications", "absences", "notes", "materials", "documents").map { "cv.$it" }.toSet()
  fun family(field: String): Set<String>? = when (field) {
    in history -> history
    in playlists -> playlists
    in school -> setOf(field)
    else -> null
  }
  fun field(read: ResearchRead): String = "${read.selection.domain}.${if (read.selection.domain == "cv") read.selection.area else read.tool}"
  fun read(query: String): ResearchRead? = runCatching {
    Json.decodeFromJsonElement<ResearchRead>(Json.parseToJsonElement(query).jsonObject.getValue("read"))
  }.getOrNull()
  fun sourceMatches(exclusion: ProfileExclusion, grant: String?, grants: List<SourceGrant>): Boolean {
    val excluded = exclusion.grantId ?: return true
    if (excluded == grant) return true
    if (grant == null) return false
    val a = grants.find { it.id == excluded } ?: return false
    val b = grants.find { it.id == grant } ?: return false
    // Area/tool aliases must not bypass a source/account-scoped exclusion.
    return a.app == b.app && a.authority == b.authority && a.account == b.account && a.student == b.student && a.year == b.year
  }
  fun blocksRead(e: ProfileExclusion, field: String?, grant: String?, grants: List<SourceGrant>): Boolean {
    if (e.unmapped) return true
    if (!sourceMatches(e, grant, grants)) return false
    if (e.field == null) return true
    val relatedFields = family(e.field) ?: return true // Unmapped legacy fields cannot safely narrow source reads.
    // A read has no record exclusion contract; block the entire related read family.
    return field == null || field in relatedFields || family(field) == null
  }
  fun blocksValue(e: ProfileExclusion, field: String, grant: String?, record: String?, grants: List<SourceGrant>): Boolean {
    if (e.unmapped) return true
    if (!sourceMatches(e, grant, grants)) return false
    val related = e.field == null || e.field == field || family(e.field)?.contains(field) == true
    if (!related) return false
    // For known source families an aggregate can reveal a forgotten record under another key.
    return e.recordKey == null || e.recordKey == record || e.field == null || e.field?.let { family(it) != null } == true
  }
}
