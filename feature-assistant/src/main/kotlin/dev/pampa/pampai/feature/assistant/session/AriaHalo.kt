package dev.pampa.pampai.feature.assistant.session

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ai.orchestrator.AssistantState
import dev.pampa.pampai.feature.assistant.halo.HaloColours
import dev.pampa.pampai.feature.assistant.halo.HaloField
import dev.pampa.pampai.feature.assistant.halo.HaloFieldSpec
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
 * Il pittore e l'orologio stanno in [HaloField]: li usano anche la capsula del composer e l'aurora
 * della chat. Qui solo la traduzione dei vecchi parametri (`thickness` era quanto la luce entrava:
 * ora e' il diametro di una macchia).
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
) = HaloField(
  mood = mood,
  level = level,
  modifier = modifier,
  spec = HaloFieldSpec(blobCount = 14, blobRadius = thickness / 2, peakAlpha = 0.45f, cornerRadius = cornerRadius, blendMode = BlendMode.Plus),
  colours = HaloColours(accent, secondary, tertiary, error),
)
