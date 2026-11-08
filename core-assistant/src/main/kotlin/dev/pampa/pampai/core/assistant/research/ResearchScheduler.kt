package dev.pampa.pampai.core.assistant.research

import dev.pampa.pampai.core.assistant.durable.*
import dev.pampa.pampai.core.assistant.runtime.RuntimeBudgetCoordinator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

data class SchedulePolicy(val configuration: AreaConfiguration, val plan: AreaReadPlan?, val generation: String,
  val reason: String?, val eligibleAt: Long? = null)

/** Android adapter is injectable; pure domain consumers never depend on WorkInfo. */
interface ResearchWorkQueue {
  suspend fun periodic(policy: SchedulePolicy)
  suspend fun enqueue(policy: SchedulePolicy, row: AreaScheduleStatus): String
  suspend fun cancel(area: String)
  suspend fun running(workId: String?): Boolean = false
  suspend fun terminal(workId: String?): Boolean = false
}

@Singleton class ResearchScheduler @Inject constructor(
  private val setup: ResearchSetup, private val foundation: DurableFoundation,
  private val runner: ResearchRunner, private val budgets: RuntimeBudgetCoordinator,
  private val store: ResearchScheduleStore, private val queue: ResearchWorkQueue, private val clock: ResearchClock,
) {
  val statuses get() = store.statuses
  private val mutex = Mutex()
  private var observer: Job? = null

  /** Called once by the application's owned scope, after Hilt injection. No autonomous source discovery. */
  @Synchronized fun start(scope: CoroutineScope) {
    if (observer != null) return
    observer = scope.launch {
      // This application has one default process and no remote runtime service. Coordinator excludes
      // every marker bearing this live process identity; reservations are deliberately untouched.
      budgets.recoverAbandonedForeground(budgets.abandonedForeground().toSet())
      combine(setup.areas, setup.plans, setup.caps, foundation.grantsFlow, foundation.exclusionsFlow) { _, _, _, _, _ -> Unit }
        .collect { try { reconcile() } catch (e: CancellationException) { throw e } catch (_: Exception) {
          // The next policy emission/startup retries local reconciliation, never source/model dispatch.
        } }
    }
  }

  internal suspend fun policy(area: String): SchedulePolicy {
    val c = setup.configuration(area) ?: AreaConfiguration(area)
    val p = setup.plan(area)
    val caps = setup.caps.first()
    val grants = foundation.grants()
    val exclusions = foundation.exclusions()
    val models = setup.models(area)
    val now = clock.wall()
    val generation = digest(c.toString() + p.toString() + models.toString() + caps.toString() +
      grants.map { listOf(it.id, it.version, it.enabled, it.generationProof) }.toString() + exclusions.toString() + foundation.policyEpoch())
    val reason = when {
      !c.enabled -> "DISABLED"
      !c.setupComplete || p == null || !p.profilingConsent || p.model.isBlank() || p.reads.isEmpty() -> "SETUP_REQUIRED"
      runCatching { p.policy(); p.reads.forEach { ResearchReadPolicy.check(it) }; true }.getOrDefault(false).not() -> "INVALID_PLAN"
      p.area != area || p.reads.any { it.selection.area != area } || p.maxRecords !in 1..10000 -> "INVALID_PLAN"
      models.none { it.provider == p.provider && it.model == p.model } ||
        (p.helperModel != null && models.none { it.provider == p.helperProvider && it.model == p.helperModel }) -> "MODEL_NOT_ALLOWED"
      p.reads.any { r -> grants.none { it.id == r.grantId && it.enabled } } -> "SOURCE_REVOKED"
      p.reads.any { !foundation.readAllowed(it) } -> "EXCLUDED"
      c.cadenceMillis < 900_000 || c.windowEnd <= c.windowStart || c.requestCap <= 0 || c.tokenCap <= 0 -> "BACKGROUND_LIMITS_REQUIRED"
      caps.none { !it.backgroundOnly && it.end > now } || caps.none { it.backgroundOnly && it.end > now } -> "NO_BUDGET_WINDOW"
      c.windowEnd <= now -> "WINDOW_EXPIRED"
      caps.filter { it.end > now }.any { it.money != null } || c.moneyCap != null -> "UNTRUSTED_OR_UNBOUNDED_TARIFF"
      else -> null
    }
    if (reason != null) return SchedulePolicy(c, p, generation, reason)
    val active = caps.filter { now >= it.start && now < it.end }
    val next = maxOf(now, c.windowStart,
      caps.filter { !it.backgroundOnly && it.end > now }.minOf { it.start },
      caps.filter { it.backgroundOnly && it.end > now }.minOf { it.start })
    if (next >= c.windowEnd) return SchedulePolicy(c, p, generation, "NO_BUDGET_WINDOW")
    if (now < next || active.none { !it.backgroundOnly } || active.none { it.backgroundOnly })
      return SchedulePolicy(c, p, generation, "FUTURE_WINDOW", next)
    if (active.any { it.requests <= 0 || it.tokens <= 0 }) return SchedulePolicy(c, p, generation, "CAP_EXCEEDED")
    return SchedulePolicy(c, p, generation, null)
  }

  suspend fun reconcile() = mutex.withLock {
    val areas = (setup.areas.first().map { it.area } + store.all().map { it.area }).distinct()
    for (area in areas) reconcileArea(policy(area))
  }

  internal suspend fun recordFailure(area: String) = mutex.withLock {
    val row = store.get(area) ?: AreaScheduleStatus(area)
    store.put(row.copy(state = ScheduleState.SUSPENDED, reason = "STORAGE_OR_SOURCE_UNAVAILABLE", nextEligibleAt = null))
  }

  private suspend fun reconcileArea(p: SchedulePolicy): AreaScheduleStatus {
    val old = store.get(p.configuration.area) ?: AreaScheduleStatus(p.configuration.area)
    val changed = old.generation != p.generation
    var row = old
    if (changed) {
      queue.cancel(row.area)
      row.jobId?.let { runner.cancel(it) }
      row = old.copy(generation = p.generation, jobId = null, slot = 0, workId = null, operations = 0,
        nextEligibleAt = clock.wall(), state = ScheduleState.WAITING, reason = null)
    }
    if (p.reason != null && p.reason != "FUTURE_WINDOW") {
      queue.cancel(row.area)
      row.jobId?.let { runner.cancel(it) }
      row = row.copy(state = if (p.reason == "DISABLED") ScheduleState.OFF else ScheduleState.SUSPENDED,
        reason = p.reason, nextEligibleAt = null, workId = null)
      store.put(row); return row
    }
    queue.periodic(p)
    // Unknown terminal suspensions never retry simply because the periodic safety wake fired.
    if (!changed && row.state == ScheduleState.SUSPENDED && row.nextEligibleAt == null) return row
    if ((row.state == ScheduleState.RUNNING && !queue.running(row.workId)) ||
      (row.state == ScheduleState.ENQUEUED && queue.terminal(row.workId))) {
      // A process can die after persisting RUNNING and before persisting its successor. Allocate
      // a new slot rather than reusing the completed/cancelled WorkRequest's primary key.
      row = row.copy(state = ScheduleState.WAITING, slot = row.slot + 1, workId = null)
    }
    if (p.eligibleAt != null) row = row.copy(nextEligibleAt = maxOf(row.nextEligibleAt ?: 0, p.eligibleAt))
    if (row.state != ScheduleState.RUNNING) {
      if (row.nextEligibleAt == null) row = row.copy(nextEligibleAt = clock.wall())
      row = schedule(p, row)
    }
    return row
  }

  private suspend fun schedule(p: SchedulePolicy, row: AreaScheduleStatus): AreaScheduleStatus {
    // Persist intent first. A periodic wake/startup repairs death before enqueue.
    store.put(row.copy(state = ScheduleState.WAITING, reason = row.reason))
    val id = queue.enqueue(p, row)
    val next = row.copy(state = ScheduleState.ENQUEUED, workId = id)
    store.put(next); return next
  }

  /** Exactly one durable tranche, no loop, no foreground or temporary entry point. */
  suspend fun execute(area: String, generation: String) = mutex.withLock {
    val p = policy(area)
    if (generation != p.generation) { reconcileArea(p); return@withLock }
    var row = store.get(area) ?: return@withLock
    if (p.reason != null) { reconcileArea(p); return@withLock }
    if (row.state == ScheduleState.SUSPENDED && row.nextEligibleAt == null) return@withLock
    val now = clock.wall()
    if ((row.nextEligibleAt ?: 0) > now) return@withLock
    if (budgets.hasForeground()) {
      schedule(p, row.copy(slot = row.slot + 1, reason = "FOREGROUND_PRIORITY", nextEligibleAt = now + 60_000)); return@withLock
    }
    val cooldown = listOfNotNull(p.plan?.provider, p.plan?.helperProvider).mapNotNull { foundation.providerEligibility(it) }.maxOrNull()
    if (cooldown != null && cooldown > now) {
      schedule(p, row.copy(slot = row.slot + 1, reason = "COOLDOWN", nextEligibleAt = cooldown)); return@withLock
    }
    var job = row.jobId?.let { foundation.job(it) } ?: foundation.backgroundJob(area)
    if (job?.execution != null && job.execution != "BACKGROUND") return@withLock
    if (job?.state == "CANCELLED") {
      store.put(row.copy(state = ScheduleState.SUSPENDED, reason = "JOB_CANCELLED", nextEligibleAt = null)); return@withLock
    }
    if (job?.state == "DONE") {
      complete(p, row); return@withLock
    }
    if (job != null && job.owner != null && job.leaseUntil > now) {
      schedule(p, row.copy(jobId = job.id, slot = row.slot + 1, nextEligibleAt = job.leaseUntil, reason = "CLAIMED")); return@withLock
    }
    if (job != null && job.eligibleAt > now) {
      if (job.eligibleAt == Long.MAX_VALUE) store.put(row.copy(jobId = job.id, state = ScheduleState.SUSPENDED, reason = "ELIGIBILITY_UNKNOWN", nextEligibleAt = null))
      else schedule(p, row.copy(jobId = job.id, slot = row.slot + 1, nextEligibleAt = job.eligibleAt, reason = "COOLDOWN"))
      return@withLock
    }
    val completedSynthesis = job?.let { foundation.steps(it.id).lastOrNull()?.outcome == "COMPLETE" } == true
    val budgetReason = if (completedSynthesis) null else foundation.backgroundBudgetReason(area, now)
    if (budgetReason != null) {
      val eligible = futureBudgetEligibility(p, now)
      if (eligible != null) schedule(p, row.copy(jobId = job?.id, slot = row.slot + 1, reason = budgetReason, nextEligibleAt = eligible))
      else store.put(row.copy(jobId = job?.id, state = ScheduleState.SUSPENDED, reason = budgetReason, nextEligibleAt = null))
      return@withLock
    }
    if (row.operations >= 16384) {
      store.put(row.copy(state = ScheduleState.SUSPENDED, reason = "TRANCHE_LIMIT", nextEligibleAt = null)); return@withLock
    }
    row = row.copy(state = ScheduleState.RUNNING, reason = null, lastAttemptAt = now)
    store.put(row)
    try {
      withTimeout(150_000) {
        if (job == null) {
          val id = runner.admitBackground(area, "Aggiorna il profilo per la sola area autorizzata $area: conserva osservazioni e deduzioni fondate sulle evidenze, con periodo, copertura e limiti.", p.plan!!.maxRecords)
          job = foundation.job(id); row = row.copy(jobId = id); store.put(row)
        }
        val current = requireNotNull(job)
        // A completed synthesis checkpoint can be finalized without dispatch. Any earlier attempt
        // without that checkpoint may already have incurred usage: require explicit reconciliation.
        val steps = foundation.steps(current.id)
        if (steps.lastOrNull()?.outcome != "COMPLETE" && foundation.hasDispatchedAttempts(current.id)) {
          store.put(row.copy(state = ScheduleState.SUSPENDED, reason = "RESULT_UNCERTAIN", nextEligibleAt = null)); return@withTimeout
        }
        when (val result = runner.runBackgroundTranche(current.id)) {
          is TrancheResult.Completed -> complete(p, row)
          is TrancheResult.Progress -> schedule(p, row.copy(operations = row.operations + 1, slot = row.slot + 1, nextEligibleAt = clock.wall(), reason = null))
          is TrancheResult.Suspended -> {
            var eligible = result.eligibleAt ?: if (result.code == "FOREGROUND_PRIORITY") clock.wall() + 60_000 else null
            if (eligible == null && result.code in setOf("CAP_EXCEEDED", "NO_GLOBAL_WINDOW", "NO_BACKGROUND_WINDOW", "UNKNOWN_PRIOR_TOKENS")) {
              val future = futureBudgetEligibility(p, clock.wall())
              if (future != null && foundation.scheduleBackgroundEligibility(current.id, future)) eligible = future
            }
            val stopped = row.copy(reason = result.code, nextEligibleAt = eligible, state = ScheduleState.SUSPENDED)
            if (eligible != null && eligible != Long.MAX_VALUE) schedule(p, stopped.copy(slot = row.slot + 1)) else store.put(stopped.copy(nextEligibleAt = null))
          }
        }
      }
    } catch (cancel: CancellationException) {
      withContext(NonCancellable) {
        val current = row.jobId?.let { foundation.job(it) } ?: foundation.backgroundJob(area)
        val safe = current == null || !foundation.hasDispatchedAttempts(current.id) ||
          foundation.steps(current.id).lastOrNull()?.outcome == "COMPLETE"
        val latest = policy(area)
        if (safe && latest.generation == p.generation && latest.reason == null) {
          schedule(p, row.copy(jobId = current?.id, slot = row.slot + 1, reason = "INTERRUPTED_SAFE",
            nextEligibleAt = maxOf(clock.wall() + 10_000, current?.leaseUntil ?: 0)))
        } else store.put(row.copy(state = ScheduleState.SUSPENDED, reason = "INTERRUPTED", nextEligibleAt = null))
      }
      throw cancel
    } catch (e: Exception) {
      store.put(row.copy(state = ScheduleState.SUSPENDED, reason = (e as? ResearchUnavailable)?.code ?: "STORAGE_OR_SOURCE_UNAVAILABLE", nextEligibleAt = null))
    }
  }

  /** Only already persisted, explicitly authorized future windows can provide eligibility.
   * No recurring-window consent exists, so no policy window is manufactured or replenished. */
  private suspend fun futureBudgetEligibility(p: SchedulePolicy, now: Long): Long? {
    val c = p.configuration
    val boundaries = setup.caps.first().flatMap { listOf(it.start, it.end) }.plus(c.windowStart)
      .filter { it > now && it < c.windowEnd }.distinct().sorted().take(256)
    return boundaries.firstOrNull { foundation.backgroundBudgetReason(c.area, it) == null }
  }

  private suspend fun complete(p: SchedulePolicy, row: AreaScheduleStatus) {
    val now = clock.wall()
    schedule(p, row.copy(state = ScheduleState.COMPLETED, lastSuccessAt = now, nextEligibleAt =
      Math.addExact(now, p.configuration.cadenceMillis), jobId = null, operations = 0, slot = row.slot + 1, reason = null))
  }
}
