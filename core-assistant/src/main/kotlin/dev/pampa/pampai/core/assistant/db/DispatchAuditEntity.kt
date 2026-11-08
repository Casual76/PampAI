package dev.pampa.pampai.core.assistant.db

import androidx.room.*

@Entity(tableName = "provider_dispatches", indices = [Index("messageId")])
data class DispatchAuditEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val attemptId: String,
  val conversationId: Long,
  val messageId: Long,
  val atMillis: Long,
  val provider: String,
  val model: String,
  val requested: String,
  val resolved: String,
  val effective: String?,
  val support: String,
  val adaptation: String?,
)

@Dao
interface DispatchAuditDao {
  @Query("DELETE FROM provider_dispatches WHERE conversationId = :id")
  suspend fun deleteConversation(id: Long)
  @Query("DELETE FROM provider_dispatches")
  suspend fun deleteAll()
  @Insert suspend fun insert(row: DispatchAuditEntity): Long
  @Query("SELECT * FROM provider_dispatches WHERE messageId = :id ORDER BY id")
  suspend fun forMessage(id: Long): List<DispatchAuditEntity>
}
