package dev.pampa.pampai.core.assistant.receipts

import androidx.room.withTransaction
import dev.antigravity.fluidengine.ai.provider.ToolCall
import dev.antigravity.fluidengine.ai.tools.*
import dev.pampa.pampai.core.assistant.db.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Same instance is safe across attempts. An attempt adapter MUST be recreated for each retry.
 * lineageId is the persisted USER message id, including regeneration of its assistant answer.
 * A new explicit user command gets a new lineage, even when its wording is identical.
 */
@Singleton
class OperationLedger @Inject constructor(private val db: PampaiDatabase) {
  fun attempt(conversationId: Long, lineageId: String, messageId: Long, isWrite: (String) -> Boolean,
    reconcile: suspend (OperationEntity) -> ToolOutput? = { null }): LedgerAttempt =
    LedgerAttempt(db, conversationId, lineageId, messageId, isWrite, reconcile)
}

class PreviousOperationRequiresDecision(val previous: OperationEntity) : IllegalStateException(
  "Operazione ${previous.operationId} gia' inviata (${previous.state}). Rileggere il risultato; per ripeterla serve un nuovo comando esplicito. Nessuna ripetizione automatica.")

class LedgerAttempt internal constructor(
  private val db: PampaiDatabase, private val conversationId: Long, private val lineage: String,
  private val messageId: Long, private val isWrite: (String) -> Boolean,
  private val reconcile: suspend (OperationEntity) -> ToolOutput?,
) {
  private val dao = db.operations()
  private val attemptId = UUID.randomUUID().toString()
  private val preparedCalls = mutableMapOf<String, ToolExecution>()

  suspend fun prepare(call: ToolCall): ToolExecution {
    // A hook invoked twice for a call must not invent a second occurrence.
    synchronized(this) { preparedCalls[call.id] }?.let { throw IllegalStateException("Chiamata gia' preparata: ${it.operationId}") }
    val canonical = canonical(call.arguments).toString()
    val fingerprint = sha256(call.name + "\n" + canonical)
    val write = isWrite(call.name)
    // Reads are always safe to refresh; writes share identity across retry/regeneration only.
    val key = if (write) fingerprint else UUID.randomUUID().toString()
    val now = System.currentTimeMillis()
    val row = OperationEntity(UUID.randomUUID().toString(), conversationId, lineage, messageId, attemptId, key,
      call.name, canonical, write, "DISPATCHED", now, now)
    // The INSERT commits durably before this method grants dispatch. A crash after it is uncertain.
    val previous = db.withTransaction {
      // Different argument spellings or changed model calls during regeneration cannot bypass
      // recovery. A fresh attempt with prior writes requires a deliberate new USER request.
      val prior = if (write) dao.priorWrite(conversationId, lineage, attemptId, call.name) else null
      prior ?: if (dao.reserve(row) == -1L) requireNotNull(dao.equivalent(conversationId, lineage, key)) else null
    }
    if (previous != null) {
      if (previous.isWrite) {
        val verified = reconcile(previous)
        if (verified != null) record(previous.operationId, verified)
      }
      throw PreviousOperationRequiresDecision(requireNotNull(dao.get(previous.operationId)))
    }
    return ToolExecution(row.operationId).also { synchronized(this) { preparedCalls[call.id] = it } }
  }

  suspend fun onOutput(call: ToolCall, execution: ToolExecution?, output: ToolOutput) {
    execution ?: return // validator/blocked retry never replaces an earlier receipt
    record(execution.operationId, output)
  }

  private suspend fun record(id: String, output: ToolOutput) = withContext(NonCancellable) {
    val row = dao.get(id) ?: return@withContext
    val reported = output.result
    val result = if (row.isWrite && reported?.error?.code == "tool_error") reported.copy(outcome = ToolOutcome.UNCERTAIN) else reported ?: ToolResultDetails(if (row.isWrite) ToolOutcome.UNCERTAIN else ToolOutcome.ACCEPTED,
      error = if (row.isWrite) ToolError("legacy_unverified", "La fonte non certifica l'effetto") else null)
    dao.finish(id, result.outcome.name, output.text, ReceiptCodec.encode(result).toString(), System.currentTimeMillis())
  }
}

internal fun canonical(value: JsonElement): JsonElement = when (value) {
  is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
  is JsonArray -> JsonArray(value.map(::canonical))
  else -> value
}
private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

/** Reactive domain receipt; legacy messages have an empty list, never widgets inferred from text. */
data class ToolReceipt(val operationId: String, val toolName: String, val text: String,
  val result: ToolResultDetails, val updatedAtMillis: Long)

fun OperationEntity.toReceipt(): ToolReceipt {
  val details = resultJson?.let { runCatching { ReceiptCodec.decode(Json.parseToJsonElement(it) as? JsonObject) }.getOrNull() }
    ?: ToolResultDetails(ToolOutcome.UNCERTAIN, error = ToolError("interrupted", "Invio registrato, esito non verificato. Non ripetuto automaticamente."))
  return ToolReceipt(operationId, toolName, outputText.orEmpty(), details, updatedAtMillis)
}
