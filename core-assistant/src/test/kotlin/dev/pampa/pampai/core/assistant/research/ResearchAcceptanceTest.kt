package dev.pampa.pampai.core.assistant.research

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.antigravity.fluidengine.ai.bridge.*
import dev.antigravity.fluidengine.ai.keys.*
import dev.antigravity.fluidengine.ai.net.*
import dev.antigravity.fluidengine.ai.provider.*
import dev.antigravity.fluidengine.ai.provider.Message
import dev.antigravity.fluidengine.ai.tools.*
import dev.pampa.pampai.core.assistant.db.*
import dev.pampa.pampai.core.assistant.durable.*
import dev.pampa.pampai.core.assistant.runtime.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class ResearchAcceptanceTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val policy = ModelPolicy(ExactModel(ProviderId.GROQ, "llama-fixture"), ThinkingLevel.LOW)
  private class Clock : ResearchClock {
    var at = System.currentTimeMillis(); var elapsed = 100L
    override fun wall() = at
    override fun tick() = elapsed
    fun advance(ms: Long) { at += ms; elapsed += ms }
  }
  private class Transport(private val clock: Clock) : ResearchTransport {
    var authorization = "auth-A"; var incarnation = "process-A"; var typed = true; var wrongProof = false
    var account = "account-A"
    var calls = mutableListOf<Pair<String, JsonObject>>()
    val caps = mutableMapOf<String, Pair<RemoteHost, Long>>()
    val leases = mutableMapOf<String, JsonObject>()
    var onRead: suspend (RemoteHost, JsonObject) -> Pair<JsonObject, ToolPagination?> = { host, request ->
      buildJsonObject { put(if (host.label == "cv") "records" else "items", buildJsonArray { add(buildJsonObject { put("id", "record-1"); put("text", "synthetic 😀"); put("durationMs", JsonNull) }) })
        if(host.label=="cv"){put("account","account-A");put("student","student-A");put("year","2026-2027");put("section",request["arguments"]!!.jsonObject.string("sezione"))}
      } to null
    }
    override suspend fun hosts() = listOf(RemoteHost("synthetic.music", "synthetic.music.tools", "fluidify"))
    override suspend fun typed(host: RemoteHost) = typed
    override suspend fun call(host: RemoteHost, tool: String, args: JsonObject): RemoteResult.Done {
      calls += tool to args
      fun proof(lease: String): JsonObject {
        val cap = args.string("capability")!!
        return buildJsonObject {
          put("contract", "aria.source-proof"); put("version", 1); put("domain", host.label); put("authority", host.authority)
          put("incarnation", incarnation); put("authorization", authorization); put("capability", cap); put("lease", lease)
          put("account", if (wrongProof) "WRONG" else account); put("student", if (host.label == "cv") JsonPrimitive("student-A") else JsonNull)
          put("scope", leases[lease]!!); put("checkedAtMillis", clock.wall()); put("expiresAtMillis", caps[cap]!!.second); put("valid", true)
        }
      }
      var pagination: ToolPagination? = null
      val data = when(tool) {
        "source_capabilities" -> {
          val cap = UUID.randomUUID().toString(); caps[cap] = host to (clock.wall() + 60000)
          buildJsonObject {
            put("contract", "aria.source-proof"); put("version", 1); put("domain", host.label); put("authority", host.authority)
            put("incarnation", incarnation); put("authorization", authorization); put("capability", cap); put("ttlMillis", 60000)
            put("account", account); put("student", if (host.label == "cv") JsonPrimitive("student-A") else JsonNull)
            put("readTools", JsonArray(listOf("history_records", "history_aggregate", "registro_dati").map(::JsonPrimitive)))
          }
        }
        "source_lease" -> {
          val id = UUID.randomUUID().toString(); leases[id] = ResearchReadPolicy.scope(args.string("tool")!!, args["arguments"]!!.jsonObject); proof(id)
        }
        "source_validate" -> proof(args.string("lease")!!)
        "source_read" -> {
          val (page, paging) = onRead(host, args); pagination = paging
          JsonObject(page + ("sourceProof" to proof(args.string("lease")!!)))
        }
        else -> error("unexpected write/control: $tool")
      }
      return RemoteResult.Done("synthetic", false, emptyList(), ToolResultDetails(ToolOutcome.COMPLETED, data = data,
        pagination = pagination, coverage = ToolCoverage(false), freshness = ToolFreshness(clock.wall())))
    }
  }
  private inner class Fixture(name: String? = null) : AutoCloseable {
    val db = if (name == null) Room.inMemoryDatabaseBuilder(context, PampaiDatabase::class.java).build()
      else Room.databaseBuilder(context, PampaiDatabase::class.java, name).build()
    val clock = Clock(); val transport = Transport(clock); val cache = VerifiedSourceReceiptCache(clock)
    val foundation = DurableFoundation(db, cache); val budget = RuntimeBudgetCoordinator(BudgetLedger(db, proofs = cache), db)
    val setup = ResearchSetup(context, foundation, budget)
    val connections = ResearchConnections(context, foundation)
    val sources = ResearchSources(transport, foundation, cache, clock, connections)
    var generations = 0; var generatedPolicy: ModelPolicy? = null
    var generatedObjective = ""
    var beforeModel: suspend () -> Unit = {}
    var profileOutput: (List<ResearchEvidence>) -> JsonArray = { buildJsonArray {} }
    var idsOutput: ((List<ResearchEvidence>) -> JsonArray)? = null
    val model = object : ResearchModel {
      override suspend fun generate(policy: ModelPolicy, objective: String, evidence: List<ResearchEvidence>, guard: suspend () -> Unit): String {
        guard(); beforeModel(); generations++; generatedPolicy = policy; generatedObjective = objective
        return buildJsonObject { put("answer", "synthetic partial coverage [${evidence.first().id}]"); put("evidenceIds", idsOutput?.invoke(evidence) ?: JsonArray(evidence.map { JsonPrimitive(it.id) })); put("profile", profileOutput(evidence)) }.toString()
      }
    }
    val profile = ResearchProfile(foundation, sources, clock, setup)
    val runner = ResearchRunner(foundation, sources, budget, model, setup, clock, profile)
    val area = "music-${UUID.randomUUID()}"
    fun music(tool: String = "history_records") = ResearchRead(SourceSelection("synthetic.music", "synthetic.music.tools", "fluidify", "account-A", area = area), tool,
      buildJsonObject { put("account", "account-A"); put("from", "0"); put("until", "1000") })
    fun school() = ResearchRead(SourceSelection("synthetic.school", "synthetic.school.tools", "cv", "account-A", "student-A", "2026-2027", "agenda"), "registro_dati",
      buildJsonObject { put("account", "account-A"); put("studente", "student-A"); put("anno", "2026-2027"); put("sezione", "agenda") })
    suspend fun conversation(temporary: Boolean = false) = db.conversations().insert(ConversationEntity(title="synthetic", createdAtMillis=0, updatedAtMillis=0, temporary=temporary))
    suspend fun admitted(reads: List<ResearchRead>): Pair<Long, String> {
      reads.forEach { sources.connect(it) }
      val cid = conversation()
      return cid to runner.admitForeground(ForegroundResearch(cid, "synthetic two sources", ResearchIntent.RESEARCH, policy, reads))
    }
    suspend fun finish(id: String): ResearchAnswer {
      repeat(12) { when(val result = runner.runTranche(id)) {
        is TrancheResult.Completed -> return result.answer!!
        is TrancheResult.Suspended -> error(result.code)
        is TrancheResult.Progress -> Unit
      } }; error("not completed")
    }
    override fun close() { db.close() }
  }
  private fun repo(f: Fixture) = ConversationsRepository(context, f.db.conversations(), f.db.messages(),
    f.db.attachments(), f.db.runs(), Json, f.db)
  private fun foreground(f: Fixture) = ResearchForeground(f.runner, f.setup, f.profile, f.connections)
  private val question = "leggi i miei ascolti su Fluidify"
  private suspend fun produce(f: Fixture, temporary: Boolean = false): Triple<ConversationsRepository, Turn, ResearchAnswer> {
    val r = f.music(); f.sources.connect(r)
    val repo = repo(f); val cid = repo.createConversation(question, 1, temporary = temporary)
    val turn = repo.startTurn(cid, Anchor.Continue, question,
      dev.antigravity.fluidengine.ai.orchestrator.AskMode.TEXT, emptyList(), 2)
    val fg = foreground(f); val task = fg.select(question, null)!!
    val answer = fg.answer(cid, question, temporary, policy, task) { repo.associateResearch(turn, it, 2) }
    assertNotNull(answer.descriptor); assertNotNull(answer.acceptance)
    return Triple(repo, turn, answer)
  }
  private suspend fun rejected(block: suspend () -> Unit) {
    try { block(); fail("private answer accepted") }
    catch (_: TurnPrivacyInvalidated) { }
    catch (_: ResearchUnavailable) { }
  }
  private suspend fun absent(repo: ConversationsRepository, turn: Turn, text: String) {
    assertFalse(repo.message(turn.assistantId)!!.text.contains(text))
    assertTrue(repo.exchanges(turn.conversationId, null, 8).isEmpty())
  }
  @Test fun forgetAtRealResearchReturnRejectsChatWithoutReplayingSettledSynthesis() = runBlocking {
    Fixture().use { f ->
      val (repo, turn, answer) = produce(f)
      val job = answer.descriptor!!.jobId!!; val checkpoint = f.foundation.steps(job)
      val attempts = f.db.durable().attempts(0, Long.MAX_VALUE)
      f.profile.forget("fluidify.history_records")
      rejected { repo.acceptResearch(turn, answer, 2, 3) }
      absent(repo, turn, answer.text)
      assertEquals(checkpoint.size, f.foundation.steps(job).size)
      assertEquals(attempts, f.db.durable().attempts(0, Long.MAX_VALUE))
      assertEquals(1, f.generations)
    }
  }
  @Test fun localRevokeAtReturnRejectsEvenAfterExplicitReconnect() = runBlocking {
    Fixture().use { f ->
      val (repo, turn, answer) = produce(f)
      f.sources.revoke(f.music().grantId)
      f.sources.connect(f.music(), reconnect = true)
      rejected { repo.acceptResearch(turn, answer, 2, 3) }
      absent(repo, turn, answer.text)
    }
  }
  @Test fun remoteAuthorizationDriftAtReturnRejectsChat() = runBlocking {
    Fixture().use { f ->
      val (repo, turn, answer) = produce(f)
      f.transport.authorization = "replacement-authorization"
      rejected { repo.acceptResearch(turn, answer, 2, 3) }
      absent(repo, turn, answer.text)
    }
  }
  @Test fun currentAnswerAndStableProofRenewalStayVisibleButNeverReplayOrToolSearch() = runBlocking {
    Fixture().use { f ->
      val (repo, turn, answer) = produce(f)
      val descriptor = answer.descriptor
      f.clock.advance(61000)
      repo.acceptResearch(turn, answer, 2, 3)
      assertEquals(descriptor, answer.descriptor)
      assertEquals(answer.text, repo.message(turn.assistantId)!!.text)
      assertEquals("research", repo.observeRuns(turn.conversationId).first().last().outcome)
      assertTrue(repo.exchanges(turn.conversationId, null, 8).isEmpty())
      assertTrue(repo.search("partial", includePrivate = false).isEmpty())
      assertFalse(repo.search("partial").isEmpty())
      val ctx = mockk<dev.pampa.pampai.core.assistant.tools.PampaiToolContext>()
      every { ctx.conversations } returns repo
      val result = dev.pampa.pampai.core.assistant.tools.aria.ConversazioniCercaTool()
        .run(buildJsonObject { put("testo", "partial") }, ctx)
      assertFalse(result.text.contains(answer.text))
    }
  }
  @Test fun sqliteFailureBetweenAnswerAndClassificationRollsBackBoth() = runBlocking {
    Fixture().use { f ->
      val (repo, turn, answer) = produce(f)
      // Actual SQLite failure, after complete() but before addFailedRun() can persist classification.
      f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_research_run BEFORE INSERT ON runs WHEN NEW.outcome = 'research' BEGIN SELECT RAISE(ABORT, 'injected classification failure'); END")
      var failed = false
      try { repo.acceptResearch(turn, answer, 2, 3) } catch (_: android.database.sqlite.SQLiteException) { failed = true }
      assertTrue(failed)
      absent(repo, turn, answer.text)
      assertFalse(repo.observeRuns(turn.conversationId).first().any { it.outcome == "research" })
      f.db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_research_run")
      repo.acceptResearch(turn, answer, 2, 4)
      assertEquals(answer.text, repo.message(turn.assistantId)!!.text)
      assertEquals(1, f.generations)
    }
  }
  @Test fun resumedDoneJobRetainsOriginalDescriptorAndGeneratesOnlyOnce() = runBlocking {
    Fixture().use { f ->
      val (repo, turn, answer) = produce(f)
      val job = answer.descriptor!!.jobId!!
      val fg = foreground(f)
      val task = fg.select(question, null)!!.copy(resumeJobId = job)
      val resumed = fg.answer(turn.conversationId, question, false, policy, task)
      assertEquals(answer.descriptor, resumed.descriptor)
      repo.acceptResearch(turn, resumed, 2, 3)
      assertEquals(1, f.generations)
    }
  }
  @Test fun temporaryAnswerUsesSameBoundaryWithoutCreatingResearchJobsOrProfile() = runBlocking {
    Fixture().use { f ->
      val before = f.foundation.jobs.first()
      val (repo, turn, answer) = produce(f, temporary = true)
      assertNull(answer.descriptor!!.jobId)
      repo.acceptResearch(turn, answer, 2, 3)
      assertEquals(before, f.foundation.jobs.first())
      assertTrue(f.profile.management.first().isEmpty())
      assertEquals(answer.text, repo.message(turn.assistantId)!!.text)
    }
  }
  @Test fun temporaryForgetDuringProductionCannotRecaptureANewEpoch() = runBlocking {
    Fixture().use { f ->
      f.beforeModel = { f.profile.forget("fluidify.history_records") }
      rejected { produce(f, temporary = true) }
      assertTrue(f.foundation.jobs.first().isEmpty())
    }
  }
  @Test fun actualStoredProfileDependencyIsCarriedAcrossSynthesisAndRejectsCorrectionAtHandoff() = runBlocking {
    Fixture().use { f ->
      f.profile.correct("user-listening", "fluidify.history_records", "private user correction")
      val (repo, turn, answer) = produce(f)
      assertTrue(f.generatedObjective.contains("private user correction"))
      assertTrue(answer.descriptor!!.profiles.any { it.id == "user-listening" })
      f.profile.correct("user-listening", "fluidify.history_records", "new correction")
      rejected { repo.acceptResearch(turn, answer, 2, 3) }
      absent(repo, turn, answer.text)
    }
  }
  @Test fun explicitProfileRefusalStillAllowsSourceResearchWithoutStoredKnowledgeOrLearning() = runBlocking {
    Fixture().use { f ->
      f.profile.correct("user-listening", "fluidify.history_records", "must not be read")
      val r = f.music(); f.sources.connect(r)
      val repo = repo(f); val q = question + "; non consultare il profilo"
      val cid = repo.createConversation(q, 1)
      val turn = repo.startTurn(cid, Anchor.Continue, q, dev.antigravity.fluidengine.ai.orchestrator.AskMode.TEXT, emptyList(), 2)
      val fg = foreground(f)
      val answer = fg.answer(cid, q, false, policy, fg.select(q, null)!!) { repo.associateResearch(turn, it, 2) }
      assertFalse(f.generatedObjective.contains("must not be read"))
      assertTrue(answer.descriptor!!.profiles.isEmpty())
      repo.acceptResearch(turn, answer, 2, 3)
      assertEquals(1, f.profile.management.first().size)
    }
  }
  @Test fun publicStreamingIsImmediateAndPrivateCapableStreamingRemainsBufferedBeforeFirstRead() = runBlocking {
    for (privateCapable in listOf(false, true)) Fixture().use { f ->
      f.sources.connect(f.music())
      val fg = foreground(f)
      val turn = fg.captureTurn(if (privateCapable) question else "quanto fa due piu due?", null, true)
      assertEquals(privateCapable, turn.requiresPrivacyFence)
      assertFalse(turn.privateTurn) // No private read yet; potential acquisition alone requires buffering.
      val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
      val provider = mockk<ChatProvider>()
      every { provider.stream(any()) } returns kotlinx.coroutines.flow.flow {
        emit(ChatDelta.Text("early")); entered.complete(Unit); release.await(); emit(ChatDelta.Text("late"))
      }
      val received = mutableListOf<ChatDelta>()
      val pending = async { fg.guarded(ReadyProvider(provider, "fixture", "fixture", null), turn).provider
        .stream(ChatRequest("fixture", emptyList())).collect { received += it } }
      withTimeout(5000) { entered.await() }
      assertEquals(if (privateCapable) 0 else 1, received.size)
      release.complete(Unit); pending.await()
      assertEquals(2, received.size)
    }
  }
  @Test fun publicScopeDecisionNeverUpgradesWhenANewSourceIsConnectedLater() = runBlocking {
    Fixture().use { f ->
      val fg = foreground(f)
      val captured = fg.captureTurn(question, null, true)
      assertFalse(captured.requiresPrivacyFence)
      f.sources.connect(f.music())
      assertFalse(captured.requiresPrivacyFence)
      val before = f.transport.calls.size
      assertNotNull(captured.read(mockk(), f.music().tool, f.music().arguments).result?.error)
      assertEquals(before, f.transport.calls.size)
      assertFalse(captured.privateTurn)
      assertTrue(fg.captureTurn(question, null, true).requiresPrivacyFence)
    }
  }
  @Test fun sourceFreeToolCommitUsesSharedRoomRollbackAndIgnoresUnrelatedEpochChanges() = runBlocking {
    Fixture().use { f ->
      val captured = foreground(f).captureTurn("quanto fa due piu due?", null, true)
      f.profile.forget("fluidify.history_records")
      val repo = repo(f); val cid = repo.createConversation("public", 1)
      val turn = repo.startTurn(cid, Anchor.Continue, "public", dev.antigravity.fluidengine.ai.orchestrator.AskMode.TEXT, emptyList(), 2)
      try {
        captured.commit { repo.complete(turn.assistantId, "rollback marker", emptyList()); error("injected failure") }
        fail("commit should fail")
      } catch (_: IllegalStateException) { }
      assertFalse(repo.message(turn.assistantId)!!.text.contains("rollback marker"))
      captured.commit { repo.complete(turn.assistantId, "public accepted", emptyList()) }
      assertEquals("public accepted", repo.message(turn.assistantId)!!.text)
    }
  }
  @Test fun rejectedAcceptancePreservesUncertainWriteReceiptAndNoReplayFence() = runBlocking {
    Fixture().use { f ->
      val (repo, turn, answer) = produce(f)
      val ledger = dev.pampa.pampai.core.assistant.receipts.OperationLedger(f.db)
      val call = ToolCall("write", "fluidify_playlist_create", buildJsonObject { put("account", "account-A"); put("name", "explicit write") })
      val attempt = ledger.attempt(turn.conversationId, turn.questionId.toString(), turn.assistantId, { true })
      val execution = attempt.prepare(call)
      attempt.onOutput(call, execution, ToolOutput("uncertain receipt", result = ToolResultDetails(ToolOutcome.UNCERTAIN)))
      f.profile.forget("fluidify.history_records")
      rejected { repo.acceptResearch(turn, answer, 2, 3) }
      assertEquals("UNCERTAIN", f.db.operations().get(execution.operationId)!!.state)
      try {
        ledger.attempt(turn.conversationId, turn.questionId.toString(), turn.assistantId + 1, { true }).prepare(call)
        fail("uncertain write replayed")
      } catch (_: dev.pampa.pampai.core.assistant.receipts.PreviousOperationRequiresDecision) { }
    }
  }
  @Test fun actualEngineRestoresPublicPartialSaverAndRetainsPrivateBuffering() = runBlocking {
    for (privateTurn in listOf(false, true)) Fixture().use { f ->
      if (privateTurn) f.profile.correct("image-statement", "fluidify.history_records", "private image profile")
      val repo = repo(f)
      val runtime = mockk<AssistantRuntime>(relaxed = true)
      every { runtime.attach(any(), any()) } returns true
      coEvery { runtime.preflight(any()) } returns Unit
      val settings = mockk<AiSettingsStore>()
      coEvery { settings.current() } returns AiSettings(enabled = true)
      val appSettings = mockk<dev.pampa.pampai.core.assistant.settings.PampaiSettingsStore>()
      coEvery { appSettings.current() } returns dev.pampa.pampai.core.assistant.settings.PampaiSettings(quickCommands = false)
      val memory = mockk<MemoryRepository>()
      coEvery { memory.promptBlock() } returns ""
      val remote = mockk<dev.pampa.pampai.core.assistant.remote.RemoteSwitches>(relaxed = true)
      coEvery { remote.stopMessage() } returns null
      val provider = mockk<ChatProvider>()
      every { provider.id } returns ProviderId.GROQ
      var wires = 0
      coEvery { provider.complete(any()) } coAnswers {
        wires++; ChatTurn(Message.Assistant("{}"), FinishReason.STOP, null, RateLimitInfo.EMPTY)
      }
      val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
      var prompt = ""
      every { provider.stream(any()) } answers {
        val request = firstArg<ChatRequest>()
        flow {
          // Exceed the production 24-character publication threshold before suspending.
          wires++; prompt = request.messages.toString(); emit(ChatDelta.Text("early private output with enough characters"))
          entered.complete(Unit); release.await()
          emit(ChatDelta.Text("late private output")); emit(ChatDelta.Finish(FinishReason.STOP, null, RateLimitInfo.EMPTY))
        }
      }
      val factory = mockk<ProviderFactory>()
      val ready = ReadyProvider(provider, "fixture-vision", "unused", null, catalogue = ModelCatalogue(listOf(
        ModelInfo("fixture-vision", "fixture", ModelKind.CHAT, contextWindow = 16000, maxOutputTokens = 4000,
          supportsVision = true, visionVerified = true)), emptyList()))
      coEvery { factory.ordered(any()) } returns listOf(ready)
      val engine = AssistantEngine(context, runtime, dev.pampa.pampai.core.assistant.tools.RegistryHolder(), factory, settings,
        appSettings, repo, memory, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), mockk(relaxed = true), dev.pampa.pampai.core.assistant.screen.ScreenContextStore(),
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), remote, mockk(relaxed = true),
        dev.pampa.pampai.core.assistant.receipts.OperationLedger(f.db), mockk(relaxed = true), f.budget, foreground(f))
      val cid = repo.createConversation("streaming fixture", 1)
      repo.rename(cid, "streaming fixture", auto = true) // Title generation is outside this streaming fixture.
      val image = dev.pampa.pampai.core.assistant.attachments.PendingAttachment(AttachmentKind.IMAGE, "image/png", "synthetic.png", byteArrayOf(1))
      val pending = async { engine.execute(AssistantRequest(cid, if (privateTurn) question else "Descrivi questa immagine",
        dev.antigravity.fluidengine.ai.orchestrator.AskMode.TEXT, attachments = listOf(image)), 1) }
      try {
        withTimeout(10_000) { entered.await() }
        if (privateTurn) {
          delay(400)
          assertTrue(repo.observeMessages(cid).first().none { it.text.contains("early private output") })
          f.profile.forget("fluidify.history_records")
        } else {
          withTimeout(5000) {
            while (repo.observeMessages(cid).first().none { it.text.contains("early private output") }) delay(20)
          }
        }
        release.complete(Unit); pending.await()
        if (privateTurn) assertTrue(repo.observeMessages(cid).first().none { it.text.contains("private output") })
        else assertTrue(repo.observeMessages(cid).first().any { it.text.contains("late private output") && it.status == MessageStatus.DONE })
      } finally { release.complete(Unit); pending.cancelAndJoin() }
    }
  }
}
