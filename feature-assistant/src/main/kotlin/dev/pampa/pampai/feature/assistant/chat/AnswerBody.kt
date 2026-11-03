package dev.pampa.pampai.feature.assistant.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.antigravity.fluidengine.ai.orchestrator.AssistantState
import dev.antigravity.fluidengine.aiui.markdown.FluidAssistantMarkdownOptions
import dev.antigravity.fluidengine.ui.fluid.FluidMarkdown
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.pampa.pampai.core.assistant.chat.MathText
import dev.pampa.pampai.core.assistant.db.Message

/*
 * Il corpo di una risposta, condiviso fra la chat e la card della sessione: tutte e due devono
 * decidere allo stesso modo quale testo mostrare a fine risposta, altrimenti una delle due salta
 * dove l'altra non salta.
 */

/**
 * Cio' che un item ricorda di se' fra una ricomposizione e l'altra. [freshStart]: al primo
 * passaggio non c'era testo, quindi quello che arriva sta nascendo adesso e si rivela parola per
 * parola; se invece c'era gia' (chat riaperta, cambio di chiave dell'item alla prima risposta) e'
 * gia' letto.
 */
internal class ResponseMemo(val freshStart: Boolean)

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
 * Il corpo della risposta: il `FluidMarkdown` dell'engine, un solo nodo per il testo che arriva e
 * per quello arrivato (le parole nuove sfumano dentro finche' [streaming], poi e' lo stesso disegno:
 * non c'e' piu' un secondo resa che prenda il posto del primo, quindi nessun salto di misure).
 *
 * Le formule LaTeX il Markdown non le disegna, le toglieva: prima si riscrivono in testo
 * ([MathText]), e i marcatori `[[chip]]` spariscono dal testo (diventano i chip sotto la risposta).
 *
 * `animateContentSize` solo sulla risposta viva: spalma la crescita sui fotogrammi, ed e' quello
 * che rende continuo il seguito della lista (che scorre di quanto il testo sporge). Sui messaggi
 * vecchi non servirebbe e costerebbe una misura in piu' ciascuno.
 */
@Composable
internal fun ResponseBody(text: String, streaming: Boolean, live: Boolean, memo: ResponseMemo) {
  val sizing = if (live) Modifier.animateContentSize(FluidMotion.intSize(FluidMotion.DampingChrome, FluidMotion.ResponseSnappy)) else Modifier
  val markdown = remember(text) { MathText.rewrite(text) }
  Box(Modifier.fillMaxWidth().then(sizing)) {
    FluidMarkdown(
      markdown = markdown,
      streaming = streaming,
      animateFirst = memo.freshStart,
      options = FluidAssistantMarkdownOptions,
    )
  }
}
