package dev.pampa.pampai.feature.assistant.session

import androidx.compose.animation.AnimatedContent
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
import dev.pampa.pampai.feature.assistant.chat.MarkdownBody
import dev.pampa.pampai.feature.assistant.chat.RevealingParagraphs
import dev.pampa.pampai.feature.assistant.chat.RunSteps
import dev.pampa.pampai.feature.assistant.chat.streamingBlocks
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
  return live?.question()?.let { Exchange(key = "live", question = it) }
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
 * nuovo: un morph a ogni blocco di testo sarebbe un tremolio continuo. Solo se un viaggio e' gia'
 * in corso si ri-punta il traguardo, cosi' l'entrata non si tronca a meta'.
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
  speaking: Boolean,
  orb: Boolean,
  maxHeight: Dp,
  backdrop: GlassBackdropState,
  onResolve: (Long, Boolean) -> Unit,
  onChip: (AnswerChip) -> Unit,
  onExpand: () -> Unit,
  onStopSpeaking: () -> Unit,
) {
  val density = LocalDensity.current
  val reducedMotion = LocalFluidMotionPolicy.current.reducedMotion
  var size by remember { mutableStateOf(IntSize.Zero) }
  var entered by remember { mutableStateOf(false) }
  // Una goccia di un pixel: a questa taglia la superficie non disegna niente di leggibile, ed e'
  // il posto dove sta la card finche' non si sa quanto e' grande.
  val physics = rememberFluidPhysicsState(remember { FluidForm.circle(Offset(1f, 1f), 1f) })
  val sheetRadius = with(density) { FluidRadius.Sheet.toPx() }
  val seedHeight = with(density) { SeedHeight.toPx() }

  LaunchedEffect(size, orb) {
    if (size == IntSize.Zero || orb) return@LaunchedEffect
    val w = size.width.toFloat()
    val h = size.height.toFloat()
    val rest = FluidForm.Slab(Rect(0f, 0f, w, h), FluidCornerRadii.all(sheetRadius))
    when {
      !entered -> {
        physics.snapTo(FluidFormPresets.capsule(Rect(w * SeedLeft, h - seedHeight, w * SeedRight, h)))
        entered = true
        physics.morphTo(rest)
      }
      // Il viaggio e' ancora in corso: si ri-punta, non si ricomincia.
      physics.isMorphing -> physics.morphTo(rest)
      else -> physics.snapTo(rest)
    }
  }

  Column(
    Modifier
      .fillMaxWidth()
      .heightIn(max = maxHeight)
      .onSizeChanged { size = it }
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
        text = exchange.question,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
      )
      AnimatedContent(
        targetState = exchange,
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
          live = live.takeIf { shown.key == exchange.key },
          pending = pending,
          speaking = speaking,
          onResolve = onResolve,
          onChip = onChip,
          onStopSpeaking = onStopSpeaking,
        )
      }
    }
  }
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
 * Il corpo di uno scambio: la riga di stato, la conferma, il lavoro fatto, la risposta, i chip.
 *
 * Il testo che arriva passa da [RevealingParagraphs] (parola per parola, come nella chat); a fine
 * risposta lo sostituisce il Markdown vero, con gli stessi stili, e il salto non si vede.
 *
 * Il fondo ha ventidue dp di aria: la barra copre gli ultimi [CardOverlap] della
 * card, e senza quel margine l'ultima riga finirebbe sotto il vetro.
 */
@Composable
private fun ExchangeBody(
  exchange: Exchange,
  live: AssistantState?,
  pending: PendingConfirmation?,
  speaking: Boolean,
  onResolve: (Long, Boolean) -> Unit,
  onChip: (AnswerChip) -> Unit,
  onStopSpeaking: () -> Unit,
) {
  val scheme = MaterialTheme.colorScheme
  val typography = MaterialTheme.typography
  val scroll = rememberScrollState()
  val answer = exchange.assistant
  val partial = (live as? AssistantState.Answering)?.partial?.takeIf { it.isNotBlank() }
  val busy = live?.isBusy == true
  val streaming = answer == null || answer.status == MessageStatus.PENDING || answer.status == MessageStatus.STREAMING
  val status = live?.let { state ->
    AssistantTexts.statusLine(state, exchange.run?.provider)
      ?.takeIf { state.isBusy || state is AssistantState.Failed || state is AssistantState.Cancelled }
  }

  FollowAnswer(scroll, following = busy)

  Column(
    Modifier
      .animateContentSize(FluidMotion.snappy())
      .verticalScroll(scroll)
      .padding(horizontal = 16.dp)
      .padding(bottom = 22.dp),
  ) {
    if (status != null) {
      Text(
        text = status,
        style = typography.bodyMedium,
        color = if (live is AssistantState.Failed) scheme.error else scheme.primary,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(bottom = 6.dp),
      )
    }
    if (pending != null && live is AssistantState.AwaitingConfirmation) ConfirmationRow(pending, onResolve)
    exchange.run?.let { RunSteps(it) }
    when {
      streaming && partial != null -> RevealingParagraphs(streamingBlocks(partial, scheme, typography), animateFirst = true)
      answer != null && answer.text.isNotBlank() -> MarkdownBody(answer.text)
      answer?.status == MessageStatus.FAILED -> Text(
        text = answer.failureKind?.let { AssistantTexts.failure(it, provider = exchange.run?.provider) } ?: "Qualcosa e' andato storto.",
        style = typography.bodyMedium,
        color = scheme.error,
      )
      answer?.status == MessageStatus.CANCELLED -> Text("Fermata.", style = typography.bodyMedium, color = scheme.onSurfaceVariant)
    }
    val chips = answer?.chips.orEmpty()
    if (chips.isNotEmpty() || speaking) {
      Spacer(Modifier.height(10.dp))
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        chips.forEach { chip -> FluidChip(label = AssistantTexts.chipLabel(chip), selected = false, onClick = { onChip(chip) }) }
        if (speaking) FluidChip(label = "Zitta", selected = false, onClick = onStopSpeaking)
      }
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
