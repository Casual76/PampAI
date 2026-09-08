package dev.pampa.pampai.feature.assistant.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ai.orchestrator.AssistantState
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.pampa.pampai.feature.assistant.chat.AssistantTexts

/**
 * La nota breve sopra la barra: lo schermo che manca, il ritaglio troppo piccolo, "Non ho sentito
 * niente". Un tocco la manda via.
 *
 * **Niente vetro.** E' una riga di due parole che compare e sparisce: una superficie rifrangente
 * qui costerebbe una cattura del fondale per ogni nota, e in mezzo alla pila ne farebbe una terza
 * dopo la card e la barra. Un colore opaco del tema basta, e si legge sopra qualunque app.
 *
 * Raccoglie anche gli epiloghi **senza domanda**: se Aria fallisce o viene fermata prima ancora di
 * avere una domanda (o non sente niente), non c'e' nessuno scambio da mostrare e la card non
 * esiste — quella riga finirebbe nel vuoto. Con una domanda, invece, la riga sta nella card,
 * attaccata a cio' che si era chiesto.
 *
 * @param notice la nota del controller; ha la precedenza sull'epilogo, perche' e' piu' recente.
 */
@Composable
internal fun SessionNotice(
  notice: String?,
  live: AssistantState?,
  onDismiss: () -> Unit,
  onOpenAssistSettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val text = notice ?: live?.noticeLine()
  // Il testo dell'uscita: mentre la riga si chiude ha ancora bisogno di qualcosa da disegnare.
  var shown by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(text) { if (text != null) shown = text }
  AnimatedVisibility(
    visible = text != null,
    enter = fadeIn() + expandVertically(),
    exit = fadeOut() + shrinkVertically(),
    modifier = modifier,
  ) {
    val message = shown.orEmpty()
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
        .fillMaxWidth()
        .padding(bottom = 8.dp)
        .background(
          MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
          ContinuousCornerShape(FluidRadius.Control),
        )
        .fluidPressable(onClick = onDismiss, role = Role.Button, haptic = null)
        .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
      Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.weight(1f),
      )
      if (message.contains(AssistSettingsHint)) {
        Spacer(Modifier.width(8.dp))
        FluidButton(
          text = "Impostazioni assistente",
          onClick = onOpenAssistSettings,
          style = FluidButtonStyle.Tinted,
          size = FluidButtonSize.Small,
        )
      }
    }
  }
}

/** L'epilogo che non ha una card dove stare: nessuna domanda, nessuno scambio. */
private fun AssistantState.noticeLine(): String? = when (this) {
  AssistantState.HeardNothing -> AssistantTexts.statusLine(this)
  is AssistantState.Failed -> if (question == null) AssistantTexts.statusLine(this) else null
  is AssistantState.Cancelled -> if (question == null) AssistantTexts.statusLine(this) else null
  else -> null
}

/** Le note che parlano delle impostazioni dell'assistente si portano dietro il tasto per aprirle. */
private const val AssistSettingsHint = "impostazioni dell'assistente"
