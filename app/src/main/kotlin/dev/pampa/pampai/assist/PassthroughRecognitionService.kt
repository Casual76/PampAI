package dev.pampa.pampai.assist

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Quando un'app diventa l'assistente predefinito, Android imposta anche il suo `recognitionService`
 * come riconoscitore vocale di sistema: la dettatura in Gboard, Chrome e nelle altre app passerebbe
 * da noi. Aria non ha un riconoscitore suo, quindi questo servizio inoltra tutto a uno vero: quello
 * on-device del telefono se c'e', altrimenti il primo di un altro pacchetto (Google per primo).
 * Senza nessuno, risponde "occupato", che e' l'errore che le app sanno gestire.
 */
class PassthroughRecognitionService : RecognitionService() {

  private val main = Handler(Looper.getMainLooper())
  private var delegate: SpeechRecognizer? = null

  override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
    main.post {
      val recognizer = createDelegate() ?: run {
        runCatching { listener.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY) }
        return@post
      }
      delegate?.destroy()
      delegate = recognizer
      recognizer.setRecognitionListener(
        object : RecognitionListener {
          override fun onReadyForSpeech(params: Bundle?) { runCatching { listener.readyForSpeech(params ?: Bundle()) } }
          override fun onBeginningOfSpeech() { runCatching { listener.beginningOfSpeech() } }
          override fun onRmsChanged(rmsdB: Float) { runCatching { listener.rmsChanged(rmsdB) } }
          override fun onBufferReceived(buffer: ByteArray?) { buffer?.let { runCatching { listener.bufferReceived(it) } } }
          override fun onEndOfSpeech() { runCatching { listener.endOfSpeech() } }
          override fun onError(error: Int) { runCatching { listener.error(error) } }
          override fun onResults(results: Bundle?) { runCatching { listener.results(results ?: Bundle()) } }
          override fun onPartialResults(partialResults: Bundle?) { runCatching { listener.partialResults(partialResults ?: Bundle()) } }
          override fun onEvent(eventType: Int, params: Bundle?) = Unit
        },
      )
      runCatching { recognizer.startListening(recognizerIntent) }.onFailure {
        Log.w("PampAI", "riconoscitore delegato: ${it.message}")
        runCatching { listener.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY) }
      }
    }
  }

  override fun onCancel(listener: Callback) {
    main.post { delegate?.cancel() }
  }

  override fun onStopListening(listener: Callback) {
    main.post { delegate?.stopListening() }
  }

  override fun onDestroy() {
    main.post {
      delegate?.destroy()
      delegate = null
    }
    super.onDestroy()
  }

  /** Il riconoscitore vero: on-device se il telefono ce l'ha, altrimenti il primo di un altro pacchetto. */
  private fun createDelegate(): SpeechRecognizer? {
    if (android.os.Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
      return runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(this) }.getOrNull()
    }
    val component = findOtherRecognizer() ?: return null
    return runCatching { SpeechRecognizer.createSpeechRecognizer(this, component) }.getOrNull()
  }

  private fun findOtherRecognizer(): ComponentName? {
    val services = packageManager.queryIntentServices(Intent(SERVICE_INTERFACE), 0)
      .mapNotNull { it.serviceInfo }
      .filter { it.packageName != packageName }
    val preferred = services.firstOrNull { it.packageName in PREFERRED } ?: services.firstOrNull() ?: return null
    return ComponentName(preferred.packageName, preferred.name)
  }

  private companion object {
    val PREFERRED = listOf("com.google.android.googlequicksearchbox", "com.google.android.tts", "com.google.android.as", "com.samsung.android.bixby.agent")
  }
}
