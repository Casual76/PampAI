package dev.pampa.pampai.feature.assistant.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
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
  onBack: (() -> Unit)? = null,
  onOpenUsage: () -> Unit = {},
  onOpenMemory: () -> Unit = {},
  /** La sezione da mostrare aprendo: "azioni", "permessi", "chiavi"... (un chip `[[impostazioni:...]]`). */
  section: String? = null,
  viewModel: AssistantSettingsViewModel = hiltViewModel(),
  about: AboutViewModel = hiltViewModel(),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val listState = rememberLazyListState()
  ScrollToSection(listState, section)
  FluidScreen(
    listState = listState,
    title = "Impostazioni",
    subtitle = "Aria, le chiavi, la voce, l'aspetto.",
    onBack = onBack,
    extraBottomPadding = bottomInset,
    itemSpacing = 12.dp,
    ambient = remember { FluidAmbient(tone = FluidHeroTone.Primary, motif = FluidHeroMotif.Cards) },
  ) {
    item(key = "section-aspetto") { FluidSectionHeader(title = "Aspetto") }
    appearanceItems(viewModel, state)

    item(key = "section-aria") { FluidSectionHeader(title = "Aria") }
    assistantSettingsItems(viewModel, state, onOpenConsent)
    item {
      FluidListGroup(glass = true) {
        FluidListRow(
          title = "Consumi",
          subtitle = "Richieste, token e costo stimato per servizio e modello; limiti e avvisi.",
          onClick = onOpenUsage,
        )
        FluidListDivider()
        FluidListRow(
          title = "Memoria e promemoria",
          subtitle = "Cosa Aria ricorda di te e i promemoria in arrivo: vedili, correggili, cancellali.",
          onClick = onOpenMemory,
        )
      }
    }

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

/**
 * Il chip "Impostazioni · azioni" apriva le impostazioni in cima, e l'interruttore stava otto
 * schermate piu' giu'. Qui si scende fino all'intestazione della sezione: le chiavi degli item
 * non si possono cercare in una lista pigra, quindi si scorre a passi finche' non compare, poi ci si
 * ferma su di lei.
 */
@OptIn(FlowPreview::class)
@Composable
private fun ScrollToSection(listState: LazyListState, section: String?) {
  val key = section?.let { SectionKeys[it.lowercase()] } ?: return
  LaunchedEffect(key) {
    // Le sezioni arrivano con lo stato (chiavi, modelli, app collegate): si cerca quando la lista ha
    // smesso di crescere, altrimenti si arrivava in fondo a una pagina ancora corta e ci si fermava.
    snapshotFlow { listState.layoutInfo.totalItemsCount }.filter { it > 0 }.debounce(300).first()
    repeat(30) {
      val hit = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
      // Un passo interrotto da un altro scorrimento (la testata della pagina che si ripiega) non
      // chiude la ricerca: si riprova. Solo la cancellazione vera dell'effetto la ferma.
      try {
        if (hit != null) {
          listState.animateScrollToItem(hit.index)
          return@LaunchedEffect
        }
        if (!listState.canScrollForward) return@LaunchedEffect
        listState.scrollBy(listState.layoutInfo.viewportSize.height * 0.8f)
      } catch (e: CancellationException) {
        if (!isActive) throw e
      }
    }
  }
}

/** Dal nome che usa il modello nei chip alla chiave dell'intestazione. */
private val SectionKeys = mapOf(
  "aspetto" to "section-aspetto",
  "aria" to "section-aria",
  "chiavi" to "section-chiavi",
  "ordine" to "section-ordine",
  "servizi" to "section-ordine",
  "modelli" to "section-modelli",
  "azioni" to "section-preferenze",
  "riserva" to "section-preferenze",
  "preferenze" to "section-preferenze",
  "voce" to "section-voce",
  "permessi" to "section-permessi",
  "fidate" to "section-fidate",
  "app" to "section-app",
  "collegate" to "section-app",
)
