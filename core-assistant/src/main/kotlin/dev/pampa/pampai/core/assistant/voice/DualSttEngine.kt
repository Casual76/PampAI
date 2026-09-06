package dev.pampa.pampai.core.assistant.voice

import android.content.Context
import android.os.ParcelFileDescriptor
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.antigravity.fluidengine.ai.keys.AiKeyStore
import dev.antigravity.fluidengine.ai.net.AiError
import dev.antigravity.fluidengine.ai.orchestrator.AssistantFailure
import dev.antigravity.fluidengine.ai.orchestrator.FailureKind
import dev.antigravity.fluidengine.ai.orchestrator.MicLevel
import dev.antigravity.fluidengine.ai.provider.ProviderFactory
import dev.antigravity.fluidengine.ai.speech.AndroidPcmSource
import dev.antigravity.fluidengine.ai.speech.SpeechCapture
import dev.antigravity.fluidengine.ai.speech.Transcriber
import dev.pampa.pampai.core.assistant.settings.PampaiSettingsStore
import dev.pampa.pampai.core.assistant.settings.SttMode
import dev.pampa.pampai.core.assistant.usage.UsageRepository
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Chi ha dato il testo finale. */
enum class SttSource { WHISPER, SYSTEM }

/** L'esito di un ascolto: il testo, chi l'ha scritto, e quanto e' durato l'audio. */
data class SttResult(val text: String, val source: SttSource, val audioSeconds: Double)

sealed interface SttState {
  data object Idle : SttState

  /** In ascolto: [partial] sono le parole riconosciute finora dal sistema (null se il modo non le da'). */
  data class Listening(val elapsedMillis: Long, val partial: String?, val speaking: Boolean, val heardSomething: Boolean) : SttState

  /** Il microfono e' chiuso, Whisper sta scrivendo la versione finale (i parziali restano a schermo). */
  data class Transcribing(val partial: String?) : SttState

  data object HeardNothing : SttState

  /** Nessun parlato entro il tempo iniziale: la barra passa al testo. */
  data object InitialSilence : SttState
}

/**
 * L'ascolto di Aria, nei tre modi: **doppio** (una cattura dell'engine per Whisper, e in copia il
 * riconoscitore on-device per le parole che compaiono mentre si parla; alla fine Whisper
 * sostituisce), **Whisper** (solo l'engine), **sistema** (solo il riconoscitore, anche senza
 * chiavi: il livello viene dal suo `onRmsChanged`). La politica delle degradazioni sta in [SttPolicy].
 */
@Singleton
class DualSttEngine @Inject constructor(
  @ApplicationContext private val context: Context,
  private val providers: ProviderFactory,
  private val keys: AiKeyStore,
  private val settings: PampaiSettingsStore,
  private val usage: UsageRepository,
) {

  private val stateFlow = MutableStateFlow<SttState>(SttState.Idle)
  val state: StateFlow<SttState> = stateFlow

  private val levelFlow = MutableStateFlow(MicLevel())
  val micLevel: StateFlow<MicLevel> = levelFlow

  private val recognizer = SystemRecognizer(context)

  @Volatile private var capture: SpeechCapture? = null
  @Volatile private var systemJob: Job? = null
  @Volatile private var stopSystem: (() -> Unit)? = null

  /** Il modo con cui si ascoltera' davvero, letto adesso. */
  suspend fun effectiveMode(): SttMode {
    val requested = settings.current().sttMode
    val sttKeys = keys.currentStates().any { it.value.verified }
    return SttPolicy.resolve(requested, recognizer.canBeFed, recognizer.isAvailable, sttKeys)
  }

  /**
   * Ascolta una volta e torna il testo (null se non ha sentito niente o se e' scaduto il silenzio
   * iniziale). Lancia [AssistantFailure] per microfono e trascrizione. Si ferma con [stopNow].
   */
  suspend fun listen(language: String = "it", hint: String? = null, initialSilenceMillis: Long = INITIAL_SILENCE_MILLIS, maxDurationMillis: Long = 30_000): SttResult? {
    val mode = effectiveMode()
    stateFlow.value = SttState.Listening(0L, null, speaking = false, heardSomething = false)
    levelFlow.value = MicLevel()
    return try {
      when (mode) {
        SttMode.SYSTEM -> listenSystemOnly(language, initialSilenceMillis)
        SttMode.WHISPER -> listenCapture(language, hint, feed = false, initialSilenceMillis, maxDurationMillis)
        SttMode.DUAL -> listenCapture(language, hint, feed = true, initialSilenceMillis, maxDurationMillis)
      }
    } finally {
      levelFlow.value = MicLevel()
      if (stateFlow.value is SttState.Listening || stateFlow.value is SttState.Transcribing) stateFlow.value = SttState.Idle
    }
  }

  /** Il secondo tocco: chiude la cattura e trascrive quello che c'e'. */
  fun stopNow() {
    capture?.stopNow()
    stopSystem?.invoke()
  }

  fun reset() {
    stateFlow.value = SttState.Idle
  }

  private suspend fun listenCapture(language: String, hint: String?, feed: Boolean, initialSilenceMillis: Long, maxDurationMillis: Long): SttResult? = coroutineScope {
    val pipe = if (feed) runCatching { ParcelFileDescriptor.createPipe() }.getOrNull() else null
    val source = TeePcmSource(AndroidPcmSource(context), pipe?.get(1))
    val speech = SpeechCapture(source, SpeechCapture.VadConfig(endSilenceMillis = 1_500, maxDurationMillis = maxDurationMillis))
    capture = speech
    var partial: String? = null
    var systemFinal: String? = null
    // Il riconoscitore legge il tubo in parallelo: le sue parole entrano nello stato come parziali.
    systemJob = if (pipe != null) {
      launch {
        runCatching {
          recognizer.listen(language, pipe[0], partials = true).collect { event ->
            when (event) {
              is SystemRecognizer.Event.Partial -> {
                partial = event.text
                (stateFlow.value as? SttState.Listening)?.let { stateFlow.value = it.copy(partial = event.text, heardSomething = true) }
                (stateFlow.value as? SttState.Transcribing)?.let { stateFlow.value = it.copy(partial = event.text) }
              }
              is SystemRecognizer.Event.Final -> {
                systemFinal = event.text.takeIf { it.isNotBlank() } ?: systemFinal
                partial = systemFinal ?: partial
                (stateFlow.value as? SttState.Listening)?.let { stateFlow.value = it.copy(partial = partial, heardSomething = true) }
              }
              else -> Unit
            }
          }
        }
      }
    } else {
      null
    }
    val dir = File(context.cacheDir, "ai").apply { mkdirs() }
    val file = File(dir, "ask-${System.currentTimeMillis()}.wav")
    val startedAt = System.currentTimeMillis()
    var heard = false
    var result: SttResult? = null
    // Il silenzio iniziale: se nessuno parla entro il tempo, si chiude e la barra passa al testo.
    val watchdog = launch {
      delay(initialSilenceMillis)
      if (!heard) {
        speech.stopNow()
        stateFlow.value = SttState.InitialSilence
      }
    }
    try {
      speech.record(file).collect { event ->
        when (event) {
          is SpeechCapture.Event.Level -> {
            levelFlow.value = MicLevel(event.level, event.speaking)
            val elapsed = event.elapsedMillis / 250 * 250
            val shown = stateFlow.value
            if (shown is SttState.Listening && (shown.elapsedMillis != elapsed || shown.speaking != event.speaking)) {
              stateFlow.value = shown.copy(elapsedMillis = elapsed, speaking = event.speaking)
            }
          }
          SpeechCapture.Event.SpeechStarted -> {
            heard = true
            (stateFlow.value as? SttState.Listening)?.let { stateFlow.value = it.copy(heardSomething = true) }
          }
          is SpeechCapture.Event.Empty -> {
            if (stateFlow.value != SttState.InitialSilence) {
              // Il rilevatore non ha sentito niente, ma se il sistema ha capito delle parole valgono quelle.
              val fallback = systemFinal ?: partial
              if (!fallback.isNullOrBlank()) result = SttResult(fallback, SttSource.SYSTEM, (System.currentTimeMillis() - startedAt) / 1000.0)
              else stateFlow.value = SttState.HeardNothing
            }
          }
          is SpeechCapture.Event.Failed -> throw AssistantFailure(FailureKind.MICROPHONE, null)
          is SpeechCapture.Event.Finished -> {
            watchdog.cancel()
            stateFlow.value = SttState.Transcribing(partial)
            val audioSeconds = event.durationMillis / 1000.0
            val text = transcribe(event.file, language, hint, audioSeconds)
            result = when {
              !text.isNullOrBlank() -> SttResult(text, SttSource.WHISPER, audioSeconds)
              !(systemFinal ?: partial).isNullOrBlank() -> SttResult((systemFinal ?: partial)!!, SttSource.SYSTEM, audioSeconds)
              else -> null
            }
            if (result == null) stateFlow.value = SttState.HeardNothing
          }
        }
      }
    } finally {
      watchdog.cancel()
      capture = null
      // Il tubo si chiude dal lato di chi scrive: il riconoscitore vede la fine e da' il suo finale.
      runCatching { pipe?.get(1)?.close() }
      systemJob?.let { job -> withTimeoutOrNull(1_500) { job.join() }; job.cancelAndJoin() }
      systemJob = null
      runCatching { pipe?.get(0)?.close() }
      runCatching { file.delete() }
    }
    result
  }

  /** Solo il riconoscitore del sistema, con il suo microfono: il livello viene da lui. */
  private suspend fun listenSystemOnly(language: String, initialSilenceMillis: Long): SttResult? = coroutineScope {
    var partial: String? = null
    var final: String? = null
    var error: String? = null
    var heard = false
    val startedAt = System.currentTimeMillis()
    val job = launch {
      recognizer.listen(language, pcm = null, partials = true).collect { event ->
        when (event) {
          is SystemRecognizer.Event.Partial -> {
            heard = true
            partial = event.text
            stateFlow.value = SttState.Listening(System.currentTimeMillis() - startedAt, event.text, speaking = true, heardSomething = true)
          }
          is SystemRecognizer.Event.Level -> {
            levelFlow.value = MicLevel(event.level, event.level > 0.35f)
            if (event.level > 0.35f) heard = true
          }
          is SystemRecognizer.Event.Final -> final = event.text.takeIf { it.isNotBlank() }
          is SystemRecognizer.Event.Error -> error = event.message
          SystemRecognizer.Event.EndOfSpeech -> stateFlow.value = SttState.Transcribing(partial)
        }
      }
    }
    stopSystem = { job.cancel() }
    val watchdog = launch {
      delay(initialSilenceMillis)
      if (!heard) {
        job.cancel()
        stateFlow.value = SttState.InitialSilence
      }
    }
    try {
      job.join()
    } finally {
      watchdog.cancel()
      stopSystem = null
    }
    val text = final ?: partial
    when {
      stateFlow.value == SttState.InitialSilence -> null
      !text.isNullOrBlank() -> SttResult(text, SttSource.SYSTEM, (System.currentTimeMillis() - startedAt) / 1000.0)
      error != null && error!!.contains("permesso") -> throw AssistantFailure(FailureKind.MICROPHONE, null)
      else -> {
        stateFlow.value = SttState.HeardNothing
        null
      }
    }
  }

  private suspend fun transcribe(file: File, language: String, hint: String?, audioSeconds: Double): String? {
    val ordered = providers.ordered(ProviderFactory.Kind.STT)
    if (ordered.isEmpty()) return null
    val started = System.currentTimeMillis()
    return try {
      val transcription = Transcriber { ordered }.transcribe(file, language, hint)
      usage.recordSpeech(transcription.provider, transcription.model, audioSeconds, System.currentTimeMillis() - started)
      transcription.text.takeIf { it.isNotBlank() }
    } catch (e: CancellationException) {
      throw e
    } catch (e: AiError.Unauthorized) {
      throw AssistantFailure(FailureKind.UNAUTHORIZED, e)
    } catch (e: Throwable) {
      ordered.firstOrNull()?.let { usage.recordSpeech(it.provider.id, it.sttModel, audioSeconds, System.currentTimeMillis() - started, e.message ?: "errore") }
      null
    }
  }

  companion object {
    /** Quanto si aspetta la prima parola prima di passare al testo: la decisione "dopo ~3 s di silenzio". */
    const val INITIAL_SILENCE_MILLIS = 3_000L
  }
}
