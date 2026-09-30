package dev.pampa.pampai.core.assistant.db

import dev.pampa.pampai.core.assistant.di.PAMPAI_MIGRATIONS
import dev.pampa.pampai.core.assistant.di.SQL_ADD_TEMPORARY
import dev.pampa.pampai.core.assistant.di.SQL_MIGRATION_3_4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le migrazioni del database, provate senza Room: il *collegamento* e i comandi. Che il database
 * migrato sia davvero quello che Room si aspetta lo prova `Migration3To4Test`, con Robolectric.
 *
 * Il collegamento e' l'errore che costa caro: una versione alzata senza la sua migrazione fa
 * fallire l'apertura, e li' le conversazioni dell'utente finiscono da parte.
 */
class MigrationsTest {

  @Test
  fun `le migrazioni coprono ogni passo fino alla versione del database`() {
    val steps = PAMPAI_MIGRATIONS.map { it.startVersion to it.endVersion }
    assertEquals(listOf(1 to 2, 2 to 3, 3 to 4), steps)
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
  fun `la 3 a 4 aggiunge colonne, aggiorna righe e crea un indice, senza cancellare niente`() {
    SQL_MIGRATION_3_4.forEach { sql ->
      assertTrue(sql, sql.startsWith("ALTER TABLE") || sql.startsWith("UPDATE") || sql.startsWith("CREATE INDEX IF NOT EXISTS"))
      assertFalse(sql, sql.contains("DROP", ignoreCase = true))
      assertFalse(sql, sql.contains("DELETE", ignoreCase = true))
    }
    // La colonna NOT NULL ha il suo default, come dichiara l'entita' (@ColumnInfo(defaultValue = "0")).
    assertTrue(SQL_MIGRATION_3_4.any { it.contains("selectedAtMillis INTEGER NOT NULL DEFAULT 0") })
    // L'indice ha il nome che Room da' a @Index("parentId"): con un altro, la validazione fallisce.
    assertTrue(SQL_MIGRATION_3_4.any { it.contains("`index_messages_parentId`") })
  }

  @Test
  fun `una conversazione nasce non temporanea, come il default della colonna`() {
    val conversation = ConversationEntity(title = "Che tempo fa", createdAtMillis = 0L, updatedAtMillis = 0L)
    assertFalse(conversation.temporary)
    assertTrue(conversation.copy(temporary = true).temporary)
    assertNull(conversation.activeLeafId)
    val message = MessageEntity(conversationId = 1, role = "USER", text = "ciao", status = "DONE", createdAtMillis = 0L)
    assertNull(message.parentId)
    assertEquals(0L, message.selectedAtMillis)
  }
}
