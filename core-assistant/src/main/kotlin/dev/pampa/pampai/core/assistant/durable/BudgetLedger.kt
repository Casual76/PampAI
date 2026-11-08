package dev.pampa.pampai.core.assistant.durable

import androidx.room.withTransaction
import dev.pampa.pampai.core.assistant.db.PampaiDatabase

/** Integer micro-units in one configured currency; never floating-point prices. */
data class TariffProof(val id: String, val provider: String, val model: String, val expiresAt: Long,
  val inputPerToken: Long, val outputPerToken: Long, val boundedFeatureCharge: Long, val allFeaturesBounded: Boolean)
fun interface TariffValidator {
  fun trusted(proof: TariffProof): Boolean
  companion object { val Deny = TariffValidator { false } }
}
data class AttemptReservation(val attemptId: String, val executionId: String, val jobId: String?, val area: String,
  val provider: String, val model: String, val purpose: String, val quotaKey: String, val background: Boolean,
  val inputTokenBound: Long?, val outputTokenBound: Long?, val tariff: TariffProof? = null, val jobFence: Long? = null, val jobOwner: String? = null, val allowUncappedForeground: Boolean = false)
data class Admission(val admitted: Boolean, val reason: String, val attempt: BudgetAttempt? = null)
data class ObservedSettlement(val key: String, val certainty: String, val tokens: Long?, val money: Long?, val error: String? = null)

class BudgetLedger(private val db: PampaiDatabase, private val tariffs: TariffValidator = TariffValidator.Deny, private val proofs: SourceProofValidator = SourceProofValidator.Deny) {
  private val dao get() = db.durable()
  suspend fun configure(cap: BudgetCap) = db.withTransaction {
    require(cap.id.isNotBlank() && !cap.id.startsWith("area:") && cap.end > cap.start && cap.requests >= 0 && cap.tokens >= 0 && (cap.money == null || cap.money >= 0))
    dao.cap(cap)
  }
  suspend fun setForeground(execution: String, active: Boolean) = db.withTransaction {
    require(execution.isNotBlank()); if (active) dao.foreground(ForegroundActivity(execution)) else dao.endForeground(execution)
  }
  /** Pass only verified provider/account Retry-After or reset eligibility. Never guesses midnight. */
  suspend fun cooldown(quotaKey: String, eligibleAt: Long, reason: String) = db.withTransaction {
    val old = dao.cooldown(quotaKey)
    dao.cooldown(ProviderCooldown(quotaKey, maxOf(old?.eligibleAt ?: 0, eligibleAt), reason))
  }
  /** Recovery caller supplies only reconciled abandoned IDs; active IDs are excluded atomically by coordinator. */
  suspend fun foregroundExecutions(): List<String> = db.withTransaction { dao.foregroundExecutions() }
  suspend fun quotaEligibility(key: String): Long = db.withTransaction { dao.cooldown(key)?.eligibleAt ?: 0L }
  suspend fun attempt(id: String) = db.withTransaction { dao.attempt(id) }
  suspend fun reserve(request: AttemptReservation, now: Long): Admission = db.withTransaction {
    fun deny(reason: String) = Admission(false, reason)
    require(listOf(request.attemptId, request.executionId, request.provider, request.model, request.purpose, request.quotaKey).all { it.isNotBlank() })
    if (dao.attempt(request.attemptId) != null) return@withTransaction deny("DUPLICATE_ATTEMPT")
    if ((dao.cooldown(request.quotaKey)?.eligibleAt ?: 0) > now) return@withTransaction deny("COOLDOWN")
    if (request.background && dao.foregroundCount() > 0) return@withTransaction deny("FOREGROUND_PRIORITY")
    if (request.jobId != null) {
      val job = dao.job(request.jobId) ?: return@withTransaction deny("JOB_REQUIRED")
      if (job.fence != request.jobFence || job.owner != request.jobOwner || job.state != "RUNNING" || job.leaseUntil <= now || job.learningEpoch != (dao.state()?.epoch ?: 0L) || job.area != request.area || (job.execution == "BACKGROUND") != request.background) return@withTransaction deny("JOB_FENCE")
      if (job.conversationId != null && dao.durableConversation(job.conversationId) != 1) return@withTransaction deny("TEMPORARY_CONVERSATION")
      if (!((job.provider == request.provider && job.model == request.model) || (job.helperProvider == request.provider && job.helperModel == request.model))) return@withTransaction deny("JOB_MODEL_MISMATCH")
      for (v in dao.vectors(job.id)) {
        val g = dao.grant(v.grantId) ?: return@withTransaction deny("SOURCE_PROOF")
        if (!g.enabled || g.version != v.version || g.generationProof != v.generationProof || g.generationProof.isNullOrBlank() || (g.proofExpiresAt ?: 0) <= now || !proofs.valid(g, now)) return@withTransaction deny("SOURCE_PROOF")
      }
    }
    val caps = dao.caps().filter { now >= it.start && now < it.end && (!it.backgroundOnly || request.background) }.toMutableList()
    val uncapped = !request.background && request.allowUncappedForeground && dao.caps().none { !it.backgroundOnly }
    if (caps.none { !it.backgroundOnly } && !uncapped) return@withTransaction deny("NO_GLOBAL_WINDOW")
    if (uncapped) {
      // Zero is an internal storage placeholder, NEVER an observed token count or a bound.
      val row = BudgetAttempt(request.attemptId, request.executionId, request.jobId, request.area, request.provider, request.model,
        request.purpose, request.quotaKey, false, now, 0, null, chargedTokens = 0, chargedMoney = null, state = "UNCAPPED_UNKNOWN")
      dao.reserve(row)
      return@withTransaction Admission(true, "UNCAPPED_NO_GUARANTEE", row)
    }
    if (request.background) {
      if (caps.none { it.backgroundOnly }) return@withTransaction deny("NO_BACKGROUND_WINDOW")
      val area = dao.area(request.area) ?: return@withTransaction deny("SETUP_REQUIRED")
      if (!area.enabled || !area.setupComplete || now < area.windowStart || now >= area.windowEnd) return@withTransaction deny("SETUP_REQUIRED")
      if (dao.models(request.area).none { it.provider == request.provider && it.model == request.model }) return@withTransaction deny("MODEL_NOT_ALLOWED")
      val job = request.jobId?.let { dao.job(it) } ?: return@withTransaction deny("JOB_REQUIRED")
      if (job.fence != request.jobFence || job.owner != request.jobOwner || job.execution != "BACKGROUND" || job.area != request.area || job.state != "RUNNING" || job.leaseUntil <= now || job.learningEpoch != (dao.state()?.epoch ?: 0L)) return@withTransaction deny("JOB_FENCE")
      caps += BudgetCap("area:${area.area}", true, area.windowStart, area.windowEnd, area.requestCap, area.tokenCap, area.moneyCap)
    }
    val input = request.inputTokenBound ?: return@withTransaction deny("UNBOUNDED_INPUT")
    val output = request.outputTokenBound ?: return@withTransaction deny("UNBOUNDED_OUTPUT")
    if (input < 0 || output < 0) return@withTransaction deny("INVALID_BOUND")
    val tokens = try { Math.addExact(input, output) } catch (_: ArithmeticException) { return@withTransaction deny("OVERFLOW") }
    val proof = request.tariff
    val money = if (proof != null && proof.provider == request.provider && proof.model == request.model && proof.expiresAt > now && proof.allFeaturesBounded && tariffs.trusted(proof) && proof.inputPerToken >= 0 && proof.outputPerToken >= 0 && proof.boundedFeatureCharge >= 0) {
      try { Math.addExact(Math.addExact(Math.multiplyExact(input, proof.inputPerToken), Math.multiplyExact(output, proof.outputPerToken)), proof.boundedFeatureCharge) }
      catch (_: ArithmeticException) { return@withTransaction deny("OVERFLOW") }
    } else null
    if (caps.any { it.money != null } && money == null) return@withTransaction deny("UNTRUSTED_OR_UNBOUNDED_TARIFF")
    for (cap in caps) {
      val rows = dao.attempts(cap.start, cap.end).filter { (!cap.backgroundOnly || it.background) && (!cap.id.startsWith("area:") || it.area == request.area) }
      try {
        if (rows.any { it.state == "UNCAPPED_UNKNOWN" }) return@withTransaction deny("UNKNOWN_PRIOR_TOKENS")
        val usedRequests = rows.fold(0L) { acc, it -> Math.addExact(acc, it.chargedRequests) }
        val usedTokens = rows.fold(0L) { acc, it -> Math.addExact(acc, it.chargedTokens) }
        if (Math.addExact(usedRequests, 1) > cap.requests || Math.addExact(usedTokens, tokens) > cap.tokens) return@withTransaction deny("CAP_EXCEEDED")
        val ceiling = cap.money
        if (ceiling != null) {
          if (rows.any { it.chargedMoney == null && it.chargedRequests != 0L }) return@withTransaction deny("UNKNOWN_PRIOR_MONEY")
          val usedMoney = rows.fold(0L) { acc, it -> Math.addExact(acc, it.chargedMoney ?: 0) }
          if (Math.addExact(usedMoney, requireNotNull(money)) > ceiling) return@withTransaction deny("MONEY_CAP_EXCEEDED")
        }
      } catch (_: ArithmeticException) { return@withTransaction deny("OVERFLOW") }
    }
    val row = BudgetAttempt(request.attemptId, request.executionId, request.jobId, request.area, request.provider, request.model, request.purpose, request.quotaKey,
      request.background, now, tokens, money, chargedTokens = tokens, chargedMoney = money, tariffId = proof?.id)
    dao.reserve(row); Admission(true, "RESERVED", row)
  }
  /** Unknown usage keeps every reserved bound. NOT_DISPATCHED releases only with no observed charge. */
  suspend fun settle(attemptId: String, settlement: ObservedSettlement): BudgetAttempt = db.withTransaction {
    require(settlement.key.isNotBlank() && settlement.certainty in setOf("NOT_DISPATCHED", "MAY_HAVE_DISPATCHED", "RESPONSE_RECEIVED"))
    require(settlement.tokens == null || settlement.tokens >= 0); require(settlement.money == null || settlement.money >= 0)
    val old = requireNotNull(dao.attempt(attemptId))
    // Persist complete settlement fingerprint, so a reused callback key cannot hide conflicting usage.
    val fingerprint = listOf(settlement.key, settlement.certainty, settlement.tokens, settlement.money, settlement.error).joinToString("|") { it?.toString()?.let { s -> "${s.length}:$s" } ?: "null" }
    if (old.settlementKey != null) { require(old.settlementKey == fingerprint) { "Conflicting settlement" }; return@withTransaction old }
    val released = settlement.certainty == "NOT_DISPATCHED"
    require(!released || ((settlement.tokens ?: 0) == 0L && (settlement.money ?: 0) == 0L))
    val tokens = if (released) 0 else settlement.tokens ?: old.reservedTokens
    val money = if (released) 0L else settlement.money ?: old.reservedMoney
    val overrun = tokens > old.reservedTokens || (money != null && old.reservedMoney != null && money > old.reservedMoney)
    val row = old.copy(chargedRequests = if (released) 0 else 1, chargedTokens = tokens, chargedMoney = money,
      state = when { !released && old.state == "UNCAPPED_UNKNOWN" && settlement.tokens == null -> "UNCAPPED_UNKNOWN"; released -> "NOT_DISPATCHED"; old.state == "UNCAPPED_UNKNOWN" -> "UNCAPPED_OBSERVED"; overrun -> "OVERRUN"; settlement.tokens == null || (old.reservedMoney != null && settlement.money == null) -> "UNCERTAIN"; settlement.error != null -> "ERROR_OBSERVED"; else -> "SETTLED" },
      settlementKey = fingerprint, error = settlement.error)
    dao.attempt(row); row
  }
}
