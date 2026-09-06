package dev.pampa.pampai.feature.assistant.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ai.keys.KeyState
import dev.antigravity.fluidengine.ai.keys.ThinkingLevel
import dev.antigravity.fluidengine.ai.orchestrator.AiRequestLog
import dev.antigravity.fluidengine.ai.provider.ModelTier
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.antigravity.fluidengine.foundation.AccentMode
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidChip
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidSegmentedControl
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.theme.FluidCard
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.fluidAccentPresets
import dev.pampa.pampai.core.assistant.settings.SttMode
import dev.pampa.pampai.core.assistant.settings.TtsEngine
import dev.pampa.pampai.feature.assistant.theme.PampaiBrand
import java.text.DateFormat
import java.util.Date
import java.util.Locale

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

/** Le voci "Aria" delle impostazioni: chiavi con la guida, ordine dei provider, modelli, preferenze, voce, ultime richieste. */
fun LazyListScope.assistantSettingsItems(
  viewModel: AssistantSettingsViewModel,
  state: AssistantSettingsUiState,
  onOpenConsent: () -> Unit,
) {
  val verified = state.verified

  item {
    FluidListGroup(glass = true) {
      FluidListRow(
        title = "Aria",
        subtitle = when {
          verified.isEmpty() -> "Serve almeno una chiave verificata qui sotto."
          state.enabled -> "Accesa: risponde in chat e dal tasto di accensione."
          else -> "Spenta: nessuna richiesta parte."
        },
        badge = {
          FluidSwitch(
            checked = state.enabled,
            enabled = verified.isNotEmpty(),
            onCheckedChange = { on ->
              if (on && !state.settings.consentAccepted) onOpenConsent() else viewModel.setEnabled(on)
            },
          )
        },
      )
    }
  }

  item { FluidSectionHeader(title = "Chiavi", detail = "Restano sul telefono, cifrate. Viaggiano solo verso il servizio a cui appartengono.") }
  item {
    FluidListGroup(glass = true) {
      ProviderId.entries.forEachIndexed { index, provider ->
        if (index > 0) FluidListDivider()
        AiKeySetup(
          provider = provider,
          state = state.keys[provider] ?: KeyState(false, null),
          saveAndVerify = viewModel::saveAndVerify,
          onRemove = viewModel::removeKey,
          initiallyExpanded = provider == ProviderId.GROQ && verified.isEmpty(),
        )
        if (provider == ProviderId.OPENROUTER && state.keys[provider]?.present == true) {
          OpenRouterKeyDetails(state)
        }
      }
    }
  }

  if (verified.size > 1) {
    item { FluidSectionHeader(title = "Ordine dei servizi", detail = "Il primo risponde; gli altri sono la riserva quando e' al limite o non risponde.") }
    item {
      Text("Chat", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
      ProviderOrderList(order = state.settings.chatOrder, available = verified, onReorder = viewModel::setChatOrder)
    }
    item {
      Text("Trascrizione della voce", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
      ProviderOrderList(order = state.settings.sttOrder, available = verified, onReorder = viewModel::setSttOrder)
    }
  }

  if (verified.isNotEmpty()) {
    item { FluidSectionHeader(title = "Modelli", detail = "Tre livelli per servizio: il router sceglie gli strumenti, la chat risponde, il profondo vede immagini e legge documenti.") }
    item { AssistantModelsSection(viewModel, state) }
  }

  item { FluidSectionHeader(title = "Preferenze") }
  item { AssistantPreferences(viewModel, state) }

  item { FluidSectionHeader(title = "Voce", detail = "Come Aria ascolta e come legge le risposte.") }
  item { VoicePreferences(viewModel, state) }

  item { FluidSectionHeader(title = "Permessi", detail = "Ogni strumento chiede il suo permesso solo quando serve; da qui li concedi tutti insieme.") }
  item { PermissionsSection() }

  item { FluidSectionHeader(title = "Azioni fidate", detail = "Le azioni che Aria fa senza chiederti conferma.") }
  item { TrustedActionsSection(viewModel, state) }

  item { FluidSectionHeader(title = "App collegate", detail = "Le app Pampa che espongono i loro strumenti ad Aria.") }
  item { ConnectedAppsSection(viewModel) }

  item {
    Text(
      "Le tue domande, gli allegati e i dati che gli strumenti leggono per rispondere partono verso il servizio scelto, con la tua chiave. Le conversazioni e la memoria restano su questo telefono.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
    )
  }

  if (state.recent.isNotEmpty()) {
    item { FluidSectionHeader(title = "Ultime richieste", detail = "Solo in memoria: sparisce chiudendo l'app. I consumi completi stanno nella pagina apposta.") }
    item {
      FluidListGroup(glass = true) {
        state.recent.forEachIndexed { index, log ->
          if (index > 0) FluidListDivider()
          RecentRequestRow(log)
        }
      }
    }
  }
}

/** Le voci "Aspetto": tema, nero assoluto, accento, aptica. Le impostazioni dell'engine. */
fun LazyListScope.appearanceItems(viewModel: AssistantSettingsViewModel, state: AssistantSettingsUiState) {
  item {
    FluidCard(glass = true) {
      FluidSegmentedControl(
        options = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK),
        selected = if (state.engine.themeMode == ThemeMode.AMOLED) ThemeMode.DARK else state.engine.themeMode,
        onSelect = viewModel::setThemeMode,
        label = { mode ->
          when (mode) {
            ThemeMode.SYSTEM -> "Sistema"
            ThemeMode.LIGHT -> "Chiaro"
            else -> "Scuro"
          }
        },
      )
    }
  }
  item {
    FluidListGroup(glass = true) {
      FluidListRow(
        title = "Nero assoluto",
        subtitle = "Sfondi neri nel tema scuro, per gli schermi OLED.",
        badge = { FluidSwitch(checked = state.engine.amoledEnabled, onCheckedChange = viewModel::setAmoled) },
      )
      FluidListDivider()
      FluidListRow(
        title = "Accento",
        subtitle = "Il colore dell'app: quello di PampAI, quello del telefono, o uno dei preset.",
        badge = {
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FluidChip(
              label = PampaiBrand.label,
              selected = state.engine.accentMode == AccentMode.BRAND,
              onClick = { viewModel.setAccentMode(AccentMode.BRAND) },
            )
            FluidChip(
              label = "Dinamico",
              selected = state.engine.accentMode == AccentMode.DYNAMIC,
              onClick = { viewModel.setAccentMode(AccentMode.DYNAMIC) },
            )
          }
        },
      )
      FluidListDivider()
      FluidListRow(
        title = "Preset",
        subtitle = "Un colore fisso, indipendente dal telefono.",
        badge = {
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            fluidAccentPresets.forEach { preset ->
              FluidChip(
                label = preset.label,
                selected = state.engine.accentMode == AccentMode.CUSTOM_PRESET && state.engine.customAccentName == preset.name,
                onClick = { viewModel.setCustomAccent(preset.name) },
              )
            }
          }
        },
      )
      FluidListDivider()
      FluidListRow(
        title = "Aptica",
        subtitle = "Le vibrazioni che accompagnano i tocchi, l'ascolto e le risposte.",
        badge = { FluidSwitch(checked = state.engine.hapticsEnabled, onCheckedChange = viewModel::setHaptics) },
      )
    }
  }
}

@Composable
private fun OpenRouterKeyDetails(state: AssistantSettingsUiState) {
  val info = state.keyInfo[ProviderId.OPENROUTER]
  val chosen = state.settings.chatModel(ProviderId.OPENROUTER)
  val model = state.catalogues[ProviderId.OPENROUTER]?.chat(chosen ?: "")
  Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
    val credits = info?.let {
      val left = it.limitRemainingUsd ?: it.limitUsd
      val today = it.usageDailyUsd ?: 0.0
      "Crediti: ${left?.let { v -> String.format(Locale.ITALIAN, "%.2f $", v) } ?: "illimitati"} · oggi ${String.format(Locale.ITALIAN, "%.3f $", today)}" +
        if (it.isFreeTier) " · account gratuito" else " · account con crediti"
    } ?: "Crediti: verifica la chiave per leggerli"
    Text(credits, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (chosen != null) {
      Text(
        if (model?.free == true || model == null) "Modello scelto: $chosen (gratuito)" else "Modello scelto: $chosen (a pagamento: nessun gratuito con strumenti nel catalogo)",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun AssistantModelsSection(viewModel: AssistantSettingsViewModel, state: AssistantSettingsUiState) {
  var picker by remember { mutableStateOf<ModelPickRequest?>(null) }
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    state.verified.sortedBy { it.ordinal }.forEach { provider ->
      val catalogue = state.catalogues[provider]
      val refreshedAt = state.settings.modelsRefreshedAt[provider]
      FluidListGroup(glass = true) {
        ModelTier.entries.forEachIndexed { index, tier ->
          if (index > 0) FluidListDivider()
          val chosen = state.settings.model(provider, tier)
          val sameAsChat = tier == ModelTier.DEEP && chosen == state.settings.model(provider, ModelTier.CHAT)
          FluidListRow(
            title = "${provider.label} · ${tier.label()}",
            subtitle = "${tier.hint()} · ${catalogue.summary(chosen)}" +
              if (sameAsChat) " · uguale alla chat: scegline uno piu' capace e Aria ci passera' da sola sulle domande difficili" else "",
            onClick = { picker = ModelPickRequest(provider, tier) },
          )
        }
        FluidListDivider()
        FluidListRow(
          title = "${provider.label} · Trascrizione",
          subtitle = catalogue.summary(state.settings.sttModel(provider)),
          onClick = { picker = ModelPickRequest(provider, null, stt = true) },
        )
        FluidListDivider()
        FluidListRow(
          title = "Aggiorna il catalogo",
          subtitle = if (refreshedAt != null) "Letto il ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(refreshedAt))}" else "Mai letto dalla rete",
          onClick = { viewModel.refreshCatalogue(provider) },
        )
      }
    }
  }
  // Dichiarato sempre, mai dentro un `let`: smontarlo alla chiusura toglie l'animazione di uscita.
  ModelPickerPortal(
    request = picker,
    catalogues = state.catalogues,
    selectedModel = { request -> if (request.stt) state.settings.sttModel(request.provider) else request.tier?.let { state.settings.model(request.provider, it) } },
    fallbacks = state.settings.openRouterFallbacks,
    onSelect = { request, id -> if (request.stt) viewModel.setSttModel(request.provider, id) else request.tier?.let { viewModel.setModel(request.provider, it, id) } },
    onFallbacks = viewModel::setOpenRouterFallbacks,
    onDismiss = { picker = null },
  )
}

@Composable
private fun AssistantPreferences(viewModel: AssistantSettingsViewModel, state: AssistantSettingsUiState) {
  FluidListGroup(glass = true) {
    FluidListRow(
      title = "Ragionamento",
      subtitle = "Quanto il modello pensa prima di rispondere: piu' alto, piu' lento e piu' preciso.",
      badge = {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          ThinkingLevel.entries.forEach { level ->
            FluidChip(label = level.label(), selected = state.settings.thinking == level, onClick = { viewModel.setThinking(level) })
          }
        }
      },
    )
    FluidListDivider()
    FluidListRow(
      title = "Azioni",
      subtitle = "Sveglie, chiamate, eventi, installazioni, azioni nelle app collegate. Quelle che contano chiedono conferma; le fidate le scegli tu.",
      badge = { FluidSwitch(checked = state.settings.actionsEnabled, onCheckedChange = viewModel::setActionsEnabled) },
    )
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
    FluidListRow(
      title = "Riconoscimento",
      subtitle = "Doppio: le parole compaiono mentre parli (sistema) e alla fine le corregge Whisper. Whisper: solo la trascrizione finale. Sistema: solo il telefono, anche senza chiavi.",
      badge = {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          SttMode.entries.forEach { mode ->
            FluidChip(label = mode.label(), selected = state.pampai.sttMode == mode, onClick = { viewModel.setSttMode(mode) })
          }
        }
      },
    )
    FluidListDivider()
    FluidListRow(
      title = "Parti in testo",
      subtitle = "Dal tasto di accensione la barra si apre come campo di testo, senza ascoltare subito.",
      badge = { FluidSwitch(checked = state.pampai.startInText, onCheckedChange = viewModel::setStartInText) },
    )
    FluidListDivider()
    FluidListRow(
      title = "Leggi le risposte",
      subtitle = "Dopo una domanda a voce, la risposta viene letta ad alta voce.",
      badge = { FluidSwitch(checked = state.settings.speakReplies, onCheckedChange = viewModel::setSpeakReplies) },
    )
    FluidListDivider()
    FluidListRow(
      title = "Voce",
      subtitle = "Chi legge: la voce del telefono, o una voce cloud (Gemini in italiano; Groq solo in inglese) con ripiego sul telefono.",
      badge = {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          TtsEngine.entries.forEach { engine ->
            FluidChip(label = engine.label(), selected = state.pampai.ttsEngine == engine, onClick = { viewModel.setTtsEngine(engine) })
          }
        }
      },
    )
  }
}

@Composable
private fun RecentRequestRow(log: AiRequestLog) {
  val details = buildList {
    add("${log.provider.label}${if (log.switchedTo.isNotEmpty()) " → ${log.switchedTo.joinToString(", ") { it.label }}" else ""} · ${log.models.values.distinct().joinToString(", ")} · ${log.steps} passi · ${log.durationMillis / 1000} s")
    if (log.groups.isNotEmpty()) add("gruppi: ${log.groups.joinToString(", ")}")
    if (log.tools.isNotEmpty()) add("strumenti: ${log.tools.joinToString(", ") { "${it.name} ${it.millis} ms${if (it.ok) "" else " ✕"}" }}")
    log.usage?.let { usage ->
      add("token: ${usage.promptTokens} in, ${usage.completionTokens} out" + (usage.costUsd?.let { " · ${String.format(Locale.getDefault(), "%.4f $", it)}" } ?: ""))
    }
    if (log.waitedSeconds > 0) add("attesa: ${log.waitedSeconds} s")
    log.error?.let { add(it) }
  }.joinToString("\n")
  FluidListRow(
    title = log.question.take(80),
    subtitle = details,
    meta = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(log.startedAtMillis)),
  )
}
