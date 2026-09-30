package dev.pampa.pampai.core.assistant.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.antigravity.fluidengine.ai.orchestrator.AskMode
import dev.pampa.pampai.core.assistant.di.MIGRATION_3_4
import dev.pampa.pampai.core.assistant.di.PAMPAI_MIGRATIONS
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * La 3 -> 4 su un database vero (Robolectric), con gli schemi esportati da Room: ogni conversazione
 * vecchia deve diventare una catena che, mostrata, e' esattamente la lista di prima.
 *
 * `MigrationTestHelper` gira con un driver ([AndroidSQLiteDriver]) e non con SupportSQLite: quello
 * confronta il nome del file tagliando il percorso a '/', e su Windows (dove i test si lanciano)
 * non trova mai il database che lui stesso ha creato.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Migration3To4Test {

  private val name = "migrazione-3-4.db"
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }

  @get:Rule
  val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), file, AndroidSQLiteDriver(), PampaiDatabase::class)

  private fun SQLiteConnection.conversation(id: Long, temporary: Boolean = false) = execSQL(
    "INSERT INTO conversations (id, title, createdAtMillis, updatedAtMillis, lastProvider, source, pinned, loadedGroupsJson, autoTitled, plugin, `temporary`) " +
      "VALUES ($id, 'c$id', 0, 0, NULL, 'app', 0, NULL, 0, NULL, ${if (temporary) 1 else 0})",
  )

  private fun SQLiteConnection.message(id: Long, conversation: Long, role: String, at: Long) = execSQL(
    "INSERT INTO messages (id, conversationId, role, text, chipsJson, status, failureKind, createdAtMillis, mode) " +
      "VALUES ($id, $conversation, '$role', 't$id', NULL, 'DONE', NULL, $at, 'TEXT')",
  )

  private fun messages(db: SQLiteConnection, conversation: Long): List<MessageEntity> =
    db.prepare("SELECT id, conversationId, role, text, status, createdAtMillis, parentId, selectedAtMillis FROM messages WHERE conversationId = $conversation").use { s ->
      buildList {
        while (s.step()) {
          add(
            MessageEntity(
              id = s.getLong(0), conversationId = s.getLong(1), role = s.getText(2), text = s.getText(3), status = s.getText(4),
              createdAtMillis = s.getLong(5), parentId = if (s.isNull(6)) null else s.getLong(6), selectedAtMillis = s.getLong(7),
            ),
          )
        }
      }
    }

  private fun activeLeaf(db: SQLiteConnection, conversation: Long): Long? =
    db.prepare("SELECT activeLeafId FROM conversations WHERE id = $conversation").use { s -> if (s.step() && !s.isNull(0)) s.getLong(0) else null }

  /** L'ordine di prima: per millisecondo, e a parita' per id. */
  private fun oldOrder(all: List<MessageEntity>) = all.sortedWith(compareBy({ it.createdAtMillis }, { it.id })).map { it.id }

  /**
   * Tre conversazioni mescolate nel tempo: la 1 con due coppie di messaggi nello stesso
   * millisecondo (e un id alto nato prima di uno basso), la 2 vuota, la 3 temporanea.
   */
  private fun seedVersion3() {
    helper.createDatabase(3).apply {
      conversation(1)
      conversation(2)
      conversation(3, temporary = true)
      message(1, 1, "USER", at = 100)
      message(2, 1, "ASSISTANT", at = 101)
      message(5, 3, "USER", at = 150)
      message(3, 1, "USER", at = 200)
      message(4, 1, "ASSISTANT", at = 200)
      message(6, 3, "ASSISTANT", at = 151)
      message(7, 1, "USER", at = 300)
      message(9, 1, "ASSISTANT", at = 301)
      message(8, 1, "USER", at = 301)
      close()
    }
  }

  @Test
  fun `ogni conversazione diventa una catena nell'ordine di prima`() {
    seedVersion3()
    val db = helper.runMigrationsAndValidate(4, listOf(MIGRATION_3_4))

    val first = messages(db, 1)
    // Stesso millisecondo: decide l'id (3 prima di 4, 8 prima di 9), come nell'ORDER BY di prima.
    assertEquals(listOf(1L, 2L, 3L, 4L, 7L, 8L, 9L), oldOrder(first))
    val parents = first.associate { it.id to it.parentId }
    assertEquals(mapOf(1L to null, 2L to 1L, 3L to 2L, 4L to 3L, 7L to 4L, 8L to 7L, 9L to 8L), parents)
    assertEquals(9L, activeLeaf(db, 1))
    first.forEach { assertEquals(it.createdAtMillis, it.selectedAtMillis) }
    // Il cammino mostrato e' la lista di prima, senza versioni.
    val path = MessageTree.path(first, activeLeaf(db, 1))
    assertEquals(oldOrder(first), path.map { it.message.id })
    path.forEach { assertEquals(1, it.version.count) }

    // La conversazione vuota resta senza foglia; la temporanea migra come le altre.
    assertNull(activeLeaf(db, 2))
    val temporary = messages(db, 3)
    assertEquals(mapOf(5L to null, 6L to 5L), temporary.associate { it.id to it.parentId })
    assertEquals(6L, activeLeaf(db, 3))
    db.close()
  }

  @Test
  fun `Room apre il database migrato e il repository mostra la lista di prima`() = runBlocking {
    seedVersion3()
    // La strada vera: Room trova la versione 3, esegue le migrazioni e controlla lo schema contro le entita'.
    val room = Room.databaseBuilder(context, PampaiDatabase::class.java, name).addMigrations(*PAMPAI_MIGRATIONS).build()
    try {
      val repository = ConversationsRepository(context, room.conversations(), room.messages(), room.attachments(), room.runs(), Json { ignoreUnknownKeys = true }, room)
      val shown = repository.observeMessages(1).first()
      assertEquals(listOf(1L, 2L, 3L, 4L, 7L, 8L, 9L), shown.map { it.id })
      shown.forEach { assertNull(it.version) }
      assertEquals(listOf(5L, 6L), repository.observeMessages(3).first().map { it.id })
      assertEquals(emptyList<Message>(), repository.observeMessages(2).first())
      // La prossima domanda si attacca all'ultimo messaggio di prima.
      val turn = repository.startTurn(1, Anchor.Continue, "e poi?", AskMode.TEXT, emptyList(), now = 1_000)
      assertEquals(9L, turn.historyLeafId)
      assertEquals(listOf(1L, 2L, 3L, 4L, 7L, 8L, 9L, turn.userId, turn.assistantId), repository.observeMessages(1).first().map { it.id })
    } finally {
      room.close()
    }
  }
}
