package dev.pampa.pampai.feature.assistant.profile

import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.pampa.pampai.core.assistant.durable.*
import dev.pampa.pampai.core.assistant.research.*
import dev.pampa.pampai.core.assistant.runtime.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

/** Fake service boundaries only; real production ViewModel validation and mutation order. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {
  private val dispatcher = UnconfinedTestDispatcher()
  private val profile = mockk<ResearchProfile>(relaxed = true)
  private val sources = mockk<ResearchSources>(relaxed = true)
  private val setup = mockk<ResearchSetup>(relaxed = true)
  private val foundation = mockk<DurableFoundation>(relaxed = true)
  private val models = mockk<ModelSelection>(relaxed = true)
  private val connections = mockk<ResearchConnections>()
  private val savedReads = MutableStateFlow(emptyList<ResearchRead>())
  private val items = MutableStateFlow(emptyList<ProfileItem>())
  private val areas = MutableStateFlow(emptyList<AreaConfiguration>())
  private val schedules = MutableStateFlow(emptyList<AreaScheduleStatus>())
  private lateinit var vm: ProfileViewModel
  private val candidate = SourceCandidate("fixture.fluidify", "fixture.authority", "fluidify", "account-fixture", null, true)
  private val source = SourceDraft(candidate, account = "account-fixture", section = "history_records", from = "2026-01-01", until = "2026-02-01")
  private val policy = ModelPolicy(ExactModel(ProviderId.GROQ, "fixture-model"))
  @Before fun before() {
    Dispatchers.setMain(dispatcher)
    every { connections.reads } returns savedReads
    every { profile.management } returns items
    every { profile.settings } returns areas
    every { profile.grants } returns MutableStateFlow(emptyList())
    every { profile.exclusions } returns MutableStateFlow(emptyList())
    every { setup.plans } returns MutableStateFlow(emptyList())
    every { setup.caps } returns MutableStateFlow(emptyList())
    coEvery { profile.exclusions() } returns emptyList()
    coEvery { foundation.grants() } returns emptyList()
    coEvery { setup.plan(any()) } returns null
    coEvery { setup.configuration(any()) } returns null
    vm = ProfileViewModel(profile, sources, models, setup, foundation, io.mockk.mockk<dev.pampa.pampai.core.assistant.research.ResearchScheduler>(relaxed = true) { every { statuses } returns schedules }, connections)
  }
  @After fun after() { androidx.lifecycle.ViewModelStore().apply { put("profile", vm); clear() }; Dispatchers.resetMain(); unmockkAll() }
  private fun valid() = AreaDraft("history_records", policy, listOf(source.read()), "8", "12000", "0", true,
    startDate = "2030-01-01", endDate = "2030-01-02", enabled = true)

  @Test fun restoredConnectionsRemainAvailableWithoutAnAutonomousPlan() = runTest {
    val read = source.read()
    savedReads.value = listOf(read)
    androidx.lifecycle.ViewModelStore().apply { put("old", vm); clear() }
    vm = ProfileViewModel(profile, sources, models, setup, foundation,
      mockk<ResearchScheduler>(relaxed = true) { every { statuses } returns schedules }, connections)
    assertEquals(listOf(read), vm.state.value.connectedReads)
    assertTrue(vm.state.value.plans.isEmpty())
    savedReads.value = emptyList()
    assertTrue(vm.state.value.connectedReads.isEmpty())
    coVerify(exactly = 0) { sources.connect(any(), any()) }
  }

  @Test fun defaultSetupCannotAuthorizeAndDoesNotAdoptChatModel() = runTest {
    vm.editArea("history_records").join()
    assertNull(vm.state.value.areaDraft?.policy)
    assertFalse(vm.state.value.areaDraft!!.enabled)
    vm.saveArea(AreaDraft("history_records")).join()
    coVerify(exactly = 0) { setup.savePlan(any()) }
    verify(exactly = 0) { models.choose(any(), any(), any()) }
  }
  @Test fun explicitSaveDisablesBeforePersistingExactPlanAndEnabling() = runTest {
    val draft = valid(); vm.saveArea(draft).join()
    coVerifyOrder {
      setup.configure(match { !it.enabled }, emptyList())
      setup.savePlan(match { it.policy() == policy && it.reads == draft.reads && it.profilingConsent })
      setup.configure(match { it.enabled && it.setupComplete && it.requestCap == 8L && it.moneyCap == null }, any())
    }
    coVerify(exactly = 0) { models.save(any(), any(), any()) }
  }
  @Test fun failedPlanWriteLeavesAreaDisabled() = runTest {
    coEvery { setup.savePlan(any()) } throws IllegalStateException("storage")
    vm.saveArea(valid()).join()
    coVerify { setup.configure(match { !it.enabled }, any()) }
    coVerify(exactly = 0) { setup.configure(match { it.enabled }, any()) }
    assertNotNull(vm.state.value.message)
  }
  @Test fun failedConnectDoesNotAdoptIdentityOrReportSuccess() = runTest {
    coEvery { sources.connect(any(), any()) } throws ResearchUnavailable("ACCOUNT_MISMATCH")
    var callback = false
    vm.connect(source, false) { callback = true }.join()
    assertFalse(callback); assertTrue(vm.state.value.connectedReads.isEmpty())
    assertTrue(vm.state.value.message!!.contains("nessun account"))
  }
  @Test fun mismatchedAccountRefusedBeforeService() = runTest {
    vm.connect(source.copy(account = "other-account"), false) {}.join()
    coVerify(exactly = 0) { sources.connect(any(), any()) }
  }
  @Test fun ordinaryRenewalAndRevokeUseSeparateServices() = runTest {
    vm.renew(listOf(source.read())).join()
    coVerify { sources.renew(listOf(source.read())) }
    coVerify(exactly = 0) { sources.connect(any(), any()) }
    vm.revoke(source.read().grantId).join()
    coVerify { sources.revoke(source.read().grantId) }
  }
  @Test fun correctionForgetAndReenablePreserveCanonicalScope() = runTest {
    val item = ProfileItem("fixture", "fluidify.history_records", "old", "INFERENCE", null, 1)
    vm.correct(item, "La mia correzione").join()
    coVerify { profile.correct(any(), item.field, "La mia correzione", null) }
    vm.forget(item).join(); coVerify { profile.forget(item.field, null, null) }
    vm.reenable("canonical-key").join(); coVerify { profile.reenable("canonical-key") }
    coVerify(exactly = 0) { sources.connect(any(), any()) }
    coVerify(exactly = 0) { setup.configure(any(), any()) }
  }
  @Test fun partialAndInvalidDeductionsRemainHonestAndLoadingEnds() = runTest {
    assertFalse(vm.state.value.loading)
    items.value = listOf(ProfileItem("p", "cv.grades", "Dato parziale", "INFERENCE", null, 1, status = "INVALID", limitations = "Copertura parziale"))
    assertEquals("INVALID", vm.state.value.items.single().status)
    assertTrue(statusLabel("INVALID").contains("non utilizzabile"))
    assertTrue(coverageLabel("PARTIAL_OR_UNKNOWN").contains("non verificata"))
    every { profile.management } returns flow { throw IllegalStateException("storage unavailable") }
    vm.reloadProfile()
    assertTrue(vm.state.value.loadFailed)
    assertFalse(vm.state.value.loading)
    every { profile.management } returns items
    vm.reloadProfile()
    assertFalse(vm.state.value.loadFailed)
  }
  @Test fun scopeBudgetAndPeriodAreExplicit() {
    assertEquals("2026-01-01", source.from)
    assertEquals("history_records", source.read().selection.area)
    assertEquals(1250000L, moneyUnits("1,25"))
    listOf(valid().copy(policy = null), valid().copy(reads = emptyList()), valid().copy(tokens = "0"), valid().copy(moneyDisabled = false)).forEach {
      assertTrue(runCatching { it.configuration() }.isFailure)
    }
    assertFalse(readableValue("{\"internal_key\":\"x\"}").contains("internal_key"))
  }
  @Test fun priorUnknownTrafficCannotBeHiddenInNewGlobalWindow() = runTest {
    vm.saveCap(BudgetCap("profile-global", false, 0, 10, 1, 100, null)).join()
    coVerify(exactly = 0) { setup.configureCap(any()) }
  }
  @Test fun sourceScopeUsesRealSchoolSectionsAndNeverAdoptsSchoolUiYear() {
    val school = SourceCandidate("fixture.cv", "fixture.cv.authority", "cv", "parent-fixture", "student-fixture", true)
    val selected = SourceDraft(school, "parent-fixture", "student-fixture", "2026-2027", "grades")
    val read = selected.read()
    assertEquals("grades", read.selection.area)
    assertEquals("2026-2027", read.selection.year)
    assertEquals("\"2026-2027\"", read.arguments["anno"].toString())
    assertEquals("20", read.arguments["limite"].toString())
    assertTrue(runCatching { selected.copy(year = "2026-2028").read() }.isFailure)
    assertTrue(runCatching { selected.copy(section = "voti").read() }.isFailure)
  }
  @Test fun queuedRevokeIsNotLostDuringPendingConnect() = runTest {
    val result = CompletableDeferred<SourceGrant>()
    coEvery { sources.connect(any(), any()) } coAnswers { result.await() }
    val connection = vm.connect(source, false) {}
    assertTrue(vm.state.value.busy)
    val revoke = vm.revoke(source.read().grantId)
    result.complete(SourceGrant(source.read().grantId, "fixture", "authority", "account-fixture", null, "history_records", null))
    connection.join(); revoke.join()
    coVerify { sources.revoke(source.read().grantId) }
    assertFalse(vm.state.value.busy)
  }
  @Test fun schedulerObservationDoesNotInventSuccessAndTranslatesSuspension() = runTest {
    schedules.value = listOf(AreaScheduleStatus("grades", ScheduleState.ENQUEUED, nextEligibleAt = 1234))
    assertEquals(ScheduleState.ENQUEUED, vm.state.value.schedules.single().state)
    assertNull(vm.state.value.schedules.single().lastSuccessAt)
    assertTrue(scheduleLabel(vm.state.value.schedules.single(), null).contains("Android"))
    schedules.value = listOf(AreaScheduleStatus("grades", ScheduleState.SUSPENDED, "RESULT_UNCERTAIN", lastAttemptAt = 1000))
    assertTrue(scheduleLabel(vm.state.value.schedules.single(), null).contains("nessuna ripetizione"))
    assertFalse(scheduleLabel(vm.state.value.schedules.single(), null).contains("RESULT_UNCERTAIN"))
  }
}
