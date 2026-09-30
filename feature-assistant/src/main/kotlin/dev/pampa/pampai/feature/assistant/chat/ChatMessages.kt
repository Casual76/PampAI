package dev.pampa.pampai.feature.assistant.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.antigravity.fluidengine.ai.orchestrator.AnswerChip
import dev.antigravity.fluidengine.ai.orchestrator.AssistantState
import dev.antigravity.fluidengine.ai.orchestrator.PendingConfirmation
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidChip
import dev.antigravity.fluidengine.ui.fluid.FluidContextAction
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.antigravity.fluidengine.ui.fluid.fluidContextMenuAnchor
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.fluid.rememberFluidContextMenu
import dev.pampa.pampai.core.assistant.chat.Greetings
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
      Spacer(Modifier.height(24.dp))
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 8.dp),
      ) {
        suggestions.forEach { suggestion -> FluidChip(label = suggestion, selected = false, onClick = { onSuggestion(suggestion) }) }
      }
    }
  }
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
  val menu = rememberFluidContextMenu(actions = {
    listOf(
      FluidContextAction("Copia", Icons.Rounded.ContentCopy) { copy(context, message.text) },
      FluidContextAction("Modifica e rinvia", Icons.Rounded.Edit, onClick = onEdit),
    )
  })
  val talkBack = listOf(
    CustomAccessibilityAction("Copia") { copy(context, message.text); true },
    CustomAccessibilityAction("Modifica e rinvia") { onEdit(); true },
  ) + versionAccessibilityActions(message.version, versionsEnabled, onVersion)
  Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
      val maxBubble = maxWidth * 0.86f
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        if (showEdit) SmallAction(Icons.Rounded.Edit, "Modifica", onEdit)
        Column(
          modifier = Modifier
            .widthIn(max = maxBubble)
            .fluidContextMenuAnchor(menu)
            .fluidPressable(onLongClick = { menu.open() }, pressedScale = 1f, haptic = null)
            .semantics { customActions = talkBack }
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
          }
        }
      }
    }
    message.version?.let { version -> VersionSwitcher(version, enabled = versionsEnabled, onSelect = onVersion) }
  }
}

/**
 * Una domanda scritta mentre Aria lavorava, che aspetta il suo turno: la bolla della domanda, piu'
 * tenue, con sotto "In coda" e la X che la riporta nel campo. Parte da sola quando Aria finisce.
 */
@Composable
internal fun QueuedBubble(question: QueuedQuestion, onCancel: () -> Unit) {
  Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
      Column(
        Modifier
          .widthIn(max = maxWidth * 0.86f)
          .alpha(QueuedAlpha)
          .background(MaterialTheme.colorScheme.primaryContainer, ContinuousCornerShape(FluidRadius.Card))
          .padding(horizontal = 16.dp, vertical = 12.dp),
      ) {
        Text(question.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        if (question.attachments.isNotEmpty()) {
          Text("${question.attachments.size} allegati", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
      }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(Icons.Rounded.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
      Spacer(Modifier.width(6.dp))
      Text("In coda: parte quando Aria ha finito", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      SmallAction(Icons.Rounded.Close, "Togli dalla coda", onCancel)
    }
  }
}

/** Quanto e' tenue una domanda in coda: si legge, ma si vede che non e' ancora partita. */
private const val QueuedAlpha = 0.6f

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
  val onDetails: (FailureReport) -> Unit = {},
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
  val reducedMotion = LocalFluidMotionPolicy.current.reducedMotion
  val busy = live?.isBusy == true
  // Chi stava rispondendo: l'ultimo modello che ha parlato, o il servizio del passaggio. Serve a
  // nominarlo in un fallimento ("OpenRouter e' al limite"): con la 2.8.0 lo porta anche `Failed`.
  val answering = run?.failure?.provider ?: (live as? AssistantState.Failed)?.provider ?: run?.models?.lastOrNull()?.provider ?: run?.provider
  val text = responseText(message, live)
  // Una per messaggio: una versione vecchia ritrovata con le frecce e' gia' letta, non si rivela.
  val memo = remember(message.id) { ResponseMemo(freshStart = text.isBlank()) }
  val header = rememberAnswerHeader(live, run, answering)
  // Finita: nessun lavoro in corso su di lei. Una risposta rimasta aperta su disco senza nessuno
  // che la scriva (l'app chiusa a meta') e' finita anche lei, e ha "Riprova".
  val settled = !busy
  val failureKind = message.failureKind ?: (live as? AssistantState.Failed)?.kind
  val failed = message.status == MessageStatus.FAILED || live is AssistantState.Failed
  val cancelled = message.status == MessageStatus.CANCELLED || live is AssistantState.Cancelled
  val sizing = if (live != null && !reducedMotion) Modifier.animateContentSize(FluidMotion.intSize(FluidMotion.DampingChrome, FluidMotion.ResponseSnappy)) else Modifier
  Column(
    modifier
      .fillMaxWidth()
      .then(sizing)
      .semantics { customActions = versionAccessibilityActions(message.version, !actions.versionsLocked, actions.onVersion) },
  ) {
    AnswerHeader(header)
    if (live is AssistantState.AwaitingConfirmation && pending != null) ConfirmationRow(pending, actions.onResolve)
    when {
      // `live = false`: la crescita la anima gia' la colonna qui sopra; una seconda molla dentro la
      // prima farebbe inseguire alla colonna un'altezza che si muove, e resterebbe indietro.
      text.isNotBlank() -> ResponseBody(text, streaming = live is AssistantState.Answering, live = false, memo = memo)
      failed -> Text(failureKind?.let { AssistantTexts.failure(it, provider = answering, tried = AssistantTexts.tried(run?.switches.orEmpty())) } ?: "Qualcosa e' andato storto.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
      cancelled -> Text("Fermata prima della risposta.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      // Ancora niente testo: lo dice la riga di stato qui sopra.
      busy || live != null -> Unit
      message.status == MessageStatus.DONE -> Text("Nessuna risposta: il servizio non ha scritto niente.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      else -> Text("Interrotta: l'app si e' chiusa prima della risposta.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (failed && text.isNotBlank()) {
      Spacer(Modifier.height(6.dp))
      Text(failureKind?.let { AssistantTexts.failure(it, provider = answering, tried = AssistantTexts.tried(run?.switches.orEmpty())) } ?: "Interrotta.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    // Una risposta fermata a meta' finisce a meta' frase: senza una riga che lo dice, sembra una
    // risposta tronca per un guasto.
    if (cancelled && !failed && text.isNotBlank() && settled) {
      Spacer(Modifier.height(6.dp))
      Text("Fermata qui.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    // I chip arrivano con il messaggio finale: sfumano dentro invece di comparire di colpo.
    AnimatedVisibility(
      visible = message.chips.isNotEmpty(),
      enter = if (reducedMotion) EnterTransition.None else fadeIn(FluidMotion.fadeIn()),
      exit = if (reducedMotion) ExitTransition.None else fadeOut(FluidMotion.fadeOut()),
    ) {
      Column {
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          message.chips.forEach { chip -> FluidChip(label = AssistantTexts.chipLabel(chip), selected = false, onClick = { actions.onChip(chip) }) }
        }
      }
    }
    // La riserva automatica e' accesa di default: un servizio diverso da quello scelto che risponde
    // senza dirlo e' un modello che cambia in silenzio. Dal `Done` (i cambi li porta lo stato) prima
    // ancora che arrivi la riga del run, poi da quella.
    val switches = run?.switches?.takeIf { it.isNotEmpty() } ?: (live as? AssistantState.Done)?.switches.orEmpty()
    val switchLine = if (!busy && !failed && text.isNotBlank()) AssistantTexts.switchLine(switches) else null
    if (switchLine != null) {
      Spacer(Modifier.height(8.dp))
      Text(switchLine, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    // Le azioni sfumano dentro a risposta finita; la misura la accompagna la colonna.
    AnimatedVisibility(
      visible = settled,
      enter = if (reducedMotion) EnterTransition.None else fadeIn(FluidMotion.fadeIn()),
      exit = if (reducedMotion) ExitTransition.None else fadeOut(FluidMotion.fadeOut()),
    ) {
      AnswerActionRow(
        message = message,
        run = run,
        text = text,
        failed = failed,
        retry = failed || cancelled || text.isBlank(),
        onDetails = { actions.onDetails(FailureReport.of(message, run, live)) },
        actions = actions,
      )
    }
  }
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
  val local = run?.outcome == LOCAL_OUTCOME || run?.outcome == STOPPED_OUTCOME
  val hasText = text.isNotBlank()
  Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
    message.version?.let { version -> VersionSwitcher(version, enabled = !actions.versionsLocked, onSelect = actions.onVersion) }
    // Una risposta fallita con qualcosa di scritto tiene Copia; Condividi resta a quelle riuscite,
    // cosi' con le frecce, "Riprova" e "Dettagli" la riga ci sta ancora su un telefono stretto.
    if (hasText && !(retry && message.version != null)) SmallAction(Icons.Rounded.ContentCopy, "Copia", { actions.onCopy(message) })
    if (hasText && !retry) SmallAction(Icons.Rounded.Share, "Condividi", { actions.onShare(message) })
    when {
      retry -> RegenerateAction("Riprova", actions.regenerateWith, !actions.locked, pill = true, onRegenerate = { actions.onRegenerate(message) }, onRegenerateWith = { actions.onRegenerateWith(message, it) })
      !local -> RegenerateAction("Rigenera", actions.regenerateWith, !actions.locked, pill = false, onRegenerate = { actions.onRegenerate(message) }, onRegenerateWith = { actions.onRegenerateWith(message, it) })
    }
    if (failed) {
      Spacer(Modifier.width(4.dp))
      PillAction(Icons.Rounded.Info, "Dettagli", onClick = onDetails)
    }
    if (!retry) run?.let {
      Spacer(Modifier.width(4.dp))
      Text(telemetry(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
    }
  }
}

/**
 * La risposta in corso quando la conversazione e' nuova e non c'e' ancora un messaggio su disco:
 * la domanda (dallo stato, finche' la riga non arriva) e sotto la riga di stato. Mostra anche il
 * testo di `Done`, `Failed` e `Cancelled`: fra la fine della risposta e il primo messaggio emesso
 * da Room passa un fotogramma, e in quel fotogramma il testo non deve sparire.
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
  val question = live.questionText()
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
    // La stessa bolla che arrivera' da Room un attimo dopo, nello stesso posto: il cambio non si vede.
    if (!question.isNullOrBlank()) {
      BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(
          text = question,
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onPrimaryContainer,
          modifier = Modifier
            .widthIn(max = maxWidth * 0.86f)
            .background(MaterialTheme.colorScheme.primaryContainer, ContinuousCornerShape(FluidRadius.Card))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        )
      }
    }
    Column(Modifier.fillMaxWidth()) {
      AssistantTexts.statusLine(live)?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = if (live is AssistantState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 8.dp))
      }
      if (text.isNotBlank()) ResponseBody(text, streaming = live is AssistantState.Answering, live = true, memo = memo)
      if (pending != null) ConfirmationRow(pending, onResolve)
    }
  }
}

/** La domanda che uno stato porta con se', se la porta (in ascolto e in trascrizione non c'e'). */
private fun AssistantState.questionText(): String? = when (this) {
  is AssistantState.Classifying -> question
  is AssistantState.Working -> question
  is AssistantState.WaitingRateLimit -> question
  is AssistantState.SwitchingProvider -> question
  is AssistantState.Answering -> question
  is AssistantState.AwaitingConfirmation -> question
  is AssistantState.Done -> question
  is AssistantState.Failed -> question
  is AssistantState.Cancelled -> question
  else -> null
}

/**
 * Le azioni sotto un messaggio: quarantotto dp di bersaglio, il minimo di Android per un dito
 * (erano 32, poi 44), e icone leggibili, non un grigio al 55% che si perdeva sul fondo. Spenta,
 * l'icona sbiadisce e il tocco non passa.
 */
@Composable
internal fun SmallAction(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
  Box(
    modifier = modifier
      .size(48.dp)
      .fluidPressable(onClick = onClick, enabled = enabled, role = Role.Button, haptic = null),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      icon,
      contentDescription = description,
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.size(18.dp).alpha(if (enabled) 1f else DisabledAlpha),
    )
  }
}

/**
 * Un'azione con la parola accanto all'icona ("Riprova", "Dettagli"): sotto una risposta fallita
 * l'icona da sola non basta a dire cosa fare. La pillola e' bassa come il chip del modello nel
 * composer, il bersaglio del tocco resta di quarantotto dp.
 */
@Composable
private fun PillAction(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
  Box(
    modifier = modifier
      .heightIn(min = 48.dp)
      .fluidPressable(onClick = onClick, enabled = enabled, role = Role.Button, haptic = null),
    contentAlignment = Alignment.Center,
  ) {
    Row(
      Modifier
        .height(32.dp)
        .alpha(if (enabled) 1f else DisabledAlpha)
        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), FluidCapsuleShape)
        .padding(start = 10.dp, end = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(16.dp))
      Spacer(Modifier.width(6.dp))
      Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
  }
}

/** Quanto sbiadisce un'azione spenta: si vede che c'e', e che adesso non si puo'. */
private const val DisabledAlpha = 0.38f

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
    PillAction(Icons.Rounded.Refresh, label, onClick = onClick, modifier = anchor, enabled = enabled)
  } else {
    SmallAction(Icons.Rounded.Refresh, description, onClick = onClick, modifier = anchor, enabled = enabled)
  }
}

internal fun copy(context: android.content.Context, text: String) {
  val clipboard = context.getSystemService(android.content.ClipboardManager::class.java) ?: return
  clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Aria", text))
  // Da Android 13 la conferma la mostra il sistema; prima no, e il tocco sembrava non fare niente.
  if (android.os.Build.VERSION.SDK_INT < 33) android.widget.Toast.makeText(context, "Copiato", android.widget.Toast.LENGTH_SHORT).show()
}

/** Condivide la risposta come testo semplice: gli asterischi e i cancelletti del Markdown in un messaggio sono rumore. */
internal fun share(context: android.content.Context, text: String) {
  val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
    type = "text/plain"
    putExtra(android.content.Intent.EXTRA_TEXT, MarkdownLite.plainText(text))
  }
  runCatching { context.startActivity(android.content.Intent.createChooser(intent, "Condividi la risposta").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
