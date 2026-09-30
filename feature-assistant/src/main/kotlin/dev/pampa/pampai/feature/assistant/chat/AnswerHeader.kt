package dev.pampa.pampai.feature.assistant.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ai.orchestrator.AssistantState
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.pampa.pampai.core.assistant.db.Run
import kotlinx.coroutines.delay

/**
 * Cosa sta sopra una risposta della chat: la riga di stato mentre lavora, poi la traccia di cosa ha
 * fatto. Un posto solo per le due, come nella card della sessione (`ExchangeHeader`), perche'
 * arrivano insieme: a fine risposta la riga di stato sparisce (`Done` non ne ha) e la traccia
 * compare (la riga del run si scrive alla fine). Due blocchi separati erano un salto doppio del
 * testo sotto — su di una riga, giu' di un chip.
 */
@Immutable
internal sealed interface AnswerHeaderState {
  /** Quale posto occupa: il crossfade scatta fra posti diversi, non a ogni nuovo testo di stato. */
  val slot: Int

  data class Status(val text: String) : AnswerHeaderState {
    override val slot = 0
  }

  data class Steps(val run: Run) : AnswerHeaderState {
    override val slot = 1
  }

  data object None : AnswerHeaderState {
    override val slot = 2
  }
}

/** Il testo di stato piu' recente di una risposta, per tenerlo al suo posto finche' arriva la traccia. */
private class StatusMemo {
  var last: String? = null
}

/**
 * Quale intestazione mostrare. Fra `Done` e la riga del run che arriva da Room passano pochi
 * fotogrammi: l'ultima riga di stato resta li' (al massimo [RunHoldMillis]) invece di chiudersi e
 * riaprirsi come traccia, cosi' il posto non si stringe per poi allargarsi.
 *
 * @param provider chi stava rispondendo, per nominarlo in una riga di stato ("Groq e' al limite").
 */
@Composable
internal fun rememberAnswerHeader(live: AssistantState?, run: Run?, provider: ProviderId?): AnswerHeaderState {
  val status = live?.takeIf { it.isBusy }?.let { AssistantTexts.statusLine(it, provider) }
  val memo = remember { StatusMemo() }
  if (status != null) memo.last = status
  val awaitingRun = live is AssistantState.Done && run == null
  var holdOver by remember { mutableStateOf(false) }
  LaunchedEffect(awaitingRun) {
    if (!awaitingRun) return@LaunchedEffect
    delay(RunHoldMillis)
    holdOver = true
  }
  return when {
    status != null -> AnswerHeaderState.Status(status)
    run != null && runHasSteps(run) -> AnswerHeaderState.Steps(run)
    awaitingRun && !holdOver ->memo.last?.let { AnswerHeaderState.Status(it) } ?: AnswerHeaderState.None
    else -> AnswerHeaderState.None
  }
}

/**
 * Il posto sopra la risposta. Il passaggio fra stato e traccia e' un crossfade con la misura
 * animata, le stesse molle della card della sessione: il testo sotto scivola di quanto il chip e'
 * piu' alto della riga, non ci salta. Con il moto ridotto il cambio e' immediato.
 */
@Composable
internal fun AnswerHeader(header: AnswerHeaderState) {
  val reducedMotion = LocalFluidMotionPolicy.current.reducedMotion
  AnimatedContent(
    targetState = header,
    contentKey = { it.slot },
    transitionSpec = {
      if (reducedMotion) {
        EnterTransition.None togetherWith ExitTransition.None
      } else {
        fadeIn(FluidMotion.crossFade()) togetherWith fadeOut(FluidMotion.crossFade()) using
          SizeTransform(clip = false) { _, _ -> FluidMotion.intSize(FluidMotion.DampingChrome, FluidMotion.ResponseSnappy) }
      }
    },
    label = "answerHeader",
  ) { shown ->
    when (shown) {
      is AnswerHeaderState.Status -> Text(
        text = shown.text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Medium,
        // Lo stesso spazio sotto della traccia (RunSteps): scambiandosi, il testo sotto non si muove.
        modifier = Modifier.padding(bottom = 8.dp),
      )
      is AnswerHeaderState.Steps -> RunSteps(shown.run)
      AnswerHeaderState.None -> Unit
    }
  }
}

/** Quanto la riga di stato aspetta la traccia dopo `Done`, prima di chiudersi da sola (come nella sessione). */
private const val RunHoldMillis = 400L
