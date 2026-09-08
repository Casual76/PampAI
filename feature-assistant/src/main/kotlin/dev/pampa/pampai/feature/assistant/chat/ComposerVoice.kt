package dev.pampa.pampai.feature.assistant.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import kotlinx.coroutines.flow.collectLatest

/**
 * L'altezza della riga in ascolto: la stessa del campo di testo a una riga (ventiquattro dp di
 * riga piu' dodici di padding sopra e sotto), cosi' la capsula non cambia misura quando si passa
 * dalla penna alla voce. A quarantaquattro, misurato sul telefono, il bordo saltava di quattro dp.
 */
internal val VoiceLineHeight = 48.dp

/** Quanto e' alta la dissolvenza in cima al transcript, dove le righe vecchie scorrono via. */
private val VoiceFadeHeight = 8.dp

/** Il punto: il disco al centro va da tre a cinque dp di raggio con la voce, l'alone attorno arriva a otto. */
private val DotRadiusMin = 3.dp
private val DotRadiusVoice = 2.dp
private val DotHaloRadius = 8.dp

/**
 * La riga della capsula mentre Aria ascolta: un punto che pulsa con la voce e, accanto, le parole
 * riconosciute che *arrivano*, non che si sostituiscono.
 *
 * Il riconoscitore riemette l'intera frase due-cinque volte al secondo; qui la frase intera va a
 * [RevealingText], che tiene ferme le parole gia' viste e sfuma dentro solo quelle nuove (e
 * riscrive in fretta la coda quando il riconoscitore cambia idea). Niente finestra degli ultimi
 * settanta caratteri: tagliare la testa cambierebbe l'identita' di tutte le parole a ogni
 * emissione, e tornerebbero gli scatti. Il transcript sta in una finestra alta una riga e mezza
 * che segue la fine: le righe vecchie scorrono su e svaniscono sotto una dissolvenza di otto dp.
 *
 * Le ventiquattro barrette del vecchio visualizzatore qui non ci sono (ed erano l'ultimo uso di
 * quel file, che se n'e' andato con il pannello della sessione): il livello lo mostra l'alone
 * attorno alla capsula ([composerListeningGlow]); il punto tiene l'affordance "sto registrando".
 * Un tocco sulla riga annulla l'ascolto, come prima.
 *
 * @param amplitude l'ampiezza smussata della voce (0-1), letta solo nel disegno del punto.
 */
@Composable
internal fun ComposerVoiceLine(
  partial: String?,
  transcribing: Boolean,
  amplitude: () -> Float,
  onTap: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val scheme = MaterialTheme.colorScheme
  val caption = transcribing || partial.isNullOrBlank()
  val text = when {
    transcribing -> "Trascrivo…"
    partial.isNullOrBlank() -> "Ti ascolto…"
    else -> partial
  }
  // La didascalia e' piu' tenue delle parole vere; il passaggio e' una sfumatura, non uno scatto.
  val colour by animateColorAsState(if (caption) scheme.onSurfaceVariant else scheme.onSurface, FluidMotion.color(), label = "voiceLineColour")
  val dot = scheme.primary
  val scroll = rememberScrollState()
  // Si segue la fine dopo il layout, non alla composizione: `maxValue` cambia quando il testo
  // nuovo e' stato impaginato, e un `scrollTo` lanciato nella composizione leggerebbe quello vecchio.
  LaunchedEffect(scroll) {
    snapshotFlow { scroll.maxValue }.collectLatest { scroll.animateScrollTo(it, FluidMotion.snappy()) }
  }
  Row(
    modifier = modifier
      .height(VoiceLineHeight)
      .fluidPressable(onClick = onTap, role = Role.Button)
      .padding(start = 12.dp, end = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Spacer(
      Modifier.size(DotHaloRadius * 2).drawBehind {
        val a = amplitude().coerceIn(0f, 1f)
        drawCircle(dot.copy(alpha = 0.10f + 0.25f * a), radius = DotHaloRadius.toPx() * (0.6f + 0.4f * a))
        drawCircle(dot.copy(alpha = 0.7f + 0.3f * a), radius = (DotRadiusMin + DotRadiusVoice * a).toPx())
      },
    )
    Spacer(Modifier.width(10.dp))
    Box(
      Modifier
        .weight(1f)
        .height(VoiceLineHeight)
        // Il layer fuori schermo serve alla dissolvenza `DstIn`, e sta solo su questa scatola
        // piccola: senza layer il `DstIn` bucherebbe il vetro invece del testo.
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
          drawContent()
          drawRect(
            brush = Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY = 0f, endY = VoiceFadeHeight.toPx()),
            blendMode = BlendMode.DstIn,
          )
        }
        .verticalScroll(scroll)
        // Almeno alta quanto la finestra: una riga sola sta al centro, due o piu' scorrono.
        .defaultMinSize(minHeight = VoiceLineHeight)
        .fillMaxWidth(),
      contentAlignment = Alignment.CenterStart,
    ) {
      RevealingText(
        text = AnnotatedString(text),
        style = MaterialTheme.typography.bodyLarge,
        color = colour,
        animateFirst = true,
      )
    }
  }
}
