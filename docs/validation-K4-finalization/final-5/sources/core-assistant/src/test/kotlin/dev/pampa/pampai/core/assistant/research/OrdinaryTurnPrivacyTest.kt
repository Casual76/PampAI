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
import dev.pampa.pampai.core.assistant.bridge.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class OrdinaryTurnPrivacyTest {
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

  private fun foreground(f: Fixture) = ResearchForeground(f.runner, f.setup, f.profile, f.connections)
  private fun mounted(f: Fixture, r: ResearchRead, name: String = r.tool): dev.antigravity.fluidengine.ai.tools.AiTool<Unit> {
    val catalog = sourceCatalog(r.selection.domain).copy(packageName = r.selection.packageName, authority = r.selection.authority,
      tools = listOf(RemoteToolInfo(name, "data", name, Schema.obj(emptyMap()), false, false, false)))
    return RemoteToolSet.of(SourceBridgeCalls(SourceFixture(), catalog), catalog, object : RemoteToolHost<Unit> {
      override suspend fun confirm(ctx: Unit, toolName: String, text: ConfirmationText): ToolOutput? = null
    }).tools.single()
  }
  private suspend fun rejected(block: suspend () -> Unit) {
    try { block(); fail("private result accepted") } catch (_: TurnPrivacyInvalidated) { }
  }
  @Test fun explicitRefusalsOverrideInferenceWithoutDisablingAffirmativeSourceConsultation() = runBlocking {
    val providedOnly = listOf(
      "Leggi i miei voti nella tabella allegata; usa solo quella, non consultare ClasseViva o il profilo.",
      "Leggi i miei ascolti, ma non usare il profilo o Fluidify.",
      "Leggi i miei voti usando esclusivamente il documento allegato.",
      "Leggi i miei voti, senza consultare ClasseViva.",
      "Leggi i miei voti qui sotto; usa solo questi dati.\nMatematica: 7; Italiano: 8.",
      "Leggi i miei voti; usa soltanto i valori riportati qui sotto. Matematica: 7.",
      "Leggi i miei voti; considera esclusivamente quanto segue. Matematica: 7.",
    )
    for (q in providedOnly) {
      assertNull(q, ResearchQuestionPlanner.profileQuery(q))
      assertNull(q, ResearchQuestionPlanner.sourceQuery(q))
    }
    val sourceOnly = "Leggi i miei voti da ClasseViva, ma non usare il profilo."
    assertNull(ResearchQuestionPlanner.profileQuery(sourceOnly))
    assertTrue("cv.grades" in ResearchQuestionPlanner.sourceQuery(sourceOnly)!!.fields)
    assertTrue("cv.grades" in ResearchQuestionPlanner.profileQuery("leggi i miei voti da ClasseViva")!!.fields)
    val permitted = "Leggi i miei voti da ClasseViva, non consultare Fluidify."
    assertTrue("cv.grades" in ResearchQuestionPlanner.sourceQuery(permitted)!!.fields)
    assertFalse(ResearchQuestionPlanner.onlyProvidedContent("Leggi i miei voti, usa solo i dati di ClasseViva."))
    assertTrue("cv.grades" in ResearchQuestionPlanner.sourceQuery("Leggi i miei voti, usa solo i dati di ClasseViva.")!!.fields)
    Fixture().use { f ->
      val grades = f.school().let { it.copy(selection = it.selection.copy(area = "grades"),
        arguments = JsonObject(it.arguments + ("sezione" to JsonPrimitive("grades")))) }
      f.sources.connect(grades)
      f.profile.correct("private-grades", "cv.grades", "private stored grades")
      val fg = foreground(f)
      val before = f.transport.calls.size
      for (q in providedOnly) {
        assertNull(fg.select(q, null))
        val turn = fg.captureTurn(q, ProfileQuery(listOf("cv.grades")), true)
        assertEquals("", turn.promptBlock)
        assertNotNull(withContext(turn) { mounted(f, grades).run(grades.arguments, Unit) }.result?.error)
      }
      assertEquals(before, f.transport.calls.size)
      assertNotNull(fg.select(permitted, null))
    }
  }
  @Test fun compoundPersonalQuestionUsesSavedScopeWhileItsGeneralDefinitionStaysOrdinary() = runBlocking {
    Fixture().use { f ->
      val r = f.music(); f.sources.connect(r)
      val fg = foreground(f)
      val question = "Che cosa ho ascoltato ieri su Fluidify?"
      assertTrue("fluidify.history_records" in ResearchQuestionPlanner.sourceQuery(question)!!.fields)
      assertEquals(listOf(r), fg.select(question, null)!!.reads)
      val generic = "Che cosa sono i voti in ClasseViva?"
      assertNull(ResearchQuestionPlanner.sourceQuery(generic))
      assertNull(ResearchQuestionPlanner.profileQuery(generic))
      assertNull(fg.select(generic, null))
    }
  }
  @Test fun actualEngineHonorsOnlyAttachmentAndPrivateRefusalBeforeResearchOrPromptInjection() = runBlocking {
    for ((imageAttached, question) in listOf(
      false to "Leggi i miei voti nella tabella allegata; usa solo quella, non consultare ClasseViva o il profilo.",
      true to "Leggi i miei voti nella tabella allegata; usa solo quella, non consultare ClasseViva o il profilo.",
      false to "Leggi i miei voti qui sotto; usa solo questi dati.\nMatematica: 7; Italiano: 8.",
    )) Fixture().use { f ->
      val grades = f.school().let { it.copy(selection = it.selection.copy(area = "grades"),
        arguments = JsonObject(it.arguments + ("sezione" to JsonPrimitive("grades")))) }
      f.sources.connect(grades)
      f.profile.correct("private-grades", "cv.grades", "DO_NOT_DISCLOSE_PROFILE")
      f.transport.calls.clear()
      val repo = ConversationsRepository(context, f.db.conversations(), f.db.messages(), f.db.attachments(), f.db.runs(), Json, f.db)
      val runtime = mockk<AssistantRuntime>(relaxed = true)
      every { runtime.attach(any(), any()) } returns true
      coEvery { runtime.preflight(any()) } returns Unit
      val settings = mockk<AiSettingsStore>()
      coEvery { settings.current() } returns AiSettings(enabled = true)
      val appSettings = mockk<dev.pampa.pampai.core.assistant.settings.PampaiSettingsStore>()
      coEvery { appSettings.current() } returns dev.pampa.pampai.core.assistant.settings.PampaiSettings(quickCommands = false)
      val memory = mockk<MemoryRepository>()
      coEvery { memory.promptBlock() } returns "DO_NOT_DISCLOSE_LEGACY_MEMORY"
      val remote = mockk<dev.pampa.pampai.core.assistant.remote.RemoteSwitches>(relaxed = true)
      coEvery { remote.stopMessage() } returns null
      val provider = mockk<ChatProvider>()
      every { provider.id } returns ProviderId.GROQ
      val prompts = mutableListOf<String>()
      coEvery { provider.complete(any()) } coAnswers {
        prompts += firstArg<ChatRequest>().messages.toString()
        ChatTurn(Message.Assistant("{}"), FinishReason.STOP, null, RateLimitInfo.EMPTY)
      }
      every { provider.stream(any()) } answers {
        prompts += firstArg<ChatRequest>().messages.toString()
        flow { emit(ChatDelta.Text("Solo i dati forniti.")); emit(ChatDelta.Finish(FinishReason.STOP, null, RateLimitInfo.EMPTY)) }
      }
      val factory = mockk<ProviderFactory>()
      val ready = ReadyProvider(provider, "llama-fixture", "unused", null, catalogue = ModelCatalogue(listOf(
        ModelInfo("llama-fixture", "fixture", ModelKind.CHAT, contextWindow = 16000, maxOutputTokens = 4000,
          supportsVision = true, visionVerified = true)), emptyList()))
      coEvery { factory.ordered(any()) } returns listOf(ready)
      coEvery { factory.build(any(), any()) } returns ready
      val engine = AssistantEngine(context, runtime, dev.pampa.pampai.core.assistant.tools.RegistryHolder(), factory, settings,
        appSettings, repo, memory, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), mockk(relaxed = true), dev.pampa.pampai.core.assistant.screen.ScreenContextStore(),
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), remote, mockk(relaxed = true),
        dev.pampa.pampai.core.assistant.receipts.OperationLedger(f.db), mockk(relaxed = true), f.budget, foreground(f))
      val cid = repo.createConversation("provided input", 1); repo.rename(cid, "provided input", auto = true)
      val image = dev.pampa.pampai.core.assistant.attachments.PendingAttachment(AttachmentKind.IMAGE, "image/png", "synthetic.png", byteArrayOf(1))
      val result = engine.execute(AssistantRequest(cid, question, dev.antigravity.fluidengine.ai.orchestrator.AskMode.TEXT,
        attachments = if (imageAttached) listOf(image) else emptyList(), modelPolicy = policy), 1)
      assertEquals("Solo i dati forniti.", result.answer)
      assertTrue(prompts.isNotEmpty())
      assertTrue(prompts.none { it.contains("DO_NOT_DISCLOSE") })
      assertTrue(f.transport.calls.isEmpty())
    }
  }
  @Test fun mountedMissingContextGenericAndUnknownReadNeverReachSource() = runBlocking {
    Fixture().use { f ->
      val r = f.music(); f.sources.connect(r)
      val tool = mounted(f, r)
      assertEquals("TURN_READ_CONTEXT_REQUIRED", tool.run(r.arguments, Unit).result?.error?.code)
      val fg = foreground(f)
      val generic = fg.captureTurn("Come posso migliorare i miei gusti musicali?", null, true)
      val before = f.transport.calls.size
      val result = withContext(generic) { tool.run(r.arguments, Unit) }
      assertEquals("PERSONAL_SOURCE_INTENT_REQUIRED", result.result?.error?.code)
      val specific = fg.captureTurn("leggi i miei ascolti", null, true)
      for (name in listOf("legacy", "source_capabilities", "source_lease", "source_read", "source_validate", "result_fragment")) {
        assertNotNull(withContext(specific) { mounted(f, r, name).run(r.arguments, Unit) }.result?.error)
      }
      assertEquals(before, f.transport.calls.size)
    }
  }
  @Test fun actualMountedValidReadUsesProofChannelAndExactSavedPeriod() = runBlocking {
    Fixture().use { f ->
      val r = f.music(); f.sources.connect(r)
      val turn = foreground(f).captureTurn("leggi i miei ascolti", null, true)
      val tool = mounted(f, r)
      val mismatch = JsonObject(r.arguments + ("until" to JsonPrimitive("9999")))
      assertNotNull(withContext(turn) { tool.run(mismatch, Unit) }.result?.error)
      val result = withContext(turn) { tool.run(r.arguments, Unit) }
      assertNull(result.result?.error)
      assertTrue(turn.privateTurn)
      assertTrue(f.transport.calls.any { it.first == "source_read" })
    }
  }
  @Test fun savedScopeReplacementAndRevocationRejectMountedReads() = runBlocking {
    for (revoke in listOf(false, true)) Fixture().use { f ->
      val r = f.music(); f.sources.connect(r)
      val turn = foreground(f).captureTurn("leggi i miei ascolti", null, true)
      if (revoke) f.sources.revoke(r.grantId)
      else f.connections.save(r.copy(arguments = JsonObject(r.arguments + ("until" to JsonPrimitive("2000")))))
      val before = f.transport.calls.size
      assertNotNull(withContext(turn) { mounted(f, r).run(r.arguments, Unit) }.result?.error)
      assertEquals(before, f.transport.calls.size)
    }
  }
  @Test fun forgottenRawAndAggregateFamiliesCannotReacquire() = runBlocking {
    for (tool in listOf("history_records", "history_aggregate")) Fixture().use { f ->
      val r = f.music(tool); f.sources.connect(r)
      f.foundation.forget(if (tool == "history_records") "fluidify.history_aggregate" else "fluidify.history_records")
      val turn = foreground(f).captureTurn("leggi i miei ascolti", null, true)
      val before = f.transport.calls.size
      assertNotNull(withContext(turn) { mounted(f, r).run(r.arguments, Unit) }.result?.error)
      assertEquals(before, f.transport.calls.size)
    }
  }
  @Test fun lateMountedReadNeverReturnsAfterForget() = runBlocking {
    Fixture().use { f ->
      val r = f.music(); f.sources.connect(r)
      val turn = foreground(f).captureTurn("leggi i miei ascolti", null, true)
      val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
      f.transport.onRead = { _, _ -> entered.complete(Unit); release.await(); buildJsonObject { put("secret", true) } to null }
      val pending = async { withContext(turn) { mounted(f, r).run(r.arguments, Unit) } }
      withTimeout(10_000) { entered.await() }; f.foundation.forget("fluidify.history_records"); release.complete(Unit)
      rejected { pending.await() }
    }
  }
  @Test fun legacyCvAndRawProofCannotGuessStudentOrYear() = runBlocking {
    Fixture().use { f ->
      val r = f.school(); f.sources.connect(r)
      val turn = foreground(f).captureTurn("leggi i miei compiti", null, true)
      assertNotNull(withContext(turn) { mounted(f, r, "voti").run(r.arguments, Unit) }.result?.error)
      val bad = JsonObject(r.arguments - "anno")
      assertNotNull(withContext(turn) { mounted(f, r).run(bad, Unit) }.result?.error)
      assertNull(withContext(turn) { mounted(f, r).run(r.arguments, Unit) }.result?.error)
    }
  }
  @Test fun mountedFragmentsRequireTheExactOriginatingTurnAndContinuation() = runBlocking {
    Fixture().use { f ->
      val r = f.music(); f.sources.connect(r)
      f.transport.onRead = { _, request ->
        val first = request.string("tool") == r.tool
        buildJsonObject {
          put("encoding", "concatenate_json_text_utf16"); put("snapshot", "owned-snapshot")
          put("hash", "a".repeat(64)); put("queryHash", "b".repeat(64))
          put("offset", if (first) 0 else 4); put("end", if (first) 4 else 8); put("totalChars", 8)
          put("fragment", if (first) "abcd" else "efgh")
          if (first) { put("nextTool", "result_fragment"); put("nextOffset", 4) }
        } to null
      }
      val turn = foreground(f).captureTurn("leggi i miei ascolti", null, true)
      val args = buildJsonObject {
        put("account", "account-A"); put("snapshot", "owned-snapshot")
        put("hash", "a".repeat(64)); put("queryHash", "b".repeat(64)); put("offset", 4)
      }
      val fragment = mounted(f, r, "result_fragment")
      assertNotNull(withContext(turn) { fragment.run(args, Unit) }.result?.error)
      assertNull(withContext(turn) { mounted(f, r).run(r.arguments, Unit) }.result?.error)
      val before = f.transport.calls.count { it.first == "source_read" }
      val wrong = JsonObject(args + ("offset" to JsonPrimitive(3)))
      assertNotNull(withContext(turn) { fragment.run(wrong, Unit) }.result?.error)
      assertEquals(before, f.transport.calls.count { it.first == "source_read" })
      val otherTurn = foreground(f).captureTurn("leggi i miei ascolti", null, true)
      assertNotNull(withContext(otherTurn) { fragment.run(args, Unit) }.result?.error)
      val final = withContext(turn) { fragment.run(args, Unit) }
      assertNull(final.result?.error); assertEquals("efgh", final.result?.data?.string("fragment"))
      assertNotNull(withContext(turn) { fragment.run(args, Unit) }.result?.error)
      assertEquals(before + 1, f.transport.calls.count { it.first == "source_read" })
    }
  }
  @Test fun lateCompleteAndStreamProfileCorrectionAreTerminalAndReleaseNoContent() = runBlocking {
    for (stream in listOf(false, true)) Fixture().use { f ->
      f.profile.correct("statement", "fluidify.history_records", "private taste")
      val fg = foreground(f)
      val query = ResearchQuestionPlanner.profileQuery("leggi i miei ascolti")!!
      val turn = fg.captureTurn("leggi i miei ascolti", query, true)
      assertTrue(turn.promptBlock.contains("private taste"))
      val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var wires = 0
      val provider = mockk<ChatProvider>()
      val answer = ChatTurn(Message.Assistant("late secret"), FinishReason.STOP, null, RateLimitInfo())
      coEvery { provider.complete(any()) } coAnswers { wires++; entered.complete(Unit); release.await(); answer }
      every { provider.stream(any()) } returns flow {
        wires++; emit(ChatDelta.Text("early secret")); entered.complete(Unit); release.await(); emit(ChatDelta.Text("late secret"))
      }
      val guarded = fg.guarded(ReadyProvider(provider, "fixture", "fixture", null), turn).provider
      val received = mutableListOf<ChatDelta>()
      val pending = async {
        if (stream) guarded.stream(ChatRequest("fixture", emptyList())).collect { received += it }
        else guarded.complete(ChatRequest("fixture", emptyList()))
      }
      withTimeout(10_000) { entered.await() }; f.profile.correct("statement", "fluidify.history_records", "new taste"); release.complete(Unit)
      rejected { pending.await() }
      assertEquals(1, wires); assertTrue(received.isEmpty())
      var committed = false
      rejected { turn.commit { committed = true } }; assertFalse(committed)
    }
  }
  @Test fun sourceFreeTurnSurvivesUnrelatedForgetAndFreshProfileCommits() = runBlocking {
    Fixture().use { f ->
      val fg = foreground(f)
      val free = fg.captureTurn("quanto fa due piu due?", null, true)
      f.profile.forget("fluidify.history_records")
      var committed = false; free.commit { committed = true }; assertTrue(committed)
      f.profile.correct("statement", "cv.grades", "synthetic grades")
      val fresh = fg.captureTurn("leggi i miei voti", ResearchQuestionPlanner.profileQuery("leggi i miei voti"), true)
      assertTrue(fresh.promptBlock.contains("synthetic grades"))
      fresh.commit { committed = false }; assertFalse(committed)
    }
  }
  @Test fun stableSourceProofRenewsAfterSlowCompletionAndStreamingWithoutLosingTheAnswer() = runBlocking {
    for (stream in listOf(false, true)) Fixture().use { f ->
      val r = f.music(); f.sources.connect(r)
      val fg = foreground(f); val turn = fg.captureTurn("leggi i miei ascolti", null, true)
      withContext(turn) { assertNull(mounted(f, r).run(r.arguments, Unit).result?.error) }
      val provider = mockk<ChatProvider>()
      coEvery { provider.complete(any()) } coAnswers {
        f.clock.advance(31_000)
        ChatTurn(Message.Assistant("valid delayed answer"), FinishReason.STOP, null, RateLimitInfo.EMPTY)
      }
      every { provider.stream(any()) } returns flow {
        emit(ChatDelta.Text("valid ")); f.clock.advance(31_000); emit(ChatDelta.Text("delayed answer"))
      }
      val guarded = fg.guarded(ReadyProvider(provider, "fixture", "unused", null), turn).provider
      if (stream) assertEquals(2, guarded.stream(ChatRequest("fixture", emptyList())).toList().size)
      else assertNotNull(guarded.complete(ChatRequest("fixture", emptyList())))
      var committed = false; turn.commit { committed = true }; assertTrue(committed)
      assertTrue(f.transport.calls.any { it.first == "source_validate" })
    }
  }
  @Test fun atomicCommitRollsBackConversationWriteWhenLocalGenerationChanged() = runBlocking {
    Fixture().use { f ->
      f.profile.correct("statement", "cv.grades", "secret")
      val turn = foreground(f).captureTurn("leggi i miei voti", ProfileQuery(listOf("cv.grades")), true)
      f.foundation.forget("cv.grades")
      rejected { turn.commit { f.conversation() } }
      assertEquals(0, f.db.openHelper.readableDatabase.query("SELECT * FROM conversations").use { it.count })
    }
  }

  @Test fun ordinaryImageProfileWiringRejectsLateStreamBeforeDiskCacheOrTitle() = runBlocking {
    Fixture().use { f ->
      f.profile.correct("image-statement", "fluidify.history_records", "private image profile")
      val repo = ConversationsRepository(context, f.db.conversations(), f.db.messages(), f.db.attachments(), f.db.runs(), Json, f.db)
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
          wires++; prompt = request.messages.toString(); emit(ChatDelta.Text("early private output"))
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
      val cid = repo.createConversation("image question", 1)
      val image = dev.pampa.pampai.core.assistant.attachments.PendingAttachment(AttachmentKind.IMAGE, "image/png", "synthetic.png", byteArrayOf(1))
      val pending = async { engine.execute(AssistantRequest(cid, "leggi i miei ascolti", dev.antigravity.fluidengine.ai.orchestrator.AskMode.TEXT,
        attachments = listOf(image)), 1) }
      withTimeout(10_000) { entered.await() }
      assertTrue(prompt.contains("private image profile"))
      assertTrue(repo.observeMessages(cid).first().none { it.text.contains("private output") })
      f.profile.forget("fluidify.history_records")
      val sent = wires; release.complete(Unit)
      assertNull(pending.await().answer); assertEquals(sent, wires)
      assertTrue(repo.observeMessages(cid).first().none { it.text.contains("private output") })
      assertTrue(repo.exchanges(cid, null, 8).isEmpty())
      assertEquals("privacy_invalidated", repo.observeRuns(cid).first().last().outcome)
      assertFalse(repo.conversation(cid)!!.autoTitled)
      val field = AssistantEngine::class.java.getDeclaredField("memoryConversations").apply { isAccessible = true }
      assertTrue((field.get(engine) as Map<*, *>).isEmpty())
    }
  }
  @Test fun invalidatedProfileCommitRetainsUncertainWriteLedgerAndReplayFence() = runBlocking {
    Fixture().use { f ->
      val cid = f.conversation()
      f.profile.correct("statement", "cv.grades", "private")
      val turn = foreground(f).captureTurn("leggi i miei voti", ProfileQuery(listOf("cv.grades")), true)
      val ledger = dev.pampa.pampai.core.assistant.receipts.OperationLedger(f.db)
      val call = ToolCall("write", "fluidify_playlist_create", buildJsonObject { put("account", "account-A"); put("name", "explicit write") })
      val attempt = ledger.attempt(cid, "lineage", 100, { true })
      val execution = attempt.prepare(call)
      attempt.onOutput(call, execution, ToolOutput("uncertain receipt", result = ToolResultDetails(ToolOutcome.UNCERTAIN)))
      f.profile.forget("cv.grades")
      rejected { turn.commit { error("must not persist answer") } }
      assertEquals("UNCERTAIN", f.db.operations().get(execution.operationId)!!.state)
      try {
        ledger.attempt(cid, "lineage", 101, { true }).prepare(call)
        fail("uncertain write replayed")
      } catch (_: dev.pampa.pampai.core.assistant.receipts.PreviousOperationRequiresDecision) { }
    }
  }

  @Test fun revokeOrRemoteAuthorizationChangeAfterReadRejectsProviderResponse() = runBlocking {
    for (remoteChange in listOf(false, true)) Fixture().use { f ->
      val r = f.music(); f.sources.connect(r)
      val fg = foreground(f); val turn = fg.captureTurn("leggi i miei ascolti", null, true)
      withContext(turn) { assertNull(mounted(f, r).run(r.arguments, Unit).result?.error) }
      var wires = 0
      val provider = mockk<ChatProvider>()
      coEvery { provider.complete(any()) } coAnswers {
        wires++
        if (remoteChange) f.transport.authorization = "revoked-on-source" else f.sources.revoke(r.grantId)
        ChatTurn(Message.Assistant("late old data"), FinishReason.STOP, null, RateLimitInfo.EMPTY)
      }
      val guarded = fg.guarded(ReadyProvider(provider, "fixture", "unused", null), turn)
      rejected { guarded.provider.complete(ChatRequest("fixture", emptyList())) }
      assertEquals(1, wires)
      rejected { guarded.provider.complete(ChatRequest("fixture", emptyList())) }
      assertEquals(1, wires)
    }
  }
  @Test fun privateSuccessfulHistoryStaysVisibleButIsNotReusedAsModelContext() = runBlocking {
    Fixture().use { f ->
      val repo = ConversationsRepository(context, f.db.conversations(), f.db.messages(), f.db.attachments(), f.db.runs(), Json, f.db)
      val cid = repo.createConversation("kept visible", 1)
      val turn = repo.startTurn(cid, Anchor.Continue, "private question", dev.antigravity.fluidengine.ai.orchestrator.AskMode.TEXT, emptyList(), 2)
      repo.complete(turn.assistantId, "private accepted answer", emptyList())
      repo.addFailedRun(cid, turn.assistantId, 2, 3, "private", null)
      assertTrue(repo.observeMessages(cid).first().any { it.text == "private accepted answer" })
      assertTrue(repo.exchanges(cid, null, 8).isEmpty())
      assertEquals(1, repo.search("accepted").size)
      assertTrue(repo.search("accepted", includePrivate = false).isEmpty())
      val ctx = mockk<dev.pampa.pampai.core.assistant.tools.PampaiToolContext>()
      every { ctx.conversations } returns repo
      val toolResult = dev.pampa.pampai.core.assistant.tools.aria.ConversazioniCercaTool()
        .run(buildJsonObject { put("testo", "accepted") }, ctx)
      assertFalse(toolResult.text.contains("private accepted answer"))
    }
  }

  @Test fun mountedLegacyMediaBrowserReadsFailWithoutInventingAccountScope() = runBlocking {
    val registry = dev.pampa.pampai.core.assistant.tools.RegistryHolder().catalog.value.registry
    val ctx = mockk<dev.pampa.pampai.core.assistant.tools.PampaiToolContext>(relaxed = true)
    for (name in listOf("musica_recenti", "musica_playlist", "playlist_brani")) {
      val result = registry.find(name)!!.run(buildJsonObject {}, ctx, ToolExecution("legacy-read"))
      assertEquals("UNSCOPED_LEGACY_SOURCE_READ", result.result?.error?.code)
    }
  }

  @Test fun modelReadCannotForgeReceiptReadbackAuthorizationByRequestPrefix() = runBlocking {
    val fake = SourceFixture(); val catalog = sourceCatalog()
    val calls = SourceBridgeCalls(fake, catalog)
    val args = buildJsonObject { put("account", "alice"); put("playlistId", "spotifyID") }
    val denied = calls.run(catalog.authority, "playlist_details", args, false, "persisted:read:playlist_details", "it") as RemoteResult.Done
    assertEquals("TURN_READ_CONTEXT_REQUIRED", denied.result?.error?.code)
    assertTrue(fake.invocations.isEmpty())
    val accepted = readSignedPlaylist(calls, catalog.authority, originalPlaylist(), "persisted")
    assertNull(accepted.error)
    assertEquals(originalPlaylist().operation, accepted.operation)
    assertEquals(ToolOutcome.UNCERTAIN, accepted.outcome)
  }

  @Test fun pendingLegacyReadAndUnboundPollNeverReleaseLatePrivateStatus() = runBlocking {
    var dispatched = 0; var polled = 0
    val raw = object : BridgeCalls by SourceFixture() {
      override suspend fun run(authority: String, name: String, args: JsonObject, confirmed: Boolean, requestId: String, language: String): RemoteResult {
        dispatched++; return RemoteResult.Job("private-old-read")
      }
      override suspend fun status(authority: String, jobId: String): RemoteResult {
        polled++; return RemoteResult.Status(BridgeProtocol.STATE_DONE, null, null, "late private history", false, emptyList())
      }
    }
    val catalog = sourceCatalog()
    val calls = SourceBridgeCalls(raw, catalog)
    val set = RemoteToolSet.of(calls, catalog, object : RemoteToolHost<Unit> {
      override suspend fun confirm(ctx: Unit, toolName: String, text: ConfirmationText): ToolOutput? = null
    })
    val read = set.tools.single { it.name == "fluidify_history_records" }
    assertEquals("TURN_READ_CONTEXT_REQUIRED", read.run(buildJsonObject {}, Unit).result?.error?.code)
    assertEquals("UNBOUND_SOURCE_JOB", (calls.status(catalog.authority, "private-old-read") as RemoteResult.Done).result?.error?.code)
    assertEquals(0, dispatched); assertEquals(0, polled)
  }
}
