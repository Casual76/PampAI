package dev.pampa.pampai.core.assistant.durable

import androidx.room.withTransaction
import dev.pampa.pampai.core.assistant.db.PampaiDatabase
import dev.pampa.pampai.core.assistant.runtime.ModelPolicy
import kotlinx.serialization.json.*
import dev.pampa.pampai.core.assistant.research.ResearchRead

fun interface SourceProofValidator {
  /** Nonblocking verified source-owned proof check. Unknown/unsupported MUST return false. */
  fun valid(grant: SourceGrant, now: Long): Boolean
  companion object { val Deny = SourceProofValidator { _, _ -> false } }
}
data class JobLease(val job: ResearchJob, val owner: String, val fence: Long)
data class ProfileWrite(val item: ProfileItem, val evidenceIds: List<String>)
data class RetrievedProfile(val item: ProfileItem, val evidence: List<ResearchEvidence>, val grants: List<SourceGrant>)
data class ResearchSnapshot(val steps: List<ResearchStep>, val evidence: List<ResearchEvidence>, val grants: List<SourceGrant>)

class DurableFoundation(private val db: PampaiDatabase, private val proofs: SourceProofValidator = SourceProofValidator.Deny) {
  private val dao get() = db.durable()
  val settings get() = dao.areas()
  val jobs get() = dao.jobs()
  /** Management flow only; never inject this entire list into prompts. */
  val profile get() = dao.profile()
  val grantsFlow get() = dao.grantsFlow()
  val exclusionsFlow get() = dao.exclusionsFlow()
  val caps get() = dao.capsFlow()
  /** Local conservative preflight before source traffic. Model reservation remains authoritative. */
  suspend fun backgroundBudgetReason(area: String, now: Long): String? = db.withTransaction {
    val c = dao.area(area) ?: return@withTransaction "SETUP_REQUIRED"
    val caps = dao.caps().filter { now >= it.start && now < it.end }
    if (caps.none { !it.backgroundOnly } || caps.none { it.backgroundOnly }) return@withTransaction "NO_BUDGET_WINDOW"
    for (cap in caps + BudgetCap("area:$area", true, c.windowStart, c.windowEnd, c.requestCap, c.tokenCap, c.moneyCap)) {
      val rows = dao.attempts(cap.start, cap.end).filter { (!cap.backgroundOnly || it.background) && (!cap.id.startsWith("area:") || it.area == area) }
      if (rows.any { it.state == "UNCAPPED_UNKNOWN" }) return@withTransaction "UNKNOWN_PRIOR_TOKENS"
      try {
        if (rows.fold(0L) { a, r -> Math.addExact(a, r.chargedRequests) } >= cap.requests ||
          rows.fold(0L) { a, r -> Math.addExact(a, r.chargedTokens) } >= cap.tokens) return@withTransaction "CAP_EXCEEDED"
      } catch (_: ArithmeticException) { return@withTransaction "OVERFLOW" }
      if (cap.money != null) return@withTransaction "UNTRUSTED_OR_UNBOUNDED_TARIFF"
    }
    null
  }
  suspend fun scheduleBackgroundEligibility(id: String, eligibleAt: Long): Boolean = db.withTransaction {
    val job = dao.job(id) ?: return@withTransaction false
    if (job.execution != "BACKGROUND" || job.state != "READY" || job.owner != null || job.learningEpoch != epoch() || dao.dispatchedAttempts(id) > 0) return@withTransaction false
    dao.job(job.copy(eligibleAt = eligibleAt)); true
  }
  /** Shared Room transaction for source-free tool commits, without an unrelated privacy epoch fence. */
  suspend fun <T> withLocalTransaction(block: suspend () -> T): T = db.withTransaction { block() }
  /** Local only: callers renew source proofs before entering this transaction. */
  suspend fun <T> withTurnPrivacy(expectedEpoch: Long, reads: List<ResearchRead>, profile: List<RetrievedProfile>, now: Long,
    requireFreshProof: Boolean = true,
    block: suspend () -> T): T = db.withTransaction {
    if (epoch() != expectedEpoch) throw dev.pampa.pampai.core.assistant.research.TurnPrivacyInvalidated()
    for (read in reads) {
      val g = dao.grant(read.grantId)
      if (g == null || !g.enabled || g.generationProof.isNullOrBlank() ||
        (requireFreshProof && !proof(g, now)) || !readAllowed(read))
        throw dev.pampa.pampai.core.assistant.research.TurnPrivacyInvalidated()
    }
    for (captured in profile) {
      if (dao.profile(captured.item.id) != captured.item ||
        dao.dependencies(captured.item.id).mapNotNull { dao.evidence(it.evidenceId) } != captured.evidence ||
        captured.evidence.any { !it.valid || blocked(it.field, it.grantId, it.recordKey) })
        throw dev.pampa.pampai.core.assistant.research.TurnPrivacyInvalidated()
    }
    block()
  }
  /** Purely local; proof renewal must happen outside this transaction. */
  suspend fun <T> withResearchAcceptance(d: dev.pampa.pampai.core.assistant.research.ResearchAcceptanceDescriptor,
    now: Long, fresh: Boolean = true, block: suspend () -> T): T = db.withTransaction {
    fun reject(): Nothing = throw dev.pampa.pampai.core.assistant.research.TurnPrivacyInvalidated()
    if (epoch() != d.epoch || d.reads.isEmpty() ||
      d.grants.map { it.id }.toSet() != d.reads.map { it.grantId }.toSet()) reject()
    for (captured in d.grants) {
      val g = dao.grant(captured.id) ?: reject()
      if (!g.enabled || g.version != captured.version || g.generationProof != captured.generation) reject()
    }
    val profiles = d.profiles.map { captured ->
      val p = profileDetail(captured.id) ?: reject()
      if (dev.pampa.pampai.core.assistant.research.digest(p.item.toString() + "\n" + p.evidence.toString()) != captured.fingerprint) reject()
      p
    }
    d.jobId?.let { id ->
      val j = dao.job(id) ?: reject()
      if (j.state == "CANCELLED" || j.learningEpoch != d.epoch || j.conversationId != d.conversationId ||
        j.objective != d.objective || (fresh && !vector(j, now))) reject()
    }
    withTurnPrivacy(d.epoch, d.reads, profiles, now, requireFreshProof = fresh, block = block)
  }
  suspend fun policyEpoch(): Long = db.withTransaction { epoch() }
  suspend fun backgroundJob(area: String): ResearchJob? = db.withTransaction { dao.backgroundJob(area) }
  suspend fun hasDispatchedAttempts(job: String): Boolean = db.withTransaction { dao.dispatchedAttempts(job) > 0 }
  suspend fun providerEligibility(provider: String): Long? = db.withTransaction { dao.cooldown("$provider/configured-account")?.eligibleAt }
  suspend fun grant(id: String) = db.withTransaction { dao.grant(id) }
  suspend fun grants(): List<SourceGrant> = db.withTransaction { dao.grants() }
  suspend fun exclusions(): List<ProfileExclusion> = db.withTransaction { dao.exclusions() }
  suspend fun area(area: String) = db.withTransaction { dao.area(area) }
  suspend fun models(area: String) = db.withTransaction { dao.models(area) }
  suspend fun job(id: String) = db.withTransaction { dao.job(id) }
  suspend fun steps(id: String) = db.withTransaction { dao.steps(id) }
  suspend fun completedSteps(id: String, now: Long): List<ResearchStep>? = db.withTransaction {
    val j = dao.job(id) ?: return@withTransaction null
    if (j.state != "DONE" || j.learningEpoch != epoch() || !vector(j, now) || jobBlocked(j)) return@withTransaction null
    dao.steps(id)
  }
  /** Policy persistence and invalidation share the DB fence; failed persistence rolls back. */
  suspend fun changePolicy(persist: suspend () -> Unit) = db.withTransaction { persist(); fence() }
  suspend fun snapshot(lease: JobLease, now: Long): ResearchSnapshot = db.withTransaction {
    require(active(lease, now) != null)
    val steps = dao.steps(lease.job.id)
    require((dao.jobEvidence(lease.job.id).map { it.id } + steps.flatMap(::references)).toSet().size <= lease.job.maxRecords) { "Distinct consumed evidence limit" }
    val referenced = steps.flatMap { step -> runCatching { step.cursor?.let { Json.parseToJsonElement(it).jsonObject["evidence"]?.jsonArray?.map { id -> id.jsonPrimitive.content } } }.getOrNull().orEmpty() }
      .mapNotNull { dao.evidence(it) }.filter { e -> dao.vectors(lease.job.id).any { it.grantId == e.grantId } && dao.job(e.jobId)?.let { vector(it, now) } == true }
    ResearchSnapshot(steps, (dao.jobEvidence(lease.job.id) + referenced).distinctBy { it.id }.filter { it.valid && !blocked(it.field, it.grantId, it.recordKey) },
      dao.vectors(lease.job.id).map { requireNotNull(dao.grant(it.grantId)) })
  }
  suspend fun eligibleBackground(now: Long, afterTime: Long, afterId: String, limit: Int): List<ResearchJob> = db.withTransaction {
    require(limit in 1..100); dao.eligibleBackground(now, afterTime, afterId, limit)
  }
  suspend fun profileDetail(id: String): RetrievedProfile? = db.withTransaction {
    val item = dao.profile(id) ?: return@withTransaction null
    val evidence = dao.dependencies(id).mapNotNull { dao.evidence(it.evidenceId) }
    RetrievedProfile(item, evidence, evidence.mapNotNull { dao.grant(it.grantId) }.distinctBy { it.id })
  }
  suspend fun profileCandidates(fields: List<String>): List<RetrievedProfile> = db.withTransaction {
    require(fields.isNotEmpty() && fields.size <= 32)
    dao.candidates(fields, 500).map { item ->
      val evidence = dao.dependencies(item.id).mapNotNull { dao.evidence(it.evidenceId) }
      RetrievedProfile(item, evidence, evidence.mapNotNull { dao.grant(it.grantId) }.distinctBy { it.id })
    }
  }
  suspend fun retryForeground(id: String, conversationId: Long, now: Long): Boolean = db.withTransaction {
    val j = dao.job(id) ?: return@withTransaction false
    if (j.execution != "FOREGROUND" || j.conversationId != conversationId || j.state != "READY" || j.learningEpoch != epoch() || !vector(j, now) || jobBlocked(j)) return@withTransaction false
    dao.job(j.copy(eligibleAt = now)); true
  }
  private suspend fun epoch() = dao.state()?.epoch ?: 0L
  private suspend fun fence() { dao.state(LearningState(epoch = Math.addExact(epoch(), 1))); dao.invalidateJobs() }
  private suspend fun conversation(id: Long?) = id == null || dao.durableConversation(id) == 1
  private fun proof(g: SourceGrant, now: Long) = g.enabled && !g.generationProof.isNullOrBlank() && (g.proofExpiresAt ?: 0) > now && proofs.valid(g, now)
  private suspend fun vector(job: ResearchJob, now: Long): Boolean {
    if (!conversation(job.conversationId)) return false
    return dao.vectors(job.id).all { v -> dao.grant(v.grantId)?.let { it.version == v.version && it.generationProof == v.generationProof && proof(it, now) } == true }
  }
  /** Source adapters must check before acquisition and before accepting a late response. */
  suspend fun readAllowed(read: ResearchRead): Boolean = db.withTransaction {
    val grants = dao.grants()
    val identities = if (grants.any { it.id == read.grantId }) grants else grants + SourceGrant(read.grantId,
      read.selection.packageName, read.selection.authority, read.selection.account, read.selection.student, read.selection.area, read.selection.year)
    dao.exclusions().none { ExclusionScopes.blocksRead(it, ExclusionScopes.field(read), read.grantId, identities) }
  }
  private suspend fun planBlocked(steps: List<ResearchStep>): Boolean {
    val exclusions = dao.exclusions()
    if (exclusions.isEmpty()) return false
    val grants = dao.grants()
    return steps.any { step ->
      val read = ExclusionScopes.read(step.query)
      exclusions.any { ExclusionScopes.blocksRead(it, read?.let(ExclusionScopes::field), step.grantId.takeIf { it.isNotBlank() }, grants) }
    } || exclusions.any { it.unmapped }
  }
  private suspend fun jobBlocked(job: ResearchJob) = planBlocked(dao.steps(job.id))
  private suspend fun blocked(field: String, grant: String? = null, record: String? = null): Boolean =
    dao.exclusions().any { ExclusionScopes.blocksValue(it, field, grant, record, dao.grants()) }
  private fun typed(value: String, type: String): Boolean = when (type) {
    "TEXT" -> true
    "NUMBER" -> value.toBigDecimalOrNull() != null
    "BOOLEAN" -> value == "true" || value == "false"
    else -> false
  }
  suspend fun putGrant(grant: SourceGrant): SourceGrant = db.withTransaction {
    require(grant.id.isNotBlank() && grant.account.isNotBlank() && grant.app.isNotBlank() && grant.authority.isNotBlank() && grant.area.isNotBlank())
    val old = dao.grant(grant.id)
    val next = grant.copy(version = Math.addExact(old?.version ?: 0, 1))
    dao.grant(next); dao.invalidateEvidence(grant.id); dao.invalidateProfile(grant.id); dao.scrubGrantCheckpoints(grant.id); fence(); next
  }
  /** Refresh receipt-backed freshness only. Authorization/scope changes must use putGrant/revoke. */
  suspend fun refreshProof(id: String, expectedVersion: Long, generationProof: String, proposedExpiresAt: Long, now: Long): Boolean = db.withTransaction {
    val current = dao.grant(id) ?: return@withTransaction false
    if (!current.enabled || current.version != expectedVersion || generationProof.isBlank() || current.generationProof != generationProof || proposedExpiresAt <= now || proposedExpiresAt <= (current.proofExpiresAt ?: 0L)) return@withTransaction false
    val proposed = current.copy(proofExpiresAt = proposedExpiresAt)
    if (!proof(proposed, now)) return@withTransaction false
    dao.grant(proposed); true
  }
  suspend fun revoke(id: String) = db.withTransaction {
    dao.grant(id)?.let { dao.grant(it.copy(enabled = false, version = Math.addExact(it.version, 1), generationProof = null, proofExpiresAt = null)) }
    dao.invalidateEvidence(id); dao.invalidateProfile(id); dao.scrubGrantCheckpoints(id); fence()
  }
  suspend fun configure(configuration: AreaConfiguration, models: List<AreaModel>) = db.withTransaction {
    require(configuration.cadenceMillis >= 900_000 && configuration.requestCap >= 0 && configuration.tokenCap >= 0 && (configuration.moneyCap == null || configuration.moneyCap >= 0))
    require(models.size <= 32 && models.all { it.area == configuration.area && it.model.isNotBlank() && it.provider.isNotBlank() })
    if (configuration.enabled) require(configuration.setupComplete && models.isNotEmpty() && configuration.windowEnd > configuration.windowStart)
    dao.area(configuration); dao.clearModels(configuration.area); dao.models(models.distinct())
  }
  suspend fun createJob(id: String, conversationId: Long?, area: String, intent: String, execution: String, objective: String,
    policy: ModelPolicy, grantIds: List<String>, plan: List<ResearchStep>, maxRecords: Int, now: Long): ResearchJob = db.withTransaction {
    if (execution == "BACKGROUND") dao.backgroundJob(area)?.takeIf { it.learningEpoch == epoch() }?.let { return@withTransaction it }
    require(conversation(conversationId)) { "Temporary or missing conversation" }
    require(intent in setOf("DIRECT", "LOOKUP", "RESEARCH") && execution in setOf("FOREGROUND", "BACKGROUND"))
    require(id.isNotBlank() && objective.length in 1..8192 && maxRecords in 1..10000 && plan.size in 1..64 && grantIds.size <= 32)
    require(grantIds.distinct().size == grantIds.size && plan.map { it.ordinal } == plan.indices.toList())
    require(!planBlocked(plan)) { "Acquisition excluded" }
    require(plan.all { step -> ExclusionScopes.read(step.query)?.let { it.grantId == step.grantId } ?: true }) { "Read/grant mismatch" }
    val grants = grantIds.map { requireNotNull(dao.grant(it)) }
    require(grants.all { (it.area == area || (execution == "FOREGROUND" && area == "interactive")) && proof(it, now) }) { "Unknown or expired source proof" }
    require(plan.all { it.jobId == id && (it.grantId in grantIds || (intent == "DIRECT" && it.grantId.isEmpty())) && it.query.length in 1..8192 })
    val job = ResearchJob(id, conversationId, area, intent, execution, objective, policy.identity.provider.name, policy.identity.model,
      policy.effort?.name, policy.helper?.provider?.name, policy.helper?.model, plan.size, maxRecords, epoch())
    dao.createJob(job); dao.vectors(grants.map { JobGrant(id, it.id, it.version, it.generationProof) }); plan.forEach { dao.step(it) }; job
  }
  private suspend fun background(job: ResearchJob): Boolean {
    if (job.execution != "BACKGROUND") return true
    val c = dao.area(job.area) ?: return false
    val models = dao.models(job.area)
    return c.enabled && c.setupComplete && models.any { it.provider == job.provider && it.model == job.model } &&
      (job.helperModel == null || models.any { it.provider == job.helperProvider && it.model == job.helperModel })
  }
  suspend fun claim(id: String, owner: String, now: Long, leaseMillis: Long): JobLease? = db.withTransaction {
    require(owner.isNotBlank() && leaseMillis in 1..300_000)
    val job = dao.job(id) ?: return@withTransaction null
    if (job.state !in setOf("READY", "RUNNING") || job.eligibleAt > now || (job.owner != null && job.leaseUntil > now) || job.learningEpoch != epoch() || !vector(job, now) || !background(job) || jobBlocked(job)) return@withTransaction null
    val next = job.copy(state = "RUNNING", owner = owner, leaseUntil = Math.addExact(now, leaseMillis), fence = Math.addExact(job.fence, 1))
    dao.job(next); JobLease(next, owner, next.fence)
  }
  private suspend fun active(lease: JobLease, now: Long): ResearchJob? {
    val job = dao.job(lease.job.id) ?: return null
    return job.takeIf { it.state == "RUNNING" && it.owner == lease.owner && it.fence == lease.fence && it.cancelVersion == lease.job.cancelVersion && it.leaseUntil > now && it.learningEpoch == epoch() && vector(it, now) && background(it) && !jobBlocked(it) }
  }
  suspend fun validate(lease: JobLease, now: Long): Boolean = db.withTransaction { active(lease, now) != null }
  suspend fun checkpoint(lease: JobLease, now: Long): List<ResearchStep> = db.withTransaction { require(active(lease, now) != null); dao.steps(lease.job.id) }
  internal suspend fun checkpoint(jobId: String): List<ResearchStep> = db.withTransaction { dao.steps(jobId) }
  suspend fun commit(lease: JobLease, step: ResearchStep, evidence: List<ResearchEvidence>, items: List<ProfileWrite>, now: Long, eligibleAt: Long = now): Boolean = db.withTransaction {
    val job = active(lease, now) ?: return@withTransaction false
    val old = dao.steps(job.id).find { it.ordinal == step.ordinal } ?: return@withTransaction false
    require(step.jobId == job.id && step.grantId == old.grantId && step.query == old.query && step.progress >= old.progress && step.fragmentOffset >= 0)
    require(step.outcome in setOf("UNKNOWN", "PARTIAL", "COMPLETE") && step.fragment.orEmpty().length <= 262144 && step.cursor.orEmpty().length <= 8192)
    require(evidence.size <= 500 && items.size <= 100 && evidence.all { typed(it.value, it.valueType) && it.field.isNotBlank() && it.recordKey.isNotBlank() && it.periodKey.isNotBlank() && it.value.length <= 16384 && it.provenance.length <= 8192 && it.jobId == job.id && it.grantId == step.grantId && it.valid })
    if (step.outcome == "UNKNOWN") require(evidence.isEmpty() && items.isEmpty()) { "Read failure is unknown" }
    if (evidence.any { blocked(it.field, it.grantId, it.recordKey) }) return@withTransaction false
    val vectorIds = dao.vectors(job.id).map { it.grantId }.toSet()
    require(evidence.all { it.grantId in vectorIds }) { "Source evidence requires a captured grant" }
    val resolved = evidence.map { e ->
      val existing = dao.record(e.grantId, e.recordKey, e.periodKey)?.takeIf { it.valid }
      require(existing == null || (existing.id == e.id && existing.field == e.field && existing.value == e.value &&
        existing.valueType == e.valueType && existing.unit == e.unit && existing.contentHash == e.contentHash &&
        EvidenceAcquisition.canonicalProvenance(existing.provenance) == EvidenceAcquisition.canonicalProvenance(e.provenance))) { "Canonical evidence collision" }
      existing ?: e
    }
    require(references(old).all { it in references(step) }) { "Checkpoint cannot discard consumed evidence" }
    validateCheckpoint(job, step, evidence, now)
    val consumed = (dao.jobEvidence(job.id).map { it.id } + evidence.map { it.id } +
      dao.steps(job.id).flatMap { references(if (it.ordinal == step.ordinal) step else it) }).toSet()
    require(consumed.size <= job.maxRecords) { "Distinct consumed evidence limit" }
    val acceptedItems = mutableListOf<ProfileWrite>()
    for (write in items) {
      val item = write.item
      require(typed(item.value, item.valueType) && item.field.isNotBlank() && item.jobId == job.id && item.kind in setOf("OBSERVATION", "INFERENCE") && item.value.length <= 16384 && write.evidenceIds.isNotEmpty() && write.evidenceIds.size <= 100)
      if (blocked(item.field)) continue
      // Optional learning must never replace a user's correction or veto a valid answer.
      if (dao.candidates(listOf(item.field), 100).any { it.kind == "USER_STATEMENT" }) continue
      require(dao.profile(item.id) == null) { "Use a fresh revision identity" }
      if (item.kind == "INFERENCE") require(item.generatedProvider == job.provider && item.generatedModel == job.model && !item.derivation.isNullOrBlank())
      for (eid in write.evidenceIds) {
        val e = resolved.find { it.id == eid } ?: dao.evidence(eid)
        require(e != null && e.valid && e.grantId in vectorIds && vector(requireNotNull(dao.job(e.jobId)), now) && !blocked(e.field, e.grantId, e.recordKey))
        val grant = requireNotNull(dao.grant(e.grantId))
        val consent = dao.area(grant.area)
        if (consent?.enabled != true || !consent.setupComplete || dao.models(grant.area).none { it.provider == job.provider && it.model == job.model }) return@withTransaction false
      }
      acceptedItems += write
    }
    evidence.forEach { e ->
      val existing = dao.evidence(e.id)
      require(existing == null || !existing.valid || existing == e ||
        (existing.grantId == e.grantId && existing.recordKey == e.recordKey && existing.periodKey == e.periodKey &&
          existing.field == e.field && existing.value == e.value && existing.valueType == e.valueType &&
          existing.unit == e.unit && existing.contentHash == e.contentHash &&
          EvidenceAcquisition.canonicalProvenance(existing.provenance) == EvidenceAcquisition.canonicalProvenance(e.provenance)))
    }
    evidence.forEach { dao.removeInvalidRecord(it.grantId, it.recordKey, it.periodKey); dao.evidence(it) }
    acceptedItems.forEach { w -> dao.profile(w.item.copy(revision = epoch())); w.evidenceIds.forEach { dao.dependency(ProfileDependency(w.item.id, it)) } }
    dao.step(step); dao.job(job.copy(state = "READY", owner = null, leaseUntil = 0, eligibleAt = eligibleAt)); true
  }
  private fun references(step: ResearchStep): List<String> {
    val cursor = step.cursor ?: return emptyList()
    // Legacy non-JSON cursors carry no canonical references.
    val parsed = runCatching { Json.parseToJsonElement(cursor) as? JsonObject }.getOrNull() ?: return emptyList()
    val ids = parsed["evidence"] ?: return emptyList()
    require(ids is JsonArray) { "Invalid checkpoint evidence" }
    return ids.map { it.jsonPrimitive.content }
  }
  private suspend fun validateCheckpoint(job: ResearchJob, step: ResearchStep, evidence: List<ResearchEvidence>, now: Long) {
    val ids = references(step)
    val read = ExclusionScopes.read(step.query)
    if (read != null) {
      require(read.grantId == step.grantId) { "Read/grant mismatch" }
      val query = Json.parseToJsonElement(step.query).jsonObject
      if (query["synthesis"] != JsonPrimitive(true) && (ids.isNotEmpty() || evidence.isNotEmpty())) {
        val cursor = Json.parseToJsonElement(requireNotNull(step.cursor)).jsonObject
        val binding = cursor.getValue("acquisition").jsonObject
        require(Json.decodeFromJsonElement<ResearchRead>(binding.getValue("read")) == read &&
          binding["grant"] == JsonPrimitive(step.grantId) &&
          binding["generation"] == JsonPrimitive(dao.grant(step.grantId)?.generationProof) &&
          !binding["lease"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) { "Acquisition/checkpoint mismatch" }
        require(evidence.all { it.id in ids && it.field == ExclusionScopes.field(read) &&
          runCatching { Json.decodeFromJsonElement<ResearchRead>(Json.parseToJsonElement(it.provenance).jsonObject.getValue("read")) }
            .getOrNull()?.let(EvidenceAcquisition::canonicalRead) == EvidenceAcquisition.canonicalRead(read) }) { "Evidence/read mismatch" }
      }
    }
    val vectorIds = dao.vectors(job.id).map { it.grantId }.toSet()
    for (id in ids) {
      val e = evidence.find { it.id == id } ?: dao.evidence(id)
      require(e != null && e.valid && e.grantId in vectorIds && !blocked(e.field, e.grantId, e.recordKey)) { "Invalid checkpoint reference" }
      if (e.jobId != job.id) require(dao.job(e.jobId)?.let { vector(it, now) } == true) { "Referenced evidence revoked" }
      if (read != null && Json.parseToJsonElement(step.query).jsonObject["synthesis"] != JsonPrimitive(true)) {
        require(e.grantId == read.grantId && e.field == ExclusionScopes.field(read) &&
          runCatching { Json.decodeFromJsonElement<ResearchRead>(Json.parseToJsonElement(e.provenance).jsonObject.getValue("read")) }
            .getOrNull()?.let(EvidenceAcquisition::canonicalRead) == EvidenceAcquisition.canonicalRead(read)) { "Reference/read mismatch" }
      }
    }
  }
  suspend fun cancel(id: String) = db.withTransaction { dao.job(id)?.let { dao.job(it.copy(state = "CANCELLED", cancelVersion = Math.addExact(it.cancelVersion, 1), owner = null, leaseUntil = 0)) } }
  suspend fun complete(lease: JobLease, now: Long): Boolean = db.withTransaction {
    val job = active(lease, now) ?: return@withTransaction false
    if (dao.steps(job.id).any { it.outcome != "COMPLETE" }) return@withTransaction false
    dao.job(job.copy(state = "DONE", owner = null, leaseUntil = 0)); true
  }
  suspend fun correct(id: String, field: String, value: String, conversationId: Long? = null) = db.withTransaction {
    require(conversation(conversationId) && field.isNotBlank() && value.length in 1..16384)
    fence(); dao.forgetDependents(field, null, null); dao.supersede(field)
    dao.clearDependencies(id); dao.profile(ProfileItem(id, field, value, "USER_STATEMENT", null, epoch()))
  }
  suspend fun exclude(exclusion: ProfileExclusion) = db.withTransaction {
    require(exclusion.key.isNotBlank() && (exclusion.unmapped || exclusion.field != null || exclusion.grantId != null || exclusion.recordKey != null))
    fence(); dao.exclusion(exclusion); dao.scrubCheckpoints(); dao.scrubCancelledObjectives()
    val relatedFields = exclusion.field?.let { ExclusionScopes.family(it) }
    val relatedGrants = if (exclusion.grantId == null) listOf<String?>(null) else
      (dao.grants().filter { ExclusionScopes.sourceMatches(exclusion, it.id, dao.grants()) }.map { it.id } + exclusion.grantId).distinct()
    if (exclusion.unmapped) { dao.forgetDependents(null, null, null); dao.forgetField(null); dao.forgetEvidence(null, null, null) }
    if (exclusion.field == null) for (grant in relatedGrants) {
      dao.forgetDependents(null, grant, null); dao.forgetEvidence(null, grant, null)
    }
    if (relatedFields != null) for (field in relatedFields) for (grant in relatedGrants) {
      dao.forgetDependents(field, grant, null)
      if (grant == null) dao.forgetField(field)
      dao.forgetEvidence(field, grant, null)
    }
    dao.forgetDependents(exclusion.field, exclusion.grantId, exclusion.recordKey)
    if (exclusion.grantId == null && exclusion.recordKey == null) dao.forgetField(exclusion.field)
    dao.forgetEvidence(exclusion.field, exclusion.grantId, exclusion.recordKey)
  }
  suspend fun forget(field: String, grantId: String? = null, recordKey: String? = null) = exclude(ProfileExclusion("forget:" + listOf(field, grantId, recordKey).joinToString("|") { it?.let { s -> "${s.length}:$s" } ?: "null" }, field, grantId, recordKey, false))
  suspend fun reenable(key: String) = db.withTransaction { fence(); dao.reenable(key) }
  suspend fun retrieve(fields: List<String>, now: Long, sourceGrant: String? = null, evidenceIds: Set<String> = emptySet(), limit: Int = 12, maxCharacters: Int = 8000): List<RetrievedProfile> = db.withTransaction {
    require(fields.isNotEmpty() && fields.size <= 32 && limit in 1..100 && maxCharacters in 1..65536 && evidenceIds.size <= 100)
    val out = mutableListOf<RetrievedProfile>(); var chars = 0
    for (item in dao.candidates(fields, 500)) {
      if (out.size >= limit) break
      if (blocked(item.field)) continue
      if (item.kind != "USER_STATEMENT" && out.any { it.item.field == item.field && it.item.kind == "USER_STATEMENT" }) continue
      val evidence = dao.dependencies(item.id).mapNotNull { dao.evidence(it.evidenceId) }
      if (item.kind != "USER_STATEMENT") {
        val job = item.jobId?.let { dao.job(it) } ?: continue
        if (!vector(job, now) || evidence.isEmpty() || evidence.size != dao.dependencies(item.id).size || evidence.any { !it.valid || dao.job(it.jobId)?.let { origin -> !vector(origin, now) } != false || blocked(it.field, it.grantId, it.recordKey) }) continue
      }
      if (sourceGrant != null && evidence.none { it.grantId == sourceGrant }) continue
      if (evidenceIds.isNotEmpty() && evidence.none { it.id in evidenceIds }) continue
      val cost = item.value.length + evidence.sumOf { it.value.length + it.provenance.length }
      if (cost > maxCharacters - chars) continue
      chars += cost; out += RetrievedProfile(item, evidence, evidence.mapNotNull { dao.grant(it.grantId) }.distinctBy { it.id })
    }; out
  }
}
