package dev.pampa.pampai.feature.assistant.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ai.keys.ThinkingLevel
import dev.pampa.pampai.feature.assistant.assist.AssistantRoleCard
import dev.antigravity.fluidengine.aiui.settings.FluidAiSettingsPart
import dev.antigravity.fluidengine.aiui.settings.FluidAiSettingsActions
import dev.antigravity.fluidengine.aiui.settings.FluidAiSettingsOptions
import dev.antigravity.fluidengine.aiui.settings.FluidAiSettingsSlots
import dev.antigravity.fluidengine.aiui.settings.FluidAiSettingsState
import dev.antigravity.fluidengine.aiui.settings.fluidAiSettingsSection
import dev.antigravity.fluidengine.aiui.strings.FluidAiSettingsStrings
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidChip
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidToggleRow
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.pampai.core.assistant.settings.SttMode
import dev.pampa.pampai.core.assistant.settings.TtsEngine

fun ThinkingLevel.label(): String = when (this) {
  ThinkingLevel.LOW -> "Basso"
  ThinkingLevel.MEDIUM -> "Medio"
  ThinkingLevel.HIGH -> "Alto"
}

fun SttMode.label(): String = when (this) {
  SttMode.DUAL -> "Doppio"
  SttMode.WHISPER -> "Whisper"
  SttMode.SYSTEM -> "Sistema"
}

fun TtsEngine.label(): String = when (this) {
  TtsEngine.SYSTEM -> "Sistema"
  TtsEngine.GEMINI -> "Gemini"
  TtsEngine.GROQ_EN -> "Groq (inglese)"
}

/**
 * Le parole di Aria nelle impostazioni dell'assistente: il default dell'engine, con il nome
 * dell'assistente e le frasi che dicono cosa fa PampAI (quali strumenti, cosa parte, cosa resta).
 */
internal val AriaAiStrings = FluidAiSettingsStrings(
  enabledTitle = "Aria",
  enabledNeedsKey = "Configura una chiave verificata in Chiavi e privacy.",
  enabledOn = "Accesa: risponde in chat e dal tasto di accensione.",
  enabledOff = "Spenta: nessuna richiesta parte.",
  // Con la chiave gia' verificata il campo sta stretto accanto a «Verifica» e «Rimuovi»: il segnaposto
  // dell'engine ("Nuova chiave (vuoto = tieni quella salvata)") andava a capo in cinque righe. Cosa succede
  // lasciandolo vuoto lo dice gia' il sottotitolo della scheda («Chiave verificata il ...»).
  keyPlaceholderKeep = "Nuova chiave",
  orderDetail = "Ordine predefinito dei servizi, usato anche per la trascrizione. In chat risponde il modello scelto nella conversazione.",
  modelSameAsChat = " · uguale alla chat: scegline uno piu' capace e Aria ci passera' da sola sulle domande difficili",
  orPolicyDenyDetail = "Aria chiede a OpenRouter di non usarli: i modelli i cui endpoint gratuiti registrano i prompt non rispondono.",
  thinkingDetailAuto = "Impegno e supporto visivo si scelgono nel controllo della conversazione.",
  actionsDetail = "Sveglie, chiamate, eventi, installazioni, azioni nelle app collegate. Quelle che contano chiedono conferma; le fidate le scegli tu.",
  failoverDetail = "La conversazione mantiene il modello scelto, senza cambi automatici.",
  privacyNote = "Le tue domande, gli allegati e i dati che gli strumenti leggono per rispondere partono verso il servizio scelto, con la tua chiave. Le conversazioni e la memoria restano su questo telefono.",
  recentDetail = "Solo in memoria: sparisce chiudendo l'app. I consumi completi stanno nella pagina apposta.",
)

/** Cosa l'engine mostra della sezione Aria: tutto, tranne le righe che PampAI tiene nel suo gruppo «Voce». */
private val AriaAiOptions = FluidAiSettingsOptions(
  requireConsent = true,
  // La lettura delle risposte e il microfono stanno nel gruppo «Voce», con il riconoscimento e il motore.
  speakReplies = false,
  microphone = false,
)

/** Lo stato delle impostazioni di Aria nei termini della sezione dell'engine (dati puri). */
internal fun AssistantSettingsUiState.toFluidAiState(): FluidAiSettingsState = FluidAiSettingsState(
  settings = settings,
  keys = keys,
  catalogues = catalogues,
  keyInfo = keyInfo,
  recent = recent,
  thinkingAuto = pampai.thinkingAuto,
  failoverEnabled = pampai.failoverEnabled,
)

/** I tocchi della sezione, legati ai metodi del ViewModel. */
internal fun AssistantSettingsViewModel.fluidAiActions(onRequestConsent: () -> Unit): FluidAiSettingsActions = FluidAiSettingsActions(
  saveAndVerify = ::saveAndVerify,
  removeKey = { removeKey(it) },
  setEnabled = { setEnabled(it) },
  setChatOrder = { setChatOrder(it) },
  setSttOrder = { setSttOrder(it) },
  setModel = { provider, tier, model -> setModel(provider, tier, model) },
  setSttModel = { provider, model -> setSttModel(provider, model) },
  setOpenRouterFallbacks = { setOpenRouterFallbacks(it) },
  setOpenRouterDataPolicy = { setOpenRouterDataPolicy(it) },
  refreshCatalogue = { refreshCatalogue(it) },
  setThinking = { setThinking(it) },
  setThinkingAuto = { setThinkingAuto(it) },
  setActionsEnabled = { setActionsEnabled(it) },
  setFailoverEnabled = { setFailoverEnabled(it) },
  onRequestConsent = onRequestConsent,
)

/**
 * Le voci «Aria» delle impostazioni: la sezione dell'assistente dell'engine (interruttore, chiavi con
 * la guida, ordine dei servizi, modelli, preferenze, ultime richieste) e, negli slot, quello che e' di
 * PampAI: comandi rapidi, voce, permessi, azioni fidate e app collegate.
 *
 * Le chiavi degli item sono `aria-*` (`aria-keys-header`, `aria-models-header`...): [SettingsPage]
 * porta il chip `[[impostazioni:chiavi]]` all'intestazione giusta.
 */
internal fun LazyListScope.assistantSettingsItems(
  viewModel: AssistantSettingsViewModel,
  state: AssistantSettingsUiState,
  onOpenConsent: () -> Unit,
  page: SettingsPage = SettingsPage.AI,
) {
  val parts = when (page) {
    SettingsPage.KEYS -> setOf(FluidAiSettingsPart.Keys, FluidAiSettingsPart.Privacy)
    SettingsPage.MODELS -> setOf(FluidAiSettingsPart.Models)
    SettingsPage.SERVICES -> setOf(FluidAiSettingsPart.Order)
    SettingsPage.POLICY -> setOf(FluidAiSettingsPart.Recent)
    SettingsPage.AI -> setOf(FluidAiSettingsPart.Enabled)
    else -> emptySet()
  }
  if (parts.isNotEmpty()) fluidAiSettingsSection(
    state = state.toFluidAiState(), actions = viewModel.fluidAiActions(onOpenConsent),
    strings = AriaAiStrings, options = AriaAiOptions.copy(actions = false),
    parts = parts, glass = true, keyPrefix = "aria",
  )
  when (page) {
    SettingsPage.VOICE -> {
      item { VoicePreferences(viewModel, state) }
      item { AssistantRoleCard() }
    }
    SettingsPage.ACTIONS -> {
      item { FluidListGroup(glass = true) {
        FluidToggleRow(title = "Azioni", subtitle = AriaAiStrings.actionsDetail,
          checked = state.settings.actionsEnabled, onCheckedChange = viewModel::setActionsEnabled)
        FluidListDivider()
        FluidToggleRow(title = "Comandi rapidi", subtitle = "Comandi locali sul telefono, anche offline.",
          checked = state.pampai.quickCommands, onCheckedChange = viewModel::setQuickCommands)
      } }
    }
    SettingsPage.PERMISSIONS -> item { PermissionsSection() }
    SettingsPage.TRUSTED -> item { TrustedActionsSection(viewModel, state) }
    SettingsPage.APPS -> item { ConnectedAppsSection(viewModel) }
    else -> Unit
  }
}

/**
 * Una riga di lista la cui impostazione e' una scelta fra chip.
 *
 * I chip **non** stanno nel `badge`: quello slot si prende la larghezza che gli serve e lascia al
 * testo quella che avanza, cosi' con tre o quattro voci il titolo finisce spezzato una lettera per
 * riga ("Ricono / scimen / to") e il sottotitolo una parola per riga. Vanno sotto, a tutta
 * larghezza, e scorrono in orizzontale quando non ci stanno.
 */
@Composable
private fun ChoiceRow(title: String, subtitle: String, chips: @Composable () -> Unit) {
  FluidListRow(title = title, subtitle = subtitle)
  Row(
    Modifier
      .horizontalScroll(rememberScrollState())
      .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    chips()
  }
}

@Composable
private fun VoicePreferences(viewModel: AssistantSettingsViewModel, state: AssistantSettingsUiState) {
  val context = LocalContext.current
  var permissionEpoch by remember { mutableIntStateOf(0) }
  val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionEpoch++ }
  val micGranted = remember(permissionEpoch) { context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
  FluidListGroup(glass = true) {
    FluidListRow(
      title = "Microfono",
      subtitle = if (micGranted) "Permesso concesso: il tasto e l'invocazione ascoltano." else "Serve per fare domande a voce.",
      badge = if (micGranted) {
        null
      } else {
        {
          FluidButton(
            text = "Consenti",
            style = FluidButtonStyle.Tinted,
            size = FluidButtonSize.Small,
            onClick = {
              micLauncher.launch(Manifest.permission.RECORD_AUDIO)
              // Negato per sempre: l'unica strada sono le impostazioni di sistema.
              if (permissionEpoch > 0) {
                runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
              }
            },
          )
        }
      },
    )
    FluidListDivider()
    ChoiceRow(
      title = "Riconoscimento",
      subtitle = "Doppio: le parole compaiono mentre parli (sistema) e alla fine le corregge Whisper. Whisper: solo la trascrizione finale. Sistema: solo il telefono, anche senza chiavi.",
    ) {
      SttMode.entries.forEach { mode ->
        FluidChip(label = mode.label(), selected = state.pampai.sttMode == mode, onClick = { viewModel.setSttMode(mode) })
      }
    }
    FluidListDivider()
    FluidToggleRow(
      title = "Parti in testo",
      subtitle = "Dal tasto di accensione la barra si apre come campo di testo, senza ascoltare subito.",
      checked = state.pampai.startInText,
      onCheckedChange = viewModel::setStartInText,
    )
    FluidListDivider()
    FluidToggleRow(
      title = "Leggi le risposte",
      subtitle = "Dopo una domanda a voce, la risposta viene letta ad alta voce.",
      checked = state.settings.speakReplies,
      onCheckedChange = viewModel::setSpeakReplies,
    )
    FluidListDivider()
    ChoiceRow(
      title = "Voce",
      subtitle = "Chi legge: la voce del telefono, o una voce cloud (Gemini in italiano; Groq solo in inglese) con ripiego sul telefono.",
    ) {
      TtsEngine.entries.forEach { engine ->
        FluidChip(label = engine.label(), selected = state.pampai.ttsEngine == engine, onClick = { viewModel.setTtsEngine(engine) })
      }
    }
  }
}
