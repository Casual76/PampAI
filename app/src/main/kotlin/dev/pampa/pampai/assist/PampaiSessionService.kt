package dev.pampa.pampai.assist

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/** Il sistema chiede una sessione nuova: la finestra di Aria sopra l'app corrente. */
class PampaiSessionService : VoiceInteractionSessionService() {
  override fun onNewSession(args: Bundle?): VoiceInteractionSession = PampaiSession(this)
}
