package dev.pampa.pampai.core.assistant.runtime

import dev.pampa.pampai.core.assistant.db.ConversationsRepository
import dev.pampa.pampai.core.assistant.db.RunImageInterpretation
import dev.pampa.pampai.core.assistant.db.Turn
import dev.pampa.pampai.core.assistant.tools.PampaiToolTrace

/** Shared production cleanup: a retained write reservation also retains its attempt evidence.
 * Returns false only when the empty version was actually discarded (or already absent). */
internal suspend fun closeCancelledTurn(
  conversations: ConversationsRepository,
  turn: Turn,
  discardEmptyVersion: Boolean,
  partial: String?,
  startedAt: Long,
  finishedAt: Long,
  traces: List<PampaiToolTrace>,
  imageInterpretations: List<RunImageInterpretation>,
): Boolean {
  if (discardEmptyVersion && partial.isNullOrBlank() && conversations.discardVersion(turn)) return false
  conversations.cancel(turn.assistantId, partial)
  conversations.addFailedRun(turn.conversationId, turn.assistantId, startedAt, finishedAt,
    "cancelled", null, traces, imageInterpretations = imageInterpretations)
  conversations.touch(turn.conversationId, finishedAt, null)
  return true
}
