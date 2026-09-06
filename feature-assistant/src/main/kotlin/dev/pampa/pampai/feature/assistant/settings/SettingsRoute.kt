package dev.pampa.pampai.feature.assistant.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.antigravity.fluidengine.foundation.AppUpdateInstallState
import dev.antigravity.fluidengine.foundation.AppUpdater
import dev.antigravity.fluidengine.foundation.AvailableAppUpdate
import dev.antigravity.fluidengine.ui.fluid.FluidAmbient
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMotif
import dev.antigravity.fluidengine.ui.fluid.FluidHeroTone
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.fluidLicensesSection
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.pampai.feature.assistant.assist.AssistantRoleCard
import javax.inject.Inject
import kotlinx.coroutines.launch

/** L'aggiornamento in-app: lo stesso manifest che il Pampa Store legge. Vive nell'app, arriva qui via Hilt. */
@HiltViewModel
class AboutViewModel @Inject constructor(val updater: AppUpdater) : ViewModel()

/**
 * Impostazioni: aspetto, Aria (chiavi, modelli, preferenze, voce), assistente di sistema,
 * informazioni con l'aggiornamento in-app, e i crediti dell'engine.
 */
@Composable
fun SettingsRoute(
  bottomInset: Dp,
  onOpenConsent: () -> Unit,
  viewModel: AssistantSettingsViewModel = hiltViewModel(),
  about: AboutViewModel = hiltViewModel(),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  FluidScreen(
    title = "Impostazioni",
    subtitle = "Aria, le chiavi, la voce, l'aspetto.",
    extraBottomPadding = bottomInset,
    itemSpacing = 12.dp,
    ambient = remember { FluidAmbient(tone = FluidHeroTone.Primary, motif = FluidHeroMotif.Cards) },
  ) {
    item { FluidSectionHeader(title = "Aspetto") }
    appearanceItems(viewModel, state)

    item { FluidSectionHeader(title = "Aria") }
    assistantSettingsItems(viewModel, state, onOpenConsent)

    item { FluidSectionHeader(title = "Assistente di sistema", detail = "Per richiamare Aria tenendo premuto il tasto di accensione.") }
    item { AssistantRoleCard() }

    item { FluidSectionHeader(title = "Informazioni") }
    item { AboutGroup(about.updater) }
    fluidLicensesSection()
  }
}

@Composable
private fun AboutGroup(updater: AppUpdater) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var status by remember { mutableStateOf("Tocca per controllare") }
  var available by remember { mutableStateOf<AvailableAppUpdate?>(null) }
  var busy by remember { mutableStateOf(false) }
  FluidListGroup(glass = true) {
    FluidListRow(title = "Versione", subtitle = "PampAI ${appVersion(context)}")
    FluidListDivider()
    FluidListRow(
      title = "Aggiornamenti",
      subtitle = status,
      onClick = {
        if (busy) return@FluidListRow
        val update = available
        scope.launch {
          busy = true
          if (update == null) {
            status = "Controllo…"
            updater.check(currentVersionName = appVersion(context))
              .onSuccess { found ->
                if (found == null) {
                  status = "Sei all'ultima versione"
                } else {
                  available = found
                  status = "Disponibile ${found.version} — tocca per installare"
                }
              }
              .onFailure { status = "Controllo non riuscito: sei offline?" }
          } else {
            updater.install(update).collect { state ->
              status = when (state) {
                is AppUpdateInstallState.Downloading -> "Scarico… ${(state.progress * 100).toInt()}%"
                is AppUpdateInstallState.Verifying -> state.message
                is AppUpdateInstallState.Installing -> state.message
                is AppUpdateInstallState.AwaitingUserAction -> state.message
                is AppUpdateInstallState.Installed -> "Installata: riapri l'app"
                is AppUpdateInstallState.Error -> "Errore: ${state.message}"
              }
            }
            available = null
          }
          busy = false
        }
      },
    )
    FluidListDivider()
    FluidListRow(
      title = "Sorgente",
      subtitle = "github.com/Casual76/PampAI",
      onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Casual76/PampAI"))) } },
    )
  }
}

private fun appVersion(context: Context): String = runCatching {
  context.packageManager.getPackageInfo(context.packageName, 0).versionName
}.getOrNull() ?: "?"
