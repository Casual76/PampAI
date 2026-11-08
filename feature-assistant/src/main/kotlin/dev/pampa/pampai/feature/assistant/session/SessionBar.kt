package dev.pampa.pampai.feature.assistant.session

import kotlinx.coroutines.launch

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ai.orchestrator.AssistantState
import dev.antigravity.fluidengine.ai.orchestrator.MicLevel
import dev.antigravity.fluidengine.aiui.chat.*
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.pampa.pampai.feature.assistant.chat.AriaAssistantStrings
import dev.pampa.pampai.feature.assistant.chat.AriaChatStrings
import dev.pampa.pampai.feature.assistant.chat.AssistantComposer
import kotlinx.coroutines.flow.StateFlow

/** Same Fluid composer as the app; no entry delay, fixed capsule height or one-line input. */
@Composable
internal fun SessionBar(
  stamp: Long,
  text: String,
  currentText: () -> String,
  onTextChange: (String) -> Unit,
  state: AssistantState,
  textMode: Boolean,
  partial: String?,
  speaking: Boolean,
  hasAttachments: Boolean,
  orb: Boolean,
  backdrop: GlassBackdropState,
  micLevel: StateFlow<MicLevel>,
  onAsk: suspend (String) -> Boolean,
  onVoice: () -> Unit,
  onStopVoice: () -> Unit,
  onVoiceToText: () -> Unit,
  onStop: () -> Unit,
  onStopSpeaking: () -> Unit,
  onAttachScreen: () -> Unit,
  modelControl: (@Composable () -> Unit)? = null,
  preparingVoice: Boolean = false,
) {
  val listening = state is AssistantState.Listening
  val transcribing = state is AssistantState.Transcribing
  val focus = remember { FocusRequester() }
  val glow = rememberFluidListeningGlow(listening, transcribing, level = { micLevel.value.level })
  LaunchedEffect(stamp, textMode, listening) {
    if (textMode && !listening) runCatching { focus.requestFocus() }
  }
  val sendScope = rememberCoroutineScope()
  var preparing by remember { mutableStateOf(false) }
  var preparation by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
  fun submit() {
    // SessionController has no queue contract. Keep the draft while the current request runs.
    if (state.isBusy || (text.isBlank() && !hasAttachments)) return
    if (preparing || preparingVoice) return
    preparing = true
    val captured = text
    preparation = sendScope.launch { try { if (onAsk(captured) && currentText() == captured) onTextChange("") } finally { preparing = false; preparation = null } }
  }
  AssistantComposer(
    value = text, onValueChange = onTextChange, backdrop = backdrop,
    action = if (preparing || preparingVoice) FluidComposerAction.Stop else fluidComposerAction(hasInput = text.isNotBlank() || hasAttachments,
      busy = state.isBusy, listening = listening || transcribing, speaking = speaking)
      .let { if (it == FluidComposerAction.StopAndQueue) FluidComposerAction.Stop else it },
    preparing = preparing || preparingVoice,
    onSend = ::submit, onStop = { if (preparing) preparation?.cancel() else onStop() }, onVoice = onVoice,
    onStopListening = { if (transcribing) onStop() else onStopVoice() }, onMute = onStopSpeaking,
    strings = AriaChatStrings, focusRequester = focus, maxLines = 4,
    // Session host owns microphone permission before it starts the voice session.
    requestMicPermission = false,
    chips = { modelControl?.invoke() },
    leading = { controlBackdrop ->
      FluidGlassRoundButton(Icons.Rounded.Screenshot, "Allega lo schermo", controlBackdrop,
        modifier = Modifier.size(48.dp), onClick = onAttachScreen)
    },
    voiceLine = {
      FluidComposerVoiceLine(partial, transcribing, amplitude = { glow.clock.amplitude },
        onTap = onVoiceToText, modifier = Modifier.fillMaxWidth(), strings = AriaAssistantStrings)
    },
    glow = glow,
  )
}

internal const val OrbMillis = 0L
