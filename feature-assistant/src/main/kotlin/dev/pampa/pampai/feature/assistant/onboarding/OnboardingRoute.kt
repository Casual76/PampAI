package dev.pampa.pampai.feature.assistant.onboarding

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ai.keys.KeyState
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.antigravity.fluidengine.ui.fluid.FluidAmbient
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMotif
import dev.antigravity.fluidengine.ui.fluid.FluidHeroTone
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidCard
import dev.antigravity.fluidengine.ui.theme.FluidHeroCard
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.pampai.feature.assistant.assist.AssistantRoleCard
import dev.pampa.pampai.feature.assistant.consent.consentItems
import dev.pampa.pampai.feature.assistant.settings.AiKeySetup
import dev.pampa.pampai.feature.assistant.settings.AssistantSettingsViewModel

private const val StepWelcome = 0
private const val StepKeys = 1
private const val StepConsent = 2
private const val StepSystem = 3

/**
 * L'onboarding, in quattro passi: chi e' Aria, le chiavi, il consenso, la voce e il tasto di
 * accensione. Ogni passo e' una pagina `FluidScreen` con il tasto "indietro" che torna al passo
 * prima; alla fine si segna l'onboarding fatto e si va alla chat.
 */
@Composable
fun OnboardingRoute(
  onDone: () -> Unit,
  viewModel: AssistantSettingsViewModel = hiltViewModel(),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  var step by rememberSaveable { mutableIntStateOf(StepWelcome) }
  val ambient = remember { FluidAmbient(tone = FluidHeroTone.Primary, motif = FluidHeroMotif.Cards) }
  BackHandler(enabled = step > StepWelcome) { step-- }
  fun finish() {
    viewModel.setOnboardingDone()
    onDone()
  }

  when (step) {
    StepWelcome -> FluidScreen(title = "Ciao, sono Aria", subtitle = "L'assistente delle app Pampa.", ambient = ambient, itemSpacing = 12.dp) {
      item {
        FluidHeroCard(
          title = "Una chat, una voce, e le tue app",
          subtitle = "Chiedimi del registro, del meteo, dei bus, della musica; fammi impostare sveglie e promemoria; tienimi premuto il tasto di accensione e parlami di quello che vedi sullo schermo.",
        )
      }
      item {
        FluidCard(glass = true) {
          Text(
            "Uso i servizi che scegli tu, con le tue chiavi: Groq, Gemini, OpenRouter. Niente account, niente server in mezzo. Prima di partire servono tre cose: una chiave, il tuo consenso, e il microfono se vuoi parlarmi.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )
        }
      }
      item { FluidButton(text = "Iniziamo", fillWidth = true, onClick = { step = StepKeys }) }
    }

    StepKeys -> FluidScreen(title = "Le chiavi", subtitle = "Ne basta una. Groq e' gratuito ed e' il primo da provare.", onBack = { step = StepWelcome }, ambient = ambient, itemSpacing = 12.dp) {
      item {
        FluidListGroup(glass = true) {
          ProviderId.entries.forEachIndexed { index, provider ->
            if (index > 0) FluidListDivider()
            AiKeySetup(
              provider = provider,
              state = state.keys[provider] ?: KeyState(false, null),
              saveAndVerify = viewModel::saveAndVerify,
              onRemove = viewModel::removeKey,
              initiallyExpanded = provider == ProviderId.GROQ && state.verified.isEmpty(),
            )
          }
        }
      }
      item {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          FluidButton(
            text = if (state.verified.isEmpty()) "Serve una chiave verificata" else "Continua",
            enabled = state.verified.isNotEmpty(),
            fillWidth = true,
            onClick = { step = StepConsent },
          )
          FluidButton(text = "Lo faccio dopo, dalle impostazioni", style = FluidButtonStyle.Plain, fillWidth = true, onClick = { finish() })
        }
      }
    }

    StepConsent -> FluidScreen(title = "Il consenso", subtitle = "Cosa parte, verso chi, cosa resta qui.", onBack = { step = StepKeys }, ambient = ambient, itemSpacing = 12.dp) {
      consentItems(
        canAccept = state.verified.isNotEmpty(),
        onAccept = {
          viewModel.acceptConsentAndEnable()
          step = StepSystem
        },
        onLater = { finish() },
      )
    }

    else -> FluidScreen(title = "Voce e tasto di accensione", subtitle = "Facoltativi, ma sono meta' del bello.", onBack = { step = StepConsent }, ambient = ambient, itemSpacing = 12.dp) {
      item { FluidSectionHeader(title = "Microfono") }
      item { MicrophoneCard() }
      item { FluidSectionHeader(title = "Assistente di sistema") }
      item { AssistantRoleCard() }
      item { FluidButton(text = "Fine, andiamo in chat", fillWidth = true, onClick = { finish() }) }
    }
  }
}

@Composable
private fun MicrophoneCard() {
  val context = LocalContext.current
  var epoch by remember { mutableIntStateOf(0) }
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { epoch++ }
  val granted = remember(epoch) { context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
  FluidListGroup(glass = true) {
    FluidListRow(
      title = "Parlami",
      subtitle = if (granted) "Permesso concesso: puoi farmi domande a voce." else "Per le domande a voce serve il microfono. Lo chiedo solo ora, mai a sorpresa.",
      badge = if (granted) {
        null
      } else {
        { FluidButton(text = "Consenti", style = FluidButtonStyle.Tinted, size = FluidButtonSize.Small, onClick = { launcher.launch(Manifest.permission.RECORD_AUDIO) }) }
      },
    )
  }
}
