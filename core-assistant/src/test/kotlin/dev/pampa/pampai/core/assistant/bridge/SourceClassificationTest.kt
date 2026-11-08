package dev.pampa.pampai.core.assistant.bridge

import dev.antigravity.fluidengine.ai.bridge.*
import dev.antigravity.fluidengine.ai.tools.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SourceClassificationTest {
  private val agreement = BridgeNegotiation(2, setOf(BridgeProtocol.CAP_TYPED_RESULTS, BridgeProtocol.CAP_OPERATION_ID))
  @Test fun otherAppDomainsKeepExistingTransportAndKnownSourceCannotMasqueradeAsAnotherDomain() = runBlocking {
    var runs = 0; var descriptions = 0; var confirmations = 0; var polls = 0
    val offered = sourceCatalog("weather").copy(tools = listOf(
      RemoteToolInfo("forecast", "data", "read", Schema.obj(emptyMap()), false, false, false),
      RemoteToolInfo("settings_change", "data", "action", Schema.obj(emptyMap()), true, false, true)))
    val catalog = SourceBridgePolicy.catalog(offered, BridgeNegotiation())
    val raw = object : BridgeCalls by SourceFixture() {
      override suspend fun describe(authority: String, name: String, args: JsonObject, language: String): Pair<String?, String?> {
        descriptions++; return "Original confirmation" to "Existing operation"
      }
      override suspend fun run(authority: String, name: String, args: JsonObject, confirmed: Boolean, requestId: String, language: String): RemoteResult {
        runs++; assertEquals(name == "settings_change", confirmed)
        if (confirmed) assertEquals("legacy-operation", requestId)
        return RemoteResult.Done("Original output", false, emptyList())
      }
      override suspend fun status(authority: String, jobId: String): RemoteResult {
        polls++; return RemoteResult.Status(BridgeProtocol.STATE_DONE, null, null, "Original job", false, emptyList())
      }
    }
    val adapter = SourceBridgeCalls(raw, catalog)
    val set = RemoteToolSet.of(adapter, catalog, object : RemoteToolHost<Unit> {
      override suspend fun confirm(ctx: Unit, toolName: String, text: ConfirmationText): ToolOutput? {
        confirmations++; assertEquals("Original confirmation", text.title); return null
      }
    })
    assertEquals("Original output", set.tools.single { it.name == "weather_forecast" }.run(buildJsonObject {}, Unit).text)
    set.tools.single { it.name == "weather_settings_change" }.run(buildJsonObject {}, Unit, ToolExecution("legacy-operation"))
    assertEquals(2, runs); assertEquals(1, confirmations); assertEquals(1, descriptions)
    assertTrue(adapter.status(catalog.authority, "existing-job") is RemoteResult.Status); assertEquals(1, polls)
    val masked = catalog.copy(packageName = "dev.pampa.fluidify")
    val denied = SourceBridgeCalls(raw, masked).run(masked.authority, "forecast", buildJsonObject {}, false, "masked", "it") as RemoteResult.Done
    assertEquals("TURN_READ_CONTEXT_REQUIRED", denied.result?.error?.code)
    assertEquals(2, runs)
  }

  @Test fun catalogAndLedgerShareTrustedNamesWithNoMetadataUpgradeOrDowngrade() {
    val mutations = mapOf("cv" to listOf("apri", "impostazione", "bacheca_segna_lette", "bacheca_presa_visione",
      "agenda_aggiungi_evento", "obiettivo_salva", "aggiorna_dati", "anno_scolastico", "voto_segna_visto",
      "evento_rimuovi", "obiettivo_rimuovi", "orario_modifica", "comunicazione_adesione"),
      "fluidify" to listOf("playlist_create", "playlist_append", "playlist_rename"))
    for ((domain, names) in mutations) {
      val offered = sourceCatalog(domain).copy(tools = (names + "unknown" + "registro_dati" + "history_records").map { name ->
        RemoteToolInfo(name, "data", name, Schema.obj(emptyMap()), name != "voto_segna_visto", false, name !in names)
      })
      val normalized = SourceBridgePolicy.catalog(offered, agreement)
      for (tool in normalized.tools) {
        assertEquals(tool.name in names, tool.action)
        assertEquals(tool.action, SourceBridgePolicy.actionFor("${domain}_${tool.name}", !tool.action))
        if (!tool.action) assertFalse(tool.needsConfirmation)
      }
      if (domain == "cv") assertFalse(normalized.tools.single { it.name == "voto_segna_visto" }.needsConfirmation)
    }
    assertTrue(SourceBridgePolicy.actionFor("ricorda", true))
    assertFalse(SourceBridgePolicy.actionFor("local_read", false))
    assertTrue(SourceBridgePolicy.actionFor("other_action", true))
  }

  @Test fun falselyReadMutationStillRequiresIdentityActionsAndProducerConfirmation() = runBlocking {
    var calls = 0; var confirmations = 0; var actions = false
    val offered = sourceCatalog("cv").copy(tools = listOf(RemoteToolInfo("evento_rimuovi", "data", "delete",
      Schema.obj(emptyMap()), false, false, false)))
    val catalog = SourceBridgePolicy.catalog(offered, agreement)
    val raw = object : BridgeCalls by SourceFixture() {
      override suspend fun run(authority: String, name: String, args: JsonObject, confirmed: Boolean, requestId: String, language: String): RemoteResult {
        calls++; assertTrue(confirmed); assertEquals("durable-id", requestId)
        return RemoteResult.Done("uncertain", false, emptyList(), ToolResultDetails(ToolOutcome.UNCERTAIN))
      }
    }
    val tool = RemoteToolSet.of(SourceBridgeCalls(raw, catalog), catalog, object : RemoteToolHost<Unit> {
      override fun actionsEnabled(ctx: Unit) = actions
      override suspend fun confirm(ctx: Unit, toolName: String, text: ConfirmationText): ToolOutput? {
        confirmations++; return null
      }
    }).tools.single()
    tool.run(buildJsonObject {}, Unit); assertEquals(0, calls)
    tool.run(buildJsonObject {}, Unit, ToolExecution("durable-id")); assertEquals(0, calls)
    actions = true
    assertEquals(ToolOutcome.UNCERTAIN, tool.run(buildJsonObject {}, Unit, ToolExecution("durable-id")).result?.outcome)
    assertEquals(1, calls); assertEquals(1, confirmations)
    assertTrue(SourceBridgePolicy.knownWrite(catalog, "evento_rimuovi", buildJsonObject {}))
  }

  @Test fun yearInspectionRemainsReadGatedWhileSelectionIsAMutation() {
    val catalog = SourceBridgePolicy.catalog(sourceCatalog("cv").copy(tools = listOf(RemoteToolInfo(
      "anno_scolastico", "data", "year", Schema.obj(emptyMap()), true, false, false))), agreement)
    assertTrue(SourceBridgePolicy.actionFor("cv_anno_scolastico", false))
    assertFalse(SourceBridgePolicy.knownWrite(catalog, "anno_scolastico", buildJsonObject {}))
    assertFalse(SourceBridgePolicy.knownWrite(catalog, "anno_scolastico", buildJsonObject { put("seleziona", " ") }))
    assertTrue(SourceBridgePolicy.knownWrite(catalog, "anno_scolastico", buildJsonObject { put("seleziona", "2026-2027") }))
  }
}
