package dev.pampa.pampai.core.assistant.bridge

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.antigravity.fluidengine.ai.provider.ToolCall
import dev.antigravity.fluidengine.ai.tools.*
import dev.pampa.pampai.core.assistant.db.PampaiDatabase
import dev.pampa.pampai.core.assistant.receipts.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SourceReceiptPersistenceTest {
  @Test fun signedReadbackUpdatesPersistedReceiptAndLedgerStillBlocksWriteReplay() = runBlocking {
    val context: Context = ApplicationProvider.getApplicationContext()
    val name = "source-${System.nanoTime()}.db"
    context.getDatabasePath(name).parentFile?.mkdirs()
    fun open() = Room.databaseBuilder(context, PampaiDatabase::class.java, name).build()
    var db = open()
    val call = ToolCall("create", "fluidify_playlist_create", buildJsonObject { put("account", "alice"); put("name", "Real playlist") })
    val first = OperationLedger(db).attempt(1, "question", 2, { true })
    val identity = first.prepare(call)
    first.onOutput(call, identity, ToolOutput("Uncertain original", result = originalPlaylist().copy(operation = ToolOperationRef(identity.operationId, "fluidify"))))
    db.close()
    db = open()
    try {
      val fake = SourceFixture()
      val retry = OperationLedger(db).attempt(1, "question", 3, { true }, reconcile = { row ->
        val catalog = sourceCatalog()
        ToolOutput(row.outputText.orEmpty(), result = readSignedPlaylist(SourceBridgeCalls(fake, catalog), catalog.authority, row.toReceipt().result, row.operationId))
      })
      try { retry.prepare(call.copy(id = "retry")); fail("write replay admitted") } catch (_: PreviousOperationRequiresDecision) { }
      val receipt = db.operations().observe(1).first().single().toReceipt()
      assertEquals(identity.operationId, receipt.operationId)
      assertEquals(ToolOutcome.UNCERTAIN, receipt.result.outcome)
      assertEquals(2, receipt.result.data!!["items"]!!.jsonArray.size)
      assertTrue(fake.invocations.none { it.first == "playlist_create" })
    } finally { db.close(); context.deleteDatabase(name) }
  }
}
