package dev.pampa.pampai.feature.assistant.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ai.orchestrator.MicLevel
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/**
 * La barra mentre si ascolta: ventiquattro barrette che scorrono con il livello del microfono
 * (l'ultima a destra e' l'istante presente) e, sopra, le parole riconosciute finche' si parla. Si
 * illumina del colore primario quando c'e' parlato, resta spenta nel silenzio. Il livello si legge
 * qui dentro, cinquanta volte al secondo, senza ricomporre il resto della barra. Un tocco la
 * riporta al testo.
 */
@Composable
fun VoiceVisualizer(
  micLevel: StateFlow<MicLevel>,
  partial: String?,
  transcribing: Boolean,
  onTap: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val samples = remember { FloatArray(BARS) }
  val voiced = remember { BooleanArray(BARS) }
  var tick by remember { mutableIntStateOf(0) }
  LaunchedEffect(transcribing) {
    if (transcribing) return@LaunchedEffect
    while (true) {
      val level = micLevel.value
      System.arraycopy(samples, 1, samples, 0, BARS - 1)
      System.arraycopy(voiced, 1, voiced, 0, BARS - 1)
      samples[BARS - 1] = level.level.coerceIn(0f, 1f)
      voiced[BARS - 1] = level.speaking
      tick++
      delay(50)
    }
  }
  val lit = MaterialTheme.colorScheme.primary
  val idle = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
  val caption = when {
    transcribing -> "Trascrivo…"
    !partial.isNullOrBlank() -> if (partial.length > 70) "…" + partial.takeLast(70) else partial
    else -> null
  }
  Column(modifier = modifier.fluidPressable(onClick = onTap, role = Role.Button).padding(horizontal = 8.dp)) {
    if (caption != null) {
      Text(
        text = caption,
        style = MaterialTheme.typography.bodySmall,
        color = if (transcribing) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
      )
    }
    Canvas(Modifier.fillMaxWidth().height(if (caption != null) 18.dp else 40.dp)) {
      @Suppress("UNUSED_VARIABLE") val frame = tick
      val gap = size.width / (BARS * 1.6f)
      val barWidth = gap * 0.6f
      val centerY = size.height / 2f
      val minHeight = size.height * 0.12f
      for (index in 0 until BARS) {
        val level = samples[index]
        val h = (minHeight + (size.height - minHeight) * level * (if (transcribing) 0.5f else 1f)).coerceAtLeast(minHeight)
        val x = index * gap * 1.6f + (gap * 1.6f - barWidth) / 2f
        drawRoundRect(
          color = if (voiced[index]) lit.copy(alpha = 0.55f + 0.45f * level) else idle,
          topLeft = Offset(x, centerY - h / 2f),
          size = Size(barWidth, h),
          cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
        )
      }
    }
  }
}

private const val BARS = 24
