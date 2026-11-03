package dev.pampa.pampai.feature.assistant.settings

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.antigravity.fluidengine.foundation.AppUpdater
import dev.antigravity.fluidengine.foundation.AvailableAppUpdate
import dev.antigravity.fluidengine.ui.fluid.FluidUpdateUiState
import dev.antigravity.fluidengine.ui.fluid.isBusy
import dev.antigravity.fluidengine.ui.fluid.toFluidUpdateRelease
import dev.antigravity.fluidengine.ui.fluid.toFluidUpdateState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Dove sta l'aggiornamento in-app, nei termini che la sezione «Aggiornamenti» dell'engine capisce
 * ([FluidUpdateUiState]): un tocco controlla, e se c'e' una versione nuova il tocco dopo la installa.
 *
 * Vive quanto il ViewModel delle impostazioni: un'installazione che sta scaricando non si ferma
 * perche' si esce dalla pagina e ci si torna, e la sezione dice ancora dov'e' arrivata.
 */
@Stable
class UpdateController(
  private val updater: AppUpdater,
  private val scope: CoroutineScope,
  /** La versione installata: il manifest conosce solo la release. */
  private val currentVersion: String,
  private val now: () -> Long = System::currentTimeMillis,
) {
  var state: FluidUpdateUiState by mutableStateOf(FluidUpdateUiState.Idle)
    private set

  /** Quando e' finito l'ultimo controllo, in millisecondi dall'epoca; null se non e' mai finito. */
  var lastCheckedAtMillis: Long? by mutableStateOf(null)
    private set

  private var available: AvailableAppUpdate? = null

  /** Chiede al manifest se c'e' una versione piu' nuova. Un secondo tocco mentre lavora non fa niente. */
  fun check() {
    if (state.isBusy) return
    scope.launch {
      state = FluidUpdateUiState.Checking
      val outcome = updateCheckOutcome(updater.check(currentVersionName = currentVersion))
      available = outcome.update
      state = outcome.state
      lastCheckedAtMillis = now()
    }
  }

  /** Scarica e installa la versione trovata dall'ultimo controllo; dopo un errore e' lo stesso tocco che riprova. */
  fun install() {
    val update = available ?: return
    if (state.isBusy) return
    val release = update.toFluidUpdateRelease()
    scope.launch {
      state = FluidUpdateUiState.Downloading(release = release, progress = null)
      try {
        updater.install(update).collect { step -> state = step.toFluidUpdateState(release) }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: Exception) {
        state = FluidUpdateUiState.Failed(release, failure.message ?: "Errore sconosciuto")
      }
    }
  }
}

/** L'esito di un controllo: cosa mostrare e quale versione tenere da parte per installarla. */
internal data class UpdateCheckOutcome(val state: FluidUpdateUiState, val update: AvailableAppUpdate?)

/**
 * Un controllo che non trova niente non e' un errore ([FluidUpdateUiState.UpToDate]), e uno che non
 * riesce non e' «nessun aggiornamento» ([FluidUpdateUiState.CheckFailed]): il primo dice «sei
 * aggiornato», il secondo «riprova».
 */
internal fun updateCheckOutcome(result: Result<AvailableAppUpdate?>): UpdateCheckOutcome = result.fold(
  onSuccess = { found ->
    if (found == null) {
      UpdateCheckOutcome(FluidUpdateUiState.UpToDate, null)
    } else {
      UpdateCheckOutcome(FluidUpdateUiState.Available(found.toFluidUpdateRelease()), found)
    }
  },
  onFailure = { UpdateCheckOutcome(FluidUpdateUiState.CheckFailed(it.message), null) },
)
