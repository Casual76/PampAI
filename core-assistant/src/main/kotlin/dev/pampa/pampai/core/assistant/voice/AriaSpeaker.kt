package dev.pampa.pampai.core.assistant.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.antigravity.fluidengine.ai.keys.AiKeyStore
import dev.antigravity.fluidengine.ai.net.AiHttp
import dev.pampa.pampai.core.assistant.remote.PampaiFlags
import dev.pampa.pampai.core.assistant.remote.RemoteSwitches
import dev.pampa.pampai.core.assistant.settings.PampaiSettingsStore
import dev.pampa.pampai.core.assistant.settings.TtsEngine
import dev.pampa.pampai.core.assistant.usage.UsageRepository
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

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
  remote: RemoteSwitches,
) {

  /**
   * Tutto lo stato qui sotto si tocca da un filo solo. Prima i campi (le frasi gia' dette, la coda
   * cloud, "cloud rotto") li scrivevano coroutine su `Dispatchers.Default` in parallelo, e due
   * pezzi di risposta arrivati vicini potevano essere letti nell'ordine sbagliato.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  private val serial = Dispatchers.Default.limitedParallelism(1)
  private val scope = CoroutineScope(SupervisorJob() + serial)
  private val speakingFlow = MutableStateFlow(false)

  /** Vero mentre una frase e' in riproduzione: la UI lo usa per il tasto "zitta". */
  val speaking: StateFlow<Boolean> = speakingFlow

  /**
   * La voce scelta, tenuta sempre pronta: leggerla dal DataStore a ogni frase era una sospensione
   * in mezzo alla coda, ed e' li' che l'ordine delle frasi si perdeva.
   */
  private val ttsEngine: StateFlow<TtsEngine> = settings.settings.map { it.ttsEngine }.stateIn(scope, SharingStarted.Eagerly, TtsEngine.SYSTEM)

  /** La voce cloud si puo' spegnere da remoto (flag `cloud_tts`): resta quella del telefono. */
  private val cloudAllowed: StateFlow<Boolean> = remote.flag(PampaiFlags.CloudTts).stateIn(scope, SharingStarted.Eagerly, true)

  /** Il testo semplice gia' letto della risposta di adesso ([PlainText.advance]). */
  private var spoken = ""
  private var utterance = 0
  @Volatile private var ready = false

  /**
   * Cresce a ogni [stop], subito e da qualunque filo. Le frasi portano quella di quando sono state
   * chieste: una rimasta in coda sul filo della voce quando e' arrivato lo stop non si dice piu'.
   */
  private val epoch = AtomicInteger(0)

  /** Le frasi in attesa che la voce del telefono sia pronta, con la loro [epoch]. */
  private val pendingSystem = mutableListOf<Pair<String, Int>>()
  private val player = SpeechPlayer()
  private var cloudJob: Job? = null

  /**
   * Il padre delle sintesi cloud della risposta di adesso. Fermare la voce le cancella tutte: prima
   * continuavano in volo, tenevano occupati i due posti del semaforo e contavano nei consumi, e la
   * risposta dopo, in coda dietro di loro, scadeva e finiva tutta sulla voce del telefono.
   */
  private var cloudTasks: Job? = null
  private var cloudQueue: Channel<Pair<String, Deferred<TtsAudio?>>>? = null
  private var cloudBroken = false

  /** Al massimo due frasi in sintesi insieme: la coda resta avanti di una senza intasare la rete. */
  private val inFlight = Semaphore(2)

  /**
   * La voce del telefono si accende alla prima frase, non all'avvio dell'app: chi non usa mai la
   * voce non paga il motore TTS (che su alcuni telefoni tiene sveglio un servizio a parte).
   */
  @Volatile private var tts: TextToSpeech? = null

  private fun engine(): TextToSpeech = tts ?: synchronized(this) {
    tts ?: TextToSpeech(context.applicationContext) { status -> scope.launch { onEngineReady(status == TextToSpeech.SUCCESS) } }.also { tts = it }
  }

  private fun onEngineReady(success: Boolean) {
    val engine = tts ?: return
    ready = success
    if (!success) {
      pendingSystem.clear()
      return
    }
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
    queued.forEach { (text, asked) -> speakSystem(text, asked) }
  }

  /**
   * Le frasi nuove dentro [fullText] rispetto all'ultima volta, e le accoda. Chiama con lo stream
   * parziale. Se il testo non continua piu' quello gia' letto (dopo un preambolo e uno strumento
   * arriva la risposta vera) si riparte dall'inizio del testo nuovo, non da meta'.
   */
  fun speakNewSentences(fullText: String, final: Boolean, language: String = "it") {
    val asked = epoch.get()
    scope.launch {
      if (asked != epoch.get()) return@launch
      val (sentences, read) = PlainText.advance(fullText, spoken, final)
      spoken = read
      if (sentences.isEmpty()) return@launch
      val cloud = if (cloudBroken) null else cloudFor(ttsEngine.value, language)
      if (cloud == null) sentences.forEach { speakSystem(it, asked) } else sentences.forEach { enqueueCloud(cloud, it, language, asked) }
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
    scope.launch {
      spoken = ""
      cloudBroken = false
    }
  }

  fun stop() {
    // Il silenzio subito, da qualunque filo; la pulizia della coda nel suo. L'epoca cresce per
    // prima: le frasi gia' in coda sul filo della voce, che arrivano dopo, non parlano piu'.
    epoch.incrementAndGet()
    player.stop()
    runCatching { tts?.stop() }
    speakingFlow.value = false
    scope.launch {
      pendingSystem.clear()
      cloudJob?.cancel()
      cloudJob = null
      cloudTasks?.cancel()
      cloudTasks = null
      cloudQueue?.close()
      cloudQueue = null
    }
  }

  private fun cloudFor(choice: TtsEngine, language: String): CloudTts? = if (!cloudAllowed.value) null else when (choice) {
    TtsEngine.SYSTEM -> null
    TtsEngine.GEMINI -> GeminiTts(http, keys)
    TtsEngine.GROQ_EN -> if (language.startsWith("en")) GroqTts(keys) else null
  }

  private fun speakSystem(text: String, asked: Int) {
    if (asked != epoch.get()) return
    val engine = engine()
    if (!ready) {
      pendingSystem += text to asked
      return
    }
    speakingFlow.value = true
    engine.speak(text, TextToSpeech.QUEUE_ADD, null, "aria-${utterance++}")
  }

  /**
   * La coda cloud: ogni frase parte da sola (al massimo due in sintesi) e si riproduce nell'ordine
   * in cui e' stata accodata; un errore o un ritardo oltre i quattro secondi manda la frase — e le
   * successive — alla voce del telefono.
   */
  private fun enqueueCloud(cloud: CloudTts, sentence: String, language: String, asked: Int) {
    val queue = cloudQueue ?: Channel<Pair<String, Deferred<TtsAudio?>>>(Channel.UNLIMITED).also { channel ->
      cloudQueue = channel
      cloudTasks = SupervisorJob()
      cloudJob = scope.launch {
        for ((text, deferred) in channel) {
          // Uno stop arrivato mentre questa coda lavorava: niente piu' frasi, nemmeno quelle pronte.
          if (asked != epoch.get()) {
            deferred.cancel()
            break
          }
          if (cloudBroken) {
            deferred.cancel()
            speakSystem(text, asked)
            continue
          }
          val audio = try {
            withTimeoutOrNull(CLOUD_TIMEOUT_MILLIS) { deferred.await() }
          } catch (e: CancellationException) {
            throw e
          } catch (e: Throwable) {
            null
          }
          if (audio == null) {
            // La frase persa la dice il telefono; le prossime pure, senza piu' provare il cloud.
            cloudBroken = true
            deferred.cancel()
            speakSystem(text, asked)
            continue
          }
          // L'attesa della sintesi puo' essere durata: uno stop arrivato intanto vince.
          if (asked != epoch.get()) break
          speakingFlow.value = true
          try {
            player.play(audio)
          } catch (e: CancellationException) {
            throw e
          } catch (e: Throwable) {
            // Un file audio che non si riproduce: si passa alla frase dopo.
          } finally {
            speakingFlow.value = false
          }
        }
      }
    }
    val started = System.currentTimeMillis()
    val deferred = scope.async(Dispatchers.IO + (cloudTasks ?: SupervisorJob().also { cloudTasks = it })) {
      inFlight.withPermit {
        val audio = cloud.synthesize(sentence, language)
        usage.recordSpeech(cloud.provider, "tts", 0.0, System.currentTimeMillis() - started, if (audio == null) "nessun audio" else null)
        audio
      }
    }
    // Coda senza limite: l'invio non sospende mai, quindi l'ordine e' quello delle frasi.
    queue.trySend(sentence to deferred)
  }

  fun release() {
    stop()
    runCatching { tts?.shutdown() }
  }

  private companion object {
    const val CLOUD_TIMEOUT_MILLIS = 4_000L
  }
}
