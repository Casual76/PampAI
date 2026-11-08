package dev.pampa.pampai.core.assistant.research

import dev.antigravity.fluidengine.ai.bridge.*
import dev.pampa.pampai.core.assistant.durable.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Terminal control signal: crosses provider/tool cancellation barriers without retry. */
class TurnPrivacyInvalidated : CancellationException("TURN_PRIVACY_INVALIDATED")

/** Immutable user intent and selected scopes; consumed dependencies belong only to this turn. */
class TurnPrivacy internal constructor(
  val question: String, private val epoch: Long, private val selected: List<ResearchRead>,
  private val profile: List<RetrievedProfile>, val promptBlock: String,
  private val foundation: DurableFoundation, private val sources: ResearchSources,
  private val clock: ResearchClock, private val currentReads: suspend () -> List<ResearchRead>,
) : AbstractCoroutineContextElement(Key) {
  companion object Key : CoroutineContext.Key<TurnPrivacy>
  private val consumed = java.util.Collections.synchronizedSet(linkedSetOf<ResearchRead>())
  private data class Fragment(val capture: CapturedSourceRead, val arguments: JsonObject)
  private val fragments = java.util.concurrent.ConcurrentHashMap<String, Fragment>()
  private fun fragmentKey(catalog: RemoteCatalog, args: JsonObject) =
    "${catalog.packageName}:${catalog.authority}:${catalog.domain}:" + JsonObject(args.toSortedMap()).toString()
  @Volatile private var invalidated = false
  /** Captured instruction also governs tools that read legacy memory/history. */
  val storedKnowledgeAllowed: Boolean = ResearchQuestionPlanner.storedKnowledgeAllowed(question)
  /** Fixed at capture: a public stream can never upgrade to private acquisition later. */
  val requiresPrivacyFence: Boolean = profile.isNotEmpty() ||
    (ResearchQuestionPlanner.sourceQuery(question)?.let { query ->
      selected.any { ResearchQuestionPlanner.pertinent(it, query.fields) }
    } == true)
  val privateTurn: Boolean get() = profile.isNotEmpty() || consumed.isNotEmpty()
  private fun dependencies(): List<ResearchRead> = (synchronized(consumed) { consumed.toList() } +
    profile.flatMap { it.evidence }.map { e ->
      researchJson.decodeFromJsonElement<ResearchRead>(researchJson.parseToJsonElement(e.provenance).jsonObject.getValue("read"))
    }).distinct()
  suspend fun check(refresh: Boolean = true) {
    if (invalidated) throw TurnPrivacyInvalidated()
    if (!privateTurn) return
    try {
      val reads = dependencies()
      // Check local revocation first; expiry alone must not prevent a signed renewal.
      foundation.withTurnPrivacy(epoch, reads, profile, clock.wall(), requireFreshProof = false) { }
      val current = currentReads().map(EvidenceAcquisition::canonicalRead).toSet()
      if (reads.any { EvidenceAcquisition.canonicalRead(it) !in current }) throw TurnPrivacyInvalidated()
      if (refresh) sources.renew(reads) // Network stays outside the commit transaction.
      foundation.withTurnPrivacy(epoch, reads, profile, clock.wall(), requireFreshProof = refresh) { }
    } catch (e: ResearchUnavailable) { invalidated = true; throw TurnPrivacyInvalidated() }
      catch (e: TurnPrivacyInvalidated) { invalidated = true; throw e }
      catch (e: CancellationException) { throw e }
      catch (_: Exception) { invalidated = true; throw TurnPrivacyInvalidated() }
  }
  suspend fun <T> commit(block: suspend () -> T): T {
    check()
    return if (privateTurn) foundation.withTurnPrivacy(epoch, dependencies(), profile, clock.wall(), block = block) else foundation.withLocalTransaction(block)
  }
  internal suspend fun read(catalog: RemoteCatalog, name: String, args: JsonObject): RemoteResult.Done {
    val query = ResearchQuestionPlanner.sourceQuery(question) ?: return denied("PERSONAL_SOURCE_INTENT_REQUIRED")
    if (!requiresPrivacyFence) return denied("TURN_HAS_NO_PRIVATE_SCOPE")
    // A fragment is authorized only by the exact continuation returned during this turn,
    // retaining its originating capture/lease. A model-supplied snapshot is not consent.
    val continuation = if (name == "result_fragment") fragments[fragmentKey(catalog, args)] else null
    val read = continuation?.capture?.read ?: selected.singleOrNull { r -> r.selection.packageName == catalog.packageName &&
      r.selection.authority == catalog.authority && r.selection.domain == catalog.domain &&
      r.tool == name && ResearchQuestionPlanner.pertinent(r, query.fields) &&
      ResearchReadPolicy.scope(name, args) == ResearchReadPolicy.scope(r.tool, r.arguments) }
      ?: return denied("SOURCE_SCOPE_REQUIRED_OR_AMBIGUOUS")
    if (!privateTurn && (foundation.policyEpoch() != epoch || !foundation.readAllowed(read) || read !in currentReads()))
      return denied("SOURCE_SCOPE_CHANGED_OR_EXCLUDED")
    consumed.add(read) // Mark private before the first suspension or remote access.
    check()
    return try {
      ResearchReadPolicy.check(read, name, args, continuation = continuation != null || args != read.arguments)
      val capture = continuation?.capture ?: sources.acquire(read)
      val result = sources.read(capture, name, args)
      check()
      val data = result.result?.data
      if (data?.string("nextTool") == "result_fragment") {
        val next = data["nextOffset"]?.jsonPrimitive?.intOrNull
        val end = data["end"]?.jsonPrimitive?.intOrNull
        val total = data["totalChars"]?.jsonPrimitive?.intOrNull
        requireResearch(data.string("encoding") == "concatenate_json_text_utf16" && next != null &&
          next == end && next > 0 && total != null && next < total &&
          listOf("snapshot", "hash", "queryHash").all { !data.string(it).isNullOrBlank() } &&
          result.result?.pagination?.nextCursor == null, "INVALID_FRAGMENT_CONTINUATION")
        val nextArgs = buildJsonObject {
          put("account", read.selection.account)
          for (key in listOf("snapshot", "hash", "queryHash")) put(key, data.getValue(key))
          put("offset", next)
        }
        fragments[fragmentKey(catalog, nextArgs)] = Fragment(capture, nextArgs)
      }
      continuation?.let { fragments.remove(fragmentKey(catalog, args), it) }
      result.copy(result = result.result?.let { details ->
        details.copy(data = details.data?.let { JsonObject(it - "sourceProof") })
      })
    } catch (e: ResearchUnavailable) {
      // No pending/poll fallback: source-proof transport must return a checked Done.
      check(false)
      denied(e.code)
    }
  }
  private fun denied(code: String) = dev.pampa.pampai.core.assistant.bridge.sourceFailure(code)
}
