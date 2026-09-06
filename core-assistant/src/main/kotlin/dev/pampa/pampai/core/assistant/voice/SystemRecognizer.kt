package dev.pampa.pampai.core.assistant.voice

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Il riconoscitore del telefono, in due modi: **alimentato** da un tubo di PCM (Android 13+, solo
 * on-device) per i parziali mentre l'engine registra per Whisper, oppure **da solo** col suo
 * microfono quando e' l'unico a lavorare (nessuna chiave, telefono vecchio). Parla solo sul main
 * thread, come vuole `SpeechRecognizer`; il resto dell'app lo legge come un flusso.
 */
class SystemRecognizer(private val context: Context) {

  sealed interface Event {
    data class Partial(val text: String) : Event
    data class Final(val text: String) : Event
    data class Level(val level: Float) : Event
    data object EndOfSpeech : Event
    data class Error(val code: Int, val message: String) : Event
  }

  /** Vero se il telefono ha un riconoscitore on-device che accetta audio da un tubo. */
  val canBeFed: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

  val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

  /**
   * Ascolta il tubo [pcm] (16 kHz mono 16 bit) se dato, altrimenti il microfono. Il flusso finisce
   * con [Event.Final] o [Event.Error]; annullarlo ferma il riconoscitore.
   */
  fun listen(language: String, pcm: ParcelFileDescriptor?, partials: Boolean = true): Flow<Event> = callbackFlow {
    val handler = Handler(Looper.getMainLooper())
    var recognizer: SpeechRecognizer? = null
    handler.post {
      val created = runCatching {
        if (pcm != null && canBeFed) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
      }.getOrNull()
      if (created == null) {
        trySend(Event.Error(SpeechRecognizer.ERROR_CLIENT, "riconoscitore non disponibile"))
        close()
        return@post
      }
      recognizer = created
      created.setRecognitionListener(
        object : RecognitionListener {
          override fun onReadyForSpeech(params: Bundle?) = Unit
          override fun onBeginningOfSpeech() = Unit
          override fun onRmsChanged(rmsdB: Float) {
            trySend(Event.Level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)))
          }
          override fun onBufferReceived(buffer: ByteArray?) = Unit
          override fun onEndOfSpeech() {
            trySend(Event.EndOfSpeech)
          }
          override fun onError(error: Int) {
            trySend(Event.Error(error, describe(error)))
            close()
          }
          override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            trySend(Event.Final(text))
            close()
          }
          override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
            if (text.isNotBlank()) trySend(Event.Partial(text))
          }
          override fun onEvent(eventType: Int, params: Bundle?) = Unit
        },
      )
      val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (language == "it") "it-IT" else language)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partials)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        if (pcm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
          putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pcm)
          putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
          putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
          putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
        }
      }
      runCatching { created.startListening(intent) }.onFailure {
        trySend(Event.Error(SpeechRecognizer.ERROR_CLIENT, it.message ?: "avvio fallito"))
        close()
      }
    }
    awaitClose {
      handler.post {
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
      }
    }
  }

  private fun describe(code: Int): String = when (code) {
    SpeechRecognizer.ERROR_AUDIO -> "errore audio"
    SpeechRecognizer.ERROR_CLIENT -> "errore del client"
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "permesso microfono mancante"
    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "rete"
    SpeechRecognizer.ERROR_NO_MATCH -> "niente riconosciuto"
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "riconoscitore occupato"
    SpeechRecognizer.ERROR_SERVER -> "errore del server"
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "nessun parlato"
    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "lingua non disponibile"
    else -> "errore $code"
  }
}
