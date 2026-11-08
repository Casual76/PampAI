package dev.pampa.pampai.core.assistant.research

import android.os.SystemClock
import dev.antigravity.fluidengine.ai.bridge.RemoteHost
import dev.antigravity.fluidengine.ai.bridge.RemoteResult
import dev.pampa.pampai.core.assistant.durable.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

interface ResearchClock { fun wall(): Long; fun tick(): Long }
@Singleton class SystemResearchClock @Inject constructor() : ResearchClock {
  override fun wall() = System.currentTimeMillis()
  override fun tick() = SystemClock.elapsedRealtime()
}
internal data class CheckedReceipt(val grantId: String, val selection: SourceSelection, val generation: String,
  val scope: JsonObject, val expires: Long, val deadline: Long, val epoch: Long)

/** Only ResearchSources' signed-response verifier can publish receipts; predicate never performs I/O. */
@Singleton class VerifiedSourceReceiptCache @Inject constructor(private val clock: ResearchClock) : SourceProofValidator {
  private val entries = ConcurrentHashMap<String, CheckedReceipt>()
  private val epochs = ConcurrentHashMap<String, Long>()
  internal fun epoch(id: String) = epochs[id] ?: 0L
  @Synchronized internal fun invalidate(id: String) {
    epochs[id] = Math.addExact(epoch(id), 1); entries.entries.removeIf { it.value.grantId == id }
  }
  @Synchronized internal fun publish(key: String, receipt: CheckedReceipt) {
    requireResearch(receipt.epoch == epoch(receipt.grantId), "SOURCE_REVOKED")
    requireResearch(entries.size < 1024 || entries.containsKey(key), "RECEIPT_CACHE_FULL")
    entries[key] = receipt
  }
  internal fun current(key: String): CheckedReceipt? = entries[key]?.takeIf { it.deadline > clock.tick() && it.epoch == epoch(it.grantId) }
  override fun valid(grant: SourceGrant, now: Long): Boolean = entries.values.any { r ->
    r.grantId == grant.id && r.generation == grant.generationProof && r.expires >= (grant.proofExpiresAt ?: Long.MAX_VALUE) &&
      r.expires > now && r.deadline > clock.tick() && r.epoch == epoch(grant.id) && r.selection.let {
        it.packageName == grant.app && it.authority == grant.authority && it.account == grant.account &&
          it.student == grant.student && it.year == grant.year && it.area == grant.area
      }
  }
}
internal data class CapturedSourceRead(val read: ResearchRead, val host: RemoteHost, val capability: String,
  val lease: String, val generation: String, val key: String, val epoch: Long, val capDeadline: Long)

@Singleton class ResearchSources @Inject constructor(private val transport: ResearchTransport,
  private val foundation: DurableFoundation, private val receipts: VerifiedSourceReceiptCache, private val clock: ResearchClock,
  private val connections: ResearchConnections) {
  private val lock = Mutex()
  private val captured = mutableMapOf<String, CapturedSourceRead>()
  private suspend fun hosts(): List<RemoteHost> {
    kotlinx.coroutines.currentCoroutineContext()[BackgroundResearchGuard]?.check?.invoke()
    return transport.hosts()
  }
  private suspend fun typed(host: RemoteHost): Boolean {
    kotlinx.coroutines.currentCoroutineContext()[BackgroundResearchGuard]?.check?.invoke()
    return transport.typed(host)
  }
  private suspend fun allowed(read: ResearchRead) {
    requireResearch(foundation.readAllowed(read), "EXCLUDED")
  }
  private suspend fun wire(host: RemoteHost, tool: String, args: JsonObject, read: ResearchRead? = null): RemoteResult.Done {
    kotlinx.coroutines.currentCoroutineContext()[BackgroundResearchGuard]?.check?.invoke()
    read?.let { allowed(it) }
    val result = transport.call(host, tool, args)
    read?.let { allowed(it) }
    return result
  }
  // Canonical content can be shared; an acquisition retains its exact consent/control read.
  private fun key(read: ResearchRead) = read.grantId + ":" + digest(researchJson.encodeToString(ResearchRead.serializer(), read))
  private fun data(result: RemoteResult.Done) = result.result?.data ?: throw ResearchUnavailable("TYPED_PROOF_REQUIRED")
  suspend fun discover(): List<SourceCandidate> = hosts().map { host ->
    fun unavailable(issue: SourceIssue) = SourceCandidate(host.packageName, host.authority,
      ResearchHostPolicy.domain(host) ?: host.label, null, null, false, issue)
    try {
      if (!typed(host)) unavailable(SourceIssue.UPDATE_REQUIRED) else {
        val c = data(wire(host, "source_capabilities", buildJsonObject {}))
        checkContract(c, host)
        val account = c.string("account")
        SourceCandidate(host.packageName, host.authority, c.string("domain")!!, account, c.string("student"),
          !account.isNullOrBlank(), if (account.isNullOrBlank()) SourceIssue.ACCOUNT_REQUIRED else null)
      }
    } catch (e: ResearchUnavailable) {
      unavailable(when (e.code) {
        "UNTRUSTED_SOURCE" -> SourceIssue.SIGNATURE_MISMATCH
        "UNSUPPORTED_SOURCE_PROOF", "TYPED_PROOF_REQUIRED", "TYPED_DATA_REQUIRED" -> SourceIssue.UPDATE_REQUIRED
        else -> SourceIssue.NOT_READY
      })
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
      catch (_: Exception) { unavailable(SourceIssue.NOT_READY) }
  }
  private fun checkContract(c: JsonObject, host: RemoteHost) {
    requireResearch(c.string("contract") == "aria.source-proof" && c["version"] == JsonPrimitive(1) &&
      c.string("authority") == host.authority && c.string("domain") in setOf("fluidify", "cv") &&
      !c.string("incarnation").isNullOrBlank() && !c.string("authorization").isNullOrBlank(), "UNSUPPORTED_SOURCE_PROOF")
  }
  suspend fun connect(read: ResearchRead, reconnect: Boolean = false): SourceGrant = lock.withLock {
    ResearchReadPolicy.check(read)
    val old = foundation.grant(read.grantId)
    requireResearch(old == null || old.enabled || reconnect, "LOCAL_REVOKED")
    val c = acquireFresh(read)
    val receipt = receipts.current(c.key) ?: throw ResearchUnavailable("PROOF_EXPIRED")
    if (old != null && old.enabled && old.generationProof == c.generation) {
      renewGrant(old, receipt); connections.save(read); return@withLock foundation.grant(old.id)!!
    }
    if (old != null) receipts.invalidate(old.id)
    // Explicit connect/reconnect may replace authorization, using the original stable grant ID.
    val fresh = if (old != null) acquireFresh(read) else c
    val proof = receipts.current(fresh.key) ?: throw ResearchUnavailable("PROOF_EXPIRED")
    val grant = foundation.putGrant(SourceGrant(read.grantId, read.selection.packageName, read.selection.authority,
      read.selection.account, read.selection.student, read.selection.area, read.selection.year,
      enabled = true, generationProof = fresh.generation, proofExpiresAt = proof.expires))
    connections.save(read); grant
  }
  suspend fun renew(reads: List<ResearchRead>) = lock.withLock {
    reads.forEach { read ->
      val g = foundation.grant(read.grantId) ?: throw ResearchUnavailable("SOURCE_NOT_CONNECTED")
      requireResearch(g.enabled, "LOCAL_REVOKED")
      var c = acquireLocked(read)
      try { validateLocked(c) } catch (e: ResearchUnavailable) {
        if (e.code in setOf("AUTHORIZATION_CHANGED", "EXCLUDED")) throw e
        // Reacquire once to distinguish expired handles from a verified authorization transition.
        c = acquireFresh(read); validateLocked(c)
      }
      val receipt = receipts.current(c.key) ?: throw ResearchUnavailable("PROOF_EXPIRED")
      if (g.generationProof != c.generation) {
        receipts.invalidate(g.id); foundation.revoke(g.id); throw ResearchUnavailable("AUTHORIZATION_CHANGED")
      }
      renewGrant(g, receipt)
    }
  }
  private suspend fun renewGrant(g: SourceGrant, r: CheckedReceipt) {
    if (r.expires > (g.proofExpiresAt ?: 0)) requireResearch(foundation.refreshProof(g.id, g.version, r.generation, r.expires, clock.wall()), "RENEWAL_FENCED")
    else requireResearch(receipts.valid(g, clock.wall()), "PROOF_EXPIRED")
  }
  suspend fun revoke(grantId: String) = lock.withLock {
    receipts.invalidate(grantId); captured.entries.removeAll { it.value.read.grantId == grantId }; foundation.revoke(grantId)
  }
  internal suspend fun acquire(read: ResearchRead): CapturedSourceRead = lock.withLock { acquireLocked(read) }
  private suspend fun acquireLocked(read: ResearchRead): CapturedSourceRead {
    ResearchReadPolicy.check(read)
    allowed(read)
    return captured[key(read)]?.takeIf { it.read == read && it.epoch == receipts.epoch(read.grantId) && receipts.current(it.key) != null }
      ?: acquireFresh(read)
  }
  private suspend fun acquireFresh(read: ResearchRead): CapturedSourceRead {
    ResearchReadPolicy.check(read)
    allowed(read)
    val host = RemoteHost(read.selection.packageName, read.selection.authority, read.selection.domain)
    val epoch = receipts.epoch(read.grantId); val start = clock.tick()
    requireResearch(typed(host), "TYPED_SOURCE_REQUIRED")
    val cap = data(wire(host, "source_capabilities", buildJsonObject {}, read)); checkContract(cap, host)
    if (cap.string("domain") == read.selection.domain && (cap.string("account") != read.selection.account || cap.string("student") != read.selection.student)) {
      receipts.invalidate(read.grantId); foundation.revoke(read.grantId); throw ResearchUnavailable("AUTHORIZATION_CHANGED")
    }
    requireResearch(cap.string("domain") == read.selection.domain && cap.string("account") == read.selection.account &&
      cap.string("student") == read.selection.student && (cap["readTools"] as? JsonArray)?.contains(JsonPrimitive(read.tool)) == true,
      "SOURCE_SCOPE_MISMATCH")
    val ttl = cap["ttlMillis"]?.jsonPrimitive?.longOrNull ?: 0
    requireResearch(ttl in 1..60_000, "INVALID_PROOF_TTL")
    val capability = cap.string("capability")?.takeIf { it.isNotBlank() } ?: throw ResearchUnavailable("CAPABILITY_REQUIRED")
    val leaseData = data(wire(host, "source_lease", buildJsonObject {
      put("version", 1); put("capability", capability); put("tool", read.tool); put("arguments", read.arguments)
    }, read))
    val c = CapturedSourceRead(read, host, capability, leaseData.string("lease") ?: throw ResearchUnavailable("LEASE_REQUIRED"),
      cap.string("incarnation")!! + "/" + cap.string("authorization")!!, key(read), epoch, start + ttl)
    verify(c, leaseData, start); captured[c.key] = c; return c
  }
  private fun args(c: CapturedSourceRead, tool: String = c.read.tool, input: JsonObject = c.read.arguments) = buildJsonObject {
    put("version", 1); put("capability", c.capability); put("lease", c.lease); put("tool", tool); put("arguments", input)
  }
  private suspend fun verify(c: CapturedSourceRead, proof: JsonObject, start: Long) {
    checkContract(proof, c.host)
    if (proof.string("domain") == c.read.selection.domain && proof.string("incarnation") + "/" + proof.string("authorization") != c.generation) {
      receipts.invalidate(c.read.grantId); foundation.revoke(c.read.grantId); throw ResearchUnavailable("AUTHORIZATION_CHANGED")
    }
    requireResearch(proof.string("domain") == c.read.selection.domain && proof.string("account") == c.read.selection.account &&
      proof.string("student") == c.read.selection.student && proof.string("incarnation") + "/" + proof.string("authorization") == c.generation &&
      proof.string("capability") == c.capability && proof.string("lease") == c.lease && proof["valid"] == JsonPrimitive(true) &&
      proof["scope"] == ResearchReadPolicy.scope(c.read.tool, c.read.arguments), "INVALID_SOURCE_PROOF")
    val remaining = (proof["expiresAtMillis"]?.jsonPrimitive?.longOrNull ?: 0) - (proof["checkedAtMillis"]?.jsonPrimitive?.longOrNull ?: Long.MAX_VALUE)
    val now = clock.tick(); val duration = minOf(c.capDeadline - now, remaining - (now - start), 30_000L)
    requireResearch(duration > 0 && remaining in 1..60_000, "PROOF_EXPIRED")
    receipts.publish(c.key, CheckedReceipt(c.read.grantId, c.read.selection, c.generation,
      ResearchReadPolicy.scope(c.read.tool, c.read.arguments), clock.wall() + duration, now + duration, c.epoch))
  }
  private suspend fun validateLocked(c: CapturedSourceRead) {
    ResearchReadPolicy.check(c.read)
    val start = clock.tick(); verify(c, data(wire(c.host, "source_validate", args(c), c.read)), start)
  }
  internal suspend fun validate(c: CapturedSourceRead) = lock.withLock { validateLocked(c) }
  internal suspend fun read(c: CapturedSourceRead, tool: String, arguments: JsonObject): RemoteResult.Done = lock.withLock {
    val g = foundation.grant(c.read.grantId)
    requireResearch(g?.enabled == true && g.generationProof == c.generation && c.epoch == receipts.epoch(c.read.grantId), "SOURCE_REVOKED")
    ResearchReadPolicy.check(c.read, tool, arguments, continuation = tool == "result_fragment" || arguments != c.read.arguments)
    if (tool != "result_fragment") requireResearch(ResearchReadPolicy.scope(tool, arguments) == ResearchReadPolicy.scope(c.read.tool, c.read.arguments), "CONTINUATION_SCOPE_CHANGED")
    val start = clock.tick(); val result = wire(c.host, "source_read", args(c, tool, arguments), c.read)
    val proof = data(result)["sourceProof"] as? JsonObject ?: throw ResearchUnavailable("TYPED_PROOF_REQUIRED")
    verify(c, proof, start); result
  }
}
