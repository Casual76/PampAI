package dev.pampa.pampai.core.assistant.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.antigravity.fluidengine.ai.keys.AiKeyStore
import dev.antigravity.fluidengine.ai.net.AiHttp
import dev.pampa.pampai.core.assistant.settings.PampaiSettingsStore
import dev.pampa.pampai.core.assistant.settings.TtsEngine
import dev.pampa.pampai.core.assistant.usage.UsageRepository
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * La voce di Aria. Legge le frasi man mano che lo stream le chiude, in coda: con la voce del
 * telefono (sempre disponibile) o con una voce cloud (Gemini in italiano; Groq solo in inglese),
 * sintetizzata frase per frase con al massimo due richieste in volo e riprodotta in ordine; se una
 * frase fallisce o tarda, il resto passa alla voce del telefono. Un tocco o una domanda nuova la
 * zittisce. Vale solo per le domande fatte a voce, e solo se l'utente l'ha accesa.
 */
@Singleton
class AriaSpeaker @Inject constructor(
  @ApplicationContext private val context: Context,
  private val http: AiHttp,
  private val keys: AiKeyStore,
  private val settings: PampaiSettingsStore,
  private val usage: UsageRepository,
) {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val speakingFlow = MutableStateFlow(false)

  /** Vero mentre una frase e' in riproduzione: la UI lo usa per il tasto "zitta". */
  val speaking: StateFlow<Boolean> = speakingFlow

  private var spokenChars = 0
  private var utterance = 0
  private var ready = false
  private val pendingSystem = mutableListOf<String>()
  private val player = SpeechPlayer()
  private var cloudJob: Job? = null
  private var cloudQueue: Channel<kotlinx.coroutines.Deferred<TtsAudio?>>? = null
  private var cloudBroken = false

  private val engine: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
    ready = status == TextToSpeech.SUCCESS
    if (ready) {
      val result = engine.setLanguage(Locale.ITALIAN)
      if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) engine.setLanguage(Locale.getDefault())
      engine.setOnUtteranceProgressListener(
        object : UtteranceProgressListener() {
          override fun onStart(utteranceId: String?) { speakingFlow.value = true }
          override fun onDone(utteranceId: String?) { speakingFlow.value = engine.isSpeaking }
          @Deprecated("Deprecated in Java")
          override fun onError(utteranceId: String?) { speakingFlow.value = false }
        },
      )
      val queued = pendingSystem.toList()
      pendingSystem.clear()
      queued.forEach { speakSystem(it) }
    }
  }

  /** Le frasi nuove dentro [fullText] rispetto all'ultima volta, e le accoda. Chiama con lo stream parziale. */
  fun speakNewSentences(fullText: String, final: Boolean, language: String = "it") {
    val (sentences, consumed) = PlainText.newSentences(fullText, spokenChars, final)
    spokenChars = consumed
    if (sentences.isEmpty()) return
    scope.launch {
      val engineChoice = settings.current().ttsEngine
      val cloud = if (cloudBroken) null else cloudFor(engineChoice, language)
      if (cloud == null) sentences.forEach { speakSystem(it) } else sentences.forEach { enqueueCloud(cloud, it, language) }
    }
  }

  /** Legge un testo intero subito (la conferma a voce). */
  fun speakNow(text: String) {
    restart()
    speakNewSentences(text, final = true)
  }

  /** Una domanda nuova: la voce si azzera e i contatori pure. */
  fun restart() {
    stop()
    spokenChars = 0
    cloudBroken = false
  }

  fun stop() {
    pendingSystem.clear()
    cloudJob?.cancel()
    cloudJob = null
    cloudQueue?.close()
    cloudQueue = null
    player.stop()
    runCatching { engine.stop() }
    speakingFlow.value = false
  }

  private fun cloudFor(choice: TtsEngine, language: String): CloudTts? = when (choice) {
    TtsEngine.SYSTEM -> null
    TtsEngine.GEMINI -> GeminiTts(http, keys)
    TtsEngine.GROQ_EN -> if (language.startsWith("en")) GroqTts(keys) else null
  }

  private fun speakSystem(text: String) {
    if (!ready) {
      pendingSystem += text
      return
    }
    speakingFlow.value = true
    engine.speak(text, TextToSpeech.QUEUE_ADD, null, "aria-${utterance++}")
  }

  /**
   * La coda cloud: ogni frase parte da sola (al massimo due in volo) e si riproduce nell'ordine in
   * cui e' stata accodata; un errore o un ritardo oltre i quattro secondi manda la frase — e le
   * successive — alla voce del telefono.
   */
  private fun enqueueCloud(cloud: CloudTts, sentence: String, language: String) {
    val queue = cloudQueue ?: Channel<kotlinx.coroutines.Deferred<TtsAudio?>>(capacity = 2).also { channel ->
      cloudQueue = channel
      cloudJob = scope.launch {
        for (deferred in channel) {
          val audio = try {
            withTimeoutOrNull(CLOUD_TIMEOUT_MILLIS) { deferred.await() }
          } catch (e: CancellationException) {
            throw e
          } catch (e: Throwable) {
            null
          }
          if (audio == null) {
            cloudBroken = true
            // La frase persa la dice il telefono; le prossime pure, senza piu' provare il cloud.
            deferred.cancel()
            continue
          }
          speakingFlow.value = true
          runCatching { player.play(audio) }
          speakingFlow.value = false
        }
      }
    }
    val started = System.currentTimeMillis()
    val deferred = scope.async {
      val audio = cloud.synthesize(sentence, language)
      usage.recordSpeech(cloud.provider, "tts", 0.0, System.currentTimeMillis() - started, if (audio == null) "nessun audio" else null)
      audio
    }
    if (queue.trySend(deferred).isFailure) {
      scope.launch { queue.send(deferred) }
    }
    // Se il cloud si e' rotto lungo la strada, la frase la dice comunque il telefono.
    scope.launch {
      val audio = runCatching { withTimeoutOrNull(CLOUD_TIMEOUT_MILLIS) { deferred.await() } }.getOrNull()
      if (audio == null && cloudBroken) speakSystem(sentence)
    }
  }

  fun release() {
    stop()
    runCatching { engine.shutdown() }
  }

  private companion object {
    const val CLOUD_TIMEOUT_MILLIS = 4_000L
  }
}

/** Un `suspend` che aspetta la fine del parlato del telefono, per la conferma a voce. */
suspend fun TextToSpeech.awaitIdle(pollMillis: Long = 100L) {
  suspendCancellableCoroutine { continuation ->
    Thread {
      while (isSpeaking) Thread.sleep(pollMillis)
      if (continuation.isActive) continuation.resume(Unit)
    }.start()
  }
}
