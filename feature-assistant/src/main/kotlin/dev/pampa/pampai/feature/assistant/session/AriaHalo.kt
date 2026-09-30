package dev.pampa.pampai.feature.assistant.session

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ai.orchestrator.AssistantState
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.pampa.pampai.feature.assistant.halo.HaloColours
import dev.pampa.pampai.feature.assistant.halo.HaloFieldSpec
import dev.pampa.pampai.feature.assistant.halo.PresenceFloor
import dev.pampa.pampai.feature.assistant.halo.amplitudeTarget
import dev.pampa.pampai.feature.assistant.halo.drawHaloField
import dev.pampa.pampai.feature.assistant.halo.rememberHaloClock
import dev.pampa.pampai.feature.assistant.halo.tempo
import dev.pampa.pampai.feature.assistant.halo.haloMood as moodOfState

/** L'umore dell'alone vive in `halo/`; qui resta il nome, per l'overlay che lo usa senza import. */
typealias HaloMood = dev.pampa.pampai.feature.assistant.halo.HaloMood

/** L'umore dell'alone dallo stato dell'assistente (delega a `halo/`). */
fun AssistantState.haloMood(): HaloMood = moodOfState()

/**
 * L'alone lungo tutto il perimetro dello schermo, nella sessione sopra un'altra app.
 *
 * Terza versione. La prima erano sei macchie da duecentoquaranta dp che viaggiavano sul bordo:
 * andava bene come idea, ma era invasiva (la luce entrava fino a meta' schermo) e con cosi' poche
 * macchie si contavano. La seconda era una banda continua di contorni concentrici: uniforme, ma
 * senza moto di posizione, "solo un leggero ai bordi". Questa e' la prima, corretta: quattordici
 * macchie da sessanta dp, equispaziate, che scorrono lungo il bordo e non entrano oltre novanta dp.
 *
 * Il pittore ([drawHaloField]) e l'orologio ([rememberHaloClock]) sono quelli di `halo/`, gli
 * stessi della capsula del composer e dell'aurora della chat. Qui non si passa da `HaloField`
 * perche' quello prende il livello del microfono come numero, cioe' letto in composizione: a
 * cinquanta hertz, cinquanta ricomposizioni al secondo del campo di luce. [level] e' una lambda, e
 * la legge solo il loop di frame dell'orologio. Quando `HaloField` accettera' una lambda, questo
 * torna un wrapper.
 */
@Composable
fun AriaHalo(
  mood: HaloMood,
  level: () -> Float,
  colours: HaloColours,
  modifier: Modifier = Modifier,
  cornerRadius: Dp = 44.dp,
  thickness: Dp = 120.dp,
) {
  val spec = remember(cornerRadius, thickness) {
    HaloFieldSpec(blobCount = 14, blobRadius = thickness / 2, peakAlpha = 0.45f, cornerRadius = cornerRadius, blendMode = BlendMode.Plus)
  }
  // Nella sessione l'alone dice "Aria e' qui": con il moto ridotto resta, fermo, invece di sparire.
  val shown = mood != HaloMood.HIDDEN
  val presence = remember { Animatable(0f) }
  LaunchedEffect(shown) {
    presence.animateTo(if (shown) 1f else 0f, if (shown) FluidMotion.fadeIn(PresenceFadeInMs) else FluidMotion.fadeOut(PresenceFadeOutMs))
  }
  val clock = rememberHaloClock(running = shown, speed = mood.tempo(), target = { mood.amplitudeTarget(level()) })
  val cycle = remember(colours, mood) { colours.cycle(mood) }
  // Sei `State<Color>`: si leggono nel draw, cosi' il cambio d'umore anima i colori senza ricomporre.
  val animated = cycle.mapIndexed { index, colour -> animateColorAsState(colour, FluidMotion.color(), label = "haloColour$index") }
  val frameColours = remember(cycle.size) { MutableList(cycle.size) { Color.Transparent } }
  val geometry = remember { EdgeGeometry() }
  val settled by remember { derivedStateOf { presence.value <= PresenceFloor } }
  if (!shown && settled) return
  Spacer(
    modifier.fillMaxSize().drawBehind {
      geometry.ensure(size, spec.cornerRadius.toPx())
      for (i in frameColours.indices) frameColours[i] = animated[i].value
      drawHaloField(geometry.measure, geometry.path, clock.time, clock.amplitude, presence.value, frameColours, spec)
    },
  )
}

/**
 * Il bordo dello schermo misurato, rifatto solo quando cambia la misura: `Path` e `PathMeasure`
 * costano, e nel draw a sessanta hertz non si allocano.
 */
private class EdgeGeometry {
  private var size: Size = Size.Unspecified
  private var corner = -1f
  val path: Path = Path()
  val measure: PathMeasure = PathMeasure()

  fun ensure(newSize: Size, newCorner: Float) {
    if (newSize == size && newCorner == corner) return
    size = newSize
    corner = newCorner
    path.reset()
    path.addRoundRect(RoundRect(Rect(0f, 0f, newSize.width, newSize.height), CornerRadius(newCorner, newCorner)))
    measure.setPath(path, forceClosed = true)
  }
}

/** Le dissolvenze della presenza, le stesse di `HaloField`. */
private const val PresenceFadeInMs = 400
private const val PresenceFadeOutMs = 600
