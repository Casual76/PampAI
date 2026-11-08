package dev.pampa.pampai.feature.assistant.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.Text
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.aiui.chat.*
import dev.antigravity.fluidengine.aiui.strings.FluidChatStrings
import dev.antigravity.fluidengine.ui.fluid.*

/** Shared app/session layout using Fluid fields, glass and actions.
 * The model occupies only the remaining toolbar width; action targets stay 48 dp.
 * Keep permissions and action selection identical to the engine composer contract.
 */
@Composable
internal fun AssistantComposer(
  value: String,
  onValueChange: (String) -> Unit,
  backdrop: GlassBackdropState,
  action: FluidComposerAction,
  onSend: () -> Unit,
  modifier: Modifier = Modifier,
  placeholder: String = FluidChatStrings().composerPlaceholder,
  strings: FluidChatStrings = FluidChatStrings(),
  onStop: () -> Unit = {},
  onVoice: () -> Unit = {},
  onStopListening: () -> Unit = {},
  onMute: () -> Unit = {},
  pills: (@Composable () -> Unit)? = null,
  leading: (@Composable RowScope.(controlBackdrop: GlassBackdropState) -> Unit)? = null,
  chips: (@Composable RowScope.() -> Unit)? = null,
  voiceLine: (@Composable () -> Unit)? = null,
  glow: FluidListeningGlow? = null,
  focusRequester: FocusRequester? = null,
  maxLines: Int = 4,
  requestMicPermission: Boolean = true,
  onBoundsChange: ((Rect) -> Unit)? = null,
  resampleIntervalMillis: Long = 0L,
  shape: Shape = ComposerShape,
  voiceActive: Boolean = action == FluidComposerAction.StopListening,
  preparing: Boolean = false,
) {
  val context = LocalContext.current
  // Il materiale della capsula, pubblicato per i tasti che ci stanno sopra: una lente appoggiata su
  // vetro smerigliato mostra la smerigliatura, non la pagina sotto.
  val composerGlass = rememberGlassBackdrop()
  val controlBackdrop = rememberCombinedGlassBackdrop(backdrop, composerGlass)
  val micGranted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
  // System-session Compose hosts have no ActivityResultRegistryOwner. Only app hosts request here.
  val micLauncher = if (requestMicPermission) {
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) onVoice() }
  } else null
  val inputFocus = focusRequester ?: remember { FocusRequester() }

  Column(modifier) {
    if (preparing || pills != null) {
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(bottom = 8.dp, start = 4.dp),
      ) {
        if (preparing) Text("Preparo la richiesta…", style = MaterialTheme.typography.labelMedium,
          modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        pills?.invoke()
      }
    }
    // La scatola esterna porta l'alone: le macchie stanno dietro al vetro e sbordano attorno, l'anello
    // sopra il bordo. Non clippa, e non deve: la luce vive fuori dalla capsula.
    Box(if (glow != null && !LocalFluidMotionPolicy.current.reducedMotion) Modifier.fluidListeningGlow(glow, shape) else Modifier) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .onGloballyPositioned { onBoundsChange?.invoke(it.boundsInRoot()) }
          .glassSurface(
            state = backdrop,
            tint = GlassDefaults.floatingTint(),
            shape = shape,
            role = GlassRole.Floating,
            exports = composerGlass,
            resampleIntervalMillis = resampleIntervalMillis,
          )
          .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 8.dp),
      ) {
        if (voiceActive && voiceLine != null) {
          voiceLine?.invoke()
        } else {
          FluidComposerField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            enabled = true,
            onSend = onSend,
            maxLines = maxLines,
            modifier = Modifier.fillMaxWidth().focusRequester(inputFocus),
          )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
          leading?.invoke(this, controlBackdrop)
          if (leading != null) Spacer(Modifier.width(8.dp))
          Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) { chips?.invoke(this) }
          when (action) {
            FluidComposerAction.StopListening -> FluidGlassRoundButton(Icons.Rounded.Stop, strings.voiceStop, controlBackdrop, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error, onClick = onStopListening)
            FluidComposerAction.StopAndQueue -> {
              FluidGlassRoundButton(Icons.Rounded.Stop, strings.stop, controlBackdrop, modifier = Modifier.size(48.dp), onClick = onStop)
              Spacer(Modifier.width(8.dp))
              FluidGlassRoundButton(Icons.Rounded.ArrowUpward, strings.sendQueued, controlBackdrop, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary, onClick = onSend)
            }
            FluidComposerAction.Stop -> FluidGlassRoundButton(Icons.Rounded.Stop, strings.stop, controlBackdrop, modifier = Modifier.size(48.dp), onClick = onStop)
            FluidComposerAction.Mute -> FluidGlassRoundButton(Icons.AutoMirrored.Rounded.VolumeOff, strings.mute, controlBackdrop, modifier = Modifier.size(48.dp), onClick = onMute)
            FluidComposerAction.Voice -> FluidGlassRoundButton(
              Icons.Rounded.Mic,
              strings.voiceStart,
              controlBackdrop,
              modifier = Modifier.size(48.dp),
              onClick = { if (micGranted || !requestMicPermission) onVoice() else micLauncher?.launch(Manifest.permission.RECORD_AUDIO) },
            )
            FluidComposerAction.Send -> FluidGlassRoundButton(Icons.Rounded.ArrowUpward, strings.send, controlBackdrop, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary, onClick = onSend)
          }
        }
      }
    }
  }
}

