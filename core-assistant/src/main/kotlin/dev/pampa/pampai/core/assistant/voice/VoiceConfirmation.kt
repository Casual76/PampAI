package dev.pampa.pampai.core.assistant.voice

import dev.antigravity.fluidengine.ai.orchestrator.AskMode
import dev.antigravity.fluidengine.ai.orchestrator.PendingConfirmation
import dev.pampa.pampai.core.assistant.runtime.PampaiConfirmationGate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * La conferma a voce: quando un'azione aspetta il si' e la domanda era arrivata a voce, Aria legge
 * "…Confermo?" e ascolta sei secondi; "si'" e "no" risolvono il cancello, il resto ripete una volta
 * e poi lascia i tasti (che restano a schermo comunque, con la scadenza dei 60 secondi).
 */
@Singleton
class VoiceConfirmation @Inject constructor(
  private val gate: PampaiConfirmationGate,
  private val stt: DualSttEngine,
  private val speaker: AriaSpeaker,
) {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private var job: Job? = null

  /** Si aggancia al cancello: parte quando compare una conferma e [lastMode] dice voce. */
  fun attach(lastMode: StateFlow<AskMode>) {
    scope.launch {
      gate.current.collect { pending ->
        job?.cancel()
        job = null
        if (pending != null && lastMode.value == AskMode.VOICE) job = scope.launch { ask(pending) }
      }
    }
  }

  private suspend fun ask(pending: PendingConfirmation) {
    speaker.speakNow("${pending.title.trimEnd('?', '.')}. Confermo?")
    // Il telefono legge la frase: si aspetta che finisca prima di aprire il microfono, o si ascolta se stesso.
    delay(600)
    while (speaker.speaking.value) delay(100)
    repeat(2) { attempt ->
      if (gate.current.value?.id != pending.id) return
      val heard = runCatching { stt.listen(initialSilenceMillis = 5_000, maxDurationMillis = 6_000) }.getOrNull()?.text ?: return
      when (YesNo.parse(heard)) {
        true -> { gate.resolve(pending.id, true); return }
        false -> { gate.resolve(pending.id, false); return }
        null -> if (attempt == 0) {
          speaker.speakNow("Si' o no?")
          delay(600)
          while (speaker.speaking.value) delay(100)
        }
      }
    }
  }
}
