package dev.pampa.pampai.core.assistant.receipts

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.pampai.core.assistant.db.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Migration4To5Test {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val name = "migration-ledger.db"
  private val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
  @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), file, AndroidSQLiteDriver(), PampaiDatabase::class)
  @Test fun `five to six preserves ledger and audits different queued efforts durably`() = runBlocking {
    helper.createDatabase(5).use { db ->
      db.execSQL("INSERT INTO tool_operations (operationId,conversationId,lineageId,messageId,attemptId,equivalence,toolName,argumentsJson,isWrite,state,createdAtMillis,updatedAtMillis) VALUES ('op',1,'q',2,'old','key','write','{}',1,'DISPATCHED',0,0)")
    }
    var db = Room.databaseBuilder(context, PampaiDatabase::class.java, name).build()
    db.dispatches().insert(DispatchAuditEntity(attemptId = "queued-low", conversationId = 1, messageId = 2,
      atMillis = 1, provider = "GROQ", model = "selected", requested = "LOW", resolved = "LOW", effective = "low", support = "SENT_UNVERIFIED", adaptation = null))
    db.dispatches().insert(DispatchAuditEntity(attemptId = "queued-high", conversationId = 1, messageId = 2,
      atMillis = 2, provider = "GROQ", model = "selected", requested = "HIGH", resolved = "HIGH", effective = null, support = "OMITTED", adaptation = "reasoning_effort"))
    db.close()
    db = Room.databaseBuilder(context, PampaiDatabase::class.java, name).build()
    try {
      assertEquals("DISPATCHED", db.operations().get("op")!!.state)
      val rows = db.dispatches().forMessage(2)
      assertEquals(listOf("LOW", "HIGH"), rows.map { it.requested })
      assertEquals(listOf("selected", "selected"), rows.map { it.model })
      assertNull(rows.last().effective); assertEquals("OMITTED", rows.last().support)
    } finally { db.close() }
  }

  @Test fun `additive migration preserves conversations messages memory and legacy receipts remain absent`() = runBlocking {
    helper.createDatabase(4).use { db ->
      db.execSQL("INSERT INTO conversations (id,title,createdAtMillis,updatedAtMillis,lastProvider,source,pinned,loadedGroupsJson,autoTitled,plugin,`temporary`,activeLeafId) VALUES (1,'saved',0,0,NULL,'app',0,NULL,0,NULL,0,2)")
      db.execSQL("INSERT INTO messages (id,conversationId,role,text,chipsJson,status,failureKind,createdAtMillis,mode,parentId,selectedAtMillis) VALUES (2,1,'ASSISTANT','old text',NULL,'DONE',NULL,0,'TEXT',NULL,0)")
      db.execSQL("INSERT INTO memories (id,text,createdAtMillis,sourceConversationId,pinned) VALUES (3,'remember',0,1,1)")
    }
    val db = Room.databaseBuilder(context, PampaiDatabase::class.java, name).build()
    try {
      assertEquals("saved", db.conversations().get(1)!!.title)
      assertEquals("old text", db.messages().get(2)!!.text)
      assertEquals("remember", db.memories().listAll().single().text)
      assertTrue(db.operations().forMessage(2).isEmpty())
      val repository = ConversationsRepository(context, db.conversations(), db.messages(), db.attachments(), db.runs(), kotlinx.serialization.json.Json, db)
      assertTrue(repository.message(2)!!.receipts.isEmpty())
    } finally { db.close() }
  }
}
