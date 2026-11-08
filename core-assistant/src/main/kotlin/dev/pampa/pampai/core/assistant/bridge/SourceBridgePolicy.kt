package dev.pampa.pampai.core.assistant.bridge

import dev.antigravity.fluidengine.ai.bridge.*
import dev.antigravity.fluidengine.ai.tools.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.currentCoroutineContext
import dev.pampa.pampai.core.assistant.research.TurnPrivacy

/** App capability gates, separate from transport negotiation. Never caches authenticated identity. */
internal object SourceBridgePolicy {
  private val writes = mapOf(
    "fluidify" to setOf("playlist_create", "playlist_append", "playlist_rename"),
    "cv" to setOf("apri", "impostazione", "bacheca_segna_lette", "bacheca_presa_visione",
      "agenda_aggiungi_evento", "obiettivo_salva", "aggiorna_dati", "voto_segna_visto", "evento_rimuovi",
      "obiettivo_rimuovi", "orario_modifica", "comunicazione_adesione", "anno_scolastico"),
  )
  private val confirmedWrites = mapOf(
    "fluidify" to setOf("playlist_create", "playlist_append", "playlist_rename"),
    "cv" to setOf("bacheca_presa_visione", "comunicazione_adesione", "agenda_aggiungi_evento",
      "obiettivo_salva", "anno_scolastico", "evento_rimuovi", "obiettivo_rimuovi", "orario_modifica"),
  )
  /** This profile-consent boundary belongs to the two connected knowledge sources. Other
   * signed app domains retain their existing tool transport instead of losing all reads/actions.
   * A known source package cannot evade the boundary by changing its advertised domain. */
  fun protectedSource(catalog: RemoteCatalog): Boolean = catalog.domain in writes || catalog.packageName in setOf(
    "dev.pampa.fluidify", "dev.pampa.fluidify.debug", "dev.antigravity.classevivaexpressive", "dev.antigravity.classevivaexpressive.debug")
  /** Trusted, argument-independent classification for ledger/staging/trace ownership.
   * The hybrid year tool is conservatively a write here; dispatch additionally checks seleziona.
   * Unknown source names remain reads subject to admission, regardless of offered metadata.
   */
  internal fun actionFor(toolName: String, fallback: Boolean): Boolean {
    val domain = writes.keys.firstOrNull { toolName.startsWith("${it}_") } ?: return fallback
    return toolName.removePrefix("${domain}_") in writes.getValue(domain)
  }
  fun knownWrite(catalog: RemoteCatalog, name: String, args: JsonObject): Boolean =
    actionFor("${catalog.domain}_$name", false) && catalog.tools.any { it.name == name } &&
      (catalog.domain != "cv" || name != "anno_scolastico" ||
        (args["seleziona"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true)

  /** Normalize before RemoteTool construction, so dispatch, visible contract and ledger agree. */
  private fun normalized(catalog: RemoteCatalog): List<RemoteToolInfo> = catalog.tools.map { tool ->
    if (catalog.domain !in writes) tool else {
      val action = actionFor("${catalog.domain}_${tool.name}", tool.action)
      tool.copy(action = action, needsConfirmation = action &&
        (tool.needsConfirmation || tool.name in confirmedWrites[catalog.domain].orEmpty()))
    }
  }
  fun typed(agreement: BridgeNegotiation) = agreement.protocol >= 2 && BridgeProtocol.CAP_TYPED_RESULTS in agreement.capabilities
  fun requiresTyped(domain: String, name: String) =
    (domain == "fluidify" && (name == "capabilities" || name.startsWith("history_") || name.startsWith("playlist"))) ||
      (domain == "cv" && name == "registro_dati")

  fun catalog(catalog: RemoteCatalog, agreement: BridgeNegotiation): RemoteCatalog = catalog.copy(
    tools = normalized(catalog).filter { tool ->
      !requiresTyped(catalog.domain, tool.name) || (typed(agreement) &&
        (!(tool.action || tool.needsConfirmation) || BridgeProtocol.CAP_OPERATION_ID in agreement.capabilities))
    },
  )
}

internal fun sourceFailure(code: String, message: String = code): RemoteResult.Done = RemoteResult.Done(
  message, false, emptyList(), ToolResultDetails(ToolOutcome.ERROR, error = ToolError(code, message, false)),
)

/** The signed host discovers the account; model arguments may only match it, never select it. */
internal class SourceBridgeCalls(private val delegate: BridgeCalls, private val catalog: RemoteCatalog,
  private val receipt: Triple<String, String, String>? = null) : BridgeCalls by delegate {
  private val writeJobs = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
  override suspend fun status(authority: String, jobId: String): RemoteResult {
    if (authority != catalog.authority) return sourceFailure("UNBOUND_SOURCE_JOB")
    if (!SourceBridgePolicy.protectedSource(catalog)) return delegate.status(authority, jobId)
    if (jobId !in writeJobs) return sourceFailure("UNBOUND_SOURCE_JOB")
    return delegate.status(authority, jobId)
  }
  internal fun forReceipt(original: ToolResultDetails, operationId: String): BridgeCalls {
    val entity = original.entities.singleOrNull { it.source == "spotify" && it.type == "playlist" }
    val account = original.sources.singleOrNull { it.id == "spotify" }?.account
    return if (entity != null && account != null && original.operation?.id == operationId)
      SourceBridgeCalls(delegate, catalog, Triple(account, entity.id, operationId)) else this
  }
  override suspend fun describe(authority: String, name: String, args: JsonObject, language: String): Pair<String?, String?>? {
    // Catalog confirmation labels cannot turn a private read into a pre-admission describe call.
    if (authority != catalog.authority) return null
    if (!SourceBridgePolicy.protectedSource(catalog)) return delegate.describe(authority, name, args, language)
    if (!SourceBridgePolicy.knownWrite(catalog, name, args)) return null
    return delegate.describe(authority, name, args, language)
  }
  override suspend fun run(authority: String, name: String, args: JsonObject, confirmed: Boolean, requestId: String, language: String): RemoteResult {
    if (authority != catalog.authority) return sourceFailure("SOURCE_AUTHORITY_MISMATCH")
    if (!SourceBridgePolicy.protectedSource(catalog)) return delegate.run(authority, name, args, confirmed, requestId, language)
    val boundedReceipt = receipt?.let { (account, entity, operation) ->
      name in setOf("playlist_details", "playlist_tracks") && args["account"] == JsonPrimitive(account) &&
        args["playlistId"] == JsonPrimitive(entity) && requestId == "$operation:read:$name" &&
        args.keys.all { it in setOf("account", "playlistId", "cursor", "limit") }
    } == true
    val write = SourceBridgePolicy.knownWrite(catalog, name, args)
    if (!write && !boundedReceipt) {
      val turn = currentCoroutineContext()[TurnPrivacy] ?: return sourceFailure("TURN_READ_CONTEXT_REQUIRED")
      return turn.read(catalog, name, args)
    }
    if (catalog.domain != "fluidify" || !SourceBridgePolicy.requiresTyped(catalog.domain, name)) {
      val result = delegate.run(authority, name, args, confirmed, requestId, language)
      if (write && result is RemoteResult.Job) writeJobs.add(result.jobId)
      return result
    }
    val discovery = delegate.run(authority, "capabilities", JsonObject(emptyMap()), false, "$requestId:capabilities", language)
    val proof = (discovery as? RemoteResult.Done)?.result
      ?: return sourceFailure("source_not_ready", "Fluidify non disponibile per scoprire l'account")
    if (proof.outcome != ToolOutcome.COMPLETED || proof.error != null) return discovery
    val account = (proof.data?.get("account") as? JsonPrimitive)?.contentOrNull
    val source = (proof.data?.get("source") as? JsonPrimitive)?.contentOrNull
    if (account.isNullOrBlank() || source != "spotify" || proof.sources.none { it.id == source && it.account == account })
      return sourceFailure("invalid_capabilities")
    if ((args["account"] as? JsonPrimitive)?.contentOrNull != account) return sourceFailure("account_mismatch")
    // Preserve the ledger identity exactly, including for writes. No retry on any outcome.
    val result = delegate.run(authority, name, args, confirmed, requestId, language)
    if (write && result is RemoteResult.Job) writeJobs.add(result.jobId)
    return result
  }
}
