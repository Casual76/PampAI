package dev.pampa.pampai.core.assistant.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.pampa.pampai.core.assistant.db.AttachmentDao
import dev.pampa.pampai.core.assistant.db.ConversationDao
import dev.pampa.pampai.core.assistant.db.MemoryDao
import dev.pampa.pampai.core.assistant.db.MessageDao
import dev.pampa.pampai.core.assistant.db.PampaiDatabase
import dev.pampa.pampai.core.assistant.db.ReminderDao
import dev.pampa.pampai.core.assistant.db.RunDao
import dev.pampa.pampai.core.assistant.db.UsageEventDao
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/** Il database di PampAI e i suoi DAO, piu' il Json con cui si scrivono le colonne serializzate. */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

  @Provides
  @Singleton
  fun provideDatabase(@ApplicationContext context: Context): PampaiDatabase =
    Room.databaseBuilder(context, PampaiDatabase::class.java, "pampai.db")
      .addMigrations(*PAMPAI_MIGRATIONS)
      // Solo per gli schemi che nessuna migrazione conosce: le conversazioni dell'utente non
      // sono un dato che si butta perche' e' cambiata una colonna.
      .fallbackToDestructiveMigration(dropAllTables = true)
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

/** 1 -> 2: il plugin scelto per la conversazione. Una colonna in piu', tutto il resto uguale. */
internal val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
  override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE conversations ADD COLUMN plugin TEXT")
  }
}

/**
 * Il comando della 2 -> 3, a parte perche' un test lo legge: `temporary` e' una parola chiave di
 * SQLite e va fra apici inversi, e il `DEFAULT 0` e' cio' che rende la migrazione innocua — le
 * conversazioni gia' su disco restano dove sono, semplicemente non sono temporanee.
 */
internal const val SQL_ADD_TEMPORARY = "ALTER TABLE conversations ADD COLUMN `temporary` INTEGER NOT NULL DEFAULT 0"

/** 2 -> 3: la chat temporanea. Una colonna in piu', e le vecchie righe valgono 0 (non temporanee). */
internal val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
  override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
    db.execSQL(SQL_ADD_TEMPORARY)
  }
}

/**
 * Tutte le migrazioni, in ordine: devono coprire ogni passo fino a PAMPAI_DB_VERSION, perche'
 * il buco lo raccoglie `fallbackToDestructiveMigration`, e li' le conversazioni si perdono.
 */
internal val PAMPAI_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
