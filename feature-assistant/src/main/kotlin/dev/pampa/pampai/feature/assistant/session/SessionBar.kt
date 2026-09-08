package dev.pampa.pampai.feature.assistant.session

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ai.orchestrator.AssistantState
import dev.antigravity.fluidengine.ai.orchestrator.MicLevel
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassRole
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.antigravity.fluidengine.ui.fluidphysics.FluidForm
import dev.antigravity.fluidengine.ui.fluidphysics.FluidFormPresets
import dev.antigravity.fluidengine.ui.fluidphysics.FluidPhysicsTier
import dev.antigravity.fluidengine.ui.fluidphysics.fluidPhysicsSurface
import dev.antigravity.fluidengine.ui.fluidphysics.rememberFluidPhysicsState
import dev.pampa.pampai.feature.assistant.chat.ComposerField
import dev.pampa.pampai.feature.assistant.chat.ComposerVoiceLine
import dev.pampa.pampai.feature.assistant.chat.GlassRound
import dev.pampa.pampai.feature.assistant.chat.composerListeningGlow
import dev.pampa.pampai.feature.assistant.halo.HaloColours
import dev.pampa.pampai.feature.assistant.halo.haloBlendForTheme
import dev.pampa.pampai.feature.assistant.halo.rememberHaloClock
import kotlinx.coroutines.flow.StateFlow

/**
 * La barra della sessione: l'orb di vetro che diventa la capsula, e resta l'unica cosa a cui si
 * parla. E' il primo nodo Fluid-physics dell'overlay — un cerchio da [OrbRadius] che in
 * [OrbMillis] millisecondi morfa nella capsula alta [CapsuleHeight]. La card nasce dopo, da un
 * seme nascosto sotto questa capsula.
 *
 * In ascolto la barra si accende come il composer della chat: le macchie del campo di luce di Aria
 * viaggiano **dietro** al vetro e sbordano attorno, e un anello sottile coi colori del tema gira
 * sul bordo. Il modificatore sta **prima** del vetro nella catena, quindi le macchie si disegnano
 * sotto la superficie e l'anello sopra; la sagoma che gli si passa e' quella della capsula
 * ([BarGlowShape]), non il riquadro alto [BarHeight] che la contiene, altrimenti l'anello
 * correrebbe sedici dp fuori dal bordo vero.
 *
 * Il livello del microfono lo legge l'orologio dell'alone dentro il loop di frame: lo `StateFlow`
 * a cinquanta hertz non ricompone niente.
 *
 * @param orb vero durante l'entrata; lo tiene l'overlay, perche' anche la card deve sapere quando
 *   la capsula si e' posata.
 * @param hasAttachments cambia solo il tasto di destra: con un allegato in attesa si invia anche
 *   senza aver scritto niente.
 */
@Composable
internal fun SessionBar(
  state: AssistantState,
  textMode: Boolean,
  partial: String?,
  speaking: Boolean,
  hasAttachments: Boolean,
  orb: Boolean,
  backdrop: GlassBackdropState,
  micLevel: StateFlow<MicLevel>,
  onAsk: (String) -> Unit,
  onVoice: () -> Unit,
  onStopVoice: () -> Unit,
  onVoiceToText: () -> Unit,
  onStop: () -> Unit,
  onStopSpeaking: () -> Unit,
  onAttachScreen: () -> Unit,
) {
  var text by remember { mutableStateOf("") }
  val listening = state is AssistantState.Listening
  val transcribing = state == AssistantState.Transcribing
  val busy = state.isBusy
  val reducedMotion = LocalFluidMotionPolicy.current.reducedMotion
  val voiceActive = (listening || transcribing) && !orb

  // L'alone: l'orologio corre solo mentre si ascolta davvero (durante l'orb non c'e' ancora una
  // capsula su cui girare), la presenza sfuma dentro e fuori.
  val clock = rememberHaloClock(
    running = listening && !orb && !reducedMotion,
    speed = 1f,
    target = { GlowFloorAmplitude + (1f - GlowFloorAmplitude) * micLevel.value.level.coerceIn(0f, 1f) },
  )
  val glow = remember { Animatable(0f) }
  LaunchedEffect(voiceActive) {
    if (voiceActive) glow.animateTo(1f, FluidMotion.fadeIn(GlowFadeInMillis))
    else glow.animateTo(0f, FluidMotion.fadeOut(GlowFadeOutMillis))
  }
  val glowColours = HaloColours.fromTheme()
  val glowBlend = haloBlendForTheme()

  // Il fuoco nel campo quando la barra e' tornata testo: dopo il silenzio iniziale, o per scelta.
  // Non durante l'orb (la tastiera aprirebbe mentre la forma viaggia) e non mentre ascolta.
  val focus = remember { FocusRequester() }
  LaunchedEffect(textMode, orb, listening) {
    if (textMode && !orb && !listening) runCatching { focus.requestFocus() }
  }

  fun submit() {
    if (text.isBlank() && !hasAttachments) return
    onAsk(text)
    text = ""
  }

  BoxWithConstraints(Modifier.fillMaxWidth().height(BarHeight)) {
    val density = LocalDensity.current
    val width = constraints.maxWidth.toFloat()
    val height = with(density) { BarHeight.toPx() }
    val orbRadius = with(density) { OrbRadius.toPx() }
    val capsuleHeight = with(density) { CapsuleHeight.toPx() }
    val orbForm = remember(width, height) { FluidForm.circle(Offset(width / 2f, height / 2f), orbRadius) }
    val capsuleForm = remember(width, height) {
      FluidFormPresets.capsule(Rect(0f, (height - capsuleHeight) / 2f, width, (height + capsuleHeight) / 2f))
    }
    val physics = rememberFluidPhysicsState(if (orb) orbForm else capsuleForm)
    LaunchedEffect(orb, width) { physics.morphTo(if (orb) orbForm else capsuleForm) }
    Box(
      Modifier
        .fillMaxSize()
        .composerListeningGlow(
          clock = clock,
          presence = { glow.value },
          colours = glowColours,
          shape = BarGlowShape,
          blend = glowBlend,
        )
        .fluidPhysicsSurface(
          state = physics,
          backdrop = backdrop,
          tint = GlassDefaults.modalTint(),
          role = GlassRole.Floating,
          tier = FluidPhysicsTier.Balanced,
        ),
    ) {
      if (orb) {
        Icon(
          imageVector = Icons.Rounded.Mic,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.align(Alignment.Center).size(26.dp),
        )
      } else {
        Row(
          modifier = Modifier.align(Alignment.Center).fillMaxWidth().height(CapsuleHeight).padding(horizontal = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          GlassRound(Icons.Rounded.Screenshot, "Allega lo schermo", backdrop, onClick = onAttachScreen)
          if (listening || transcribing) {
            ComposerVoiceLine(
              partial = partial,
              transcribing = transcribing,
              amplitude = { clock.amplitude },
              onTap = onVoiceToText,
              modifier = Modifier.weight(1f),
            )
          } else {
            ComposerField(
              value = text,
              onValueChange = { text = it },
              placeholder = if (busy) "Sto rispondendo…" else "Chiedi ad Aria…",
              enabled = !busy,
              onSend = { submit() },
              maxLines = 1,
              modifier = Modifier.weight(1f).focusRequester(focus),
            )
          }
          Spacer(Modifier.width(4.dp))
          when {
            listening -> GlassRound(Icons.Rounded.Stop, "Smetti di ascoltare", backdrop, tint = MaterialTheme.colorScheme.error, onClick = onStopVoice)
            busy -> GlassRound(Icons.Rounded.Stop, "Ferma", backdrop, onClick = onStop)
            speaking && text.isBlank() -> GlassRound(Icons.Rounded.VolumeOff, "Zitta", backdrop, onClick = onStopSpeaking)
            text.isBlank() && !hasAttachments -> GlassRound(Icons.Rounded.Mic, "Parla", backdrop, tint = MaterialTheme.colorScheme.primary, onClick = onVoice)
            else -> GlassRound(Icons.Rounded.ArrowUpward, "Invia", backdrop, tint = MaterialTheme.colorScheme.primary, onClick = { submit() })
          }
        }
      }
    }
  }
}

/**
 * La sagoma su cui gira l'alone: la capsula vera dentro il riquadro della barra.
 *
 * Il nodo e' alto [BarHeight] perche' l'orb, che ha diametro maggiore della capsula, ci deve
 * stare; la capsula ne occupa [CapsuleHeight] al centro. Passare al pittore il rettangolo del
 * nodo disegnerebbe l'anello su un bordo che non esiste.
 */
private val BarGlowShape = object : Shape {
  override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
    val inset = ((size.height - with(density) { CapsuleHeight.toPx() }) / 2f).coerceAtLeast(0f)
    val radius = (size.height - 2f * inset) / 2f
    return Outline.Rounded(
      RoundRect(Rect(0f, inset, size.width, size.height - inset), CornerRadius(radius, radius)),
    )
  }
}

/** Il riquadro della barra: alto quanto serve all'orb, che e' piu' grosso della capsula. */
private val BarHeight = 72.dp

/** L'orb dell'entrata e la capsula in cui si posa. */
private val OrbRadius = 34.dp
private val CapsuleHeight = 56.dp

/** Quanto dura l'entrata orb → capsula: la card aspetta questo prima di uscire dal suo seme. */
internal const val OrbMillis = 320L

/** L'ampiezza dell'alone nel silenzio: fra due sillabe non si spegne. Con la voce va a uno. */
private const val GlowFloorAmplitude = 0.35f

/** Quanto ci mette la luce a comparire e a posarsi. */
private const val GlowFadeInMillis = 240
private const val GlowFadeOutMillis = 200
