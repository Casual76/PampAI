package dev.pampa.pampai.core.assistant.runtime

import dev.antigravity.fluidengine.ai.provider.*
import dev.pampa.pampai.core.assistant.durable.*
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Validated policy supplies a total input bound including media/reasoning, never payload bytes.
 * Default denies bounded dispatch. No historical tariffs or live account lookup is consulted. */
fun interface RuntimeAttemptPolicy {
  fun bounds(attempt: ProviderAttempt): RuntimeAttemptBounds?
  companion object { val Deny = RuntimeAttemptPolicy { null } }
}
data class RuntimeAttemptBounds(val inputTokens: Long, val totalOutputTokens: Long)
data class RuntimeBudgetStop(val executionId: String, val reason: String, val storageFailure: Boolean)
/** Preparation boundary error: callers display it while retaining their unconsumed draft. */
class RuntimeBudgetRefusal(val reason: String, cause: Throwable) : IllegalStateException(
  "Esecuzione sospesa ($reason). La bozza e' conservata; verifica limiti e disponibilita prima di riprovare.", cause)


sealed interface BudgetExecutionResult<out T> {
  data class Completed<T>(val value: T) : BudgetExecutionResult<T>
  data class Yielded(val reason: String, val eligibleAt: Long?) : BudgetExecutionResult<Nothing>
  data class StorageFailure(val attemptId: String) : BudgetExecutionResult<Nothing>
}

/** App-owned lifecycle around every transport. No content, credentials or profile rows are stored. */
@Singleton
class RuntimeBudgetCoordinator @Inject constructor(private val ledger: BudgetLedger, private val db: dev.pampa.pampai.core.assistant.db.PampaiDatabase) {
  private val process = UUID.randomUUID().toString()
  private val lock = Mutex()
  private val active = mutableSetOf<String>()
  private val stop = kotlinx.coroutines.flow.MutableStateFlow<RuntimeBudgetStop?>(null)
  val lastStop: kotlinx.coroutines.flow.StateFlow<RuntimeBudgetStop?> = stop
  @Volatile private var boundsPolicy: RuntimeAttemptPolicy = RuntimeAttemptPolicy.Deny

  /** Install only validated local model metadata/policy. Does not enable autonomous work. */
  fun configureBounds(policy: RuntimeAttemptPolicy) { boundsPolicy = policy }

  /** All global caps apply to foreground too; an expired configured cap closes dispatch.
   * Only absence of ANY configured global cap permits explicitly uncapped foreground telemetry. */
  suspend fun configure(cap: BudgetCap) = ledger.configure(cap)

  suspend fun hasForeground(): Boolean = lock.withLock { ledger.foregroundExecutions().isNotEmpty() }
  suspend fun abandonedForeground(): List<String> = lock.withLock {
    ledger.foregroundExecutions().filter { it !in active && !it.startsWith("$process/") }
  }
  /** Explicit process-restart reconciliation. Never ends work owned by this live process. */
  suspend fun recoverAbandonedForeground(ids: Set<String>) = lock.withLock {
    val abandoned = ledger.foregroundExecutions().filter { it !in active && !it.startsWith("$process/") }.toSet()
    require(ids.all { it in abandoned })
    ids.forEach { ledger.setForeground(it, false) }
  }

  suspend fun <T> foreground(policy: ModelPolicy?, purpose: String = "chat", block: suspend () -> T): T {
    // Nested preflight inherits the original execution and priority marker.
    currentCoroutineContext()[ProviderExecutionContext]?.let {
      if (it.lifecycle !is Adapter) throw ProviderAdmissionDenied("APP_EXECUTION_CONTEXT_REQUIRED")
      return block()
    }
    val id = "$process/${UUID.randomUUID()}"
    lock.withLock { ledger.setForeground(id, true); active.add(id) }
    try {
      return withContext(context(id, purpose, policy?.identity, null, true)) { block() }
    } catch (e: ProviderAdmissionDenied) {
      stop.value = RuntimeBudgetStop(id, e.reason, false)
      throw e
    } catch (e: ProviderObserverFailure) {
      stop.value = RuntimeBudgetStop(id, "OBSERVER_STORAGE_FAILURE", true)
      throw e
    } finally {
      withContext(NonCancellable) { lock.withLock { ledger.setForeground(id, false); active.remove(id) } }
    }
  }

  /** Runner-facing fenced execution. Denials yield once; the runner checkpoints and schedules.
   * Background never falls back into uncapped mode. Foreground is uncapped only before global setup.
   * Lease/source vectors are checked by ledger before either admission path. */
  suspend fun <T> job(lease: JobLease, purpose: String, block: suspend () -> T): BudgetExecutionResult<T> {
    val job = lease.job
    val identity = ExactModel(ProviderId.valueOf(job.provider), job.model)
    val id = "$process/job/${job.id}/${lease.fence}/${UUID.randomUUID()}"
    val foreground = job.execution != "BACKGROUND"
    if (foreground) lock.withLock { ledger.setForeground(id, true); active.add(id) }
    return try {
      BudgetExecutionResult.Completed(withContext(context(id, purpose, identity, lease, foreground)) { block() })
    } catch (e: ProviderAdmissionDenied) {
      stop.value = RuntimeBudgetStop(id, e.reason, false)
      val eligibility = if (e.reason == "COOLDOWN") {
        listOfNotNull(job.provider, job.helperProvider).map { ledger.quotaEligibility(quota(ProviderId.valueOf(it))) }
          .maxOrNull()?.takeIf { it > System.currentTimeMillis() }
      } else null
      BudgetExecutionResult.Yielded(e.reason, eligibility)
    } catch (e: ProviderObserverFailure) {
      stop.value = RuntimeBudgetStop(id, "OBSERVER_STORAGE_FAILURE", true)
      BudgetExecutionResult.StorageFailure(e.attemptId)
    } finally {
      if (foreground) withContext(NonCancellable) { lock.withLock { ledger.setForeground(id, false); active.remove(id) } }
    }
  }

  /** Explicit helper identity, sharing execution/lease/priority and accounting. Cache hits never enter here. */
  suspend fun <T> helper(identity: ExactModel, block: suspend () -> T): T {
    val parent = currentCoroutineContext()[ProviderExecutionContext]
      ?: throw ProviderAdmissionDenied("EXECUTION_CONTEXT_REQUIRED")
    val adapter = parent.lifecycle as? Adapter ?: throw ProviderAdmissionDenied("APP_EXECUTION_CONTEXT_REQUIRED")
    if (adapter.lease != null) {
      val j = adapter.lease.job
      if (identity.provider.name != j.helperProvider || identity.model != j.helperModel) throw ProviderAdmissionDenied("HELPER_NOT_ALLOWED")
    }
    return withContext(context(parent.executionId, "image-helper", identity, adapter.lease, adapter.uncapped)) { block() }
  }

  private fun context(id: String, purpose: String, identity: ExactModel?, lease: JobLease?, uncapped: Boolean) =
    ProviderExecutionContext(id, purpose, Adapter(id, purpose, identity, lease, uncapped))

  private inner class Adapter(val id: String, val purpose: String, val identity: ExactModel?, val lease: JobLease?, val uncapped: Boolean) : ProviderAttemptLifecycle {
    override suspend fun beforeAttempt(attempt: ProviderAttempt) {
      val wire = attempt.dispatch
      if (attempt.modelCandidates.isEmpty() || attempt.modelCandidates.any { it != (identity?.model ?: wire.model) } ||
        (identity != null && (wire.provider != identity.provider || wire.model != identity.model))) {
        throw ProviderAdmissionDenied("MODEL_NOT_ALLOWED")
      }
      val bounds = boundsPolicy.bounds(attempt)
      val output = attempt.maxOutputTokens?.toLong()
      // Policy must explicitly cover reasoning/media; the encoded limit cannot have been dropped.
      val valid = bounds?.takeIf { it.inputTokens >= 0 && output != null && output > 0 && it.totalOutputTokens >= output }
      val result = ledger.reserve(AttemptReservation(attempt.attemptId, id, lease?.job?.id, lease?.job?.area ?: "interactive",
        wire.provider.name, wire.model, if (attempt.webSearch) "web-helper" else purpose, quota(wire.provider), lease?.job?.execution == "BACKGROUND",
        valid?.inputTokens, valid?.totalOutputTokens, jobFence = lease?.fence, jobOwner = lease?.owner,
        allowUncappedForeground = uncapped), System.currentTimeMillis())
      if (!result.admitted) throw ProviderAdmissionDenied(result.reason)
      lease?.let {
        val d = attempt.dispatch
        db.dispatches().insert(dev.pampa.pampai.core.assistant.db.DispatchAuditEntity(attemptId = attempt.attemptId,
          conversationId = it.job.conversationId ?: -1L, messageId = -1L, atMillis = System.currentTimeMillis(),
          provider = d.provider.name, model = d.model, requested = it.job.effort ?: "AUTO", resolved = d.resolved.name,
          effective = d.effective, support = d.support, adaptation = d.adaptation))
      }
    }

    override suspend fun afterAttempt(settlement: ProviderSettlement) {
      val now = System.currentTimeMillis()
      val r = settlement.rateLimit
      val observedDelay = listOfNotNull(settlement.retryAfterSec,
        r.resetRequestsSec?.takeIf { r.remainingRequests == 0 }, r.resetTokensSec?.takeIf { r.remainingTokens == 0 })
        .filter { it.isFinite() && it >= 0 }.maxOrNull()
      val rateLimited = settlement.httpStatus == 429
      if (observedDelay != null || rateLimited) {
        // Unknown reset: local bounded jitter, clearly labelled, never presented as provider reset.
        val delay = observedDelay?.times(1000)?.coerceAtMost((Long.MAX_VALUE - now).toDouble())?.toLong()
          ?: kotlin.random.Random.nextLong(30_000, 60_001)
        ledger.cooldown(quota(settlement.attempt.dispatch.provider), Math.addExact(now, delay),
          if (observedDelay != null) "OBSERVED_RETRY_ELIGIBILITY" else "LOCAL_BACKOFF_UNKNOWN_RESET")
      }
      val usage = settlement.usage
      val tokens = usage?.takeIf { it.promptTokens >= 0 && it.completionTokens >= 0 && it.totalTokens >= 0 }
        ?.let { maxOf(it.totalTokens.toLong(), it.promptTokens.toLong() + it.completionTokens) }
      // No configured currency/provenance authority exists yet. Money remains unknown, never zero.
      ledger.settle(settlement.attempt.attemptId, ObservedSettlement(settlement.attempt.attemptId,
        settlement.certainty.name, tokens, null, settlement.errorType))
    }
  }
  // Shared conservative bucket across key rotations: no secret/account identifiers persisted.
  private fun quota(provider: ProviderId) = "${provider.name}/configured-account"
}
