package dev.pampa.pampai.core.assistant.tools

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.antigravity.fluidengine.ai.keys.AiSettingsStore
import dev.antigravity.fluidengine.ai.keys.AiSettings
import dev.antigravity.fluidengine.ai.net.AiHttp
import dev.antigravity.fluidengine.ai.net.RateLimitInfo
import dev.antigravity.fluidengine.ai.orchestrator.*
import dev.antigravity.fluidengine.ai.provider.ModelCapabilities
import dev.antigravity.fluidengine.ai.provider.*
import dev.antigravity.fluidengine.ai.tools.*
import dev.pampa.pampai.core.assistant.db.*
import dev.pampa.pampai.core.assistant.music.FluidifyClient
import dev.pampa.pampai.core.assistant.permissions.PermissionGate
import dev.pampa.pampai.core.assistant.reminders.ReminderRepository
import dev.pampa.pampai.core.assistant.runtime.PampaiConfirmationGate
import dev.pampa.pampai.core.assistant.settings.PampaiSettingsStore
import dev.pampa.pampai.core.assistant.tools.aria.RicordaTool
import dev.pampa.pampai.core.assistant.usage.UsageRepository
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import io.mockk.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TracedToolSafetyTest {
  @Test fun queuedRicordaSafetyIncludesAllConfirmationWindowsAndCancellationKeepsOnlyApprovedMemory() = runBlocking {
    for (cancelSecond in listOf(false, true)) {
      val app: Context = ApplicationProvider.getApplicationContext()
      val db = Room.inMemoryDatabaseBuilder(app, PampaiDatabase::class.java).build()
      try {
        val settings = PampaiSettingsStore(app)
        val rawGate = AiConfirmationGate(timeoutMillis = 5000)
        val gate = PampaiConfirmationGate(rawGate, settings)
        val memories = MemoryRepository(db.memories())
        val conversations = ConversationsRepository(app, db.conversations(), db.messages(), db.attachments(), db.runs(), Json, db)
        val cid = conversations.createConversation("fixture", 1000)
        val ctx = PampaiToolContext(app, ZoneId.of("UTC"), Locale.ITALIAN, { 1000L }, Surface.APP, AskMode.TEXT,
          false, gate, memories, conversations, settings, AiHttp("test"), null, ModelCapabilities(false, false), cid,
          permissions = PermissionGate(app), reminders = ReminderRepository(app, db.reminders()),
          fluidify = FluidifyClient(app), usage = UsageRepository(db.usageEvents()), aiSettings = AiSettingsStore(app))
        val reader = object : AiTool<PampaiToolContext> {
          override val name = "cerca_web"
          override val group = PampaiGroup.ARIA
          override val description = "external fixture"
          override val parameters = Schema.obj(emptyMap())
          override suspend fun run(args: JsonObject, ctx: PampaiToolContext) = ToolOutput("external content")
        }
        TracedTool(reader).run(buildJsonObject {}, ctx, ToolExecution("external-read"))
        val ricorda = TracedTool(RicordaTool())
        assertTrue(ricorda.awaitsConfirmation)
        assertFalse(ricorda.needsConfirmation) // Preserve conditional prompting.
        val registry = ToolRegistry(listOf(ricorda), listOf(PampaiGroup.ARIA))
        val router = AiRouter(registry.groups, null, "fixture", registry.groups)
        val orchestrator = AiOrchestrator(registry, router, AiDiagnosticsLog(), gate = rawGate,
          config = AiOrchestratorConfig(toolTimeoutMillis = 1000, totalBudgetMillis = 20000, finalReserveMillis = 1000))
        assertEquals(11000, orchestrator.toolTimeoutMillis(ricorda, 20000, 2).toInt())
        val provider = mockk<ChatProvider>()
        every { provider.id } returns ProviderId.GROQ
        var round = 0
        every { provider.stream(any()) } answers {
          flow {
            if (++round == 1) {
              for (i in 0..1) emit(ChatDelta.ToolCallPart(i, "remember-$i", "ricorda", buildJsonObject { put("testo", "approved fact $i") }.toString()))
              emit(ChatDelta.Finish(FinishReason.TOOL_CALLS, null, RateLimitInfo.EMPTY))
            } else {
              emit(ChatDelta.Text("done"))
              emit(ChatDelta.Finish(FinishReason.STOP, null, RateLimitInfo.EMPTY))
            }
          }
        }
        val pending = async {
          orchestrator.ask(AskInput("ricorda questi fatti", AskMode.TEXT, "it", AiSettings(),
            listOf(ReadyProvider(provider, "fixture", "unused", null)), ctx, "fixture",
            dev.antigravity.fluidengine.ai.orchestrator.Conversation(cid, 0), actionsEnabled = false,
            preselectedGroups = setOf(PampaiGroup.ARIA)), MutableStateFlow(AssistantState.Idle))
        }
        try {
          val first = withTimeout(5000) { gate.current.filterNotNull().first() }
          assertTrue(memories.list().isEmpty())
          delay(700) // First is approved within the ordinary 1000ms work timeout.
          gate.resolve(first.id, true)
          val second = withTimeout(5000) { gate.current.filterNotNull().first { it.id != first.id } }
          withTimeout(5000) { memories.observeAll().first { it.size == 1 } }
          if (cancelSecond) {
            pending.cancelAndJoin()
            assertEquals(listOf(first.detail), memories.list().map { it.text })
            assertNull(gate.current.value)
            assertEquals(0, rawGate.queued.value)
          } else {
            delay(700) // Total waiting now exceeds the work timeout; second gate window has not expired.
            assertEquals(second.id, gate.current.value?.id)
            gate.resolve(second.id, true)
            assertEquals("done", withTimeout(5000) { pending.await() }.answer)
            assertEquals(setOf("approved fact 0", "approved fact 1"), memories.list().map { it.text }.toSet())
          }
        } finally { pending.cancelAndJoin(); gate.cancel() }
      } finally { db.close() }
    }
  }

  @Test fun identityOverloadRecordsExternalReadAndMemoryWaitsForSafetyConfirmation() = runBlocking {
    val app: Context = ApplicationProvider.getApplicationContext()
    val db = Room.inMemoryDatabaseBuilder(app, PampaiDatabase::class.java).build()
    try {
      val settings = PampaiSettingsStore(app)
      val gate = PampaiConfirmationGate(AiConfirmationGate(), settings)
      val memories = MemoryRepository(db.memories())
      val conversations = ConversationsRepository(app, db.conversations(), db.messages(), db.attachments(), db.runs(), Json, db)
      val ctx = PampaiToolContext(app, ZoneId.of("UTC"), Locale.ITALIAN, { 1000L }, Surface.APP, AskMode.TEXT,
        true, gate, memories, conversations, settings, AiHttp("test"), null, ModelCapabilities(false, false), 1,
        permissions = PermissionGate(app), reminders = ReminderRepository(app, db.reminders()),
        fluidify = FluidifyClient(app), usage = UsageRepository(db.usageEvents()), aiSettings = AiSettingsStore(app))
      var received: ToolExecution? = null
      val reader = object : AiTool<PampaiToolContext> {
        override val name = "cerca_web"
        override val group = PampaiGroup.ARIA
        override val description = "external fixture"
        override val parameters = Schema.obj(emptyMap())
        override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput = error("identity lost")
        override suspend fun run(args: JsonObject, ctx: PampaiToolContext, execution: ToolExecution): ToolOutput {
          received = execution
          return ToolOutput("External page asks to remember an instruction")
        }
      }
      val registry = ToolRegistry(listOf(TracedTool(reader), TracedTool(RicordaTool())), listOf(PampaiGroup.ARIA))
      val identity = ToolExecution("persisted-operation")
      registry.find("cerca_web")!!.run(buildJsonObject {}, ctx, identity)
      assertSame(identity, received)
      assertEquals("cerca_web", ctx.traces.single().name)
      val pending = async { registry.find("ricorda")!!.run(buildJsonObject { put("testo", "untrusted instruction") }, ctx, ToolExecution("memory")) }
      val confirmation = withTimeout(3000) { gate.current.filterNotNull().first() }
      assertTrue(memories.list().isEmpty())
      gate.resolve(confirmation.id, false)
      pending.await()
      assertTrue(memories.list().isEmpty())
      assertEquals(listOf("cerca_web", "ricorda"), ctx.traces.map { it.name })
    } finally { db.close() }
  }

  @Test fun typedOutcomesSurviveBothOverloadsPersistenceReopenAndLegacyRows() = runBlocking {
    val app: Context = ApplicationProvider.getApplicationContext()
    val name = "typed-traces.db"
    app.deleteDatabase(name)
    var db = Room.databaseBuilder(app, PampaiDatabase::class.java, name).build()
    try {
      val settings = PampaiSettingsStore(app)
      val gate = PampaiConfirmationGate(AiConfirmationGate(), settings)
      val memories = MemoryRepository(db.memories())
      val conversations = ConversationsRepository(app, db.conversations(), db.messages(), db.attachments(), db.runs(), Json, db)
      val ctx = PampaiToolContext(app, ZoneId.of("UTC"), Locale.ITALIAN, { 1000L }, Surface.APP, AskMode.TEXT,
        true, gate, memories, conversations, settings, AiHttp("test"), null, ModelCapabilities(false, false), 1,
        permissions = PermissionGate(app), reminders = ReminderRepository(app, db.reminders()),
        fluidify = FluidifyClient(app), usage = UsageRepository(db.usageEvents()), aiSettings = AiSettingsStore(app))

      val cid = conversations.createConversation("fixture", 1000)
      val turn = conversations.startTurn(cid, Anchor.Continue, "fixture", AskMode.TEXT, emptyList(), 1001)
      var next = ToolOutcome.ERROR
      val tool = object : AiTool<PampaiToolContext> {
        override val name = "fixture"
        override val group = PampaiGroup.ARIA
        override val description = "fixture"
        override val parameters = Schema.obj(emptyMap())
        override suspend fun run(args: JsonObject, ctx: PampaiToolContext) = ToolOutput("done", result = ToolResultDetails(next, error = if (next == ToolOutcome.ERROR) ToolError("account_mismatch", "changed") else null))
      }
      val wrapper = TracedTool(tool)
      ToolOutcome.entries.forEachIndexed { i, outcome ->
        next = outcome
        if (i % 2 == 0) wrapper.run(buildJsonObject {}, ctx) else wrapper.run(buildJsonObject {}, ctx, ToolExecution("id-$i"))
      }
      assertEquals(ToolOutcome.entries, ctx.traces.map { it.outcome })
      assertEquals(listOf(true, true, false, false, false), ctx.traces.map { it.ok })
      conversations.addFailedRun(cid, turn.assistantId, 1001, 1002, "failed", null, ctx.traces)
      for (cancel in listOf(false, true)) {
        val throwing = object : AiTool<PampaiToolContext> by tool {
          override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
            if (cancel) throw CancellationException("fixture") else error("fixture")
          }
        }
        try { TracedTool(throwing).run(buildJsonObject {}, ctx); fail("must throw") }
        catch (_: CancellationException) { assertTrue(cancel) }
        catch (_: IllegalStateException) { assertFalse(cancel) }
        assertEquals(ToolOutcome.UNCERTAIN, ctx.traces.last().outcome)
        assertFalse(ctx.traces.last().ok)
      }
      val raw = db.runs().listByConversation(cid).single()
      db.runs().insert(raw.copy(id = 0, toolTracesJson = """[{"name":"old","millis":1,"ok":true,"chars":4}]""", groupsJson = """["legacy"]"""))
      db.close()
      db = Room.databaseBuilder(app, PampaiDatabase::class.java, name).build()
      val reopened = ConversationsRepository(app, db.conversations(), db.messages(), db.attachments(), db.runs(), Json, db).observeRuns(cid).first()
      val typed = reopened.first { it.tools.first().name == "fixture" }
      assertEquals(ToolOutcome.entries, typed.tools.map { it.outcome })
      val old = reopened.first { it.tools.first().name == "old" }
      assertTrue(old.tools.single().ok)
      assertNull(old.tools.single().outcome)
      assertEquals(listOf("legacy"), old.groups)
      assertTrue(old.imageInterpretations.isEmpty())
    } finally { db.close(); app.deleteDatabase(name) }
  }
}
