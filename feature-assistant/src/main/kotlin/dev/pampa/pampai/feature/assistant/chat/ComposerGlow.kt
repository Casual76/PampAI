package dev.pampa.pampai.feature.assistant.chat

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.pampa.pampai.feature.assistant.halo.HaloClock
import dev.pampa.pampai.feature.assistant.halo.HaloColours
import dev.pampa.pampai.feature.assistant.halo.HaloFieldSpec
import dev.pampa.pampai.feature.assistant.halo.HaloMood
import dev.pampa.pampai.feature.assistant.halo.PresenceFloor
import dev.pampa.pampai.feature.assistant.halo.drawHaloField
import dev.pampa.pampai.feature.assistant.halo.sampleCycle
import dev.pampa.pampai.feature.assistant.halo.wrap01

/**
 * La forma del campo di luce attorno alla capsula: sei macchie da ventotto dp. Sono poche e
 * piccole rispetto all'alone della sessione (quattordici da sessanta) perche' qui il perimetro e'
 * quello di una capsula larga un telefono e alta due righe, non quello di uno schermo: piu'
 * macchie si toccherebbero e farebbero una cornice piena; piu' grandi sbordarebbero sulla chat.
 * Niente filo sul bordo dal pittore: l'anello lo disegna [composerListeningGlow] sopra il vetro,
 * con i colori che girano. Il blend lo mette chi applica il modificatore ([haloBlendForTheme]).
 */
internal val GlowSpec = HaloFieldSpec(blobCount = 6, blobRadius = 28.dp, peakAlpha = 0.40f, edgeStroke = 0.dp)

/** Lo spessore dell'anello di luce sul bordo della capsula. */
private val RingWidth = 1.5.dp

/**
 * Quanti giri al secondo (di tempo dell'orologio) fanno i colori dell'anello: uno ogni otto
 * secondi. Piu' lento delle macchie no, perche' l'anello e' sottile e senza moto sembra un bordo
 * stampato; molto piu' veloce no, perche' diventa una giostra.
 */
private const val RingCyclesPerSec = 0.125f

/** Quante fermate ha il gradiente dell'anello: dodici bastano a non vedere gli spigoli fra un colore e l'altro. */
private const val RingStops = 12

/** L'alpha dell'anello: da un quarto a sette decimi con l'ampiezza della voce, per la presenza. */
private const val RingFloorAlpha = 0.25f
private const val RingVoiceAlpha = 0.45f

/**
 * L'alone della capsula mentre ascolta: le macchie del campo di luce di Aria viaggiano sul
 * perimetro della capsula **dietro** al contenuto, e un anello sottile con i colori del tema che
 * girano sta **sopra** il bordo.
 *
 * Si applica a un contenitore *esterno* al pannello di vetro, che non clippa: dentro la capsula
 * le macchie le copre il vetro (il fondale rifratto e il suo film), fuori sbordano di una
 * ventina di dp e diventano l'alone; l'anello, disegnato dopo `drawContent`, corre sul filo del
 * bordo. Non si scala e non si sposta il vetro (scalare un layer di vetro scala anche l'immagine
 * campionata): la capsula resta ferma, e' la luce a muoversi.
 *
 * `Path` e `PathMeasure` si rifanno solo quando cambia la misura (`drawWithCache`); tempo,
 * ampiezza e presenza si leggono dentro il draw, cosi' a sessanta fotogrammi al secondo si
 * invalida solo questo disegno e nessuno ricompone. Sotto [PresenceFloor] non si disegna niente:
 * a presenza zero un `Plus` costa e non si vede.
 *
 * @param presence quanto l'alone c'e', da 0 a 1: la dissolvenza in entrata e in uscita, letta nel draw.
 * @param blend [BlendMode.Plus] su una pagina scura, [BlendMode.SrcOver] su una chiara.
 */
internal fun Modifier.composerListeningGlow(
  clock: HaloClock,
  presence: () -> Float,
  colours: HaloColours,
  shape: Shape = ComposerShape,
  blend: BlendMode,
): Modifier = drawWithCache {
  val outline = shape.createOutline(size, layoutDirection, this)
  val path = Path().apply { addOutline(outline) }
  val measure = PathMeasure().apply { setPath(path, forceClosed = true) }
  val spec = GlowSpec.copy(blendMode = blend)
  val blobs = colours.cycle(HaloMood.LISTENING)
  val ring = listOf(colours.accent, colours.secondary, colours.tertiary)
  // Le fermate dell'anello: una lista riempita a ogni fotogramma, non riallocata.
  val stops = MutableList(RingStops + 1) { Color.Transparent }
  val center = Offset(size.width / 2f, size.height / 2f)
  val stroke = Stroke(width = RingWidth.toPx())
  onDrawWithContent {
    val p = presence()
    val lit = p > PresenceFloor
    if (lit) drawHaloField(measure, path, clock.time, clock.amplitude, p, blobs, spec)
    drawContent()
    if (lit) {
      // I colori girano sull'anello: fermate fisse, colore campionato dal ciclo con la fase che
      // avanza col tempo (la prima e l'ultima coincidono, cosi' il giro si chiude senza cucitura).
      val phase = wrap01(clock.time * RingCyclesPerSec)
      for (k in 0..RingStops) stops[k] = sampleCycle(ring, k / RingStops.toFloat() - phase)
      drawPath(
        path = path,
        brush = Brush.sweepGradient(stops, center = center),
        alpha = (RingFloorAlpha + RingVoiceAlpha * clock.amplitude) * p,
        style = stroke,
        blendMode = blend,
      )
    }
  }
}
