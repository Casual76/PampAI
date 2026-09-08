package dev.pampa.pampai.core.assistant.db

import dev.pampa.pampai.core.assistant.di.PAMPAI_MIGRATIONS
import dev.pampa.pampai.core.assistant.di.SQL_ADD_TEMPORARY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le migrazioni del database, provate senza Room.
 *
 * Room qui non si puo' aprire: e' un test JVM, e non c'e' ne' Robolectric ne' `room-testing`. Cio'
 * che si puo' provare — e che e' anche l'errore che costa caro — e' il *collegamento*: una versione
 * alzata senza la sua migrazione fa cadere l'aggiornamento in `fallbackToDestructiveMigration`, e
 * li' le conversazioni dell'utente non ci sono piu'.
 */
class MigrationsTest {

  @Test
  fun `le migrazioni coprono ogni passo fino alla versione del database`() {
    val steps = PAMPAI_MIGRATIONS.map { it.startVersion to it.endVersion }
    assertEquals(listOf(1 to 2, 2 to 3), steps)
    assertEquals(PAMPAI_DB_VERSION, steps.last().second)
    // Nessun salto: ogni migrazione riparte da dove finisce quella prima.
    steps.zipWithNext().forEach { (before, after) -> assertEquals(before.second, after.first) }
  }

  @Test
  fun `la 2 a 3 aggiunge la colonna delle temporanee senza toccare le conversazioni gia' salvate`() {
    val sql = SQL_ADD_TEMPORARY
    assertTrue(sql, sql.startsWith("ALTER TABLE conversations ADD COLUMN"))
    // Il nome fra apici inversi: `temporary` e' una parola chiave di SQLite.
    assertTrue(sql, sql.contains("`temporary`"))
    // NOT NULL con un default: e' questo a rendere l'aggiornamento innocuo per le righe vecchie.
    assertTrue(sql, sql.contains("INTEGER NOT NULL DEFAULT 0"))
    // Nient'altro: niente tabelle ricreate, niente righe cancellate.
    assertFalse(sql, sql.contains("DROP", ignoreCase = true))
    assertFalse(sql, sql.contains("DELETE", ignoreCase = true))
  }

  @Test
  fun `una conversazione nasce non temporanea, come il default della colonna`() {
    val conversation = ConversationEntity(title = "Che tempo fa", createdAtMillis = 0L, updatedAtMillis = 0L)
    assertFalse(conversation.temporary)
    assertTrue(conversation.copy(temporary = true).temporary)
  }
}
