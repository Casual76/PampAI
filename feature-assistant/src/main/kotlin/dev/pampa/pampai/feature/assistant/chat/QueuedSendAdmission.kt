package dev.pampa.pampai.feature.assistant.chat

import dev.pampa.pampai.core.assistant.runtime.AssistantRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** A preparing item stays in the visible queue until dispatch succeeds. Navigation/Stop may
 * recover it normally; a late helper cannot consume it or clear a newer preparation. */
internal class QueuedSendAdmission(
  private val scope: CoroutineScope,
  private val queue: MutableStateFlow<List<QueuedQuestion>>,
  private val preflight: suspend (AssistantRequest) -> Unit,
  private val canSubmit: () -> Boolean,
  private val captureOwner: (QueuedQuestion) -> (() -> Boolean),
  private val submit: (QueuedQuestion) -> Boolean,
  private val failed: (QueuedQuestion, Exception) -> Unit,
) {
  private var generation = 0L
  private var preparing: QueuedQuestion? = null
  private var job: Job? = null

  fun flush() {
    if (preparing != null || !canSubmit()) return
    val question = queue.value.firstOrNull() ?: return
    val stillOwned = captureOwner(question)
    val stamp = ++generation
    preparing = question
    val next = scope.launch(start = CoroutineStart.LAZY) {
      try {
        if (!SendAdmission(preflight).admit(question.asRequest()) {
            stamp == generation && question in queue.value && stillOwned()
          } || !canSubmit()) return@launch
        if (submit(question)) queue.value = queue.value - question
      } catch (e: CancellationException) { throw e }
      catch (e: Exception) {
        if (stamp == generation && question in queue.value && stillOwned()) {
          queue.value = queue.value - question
          failed(question, e)
        }
      } finally {
        if (stamp == generation) { preparing = null; job = null }
      }
    }
    job = next
    next.start()
  }

  fun cancel(question: QueuedQuestion? = null) {
    if (question != null && question != preparing) return
    generation++
    job?.cancel()
    job = null
    preparing = null
  }
}
