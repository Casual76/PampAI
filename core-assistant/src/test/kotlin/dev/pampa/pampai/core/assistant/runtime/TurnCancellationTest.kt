package dev.pampa.pampai.core.assistant.runtime

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.antigravity.fluidengine.ai.orchestrator.AskMode
import dev.antigravity.fluidengine.ai.provider.ContentPart
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.antigravity.fluidengine.ai.provider.ToolCall
import dev.antigravity.fluidengine.ai.tools.ToolOutcome
import dev.pampa.pampai.core.assistant.db.*
import dev.pampa.pampai.core.assistant.receipts.OperationLedger
import dev.pampa.pampai.core.assistant.receipts.PreviousOperationRequiresDecision
import dev.pampa.pampai.core.assistant.tools.PampaiToolTrace
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Executes the same cancellation closure as AssistantEngine, not direct addFailedRun alone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TurnCancellationTest {
  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test fun retainedEmptyEditAndRegenerationKeepHelperAndTraceAfterReopen() = runBlocking {
    for (editing in listOf(false, true)) {
      val name = "cancel-evidence-$editing.db"
      context.deleteDatabase(name)
      var db = Room.databaseBuilder(context, PampaiDatabase::class.java, name).build()
      fun repository() = ConversationsRepository(context, db.conversations(), db.messages(), db.attachments(), db.runs(), Json, db)
      try {
        val repo = repository()
        val cid = repo.createConversation("cancel", 1)
        val original = repo.startTurn(cid, Anchor.Continue, "original", AskMode.TEXT, emptyList(), 2)
        repo.complete(original.assistantId, "previous answer", emptyList())
        val anchor = if (editing) Anchor.Edit(original.questionId) else Anchor.Regenerate(original.assistantId)
        val turn = repo.startTurn(cid, anchor, "edit", AskMode.TEXT, emptyList(), 3)
        val helper = ExactModel(ProviderId.GEMINI, "fixture-vision")
        val policy = ModelPolicy(ExactModel(ProviderId.GROQ, "fixture-text"), helper = helper)
        val cache = ImageInterpretationCache()
        val evidence = mutableListOf<RunImageInterpretation>()
        val pixels = listOf(ContentPart.Image(byteArrayOf(1), "image/png"))
        repeat(2) { cache.interpret(pixels, policy, { evidence.add(it); Unit }) { _, _ -> "description" } }
        assertEquals(listOf(false, true), evidence.map { it.cached })
        val call = ToolCall("call", "evento_crea", buildJsonObject {})
        val operation = OperationLedger(db).attempt(cid, turn.questionId.toString(), turn.assistantId, { true }).prepare(call)
        val trace = PampaiToolTrace("evento_crea", "{}", 1, false, 0, "Stopped before acknowledgement", outcome = ToolOutcome.UNCERTAIN)
        assertTrue(closeCancelledTurn(repo, turn, true, null, 3, 4, listOf(trace), evidence))
        db.close()
        db = Room.databaseBuilder(context, PampaiDatabase::class.java, name).build()
        val reopened = repository()
        val message = reopened.message(turn.assistantId)!!
        assertEquals(MessageStatus.CANCELLED, message.status)
        assertEquals(operation.operationId, message.receipts.single().operationId)
        val run = reopened.observeRuns(cid).first().single { it.messageId == turn.assistantId }
        assertEquals("cancelled", run.outcome)
        assertEquals(evidence, run.imageInterpretations)
        assertEquals(ToolOutcome.UNCERTAIN, run.tools.single().outcome)
        try {
          OperationLedger(db).attempt(cid, turn.questionId.toString(), turn.assistantId + 100, { true })
            .prepare(call.copy(id = "retry"))
          fail("Retained write must still prevent replay")
        } catch (e: PreviousOperationRequiresDecision) { assertEquals(operation.operationId, e.previous.operationId) }
      } finally { db.close(); context.deleteDatabase(name) }
    }
  }

  @Test fun genuinelyDiscardedEmptyVersionDoesNotCreateAnOrphanRun() = runBlocking {
    val db = Room.inMemoryDatabaseBuilder(context, PampaiDatabase::class.java).build()
    try {
      val repo = ConversationsRepository(context, db.conversations(), db.messages(), db.attachments(), db.runs(), Json, db)
      val cid = repo.createConversation("discard", 1)
      val original = repo.startTurn(cid, Anchor.Continue, "original", AskMode.TEXT, emptyList(), 2)
      repo.complete(original.assistantId, "previous answer", emptyList())
      val turn = repo.startTurn(cid, Anchor.Regenerate(original.assistantId), "", AskMode.TEXT, emptyList(), 3)
      val evidence = listOf(RunImageInterpretation(ProviderId.GEMINI, "fixture", false))
      assertFalse(closeCancelledTurn(repo, turn, true, null, 3, 4, emptyList(), evidence))
      assertNull(repo.message(turn.assistantId))
      assertEquals("previous answer", repo.message(original.assistantId)!!.text)
      assertTrue(repo.observeRuns(cid).first().isEmpty())
    } finally { db.close() }
  }
}
