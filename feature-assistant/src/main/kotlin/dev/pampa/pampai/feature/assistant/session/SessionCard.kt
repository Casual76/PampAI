package dev.pampa.pampai.feature.assistant.session

import dev.pampa.pampai.feature.assistant.chat.ToolResultCards
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ai.orchestrator.AnswerChip
import dev.antigravity.fluidengine.ai.orchestrator.AssistantState
import dev.antigravity.fluidengine.ai.orchestrator.PendingConfirmation
import dev.antigravity.fluidengine.aiui.overlay.FluidConfirmationRow
import dev.pampa.pampai.feature.assistant.chat.AriaAssistantStrings
import dev.antigravity.fluidengine.ui.fluid.FluidChip
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.pampa.pampai.core.assistant.db.Message
import dev.pampa.pampai.core.assistant.db.MessageRole
import dev.pampa.pampai.core.assistant.db.MessageStatus
import dev.pampa.pampai.core.assistant.db.Run
import dev.pampa.pampai.feature.assistant.chat.AssistantTexts
import dev.pampa.pampai.feature.assistant.chat.ResponseBody
import dev.pampa.pampai.feature.assistant.chat.ResponseMemo
import dev.pampa.pampai.feature.assistant.chat.RunSteps
import dev.pampa.pampai.feature.assistant.chat.responseText
import kotlinx.coroutines.delay

/**
 * Uno scambio: la domanda e la risposta che le corrisponde, con la sua traccia.
 *
 * [key] e' l'identita' per il crossfade: cambia quando cambia la domanda, e solo allora. La
 * risposta che si forma dentro lo stesso scambio non deve far ripartire niente.
 */
internal class Exchange(
  val key: String,
  val question: String,
  val assistant: Message? = null,
  val run: Run? = null,
)

/**
 * Lo scambio da mostrare: l'ultima domanda dell'utente e la prima risposta che la segue.
 *
 * Si guarda il disco per primo, non lo stato vivo: durante un ascolto nuovo lo stato non porta
 * nessuna domanda, e cosi' resta a schermo la risposta di prima — come fa Siri, che non si svuota
 * appena la si richiama. Quando la domanda nuova arriva su disco (o, per una conversazione appena
 * nata, quando lo stato passa a `Working`) la chiave cambia e la card fa il crossfade.
 */
internal fun currentExchange(messages: List<Message>, runs: List<Run>, live: AssistantState?): Exchange? {
  val lastUser = messages.indexOfLast { it.role == MessageRole.USER }
  if (lastUser >= 0) {
    val user = messages[lastUser]
    val assistant = messages.drop(lastUser + 1).firstOrNull { it.role == MessageRole.ASSISTANT }
    return Exchange(
      key = "m${user.id}",
      question = user.text,
      assistant = assistant,
      run = assistant?.let { answer -> runs.firstOrNull { it.messageId == answer.id } },
    )
  }
  // Niente su disco: la domanda esiste solo dentro lo stato (la riga la scrive il runtime poco dopo).
  return live?.question()?.let { Exchange(key = LiveKey, question = it) }
}

/** Quante domande vengono prima di quella in mostra: la pillola "N precedenti". */
internal fun earlierExchanges(messages: List<Message>): Int =
  (messages.count { it.role == MessageRole.USER } - 1).coerceAtLeast(0)

/** La domanda che uno stato porta con se', se la porta (in ascolto e in trascrizione non c'e'). */
private fun AssistantState.question(): String? = when (this) {
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

/** La chiave dello scambio che esiste solo nello stato vivo. */
private const val LiveKey = "live"

/**
 * La chiave del crossfade, ferma quando lo scambio nato dallo stato arriva su disco.
 *
 * In una conversazione nuova la prima domanda compare con la chiave [LiveKey] e, un attimo dopo,
 * con quella del messaggio salvato: e' **lo stesso scambio**, e un crossfade fra due corpi
 * identici si vede come un calo di contrasto (e butta via lo scorrimento e la memoria della
 * risposta). Qui la chiave del disco viene adottata come alias di [LiveKey] finche' la domanda
 * e' quella; alla domanda successiva le chiavi tornano quelle vere.
 */
private class ExchangeKeys {
  private var liveQuestion: String? = null
  private var adopted: String? = null

  fun of(exchange: Exchange): String {
    val key = exchange.key
    return when {
      key == LiveKey -> {
        liveQuestion = exchange.question
        adopted = null
        LiveKey
      }
      key == adopted -> LiveKey
      adopted == null && liveQuestion != null && exchange.question == liveQuestion -> {
        adopted = key
        LiveKey
      }
      else -> {
        liveQuestion = null
        key
      }
    }
  }
}

/** Content-sized answer viewport. Its parent measures the fixed controls before this region. */
@Composable
internal fun SessionCard(
  exchange: Exchange,
  live: AssistantState?,
  pending: PendingConfirmation?,
  orb: Boolean,
  maxHeight: Dp,
  backdrop: GlassBackdropState,
  onResolve: (Long, Boolean) -> Unit,
  onChip: (AnswerChip) -> Unit,
  onExpand: () -> Unit,
) {
  val keys = remember { ExchangeKeys() }
  val keyed = Exchange(keys.of(exchange), exchange.question, exchange.assistant, exchange.run)
  Column(Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
    Text(keyed.question, style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
      overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    // The parent reserves the header and composer first; only the answer scrolls.
    Box(Modifier.weight(1f, fill = false)) {
      key(keyed.key) { ExchangeBody(keyed, live, pending, onResolve, onChip) }
    }
  }
}

/**
 * Cosa sta sopra la risposta: la riga di stato mentre lavora, poi la traccia di cosa ha fatto.
 *
 * Un posto solo per le due, perche' arrivano insieme: a fine risposta la riga di stato sparisce
 * (`Done` non ne ha) e la traccia compare (la riga del run si scrive alla fine). Due blocchi
 * separati erano un salto doppio del testo sotto — su di una riga, giu' di un chip.
 */
private sealed interface Header {
  /** Quale posto occupa: il crossfade scatta fra posti diversi, non a ogni nuovo testo di stato. */
  val slot: Int

  data class Status(val text: String, val error: Boolean) : Header {
    override val slot = 0
  }

  data class Steps(val run: Run) : Header {
    override val slot = 1
  }

  data object None : Header {
    override val slot = 2
  }
}

/** Il testo di stato piu' recente di uno scambio, per tenerlo al suo posto finche' arriva la traccia. */
private class StatusMemo {
  var last: String? = null
}

/**
 * Il corpo di uno scambio: la riga di stato (o la traccia), la conferma, la risposta, i chip.
 *
 * La risposta passa da [ResponseBody] con [responseText], gli stessi della chat: a `Done` il testo
 * e' quello dello stato, non `message.text` — che per un fotogramma e' ancora il parziale salvato
 * fino a 300 ms prima, e a fine risposta le ultime parole sparivano e ricomparivano (o, in una
 * conversazione nuova senza messaggio su disco, spariva tutto). I blocchi dello streaming li
 * ricorda [ResponseBody]: non si rianalizzano a ogni ricomposizione.
 *
 * Il fondo ha ventidue dp di aria: la barra copre gli ultimi the composer della
 * card, e senza quel margine l'ultima riga finirebbe sotto il vetro.
 */
@Composable
private fun ExchangeBody(
  exchange: Exchange,
  live: AssistantState?,
  pending: PendingConfirmation?,
  onResolve: (Long, Boolean) -> Unit,
  onChip: (AnswerChip) -> Unit,
) {
  val scheme = MaterialTheme.colorScheme
  val typography = MaterialTheme.typography
  val scroll = rememberScrollState()
  val answer = exchange.assistant
  val busy = live?.isBusy == true
  val text = responseText(answer, live)
  val memo = remember { ResponseMemo(freshStart = text.isBlank()) }
  val status = live?.let { state ->
    AssistantTexts.statusLine(state, exchange.run?.provider)
      ?.takeIf { state.isBusy || state is AssistantState.Failed || state is AssistantState.Cancelled }
  }

  // Fra `Done` e la riga del run che arriva da Room passano pochi fotogrammi: la riga di stato
  // resta li' (al massimo RunHoldMillis) invece di chiudersi e riaprirsi come traccia.
  val statusMemo = remember { StatusMemo() }
  if (status != null) statusMemo.last = status
  val awaitingRun = live is AssistantState.Done && exchange.run == null
  var holdOver by remember { mutableStateOf(false) }
  LaunchedEffect(awaitingRun) {
    if (!awaitingRun) return@LaunchedEffect
    delay(RunHoldMillis)
    holdOver = true
  }
  val header = when {
    status != null -> Header.Status(status, error = live is AssistantState.Failed)
    exchange.run != null -> Header.Steps(exchange.run)
    awaitingRun && !holdOver -> statusMemo.last?.let { Header.Status(it, error = false) } ?: Header.None
    else -> Header.None
  }

  FollowAnswer(scroll, following = busy)

  Column(
    Modifier
      .then(if (LocalFluidMotionPolicy.current.reducedMotion) Modifier else Modifier.animateContentSize(FluidMotion.snappy()))
      .verticalScroll(scroll)
      .padding(horizontal = 16.dp)
      .padding(bottom = 22.dp),
  ) {
    ExchangeHeader(header)
    if (pending != null && live is AssistantState.AwaitingConfirmation) FluidConfirmationRow(pending, onConfirm = { onResolve(pending.id, it) }, strings = AriaAssistantStrings, modifier = Modifier.padding(top = 8.dp))
    when {
      // `live = false`: la crescita la anima gia' la colonna qui sopra; una seconda molla dentro la
      // prima farebbe inseguire alla card un'altezza che si muove, e la card resterebbe indietro.
      text.isNotBlank() -> ResponseBody(text, streaming = live is AssistantState.Answering, live = false, memo = memo)
      answer?.status == MessageStatus.FAILED -> Text(
        text = answer.failureKind?.let { AssistantTexts.failure(it, provider = exchange.run?.provider, tried = AssistantTexts.tried(exchange.run?.switches.orEmpty())) } ?: "Qualcosa e' andato storto.",
        style = typography.bodyMedium,
        color = scheme.error,
      )
      answer?.status == MessageStatus.CANCELLED -> Text("Fermata.", style = typography.bodyMedium, color = scheme.onSurfaceVariant)
    }
    // I chip arrivano con il messaggio finale: sfumano dentro invece di comparire di colpo. "Zitta"
    // non sta qui: e' il tasto tondo della barra, uno solo.
    ToolResultCards(answer?.receipts.orEmpty())
    val chips = answer?.chips.orEmpty()
    AnimatedVisibility(
      visible = chips.isNotEmpty(),
      enter = fadeIn(FluidMotion.fadeIn()),
      exit = fadeOut(FluidMotion.fadeOut()),
    ) {
      Column {
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          chips.forEach { chip -> FluidChip(label = AssistantTexts.chipLabel(chip), selected = false, onClick = { onChip(chip) }) }
        }
      }
    }
  }
}

/**
 * Il posto sopra la risposta. Il passaggio fra stato e traccia e' un crossfade con la misura
 * animata: il testo sotto scivola di quanto il chip e' piu' alto della riga, non ci salta.
 */
@Composable
private fun ExchangeHeader(header: Header) {
  val reducedMotion = LocalFluidMotionPolicy.current.reducedMotion
  val scheme = MaterialTheme.colorScheme
  val typography = MaterialTheme.typography
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
    label = "exchangeHeader",
  ) { shown ->
    when (shown) {
      is Header.Status -> Text(
        text = shown.text,
        style = typography.bodyMedium,
        color = if (shown.error) scheme.error else scheme.primary,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(bottom = 6.dp),
      )
      is Header.Steps -> RunSteps(shown.run)
      Header.None -> Unit
    }
  }
}

/**
 * Il corpo che segue la risposta mentre si forma: ogni [FollowIntervalMillis] torna in fondo, ma
 * solo se ci si era. Chi e' risalito a rileggere resta dov'e' e si riaggancia quando torna lui in
 * fondo — la stessa regola della lista della chat, con lo scroll di una colonna invece che di una
 * `LazyColumn`.
 */
@Composable
private fun FollowAnswer(scroll: ScrollState, following: Boolean) {
  val reducedMotion = LocalFluidMotionPolicy.current.reducedMotion
  LaunchedEffect(scroll, following, reducedMotion) {
    if (!following) return@LaunchedEffect
    var follow = true
    var last = scroll.value
    while (true) {
      delay(FollowIntervalMillis)
      if (scroll.value < last - FollowSlackPx) follow = false
      if (scroll.value >= scroll.maxValue - FollowSlackPx) follow = true
      if (follow && scroll.value < scroll.maxValue) {
        if (reducedMotion) scroll.scrollTo(scroll.maxValue) else scroll.animateScrollTo(scroll.maxValue, FluidMotion.snappy())
      }
      last = scroll.value
    }
  }
}

/** Il seme del morph: una capsula bassa e stretta sul bordo inferiore della card. */
private val SeedHeight = 8.dp
private const val SeedLeft = 0.28f
private const val SeedRight = 0.72f

/** Quanto va trascinata in alto la maniglia perche' la sessione diventi l'app. */
private val ExpandDragThreshold = 96.dp

/** Ogni quanto si torna in fondo, e quanti pixel di gioco prima di dire "l'utente e' risalito". */
private const val FollowIntervalMillis = 120L
private const val FollowSlackPx = 24

/** Quanto la riga di stato aspetta la traccia dopo `Done`, prima di chiudersi da sola. */
private const val RunHoldMillis = 400L
