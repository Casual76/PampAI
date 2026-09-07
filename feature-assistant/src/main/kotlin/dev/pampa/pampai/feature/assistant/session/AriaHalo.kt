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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ai.orchestrator.AssistantState
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import kotlin.math.pow

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
 * L'alone lungo tutto il perimetro dello schermo.
 *
 * Una **banda continua**, non delle macchie che girano. La prima versione erano sei cerchi che
 * viaggiavano sul bordo con `PathMeasure`: si contavano a occhio, e un alone di cui conti i pezzi
 * non e' un alone, e' una processione. Qui il colore lo da' un gradiente a ventaglio centrato sullo
 * schermo — cosi' cambia con continuita' girando intorno, e ruota piano — e la forma la danno tanti
 * contorni concentrici dello stesso rettangolo arrotondato, sempre piu' larghi e sempre piu'
 * deboli: sommati danno una luce che sta attaccata al bordo e si spegne verso il centro, uguale su
 * tutti e quattro i lati.
 *
 * Resta reattivo: in ascolto l'intensita' segue il microfono con attacco veloce e rilascio lento, al
 * lavoro respira, mentre scrive gira piu' svelto. Solo gradienti in somma di luce (`BlendMode.Plus`):
 * niente shader, gira uguale sui device deboli.
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
  /** Quanto la luce entra verso il centro prima di spegnersi. */
  thickness: Dp = 150.dp,
) {
  val reducedMotion = LocalFluidMotionPolicy.current.reducedMotion
  val visible = mood != HaloMood.HIDDEN
  val presence by animateFloatAsState(
    targetValue = if (visible) 1f else 0f,
    animationSpec = spring(dampingRatio = FluidMotion.DampingStandard, stiffness = FluidMotion.ResponseSmooth),
    label = "haloPresence",
  )
  // Numeri bassi apposta: l'alone accompagna, non annuncia. Sotto c'e' l'app dell'utente, e deve
  // restare leggibile anche mentre Aria lavora.
  val target = when (mood) {
    HaloMood.LISTENING -> 0.34f + level.coerceIn(0f, 1f) * 0.46f
    HaloMood.IDLE -> 0.30f
    HaloMood.DONE -> 0.42f
    HaloMood.ERROR -> 0.62f
    else -> 0.50f
  }
  val amplitude = remember { Animatable(0.30f) }
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
      HaloMood.WRITING -> 0.30f
      HaloMood.LISTENING -> 0.20f
      HaloMood.WORKING -> 0.13f
      HaloMood.IDLE -> 0.05f
      else -> 0.07f
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
      HaloMood.ERROR -> listOf(error, error, accent, error, error, accent)
      HaloMood.DONE -> listOf(accent, secondary, accent, tertiary, accent, secondary)
      else -> listOf(accent, secondary, tertiary, accent, secondary, tertiary)
    }
  }
  val colours = targetColours.mapIndexed { index, colour ->
    animateColorAsState(targetValue = colour, animationSpec = FluidMotion.smooth(), label = "haloColour$index").value
  }
  val intensity = amplitude.value * presence
  Canvas(modifier.fillMaxSize()) {
    val corner = cornerRadius.toPx()
    val rect = Rect(0f, 0f, size.width, size.height)
    val path = Path().apply { addRoundRect(RoundRect(rect, CornerRadius(corner, corner))) }
    drawBand(path, rect, colours, time, intensity, thickness.toPx())
  }
}

/**
 * La banda: contorni concentrici sommati.
 *
 * Ogni contorno e' centrato sul bordo, quindi meta' esce dallo schermo e viene tagliata: quello che
 * resta e' luce che nasce sul bordo e sfuma verso l'interno. I piu' larghi sono i piu' deboli, cosi'
 * la somma scende con continuita' invece di finire di colpo.
 */
private fun DrawScope.drawBand(path: Path, rect: Rect, colours: List<Color>, time: Float, intensity: Float, thickness: Float) {
  if (intensity <= 0.001f || colours.isEmpty()) return
  val stops = Array(SweepSteps + 1) { step ->
    val t = step / SweepSteps.toFloat()
    t to sampleCycle(colours, t + time)
  }
  val brush = Brush.sweepGradient(colorStops = stops, center = rect.center)
  for (layer in 0 until Layers) {
    val f = layer / (Layers - 1f)
    val width = 3.dp.toPx() + f * thickness * 2f
    val alpha = intensity * LayerAlpha * (1f - f).pow(1.5f)
    if (alpha <= 0.002f) continue
    drawPath(path = path, brush = brush, alpha = alpha, style = Stroke(width = width), blendMode = BlendMode.Plus)
  }
}

/** Il colore del ciclo a `t` (che gira: 1.2 e' come 0.2), interpolando fra un colore e il successivo. */
private fun sampleCycle(colours: List<Color>, t: Float): Color {
  val n = colours.size
  if (n == 1) return colours[0]
  val position = ((t % 1f) + 1f) % 1f * n
  val index = position.toInt() % n
  return lerp(colours[index], colours[(index + 1) % n], position - position.toInt())
}

/** Quanti campioni del ventaglio: pochi si vedono a bande, tanti costano e non si distinguono. */
private const val SweepSteps = 24

/** Quanti contorni: sotto i dieci si contano, sopra i venti non cambia niente. */
private const val Layers = 16

/** Quanto pesa il contorno piu' esterno. Sommato sugli altri fa il massimo dell'alone. */
private const val LayerAlpha = 0.055f

/** L'attacco: la voce sale subito, come su un indicatore di livello. */
private const val AttackStiffness = 1_400f

/** Il rilascio: scende piano, cosi' fra due sillabe l'alone non si spegne. */
private const val ReleaseStiffness = 180f
