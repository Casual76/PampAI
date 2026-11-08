package dev.pampa.pampai.core.assistant.runtime

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.antigravity.fluidengine.ai.net.*
import dev.antigravity.fluidengine.ai.provider.*
import dev.pampa.pampai.core.assistant.db.PampaiDatabase
import dev.pampa.pampai.core.assistant.durable.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual engine wire lifecycle + app coordinator + Room. Local synthetic transport only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RuntimeBudgetIntegrationTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val identity = ExactModel(ProviderId.GROQ, "exact")
  private val policy get() = ModelPolicy(identity)
  private fun memory() = Room.inMemoryDatabaseBuilder(context, PampaiDatabase::class.java).build()
  private inline fun <T> PampaiDatabase.useDatabase(block: (PampaiDatabase) -> T): T = try { block(this) } finally { close() }
  private fun coordinator(db: PampaiDatabase) = RuntimeBudgetCoordinator(BudgetLedger(db), db)
  private fun provider(server: TinyHttpServer, extraCandidate: Boolean = false) = object : OpenAiCompatProvider(AiHttp("fixture"), server.base, "fixture") {
    override val id = ProviderId.GROQ
    override fun JsonObjectBuilder.providerFields(request: ChatRequest, stream: Boolean, dropped: Set<String>) {
      if (extraCandidate) put("models", buildJsonArray { add("exact"); add("unauthorized") })
    }
    override suspend fun listModels() = ModelCatalogue(emptyList(), emptyList())
    override suspend fun transcribe(audio: File, mime: String, options: TranscribeOptions): Transcript = error("unused")
  }
  private fun request(model: String = "exact") = ChatRequest(model, listOf(Message.User("synthetic")), maxOutputTokens = 40)
  private val ok = """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}"""
  private suspend fun cap(c: RuntimeBudgetCoordinator, requests: Long = 10, money: Long? = null) = c.configure(
    BudgetCap("global", false, 0, Long.MAX_VALUE, requests, 10000, money))
  private fun bounds(c: RuntimeBudgetCoordinator) = c.configureBounds(RuntimeAttemptPolicy { RuntimeAttemptBounds(100, 40) })
  private suspend fun denied(reason: String, block: suspend () -> Unit) {
    try { block(); fail("dispatch admitted") } catch (e: ProviderAdmissionDenied) { assertEquals(reason, e.reason) }
  }

  @Test fun ordinaryPreSetupChatIsUncappedButStillSettled() = runBlocking {
    memory().useDatabase { db -> TinyHttpServer().use { server ->
      server.handle("/chat/completions") { _, r -> r.reply(200, ok) }
      val c = coordinator(db)
      c.foreground(policy) { provider(server).complete(request()) }
      val row = db.durable().attempts(0, Long.MAX_VALUE).single()
      assertEquals("UNCAPPED_OBSERVED", row.state); assertEquals(5L, row.chargedTokens)
      assertNull(row.chargedMoney); assertEquals(0, db.durable().foregroundCount())
    } }
  }

  @Test fun boundsAndMoneyRefuseBeforeTransportAndPreservePriorityCleanup() = runBlocking {
    memory().useDatabase { db -> TinyHttpServer().use { server ->
      val calls = AtomicInteger(); server.handle("/chat/completions") { _, r -> calls.incrementAndGet(); r.reply(200, ok) }
      val c = coordinator(db); cap(c)
      denied("UNBOUNDED_INPUT") { c.foreground(policy) { provider(server).complete(request()) } }
      bounds(c); cap(c, money = 10000)
      denied("UNTRUSTED_OR_UNBOUNDED_TARIFF") { c.foreground(policy) { provider(server).complete(request()) } }
      assertEquals(0, calls.get()); assertEquals(0, db.durable().foregroundCount())
    } }
  }

  @Test fun droppedOutputLimitCannotSpendSecondWire() = runBlocking {
    memory().useDatabase { db -> TinyHttpServer().use { server ->
      val calls = AtomicInteger(); server.handle("/chat/completions") { _, r ->
        calls.incrementAndGet(); r.reply(400, """{"error":{"message":"unsupported max_completion_tokens"}}""")
      }
      val c = coordinator(db); cap(c); bounds(c)
      denied("UNBOUNDED_INPUT") { c.foreground(policy) { provider(server).complete(request()) } }
      assertEquals(1, calls.get()); assertEquals(1, db.durable().attempts(0, Long.MAX_VALUE).size)
      assertEquals(140L, db.durable().attempts(0, Long.MAX_VALUE).single().chargedTokens)
    } }
  }

  @Test fun concurrentLastSlotAllowsExactlyOneActualWire() = runBlocking {
    memory().useDatabase { db -> TinyHttpServer().use { server ->
      val calls = AtomicInteger(); server.handle("/chat/completions") { _, r -> calls.incrementAndGet(); r.reply(200, ok) }
      val c = coordinator(db); cap(c, 1); bounds(c)
      val results = coroutineScope { (1..8).map { async(Dispatchers.Default) {
        try { c.foreground(policy) { provider(server).complete(request()) }; true } catch (_: ProviderAdmissionDenied) { false }
      } }.awaitAll() }
      assertEquals(1, results.count { it }); assertEquals(1, calls.get()); assertEquals(0, db.durable().foregroundCount())
    } }
  }

  @Test fun helperCacheAndChildRouterWebContextsShareExecution() = runBlocking {
    memory().useDatabase { db -> TinyHttpServer().use { server ->
      val calls = AtomicInteger(); server.handle("/chat/completions") { _, r -> calls.incrementAndGet(); r.reply(200, ok) }
      val c = coordinator(db); val p = provider(server); val cache = ImageInterpretationCache()
      val helper = ExactModel(ProviderId.GROQ, "helper")
      val selected = policy.copy(helper = helper)
      val image = listOf(ContentPart.Image(byteArrayOf(1), "image/jpeg"))
      c.foreground(selected) {
        coroutineScope { async { p.complete(request()) }.await() } // router child
        repeat(2) { cache.interpret(image, selected) { h, _ -> c.helper(h) { p.complete(request(h.model)).message.text!! } } }
        p.complete(request().copy(webSearch = true))
      }
      c.foreground(policy, "title") { p.complete(request()) }
      val rows = db.durable().attempts(0, Long.MAX_VALUE)
      assertEquals(4, calls.get()); assertEquals(1, rows.count { it.purpose == "image-helper" })
      assertEquals(1, rows.count { it.purpose == "web-helper" }); assertEquals(1, rows.count { it.purpose == "title" })
      assertEquals(1, rows.filter { it.purpose != "title" }.map { it.executionId }.distinct().size)
    } }
  }

  @Test fun wrongExactModelIsTerminalBeforeSocket() = runBlocking {
    memory().useDatabase { db -> TinyHttpServer().use { server ->
      val calls = AtomicInteger(); server.handle("/chat/completions") { _, r -> calls.incrementAndGet(); r.reply(200, ok) }
      denied("MODEL_NOT_ALLOWED") { coordinator(db).foreground(policy) { provider(server).complete(request("other")) } }
      assertEquals(0, calls.get()); assertTrue(db.durable().attempts(0, Long.MAX_VALUE).isEmpty())
    } }
  }

  @Test fun quotaCooldownSurvivesNewExecutionEvenUncapped() = runBlocking {
    memory().useDatabase { db -> TinyHttpServer().use { server ->
      val calls = AtomicInteger(); server.handle("/chat/completions") { _, r -> calls.incrementAndGet(); r.reply(429, "{}", mapOf("Retry-After" to "60")) }
      val c = coordinator(db)
      try { c.foreground(policy) { provider(server).complete(request()) }; fail() } catch (_: AiError.RateLimited) { }
      denied("COOLDOWN") { c.foreground(policy) { provider(server).complete(request()) } }
      assertEquals(1, calls.get()); assertEquals("UNCAPPED_UNKNOWN", db.durable().attempts(0, Long.MAX_VALUE).single().state)
    } }
  }

  @Test fun unknownCancelledStreamRetainsReservationAcrossReopen() = runBlocking {
    val name = "runtime-budget-${System.nanoTime()}.db"
    var db = Room.databaseBuilder(context, PampaiDatabase::class.java, name).build()
    try { TinyHttpServer().use { server ->
      server.handle("/chat/completions") { _, r -> r.begin(200); r.write("data: {\"choices\":[{\"delta\":{\"content\":\"part\"}}]}\n\n") }
      val c = coordinator(db); cap(c, 1); bounds(c)
      c.foreground(policy) { provider(server).stream(request()).take(1).collect() }
      db.close(); db = Room.databaseBuilder(context, PampaiDatabase::class.java, name).build()
      val row = db.durable().attempts(0, Long.MAX_VALUE).single()
      assertEquals("UNCERTAIN", row.state); assertEquals(140L, row.chargedTokens)
      assertEquals(0, db.durable().foregroundCount())
    } } finally { db.close(); context.deleteDatabase(name) }
  }

  @Test fun internalAndOuterRetriesEachReserveRealAttempt() = runBlocking {
    memory().useDatabase { db -> TinyHttpServer().use { server ->
      val calls = AtomicInteger(); server.handle("/chat/completions") { _, r ->
        if (calls.incrementAndGet() == 1) r.reply(400, """{"error":{"message":"unsupported temperature"}}""") else r.reply(200, ok)
      }
      val c = coordinator(db); cap(c); bounds(c)
      c.foreground(policy) {
        provider(server).complete(request().copy(temperature = 0.2))
        provider(server).complete(request()) // outer call is independently admitted
      }
      val rows = db.durable().attempts(0, Long.MAX_VALUE)
      assertEquals(3, calls.get()); assertEquals(3, rows.map { it.attemptId }.distinct().size)
      assertEquals(1, rows.count { it.state == "UNCERTAIN" })
      assertEquals(2, rows.count { it.chargedTokens == 5L })
    } }
  }

  @Test fun serverFallbackCandidatesCannotSpendOutsidePolicy() = runBlocking {
    memory().useDatabase { db -> TinyHttpServer().use { server ->
      val calls = AtomicInteger(); server.handle("/chat/completions") { _, r -> calls.incrementAndGet(); r.reply(200, ok) }
      denied("MODEL_NOT_ALLOWED") { coordinator(db).foreground(policy) { provider(server, true).complete(request()) } }
      assertEquals(0, calls.get())
    } }
  }

  @Test fun storageObserverFailureNeverReplaysCompletedTransport() = runBlocking {
    memory().useDatabase { db -> TinyHttpServer().use { server ->
      val calls = AtomicInteger(); server.handle("/chat/completions") { _, r -> calls.incrementAndGet(); r.reply(200, ok) }
      val c = coordinator(db)
      try {
        c.foreground(policy) { provider(server).complete(request().copy(lifecycle = object : ProviderAttemptLifecycle {
          override suspend fun afterAttempt(settlement: ProviderSettlement) { error("synthetic observer failure") }
        })) }
        fail()
      } catch (_: ProviderObserverFailure) { }
      assertEquals(1, calls.get()); assertEquals(5L, db.durable().attempts(0, Long.MAX_VALUE).single().chargedTokens)
      assertEquals(0, db.durable().foregroundCount())
    } }
  }

  @Test fun backgroundNeverUsesUncappedForegroundAndYieldsForPriorityAndFences() = runBlocking {
    memory().useDatabase { db -> TinyHttpServer().use { server ->
      val calls = AtomicInteger(); server.handle("/chat/completions") { _, r -> calls.incrementAndGet(); r.reply(200, ok) }
      val now = System.currentTimeMillis(); val foundation = DurableFoundation(db)
      foundation.configure(AreaConfiguration("music", enabled = true, setupComplete = true, requestCap = 10,
        tokenCap = 10000, windowStart = 0, windowEnd = Long.MAX_VALUE), listOf(AreaModel("music", "GROQ", "exact")))
      foundation.createJob("job", null, "music", "DIRECT", "BACKGROUND", "synthetic", policy, emptyList(),
        listOf(ResearchStep("job", 0, "", "synthetic")), 10, now)
      val lease = requireNotNull(foundation.claim("job", "owner", now, 60000))
      val c = coordinator(db); bounds(c)
      val missing = c.job(lease, "synthesis") { provider(server).complete(request()) }
      assertEquals("NO_GLOBAL_WINDOW", (missing as BudgetExecutionResult.Yielded).reason)
      cap(c); c.configure(BudgetCap("background", true, 0, Long.MAX_VALUE, 10, 10000, null))
      c.foreground(policy) {
        val priority = c.job(lease, "synthesis") { provider(server).complete(request()) }
        assertEquals("FOREGROUND_PRIORITY", (priority as BudgetExecutionResult.Yielded).reason)
      }
      val result = c.job(lease, "synthesis") { provider(server).complete(request()) }
      assertTrue(result is BudgetExecutionResult.Completed)
      foundation.cancel("job")
      val fenced = c.job(lease, "extraction") { provider(server).complete(request()) }
      assertEquals("JOB_FENCE", (fenced as BudgetExecutionResult.Yielded).reason)
      assertEquals(1, calls.get())
    } }
  }

  @Test fun foregroundJobBeforeGlobalSetupIsUncappedButKeepsLeaseAndSourceFences() = runBlocking {
    memory().useDatabase { db -> TinyHttpServer().use { server ->
      val calls = AtomicInteger(); server.handle("/chat/completions") { _, r -> calls.incrementAndGet(); r.reply(200, ok) }
      var proofValid = true
      val proofs = SourceProofValidator { grant, _ -> proofValid && grant.generationProof == "synthetic-verified" }
      val foundation = DurableFoundation(db, proofs)
      val now = System.currentTimeMillis()
      foundation.putGrant(SourceGrant("source", "synthetic.package", "synthetic.authority", "account", null, "music", null,
        enabled = true, generationProof = "synthetic-verified", proofExpiresAt = Long.MAX_VALUE))
      foundation.createJob("foreground-job", null, "music", "RESEARCH", "FOREGROUND", "synthetic", policy, listOf("source"),
        listOf(ResearchStep("foreground-job", 0, "source", "synthetic")), 10, now)
      val lease = requireNotNull(foundation.claim("foreground-job", "owner", now, 60000))
      val c = RuntimeBudgetCoordinator(BudgetLedger(db, proofs = proofs), db)
      assertTrue(c.job(lease, "synthesis") { provider(server).complete(request()) } is BudgetExecutionResult.Completed)
      val row = db.durable().attempts(0, Long.MAX_VALUE).single()
      assertEquals("UNCAPPED_OBSERVED", row.state); assertEquals("foreground-job", row.jobId)
      assertEquals(5L, row.chargedTokens); assertNull(row.chargedMoney)
      val wrongOwner = c.job(lease.copy(owner = "other"), "synthesis") { provider(server).complete(request()) }
      assertEquals("JOB_FENCE", (wrongOwner as BudgetExecutionResult.Yielded).reason)
      proofValid = false
      val staleSource = c.job(lease, "extraction") { provider(server).complete(request()) }
      assertEquals("SOURCE_PROOF", (staleSource as BudgetExecutionResult.Yielded).reason)
      proofValid = true
      c.configure(BudgetCap("global", false, 0, 1, 10, 10000, null))
      val expiredCap = c.job(lease, "synthesis") { provider(server).complete(request()) }
      assertEquals("NO_GLOBAL_WINDOW", (expiredCap as BudgetExecutionResult.Yielded).reason)
      assertEquals(1, calls.get()); assertEquals(0, db.durable().foregroundCount())
    } }
  }

  @Test fun expiredGlobalConfigurationNeverReopensUncappedPath() = runBlocking {
    memory().useDatabase { db -> TinyHttpServer().use { server ->
      val c = coordinator(db); c.configure(BudgetCap("global", false, 0, 1, 10, 10000, null))
      denied("NO_GLOBAL_WINDOW") { c.foreground(policy) { provider(server).complete(request()) } }
      assertTrue(db.durable().attempts(0, Long.MAX_VALUE).isEmpty())
    } }
  }

  @Test fun recoveryRejectsActiveMarkerAndClearsOnlyAbandonedIds() = runBlocking {
    memory().useDatabase { db ->
      val c = coordinator(db); val ledger = BudgetLedger(db)
      ledger.setForeground("old-process/abandoned", true)
      c.foreground(policy) {
        assertEquals(listOf("old-process/abandoned"), c.abandonedForeground())
        val active = db.durable().foregroundExecutions().first { it != "old-process/abandoned" }
        try { c.recoverAbandonedForeground(setOf(active)); fail() } catch (_: IllegalArgumentException) { }
        c.recoverAbandonedForeground(setOf("old-process/abandoned"))
        assertEquals(1, db.durable().foregroundCount())
      }
      assertEquals(0, db.durable().foregroundCount())
    }
  }
}
