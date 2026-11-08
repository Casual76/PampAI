package dev.pampa.pampai.core.assistant.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** No destructive recovery: a dispatched row is evidence that an effect MAY have happened. */
@Entity(tableName = "tool_operations", indices = [Index(value = ["conversationId", "lineageId", "equivalence"], unique = true), Index("messageId")])
data class OperationEntity(
  @PrimaryKey val operationId: String,
  val conversationId: Long,
  val lineageId: String,
  val messageId: Long,
  val attemptId: String,
  val equivalence: String,
  val toolName: String,
  val argumentsJson: String,
  val isWrite: Boolean,
  val state: String,
  val createdAtMillis: Long,
  val updatedAtMillis: Long,
  val outputText: String? = null,
  val resultJson: String? = null,
)

@Dao
interface OperationDao {
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun reserve(row: OperationEntity): Long
  @Query("SELECT * FROM tool_operations WHERE conversationId = :conversationId AND lineageId = :lineage AND equivalence = :key")
  suspend fun equivalent(conversationId: Long, lineage: String, key: String): OperationEntity?
  @Query("SELECT * FROM tool_operations WHERE conversationId = :conversationId AND lineageId = :lineage AND isWrite = 1 AND (attemptId != :attempt OR (toolName = :tool AND state IN ('DISPATCHED', 'UNCERTAIN', 'ACCEPTED', 'PARTIAL'))) ORDER BY rowid LIMIT 1")
  suspend fun priorWrite(conversationId: Long, lineage: String, attempt: String, tool: String): OperationEntity?
  @Query("SELECT * FROM tool_operations WHERE operationId = :id")
  suspend fun get(id: String): OperationEntity?
  @Query("UPDATE tool_operations SET state = :state, outputText = :text, resultJson = :result, updatedAtMillis = :now WHERE operationId = :id")
  suspend fun finish(id: String, state: String, text: String, result: String?, now: Long)
  @Query("SELECT * FROM tool_operations WHERE conversationId = :conversationId ORDER BY createdAtMillis, operationId")
  fun observe(conversationId: Long): Flow<List<OperationEntity>>
  @Query("SELECT * FROM tool_operations WHERE messageId = :messageId ORDER BY createdAtMillis, operationId")
  suspend fun forMessage(messageId: Long): List<OperationEntity>
  @Query("DELETE FROM tool_operations WHERE messageId IN (:ids)")
  suspend fun deleteMessages(ids: List<Long>)
  @Query("DELETE FROM tool_operations WHERE conversationId = :id")
  suspend fun deleteConversation(id: Long)
  @Query("DELETE FROM tool_operations")
  suspend fun deleteAll()
}
