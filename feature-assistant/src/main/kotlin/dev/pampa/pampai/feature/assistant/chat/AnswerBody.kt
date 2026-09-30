package dev.pampa.pampai.feature.assistant.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.antigravity.fluidengine.ai.orchestrator.AssistantState
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.pampa.pampai.core.assistant.db.Message

/*
 * Il corpo di una risposta, condiviso fra la chat e la card della sessione: tutte e due devono
 * decidere allo stesso modo quale testo mostrare a fine risposta e come passare dai paragrafi che
 * si rivelano al Markdown completo, altrimenti una delle due salta dove l'altra non salta.
 */

/**
 * A fine risposta il testo passa al Markdown completo (tabelle, tasto copia sul codice, link
 * veri). Se il salto al passaggio si vedesse troppo, con `false` una risposta arrivata in
 * streaming resta sui paragrafi di [RevealingParagraphs] finche' l'item vive: riaperta dalla
 * cronologia sara' comunque Markdown.
 */
private const val HandOverToMarkdownWhenDone = true

/**
 * Cio' che un item ricorda di se' fra una ricomposizione e l'altra. [freshStart]: al primo
 * passaggio non c'era testo, quindi quello che arriva sta nascendo adesso e si rivela parola per
 * parola; se invece c'era gia' (chat riaperta, cambio di chiave dell'item alla prima risposta) e'
 * gia' letto. [streamed]: e' passato di qui in streaming, per [HandOverToMarkdownWhenDone].
 */
internal class ResponseMemo(val freshStart: Boolean) {
  var streamed = false
}

/**
 * Il testo da mostrare. Mentre risponde, il partial vivo. A `Done` il testo dello stato: Room
 * emette il messaggio completo un attimo dopo, e per quel fotogramma `message.text` e' ancora il
 * partial salvato fino a 300 ms prima (era la riga che spariva a fine risposta). Fermata o
 * fallita, il partial se c'e'; altrimenti il messaggio. Senza messaggio su disco (la prima
 * risposta di una conversazione nuova) conta solo lo stato.
 */
internal fun responseText(message: Message?, live: AssistantState?): String {
  val stored = message?.text.orEmpty()
  return when (live) {
    is AssistantState.Answering -> live.partial.ifBlank { stored }
    is AssistantState.Done -> live.answer.ifBlank { stored }
    is AssistantState.Failed -> live.partial?.takeIf { it.isNotBlank() } ?: stored
    is AssistantState.Cancelled -> live.partial?.takeIf { it.isNotBlank() } ?: stored
    else -> stored
  }
}

/**
 * Il corpo della risposta: in streaming i paragrafi che si rivelano, poi il Markdown completo.
 *
 * `animateContentSize` solo sulla risposta viva: spalma la crescita sui fotogrammi, ed e' quello
 * che rende continuo il seguito della lista (che scorre di quanto il testo sporge). Sui messaggi
 * vecchi non servirebbe e costerebbe una misura in piu' ciascuno.
 */
@Composable
internal fun ResponseBody(text: String, streaming: Boolean, live: Boolean, memo: ResponseMemo) {
  if (streaming) memo.streamed = true
  val reveal = streaming || (!HandOverToMarkdownWhenDone && memo.streamed)
  val scheme = MaterialTheme.colorScheme
  val typography = MaterialTheme.typography
  val sizing = if (live) Modifier.animateContentSize(FluidMotion.intSize(FluidMotion.DampingChrome, FluidMotion.ResponseSnappy)) else Modifier
  Box(Modifier.fillMaxWidth().then(sizing)) {
    if (reveal) {
      val blocks = remember(text, scheme, typography) { streamingBlocks(text, scheme, typography) }
      RevealingParagraphs(blocks, animateFirst = memo.freshStart)
    } else {
      MarkdownBody(text)
    }
  }
}
