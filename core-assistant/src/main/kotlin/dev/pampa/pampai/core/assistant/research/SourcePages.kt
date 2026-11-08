package dev.pampa.pampai.core.assistant.research

import dev.antigravity.fluidengine.ai.bridge.RemoteResult
import dev.antigravity.fluidengine.ai.tools.ToolResultDetails
import dev.pampa.pampai.core.assistant.durable.*
import kotlinx.serialization.json.*

internal data class ReadPage(val step: ResearchStep, val evidence: List<ResearchEvidence>)
internal object SourcePages {
  fun continuation(step: ResearchStep, c: CapturedSourceRead): Pair<String, JsonObject> {
    val state = step.cursor?.let { researchJson.parseToJsonElement(it).jsonObject } ?: buildJsonObject {}
    val fragment = state["fragment"] as? JsonObject
    if (fragment != null && state.string("lease") == c.lease) return "result_fragment" to buildJsonObject {
      put("account", c.read.selection.account); put("snapshot", fragment["snapshot"]!!); put("hash", fragment["hash"]!!)
      put("queryHash", fragment["queryHash"]!!); put("offset", step.fragmentOffset)
    }
    return c.read.tool to JsonObject(c.read.arguments + buildJsonObject {
      state.string("page")?.let { put(if (c.read.selection.domain == "cv") "cursore" else "cursor", it) }
      if (state["detail"] != null && c.read.selection.domain == "cv") {
        put("dettaglio_offset", state["detail"]!!); put("dettaglio_hash", state["detailHash"]!!)
      }
      if (state.isNotEmpty() && c.read.selection.domain == "cv") put("aggiorna", false)
    })
  }
  fun consume(step: ResearchStep, c: CapturedSourceRead, result: RemoteResult.Done): ReadPage {
    val details = result.result ?: throw ResearchUnavailable("TYPED_DATA_REQUIRED")
    val raw = details.data ?: throw ResearchUnavailable("TYPED_DATA_REQUIRED")
    val data = JsonObject(raw - "sourceProof")
    var page = data
    val previous = step.cursor?.let { researchJson.parseToJsonElement(it).jsonObject } ?: buildJsonObject {}
    if (data.string("encoding") != null) {
      requireResearch(data.string("encoding") == "concatenate_json_text_utf16", "FRAGMENT_ENCODING")
      val offset = data["offset"]?.jsonPrimitive?.intOrNull ?: -1
      val end = data["end"]?.jsonPrimitive?.intOrNull ?: -1
      val total = data["totalChars"]?.jsonPrimitive?.intOrNull ?: -1
      val fragment = data.string("fragment") ?: throw ResearchUnavailable("FRAGMENT_REQUIRED")
      val sameLease = previous.string("lease") == c.lease
      val before = if (sameLease) step.fragment.orEmpty() else ""
      requireResearch(total in 1..262144 && offset == before.length && end == offset + fragment.length && end in 1..total &&
        (end == total || fragment.lastOrNull()?.isHighSurrogate() != true) &&
        (before.isEmpty() || step.contentHash == data.string("hash")) &&
        (previous["fragment"] == null || !sameLease || (previous["fragment"]!!.jsonObject.string("snapshot") == data.string("snapshot") &&
          previous["fragment"]!!.jsonObject.string("queryHash") == data.string("queryHash"))), "FRAGMENT_MISMATCH")
      val text = before + fragment
      if (end < total) {
        requireResearch(data["nextOffset"] == JsonPrimitive(end) && data.string("nextTool") == "result_fragment" &&
          data["nextPageCursor"] == null && details.pagination?.nextCursor == null, "PREMATURE_CURSOR")
        val state = buildJsonObject { previous["page"]?.let { put("page", it) }; previous["evidence"]?.let { put("evidence", it) }; put("acquisition", acquisition(c)); previous["restarts"]?.let { put("restarts", it) }; put("lease", c.lease); put("fragment", data) }
        return ReadPage(step.copy(cursor = state.toString(), fragment = text, fragmentOffset = end,
          contentHash = data.string("hash"), progress = step.progress + 1, outcome = "PARTIAL"), emptyList())
      }
      requireResearch(data["truncated"] == JsonPrimitive(false) && digest(text) == data.string("hash"), "FRAGMENT_HASH")
      page = researchJson.parseToJsonElement(text) as? JsonObject ?: throw ResearchUnavailable("PAGE_JSON_REQUIRED")
    }
    val next = data.string("nextPageCursor") ?: details.pagination?.nextCursor
    if (c.read.selection.domain == "cv") requireResearch(page.string("account") == c.read.selection.account &&
      page.string("student") == c.read.selection.student && page.string("year") == c.read.selection.year &&
      page.string("section") == c.read.selection.area, "SCHOOL_RESULT_SCOPE_MISMATCH")
    val detail = if (c.read.selection.domain == "cv" && details.pagination?.hasMore == true && next == null)
      Regex("(?m)^Pagina suddivisa: ripeti stessi filtri/cursore/limite, aggiorna=false, dettaglio_offset=([0-9]+), dettaglio_hash=([a-f0-9]{64})\\.").find(result.text) else null
    requireResearch(details.pagination?.hasMore != true || next != null || detail != null, "MISSING_CONTINUATION")
    val state = buildJsonObject {
      previous["restarts"]?.let { put("restarts", it) }
      (next ?: previous.string("page").takeIf { detail != null })?.let { put("page", it) }
      if (detail != null) { put("detail", detail.groupValues[1].toInt()); put("detailHash", detail.groupValues[2]) }
    }
    val rows = (page["records"] ?: page["items"]) as? JsonArray
    val entries = rows?.mapIndexed { index, row ->
      val id = (row as? JsonObject)?.string("id") ?: (row as? JsonObject)?.string("eventId") ?: digest(row.toString())
      id to row
    }?.toMutableList() ?: mutableListOf()
    page["aggregate"]?.let { entries += "aggregate:${digest(ResearchReadPolicy.scope(c.read.tool, c.read.arguments).toString())}" to it }
    if (rows == null && page["aggregate"] == null) entries += "aggregate:${digest(ResearchReadPolicy.scope(c.read.tool, c.read.arguments).toString())}" to page
    val evidence = entries.map { (key, value) -> evidence(step, c.read, key, value, details) }
    requireResearch(evidence.size <= 500, "PAGE_RECORD_LIMIT")
    val ids = (previous["evidence"] as? JsonArray).orEmpty() + evidence.map { JsonPrimitive(it.id) }
    val checkpoint = JsonObject(state + buildJsonObject {
      put("evidence", JsonArray(ids.distinct()))
      put("acquisition", acquisition(c))
    })
    requireResearch(checkpoint.toString().length <= 8192, "CHECKPOINT_RECORD_LIMIT")
    return ReadPage(step.copy(cursor = checkpoint.toString(), fragment = null, fragmentOffset = 0,
      contentHash = null, progress = step.progress + 1, outcome = if (next == null && detail == null) "COMPLETE" else "PARTIAL"), evidence)
  }
  private fun acquisition(c: CapturedSourceRead) = buildJsonObject {
    put("read", researchJson.encodeToJsonElement(c.read)); put("grant", c.read.grantId)
    put("generation", c.generation); put("lease", c.lease)
  }
  private fun evidence(step: ResearchStep, read: ResearchRead, key: String, value: JsonElement, details: ToolResultDetails): ResearchEvidence {
    val text = value.toString(); requireResearch(text.length <= 16384, "RECORD_TOO_LARGE")
    val hash = digest(text)
    val period = listOf(read.arguments.string("from"), read.arguments.string("until"), read.selection.year, hash).joinToString(":")
    val provenance = buildJsonObject { put("read", researchJson.encodeToJsonElement(EvidenceAcquisition.canonicalRead(read))); put("archiveComplete", false)
      put("durationKnown", false); put("coverageComplete", details.coverage?.complete?.let(::JsonPrimitive) ?: JsonNull) }.toString()
    requireResearch(provenance.length <= 8192, "PROVENANCE_TOO_LARGE")
    return ResearchEvidence(digest("${read.grantId}:$key:$period"), step.jobId, read.grantId, key, period,
      "${read.selection.domain}.${if (read.selection.domain == "cv") read.selection.area else read.tool}", text, null,
      read.arguments.string("from")?.toLongOrNull(), read.arguments.string("until")?.toLongOrNull(), if (read.selection.domain == "fluidify") "UTC" else "UNKNOWN",
      details.freshness?.observedAtMillis, details.freshness?.syncedAtMillis, if (details.coverage?.complete == true) "COMPLETE" else "PARTIAL_OR_UNKNOWN",
      details.freshness?.stale?.let { if (it) "STALE" else "SOURCE_REPORTED" } ?: "UNKNOWN", provenance, hash)
  }
}
