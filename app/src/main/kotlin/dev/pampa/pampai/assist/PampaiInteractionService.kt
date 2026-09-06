package dev.pampa.pampai.assist

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.util.Log
import dev.pampa.pampai.MainActivity
import java.lang.ref.WeakReference

/**
 * Il servizio che il sistema tiene in vita quando PampAI e' l'assistente predefinito: il tasto di
 * accensione tenuto premuto, il gesto dagli angoli e il blocco schermo passano da qui e aprono la
 * sessione ([PampaiSession]). Il tile, le scorciatoie e il trampolino chiedono a [show]; se il
 * ruolo non e' nostro, ripiegano sulla chat dell'app con la voce gia' accesa.
 */
class PampaiInteractionService : VoiceInteractionService() {

  override fun onReady() {
    super.onReady()
    instance = WeakReference(this)
  }

  override fun onShutdown() {
    instance = null
    super.onShutdown()
  }

  override fun onLaunchVoiceAssistFromKeyguard() {
    showSession(Bundle().apply { putString(EXTRA_SOURCE, SOURCE_KEYGUARD) }, SESSION_FLAGS)
  }

  companion object {
    const val EXTRA_SOURCE = "dev.pampa.pampai.assist.SOURCE"
    const val SOURCE_ASSIST = "assist"
    const val SOURCE_KEYGUARD = "keyguard"
    const val SOURCE_TILE = "tile"
    const val SOURCE_SHORTCUT = "shortcut"
    const val SOURCE_TEXT = "text"

    private const val SESSION_FLAGS = VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT

    @Volatile private var instance: WeakReference<PampaiInteractionService>? = null

    /** Vero se PampAI e' l'assistente attivo e il servizio e' pronto a mostrare la sessione. */
    fun isActive(context: Context): Boolean =
      isActiveService(context, ComponentName(context, PampaiInteractionService::class.java)) && instance?.get() != null

    /** Apre la sessione sopra l'app corrente; false se il ruolo non e' nostro (chi chiama ripiega sull'app). */
    fun show(context: Context, source: String): Boolean {
      val service = instance?.get() ?: return false
      if (!isActiveService(context, ComponentName(context, PampaiInteractionService::class.java))) return false
      return runCatching {
        service.showSession(Bundle().apply { putString(EXTRA_SOURCE, source) }, SESSION_FLAGS)
        true
      }.onFailure { Log.w("PampAI", "showSession fallita: ${it.message}") }.getOrDefault(false)
    }

    /** Il ripiego: la chat dell'app, con la voce gia' accesa (o il testo, se chiesto). */
    fun openApp(context: Context, voice: Boolean) {
      val intent = Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .putExtra(MainActivity.EXTRA_VOICE, voice)
        .putExtra(MainActivity.EXTRA_NEW, true)
      runCatching { context.startActivity(intent) }
    }
  }
}
