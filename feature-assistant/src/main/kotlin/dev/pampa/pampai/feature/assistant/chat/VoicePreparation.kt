package dev.pampa.pampai.feature.assistant.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Owns admission before the runtime owns a voice request. A cancelled, non-cooperative
 * helper cannot commit its result, even if another preparation has since started. */
internal class VoicePreparation(private val scope: CoroutineScope) {
  private val active = MutableStateFlow(false)
  val preparing: StateFlow<Boolean> = active
  private var generation = 0L
  private var job: Job? = null

  fun start(preflight: suspend () -> Unit, stillOwned: () -> Boolean, listen: () -> Unit) {
    if (active.value) return
    val stamp = ++generation
    active.value = true
    val next = scope.launch(start = CoroutineStart.LAZY) {
      try {
        preflight()
        if (stamp == generation && stillOwned()) listen()
      } finally {
        if (stamp == generation) { active.value = false; job = null }
      }
    }
    job = next
    next.start()
  }

  fun cancel() {
    generation++
    job?.cancel()
    job = null
    active.value = false
  }
}
