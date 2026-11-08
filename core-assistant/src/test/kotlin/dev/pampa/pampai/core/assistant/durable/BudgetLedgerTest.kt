package dev.pampa.pampai.core.assistant.durable

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.pampa.pampai.core.assistant.db.PampaiDatabase
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BudgetLedgerTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private fun memory() = Room.inMemoryDatabaseBuilder(context, PampaiDatabase::class.java).build()
  private fun request(id: String) = AttemptReservation(id, "execution", null, "music", "synthetic-provider", "exact-model", "synthesis", "provider/account", false, 20, 30)
  private suspend fun cap(l: BudgetLedger, requests: Long = 1, money: Long? = null) = l.configure(BudgetCap("global", false, 0, 10000, requests, 1000, money))
  @Test fun concurrentLastSlotHasExactlyOneWinner() = runBlocking {
    val db=memory(); try {
      val l=BudgetLedger(db); cap(l)
      val results=coroutineScope { (1..12).map { async(Dispatchers.Default) { l.reserve(request("attempt-$it"), 100) } }.awaitAll() }
      assertEquals(1, results.count { it.admitted })
      assertEquals(1, db.durable().attempts(0, 10000).size)
    } finally { db.close() }
  }
  @Test fun dispatchedUnknownSurvivesReopenAndCannotReleaseOnCancellation() = runBlocking {
    val name="budget-reopen-${System.nanoTime()}.db"
    var db=Room.databaseBuilder(context, PampaiDatabase::class.java, name).build()
    try {
      var l=BudgetLedger(db); cap(l); assertTrue(l.reserve(request("a"), 100).admitted)
      l.settle("a", ObservedSettlement("cancel", "MAY_HAVE_DISPATCHED", null, null, "cancelled"))
      db.close(); db=Room.databaseBuilder(context, PampaiDatabase::class.java, name).build(); l=BudgetLedger(db)
      assertEquals("UNCERTAIN", l.attempt("a")!!.state)
      assertEquals(50L, l.attempt("a")!!.chargedTokens)
      assertFalse(l.reserve(request("b"), 101).admitted)
      assertFalse(l.reserve(request("a"), 101).admitted)
    } finally { db.close(); context.deleteDatabase(name) }
  }
  @Test fun settlementIsIdempotentConflictsFailAndNotDispatchedReleases() = runBlocking {
    val db=memory(); try {
      val l=BudgetLedger(db); cap(l); l.reserve(request("a"), 100)
      val settlement=ObservedSettlement("no-wire", "NOT_DISPATCHED", null, null)
      assertEquals(l.settle("a", settlement), l.settle("a", settlement))
      assertTrue(l.reserve(request("b"), 101).admitted)
      try { l.settle("a", ObservedSettlement("no-wire", "RESPONSE_RECEIVED", 1, null)); fail("conflict accepted") } catch (_: IllegalArgumentException) { }
    } finally { db.close() }
  }
  @Test fun observedOverrunIsChargedAndNeverHiddenByError() = runBlocking {
    val db=memory(); try {
      val l=BudgetLedger(db); cap(l); l.reserve(request("a"), 100)
      val row=l.settle("a", ObservedSettlement("response", "RESPONSE_RECEIVED", 1200, 300, "provider_error"))
      assertEquals("OVERRUN", row.state); assertEquals(1200L, row.chargedTokens)
      assertEquals("provider_error", row.error)
      assertFalse(l.reserve(request("b"), 101).admitted)
    } finally { db.close() }
  }
  @Test fun moneyRequiresTrustedCurrentBoundedTariffAndChecksOverflow() = runBlocking {
    val db=memory(); try {
      val l=BudgetLedger(db, TariffValidator { it.id=="verified" }); cap(l, money=100)
      assertFalse(l.reserve(request("a"), 100).admitted)
      val t=TariffProof("verified", "synthetic-provider", "exact-model", 1000, 1, 1, 0, true)
      assertFalse(l.reserve(request("stale").copy(tariff=t.copy(expiresAt=100)), 100).admitted)
      assertFalse(l.reserve(request("unbounded").copy(tariff=t.copy(allFeaturesBounded=false)), 100).admitted)
      assertEquals("OVERFLOW", l.reserve(request("overflow").copy(tariff=t.copy(inputPerToken=Long.MAX_VALUE)), 100).reason)
      assertTrue(l.reserve(request("ok").copy(tariff=t), 100).admitted)
      assertEquals(50L, l.attempt("ok")!!.reservedMoney)
    } finally { db.close() }
  }
  @Test fun cooldownIsSharedPersistentAndForegroundBlocksBackground() = runBlocking {
    val db=memory(); try {
      val l=BudgetLedger(db); cap(l, 10)
      l.cooldown("provider/account", 500, "verified retry-after")
      l.cooldown("provider/account", 200, "shorter retry-after")
      assertEquals("COOLDOWN", l.reserve(request("a"), 499).reason)
      assertTrue(l.reserve(request("b"), 500).admitted)
      l.setForeground("interactive", true)
      assertEquals("FOREGROUND_PRIORITY", l.reserve(request("background").copy(background=true), 501).reason)
      l.setForeground("interactive", false)
      assertEquals("NO_BACKGROUND_WINDOW", l.reserve(request("disabled").copy(background=true), 501).reason)
      l.configure(BudgetCap("background", true, 0, 10000, 10, 1000, null))
      assertEquals("SETUP_REQUIRED", l.reserve(request("setup").copy(background=true), 501).reason)
    } finally { db.close() }
  }
  @Test fun observedUnderuseReducesTokensAndFailedUnobservedKeepsBounds() = runBlocking {
    val db=memory(); try {
      val l=BudgetLedger(db); cap(l, 10)
      l.reserve(request("one"), 100)
      assertEquals(3L, l.settle("one", ObservedSettlement("one-result", "RESPONSE_RECEIVED", 3, null)).chargedTokens)
      l.reserve(request("two"), 101)
      assertEquals(50L, l.settle("two", ObservedSettlement("two-result", "RESPONSE_RECEIVED", null, null, "HTTP429")).chargedTokens)
    } finally { db.close() }
  }
  @Test fun backgroundAreaCapAndLeaseFenceAreAuthoritativeAtEveryAttempt() = runBlocking {
    val db=memory(); try {
      val f=DurableFoundation(db)
      f.configure(AreaConfiguration("music", enabled=true, setupComplete=true, requestCap=1, tokenCap=1000, windowStart=0, windowEnd=10000), listOf(AreaModel("music", "GROQ", "exact-model")))
      val p=dev.pampa.pampai.core.assistant.runtime.ModelPolicy(dev.pampa.pampai.core.assistant.runtime.ExactModel(dev.antigravity.fluidengine.ai.provider.ProviderId.GROQ, "exact-model"))
      f.createJob("j", null, "music", "DIRECT", "BACKGROUND", "bounded synthetic task", p, emptyList(), listOf(ResearchStep("j", 0, "", "local")), 10, 100)
      val lease=requireNotNull(f.claim("j", "owner", 100, 100))
      val l=BudgetLedger(db); cap(l, 10); l.configure(BudgetCap("bg", true, 0, 10000, 10, 1000, null))
      val r=request("one").copy(provider="GROQ", background=true, jobId="j", jobFence=lease.fence, jobOwner=lease.owner)
      assertEquals("JOB_FENCE", l.reserve(r.copy(jobFence=lease.fence-1), 101).reason)
      val admitted=l.reserve(r, 101); assertTrue(admitted.reason, admitted.admitted)
      assertEquals("CAP_EXCEEDED", l.reserve(r.copy(attemptId="two"), 102).reason)
      f.cancel("j")
      assertEquals("JOB_FENCE", l.reserve(r.copy(attemptId="late"), 103).reason)
      assertEquals(50L, l.settle("one", ObservedSettlement("cancel", "MAY_HAVE_DISPATCHED", null, null)).chargedTokens)
    } finally { db.close() }
  }

}
