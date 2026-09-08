package dev.pampa.pampai.core.assistant.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Le tabelle di PampAI. Nessuna chiave esterna: le cancellazioni le fa il repository, in ordine,
 * e i messaggi non hanno mai bisogno di sapere della conversazione piu' di quanto dica l'indice.
 */
@Entity(tableName = "conversations", indices = [Index("updatedAtMillis")])
data class ConversationEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0L,
  val title: String,
  val createdAtMillis: Long,
  val updatedAtMillis: Long,
  val lastProvider: String? = null,
  /** app, session, share. */
  val source: String = "app",
  val pinned: Boolean = false,
  /** Gli id dei gruppi di strumenti aperti nella conversazione, per ridarli all'orchestratore. */
  val loadedGroupsJson: String? = null,
  /** Vero quando il titolo l'ha scritto il modello, e non e' piu' la prima domanda troncata. */
  val autoTitled: Boolean = false,
  /**
   * Il plugin scelto dall'utente per questa conversazione: l'id di una categoria del catalogo
   * (`classeviva`, `meteo`, `dispositivo`...), o null. Non forza: apre i suoi strumenti in
   * partenza e lo dice al modello, che resta libero di usare altro se la domanda e' altro.
   */
  val plugin: String? = null,
  /**
   * La chat temporanea: non compare in cronologia, non prende un titolo dal modello, non entra
   * nella memoria a lungo termine, e sparisce quando la si lascia. Il nome della colonna e' una
   * parola chiave di SQLite, quindi nelle query scritte a mano va fra apici inversi.
   */
  val temporary: Boolean = false,
)

@Entity(tableName = "messages", indices = [Index("conversationId")])
data class MessageEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0L,
  val conversationId: Long,
  /** USER o ASSISTANT. */
  val role: String,
  val text: String,
  val chipsJson: String? = null,
  /** PENDING, STREAMING, DONE, FAILED, CANCELLED. */
  val status: String,
  val failureKind: String? = null,
  val createdAtMillis: Long,
  /** TEXT o VOICE. */
  val mode: String = "TEXT",
)

@Entity(tableName = "attachments", indices = [Index("messageId")])
data class AttachmentEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0L,
  val messageId: Long,
  /** IMAGE, DOCUMENT. */
  val kind: String,
  val mime: String,
  val name: String,
  val path: String,
  val bytes: Long,
)

/** Uno scambio con la sua telemetria: modelli, passi, token, costo, strumenti, contesto. */
@Entity(tableName = "runs", indices = [Index("conversationId"), Index("messageId")])
data class RunEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0L,
  val conversationId: Long,
  val messageId: Long,
  val startedAtMillis: Long,
  val finishedAtMillis: Long?,
  val steps: Int,
  val provider: String?,
  val routerModel: String?,
  val chatModel: String?,
  val deepModel: String?,
  val tierReached: String?,
  val groupsJson: String?,
  val promptTokens: Int?,
  val completionTokens: Int?,
  val costUsd: Double?,
  val waitedSeconds: Int,
  val toolTracesJson: String?,
  /** ok, failed, cancelled. */
  val outcome: String,
  val error: String?,
  val contextTokens: Int? = null,
  val contextWindow: Int? = null,
)

/** Una chiamata a un provider, per il tracker dei consumi. */
@Entity(tableName = "usage_events", indices = [Index("atMillis"), Index("provider")])
data class UsageEventEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0L,
  val atMillis: Long,
  val provider: String,
  val model: String,
  /** ROUTER, CHAT, DEEP, STT, TTS, SEARCH. */
  val kind: String,
  val promptTokens: Int?,
  val completionTokens: Int?,
  val costUsd: Double?,
  val audioSeconds: Double? = null,
  val durationMillis: Long,
  val conversationId: Long?,
  val error: String?,
  val rateLimited: Boolean,
  val remainingRequests: Int?,
  val remainingTokens: Int?,
)

/** Un fatto che Aria ricorda dell'utente. */
@Entity(tableName = "memories")
data class MemoryEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0L,
  val text: String,
  val createdAtMillis: Long,
  val sourceConversationId: Long?,
  val pinned: Boolean = false,
)

/** Un promemoria di PampAI: una notifica a un'ora, una volta o ricorrente. */
@Entity(tableName = "reminders", indices = [Index("atMillis")])
data class ReminderEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0L,
  val text: String,
  val atMillis: Long,
  /** NONE, DAILY, WEEKDAYS, WEEKLY. */
  val repeat: String = "NONE",
  val enabled: Boolean = true,
  val lastFiredAtMillis: Long? = null,
)

/**
 * La cronologia esclude sempre le temporanee (`observeAll`, `latest`, `observeCount`): una chat
 * temporanea esiste solo finche' e' aperta, e chi la sta guardando la chiede per id.
 */
@Dao
interface ConversationDao {
  @Query("SELECT * FROM conversations WHERE `temporary` = 0 ORDER BY pinned DESC, updatedAtMillis DESC")
  fun observeAll(): Flow<List<ConversationEntity>>

  @Query("SELECT * FROM conversations WHERE id = :id")
  fun observe(id: Long): Flow<ConversationEntity?>

  @Query("SELECT * FROM conversations WHERE id = :id")
  suspend fun get(id: Long): ConversationEntity?

  @Query("SELECT * FROM conversations WHERE `temporary` = 0 ORDER BY updatedAtMillis DESC LIMIT 1")
  suspend fun latest(): ConversationEntity?

  @Query("SELECT COUNT(*) FROM conversations WHERE `temporary` = 0")
  fun observeCount(): Flow<Int>

  /** Gli id delle temporanee da buttare: i loro messaggi, allegati e passaggi li toglie il repository. */
  @Query("SELECT id FROM conversations WHERE `temporary` = 1")
  suspend fun temporaryIds(): List<Long>

  @Insert
  suspend fun insert(entity: ConversationEntity): Long

  @Update
  suspend fun update(entity: ConversationEntity)

  @Query("DELETE FROM conversations WHERE id = :id")
  suspend fun delete(id: Long)

  @Query("DELETE FROM conversations")
  suspend fun deleteAll()
}

@Dao
interface MessageDao {
  @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAtMillis ASC, id ASC")
  fun observeByConversation(conversationId: Long): Flow<List<MessageEntity>>

  @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAtMillis ASC, id ASC")
  suspend fun listByConversation(conversationId: Long): List<MessageEntity>

  @Query("SELECT * FROM messages WHERE id = :id")
  suspend fun get(id: Long): MessageEntity?

  @Query("SELECT * FROM messages WHERE text LIKE '%' || :query || '%' ORDER BY createdAtMillis DESC LIMIT :limit")
  suspend fun search(query: String, limit: Int): List<MessageEntity>

  @Insert
  suspend fun insert(entity: MessageEntity): Long

  @Update
  suspend fun update(entity: MessageEntity)

  /** Il testo che si sta formando, scritto solo se il messaggio e' ancora aperto: la condizione sta qui, non in Kotlin. */
  @Query("UPDATE messages SET text = :text, status = :streaming WHERE id = :id AND status IN (:openStatuses)")
  suspend fun updatePartial(id: Long, text: String, streaming: String, openStatuses: List<String>)

  @Query("UPDATE messages SET status = :status, failureKind = :failureKind WHERE status IN (:staleStatuses)")
  suspend fun failStale(status: String, failureKind: String, staleStatuses: List<String>)

  @Query("DELETE FROM messages WHERE conversationId = :conversationId AND (createdAtMillis > :afterMillis OR (createdAtMillis = :afterMillis AND id > :afterId))")
  suspend fun deleteAfter(conversationId: Long, afterMillis: Long, afterId: Long)

  @Query("DELETE FROM messages WHERE conversationId = :conversationId")
  suspend fun deleteByConversation(conversationId: Long)

  @Query("DELETE FROM messages")
  suspend fun deleteAll()
}

@Dao
interface AttachmentDao {
  @Query("SELECT * FROM attachments WHERE messageId IN (:messageIds)")
  fun observeByMessages(messageIds: List<Long>): Flow<List<AttachmentEntity>>

  @Query("SELECT a.* FROM attachments a INNER JOIN messages m ON m.id = a.messageId WHERE m.conversationId = :conversationId")
  fun observeByConversation(conversationId: Long): Flow<List<AttachmentEntity>>

  @Query("SELECT * FROM attachments WHERE messageId = :messageId")
  suspend fun listByMessage(messageId: Long): List<AttachmentEntity>

  @Query("SELECT a.* FROM attachments a INNER JOIN messages m ON m.id = a.messageId WHERE m.conversationId = :conversationId")
  suspend fun listByConversation(conversationId: Long): List<AttachmentEntity>

  @Insert
  suspend fun insert(entity: AttachmentEntity): Long

  @Query("DELETE FROM attachments WHERE messageId IN (SELECT id FROM messages WHERE conversationId = :conversationId)")
  suspend fun deleteByConversation(conversationId: Long)

  @Query("DELETE FROM attachments WHERE messageId IN (:messageIds)")
  suspend fun deleteByMessages(messageIds: List<Long>)

  @Query("DELETE FROM attachments")
  suspend fun deleteAll()
}

@Dao
interface RunDao {
  @Query("SELECT * FROM runs WHERE conversationId = :conversationId ORDER BY startedAtMillis ASC")
  fun observeByConversation(conversationId: Long): Flow<List<RunEntity>>

  @Query("SELECT * FROM runs ORDER BY startedAtMillis DESC LIMIT :limit")
  fun observeRecent(limit: Int): Flow<List<RunEntity>>

  @Query("SELECT COUNT(*) FROM runs")
  fun observeCount(): Flow<Int>

  @Query("SELECT COALESCE(SUM(promptTokens), 0) + COALESCE(SUM(completionTokens), 0) FROM runs")
  fun observeTotalTokens(): Flow<Long>

  @Query("SELECT COALESCE(SUM(costUsd), 0.0) FROM runs")
  fun observeTotalCost(): Flow<Double>

  @Insert
  suspend fun insert(entity: RunEntity): Long

  @Query("DELETE FROM runs WHERE conversationId = :conversationId")
  suspend fun deleteByConversation(conversationId: Long)

  @Query("DELETE FROM runs WHERE messageId IN (:messageIds)")
  suspend fun deleteByMessages(messageIds: List<Long>)

  @Query("DELETE FROM runs")
  suspend fun deleteAll()
}

@Dao
interface UsageEventDao {
  @Insert
  suspend fun insert(entity: UsageEventEntity): Long

  @Query("SELECT * FROM usage_events WHERE atMillis >= :fromMillis ORDER BY atMillis DESC")
  fun observeSince(fromMillis: Long): Flow<List<UsageEventEntity>>

  @Query("SELECT COUNT(*) FROM usage_events WHERE atMillis >= :fromMillis AND provider = :provider AND error IS NULL")
  suspend fun countSince(fromMillis: Long, provider: String): Int

  @Query("DELETE FROM usage_events")
  suspend fun deleteAll()

  @Query("DELETE FROM usage_events WHERE atMillis < :beforeMillis")
  suspend fun prune(beforeMillis: Long)
}

@Dao
interface MemoryDao {
  @Query("SELECT * FROM memories ORDER BY pinned DESC, createdAtMillis DESC")
  fun observeAll(): Flow<List<MemoryEntity>>

  @Query("SELECT * FROM memories ORDER BY pinned DESC, createdAtMillis DESC")
  suspend fun listAll(): List<MemoryEntity>

  @Insert
  suspend fun insert(entity: MemoryEntity): Long

  @Update
  suspend fun update(entity: MemoryEntity)

  @Query("DELETE FROM memories WHERE id = :id")
  suspend fun delete(id: Long)

  @Query("DELETE FROM memories")
  suspend fun deleteAll()
}

@Dao
interface ReminderDao {
  @Query("SELECT * FROM reminders ORDER BY atMillis ASC")
  fun observeAll(): Flow<List<ReminderEntity>>

  @Query("SELECT * FROM reminders WHERE enabled = 1 ORDER BY atMillis ASC")
  suspend fun listEnabled(): List<ReminderEntity>

  @Query("SELECT * FROM reminders WHERE id = :id")
  suspend fun get(id: Long): ReminderEntity?

  @Insert
  suspend fun insert(entity: ReminderEntity): Long

  @Update
  suspend fun update(entity: ReminderEntity)

  @Query("DELETE FROM reminders WHERE id = :id")
  suspend fun delete(id: Long)
}

/**
 * La versione dello schema. Costante e non un numero dentro l'annotazione perche' le migrazioni
 * (in `DatabaseModule`) devono arrivare esattamente fin qui, e un test lo verifica: alzare la
 * versione senza scrivere la migrazione vuol dire buttare le conversazioni dell'utente.
 */
const val PAMPAI_DB_VERSION = 3

@Database(
  entities = [ConversationEntity::class, MessageEntity::class, AttachmentEntity::class, RunEntity::class, UsageEventEntity::class, MemoryEntity::class, ReminderEntity::class],
  version = PAMPAI_DB_VERSION,
  exportSchema = false,
)
abstract class PampaiDatabase : RoomDatabase() {
  abstract fun conversations(): ConversationDao
  abstract fun messages(): MessageDao
  abstract fun attachments(): AttachmentDao
  abstract fun runs(): RunDao
  abstract fun usageEvents(): UsageEventDao
  abstract fun memories(): MemoryDao
  abstract fun reminders(): ReminderDao
}
