package dev.pampa.pampai.core.assistant.runtime

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.antigravity.fluidengine.ai.keys.*
import dev.antigravity.fluidengine.ai.orchestrator.AskMode
import dev.antigravity.fluidengine.ai.provider.ToolCall
import dev.antigravity.fluidengine.ai.tools.ToolOutcome
import dev.pampa.pampai.core.assistant.db.*
import dev.pampa.pampai.core.assistant.music.FluidifyClient
import dev.pampa.pampai.core.assistant.receipts.*
import dev.pampa.pampai.core.assistant.remote.RemoteSwitches
import dev.pampa.pampai.core.assistant.screen.ScreenContextStore
import dev.pampa.pampai.core.assistant.settings.*
import dev.pampa.pampai.core.assistant.tools.RegistryHolder
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual engine -> quick matching -> real traced music tool -> durable ledger -> cancellation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QuickCancellationIntegrationTest {
  @Test fun editedQuickWriteKeepsRealCancelledTraceReceiptAndReplayGuardAfterReopen() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val name = "quick-cancellation-r4.db"
    context.deleteDatabase(name)
    var db = Room.databaseBuilder(context, PampaiDatabase::class.java, name).build()
    fun repository() = ConversationsRepository(context, db.conversations(), db.messages(), db.attachments(), db.runs(), Json, db)
    val runtime = mockk<AssistantRuntime>(relaxed = true)
    val settings = mockk<AiSettingsStore>()
    val appSettings = mockk<PampaiSettingsStore>()
    val remote = mockk<RemoteSwitches>()
    val music = mockk<FluidifyClient>()
    val receiptActions = mockk<ReceiptActions>()
    val entered = CompletableDeferred<Unit>()
    every { runtime.attach(any(), any()) } returns true
    coEvery { runtime.preflight(any()) } returns Unit
    coEvery { settings.current() } returns AiSettings(enabled = true, actionsEnabled = true)
    coEvery { appSettings.current() } returns PampaiSettings(quickCommands = true)
    coEvery { remote.stopMessage() } returns null
    every { music.installed } returns true
    coEvery { music.pause() } coAnswers { entered.complete(Unit); awaitCancellation() }
    coEvery { receiptActions.reconcile(any()) } returns null
    try {
      val repo = repository()
      val cid = repo.createConversation("synthetic quick", 1)
      val old = repo.startTurn(cid, Anchor.Continue, "old question", AskMode.TEXT, emptyList(), 2)
      repo.complete(old.assistantId, "old answer", emptyList())
      val engine = AssistantEngine(context, runtime, RegistryHolder(), mockk(relaxed = true), settings,
        appSettings, repo, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), ScreenContextStore(),
        mockk(relaxed = true), mockk(relaxed = true), music, remote, mockk(relaxed = true),
        OperationLedger(db), receiptActions, RuntimeBudgetCoordinator(dev.pampa.pampai.core.assistant.durable.BudgetLedger(db), db), mockk(relaxed = true))
      val pending = async(Dispatchers.Default) {
        engine.execute(AssistantRequest(cid, "pausa la musica", AskMode.TEXT, anchor = Anchor.Edit(old.questionId)), 1)
      }
      withTimeout(10_000) { entered.await() }
      pending.cancelAndJoin()
      val run = repo.observeRuns(cid).first().single()
      val messageId = run.messageId
      val operation = db.operations().forMessage(messageId).single()
      db.close()
      db = Room.databaseBuilder(context, PampaiDatabase::class.java, name).build()
      val reopened = repository()
      val cancelled = reopened.message(messageId)!!
      val storedRun = reopened.observeRuns(cid).first().single()
      assertEquals(MessageStatus.CANCELLED, cancelled.status)
      assertEquals("cancelled", storedRun.outcome)
      assertEquals("musica_controllo", storedRun.tools.single().name)
      assertEquals(ToolOutcome.UNCERTAIN, storedRun.tools.single().outcome)
      assertEquals(ToolOutcome.UNCERTAIN, cancelled.receipts.single().result!!.outcome)
      val call = ToolCall("retry", "musica_controllo", buildJsonObject { put("azione", "pausa") })
      try {
        OperationLedger(db).attempt(cid, operation.lineageId, messageId + 100, { true }).prepare(call)
        fail("A cancelled quick write must never be replayed automatically")
      } catch (e: PreviousOperationRequiresDecision) { assertEquals(operation.operationId, e.previous.operationId) }
      coVerify(exactly = 1) { music.pause() }
    } finally { db.close(); context.deleteDatabase(name) }
  }
}
