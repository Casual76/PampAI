package dev.pampa.pampai.feature.assistant.session

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ai.orchestrator.AssistantState
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import kotlin.math.PI
import kotlin.math.sin

/** Come si muove l'alone: tace (lento, basso), ascolta (segue la voce), lavora (pulsa), scrive (scorre), ha finito, ha sbagliato. */
enum class HaloMood { HIDDEN, IDLE, LISTENING, WORKING, WRITING, DONE, ERROR }

/** L'umore dell'alone dallo stato dell'assistente. */
fun AssistantState.haloMood(): HaloMood = when (this) {
  is AssistantState.Listening -> HaloMood.LISTENING
  AssistantState.Transcribing, is AssistantState.Classifying, is AssistantState.Working, is AssistantState.WaitingRateLimit, is AssistantState.SwitchingProvider, is AssistantState.AwaitingConfirmation -> HaloMood.WORKING
  is AssistantState.Answering -> HaloMood.WRITING
  is AssistantState.Done -> HaloMood.DONE
  is AssistantState.Failed -> HaloMood.ERROR
  else -> HaloMood.IDLE
}

/**
 * L'alone lungo tutto il perimetro dello schermo: sei macchie di colore che viaggiano sul bordo
 * (un rettangolo arrotondato misurato con `PathMeasure`) e sfumano verso il centro, piu' un filo
 * di luce sul bordo stesso. In ascolto l'ampiezza segue il microfono con attacco veloce e rilascio
 * lento; al lavoro pulsa; mentre scrive scorre. Solo gradienti, in somma di luce (`BlendMode.Plus`)
 * sopra lo scrim: niente shader, gira uguale sui device deboli. Evoluzione dell'aureola in cima di
 * ClasseViva, distesa su tutti e quattro i lati.
 */
@Composable
fun AriaHalo(
  mood: HaloMood,
  level: Float,
  accent: Color,
  secondary: Color,
  tertiary: Color,
  error: Color,
  modifier: Modifier = Modifier,
  cornerRadius: Dp = 44.dp,
  thickness: Dp = 120.dp,
) {
  val reducedMotion = LocalFluidMotionPolicy.current.reducedMotion
  val visible = mood != HaloMood.HIDDEN
  val presence by animateFloatAsState(
    targetValue = if (visible) 1f else 0f,
    animationSpec = spring(dampingRatio = FluidMotion.DampingStandard, stiffness = FluidMotion.ResponseSmooth),
    label = "haloPresence",
  )
  val target = when (mood) {
    HaloMood.LISTENING -> 0.30f + level.coerceIn(0f, 1f) * 0.70f
    HaloMood.IDLE -> 0.35f
    HaloMood.DONE -> 0.45f
    else -> 0.6f
  }
  val amplitude = remember { Animatable(0.35f) }
  LaunchedEffect(target, mood, reducedMotion) {
    if (reducedMotion) {
      amplitude.snapTo(target)
      return@LaunchedEffect
    }
    val rising = target > amplitude.value
    amplitude.animateTo(target, spring(dampingRatio = FluidMotion.DampingStandard, stiffness = if (rising) AttackStiffness else ReleaseStiffness))
  }
  var time by remember { mutableFloatStateOf(0f) }
  LaunchedEffect(visible, reducedMotion, mood) {
    if (!visible || reducedMotion) return@LaunchedEffect
    val speed = when (mood) {
      HaloMood.WRITING -> 1.5f
      HaloMood.LISTENING -> 1.0f
      HaloMood.WORKING -> 0.55f
      HaloMood.IDLE -> 0.25f
      else -> 0.3f
    }
    var last = 0L
    while (true) {
      withFrameNanos { now ->
        if (last != 0L) time += (now - last) / 1_000_000_000f * speed
        last = now
      }
    }
  }
  if (presence <= 0.001f) return
  val targetColours = remember(accent, secondary, tertiary, error, mood) {
    when (mood) {
      HaloMood.ERROR -> listOf(error, error.copy(alpha = 0.8f), accent.copy(alpha = 0.5f), error, error.copy(alpha = 0.7f), accent.copy(alpha = 0.5f))
      HaloMood.DONE -> listOf(accent, secondary.copy(alpha = 0.9f), accent.copy(alpha = 0.9f), tertiary.copy(alpha = 0.8f), accent, secondary.copy(alpha = 0.8f))
      else -> listOf(accent, secondary, tertiary, accent.copy(alpha = 0.85f), secondary.copy(alpha = 0.9f), tertiary.copy(alpha = 0.9f))
    }
  }
  val colours = targetColours.mapIndexed { index, colour ->
    animateColorAsState(targetValue = colour, animationSpec = FluidMotion.smooth(), label = "haloColour$index").value
  }
  val amplitudeValue = amplitude.value
  Canvas(modifier.fillMaxSize()) {
    val corner = cornerRadius.toPx()
    val path = Path().apply { addRoundRect(RoundRect(Rect(0f, 0f, size.width, size.height), CornerRadius(corner, corner))) }
    val measure = PathMeasure().apply { setPath(path, forceClosed = true) }
    drawBlobs(measure, time, amplitudeValue, presence, colours, thickness.toPx())
    // Il filo sul bordo: la luce che c'e' anche fra una macchia e l'altra.
    drawPath(
      path = path,
      brush = Brush.linearGradient(listOf(colours[0].copy(alpha = 0.35f * presence), colours[1].copy(alpha = 0.35f * presence))),
      style = Stroke(width = 3.dp.toPx()),
      blendMode = BlendMode.Plus,
    )
  }
}

private fun DrawScope.drawBlobs(measure: PathMeasure, time: Float, amplitude: Float, presence: Float, colours: List<Color>, thickness: Float) {
  val length = measure.length
  if (length <= 0f) return
  val blobs = colours.size
  for (i in 0 until blobs) {
    // Ogni macchia viaggia a una velocita' un po' diversa: due non si sovrappongono mai a lungo.
    val phase = time * (0.06f + i * 0.011f) + i.toFloat() / blobs
    val distance = ((phase % 1f) + 1f) % 1f * length
    val center = measure.getPosition(distance)
    val breath = 0.75f + 0.25f * sin(time * 1.3f + i * (PI.toFloat() * 2f / blobs))
    val radius = thickness * (0.9f + 1.1f * amplitude) * breath
    val alpha = (0.35f + 0.45f * amplitude) * presence
    drawCircle(
      brush = Brush.radialGradient(colors = listOf(colours[i].copy(alpha = alpha), colours[i].copy(alpha = 0f)), center = center, radius = radius),
      radius = radius,
      center = Offset(center.x, center.y),
      blendMode = BlendMode.Plus,
    )
  }
}

/** L'attacco: la voce sale subito, come su un indicatore di livello. */
private const val AttackStiffness = 1_400f

/** Il rilascio: scende piano, cosi' fra due sillabe l'alone non si spegne. */
private const val ReleaseStiffness = 180f
