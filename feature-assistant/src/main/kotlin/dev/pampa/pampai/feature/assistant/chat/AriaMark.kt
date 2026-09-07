package dev.pampa.pampai.feature.assistant.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Il marchio di Aria, disegnato: una "a" minuscola a un piano, scritta in un tratto, la cui gamba
 * non si ferma ma scivola via in un soffio.
 *
 * E' una lettera, quindi e' sua e di nessun altro assistente -- la scintilla a quattro punte era
 * di tutti; ed e' aria, perche' la coda e' quello che fa. Le stesse tre curve dell'icona
 * (`ic_launcher_foreground.xml`) e del tile, qui col gradiente del tema del momento: e' per questo
 * che sta in un Canvas e non in una risorsa.
 */
@Composable
fun AriaMark(size: Dp = 72.dp, modifier: Modifier = Modifier) {
  val a = MaterialTheme.colorScheme.primary
  val b = MaterialTheme.colorScheme.tertiary
  Canvas(modifier.size(size)) {
    val k = this.size.minDimension / 108f
    val glow = Brush.radialGradient(listOf(a.copy(alpha = 0.20f), Color.Transparent), center = Offset(60f * k, 58f * k), radius = 46f * k)
    drawCircle(brush = glow, radius = 46f * k, center = Offset(60f * k, 58f * k))
    val ink = Brush.linearGradient(listOf(a, b), start = Offset(29f * k, 42f * k), end = Offset(90f * k, 80f * k))
    val stroke = Stroke(width = 9f * k, cap = StrokeCap.Round, join = StrokeJoin.Round)
    // L'anello: centro (46,59), raggio 17, aperto in alto a destra dove arriva la gamba.
    val ring = Path().apply {
      arcTo(Rect(Offset(46f * k, 59f * k), 17f * k), startAngleDegrees = -30f, sweepAngleDegrees = 320f, forceMoveTo = true)
    }
    drawPath(ring, brush = ink, style = stroke)
    // La gamba, e la coda che se ne va.
    val leg = Path().apply {
      moveTo(66f * k, 42f * k)
      lineTo(66f * k, 70f * k)
      cubicTo(66f * k, 80f * k, 78f * k, 80f * k, 82f * k, 72f * k)
      cubicTo(85f * k, 67f * k, 88f * k, 63f * k, 90f * k, 60f * k)
    }
    drawPath(leg, brush = ink, style = stroke)
  }
}
