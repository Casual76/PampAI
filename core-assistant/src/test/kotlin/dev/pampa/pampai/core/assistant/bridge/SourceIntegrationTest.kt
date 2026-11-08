package dev.pampa.pampai.core.assistant.bridge

import dev.antigravity.fluidengine.ai.bridge.*
import dev.antigravity.fluidengine.ai.tools.*
import dev.pampa.pampai.core.assistant.receipts.ReceiptCodec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal class SourceFixture : BridgeCalls {
  var account = "alice"
  var error: String? = null
  var secondPageError = false
  var changed = false
  var detailReads = 0
  val invocations = mutableListOf<Triple<String, JsonObject, String>>()
  override suspend fun describe(authority: String, name: String, args: JsonObject, language: String): Pair<String?, String?>? = null
  override suspend fun status(authority: String, jobId: String): RemoteResult = error("no jobs")
  override suspend fun cancel(authority: String, jobId: String) = error("no cancellation")
  override fun readPart(part: RemotePart): ByteArray? = null
  override suspend fun run(authority: String, name: String, args: JsonObject, confirmed: Boolean, requestId: String, language: String): RemoteResult {
    invocations += Triple(name, args, requestId)
    if (error != null) return sourceFailure(error!!)
    if (secondPageError && args.containsKey("cursor")) return sourceFailure("source_unavailable")
    val data = when (name) {
      "capabilities" -> buildJsonObject { put("account", account); put("source", "spotify") }
      "playlist_details" -> buildJsonObject {
        put("playlist", buildJsonObject { put("id", "spotifyID"); put("name", "Real playlist") })
        put("snapshotId", if (changed && detailReads++ > 0) "changed" else "stable"); put("trackCount", 2)
      }
      else -> buildJsonObject { put("items", buildJsonArray { add(buildJsonObject { put("position", if (args.containsKey("cursor")) 1 else 0); put("item", JsonNull) }) }) }
    }
    val page = if (name == "playlist_tracks") ToolPagination(if (args.containsKey("cursor")) null else "real-cursor", !args.containsKey("cursor"), 2) else null
    return RemoteResult.Done("source", false, emptyList(), ToolResultDetails(
      if (page?.hasMore == true) ToolOutcome.PARTIAL else ToolOutcome.COMPLETED, data,
      entities = if (name == "playlist_details") listOf(ToolEntityRef("spotify", "playlist", "spotifyID")) else emptyList(),
      sources = listOf(ToolSourceRef("spotify", account = account)), coverage = ToolCoverage(false),
      freshness = ToolFreshness(observedAtMillis = 100), pagination = page,
    ))
  }
}

internal fun sourceCatalog(domain: String = "fluidify") = RemoteCatalog(1, "signed.host", "source.app", "source.app", domain,
  "Source", "1", "Snapshot reads, explicit account/year, unknown archive coverage", emptyList(), emptyList(),
  listOf(RemoteGroupInfo("data", "data", "data", null, null, true, false)),
  listOf("legacy", "capabilities", "history_records", "playlist_details", "playlist_tracks", "playlist_create", "registro_dati").map {
    RemoteToolInfo(it, "data", it, Schema.obj(emptyMap()), false, false, it == "playlist_create")
  })

internal fun originalPlaylist() = ToolResultDetails(ToolOutcome.UNCERTAIN,
  data = buildJsonObject { put("originalEvidence", "retained") },
  entities = listOf(ToolEntityRef("spotify", "playlist", "spotifyID", "spotify:playlist:spotifyID")),
  operation = ToolOperationRef("persisted", "fluidify"), sources = listOf(ToolSourceRef("spotify", account = "alice")))

class SourceIntegrationTest {
  @Test fun allSixProducerMutationsPreserveTheirRealConfirmationContractAndIdentity() = runBlocking {
    val mutations = listOf(
      Triple("anno_scolastico", buildJsonObject { put("seleziona", "2025-2026") }, true),
      Triple("voto_segna_visto", buildJsonObject { put("id", "grade-1") }, false),
      Triple("evento_rimuovi", buildJsonObject { put("id", "event-1") }, true),
      Triple("obiettivo_rimuovi", buildJsonObject { put("materia", "Matematica") }, true),
      Triple("orario_modifica", buildJsonObject { put("giorno", "lunedi"); put("ora", "08:00"); put("materia", "Matematica") }, true),
      Triple("comunicazione_adesione", buildJsonObject { put("id", "communication-1") }, true),
    )
    for ((name, args, confirm) in mutations) {
      var calls = 0; var descriptions = 0; var confirmations = 0; var allowed = false; var actions = true
      val id = "existing-ledger:$name"
      val raw = object : BridgeCalls by SourceFixture() {
        override suspend fun describe(authority: String, name: String, args: JsonObject, language: String): Pair<String?, String?> {
          descriptions++; return "Source-owned action" to "Exact requested operation"
        }
        override suspend fun run(authority: String, name: String, arguments: JsonObject, confirmed: Boolean, requestId: String, language: String): RemoteResult {
          calls++; assertEquals(args, arguments); assertEquals(confirm, confirmed); assertEquals(id, requestId)
          return RemoteResult.Done("accepted", false, emptyList(), ToolResultDetails(ToolOutcome.ACCEPTED))
        }
      }
      val parameters = Schema.obj(args.keys.associateWith { Schema.str("producer argument") },
        required = if (name == "anno_scolastico") emptyList() else args.keys.toList())
      val catalog = sourceCatalog("cv").copy(tools = listOf(RemoteToolInfo(name, "data", name, parameters, confirm, false, true)))
      val adapter = SourceBridgeCalls(raw, catalog)
      val host = object : RemoteToolHost<Unit> {
        override fun actionsEnabled(ctx: Unit) = actions
        override suspend fun confirm(ctx: Unit, toolName: String, text: ConfirmationText): ToolOutput? {
          confirmations++; assertEquals("Source-owned action", text.title)
          return if (allowed) null else ToolOutput.error("refused")
        }
      }
      val tool = RemoteToolSet.of(adapter, catalog, host).tools.single()
      if (confirm) {
        tool.run(args, Unit, ToolExecution(id)); assertEquals(0, calls)
      }
      allowed = true
      val output = tool.run(args, Unit, ToolExecution(id))
      assertEquals(1, calls); assertEquals(id, output.result?.operation?.id)
      assertEquals(if (confirm) 2 else 0, confirmations)
      assertEquals(if (confirm) 2 else 0, descriptions)
      actions = false; tool.run(args, Unit, ToolExecution(id)); assertEquals(1, calls)
      if (name == "anno_scolastico") {
        assertNull(adapter.describe(catalog.authority, name, buildJsonObject {}, "it"))
        val readOnly = adapter.run(catalog.authority, name, buildJsonObject {}, true, id, "it") as RemoteResult.Done
        assertEquals("TURN_READ_CONTEXT_REQUIRED", readOnly.result?.error?.code)
        assertEquals(1, calls)
      }
    }
  }
  @Test fun knownSchoolWriteRetainsConfirmationIdentityAndBoundJobsWithoutReadBypass() = runBlocking {
    var describes = 0; var confirmations = 0; var writes = 0; var polls = 0; var pending = false
    val raw = object : BridgeCalls by SourceFixture() {
      override suspend fun describe(authority: String, name: String, args: JsonObject, language: String): Pair<String?, String?> {
        describes++; return "Confermare l'evento?" to "Evento richiesto"
      }
      override suspend fun run(authority: String, name: String, args: JsonObject, confirmed: Boolean, requestId: String, language: String): RemoteResult {
        assertTrue(confirmed); assertEquals("school-ledger", requestId); writes++
        return if (pending) RemoteResult.Job("owned-school-write") else RemoteResult.Done("accepted", false, emptyList())
      }
      override suspend fun status(authority: String, jobId: String): RemoteResult {
        polls++; return RemoteResult.Status(BridgeProtocol.STATE_DONE, null, null, "accepted", false, emptyList())
      }
    }
    val catalog = sourceCatalog("cv").copy(tools = listOf(
      RemoteToolInfo("agenda_aggiungi_evento", "data", "write", Schema.obj(emptyMap()), true, true, true),
      RemoteToolInfo("voti", "data", "mislabelled read", Schema.obj(emptyMap()), false, false, true)))
    val calls = SourceBridgeCalls(raw, catalog)
    var allowed = true; var enabled = true
    val host = object : RemoteToolHost<Unit> {
      override fun actionsEnabled(ctx: Unit) = enabled
      override suspend fun confirm(ctx: Unit, toolName: String, text: ConfirmationText): ToolOutput? {
        confirmations++; assertEquals("Confermare l'evento?", text.title)
        return if (allowed) null else ToolOutput.error("refused")
      }
    }
    val set = RemoteToolSet.of(calls, catalog, host)
    val write = set.tools.single { it.name == "cv_agenda_aggiungi_evento" }
    allowed = false; write.run(buildJsonObject {}, Unit, ToolExecution("school-ledger"))
    assertEquals(0, writes)
    allowed = true; write.run(buildJsonObject {}, Unit, ToolExecution("school-ledger"))
    assertEquals(1, writes); assertEquals(2, describes); assertEquals(2, confirmations)
    enabled = false; write.run(buildJsonObject {}, Unit, ToolExecution("school-ledger"))
    assertEquals(1, writes); assertEquals(2, confirmations)
    val read = set.tools.single { it.name == "cv_voti" }
    enabled = true
    assertEquals("TURN_READ_CONTEXT_REQUIRED", read.run(buildJsonObject {}, Unit, ToolExecution("read")).result?.error?.code)
    assertEquals(1, writes)
    pending = true
    calls.run(catalog.authority, "agenda_aggiungi_evento", buildJsonObject {}, true, "school-ledger", "it")
    assertTrue(calls.status(catalog.authority, "owned-school-write") is RemoteResult.Status)
    assertEquals(1, polls)
    assertEquals("UNBOUND_SOURCE_JOB", (calls.status(catalog.authority, "foreign-read") as RemoteResult.Done).result?.error?.code)
    assertEquals(1, polls)
  }
  @Test fun legacyCatalogKeepsLegacyButGatesTypedFunctionsAndWriteIdentity() {
    val catalog = sourceCatalog()
    assertEquals(listOf("legacy", "registro_dati"), SourceBridgePolicy.catalog(catalog, BridgeNegotiation()).tools.map { it.name })
    val typed = BridgeNegotiation(2, setOf(BridgeProtocol.CAP_TYPED_RESULTS))
    assertTrue(SourceBridgePolicy.catalog(catalog, typed).tools.any { it.name == "history_records" })
    assertFalse(SourceBridgePolicy.catalog(catalog, typed).tools.any { it.name == "playlist_create" })
    assertFalse(SourceBridgePolicy.catalog(sourceCatalog("cv"), BridgeNegotiation()).tools.any { it.name == "registro_dati" })
  }

  @Test fun actualAdapterDiscoversAccountAndPreservesParentIdentityWithoutGlobalAccountState() = runBlocking {
    val fake = SourceFixture()
    val catalog = sourceCatalog()
    val host = object : RemoteToolHost<Unit> { override suspend fun confirm(ctx: Unit, toolName: String, text: ConfirmationText): ToolOutput? = null }
    val tool = RemoteToolSet.of(SourceBridgeCalls(fake, catalog), catalog, host).tools.single { it.name == "fluidify_playlist_create" }
    tool.run(buildJsonObject { put("account", "alice") }, Unit, ToolExecution("ledger-parent"))
    assertEquals("ledger-parent", fake.invocations.last().third)
    assertEquals("capabilities", fake.invocations.first().first)
    assertTrue(fake.invocations.first().second.isEmpty())
    fake.account = "bob"
    val rejected = tool.run(buildJsonObject { put("account", "alice") }, Unit, ToolExecution("ledger-next"))
    assertEquals("account_mismatch", rejected.result?.error?.code)
    assertEquals(1, fake.invocations.count { it.first == "playlist_create" })
    fake.error = "authentication_required"
    assertEquals("authentication_required", tool.run(buildJsonObject { put("account", "bob") }, Unit, ToolExecution("third")).result?.error?.code)
  }

  @Test fun spotifyEntityUsesSignedReadsAndRealCursorWithoutUpgradingOriginalWrite() = runBlocking {
    val fake = SourceFixture()
    val result = readSignedPlaylist(SourceBridgeCalls(fake, sourceCatalog()), "signed.host", originalPlaylist(), "persisted")
    assertEquals(ToolOutcome.UNCERTAIN, result.outcome)
    assertNull(result.error)
    assertEquals(2, result.data!!["items"]!!.jsonArray.size)
    assertEquals("real-cursor", fake.invocations.first { it.second.containsKey("cursor") }.second["cursor"]!!.jsonPrimitive.content)
    assertEquals(false, result.coverage?.complete)
    assertEquals(originalPlaylist().operation, result.operation)
    assertTrue(fake.invocations.none { it.first in setOf("playlist_create", "playlist_append", "playlist_rename") })
  }

  @Test fun laterPageFailureAndSnapshotChangeNeverBecomeEmptySuccess() = runBlocking {
    for (fake in listOf(SourceFixture().apply { secondPageError = true }, SourceFixture().apply { changed = true })) {
      val original = originalPlaylist().copy(outcome = ToolOutcome.PARTIAL)
      val result = readSignedPlaylist(SourceBridgeCalls(fake, sourceCatalog()), "signed.host", original, "persisted")
      assertEquals(ToolOutcome.PARTIAL, result.outcome)
      assertNotNull(result.error)
      assertEquals(original.data, result.data)
    }
  }

  @Test fun missingAccountOrEntityCannotBeReconciled() = runBlocking {
    val fake = SourceFixture()
    val result = readSignedPlaylist(fake, "host", originalPlaylist().copy(sources = emptyList()), "id")
    assertEquals("account_identity_missing", result.error?.code)
    assertTrue(fake.invocations.isEmpty())
  }

  @Test fun schoolScopeAndFullEvidenceSurviveReceiptCodec() {
    val evidence = ToolResultDetails(ToolOutcome.PARTIAL,
      data = buildJsonObject { put("account", "student"); put("year", "2025"); put("records", buildJsonArray { add(buildJsonObject { put("sourceId", "record"); put("body", "full source body") }) }) },
      entities = listOf(ToolEntityRef("classeviva", "event", "source-event")),
      sources = listOf(ToolSourceRef("classeviva", account = "student")), coverage = ToolCoverage(null),
      freshness = ToolFreshness(100, 90, true), pagination = ToolPagination("actual-next", true, 140),
      error = ToolError("refresh_failed", "Readback unavailable"))
    assertEquals(evidence, ReceiptCodec.decode(ReceiptCodec.encode(evidence)))
  }
}
