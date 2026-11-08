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
import kotlinx.coroutines.flow.filterNotNull
import dev.pampa.pampai.core.assistant.tools.*
import dev.pampa.pampai.core.assistant.receipts.*
import dev.antigravity.fluidengine.ai.orchestrator.*
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class MemoryClassificationIntegrationTest {
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
    var beforeValidate: suspend () -> Unit = {}
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
        "source_validate" -> { beforeValidate(); proof(args.string("lease")!!) }
        "source_read" -> {
          val (page, paging) = onRead(host, args); pagination = paging
          JsonObject(page + ("sourceProof" to proof(args.string("lease")!!)))
        }
        else -> error("unexpected write/control: $tool")
      }
      val text = if (tool == "source_read") JsonObject(data - "sourceProof").toString() else "synthetic"
      return RemoteResult.Done(text, false, emptyList(), ToolResultDetails(ToolOutcome.COMPLETED, data = data,
        pagination = pagination, coverage = ToolCoverage(false), freshness = ToolFreshness(clock.wall())))
    }
  }
  private inner class Fixture(name: String? = null) : AutoCloseable {
    val db = if (name == null) Room.inMemoryDatabaseBuilder(context, PampaiDatabase::class.java).build()
      else Room.databaseBuilder(context, PampaiDatabase::class.java, name)
        .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE).build()
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
  /** Real profile synthesis, evidence dependencies and Room; only source transport is fake. */
  private suspend fun seedProfile(f: Fixture): ResearchRead {
    val r = f.music(); f.sources.connect(r)
    f.profile.savePlan(AreaResearchPlan(AreaConfiguration(r.selection.area, enabled = true, setupComplete = true,
      requestCap = 10, tokenCap = 100000, windowStart = 0, windowEnd = Long.MAX_VALUE), policy, listOf(r)))
    f.profileOutput = { values -> val e = values.first(); buildJsonArray { add(buildJsonObject {
      put("field", e.field); put("value", e.value); put("valueType", e.valueType)
      put("kind", "OBSERVATION"); put("periodKey", e.periodKey)
      put("evidenceIds", buildJsonArray { add(e.id) })
    }) } }
    val cid = f.conversation()
    f.finish(f.runner.admitForeground(ForegroundResearch(cid, "quali brani ascolto?", ResearchIntent.LOOKUP, policy, listOf(r))))
    assertTrue(f.profile.retrieve(ProfileQuery(listOf("fluidify.history_records")), listOf(r)).isNotEmpty())
    return r
  }

  private inner class EngineFixture(val f: Fixture, actions: Boolean, titleScope: CoroutineScope? = null) {
    val repo = ConversationsRepository(context, f.db.conversations(), f.db.messages(), f.db.attachments(), f.db.runs(), Json, f.db)
    val memory = MemoryRepository(f.db.memories())
    val registry = RegistryHolder()
    val settings = mockk<AiSettingsStore>()
    val appSettings = mockk<dev.pampa.pampai.core.assistant.settings.PampaiSettingsStore>()
    val gate = PampaiConfirmationGate(AiConfirmationGate(), appSettings)
    val provider = mockk<ChatProvider>()
    var streamCount = 0
    var prompt = ""
    val providerInputs = java.util.Collections.synchronizedList(mutableListOf<ChatRequest>())
    val published = java.util.Collections.synchronizedList(mutableListOf<AssistantState>())
    var emitCall = ToolCall("first-call", "ricorda", buildJsonObject { put("testo", "promoted private fact") })
    var beforeAnswer: suspend () -> Unit = {}
    val engine: AssistantEngine
    init {
      coEvery { settings.current() } returns AiSettings(enabled = true, actionsEnabled = actions)
      coEvery { appSettings.current() } returns dev.pampa.pampai.core.assistant.settings.PampaiSettings(quickCommands = false)
      every { provider.id } returns ProviderId.GROQ
      coEvery { provider.complete(any()) } coAnswers {
        providerInputs += firstArg<ChatRequest>()
        ChatTurn(Message.Assistant("fixture title"), FinishReason.STOP, null, RateLimitInfo.EMPTY)
      }
      every { provider.stream(any()) } answers {
        val request = firstArg<ChatRequest>()
        providerInputs += request
        flow {
          streamCount++; prompt += request.messages.toString()
          if (streamCount == 1) {
            emit(ChatDelta.ToolCallPart(0, emitCall.id, emitCall.name, emitCall.arguments.toString()))
            emit(ChatDelta.Finish(FinishReason.TOOL_CALLS, null, RateLimitInfo.EMPTY))
          } else {
            beforeAnswer()
            emit(ChatDelta.Text("fixture final answer"))
            emit(ChatDelta.Finish(FinishReason.STOP, null, RateLimitInfo.EMPTY))
          }
        }
      }
      val runtime = mockk<AssistantRuntime>(relaxed = true)
      if (titleScope != null) every { runtime.scope } returns titleScope
      every { runtime.attach(any(), any()) } returns true
      every { runtime.publish(any(), any()) } answers { published += secondArg<AssistantState>(); true }
      coEvery { runtime.preflight(any()) } returns Unit
      val switches = mockk<dev.pampa.pampai.core.assistant.remote.RemoteSwitches>(relaxed = true)
      coEvery { switches.stopMessage() } returns null
      val ready = ReadyProvider(provider, "fixture-vision", "unused", null, catalogue = ModelCatalogue(listOf(
        ModelInfo("fixture-vision", "fixture", ModelKind.CHAT, contextWindow = 16000, maxOutputTokens = 4000,
          supportsVision = true, visionVerified = true)), emptyList()))
      val factory = mockk<ProviderFactory>()
      coEvery { factory.ordered(any()) } returns listOf(ready)
      engine = AssistantEngine(context, runtime, registry, factory, settings, appSettings, repo, memory,
        mockk(relaxed = true), gate, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        dev.pampa.pampai.core.assistant.screen.ScreenContextStore(), mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), switches, mockk(relaxed = true), OperationLedger(f.db), mockk(relaxed = true), f.budget, foreground(f))
    }
    suspend fun request(cid: Long, question: String = "leggi i miei ascolti") = engine.execute(AssistantRequest(cid, question, AskMode.TEXT,
      plugin = if (emitCall.name.startsWith("fluidify_")) "fluidify" else "aria",
      attachments = listOf(dev.pampa.pampai.core.assistant.attachments.PendingAttachment(
        AttachmentKind.IMAGE, "image/png", "synthetic.png", byteArrayOf(1)))), 1)
  }

  @Test fun memoryRefusalDeniesBothDispatchOverloadsAndStoredConversationSearch() = runBlocking {
    Fixture().use { f ->
      val memory = mockk<MemoryRepository>()
      val conversations = mockk<ConversationsRepository>()
      val ctx = mockk<PampaiToolContext>(relaxed = true)
      every { ctx.memory } returns memory
      every { ctx.conversations } returns conversations
      val turn = foreground(f).captureTurn("Spiegami la media aritmetica; non usare la memoria.", null, true)
      val tools = listOf(dev.pampa.pampai.core.assistant.tools.aria.MemoriaElencoTool(),
        dev.pampa.pampai.core.assistant.tools.aria.ConversazioniCercaTool())
      for (tool in tools) for (withIdentity in listOf(false, true)) {
        val output = withContext(turn) {
          if (withIdentity) tool.run(buildJsonObject { put("testo", "sentinel") }, ctx, ToolExecution("refused-memory"))
          else tool.run(buildJsonObject { put("testo", "sentinel") }, ctx)
        }
        assertTrue(output.text.contains("STORED_KNOWLEDGE_REFUSED"))
      }
      coVerify(exactly = 0) { memory.list() }
      coVerify(exactly = 0) { conversations.search(any(), any(), any()) }
      coEvery { memory.list() } returns emptyList()
      val allowed = foreground(f).captureTurn("Elenca quello che ricordi di me.", null, true)
      assertTrue(withContext(allowed) { tools.first().run(buildJsonObject {}, ctx) }.text.contains("vuota"))
      coVerify(exactly = 1) { memory.list() }
    }
  }

  @Test fun actualEngineRefusedMemoryCannotReachProviderAnswerCacheOrTitle() = runBlocking {
    Fixture().use { f ->
      val titleScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val e = EngineFixture(f, actions = false, titleScope = titleScope)
        val cid = e.repo.createConversation("fixture", 1)
        val sentinel = "REFUSED_MEMORY_SENTINEL"
        e.memory.add(sentinel, cid, f.clock.wall())
        e.emitCall = ToolCall("prohibited-read", "memoria_elenco", buildJsonObject {})
        val answer = e.request(cid, "Spiegami la media aritmetica; non usare la memoria.")
        titleScope.coroutineContext[Job]!!.children.toList().joinAll()
        assertEquals("fixture final answer", answer.answer)
        assertTrue(e.prompt.contains("STORED_KNOWLEDGE_REFUSED")) // Real tool denial reached the next round.
        assertTrue(e.providerInputs.none { it.messages.toString().contains(sentinel) })
        coVerify(atLeast = 1) { e.provider.complete(any()) } // Includes the actual title path.
        assertFalse(e.repo.conversation(cid)!!.title.contains(sentinel))
        assertTrue(e.repo.observeMessages(cid).first().none { it.text.contains(sentinel) })
        val field = AssistantEngine::class.java.getDeclaredField("memoryConversations").apply { isAccessible = true }
        val cached = (field.get(e.engine) as Map<*, *>)[cid]!!
        val conversation = cached.javaClass.getDeclaredField("conversation").apply { isAccessible = true }.get(cached) as dev.antigravity.fluidengine.ai.orchestrator.Conversation
        assertFalse(conversation.exchanges.toString().contains(sentinel))
        assertFalse(conversation.lastToolRound.toString().contains(sentinel))
        assertEquals(sentinel, e.memory.list().single().text) // Refusal does not delete the saved memory.
      } finally { titleScope.cancel() }
    }
  }

  @Test fun actualEngineNeverPublishesPrivateDoneBeforeDeferredSqliteCommitSucceeds() = runBlocking {
    // Robolectric already isolates each test's directory; keep the filename short on Windows.
    val dbName = "commit.db"
    context.deleteDatabase(dbName)
    try { Fixture(dbName).use { f ->
      seedProfile(f)
      val e = EngineFixture(f, actions = false)
      e.emitCall = ToolCall("read", "memoria_elenco", buildJsonObject {})
      val cid = e.repo.createConversation("fixture", 1)
      val sql = f.db.openHelper.writableDatabase
      sql.setForeignKeyConstraintsEnabled(true)
      // The run INSERT itself succeeds. SQLite rejects the deferred FK only at COMMIT,
      // after every SQL statement in the answer transaction has executed.
      sql.execSQL("CREATE TABLE deferred_answer_failure (parent_id INTEGER REFERENCES conversations(id) DEFERRABLE INITIALLY DEFERRED)")
      sql.execSQL("CREATE TRIGGER fail_private_commit AFTER INSERT ON runs WHEN NEW.outcome = 'private' BEGIN INSERT INTO deferred_answer_failure(parent_id) VALUES(-1); END")
      val failed = runCatching { e.request(cid) }
      assertTrue(e.prompt.contains("Profilo pertinente"))
      assertTrue(failed.isFailure || failed.getOrNull()?.answer == null)
      assertTrue(e.published.none { it is AssistantState.Done })
      assertTrue(e.published.none { it is AssistantState.Answering && it.partial.contains("fixture final answer") })
      // SQLite keeps a failed deferred COMMIT open, while the framework session has
      // already unwound. Verify that the SQL ran, then close/reopen the file to
      // roll back the injected native transaction without another Room transaction.
      sql.query("SELECT count(*) FROM runs WHERE outcome = 'private'").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
      val cache = AssistantEngine::class.java.getDeclaredField("memoryConversations").apply { isAccessible = true }.get(e.engine) as Map<*, *>
      assertFalse(cache.containsKey(cid))
      f.db.close()
      val reopened = Room.databaseBuilder(context, PampaiDatabase::class.java, dbName).build()
      try {
        val repo = ConversationsRepository(context, reopened.conversations(), reopened.messages(), reopened.attachments(), reopened.runs(), Json, reopened)
        assertTrue(repo.observeMessages(cid).first().none { it.text.contains("fixture final answer") })
        assertTrue(repo.observeRuns(cid).first().none { it.outcome == "private" })
        reopened.openHelper.readableDatabase.query("SELECT count(*) FROM deferred_answer_failure").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
      } finally { reopened.close() }
    }
    // The unchanged real acceptance/publication path succeeds without an injected fault.
    Fixture().use { f ->
      seedProfile(f)
      val e = EngineFixture(f, actions = false)
      e.emitCall = ToolCall("read", "memoria_elenco", buildJsonObject {})
      val cid = e.repo.createConversation("fixture", 1)
      val accepted = e.request(cid)
      assertEquals("fixture final answer", accepted.answer)
      assertTrue(e.published.any { it is AssistantState.Done })
      assertTrue(e.repo.observeRuns(cid).first().any { it.outcome == "private" })
    } } finally { context.deleteDatabase(dbName) }
  }

  @Test fun profileFirstRicordaWaitsForSafetyWithActionsOffAndRefusalDoesNotSave() = runBlocking {
    Fixture().use { f ->
      seedProfile(f)
      val e = EngineFixture(f, actions = false); val cid = e.repo.createConversation("fixture", 1)
      val pending = async { e.request(cid) }
      val confirmation = withTimeout(10000) { e.gate.current.filterNotNull().first() }
      assertEquals("Ricordare questo?", confirmation.title)
      assertTrue(e.prompt.contains("Profilo pertinente"))
      assertEquals(1, e.streamCount) // ricorda is the first tool, no manufactured reader trace.
      assertTrue(e.memory.list().isEmpty())
      e.gate.resolve(confirmation.id, false); pending.await()
      assertTrue(e.memory.list().isEmpty())
    }
  }

  @Test fun pendingProfilePromotionRejectsForgetRevokeAndCorrectionAfterConfirmation() = runBlocking {
    for (change in listOf("forget", "revoke", "correct")) Fixture().use { f ->
      val read = seedProfile(f)
      val e = EngineFixture(f, actions = false); val cid = e.repo.createConversation("fixture", 1)
      val pending = async { e.request(cid) }
      val confirmation = withTimeout(10000) { e.gate.current.filterNotNull().first() }
      assertTrue(e.memory.list().isEmpty())
      when (change) {
        "forget" -> f.profile.forget("fluidify.history_records")
        "revoke" -> f.sources.revoke(read.grantId)
        else -> {
          val captured = f.profile.retrieve(ProfileQuery(listOf("fluidify.history_records")), listOf(read)).single()
          f.profile.correct(captured.item.id, captured.item.field, "corrected fact")
        }
      }
      e.gate.resolve(confirmation.id, true)
      assertNull(pending.await().answer)
      assertTrue(e.memory.list().isEmpty())
      assertFalse(e.memory.promptBlock().contains("promoted private fact"))
      assertEquals("privacy_invalidated", e.repo.observeRuns(cid).first().last().outcome)
    }
  }

  @Test fun engineApprovedPromotionPersistsOnlyAfterSafetyAndTemporaryTurnNeverPromotes() = runBlocking {
    for (temporary in listOf(false, true)) Fixture().use { f ->
      seedProfile(f)
      val e = EngineFixture(f, actions = false)
      val cid = if (temporary) f.conversation(true) else e.repo.createConversation("fixture", 1)
      val pending = async { e.request(cid) }
      if (!temporary) {
        val confirmation = withTimeout(10000) { e.gate.current.filterNotNull().first() }
        assertTrue(e.memory.list().isEmpty())
        e.gate.resolve(confirmation.id, true)
      }
      val result = pending.await()
      if (temporary) {
        assertTrue(e.memory.list().isEmpty()); assertNull(e.gate.current.value)
      } else {
        assertNotNull(result.answer)
        assertEquals("promoted private fact", e.memory.list().single().text)
        f.profile.forget("fluidify.history_records")
        assertTrue(e.memory.promptBlock().contains("promoted private fact"))
      }
    }
  }

  @Test fun forgetAfterSafetyApprovalDuringProofRenewalStillPreventsTheInsert() = runBlocking {
    Fixture().use { f ->
      seedProfile(f)
      val e = EngineFixture(f, actions = false); val cid = e.repo.createConversation("fixture", 1)
      val pending = async { e.request(cid) }
      val confirmation = withTimeout(10000) { e.gate.current.filterNotNull().first() }
      var invalidated = false
      // The next renewal is inside Ricorda's post-consent commit, outside the Room lock.
      f.transport.beforeValidate = {
        f.transport.beforeValidate = {}
        invalidated = true
        f.profile.forget("fluidify.history_records")
      }
      e.gate.resolve(confirmation.id, true)
      assertNull(pending.await().answer)
      assertTrue(invalidated); assertTrue(e.memory.list().isEmpty())
    }
  }

  @Test fun cancellingPendingSafetyNeverInsertsMemory() = runBlocking {
    Fixture().use { f ->
      seedProfile(f)
      val e = EngineFixture(f, actions = false); val cid = e.repo.createConversation("fixture", 1)
      val pending = async { e.request(cid) }
      withTimeout(10000) { e.gate.current.filterNotNull().first() }
      pending.cancelAndJoin()
      assertTrue(e.memory.list().isEmpty())
    }
  }

  @Test fun currentApprovedPromotionIsIndependentAndBothToolOverloadsUseRealProvenance() = runBlocking {
    for (identity in listOf(false, true)) Fixture().use { f ->
      seedProfile(f)
      val e = EngineFixture(f, actions = false); val cid = e.repo.createConversation("fixture", 1)
      val turn = foreground(f).captureTurn("leggi i miei ascolti", ProfileQuery(listOf("fluidify.history_records")), true)
      assertTrue(turn.privateTurn)
      // Real context and gate, real DB. Neither a forged trace nor a mocked privacy guard.
      val ctx = PampaiToolContext(context, java.time.ZoneId.of("UTC"), java.util.Locale.ITALIAN, { f.clock.wall() },
        Surface.APP, AskMode.TEXT, false, e.gate, e.memory, e.repo, e.appSettings, AiHttp("fixture"), null,
        ModelCapabilities(false, false), cid, permissions = dev.pampa.pampai.core.assistant.permissions.PermissionGate(context),
        reminders = dev.pampa.pampai.core.assistant.reminders.ReminderRepository(context, f.db.reminders()),
        fluidify = dev.pampa.pampai.core.assistant.music.FluidifyClient(context),
        usage = dev.pampa.pampai.core.assistant.usage.UsageRepository(f.db.usageEvents()), aiSettings = e.settings)
      val tool = TracedTool(dev.pampa.pampai.core.assistant.tools.aria.RicordaTool())
      val pending = async { withContext(turn) {
        if (identity) tool.run(e.emitCall.arguments, ctx, ToolExecution("memory-identity"))
        else tool.run(e.emitCall.arguments, ctx)
      } }
      val confirmation = withTimeout(10000) { e.gate.current.filterNotNull().first() }
      assertTrue(ctx.traces.isEmpty()); assertTrue(e.memory.list().isEmpty())
      e.gate.resolve(confirmation.id, true); pending.await()
      assertEquals("promoted private fact", e.memory.list().single().text)
      f.profile.forget("fluidify.history_records")
      assertTrue(e.memory.promptBlock().contains("promoted private fact"))
    }
  }

  @Test fun admittedMislabelledReadIsNotSettledOrRetainedWhenRevokedBeforeFinalCompletion() = runBlocking {
    Fixture().use { f ->
      val read = f.music(); f.sources.connect(read)
      f.transport.onRead = { _, _ -> buildJsonObject { put("items", buildJsonArray {
        add(buildJsonObject { put("id", "record-1"); put("text", "private-read-sentinel") })
      }) } to null }
      val e = EngineFixture(f, actions = false)
      val catalog = SourceBridgePolicy.catalog(sourceCatalog().copy(packageName = read.selection.packageName,
        authority = read.selection.authority, tools = listOf(RemoteToolInfo(read.tool, "data", "misleading read",
          Schema.obj(emptyMap()), true, false, true))), BridgeNegotiation(2, setOf(BridgeProtocol.CAP_TYPED_RESULTS)))
      assertFalse(catalog.tools.single().action); assertFalse(catalog.tools.single().needsConfirmation)
      val raw = SourceFixture()
      e.registry.setRemote(listOf(RemoteToolSet.of(SourceBridgeCalls(raw, catalog), catalog, object : RemoteToolHost<PampaiToolContext> {
        override fun actionsEnabled(ctx: PampaiToolContext) = ctx.actionsEnabled
        override suspend fun confirm(ctx: PampaiToolContext, toolName: String, text: ConfirmationText): ToolOutput? =
          error("A read must not ask action confirmation")
      })))
      assertFalse(e.registry.catalog.value.registry.find("fluidify_history_records")!!.isAction)
      e.emitCall = ToolCall("read", "fluidify_history_records", read.arguments)
      val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
      e.beforeAnswer = { entered.complete(Unit); release.await() }
      val cid = e.repo.createConversation("fixture", 1)
      val pending = async { e.request(cid) }
      try {
        withTimeout(10000) { entered.await() }
        assertTrue(f.transport.calls.any { it.first == "source_read" }) // Admitted, actually read.
        assertTrue(e.prompt.contains("private-read-sentinel")) // Provider received the real output before revocation.
        assertTrue(raw.invocations.isEmpty()) // Proof-channel read, no generic dispatch bypass.
        assertTrue(f.db.operations().observe(cid).first().isEmpty())
        f.sources.revoke(read.grantId); release.complete(Unit)
        assertNull(pending.await().answer)
        assertTrue(f.db.operations().observe(cid).first().isEmpty())
        assertTrue(e.repo.observeRuns(cid).first().all { run -> run.tools.none { it.name == "fluidify_history_records" } })
        assertTrue(e.repo.exchanges(cid, null, 8).isEmpty())
        assertTrue(e.repo.observeMessages(cid).first().none { it.text.contains("private-read-sentinel") })
      } finally { release.complete(Unit); pending.cancelAndJoin() }
    }
  }

  @Test fun privacyTransactionRollsBackActualMemoryInsertAndRetainsRealUncertainWriteNoReplay() = runBlocking {
    Fixture().use { f ->
      seedProfile(f)
      val cid = f.conversation()
      val turn = foreground(f).captureTurn("leggi i miei ascolti", ProfileQuery(listOf("fluidify.history_records")), true)
      val memory = MemoryRepository(f.db.memories())
      rejected { turn.commit {
        memory.add("rollback fact", cid, f.clock.wall())
        throw TurnPrivacyInvalidated()
      } }
      assertTrue(memory.list().isEmpty())
      val catalog = SourceBridgePolicy.catalog(sourceCatalog("cv").copy(tools = listOf(RemoteToolInfo(
        "evento_rimuovi", "data", "explicit write", Schema.obj(emptyMap()), true, false, false))),
        BridgeNegotiation(2, setOf(BridgeProtocol.CAP_TYPED_RESULTS, BridgeProtocol.CAP_OPERATION_ID)))
      var dispatches = 0
      val raw = object : BridgeCalls by SourceFixture() {
        override suspend fun run(authority: String, name: String, args: JsonObject, confirmed: Boolean, requestId: String, language: String): RemoteResult {
          dispatches++; assertTrue(confirmed)
          return RemoteResult.Done("uncertain write", false, emptyList(), ToolResultDetails(ToolOutcome.UNCERTAIN))
        }
      }
      val tool = RemoteToolSet.of(SourceBridgeCalls(raw, catalog), catalog, object : RemoteToolHost<Unit> {
        override suspend fun confirm(ctx: Unit, toolName: String, text: ConfirmationText): ToolOutput? = null
      }).tools.single()
      val ledger = OperationLedger(f.db)
      val call = ToolCall("write", tool.name, buildJsonObject {})
      val attempt = ledger.attempt(cid, "lineage", 100, { SourceBridgePolicy.actionFor(it, false) })
      val execution = attempt.prepare(call)
      attempt.onOutput(call, execution, tool.run(call.arguments, Unit, execution))
      f.profile.forget("fluidify.history_records")
      rejected { turn.commit { fail("stale privacy context") } }
      assertEquals("UNCERTAIN", f.db.operations().get(execution.operationId)!!.state)
      assertEquals(execution.operationId, f.db.operations().observe(cid).first().single().operationId)
      try {
        ledger.attempt(cid, "lineage", 101, { SourceBridgePolicy.actionFor(it, false) }).prepare(call)
        fail("uncertain write replayed")
      } catch (_: PreviousOperationRequiresDecision) { }
      assertEquals(1, dispatches)
    }
  }
}
