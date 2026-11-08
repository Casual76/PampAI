package dev.pampa.pampai.core.assistant.receipts

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.antigravity.fluidengine.ai.provider.ToolCall
import dev.antigravity.fluidengine.ai.tools.*
import dev.pampa.pampai.core.assistant.db.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OperationLedgerTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private fun call(id: String = "call") = ToolCall(id, "evento_crea", buildJsonObject { put("titolo", "Prova") })
  private fun open(name: String) = Room.databaseBuilder(context, PampaiDatabase::class.java, name).build()
  private suspend fun blocked(block: suspend () -> Unit): PreviousOperationRequiresDecision {
    try { block() } catch (e: PreviousOperationRequiresDecision) { return e }
    throw AssertionError("Duplicate write was dispatched")
  }

  @Test fun cancelledWriteSurvivesReopen() = runBlocking {
    val name = "cancel.db"
    context.getDatabasePath(name).parentFile?.mkdirs()
    var db = open(name)
    fun repository() = ConversationsRepository(context, db.conversations(), db.messages(), db.attachments(), db.runs(), Json, db)
    val repo = repository()
    val c = repo.createConversation("write", 1)
    val original = repo.startTurn(c, Anchor.Continue, "create", dev.antigravity.fluidengine.ai.orchestrator.AskMode.TEXT, emptyList(), 2)
    repo.complete(original.assistantId, "old", emptyList())
    val turn = repo.startTurn(c, Anchor.Regenerate(original.assistantId), "", dev.antigravity.fluidengine.ai.orchestrator.AskMode.TEXT, emptyList(), 3)
    val execution = OperationLedger(db).attempt(c, turn.questionId.toString(), turn.assistantId, { true }).prepare(call())
    var effects = 1 // external side effect occurred; cancellation arrived before acknowledgement/text
    repo.discardVersion(turn)
    db.close()
    db = open(name)
    try {
      assertEquals(MessageStatus.CANCELLED, repository().message(turn.assistantId)!!.status)
      assertEquals(execution.operationId, repository().message(turn.assistantId)!!.receipts.single().operationId)
      blocked { OperationLedger(db).attempt(c, turn.questionId.toString(), turn.assistantId + 1, { true }).prepare(call("retry")); effects++ }
      assertEquals(1, effects)
      assertEquals(ToolOutcome.UNCERTAIN, db.operations().get(execution.operationId)!!.toReceipt().result.outcome)
    } finally { db.close(); context.deleteDatabase(name) }
  }

  @Test fun `process interruption after durable reservation never dispatches a second write`() = runBlocking {
    val name = "ledger-death-${System.nanoTime()}.db"
    var db = open(name)
    val execution = OperationLedger(db).attempt(1, "10", 11, { true }).prepare(call())
    db.close() // no onOutput: simulated process death after dispatch, before acknowledgement
    db = open(name)
    try {
      val e = blocked { OperationLedger(db).attempt(1, "10", 12, { true }).prepare(call("regenerated-call")) }
      assertEquals(execution.operationId, e.previous.operationId)
      assertEquals(ToolOutcome.UNCERTAIN, e.previous.toReceipt().result.outcome)
      assertEquals("DISPATCHED", e.previous.state)
    } finally { db.close(); context.deleteDatabase(name) }
  }

  @Test fun `retry is blocked but identical intentional next user request dispatches`() = runBlocking {
    val db = Room.inMemoryDatabaseBuilder(context, PampaiDatabase::class.java).build()
    try {
      val ledger = OperationLedger(db)
      val first = ledger.attempt(1, "question-1", 2, { true })
      val execution = first.prepare(call())
      first.onOutput(call(), execution, ClockReceipts.timer(60, "Test"))
      blocked { ledger.attempt(1, "question-1", 3, { true }).prepare(call("retry")) }
      val second = ledger.attempt(1, "question-2", 5, { true }).prepare(call("intentional"))
      assertNotEquals(execution.operationId, second.operationId)
      assertEquals(ToolOutcome.ACCEPTED, db.operations().get(execution.operationId)!!.toReceipt().result.outcome)
    } finally { db.close() }
  }

  @Test fun `uncertain result reconciles by readback before blocking retry and preserves original receipt`() = runBlocking {
    val db = Room.inMemoryDatabaseBuilder(context, PampaiDatabase::class.java).build()
    try {
      val ledger = OperationLedger(db)
      val attempt = ledger.attempt(1, "q", 2, { true })
      val ex = attempt.prepare(call())
      attempt.onOutput(call(), ex, CalendarReceipts.uncertain("timeout"))
      var reads = 0
      val retry = ledger.attempt(1, "q", 3, { true }) {
        reads++
        ToolOutput("riletto", result = ToolResultDetails(ToolOutcome.COMPLETED, entities = listOf(ToolEntityRef("android.calendar", "event", "42"))))
      }
      val blocked = blocked { retry.prepare(call("retry")) }
      assertEquals(1, reads)
      assertEquals(ToolOutcome.COMPLETED, blocked.previous.toReceipt().result.outcome)
      retry.onOutput(call(), null, ToolOutput.error("blocked"))
      assertEquals("riletto", db.operations().get(ex.operationId)!!.outputText)
    } finally { db.close() }
  }

  @Test fun `repeated model tool call in same request cannot dispatch duplicate action`() = runBlocking {
    val db = Room.inMemoryDatabaseBuilder(context, PampaiDatabase::class.java).build()
    try {
      val attempt = OperationLedger(db).attempt(1, "q", 2, { true })
      val first = attempt.prepare(call("1"))
      assertEquals(first.operationId, blocked { attempt.prepare(call("2")) }.previous.operationId)
    } finally { db.close() }
  }

  @Test fun `legacy output cannot certify write completion and full typed output persists`() = runBlocking {
    val db = Room.inMemoryDatabaseBuilder(context, PampaiDatabase::class.java).build()
    try {
      val attempt = OperationLedger(db).attempt(1, "q", 2, { true })
      val ex = attempt.prepare(call())
      attempt.onOutput(call(), ex, ToolOutput("fatto"))
      assertEquals(ToolOutcome.UNCERTAIN, db.operations().get(ex.operationId)!!.toReceipt().result.outcome)
      val text = "evidence".repeat(2000)
      val details = ToolResultDetails(ToolOutcome.PARTIAL, data = buildJsonObject { put("full", text) },
        sources = listOf(ToolSourceRef("test", account = "account")), coverage = ToolCoverage(false),
        freshness = ToolFreshness(123L), pagination = ToolPagination("cursor", true, 100))
      attempt.onOutput(call(), ex, ToolOutput(text, result = details))
      val saved = db.operations().get(ex.operationId)!!.toReceipt()
      assertEquals(text, saved.text); assertEquals(details, saved.result)
    } finally { db.close() }
  }

  @Test fun `regeneration with different model arguments cannot replay an interrupted write`() = runBlocking {
    val db = Room.inMemoryDatabaseBuilder(context, PampaiDatabase::class.java).build()
    try {
      val ledger = OperationLedger(db)
      val first = ledger.attempt(1, "q", 2, { true }).prepare(call())
      val changed = ToolCall("different", "timer_crea", buildJsonObject { put("durata", "dieci minuti") })
      assertEquals(first.operationId, blocked { ledger.attempt(1, "q", 3, { true }).prepare(changed) }.previous.operationId)
    } finally { db.close() }
  }

  @Test fun `timer dispatch receipt never invents countdown completion or entity ID`() {
    val receipt = ClockReceipts.timer(90, "Pasta").result!!
    assertEquals(ToolOutcome.ACCEPTED, receipt.outcome)
    assertEquals(90, receipt.data!!["requestedDurationSeconds"]!!.jsonPrimitive.int)
    assertEquals("Pasta", receipt.data!!["title"]!!.jsonPrimitive.content)
    assertTrue(receipt.entities.isEmpty())
    assertNull(receipt.data!!["endsAt"])
  }
}
