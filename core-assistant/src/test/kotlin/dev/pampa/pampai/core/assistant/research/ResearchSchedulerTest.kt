package dev.pampa.pampai.core.assistant.research

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.testing.*
import dev.pampa.pampai.core.assistant.db.PampaiDatabase
import dev.pampa.pampai.core.assistant.durable.*
import dev.pampa.pampai.core.assistant.runtime.*
import dev.antigravity.fluidengine.ai.provider.ProviderId
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import javax.inject.Provider

class ScheduleStartupApplication : android.app.Application(), Configuration.Provider {
  var configurationReads = 0
  override val workManagerConfiguration: Configuration
    get() {
      configurationReads++
      return Configuration.Builder().setWorkerFactory(ResearchWorkerFactory(Provider { mockk(relaxed = true) })).build()
    }
}

@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class ResearchSchedulerTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private lateinit var db: PampaiDatabase
  private lateinit var foundation: DurableFoundation
  private lateinit var budgets: RuntimeBudgetCoordinator
  private lateinit var setup: ResearchSetup
  private lateinit var scheduler: ResearchScheduler
  private lateinit var store: ResearchScheduleStore
  private val runner = mockk<ResearchRunner>(relaxed = true)
  private val queue = Queue()
  private val area = "worker-${UUID.randomUUID()}"
  private val clock = object : ResearchClock { var at = System.currentTimeMillis(); override fun wall() = at; override fun tick() = 0L }
  private val policy = ModelPolicy(ExactModel(ProviderId.GROQ, "llama-fixture"))
  private val read get() = ResearchRead(SourceSelection("synthetic.music", "synthetic.music.tools", "fluidify", "account-A", area = area), "history_records",
    buildJsonObject { put("account", "account-A"); put("from", "0"); put("until", "1000") })
  private class Queue : ResearchWorkQueue {
    val enqueued = mutableListOf<Pair<SchedulePolicy, AreaScheduleStatus>>()
    val periodic = mutableListOf<SchedulePolicy>()
    val cancelled = mutableListOf<String>()
    override suspend fun periodic(policy: SchedulePolicy) { periodic += policy }
    override suspend fun enqueue(policy: SchedulePolicy, row: AreaScheduleStatus): String { enqueued += policy to row; return "work-${row.slot}" }
    override suspend fun cancel(area: String) { cancelled += area }
  }
  @Before fun before() {
    db = Room.inMemoryDatabaseBuilder(context, PampaiDatabase::class.java).build()
    foundation = DurableFoundation(db, SourceProofValidator { _, _ -> true })
    budgets = RuntimeBudgetCoordinator(BudgetLedger(db), db)
    setup = ResearchSetup(context, foundation, budgets)
    store = ResearchScheduleStore(context)
    scheduler = ResearchScheduler(setup, foundation, runner, budgets, store, queue, clock)
  }
  @After fun after() { db.close(); unmockkAll() }
  private suspend fun enabled(money: Long? = null) {
    foundation.putGrant(SourceGrant(read.grantId, "synthetic.music", "synthetic.music.tools", "account-A", null, area, null,
      enabled = true, generationProof = "proof", proofExpiresAt = clock.wall() + 600_000))
    setup.savePlan(AreaReadPlan(area, reads = listOf(read), provider = "GROQ", model = "llama-fixture", profilingConsent = true))
    setup.configure(AreaConfiguration(area, true, true, 3_600_000, requestCap = 10, tokenCap = 10000, moneyCap = money,
      windowStart = clock.wall() - 1000, windowEnd = clock.wall() + 86_400_000), listOf(AreaModel(area, "GROQ", "llama-fixture")))
    setup.configureCap(BudgetCap("global", false, clock.wall() - 1000, clock.wall() + 86_400_000, 100, 100000, null))
    setup.configureCap(BudgetCap("background", true, clock.wall() - 1000, clock.wall() + 86_400_000, 100, 100000, null))
  }
  private suspend fun job(): String {
    val id = UUID.randomUUID().toString()
    return foundation.createJob(id, null, area, "RESEARCH", "BACKGROUND", "synthetic", policy, listOf(read.grantId),
      listOf(ResearchStep(id, 0, read.grantId, "synthetic")), 100, clock.wall()).id
  }
  @Test fun absentAndDisabledStartupNeverEnqueueOrAcquire() = runBlocking {
    scheduler.reconcile()
    assertTrue(queue.enqueued.isEmpty()); assertTrue(queue.periodic.isEmpty())
    setup.configure(AreaConfiguration(area), emptyList()); scheduler.reconcile()
    assertTrue(queue.enqueued.isEmpty()); assertEquals(ScheduleState.OFF, store.get(area)!!.state)
    coVerify(exactly = 0) { runner.admitBackground(any(), any(), any()) }
  }
  @Test fun applicationOwnedObserverReconcilesDisabledStartupAndRuntimeChanges() = runBlocking {
    setup.configure(AreaConfiguration(area), emptyList())
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      scheduler.start(scope)
      withTimeout(5000) { store.statuses.first { rows -> rows.any { it.area == area && it.state == ScheduleState.OFF } } }
      assertTrue(queue.enqueued.isEmpty())
      enabled()
      withTimeout(5000) { store.statuses.first { rows -> rows.any { it.area == area && it.state == ScheduleState.ENQUEUED } } }
      setup.configure(setup.configuration(area)!!.copy(enabled = false), setup.models(area))
      withTimeout(5000) { store.statuses.first { rows -> rows.any { it.area == area && it.state == ScheduleState.OFF } } }
      coVerify(exactly = 0) { runner.admitBackground(any(), any(), any()) }
    } finally { scope.cancel() }
  }
  @Test fun persistedCadenceAndConstraintsArePassedToQueueAndReopened() = runBlocking {
    enabled(); scheduler.reconcile()
    val queued = queue.enqueued.single()
    assertEquals(3_600_000L, queued.first.configuration.cadenceMillis)
    assertTrue(queued.first.configuration.wifiOnly); assertTrue(queued.first.configuration.batteryNotLow)
    assertEquals(ScheduleState.ENQUEUED, ResearchScheduleStore(context).get(area)!!.state)
    assertNull(store.get(area)!!.lastSuccessAt)
    val adapter = AndroidResearchWorkQueue(context)
    assertEquals(NetworkType.UNMETERED, adapter.constraints(queued.first.configuration).requiredNetworkType)
    assertTrue(adapter.constraints(queued.first.configuration).requiresBatteryNotLow())
    assertEquals(NetworkType.CONNECTED, adapter.constraints(queued.first.configuration.copy(wifiOnly = false)).requiredNetworkType)
  }
  @Test fun changedModelAndDisableCancelOldGeneration() = runBlocking {
    enabled(); scheduler.reconcile(); val old = store.get(area)!!.generation
    setup.savePlan(setup.plan(area)!!.copy(model = "llama-other")); scheduler.reconcile()
    assertNotEquals(old, store.get(area)!!.generation)
    assertEquals("MODEL_NOT_ALLOWED", store.get(area)!!.reason)
    setup.configure(setup.configuration(area)!!.copy(enabled = false), setup.models(area)); scheduler.reconcile()
    assertEquals(ScheduleState.OFF, store.get(area)!!.state); assertTrue(queue.cancelled.contains(area))
  }
  @Test fun cooldownYieldsBeforeAdmissionAndUnknownSuspensionDoesNotRetry() = runBlocking {
    enabled(); scheduler.reconcile()
    BudgetLedger(db).cooldown("GROQ/configured-account", clock.wall() + 60_000, "observed")
    scheduler.execute(area, store.get(area)!!.generation)
    assertEquals("COOLDOWN", store.get(area)!!.reason)
    coVerify(exactly = 0) { runner.admitBackground(any(), any(), any()) }
    store.put(store.get(area)!!.copy(state = ScheduleState.SUSPENDED, reason = "RESULT_UNCERTAIN", nextEligibleAt = null))
    scheduler.reconcile(); scheduler.execute(area, store.get(area)!!.generation)
    assertEquals("RESULT_UNCERTAIN", store.get(area)!!.reason)
  }
  @Test fun exhaustedBudgetUsesOnlyAnAlreadyAuthorizedFutureWindow() = runBlocking {
    enabled()
    val now = clock.wall()
    setup.configureCap(BudgetCap("global", false, now - 1000, now + 1000, 1, 1000, null))
    setup.configureCap(BudgetCap("background", true, now - 1000, now + 1000, 1, 1000, null))
    setup.configureCap(BudgetCap("next-global", false, now + 1000, now + 10_000, 1, 1000, null))
    setup.configureCap(BudgetCap("next-background", true, now + 1000, now + 10_000, 1, 1000, null))
    db.durable().reserve(BudgetAttempt("used", "other", null, "other-area", "GROQ", "llama-fixture", "synthetic", "GROQ/configured-account",
      true, now, 1, null, chargedTokens = 1, chargedMoney = null))
    scheduler.reconcile(); scheduler.execute(area, store.get(area)!!.generation)
    assertEquals("CAP_EXCEEDED", store.get(area)!!.reason)
    assertEquals(now + 1000, store.get(area)!!.nextEligibleAt)
    coVerify(exactly = 0) { runner.admitBackground(any(), any(), any()) }
  }
  @Test fun moneyCapAndRevocationSuspendWithoutCalls() = runBlocking {
    enabled(100); scheduler.reconcile(); assertEquals("UNTRUSTED_OR_UNBOUNDED_TARIFF", store.get(area)!!.reason)
    foundation.revoke(read.grantId); scheduler.reconcile(); assertEquals("SOURCE_REVOKED", store.get(area)!!.reason)
    coVerify(exactly = 0) { runner.runBackgroundTranche(any()) }
  }
  @Test fun foregroundPriorityAndRecoveryKeepLiveOwnership() = runBlocking {
    enabled(); scheduler.reconcile()
    BudgetLedger(db).setForeground("older-process/request", true)
    budgets.foreground(policy) {
      scheduler.execute(area, store.get(area)!!.generation)
      assertEquals("FOREGROUND_PRIORITY", store.get(area)!!.reason)
      val abandoned = budgets.abandonedForeground()
      assertEquals(listOf("older-process/request"), abandoned)
      budgets.recoverAbandonedForeground(abandoned.toSet())
      assertTrue(budgets.hasForeground())
    }
    assertFalse(budgets.hasForeground())
    coVerify(exactly = 0) { runner.admitBackground(any(), any(), any()) }
  }
  @Test fun atomicBackgroundAdmissionAdoptsSameJobAndDoesNotResetCheckpoint() = runBlocking {
    enabled(); val id = job()
    val lease = foundation.claim(id, "owner", clock.wall(), 1000)!!
    foundation.commit(lease, foundation.steps(id).single().copy(outcome = "PARTIAL", cursor = "cursor", progress = 1), emptyList(), emptyList(), clock.wall())
    assertEquals(id, job()); assertEquals("cursor", foundation.steps(id).single().cursor)
    assertEquals(1L, foundation.steps(id).single().progress)
    assertEquals(id, foundation.backgroundJob(area)!!.id)
  }
  @Test fun orphanJobIsResumedAndCompletionSchedulesCadenceOnce() = runBlocking {
    enabled(); scheduler.reconcile(); val id = job()
    coEvery { runner.runBackgroundTranche(id) } returns TrancheResult.Completed(id, null)
    scheduler.execute(area, store.get(area)!!.generation)
    assertEquals(clock.wall(), store.get(area)!!.lastSuccessAt)
    assertEquals(clock.wall() + 3_600_000, store.get(area)!!.nextEligibleAt)
    scheduler.execute(area, store.get(area)!!.generation)
    coVerify(exactly = 1) { runner.runBackgroundTranche(id) }
    coVerify(exactly = 0) { runner.admitBackground(any(), any(), any()) }
  }
  @Test fun unexpiredClaimIsDeferredAndExpiredClaimCanResume() = runBlocking {
    enabled(); scheduler.reconcile(); val id = job(); foundation.claim(id, "dead-process", clock.wall(), 1000)
    scheduler.execute(area, store.get(area)!!.generation)
    assertEquals("CLAIMED", store.get(area)!!.reason)
    clock.at += 1001
    coEvery { runner.runBackgroundTranche(id) } returns TrancheResult.Progress(id)
    scheduler.execute(area, store.get(area)!!.generation)
    coVerify(exactly = 1) { runner.runBackgroundTranche(id) }
  }
  @Test fun realWorkerFactoryBuildsAndUsesOnlyBackgroundExecutor() = runBlocking {
    enabled(); scheduler.reconcile(); val actual = mockk<ResearchScheduler>(relaxed = true)
    val factory = ResearchWorkerFactory(Provider { actual })
    val worker = TestListenableWorkerBuilder<ResearchWorker>(context).setWorkerFactory(factory)
      .setInputData(workDataOf("area" to area, "generation" to "fixture")).build()
    assertEquals(ListenableWorker.Result.success(), worker.doWork())
    coVerify(exactly = 1) { actual.execute(area, "fixture") }
  }
  @Test fun uncertainDispatchSurvivesRecoveryWithoutSynthesisReplay() = runBlocking {
    enabled(); scheduler.reconcile(); val id = job()
    db.durable().reserve(BudgetAttempt("attempt", "dead-process", id, area, "GROQ", "llama-fixture", "synthesis", "GROQ/configured-account",
      true, clock.wall(), 2000, null, chargedTokens = 2000, chargedMoney = null))
    scheduler.execute(area, store.get(area)!!.generation)
    assertEquals("RESULT_UNCERTAIN", store.get(area)!!.reason)
    assertEquals(2000L, db.durable().attempt("attempt")!!.chargedTokens)
    coVerify(exactly = 0) { runner.runBackgroundTranche(any()) }
  }
  @Test fun eligibilityAndAdoptionExcludeForegroundJobs() = runBlocking {
    enabled(); val bg = job(); val fg = UUID.randomUUID().toString()
    foundation.createJob(fg, null, "interactive", "RESEARCH", "FOREGROUND", "synthetic foreground", policy, listOf(read.grantId),
      listOf(ResearchStep(fg, 0, read.grantId, "synthetic")), 100, clock.wall())
    assertEquals(listOf(bg), foundation.eligibleBackground(clock.wall(), -1, "", 20).map { it.id })
    assertEquals(bg, foundation.backgroundJob(area)!!.id)
  }
  @Test fun cancellationBeforeDispatchPreservesCheckpointAndSchedulesSafeResume() = runBlocking {
    enabled(); scheduler.reconcile(); val id = job()
    coEvery { runner.runBackgroundTranche(id) } throws CancellationException("synthetic stop")
    try { scheduler.execute(area, store.get(area)!!.generation); fail() } catch (_: CancellationException) { }
    assertEquals("INTERRUPTED_SAFE", store.get(area)!!.reason)
    assertNull(store.get(area)!!.lastSuccessAt); assertEquals(clock.wall() + 10_000, store.get(area)!!.nextEligibleAt)
    assertEquals(id, store.get(area)!!.jobId)
  }
  @Test fun foregroundContextStopsActualSourceTransportBeforeDiscoveryWire() = runBlocking {
    val transport = mockk<ResearchTransport>(relaxed = true)
    val cache = VerifiedSourceReceiptCache(clock)
    val sources = ResearchSources(transport, foundation, cache, clock, ResearchConnections(context, foundation))
    try {
      withContext(BackgroundResearchGuard { throw ResearchUnavailable("FOREGROUND_PRIORITY") }) { sources.discover() }
      fail()
    } catch (e: ResearchUnavailable) { assertEquals("FOREGROUND_PRIORITY", e.code) }
    coVerify(exactly = 0) { transport.hosts() }
    coVerify(exactly = 0) { transport.call(any(), any(), any()) }
  }
  @Test fun cancellationAfterPossibleDispatchKeepsChargeAndRequiresExplicitReconciliation() = runBlocking {
    enabled(); scheduler.reconcile(); val id = job()
    coEvery { runner.runBackgroundTranche(id) } coAnswers {
      db.durable().reserve(BudgetAttempt("cancelled-wire", "old-process", id, area, "GROQ", "llama-fixture", "synthesis", "GROQ/configured-account",
        true, clock.wall(), 2000, null, chargedTokens = 2000, chargedMoney = null))
      throw CancellationException("synthetic possible dispatch")
    }
    try { scheduler.execute(area, store.get(area)!!.generation); fail() } catch (_: CancellationException) { }
    assertEquals("INTERRUPTED", store.get(area)!!.reason)
    assertNull(store.get(area)!!.nextEligibleAt)
    assertEquals(2000L, db.durable().attempt("cancelled-wire")!!.chargedTokens)
  }
  @Test fun realWorkManagerPersistsUniqueConstraintsAndWorkerFactory() = runBlocking {
    val actual = mockk<ResearchScheduler>(relaxed = true)
    val factory = ResearchWorkerFactory(Provider { actual })
    WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setWorkerFactory(factory).build())
    enabled(); val p = scheduler.policy(area)
    val adapter = AndroidResearchWorkQueue(context)
    val row = AreaScheduleStatus(area, generation = p.generation, nextEligibleAt = System.currentTimeMillis() + 3_600_000)
    adapter.periodic(p)
    val id = adapter.enqueue(p, row)
    assertEquals(id, adapter.enqueue(p, row))
    val info = WorkManager.getInstance(context).getWorkInfoById(UUID.fromString(id)).get()!!
    assertEquals(WorkInfo.State.ENQUEUED, info.state)
    assertEquals(NetworkType.UNMETERED, info.constraints.requiredNetworkType)
    adapter.cancel(area)
    assertEquals(WorkInfo.State.CANCELLED, WorkManager.getInstance(context).getWorkInfoById(UUID.fromString(id)).get()!!.state)
  }
  @Test @Config(application = ScheduleStartupApplication::class)
  fun onDemandInitializationUsesApplicationConfigurationAndCustomFactory() {
    val app = context as ScheduleStartupApplication
    assertNotNull(WorkManager.getInstance(context))
    assertTrue(app.configurationReads > 0)
    assertTrue(app.workManagerConfiguration.workerFactory is ResearchWorkerFactory)
  }
  @Test fun persistedRunningMarkerIsReconciledWithoutLosingCheckpoint() = runBlocking {
    enabled(); scheduler.reconcile(); val id = job()
    store.put(store.get(area)!!.copy(state = ScheduleState.RUNNING, jobId = id))
    scheduler.reconcile()
    assertEquals(ScheduleState.ENQUEUED, store.get(area)!!.state)
    assertEquals(id, store.get(area)!!.jobId)
    assertEquals(1L, store.get(area)!!.slot)
  }
  @Test fun fieldAndRecordExclusionsSuspendOnlyTheRelatedAreaAfterReopen() = runBlocking {
    enabled()
    val school = ResearchRead(SourceSelection("school", "school.tools", "cv", "school-account", "student", "2026-2027", "grades"),
      "registro_dati", buildJsonObject { put("account", "school-account"); put("studente", "student"); put("anno", "2026-2027"); put("sezione", "grades") })
    foundation.putGrant(SourceGrant(school.grantId, "school", "school.tools", "school-account", "student", "grades", "2026-2027",
      enabled = true, generationProof = "school-proof", proofExpiresAt = clock.wall() + 600_000))
    setup.savePlan(AreaReadPlan("grades", reads = listOf(school), provider = "GROQ", model = "llama-fixture", profilingConsent = true))
    setup.configure(AreaConfiguration("grades", true, true, requestCap = 10, tokenCap = 10000,
      windowStart = clock.wall() - 1000, windowEnd = clock.wall() + 86_400_000), listOf(AreaModel("grades", "GROQ", "llama-fixture")))
    foundation.forget("fluidify.history_records", read.grantId, "record-1")
    scheduler.reconcile()
    assertEquals("EXCLUDED", store.get(area)!!.reason)
    assertEquals(ScheduleState.ENQUEUED, store.get("grades")!!.state)
    val reopened = ResearchScheduler(setup, DurableFoundation(db, SourceProofValidator { _, _ -> true }), runner, budgets,
      ResearchScheduleStore(context), queue, clock)
    assertNull(reopened.policy("grades").reason); assertEquals("EXCLUDED", reopened.policy(area).reason)
    foundation.exclude(ProfileExclusion("broad", null, null, null, true))
    reopened.reconcile(); assertEquals("EXCLUDED", store.get("grades")!!.reason)
    coVerify(exactly = 0) { runner.admitBackground(any(), any(), any()) }
  }

}
