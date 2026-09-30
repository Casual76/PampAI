package dev.pampa.pampai.core.assistant.di

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.pampa.pampai.core.assistant.db.AttachmentDao
import dev.pampa.pampai.core.assistant.db.ConversationDao
import dev.pampa.pampai.core.assistant.db.DatabaseRecovery
import dev.pampa.pampai.core.assistant.db.MemoryDao
import dev.pampa.pampai.core.assistant.db.MessageDao
import dev.pampa.pampai.core.assistant.db.PAMPAI_DB_NAME
import dev.pampa.pampai.core.assistant.db.PampaiDatabase
import dev.pampa.pampai.core.assistant.db.RecoveryNotice
import dev.pampa.pampai.core.assistant.db.ReminderDao
import dev.pampa.pampai.core.assistant.db.RunDao
import dev.pampa.pampai.core.assistant.db.UsageEventDao
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Singleton
import kotlinx.serialization.json.Json

private const val TAG = "PampaiDatabase"

/** Il database di PampAI e i suoi DAO, piu' il Json con cui si scrivono le colonne serializzate. */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

  /**
   * Il database, aperto subito e non alla prima query: e' l'unico modo di vedere un errore di
   * migrazione in un punto solo. Aperto alla prima query, l'errore saltava fuori dentro qualche
   * `runCatching` (e le conversazioni sembravano sparite) o, peggio, faceva cadere l'app a ogni
   * avvio.
   *
   * Prima di aprirlo, una copia di sicurezza della versione 3 ([backupBeforeMigration]): l'app non
   * fa backup (`allowBackup=false`) e la 3 -> 4 e' la prima migrazione che riscrive delle righe. Se
   * l'apertura fallisce lo stesso, i file rotti si mettono da parte, si riparte da un database
   * vuoto e [DatabaseRecovery] lo dice alla UI: mai un'app che non parte piu'.
   */
  @Provides
  @Singleton
  fun provideDatabase(@ApplicationContext context: Context, recovery: DatabaseRecovery): PampaiDatabase {
    runCatching { backupBeforeMigration(context, PAMPAI_DB_NAME) }.onFailure { Log.w(TAG, "copia di sicurezza non riuscita", it) }
    val database = build(context)
    return try {
      database.openHelper.writableDatabase
      database
    } catch (e: Throwable) {
      Log.e(TAG, "il database non si apre: lo metto da parte e ne apro uno nuovo", e)
      runCatching { database.close() }
      val now = System.currentTimeMillis()
      val movedTo = moveAside(context, PAMPAI_DB_NAME, now)
      recovery.report(RecoveryNotice(now, movedTo, e.message ?: e::class.java.simpleName))
      val fresh = build(context)
      // Se non si apre nemmeno questo (disco pieno, per dire) gli errori usciranno alle query, dove
      // sono gia' gestiti: qui l'importante e' non cadere.
      runCatching { fresh.openHelper.writableDatabase }.onFailure { Log.e(TAG, "anche il database nuovo non si apre", it) }
      fresh
    }
  }

  private fun build(context: Context): PampaiDatabase =
    Room.databaseBuilder(context, PampaiDatabase::class.java, PAMPAI_DB_NAME)
      .addMigrations(*PAMPAI_MIGRATIONS)
      // Solo all'indietro (una versione vecchia installata sopra una nuova) si ricomincia da zero.
      // In avanti ogni versione ha la sua migrazione, verificata sugli schemi in core-assistant/schemas:
      // un buco deve fallire in sviluppo, non cancellare in silenzio le conversazioni dell'utente.
      .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
      .build()

  @Provides fun conversations(db: PampaiDatabase): ConversationDao = db.conversations()
  @Provides fun messages(db: PampaiDatabase): MessageDao = db.messages()
  @Provides fun attachments(db: PampaiDatabase): AttachmentDao = db.attachments()
  @Provides fun runs(db: PampaiDatabase): RunDao = db.runs()
  @Provides fun usageEvents(db: PampaiDatabase): UsageEventDao = db.usageEvents()
  @Provides fun memories(db: PampaiDatabase): MemoryDao = db.memories()
  @Provides fun reminders(db: PampaiDatabase): ReminderDao = db.reminders()

  @Provides
  @Singleton
  fun provideJson(): Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
}

/** I file di un database SQLite: lui, il suo WAL e la memoria condivisa, se ci sono. */
private val SQLITE_SUFFIXES = listOf("", "-wal", "-shm")

/**
 * Una copia del database alla versione 3, fatta una volta sola prima che la migrazione lo tocchi:
 * `<nome>.v3-backup` (piu' `-wal` e `-shm`, rinominabili insieme per tornare indietro). La versione
 * si legge con un'apertura in sola lettura chiusa subito, prima che Room lo apra.
 */
internal fun backupBeforeMigration(context: Context, name: String) {
  val file = context.getDatabasePath(name)
  if (!file.exists()) return
  val backup = File(file.parentFile, "$name.v3-backup")
  if (backup.exists()) return
  val version = runCatching {
    SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
      db.rawQuery("PRAGMA user_version", null).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else -1 }
    }
  }.getOrElse {
    // In sola lettura un database in WAL senza `-shm` puo' non aprirsi: allora l'intestazione del
    // file, dove `user_version` sta in 4 byte all'offset 60 (cambia solo con una migrazione).
    RandomAccessFile(file, "r").use { raf -> if (raf.length() < 64) -1 else raf.run { seek(60); readInt() } }
  }
  if (version != 3) return
  SQLITE_SUFFIXES.forEach { suffix ->
    val source = File(file.path + suffix)
    if (source.exists()) source.copyTo(File(backup.path + suffix), overwrite = true)
  }
  Log.i(TAG, "copia di sicurezza della versione 3 in ${backup.name}")
}

/** Sposta i file del database che non si apre in `<nome>.broken-<millis>`: restano, ma non bloccano l'app. */
private fun moveAside(context: Context, name: String, now: Long): String {
  val file = context.getDatabasePath(name)
  val target = "$name.broken-$now"
  SQLITE_SUFFIXES.forEach { suffix ->
    val source = File(file.path + suffix)
    if (source.exists()) {
      val moved = File(file.parentFile, target + suffix)
      if (!source.renameTo(moved)) {
        // Rinominare puo' fallire (un'altra connessione aperta): allora si copia e si cancella.
        runCatching { source.copyTo(moved, overwrite = true) }
        source.delete()
      }
    }
  }
  return target
}

/**
 * Una migrazione fatta solo di comandi SQL, eseguibile in tutti e due i modi di Room: sul
 * `SupportSQLiteDatabase` (l'app) e su una connessione di un driver (i test con
 * `MigrationTestHelper`, che su Windows funziona solo con un driver: quello "support" confronta i
 * percorsi tagliando a '/').
 */
private class SqlMigration(start: Int, end: Int, private val statements: List<String>) : Migration(start, end) {
  override fun migrate(db: SupportSQLiteDatabase) = statements.forEach { db.execSQL(it) }

  override fun migrate(connection: SQLiteConnection) = statements.forEach { connection.execSQL(it) }
}

/** 1 -> 2: il plugin scelto per la conversazione. Una colonna in piu', tutto il resto uguale. */
internal val MIGRATION_1_2: Migration = SqlMigration(1, 2, listOf("ALTER TABLE conversations ADD COLUMN plugin TEXT"))

/**
 * Il comando della 2 -> 3, a parte perche' un test lo legge: `temporary` e' una parola chiave di
 * SQLite e va fra apici inversi, e il `DEFAULT 0` e' cio' che rende la migrazione innocua — le
 * conversazioni gia' su disco restano dove sono, semplicemente non sono temporanee.
 */
internal const val SQL_ADD_TEMPORARY = "ALTER TABLE conversations ADD COLUMN `temporary` INTEGER NOT NULL DEFAULT 0"

/** 2 -> 3: la chat temporanea. Una colonna in piu', e le vecchie righe valgono 0 (non temporanee). */
internal val MIGRATION_2_3: Migration = SqlMigration(2, 3, listOf(SQL_ADD_TEMPORARY))

/**
 * I comandi della 3 -> 4, le versioni delle risposte: i messaggi diventano un albero. A parte
 * perche' un test li legge: solo colonne aggiunte, righe aggiornate e un indice, niente di
 * cancellato. Ogni conversazione vecchia diventa una catena, il padre di un messaggio e' quello
 * che lo precedeva (a parita' di millisecondo decide l'id, come nell'ordine di prima), e la foglia
 * attiva e' l'ultimo messaggio: il cammino mostrato e' esattamente la lista di prima.
 */
internal val SQL_MIGRATION_3_4 = listOf(
  "ALTER TABLE messages ADD COLUMN parentId INTEGER",
  "ALTER TABLE messages ADD COLUMN selectedAtMillis INTEGER NOT NULL DEFAULT 0",
  "ALTER TABLE conversations ADD COLUMN activeLeafId INTEGER",
  "UPDATE messages SET parentId = (SELECT p.id FROM messages p WHERE p.conversationId = messages.conversationId AND (p.createdAtMillis < messages.createdAtMillis OR (p.createdAtMillis = messages.createdAtMillis AND p.id < messages.id)) ORDER BY p.createdAtMillis DESC, p.id DESC LIMIT 1)",
  "UPDATE messages SET selectedAtMillis = createdAtMillis",
  "UPDATE conversations SET activeLeafId = (SELECT m.id FROM messages m WHERE m.conversationId = conversations.id ORDER BY m.createdAtMillis DESC, m.id DESC LIMIT 1)",
  // Il nome e' quello che Room da' a `@Index("parentId")`: con un altro la validazione fallisce.
  "CREATE INDEX IF NOT EXISTS `index_messages_parentId` ON `messages` (`parentId`)",
)

/** 3 -> 4: l'albero dei messaggi. */
internal val MIGRATION_3_4: Migration = SqlMigration(3, 4, SQL_MIGRATION_3_4)

/**
 * Tutte le migrazioni, in ordine: devono coprire ogni passo fino a PAMPAI_DB_VERSION, perche'
 * un buco fa fallire l'apertura del database: meglio in sviluppo che con i dati di qualcuno.
 */
internal val PAMPAI_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
