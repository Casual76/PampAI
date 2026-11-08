package dev.pampa.pampai.core.assistant.bridge

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.antigravity.fluidengine.ai.bridge.*
import dev.antigravity.fluidengine.ai.tools.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SignedPlaylistReader @Inject constructor(@ApplicationContext private val context: Context) {
  suspend fun read(original: ToolResultDetails, operationId: String): ToolResultDetails {
    val client = AiToolClient(context)
    val host = client.discover().singleOrNull { it.packageName == "dev.pampa.fluidify" || it.packageName == "dev.pampa.fluidify.debug" }
      ?: return playlistReadFailure(original, "source_not_ready")
    val catalog = client.catalog(host) ?: return playlistReadFailure(original, "source_not_ready")
    val gated = SourceBridgePolicy.catalog(catalog, client.negotiation(host.authority))
    if (gated.domain != "fluidify" || !setOf("capabilities", "playlist_details", "playlist_tracks").all { n -> gated.tools.any { it.name == n } })
      return playlistReadFailure(original, "typed_readback_unavailable")
    return readSignedPlaylist(SourceBridgeCalls(client, gated), gated.authority, original, operationId)
  }
}

internal fun playlistReadFailure(original: ToolResultDetails, code: String): ToolResultDetails = original.copy(
  outcome = if (original.outcome == ToolOutcome.PARTIAL) ToolOutcome.PARTIAL else ToolOutcome.UNCERTAIN,
  error = ToolError(code, "Rilettura playlist non verificata: $code", false),
  freshness = original.freshness?.copy(stale = true),
)

/** Read-only traversal: real cursors, 2,000 entries / 20 pages, snapshot bracket, no write replay. */
internal suspend fun readSignedPlaylist(calls: BridgeCalls, authority: String, original: ToolResultDetails, operationId: String): ToolResultDetails {
  val entity = original.entities.singleOrNull { it.source == "spotify" && it.type == "playlist" }
    ?: return playlistReadFailure(original, "entity_identity_missing")
  val account = original.sources.singleOrNull { it.id == "spotify" }?.account
    ?: return playlistReadFailure(original, "account_identity_missing")
  val bounded = (calls as? SourceBridgeCalls)?.forReceipt(original, operationId) ?: calls
  class ReadFailure(val code: String) : Exception()
  suspend fun read(name: String, cursor: String? = null): ToolResultDetails {
    val args = buildJsonObject { put("account", account); put("playlistId", entity.id); if (name == "playlist_tracks") put("limit", 100); cursor?.let { put("cursor", it) } }
    val response = bounded.run(authority, name, args, false, "$operationId:read:$name", "it")
    val result = (response as? RemoteResult.Done)?.result ?: throw ReadFailure("source_not_ready")
    result.error?.let { throw ReadFailure(it.code) }
    if (result.outcome !in setOf(ToolOutcome.COMPLETED, ToolOutcome.PARTIAL)) throw ReadFailure("readback_uncertain")
    if (result.sources.none { it.id == "spotify" && it.account == account }) throw ReadFailure("account_mismatch")
    return result
  }
  fun details(result: ToolResultDetails): JsonObject {
    if (result.entities.none { it.source == entity.source && it.type == entity.type && it.id == entity.id }) throw ReadFailure("entity_mismatch")
    val playlist = result.data?.get("playlist") as? JsonObject ?: throw ReadFailure("invalid_source_page")
    if ((playlist["id"] as? JsonPrimitive)?.content != entity.id) throw ReadFailure("entity_mismatch")
    return result.data!!
  }
  try {
    val first = read("playlist_details")
    val before = details(first)
    val snapshot = (before["snapshotId"] as? JsonPrimitive)?.contentOrNull ?: throw ReadFailure("snapshot_unavailable")
    val count = (before["trackCount"] as? JsonPrimitive)?.intOrNull ?: throw ReadFailure("count_unavailable")
    if (count !in 0..2000) throw ReadFailure("snapshot_limit")
    val items = mutableListOf<JsonElement>()
    val cursors = mutableSetOf<String>()
    var cursor: String? = null
    var last: ToolResultDetails
    var pages = 0
    do {
      if (++pages > 20) throw ReadFailure("snapshot_limit")
      last = read("playlist_tracks", cursor)
      val page = last.data?.get("items") as? JsonArray ?: throw ReadFailure("invalid_source_page")
      items.addAll(page)
      if (items.size > 2000) throw ReadFailure("snapshot_limit")
      val pagination = last.pagination ?: throw ReadFailure("pagination_unknown")
      cursor = pagination.nextCursor
      if (pagination.hasMore == null || pagination.hasMore != (cursor != null)) throw ReadFailure("pagination_unknown")
      if (cursor != null && (page.isEmpty() || !cursors.add(cursor))) throw ReadFailure("invalid_source_page")
    } while (cursor != null)
    if (items.size != count) throw ReadFailure("source_changed")
    val after = details(read("playlist_details"))
    if (after["snapshotId"] != before["snapshotId"] || after["trackCount"] != before["trackCount"]) throw ReadFailure("source_changed")
    return original.copy(
      // A current read cannot prove the original write. Keep its outcome and operation identity.
      data = JsonObject(original.data.orEmpty() + after + mapOf("items" to JsonArray(items))),
      freshness = last.freshness, pagination = last.pagination, coverage = last.coverage,
    )
  } catch (cancelled: CancellationException) { throw cancelled
  } catch (failure: Exception) { return playlistReadFailure(original, (failure as? ReadFailure)?.code ?: "source_unavailable") }
}
