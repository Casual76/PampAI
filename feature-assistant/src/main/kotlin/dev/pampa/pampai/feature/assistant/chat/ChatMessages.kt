package dev.pampa.pampai.feature.assistant.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.antigravity.fluidengine.ai.orchestrator.AnswerChip
import dev.antigravity.fluidengine.ai.orchestrator.AssistantState
import dev.antigravity.fluidengine.ai.orchestrator.PendingConfirmation
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.antigravity.fluidengine.aiui.chat.FluidAssistantBubble
import dev.antigravity.fluidengine.aiui.chat.FluidBubbleOutcome
import dev.antigravity.fluidengine.aiui.chat.FluidEmptyGreeting
import dev.antigravity.fluidengine.aiui.chat.FluidLiveBubble
import dev.antigravity.fluidengine.aiui.chat.FluidMessageAction
import dev.antigravity.fluidengine.aiui.chat.FluidPillAction
import dev.antigravity.fluidengine.aiui.chat.FluidQueuedBubble
import dev.antigravity.fluidengine.aiui.chat.FluidSmallAction
import dev.antigravity.fluidengine.aiui.chat.FluidUserBubble
import dev.antigravity.fluidengine.aiui.chat.fluidCopyToClipboard
import dev.antigravity.fluidengine.aiui.chat.fluidShareText
import dev.antigravity.fluidengine.aiui.overlay.FluidFailureReport
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidChip
import dev.antigravity.fluidengine.ui.fluid.FluidContextAction
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.fluidContextMenuAnchor
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.fluid.rememberFluidContextMenu
import dev.pampa.pampai.core.assistant.chat.Greetings
import dev.pampa.pampai.core.assistant.chat.MathText
import dev.pampa.pampai.core.assistant.db.Attachment
import dev.pampa.pampai.core.assistant.db.AttachmentKind
import dev.pampa.pampai.core.assistant.db.Message
import dev.pampa.pampai.core.assistant.db.MessageStatus
import dev.pampa.pampai.core.assistant.db.Run
import dev.pampa.pampai.core.assistant.runtime.LOCAL_OUTCOME
import dev.pampa.pampai.core.assistant.runtime.STOPPED_OUTCOME
import java.time.LocalTime
import kotlin.random.Random

/**
 * La pagina vuota di una chat gia' aperta altre volte: il nome al centro e una frase sotto.
 *
 * La frase si sceglie una volta per apertura (`remember` senza chiave): rigenerarla a ogni
 * ricomposizione la farebbe cambiare mentre si scrive, che e' esattamente il contrario di un saluto.
 */
@Composable
internal fun LazyItemScope.EmptyGreeting(onSuggestion: (String) -> Unit = {}) {
  val greeting = remember {
    Greetings.greeting(hour = LocalTime.now().hour, pick = Random.nextLong())
  }
  val suggestions = remember { Greetings.suggestions(Greetings.band(LocalTime.now().hour)) }
  FluidEmptyGreeting(
    title = "Aria",
    greeting = greeting,
    suggestions = suggestions,
    onSuggestion = onSuggestion,
    modifier = Modifier.fillParentMaxHeight(),
    mark = { AriaMark(size = 84.dp) },
  )
}

/**
 * La domanda: una bolla colorata a destra, come in una chat.
 *
 * Non e' una card di vetro a tutta larghezza. In una conversazione le due voci si distinguono per
 * forma prima che per etichetta, e una domanda che occupa la riga intera quanto la risposta toglie
 * proprio quel segnale.
 *
 * La matita sta fuori, a sinistra, e solo sull'ultima domanda ([showEdit]): una matita accanto a
 * ogni bolla era rumore. Ma "modifica e rinvia" non cancella piu' niente (fa una versione nuova, e
 * le frecce riportano a quella di prima), quindi vale per ogni domanda: sta nella pressione lunga
 * sulla bolla (Copia, Modifica e rinvia), che TalkBack elenca fra le azioni insieme alle versioni.
 *
 * Con piu' versioni, sotto la bolla, a destra, le frecce "‹ 2/3 ›" ([VersionSwitcher]).
 */
@Composable
internal fun UserBubble(
  message: Message,
  onEdit: () -> Unit,
  onOpenAttachment: (Attachment) -> Unit = {},
  showEdit: Boolean = true,
  /** Falso mentre Aria lavora su questa conversazione: frecce e modifica aspettano. */
  versionsEnabled: Boolean = true,
  onVersion: (Long) -> Unit = {},
) {
  val context = androidx.compose.ui.platform.LocalContext.current
  FluidUserBubble(
    text = message.text,
    actions = listOf(
      FluidMessageAction("Copia", Icons.Rounded.ContentCopy) { copy(context, message.text) },
      FluidMessageAction("Modifica e rinvia", Icons.Rounded.Edit, onEdit),
    ),
    extraAccessibilityActions = versionAccessibilityActions(message.version, versionsEnabled, onVersion),
    leading = if (showEdit) ({ FluidSmallAction(Icons.Rounded.Edit, "Modifica", onEdit) }) else null,
    attachments = if (message.attachments.isEmpty()) null else ({
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        message.attachments.forEach { attachment ->
          if (attachment.kind == AttachmentKind.IMAGE) {
            // La foto si vede: una miniatura vera, e un tocco la apre grande.
            AsyncImage(
              model = java.io.File(attachment.path),
              contentDescription = "Immagine allegata: ${attachment.name}",
              contentScale = ContentScale.Crop,
              modifier = Modifier
                .size(88.dp)
                .clip(ContinuousCornerShape(FluidRadius.Control))
                .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.08f))
                .fluidPressable(onClick = { onOpenAttachment(attachment) }, role = Role.Image, haptic = null),
            )
          } else {
            FluidChip(
              label = attachment.name.take(28),
              selected = false,
              onClick = { onOpenAttachment(attachment) },
              leading = { Icon(Icons.Rounded.AttachFile, contentDescription = "Documento", modifier = Modifier.size(16.dp)) },
            )
          }
        }
      }
    }),
    below = message.version?.let { version -> { VersionSwitcher(version, enabled = versionsEnabled, onSelect = onVersion) } },
    strings = AriaChatStrings,
  )
}

/**
 * Una domanda scritta mentre Aria lavorava, che aspetta il suo turno: la bolla della domanda, piu'
 * tenue, con sotto "In coda" e la X che la riporta nel campo. Parte da sola quando Aria finisce.
 */
@Composable
internal fun QueuedBubble(question: QueuedQuestion, onCancel: () -> Unit) {
  FluidQueuedBubble(text = question.text, onCancel = onCancel, attachmentCount = question.attachments.size, strings = AriaChatStrings)
}

/**
 * Cosa si puo' fare con una risposta: callback e stato della conversazione, in un oggetto solo.
 * Prima erano dieci parametri, e ogni azione nuova rompeva ogni chiamata (anche negli screenshot).
 *
 * @param regenerateWith i servizi con una chiave verificata, per "Rigenera con…": con meno di due
 *   il tasto rigenera e basta.
 * @param locked Aria sta lavorando, qui o altrove: "rigenera" aspetta, perche' partirebbe fermando
 *   il lavoro in corso (anche quello dell'overlay).
 * @param versionsLocked Aria sta lavorando su questa conversazione: le frecce aspettano, perche' la
 *   domanda in corso si attacca al ramo di adesso.
 */
@Stable
internal class AnswerActions(
  val onResolve: (Long, Boolean) -> Unit = { _, _ -> },
  val onChip: (AnswerChip) -> Unit = {},
  val onRegenerate: (Message) -> Unit = {},
  val onRegenerateWith: (Message, ProviderId) -> Unit = { _, _ -> },
  val onCopy: (Message) -> Unit = {},
  val onShare: (Message) -> Unit = {},
  val onVersion: (Long) -> Unit = {},
  val onDetails: (FluidFailureReport) -> Unit = {},
  val regenerateWith: List<ProviderId> = emptyList(),
  val locked: Boolean = false,
  val versionsLocked: Boolean = locked,
)

/**
 * La risposta: testo a tutta larghezza, senza card.
 *
 * Una risposta chiusa in un riquadro si legge come una scheda; una conversazione vuole che le
 * parole stiano sulla pagina. Sopra, quando c'e' stato del lavoro, una riga richiudibile dice cosa
 * ha fatto; sotto stanno le azioni e la telemetria, in piccolo, per chi la va a cercare.
 *
 * La fine della risposta e' **un** movimento, non cinque. Prima, al `Done`, la riga di stato
 * spariva di colpo, la traccia compariva, i paragrafi diventavano Markdown, la riga delle azioni
 * spuntava e la misura animata si spegneva a meta'. Adesso: stato e traccia si scambiano nello
 * stesso posto ([AnswerHeader], come nella card della sessione), le azioni sfumano dentro, e la
 * colonna intera anima la sua misura finche' la risposta e' viva — [live] resta lo stato `Done`
 * finche' non comincia la domanda dopo (vedi `ChatRoute`), cosi' la molla arriva in fondo.
 *
 * @param live lo stato del runtime se riguarda questa risposta: in corso, o appena finita.
 */
@Composable
internal fun AssistantMessage(
  message: Message,
  run: Run?,
  live: AssistantState?,
  pending: PendingConfirmation?,
  actions: AnswerActions,
  modifier: Modifier = Modifier,
) {
  val busy = live?.isBusy == true
  // Chi stava rispondendo: l'ultimo modello che ha parlato, o il servizio del passaggio. Serve a
  // nominarlo in un fallimento ("OpenRouter e' al limite"): con la 2.8.0 lo porta anche `Failed`.
  val answering = run?.failure?.provider ?: (live as? AssistantState.Failed)?.provider ?: run?.models?.lastOrNull()?.provider ?: run?.provider
  val text = responseText(message, live)
  val header = rememberAnswerHeader(live, run, answering)
  // Finita: nessun lavoro in corso su di lei. Una risposta rimasta aperta su disco senza nessuno
  // che la scriva (l'app chiusa a meta') e' finita anche lei, e ha "Riprova".
  val settled = !busy
  val failureKind = message.failureKind ?: (live as? AssistantState.Failed)?.kind
  val failed = message.status == MessageStatus.FAILED || live is AssistantState.Failed
  val cancelled = message.status == MessageStatus.CANCELLED || live is AssistantState.Cancelled
  // Le formule LaTeX si riscrivono in testo prima del Markdown (il disegno di `FluidMarkdown` non le conosce).
  val shown = remember(text) { MathText.rewrite(text) }
  // Come finisce una risposta senza (o oltre) il suo testo; la tabella dei casi e' `fluidOutcomeLines`.
  val outcome = when {
    failed -> FluidBubbleOutcome.Failed(
      failureKind?.let { AssistantTexts.failure(it, provider = answering, tried = AssistantTexts.tried(run?.switches.orEmpty())) } ?: "Qualcosa e' andato storto.",
    )
    cancelled -> FluidBubbleOutcome.Cancelled
    // Ancora niente testo: lo dice la riga di stato nell'intestazione.
    live != null -> FluidBubbleOutcome.None
    message.status == MessageStatus.DONE -> FluidBubbleOutcome.Empty
    else -> FluidBubbleOutcome.Interrupted
  }
  // La riserva automatica e' accesa di default: un servizio diverso da quello scelto che risponde
  // senza dirlo e' un modello che cambia in silenzio. Dal `Done` (i cambi li porta lo stato) prima
  // ancora che arrivi la riga del run, poi da quella.
  val switches = run?.switches?.takeIf { it.isNotEmpty() } ?: (live as? AssistantState.Done)?.switches.orEmpty()
  val switchLine = if (!busy && !failed && text.isNotBlank()) AssistantTexts.switchLine(switches) else null
  FluidAssistantBubble(
    text = shown,
    modifier = modifier.semantics { customActions = versionAccessibilityActions(message.version, !actions.versionsLocked, actions.onVersion) },
    streaming = live is AssistantState.Answering && !dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy.current.reducedMotion,
    settled = settled,
    // Una per messaggio: una versione vecchia ritrovata con le frecce e' gia' letta, non si rivela.
    contentKey = message.id,
    header = { AnswerHeader(header) },
    confirmation = pending.takeIf { live is AssistantState.AwaitingConfirmation },
    onConfirm = actions.onResolve,
    outcome = outcome,
    busy = busy,
    chips = message.chips,
    chipLabel = AssistantTexts::chipLabel,
    onChip = actions.onChip,
    switchLine = switchLine,
    actions = {
      ToolResultCards(message.receipts)
      AnswerActionRow(
        message = message,
        run = run,
        text = text,
        failed = failed,
        retry = failed || cancelled || text.isBlank(),
        onDetails = { actions.onDetails(failureReportOf(message, run, live)) },
        actions = actions,
      )
    },
    animateSize = live != null && !dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy.current.reducedMotion,
    strings = AriaChatStrings,
    assistantStrings = AriaAssistantStrings,
  )
}

/**
 * La riga sotto una risposta finita.
 *
 * Nell'ordine: le versioni (se ce n'e' piu' d'una), Copia e Condividi (se c'e' del testo), poi
 * "Rigenera" su ogni risposta — non solo sull'ultima: rigenerarne una vecchia fa una versione
 * nuova in quel punto, e le altre restano — e la telemetria. Una risposta fallita, fermata o vuota
 * al posto di "Rigenera" ha "Riprova", in parole e non solo in icona, e se e' fallita "Dettagli".
 * Un comando rapido (o il kill switch) non si rigenera: rifarlo con il modello sarebbe un secondo
 * timer.
 */
@Composable
private fun AnswerActionRow(
  message: Message,
  run: Run?,
  text: String,
  failed: Boolean,
  retry: Boolean,
  onDetails: () -> Unit,
  actions: AnswerActions,
) {
  FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
  val local = run?.outcome == LOCAL_OUTCOME || run?.outcome == STOPPED_OUTCOME
  val hasText = text.isNotBlank()
  message.version?.let { version -> VersionSwitcher(version, enabled = !actions.versionsLocked, onSelect = actions.onVersion) }
  // Una risposta fallita con qualcosa di scritto tiene Copia; Condividi resta a quelle riuscite,
  // cosi' con le frecce, "Riprova" e "Dettagli" la riga ci sta ancora su un telefono stretto.
  if (hasText && !(retry && message.version != null)) FluidSmallAction(Icons.Rounded.ContentCopy, "Copia", { actions.onCopy(message) })
  if (hasText && !retry) FluidSmallAction(Icons.Rounded.Share, "Condividi", { actions.onShare(message) })
  when {
    retry -> RegenerateAction("Riprova", actions.regenerateWith, !actions.locked, pill = true, onRegenerate = { actions.onRegenerate(message) }, onRegenerateWith = { actions.onRegenerateWith(message, it) })
    !local -> RegenerateAction("Rigenera", actions.regenerateWith, !actions.locked, pill = false, onRegenerate = { actions.onRegenerate(message) }, onRegenerateWith = { actions.onRegenerateWith(message, it) })
  }
  if (failed) {
    Spacer(Modifier.width(4.dp))
    FluidPillAction(Icons.Rounded.Info, "Dettagli", onClick = onDetails)
  }
  }

}

/**
 * La risposta in corso quando la conversazione e' nuova e non c'e' ancora un messaggio su disco:
 * la domanda (dallo stato, finche' la riga non arriva) e sotto la riga di stato. Mostra anche il
 * testo di `Done`, `Failed` e `Cancelled`: fra la fine della risposta e il primo messaggio emesso
 * da Room passa un fotogramma, e in quel fotogramma il testo non deve sparire. E' la bolla viva
 * dell'engine, con le parole di Aria.
 */
@Composable
internal fun LiveBubble(live: AssistantState, pending: PendingConfirmation?, onResolve: (Long, Boolean) -> Unit, onChip: (AnswerChip) -> Unit = {}) {
  FluidLiveBubble(
    state = live,
    pending = pending,
    onConfirm = onResolve,
    strings = AriaChatStrings,
    assistantStrings = AriaAssistantStrings,
    chipLabel = AssistantTexts::chipLabel,
    onChip = onChip,
  )
}

/**
 * Rigenera (o Riprova), e "…con…": con piu' di un servizio pronto il tocco apre un menu con lo
 * stesso servizio e gli altri. Una risposta sbagliata di un modello e' spesso giusta per un altro,
 * e prima per provarlo bisognava cambiare l'ordine nelle impostazioni.
 *
 * @param pill con la parola accanto ("Riprova"), per le risposte fallite; altrimenti solo l'icona.
 */
@Composable
private fun RegenerateAction(
  label: String,
  providers: List<ProviderId>,
  enabled: Boolean,
  pill: Boolean,
  onRegenerate: () -> Unit,
  onRegenerateWith: (ProviderId) -> Unit,
) {
  val several = providers.size >= 2
  val menu = rememberFluidContextMenu(actions = {
    listOf(FluidContextAction(label, Icons.Rounded.Refresh, onClick = onRegenerate)) +
      providers.map { provider -> FluidContextAction("$label con ${provider.label}", Icons.Rounded.SwapHoriz) { onRegenerateWith(provider) } }
  })
  val onClick = if (several) ({ menu.open(); Unit }) else onRegenerate
  val anchor = if (several) Modifier.fluidContextMenuAnchor(menu) else Modifier
  val description = if (several) "$label, anche con un altro servizio" else label
  if (pill) {
    FluidPillAction(Icons.Rounded.Refresh, label, onClick = onClick, modifier = anchor, enabled = enabled)
  } else {
    FluidSmallAction(Icons.Rounded.Refresh, description, onClick = onClick, modifier = anchor, enabled = enabled)
  }
}

internal fun copy(context: android.content.Context, text: String) = fluidCopyToClipboard(context, text, label = "Aria", confirmation = "Copiato")

/** Condivide la risposta come testo semplice: gli asterischi e i cancelletti del Markdown in un messaggio sono rumore. */
internal fun share(context: android.content.Context, text: String) = fluidShareText(context, text, chooserTitle = "Condividi la risposta")
