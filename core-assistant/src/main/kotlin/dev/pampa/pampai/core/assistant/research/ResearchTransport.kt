package dev.pampa.pampai.core.assistant.research

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.antigravity.fluidengine.ai.bridge.*
import dev.antigravity.fluidengine.ai.tools.*
import kotlinx.serialization.json.*
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Test seam authenticates the channel, not a boolean supplied with model/source content. */
interface ResearchTransport {
  suspend fun hosts(): List<RemoteHost>
  suspend fun typed(host: RemoteHost): Boolean
  suspend fun call(host: RemoteHost, tool: String, args: JsonObject): RemoteResult.Done
}
internal object ResearchHostPolicy {
  val packages = mapOf("dev.pampa.fluidify" to "fluidify", "dev.pampa.fluidify.debug" to "fluidify",
    "dev.antigravity.classevivaexpressive" to "cv", "dev.antigravity.classevivaexpressive.debug" to "cv")
  fun domain(host: RemoteHost): String? {
    if (host.authority != "${host.packageName}.ai.tools") return null
    return packages[host.packageName]
  }
  /** Listing an installed known app grants no read permission; typed/trusted admission is separate. */
  fun installed(pm: PackageManager): List<RemoteHost> = packages.mapNotNull { (pkg, label) ->
    runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()?.let { RemoteHost(pkg, "$pkg.ai.tools", label) }
  }
}

@Singleton class SignedResearchTransport @Inject constructor(@ApplicationContext private val context: Context) : ResearchTransport {
  private val client = AiToolClient(context)
  private fun trusted(host: RemoteHost) {
    requireResearch(ResearchHostPolicy.domain(host) != null, "UNKNOWN_SOURCE_HOST")
    val info = context.packageManager.resolveContentProvider(host.authority, 0)
    requireResearch(context.packageManager.checkSignatures(host.packageName, context.packageName) == PackageManager.SIGNATURE_MATCH, "UNTRUSTED_SOURCE")
    requireResearch(info?.packageName == host.packageName && info.enabled && info.exported, "SOURCE_PROVIDER_UNAVAILABLE")
  }
  override suspend fun hosts() = (client.discover().filter { ResearchHostPolicy.domain(it) != null } +
    ResearchHostPolicy.installed(context.packageManager)).distinctBy { it.authority }
  override suspend fun typed(host: RemoteHost): Boolean {
    trusted(host)
    val catalog = client.catalog(host) ?: return false
    val agreement = client.negotiation(host.authority)
    return catalog.domain == ResearchHostPolicy.domain(host) && agreement.protocol >= 2 &&
      BridgeProtocol.CAP_TYPED_RESULTS in agreement.capabilities &&
      setOf("source_capabilities", "source_lease", "source_read", "source_validate").all { name -> catalog.tools.any { it.name == name } }
  }
  override suspend fun call(host: RemoteHost, tool: String, args: JsonObject): RemoteResult.Done {
    trusted(host)
    requireResearch(tool in setOf("source_capabilities", "source_lease", "source_read", "source_validate"), "SOURCE_TOOL_DENIED")
    val result = client.run(host.authority, tool, args, false, UUID.randomUUID().toString(), "it") as? RemoteResult.Done
      ?: throw ResearchUnavailable("SOURCE_UNAVAILABLE")
    val details = result.result ?: throw ResearchUnavailable("TYPED_DATA_REQUIRED")
    requireResearch(details.data != null && details.error == null &&
      details.outcome in setOf(ToolOutcome.COMPLETED, ToolOutcome.PARTIAL), details.error?.code ?: "SOURCE_UNAVAILABLE")
    return result
  }
}

/** Hardcoded inner-tool schemas. Catalog labels never authorize a dispatch. */
internal object ResearchReadPolicy {
  private val page = setOf("account", "cursor", "limit")
  private val music = mapOf("history_records" to page + setOf("from", "until"),
    "history_aggregate" to setOf("account", "from", "until"),
    "history_track_counts" to page + setOf("from", "until"), "history_artist_counts" to page + setOf("from", "until"),
    "history_coverage" to page, "playlists" to page, "playlist_details" to setOf("account", "playlistId"),
    "playlist_tracks" to page + "playlistId", "result_fragment" to setOf("account", "snapshot", "hash", "queryHash", "offset"))
  private val school = setOf("account", "studente", "anno", "sezione", "da", "a", "materia", "periodo", "cerca",
    "cursore", "limite", "dettaglio_offset", "dettaglio_hash", "aggiorna")
  val controls = setOf("cursor", "cursore", "dettaglio_offset", "dettaglio_hash", "aggiorna")
  fun scope(tool: String, args: JsonObject) = buildJsonObject { put("tool", tool); put("arguments", JsonObject(args.filterKeys { it !in controls })) }
  fun check(read: ResearchRead, tool: String = read.tool, args: JsonObject = read.arguments, continuation: Boolean = false) {
    val s = read.selection
    requireResearch(s.account.isNotBlank() && s.area.isNotBlank() && s.authority.isNotBlank() && s.packageName.isNotBlank(), "SOURCE_SCOPE_REQUIRED")
    val allowed = when (s.domain) { "fluidify" -> music[tool]; "cv" -> school.takeIf { tool == "registro_dati" }; else -> null }
    requireResearch(allowed != null && args.keys.all { it in allowed }, "SOURCE_TOOL_DENIED")
    args.forEach { (key, value) ->
      val primitive = value as? JsonPrimitive
      requireResearch(primitive != null && when (key) {
        "limit", "limite", "offset", "dettaglio_offset" -> !primitive.isString && primitive.intOrNull?.let { it >= 0 } == true
        "aggiorna" -> !primitive.isString && primitive.booleanOrNull != null
        else -> primitive.isString
      }, "INVALID_ARGUMENT_TYPE")
    }
    requireResearch(args.string("account") == s.account, "ACCOUNT_MISMATCH")
    requireResearch(tool != "result_fragment" || continuation, "UNBOUND_FRAGMENT")
    val limit = (args[if (s.domain == "cv") "limite" else "limit"] as? JsonPrimitive)?.intOrNull
    requireResearch(limit == null || limit in 1..100, "INVALID_LIMIT")
    if (s.domain == "cv") {
      requireResearch(!s.student.isNullOrBlank() && s.year?.matches(Regex("[0-9]{4}-[0-9]{4}")) == true &&
        s.year.takeLast(4).toInt() == s.year.take(4).toInt() + 1 && args.string("studente") == s.student &&
        args.string("anno") == s.year && args.string("sezione") == s.area &&
        s.area in setOf("grades", "agenda", "lessons", "communications", "absences", "notes", "materials", "documents"), "SCHOOL_SCOPE_MISMATCH")
      requireResearch(args["aggiorna"] != JsonPrimitive(true) || (read.allowTechnicalRefresh && !continuation && args.string("cursore") == null), "REFRESH_DENIED")
    }
    if (tool.startsWith("history_") && tool != "history_coverage") {
      val from = args.string("from")?.toLongOrNull(); val until = args.string("until")?.toLongOrNull()
      requireResearch(from != null && until != null && from < until, "PERIOD_REQUIRED")
    }
    if (tool.startsWith("playlist_") && tool != "playlists") requireResearch(args.string("playlistId")?.matches(Regex("[A-Za-z0-9]{1,100}")) == true, "PLAYLIST_REQUIRED")
  }
}
