package dev.pampa.pampai.core.assistant.remote

import dev.antigravity.fluidengine.config.EngineRemoteConfig
import dev.antigravity.fluidengine.foundation.EngineCompatibility
import dev.antigravity.fluidengine.foundation.EngineFlag
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** I flag remoti, dichiarati con il valore con cui la build e' stata provata. */
object PampaiFlags {
  /** La sessione di sistema (tasto di accensione). */
  val AssistantSession = EngineFlag(key = "assistant_session", default = true)

  /** I tool delle app collegate attraverso il bridge. */
  val FederatedTools = EngineFlag(key = "federated_tools", default = true)

  /** La voce cloud per leggere le risposte. */
  val CloudTts = EngineFlag(key = "cloud_tts", default = true)

  /** La ricerca web attraverso il provider. */
  val WebSearch = EngineFlag(key = "web_search", default = true)
}

/** Cosa il file di controllo dice di questa build: se e' ferma, se va aggiornata, e perche'. */
data class RemoteStatus(
  /** Il kill switch acceso: le domande non partono, e questa e' la frase da mostrare. */
  val stopped: Boolean = false,
  val message: String? = null,
  val compatibility: EngineCompatibility = EngineCompatibility.OK,
  /** L'avviso libero del manifest, se c'e'. */
  val notice: String? = null,
)

/**
 * Il file di controllo remoto, letto da chi deve obbedirgli: l'engine (kill switch, ricerca web),
 * la voce (TTS cloud), la sessione di sistema, la chat (il banner).
 *
 * Prima i flag erano dichiarati e mai letti, e il kill switch finiva in un log: una build rotta
 * non si poteva fermare da remoto. Tutto qui legge l'ultima copia in cache: senza rete vale
 * l'ultima risposta buona (o i default compilati), mai un "spento" inventato.
 */
@Singleton
class RemoteSwitches @Inject constructor(private val config: EngineRemoteConfig) {

  val status: Flow<RemoteStatus> = config.config
    .map { current ->
      RemoteStatus(
        stopped = current.killSwitch.enabled,
        message = current.killSwitch.message,
        compatibility = runCatching { config.compatibility() }.getOrDefault(EngineCompatibility.OK),
        notice = current.notice,
      )
    }
    .distinctUntilChanged()

  fun flag(flag: EngineFlag): Flow<Boolean> = config.flag(flag)

  suspend fun isEnabled(flag: EngineFlag): Boolean = runCatching { config.current().isEnabled(flag) }.getOrDefault(flag.default)

  /** La frase del kill switch se e' acceso, altrimenti null. */
  suspend fun stopMessage(): String? {
    val kill = runCatching { config.current().killSwitch }.getOrNull() ?: return null
    if (!kill.enabled) return null
    return kill.message?.takeIf { it.isNotBlank() } ?: DEFAULT_STOP
  }

  companion object {
    const val DEFAULT_STOP = "Aria e' sospesa per un problema noto a questa versione. Arriva presto un aggiornamento dal Pampa Store."
  }
}
