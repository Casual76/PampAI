package dev.pampa.pampai.core.assistant.voice

import dev.pampa.pampai.core.assistant.settings.SttMode

/**
 * Quale ascolto si puo' fare davvero, dato cosa chiede l'utente e cosa offre il telefono. Puro,
 * cosi' si prova sulla JVM: e' la tabella delle degradazioni della decisione "STT doppio".
 *
 * Due catture concorrenti sono impossibili (Android da' il microfono a una sola app): il doppio
 * esiste solo se il riconoscitore on-device accetta l'audio da un tubo (Android 13+).
 */
object SttPolicy {

  fun resolve(requested: SttMode, canBeFed: Boolean, systemAvailable: Boolean, sttKeys: Boolean): SttMode = when (requested) {
    SttMode.DUAL -> when {
      canBeFed && sttKeys -> SttMode.DUAL
      sttKeys -> SttMode.WHISPER
      systemAvailable -> SttMode.SYSTEM
      else -> SttMode.WHISPER
    }
    SttMode.WHISPER -> if (sttKeys || !systemAvailable) SttMode.WHISPER else SttMode.SYSTEM
    SttMode.SYSTEM -> if (systemAvailable) SttMode.SYSTEM else SttMode.WHISPER
  }
}

/** "Si'" e "no" detti a voce, per le conferme: lessico corto, tollerante, niente altro. */
object YesNo {
  private val yes = setOf("si", "sì", "ok", "okay", "vai", "conferma", "confermo", "certo", "procedi", "esatto", "va bene", "d'accordo", "d accordo", "yes", "affermativo", "fallo")
  private val no = setOf("no", "annulla", "ferma", "lascia", "niente", "non", "nope", "negativo", "lascia stare", "aspetta", "stop")

  /** Vero per si', falso per no, null se non si capisce. */
  fun parse(text: String): Boolean? {
    val normalized = text.lowercase().trim().replace(Regex("[^a-zàèéìòù' ]"), " ").replace(Regex(" +"), " ").trim()
    if (normalized.isEmpty()) return null
    if (normalized in yes) return true
    if (normalized in no) return false
    val words = normalized.split(" ")
    val first = words.first()
    if (words.any { it in no } || first.startsWith("non")) return false
    if (words.any { it in yes }) return true
    return null
  }
}
