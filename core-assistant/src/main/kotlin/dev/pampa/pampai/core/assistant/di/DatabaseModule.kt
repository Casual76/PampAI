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
      .addMigrations(MIGRATION_1_2)
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
private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
  override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE conversations ADD COLUMN plugin TEXT")
  }
}
