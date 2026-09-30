package dev.pampa.pampai

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import dev.antigravity.fluidengine.config.EngineRemoteConfig
import dev.antigravity.fluidengine.foundation.EngineCompatibility
import dev.pampa.pampai.core.assistant.bridge.RemoteCatalogs
import dev.pampa.pampai.core.assistant.remote.PampaiFlags
import dev.pampa.pampai.core.assistant.runtime.ModelsMaintenance
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltAndroidApp
class PampaiApplication : Application() {

  @Inject lateinit var remoteConfig: EngineRemoteConfig
  @Inject lateinit var remoteCatalogs: RemoteCatalogs
  @Inject lateinit var modelsMaintenance: ModelsMaintenance

  /** Vive quanto il processo: niente di quello che parte qui ha qualcosa da cui essere cancellato. */
  private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  override fun onCreate() {
    super.onCreate()

    // Il file di controllo, se la copia in cache e' vecchia. Non blocca niente: finche' non
    // arriva, l'app usa l'ultima risposta valida (o i default compilati).
    applicationScope.launch {
      runCatching { remoteConfig.refreshIfStale() }
    }

    // Le app collegate: si cercano subito e a ogni pacchetto che cambia (se il flag remoto non le spegne).
    applicationScope.launch {
      val enabled = runCatching { remoteConfig.flag(PampaiFlags.FederatedTools).first() }.getOrDefault(true)
      if (enabled) remoteCatalogs.start()
    }

    // I modelli scelti contro il catalogo vero, una volta al giorno: un modello ritirato si
    // sostituisce qui, prima che una domanda ci sbatta contro, e non solo aprendo le impostazioni.
    applicationScope.launch {
      runCatching { modelsMaintenance.runDaily() }
    }

    // Cosa fare se questa build e' rimasta indietro: la chat lo mostra (RemoteSwitches), il kill
    // switch ferma le domande nell'engine. Qui resta la riga nel log per chi fa il debug.
    applicationScope.launch {
      runCatching {
        when (remoteConfig.compatibility()) {
          EngineCompatibility.OK -> Unit
          EngineCompatibility.UPDATE_RECOMMENDED -> Log.i(TAG, "engine: aggiornamento consigliato")
          EngineCompatibility.UPDATE_REQUIRED -> Log.w(TAG, "engine: aggiornamento necessario")
        }
      }
    }
  }

  private companion object {
    const val TAG = "PampAI"
  }
}
