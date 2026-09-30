package dev.pampa.pampai.feature.assistant.session

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
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ai.orchestrator.AnswerChip
import dev.antigravity.fluidengine.ai.orchestrator.AssistantState
import dev.antigravity.fluidengine.ai.orchestrator.PendingConfirmation
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidChip
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassRole
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.fluidphysics.FluidCornerRadii
import dev.antigravity.fluidengine.ui.fluidphysics.FluidForm
import dev.antigravity.fluidengine.ui.fluidphysics.FluidFormPresets
import dev.antigravity.fluidengine.ui.fluidphysics.FluidPhysicsContentRole
import dev.antigravity.fluidengine.ui.fluidphysics.FluidPhysicsTier
import dev.antigravity.fluidengine.ui.fluidphysics.fluidPhysicsClip
import dev.antigravity.fluidengine.ui.fluidphysics.fluidPhysicsContent
import dev.antigravity.fluidengine.ui.fluidphysics.fluidPhysicsSurface
import dev.antigravity.fluidengine.ui.fluidphysics.rememberFluidPhysicsState
import dev.pampa.pampai.core.assistant.db.Message
import dev.pampa.pampai.core.assistant.db.MessageRole
import dev.pampa.pampai.core.assistant.db.MessageStatus
import dev.pampa.pampai.core.assistant.db.Run
import dev.pampa.pampai.feature.assistant.chat.AssistantTexts
import dev.pampa.pampai.feature.assistant.chat.ConfirmationRow
import dev.pampa.pampai.feature.assistant.chat.ResponseBody
import dev.pampa.pampai.feature.assistant.chat.ResponseMemo
import dev.pampa.pampai.feature.assistant.chat.RunSteps
import dev.pampa.pampai.feature.assistant.chat.responseText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

/**
 * La risposta che galleggia sopra la barra: **uno scambio alla volta**, non un pannello di chat.
 *
 * E' il secondo nodo Fluid-physics della sessione (il primo e' la barra): nasce come una capsula
 * di otto dp larga meno di meta' card, appoggiata sul proprio bordo inferiore — che sta
 * [CardOverlap] dp **sotto** la barra, quindi invisibile — e da li' morfa nella
 * lastra. Il seme nascosto e' il punto: la card sembra uscire dalla capsula.
 *
 * Perche' non un semplice `heightIn` animato o un `AnimatedVisibility`: il vetro in movimento ha
 * tre trappole (`engine/skill/fluid-engine/references/regole.md`), e la prima e' che scalare o
 * ritagliare in altezza il layer di una superficie di vetro trascina anche il fondale campionato
 * dentro — il testo dell'app sotto si muoverebbe, e quel testo sta fermo. La sagoma si trasforma
 * nella geometria, mai nella trasformazione del nodo.
 *
 * La crescita a riposo (la risposta che arriva e allunga la card) e' uno **snap**, non un morph
 * nuovo: un morph a ogni blocco di testo sarebbe un tremolio continuo. E lo snap avviene nella
 * stessa passata di layout che cambia la misura (`onSizeChanged`), non in un effetto: un
 * `LaunchedEffect(size)` ripartiva a ogni fotogramma di `animateContentSize` e arrivava un
 * fotogramma dopo, con il testo gia' disegnato fuori dal vetro. Solo se un viaggio e' gia' in corso
 * si ri-punta il traguardo, cosi' l'entrata non si tronca a meta'.
 *
 * @param orb vero finche' la barra e' ancora l'orb: l'entrata della card aspetta che la capsula
 *   sia posata, altrimenti nasce da un seme che non e' ancora sotto niente.
 * @param maxHeight quanto puo' alzarsi: oltre, il corpo scorre.
 */
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
  val density = LocalDensity.current
  val reducedMotion = LocalFluidMotionPolicy.current.reducedMotion
  val scope = rememberCoroutineScope()
  // La misura piu' recente, fuori dallo snapshot: la leggono l'entrata e il layout, mai la
  // composizione.
  val measured = remember { MeasuredSize() }
  var sized by remember { mutableStateOf(false) }
  var entered by remember { mutableStateOf(false) }
  // Una goccia di un pixel: a questa taglia la superficie non disegna niente di leggibile, ed e'
  // il posto dove sta la card finche' non si sa quanto e' grande.
  val physics = rememberFluidPhysicsState(remember { FluidForm.circle(Offset(1f, 1f), 1f) })
  val sheetRadius = with(density) { FluidRadius.Sheet.toPx() }
  val seedHeight = with(density) { SeedHeight.toPx() }
  val restOf: (IntSize) -> FluidForm = remember(sheetRadius) {
    { size -> FluidForm.Slab(Rect(0f, 0f, size.width.toFloat(), size.height.toFloat()), FluidCornerRadii.all(sheetRadius)) }
  }

  // L'entrata: una volta sola, quando la capsula si e' posata e la card ha una misura. Non dipende
  // dalla misura in se': i cambi successivi li segue `onSizeChanged`, senza far ripartire niente.
  LaunchedEffect(orb, sized) {
    if (orb || !sized || entered) return@LaunchedEffect
    val size = measured.size
    val w = size.width.toFloat()
    val h = size.height.toFloat()
    physics.snapTo(FluidFormPresets.capsule(Rect(w * SeedLeft, h - seedHeight, w * SeedRight, h)))
    entered = true
    physics.morphTo(restOf(size))
  }

  val keys = remember { ExchangeKeys() }
  val keyed = Exchange(keys.of(exchange), exchange.question, exchange.assistant, exchange.run)

  Column(
    Modifier
      .fillMaxWidth()
      .heightIn(max = maxHeight)
      .onSizeChanged { size ->
        measured.size = size
        if (size == IntSize.Zero) return@onSizeChanged
        if (!sized) sized = true
        if (!entered) return@onSizeChanged
        val rest = restOf(size)
        // Siamo in fase di layout: lo snap scrive la forma prima del disegno di questo stesso
        // fotogramma, e il vetro e il testo cambiano misura insieme. Durante l'entrata si
        // ri-punta con una molla critica (una nuova rincorsa lenta a ogni fotogramma di crescita
        // fermerebbe l'entrata a meta'); il contenuto, nel frattempo, e' ritagliato sulla sagoma.
        if (physics.isMorphing) scope.launch { physics.morphTo(rest, FluidMotion.snappy()) } else physics.snapTo(rest)
      }
      .fluidPhysicsSurface(
        state = physics,
        backdrop = backdrop,
        tint = GlassDefaults.floatingTint(),
        role = GlassRole.Floating,
        tier = FluidPhysicsTier.Balanced,
      ),
  ) {
    Column(
      Modifier
        // Il cancello della seconda trappola: prima che il seme sia posato il contenuto e' gia'
        // impaginato a taglia piena, e un solo fotogramma disegnato li' e' la card intera che
        // lampeggia prima del morph. A `entered` falso non si registra niente.
        .drawWithContent { if (entered) drawContent() }
        .fluidPhysicsClip(physics, active = { physics.isMorphing })
        .fluidPhysicsContent(physics, FluidPhysicsContentRole.Incoming),
    ) {
      CardHandle(onExpand)
      Text(
        text = keyed.question,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
      )
      AnimatedContent(
        targetState = keyed,
        contentKey = { it.key },
        modifier = Modifier.weight(1f, fill = false),
        transitionSpec = {
          if (reducedMotion) {
            EnterTransition.None togetherWith ExitTransition.None
          } else {
            fadeIn(FluidMotion.crossFade()) togetherWith fadeOut(FluidMotion.crossFade()) using
              SizeTransform(clip = false) { _, _ -> FluidMotion.snappy() }
          }
        },
        label = "sessionExchange",
      ) { shown ->
        // Lo stato vivo appartiene allo scambio in arrivo: quello che sta uscendo si porta via la
        // sua ultima riga invece di prendere in prestito quella del successivo.
        ExchangeBody(
          exchange = shown,
          live = live.takeIf { shown.key == keyed.key },
          pending = pending,
          onResolve = onResolve,
          onChip = onChip,
        )
      }
    }
  }
}

/** L'ultima misura della card; un contenitore semplice perche' si scrive in layout e si legge in un effetto. */
private class MeasuredSize {
  var size: IntSize = IntSize.Zero
}

/**
 * La maniglia: si trascina in alto (o si tocca) per continuare la stessa conversazione nell'app.
 *
 * Niente scala alla pressione: la superficie sotto e' vetro, e scalare il nodo di una superficie
 * di vetro muove anche il fondale campionato (prima trappola).
 */
@Composable
private fun CardHandle(onExpand: () -> Unit) {
  val threshold = with(LocalDensity.current) { ExpandDragThreshold.toPx() }
  var dragged by remember { mutableStateOf(0f) }
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .pointerInput(Unit) {
        detectVerticalDragGestures(
          onDragStart = { dragged = 0f },
          onDragEnd = { if (dragged < -threshold) onExpand() },
          onVerticalDrag = { change, dy -> dragged += dy; change.consume() },
        )
      }
      .fluidPressable(onClick = onExpand, pressedScale = 1f, role = Role.Button, haptic = null)
      .padding(horizontal = 16.dp, vertical = 10.dp),
  ) {
    Box(
      Modifier
        .size(width = 36.dp, height = 4.dp)
        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f), ContinuousCornerShape(2.dp)),
    )
    Spacer(Modifier.width(10.dp))
    Text(
      text = "Apri in PampAI",
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.weight(1f),
    )
    Icon(
      imageVector = Icons.Rounded.KeyboardArrowUp,
      contentDescription = "Apri in PampAI",
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.size(20.dp),
    )
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
 * Il fondo ha ventidue dp di aria: la barra copre gli ultimi [CardOverlap] della
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
      .animateContentSize(FluidMotion.snappy())
      .verticalScroll(scroll)
      .padding(horizontal = 16.dp)
      .padding(bottom = 22.dp),
  ) {
    ExchangeHeader(header)
    if (pending != null && live is AssistantState.AwaitingConfirmation) ConfirmationRow(pending, onResolve)
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
  LaunchedEffect(scroll, following) {
    if (!following) return@LaunchedEffect
    var follow = true
    var last = scroll.value
    while (true) {
      delay(FollowIntervalMillis)
      if (scroll.value < last - FollowSlackPx) follow = false
      if (scroll.value >= scroll.maxValue - FollowSlackPx) follow = true
      if (follow && scroll.value < scroll.maxValue) scroll.animateScrollTo(scroll.maxValue, FluidMotion.snappy())
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
