package dev.pampa.pampai.feature.assistant.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ai.orchestrator.AnswerChip
import dev.antigravity.fluidengine.ai.orchestrator.AssistantState
import dev.antigravity.fluidengine.ai.orchestrator.PendingConfirmation
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidChip
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.pampa.pampai.core.assistant.chat.Greetings
import dev.pampa.pampai.core.assistant.db.AttachmentKind
import dev.pampa.pampai.core.assistant.db.Message
import dev.pampa.pampai.core.assistant.db.MessageStatus
import dev.pampa.pampai.core.assistant.db.Run
import java.time.LocalTime
import kotlin.random.Random

/**
 * La pagina vuota di una chat gia' aperta altre volte: il nome al centro e una frase sotto.
 *
 * La frase si sceglie una volta per apertura (`remember` senza chiave): rigenerarla a ogni
 * ricomposizione la farebbe cambiare mentre si scrive, che e' esattamente il contrario di un saluto.
 */
@Composable
internal fun LazyItemScope.EmptyGreeting() {
  val greeting = remember {
    Greetings.greeting(hour = LocalTime.now().hour, pick = Random.nextLong())
  }
  Box(
    Modifier
      .fillParentMaxHeight()
      .fillMaxWidth()
      .padding(bottom = 96.dp),
    contentAlignment = Alignment.Center,
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      AriaMark(size = 84.dp)
      Spacer(Modifier.height(6.dp))
      Text(
        "Aria",
        style = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Spacer(Modifier.height(8.dp))
      Text(
        greeting,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
    }
  }
}

/**
 * La domanda: una bolla colorata a destra, come in una chat.
 *
 * Non e' una card di vetro a tutta larghezza. In una conversazione le due voci si distinguono per
 * forma prima che per etichetta, e una domanda che occupa la riga intera quanto la risposta toglie
 * proprio quel segnale. La matita sta fuori, a sinistra: dentro rubava spazio a ogni messaggio.
 */
@Composable
internal fun UserBubble(message: Message, onEdit: () -> Unit) {
  BoxWithConstraints(Modifier.fillMaxWidth()) {
    val maxBubble = maxWidth * 0.86f
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
      SmallAction(Icons.Rounded.Edit, "Modifica", onEdit)
      Column(
        modifier = Modifier
          .widthIn(max = maxBubble)
          .background(MaterialTheme.colorScheme.primaryContainer, ContinuousCornerShape(FluidRadius.Card))
          .padding(horizontal = 16.dp, vertical = 12.dp),
      ) {
        Text(
          text = message.text,
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        if (message.attachments.isNotEmpty()) {
          Spacer(Modifier.height(8.dp))
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            message.attachments.forEach { attachment ->
              FluidChip(label = attachment.name.take(28), selected = false, onClick = {}, leading = { Icon(if (attachment.kind == AttachmentKind.IMAGE) Icons.Rounded.Image else Icons.Rounded.AttachFile, contentDescription = null, modifier = Modifier.size(16.dp)) })
            }
          }
        }
      }
    }
  }
}

/**
 * La risposta: testo a tutta larghezza, senza card.
 *
 * Una risposta chiusa in un riquadro si legge come una scheda; una conversazione vuole che le
 * parole stiano sulla pagina. Sopra, quando c'e' stato del lavoro, una riga richiudibile dice cosa
 * ha fatto; sotto stanno le azioni e la telemetria, in piccolo, per chi la va a cercare.
 */
@Composable
internal fun AssistantMessage(
  message: Message,
  run: Run?,
  live: AssistantState?,
  pending: PendingConfirmation?,
  onResolve: (Long, Boolean) -> Unit,
  onChip: (AnswerChip) -> Unit,
  onRegenerate: () -> Unit,
  onCopy: () -> Unit,
  onShare: () -> Unit,
) {
  val busy = live != null && live.isBusy
  // Chi stava rispondendo: l'ultimo modello che ha parlato, o il servizio del passaggio. Serve
  // solo a nominarlo in un fallimento ("OpenRouter e' al limite"), che senza nome non dice a chi
  // tornare: `AssistantState.Failed` il servizio non lo porta.
  val answering = run?.models?.lastOrNull()?.provider ?: run?.provider
  Column(Modifier.fillMaxWidth()) {
    run?.let { RunSteps(it) }
    if (busy) {
      AssistantTexts.statusLine(live!!, answering)?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
      }
      if (pending != null) ConfirmationRow(pending, onResolve)
    }
    val text = responseText(message, live)
    val memo = remember { ResponseMemo(freshStart = text.isBlank()) }
    if (busy && text.isNotBlank()) Spacer(Modifier.height(8.dp))
    when {
      text.isNotBlank() -> ResponseBody(text, streaming = live is AssistantState.Answering, live = live != null, memo = memo)
      message.status == MessageStatus.FAILED -> Text(message.failureKind?.let { AssistantTexts.failure(it, provider = answering) } ?: "Qualcosa e' andato storto.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
      message.status == MessageStatus.CANCELLED -> Text("Fermata prima della risposta.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      live == null -> Text("Interrotta: l'app si e' chiusa prima della risposta.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (message.status == MessageStatus.FAILED && text.isNotBlank()) {
      Spacer(Modifier.height(6.dp))
      Text(message.failureKind?.let { AssistantTexts.failure(it, provider = answering) } ?: "Interrotta.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    if (message.chips.isNotEmpty()) {
      Spacer(Modifier.height(10.dp))
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        message.chips.forEach { chip -> FluidChip(label = AssistantTexts.chipLabel(chip), selected = false, onClick = { onChip(chip) }) }
      }
    }
    if (!busy && text.isNotBlank()) {
      Spacer(Modifier.height(2.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        SmallAction(Icons.Rounded.ContentCopy, "Copia", onCopy)
        SmallAction(Icons.Rounded.Share, "Condividi", onShare)
        SmallAction(Icons.Rounded.Refresh, "Rigenera", onRegenerate)
        run?.let {
          Spacer(Modifier.width(4.dp))
          Text(telemetry(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
      }
    }
  }
}

/**
 * La risposta in corso quando la conversazione e' nuova e non c'e' ancora un messaggio su disco.
 * Mostra anche il testo di `Done`, `Failed` e `Cancelled`: fra la fine della risposta e il primo
 * messaggio emesso da Room passa un fotogramma, e in quel fotogramma il testo non deve sparire.
 */
@Composable
internal fun LiveBubble(live: AssistantState, pending: PendingConfirmation?, onResolve: (Long, Boolean) -> Unit) {
  val text = when (live) {
    is AssistantState.Answering -> live.partial
    is AssistantState.Done -> live.answer
    is AssistantState.Failed -> live.partial.orEmpty()
    is AssistantState.Cancelled -> live.partial.orEmpty()
    else -> ""
  }
  val memo = remember { ResponseMemo(freshStart = text.isBlank()) }
  Column(Modifier.fillMaxWidth()) {
    AssistantTexts.statusLine(live)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = if (live is AssistantState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium) }
    if (text.isNotBlank()) {
      Spacer(Modifier.height(8.dp))
      ResponseBody(text, streaming = live is AssistantState.Answering, live = true, memo = memo)
    }
    if (pending != null) ConfirmationRow(pending, onResolve)
  }
}

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
private class ResponseMemo(val freshStart: Boolean) {
  var streamed = false
}

/**
 * Il testo da mostrare. Mentre risponde, il partial vivo. A `Done` il testo dello stato: Room
 * emette il messaggio completo un attimo dopo, e per quel fotogramma `message.text` e' ancora il
 * partial salvato fino a 300 ms prima (era la riga che spariva a fine risposta). Fermata o
 * fallita, il partial se c'e'; altrimenti il messaggio.
 */
private fun responseText(message: Message, live: AssistantState?): String = when (live) {
  is AssistantState.Answering -> live.partial.ifBlank { message.text }
  is AssistantState.Done -> live.answer.ifBlank { message.text }
  is AssistantState.Failed -> live.partial?.takeIf { it.isNotBlank() } ?: message.text
  is AssistantState.Cancelled -> live.partial?.takeIf { it.isNotBlank() } ?: message.text
  else -> message.text
}

/**
 * Il corpo della risposta: in streaming i paragrafi che si rivelano, poi il Markdown completo.
 *
 * `animateContentSize` solo sulla risposta viva: spalma la crescita sui fotogrammi, ed e' quello
 * che rende continuo il seguito della lista (che scorre di quanto il testo sporge). Sui messaggi
 * vecchi non servirebbe e costerebbe una misura in piu' ciascuno.
 */
@Composable
private fun ResponseBody(text: String, streaming: Boolean, live: Boolean, memo: ResponseMemo) {
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

@Composable
private fun SmallAction(icon: ImageVector, description: String, onClick: () -> Unit) {
  Box(
    modifier = Modifier
      .size(32.dp)
      .fluidPressable(onClick = onClick, role = Role.Button, haptic = null),
    contentAlignment = Alignment.Center,
  ) {
    Icon(icon, contentDescription = description, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f), modifier = Modifier.size(16.dp))
  }
}

internal fun copy(context: android.content.Context, text: String) {
  val clipboard = context.getSystemService(android.content.ClipboardManager::class.java) ?: return
  clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Aria", text))
}

internal fun share(context: android.content.Context, text: String) {
  val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
    type = "text/plain"
    putExtra(android.content.Intent.EXTRA_TEXT, text)
  }
  runCatching { context.startActivity(android.content.Intent.createChooser(intent, "Condividi la risposta").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
