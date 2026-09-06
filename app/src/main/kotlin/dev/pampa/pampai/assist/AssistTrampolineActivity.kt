package dev.pampa.pampai.assist

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * L'Activity invisibile dietro `ACTION_ASSIST` e `ACTION_VOICE_COMMAND` (il tasto dell'assistente di
 * alcune tastiere e auricolari): prova ad aprire la sessione di sistema; se PampAI non e'
 * l'assistente predefinito, apre la chat dell'app con la voce accesa.
 */
class AssistTrampolineActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val voice = intent?.action != Intent.ACTION_ASSIST || intent.getBooleanExtra(EXTRA_VOICE, true)
    if (!PampaiInteractionService.show(this, PampaiInteractionService.SOURCE_SHORTCUT)) {
      PampaiInteractionService.openApp(this, voice = voice)
    }
    finish()
    overridePendingTransition(0, 0)
  }

  companion object {
    const val EXTRA_VOICE = "dev.pampa.pampai.assist.VOICE"
  }
}
