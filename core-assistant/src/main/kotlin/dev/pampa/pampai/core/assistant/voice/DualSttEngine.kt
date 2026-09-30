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
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
 *
 * Ogni ascolto ha il suo numero ([session]): uno vecchio che finisce tardi (fermato, o superato
 * dalla conferma a voce) non scrive piu' lo stato, e non chiude la cattura di quello nuovo.
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

  private val sessionLock = Any()
  @Volatile private var session = 0L

  /** Lo stato dell'ascolto [mine], se e' ancora quello di adesso. */
  private fun set(mine: Long, state: SttState) {
    synchronized(sessionLock) { if (mine == session) stateFlow.value = state }
  }

  /** Come [set], a partire dallo stato di adesso: [change] torna null per non toccarlo. */
  private inline fun update(mine: Long, change: (SttState) -> SttState?) {
    synchronized(sessionLock) {
      if (mine != session) return
      change(stateFlow.value)?.let { stateFlow.value = it }
    }
  }

  private fun level(mine: Long, level: MicLevel) {
    if (mine == session) levelFlow.value = level
  }

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
    val mine = synchronized(sessionLock) {
      session += 1
      stateFlow.value = SttState.Listening(0L, null, speaking = false, heardSomething = false)
      levelFlow.value = MicLevel()
      session
    }
    return try {
      when (mode) {
        SttMode.SYSTEM -> listenSystemOnly(mine, language, initialSilenceMillis, maxDurationMillis)
        SttMode.WHISPER -> listenCapture(mine, language, hint, feed = false, initialSilenceMillis, maxDurationMillis)
        SttMode.DUAL -> listenCapture(mine, language, hint, feed = true, initialSilenceMillis, maxDurationMillis)
      }
    } finally {
      synchronized(sessionLock) {
        if (mine == session) {
          levelFlow.value = MicLevel()
          if (stateFlow.value is SttState.Listening || stateFlow.value is SttState.Transcribing) stateFlow.value = SttState.Idle
        }
      }
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

  private suspend fun listenCapture(mine: Long, language: String, hint: String?, feed: Boolean, initialSilenceMillis: Long, maxDurationMillis: Long): SttResult? = coroutineScope {
    val pipe = if (feed) runCatching { ParcelFileDescriptor.createPipe() }.getOrNull() else null
    val source = TeePcmSource(AndroidPcmSource(context), pipe?.get(1))
    val speech = SpeechCapture(source, SpeechCapture.VadConfig(endSilenceMillis = 1_500, maxDurationMillis = maxDurationMillis))
    capture = speech
    var partial: String? = null
    var systemFinal: String? = null
    // Il riconoscitore legge il tubo in parallelo: le sue parole entrano nello stato come parziali.
    val feeder = if (pipe != null) {
      launch {
        runCatching {
          recognizer.listen(language, pipe[0], partials = true).collect { event ->
            when (event) {
              is SystemRecognizer.Event.Partial -> {
                partial = event.text
                update(mine) { s -> (s as? SttState.Listening)?.copy(partial = event.text, heardSomething = true) ?: (s as? SttState.Transcribing)?.copy(partial = event.text) }
              }
              is SystemRecognizer.Event.Final -> {
                systemFinal = event.text.takeIf { it.isNotBlank() } ?: systemFinal
                partial = systemFinal ?: partial
                val shown = partial
                update(mine) { s -> (s as? SttState.Listening)?.copy(partial = shown, heardSomething = true) }
              }
              else -> Unit
            }
          }
        }
      }
    } else {
      null
    }
    systemJob = feeder
    val dir = File(context.cacheDir, "ai").apply { mkdirs() }
    val file = File(dir, "ask-${System.currentTimeMillis()}.wav")
    val startedAt = System.currentTimeMillis()
    var heard = false
    var voicedMillis = 0L
    var lastLevelAt = 0L
    var silenceFired = false
    var result: SttResult? = null
    // Il silenzio iniziale: se nessuno parla entro il tempo (ne' per il rilevatore ne' per il
    // riconoscitore), si chiude e la barra passa al testo.
    val watchdog = launch {
      delay(initialSilenceMillis)
      if (!heard && partial.isNullOrBlank()) {
        silenceFired = true
        speech.stopNow()
        set(mine, SttState.InitialSilence)
      }
    }
    try {
      speech.record(file).collect { event ->
        when (event) {
          is SpeechCapture.Event.Level -> {
            // Quanto parlato ha sentito il rilevatore, frame per frame, anche senza un "inizio" pieno.
            if (event.speaking) voicedMillis += (event.elapsedMillis - lastLevelAt).coerceIn(0L, 100L)
            lastLevelAt = event.elapsedMillis
            level(mine, MicLevel(event.level, event.speaking))
            val elapsed = event.elapsedMillis / 250 * 250
            update(mine) { s -> (s as? SttState.Listening)?.takeIf { it.elapsedMillis != elapsed || it.speaking != event.speaking }?.copy(elapsedMillis = elapsed, speaking = event.speaking) }
          }
          SpeechCapture.Event.SpeechStarted -> {
            heard = true
            update(mine) { s -> (s as? SttState.Listening)?.copy(heardSomething = true) }
          }
          is SpeechCapture.Event.Empty -> {
            if (!silenceFired) {
              // Il rilevatore non ha sentito niente, ma se il sistema ha capito delle parole valgono quelle.
              val fallback = systemFinal ?: partial
              if (!fallback.isNullOrBlank()) result = SttResult(fallback, SttSource.SYSTEM, (System.currentTimeMillis() - startedAt) / 1000.0)
              else set(mine, SttState.HeardNothing)
            }
          }
          is SpeechCapture.Event.Failed -> throw AssistantFailure(FailureKind.MICROPHONE, null)
          is SpeechCapture.Event.Finished -> {
            watchdog.cancel()
            val fallback = systemFinal ?: partial
            when {
              // L'ha chiusa il silenzio iniziale, e nessuno aveva parlato: la cattura tiene l'audio
              // se un picco di rumore supera il fondo, ma e' rumore. A Whisper no: sul rumore
              // inventa una frase, e la domanda partiva da sola.
              silenceFired && !heard -> set(mine, SttState.InitialSilence)
              // Fermata a mano senza che il rilevatore abbia sentito cominciare a parlare: si
              // trascrive solo con almeno un po' di voce, altrimenti vale cio' che ha capito il sistema.
              !heard && voicedMillis < MIN_SPEECH_MILLIS -> {
                if (!fallback.isNullOrBlank()) result = SttResult(fallback, SttSource.SYSTEM, event.durationMillis / 1000.0)
                else set(mine, SttState.HeardNothing)
              }
              else -> {
                set(mine, SttState.Transcribing(partial))
                val audioSeconds = event.durationMillis / 1000.0
                val text = transcribe(event.file, language, hint, audioSeconds)
                val system = systemFinal ?: partial
                result = when {
                  !text.isNullOrBlank() -> SttResult(text, SttSource.WHISPER, audioSeconds)
                  !system.isNullOrBlank() -> SttResult(system, SttSource.SYSTEM, audioSeconds)
                  else -> null
                }
                if (result == null) set(mine, SttState.HeardNothing)
              }
            }
          }
        }
      }
    } finally {
      watchdog.cancel()
      // Solo la propria: un ascolto nuovo puo' aver gia' messo qui la sua.
      if (capture === speech) capture = null
      // Il tubo si chiude dal lato di chi scrive: il riconoscitore vede la fine e da' il suo finale.
      runCatching { pipe?.get(1)?.close() }
      feeder?.let { job -> withTimeoutOrNull(1_500) { job.join() }; job.cancelAndJoin() }
      if (systemJob === feeder) systemJob = null
      runCatching { pipe?.get(0)?.close() }
      runCatching { file.delete() }
    }
    result
  }

  /**
   * Solo il riconoscitore del sistema, con il suo microfono: il livello viene da lui. Il secondo
   * tocco lo chiude con `stopListening` (e non annullandolo), cosi' la frase detta arriva intera
   * come risultato finale; c'e' un tetto di durata come per la cattura.
   */
  private suspend fun listenSystemOnly(mine: Long, language: String, initialSilenceMillis: Long, maxDurationMillis: Long): SttResult? = coroutineScope {
    var partial: String? = null
    var final: String? = null
    var error: String? = null
    var heard = false
    var silenceFired = false
    val startedAt = System.currentTimeMillis()
    val job = launch {
      recognizer.listen(language, pcm = null, partials = true).collect { event ->
        when (event) {
          is SystemRecognizer.Event.Partial -> {
            heard = true
            partial = event.text
            set(mine, SttState.Listening(System.currentTimeMillis() - startedAt, event.text, speaking = true, heardSomething = true))
          }
          is SystemRecognizer.Event.Level -> {
            level(mine, MicLevel(event.level, event.level > 0.35f))
            if (event.level > 0.35f) heard = true
          }
          is SystemRecognizer.Event.Final -> final = event.text.takeIf { it.isNotBlank() }
          is SystemRecognizer.Event.Error -> error = event.message
          SystemRecognizer.Event.EndOfSpeech -> set(mine, SttState.Transcribing(partial))
        }
      }
    }
    // Chiudere tenendo l'esito; se il finale non arriva (qualche riconoscitore tace), dopo un po'
    // si chiude comunque e vale il parziale.
    var stopper: Job? = null
    val stop: () -> Unit = {
      recognizer.stopListening()
      if (stopper == null) stopper = launch { delay(STOP_GRACE_MILLIS); job.cancel() }
      Unit
    }
    stopSystem = stop
    val watchdog = launch {
      delay(initialSilenceMillis)
      if (!heard) {
        silenceFired = true
        job.cancel()
        set(mine, SttState.InitialSilence)
      }
    }
    val ceiling = launch {
      delay(maxDurationMillis)
      stop()
    }
    try {
      job.join()
    } finally {
      watchdog.cancel()
      ceiling.cancel()
      stopper?.cancel()
      if (stopSystem === stop) stopSystem = null
    }
    val text = final ?: partial
    when {
      silenceFired -> null
      !text.isNullOrBlank() -> SttResult(text, SttSource.SYSTEM, (System.currentTimeMillis() - startedAt) / 1000.0)
      error != null && error!!.contains("permesso") -> throw AssistantFailure(FailureKind.MICROPHONE, null)
      else -> {
        set(mine, SttState.HeardNothing)
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

    /**
     * Quanta voce (frame sopra la soglia) serve per trascrivere una cattura fermata a mano in cui il
     * rilevatore non ha mai sentito cominciare a parlare: sotto, e' un colpo di tosse o rumore.
     */
    const val MIN_SPEECH_MILLIS = 300L

    /** Dopo `stopListening`, quanto si aspetta il finale del riconoscitore prima di chiudere. */
    private const val STOP_GRACE_MILLIS = 2_500L
  }
}
