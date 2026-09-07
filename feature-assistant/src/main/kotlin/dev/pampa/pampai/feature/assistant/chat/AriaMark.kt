package dev.pampa.pampai.feature.assistant.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Il marchio di Aria, disegnato: la scintilla a quattro punte e la piccola accanto.
 *
 * Lo stesso glifo dell'icona dell'app e del tile, con il gradiente del marchio. Sta in un Canvas
 * e non in una risorsa perche' cosi' prende i colori del tema del momento (dinamico, preset,
 * scuro) invece di quelli fissati in un XML, e perche' un vettore in un modulo senza `res/` e' una
 * cartella in piu' per un file solo.
 */
@Composable
fun AriaMark(size: Dp = 72.dp, modifier: Modifier = Modifier) {
  val a = MaterialTheme.colorScheme.primary
  val b = MaterialTheme.colorScheme.tertiary
  Canvas(modifier.size(size)) {
    val k = this.size.minDimension / 108f
    val glow = Brush.radialGradient(listOf(a.copy(alpha = 0.22f), Color.Transparent), center = Offset(54f * k, 52f * k), radius = 44f * k)
    drawCircle(brush = glow, radius = 44f * k, center = Offset(54f * k, 52f * k))
    val fill = Brush.linearGradient(listOf(a, b), start = Offset(26f * k, 26f * k), end = Offset(82f * k, 72f * k))
    drawPath(spark(k, cx = 52f, cy = 49f, r = 23f), brush = fill)
    drawPath(spark(k, cx = 74f, cy = 36f, r = 8f), brush = fill, alpha = 0.95f)
  }
}

/** Una scintilla a quattro punte coi fianchi concavi, centrata in ([cx],[cy]) con raggio [r], in coordinate 108×108 scalate di [k]. */
private fun spark(k: Float, cx: Float, cy: Float, r: Float): Path {
  // I fianchi si tirano verso il centro: il punto di controllo sta a un quarto del raggio.
  val c = r * 0.28f
  return Path().apply {
    moveTo((cx) * k, (cy - r) * k)
    cubicTo((cx + c * 0.25f) * k, (cy - c) * k, (cx + c) * k, (cy - c * 0.25f) * k, (cx + r) * k, cy * k)
    cubicTo((cx + c) * k, (cy + c * 0.25f) * k, (cx + c * 0.25f) * k, (cy + c) * k, cx * k, (cy + r) * k)
    cubicTo((cx - c * 0.25f) * k, (cy + c) * k, (cx - c) * k, (cy + c * 0.25f) * k, (cx - r) * k, cy * k)
    cubicTo((cx - c) * k, (cy - c * 0.25f) * k, (cx - c * 0.25f) * k, (cy - c) * k, cx * k, (cy - r) * k)
    close()
  }
}
