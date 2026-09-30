package dev.pampa.pampai.feature.assistant.session

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ai.orchestrator.AnswerChip
import dev.antigravity.fluidengine.ai.orchestrator.AssistantState
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidChip
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassRole
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.glassSurface
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.pampa.pampai.core.assistant.attachments.PendingAttachment
import dev.pampa.pampai.feature.assistant.halo.HaloColours
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Cosa l'overlay chiede alla sessione: chiudersi, aprirsi nell'app, aprire le impostazioni dell'assistente, un chip. */
class SessionActions(
  val hide: () -> Unit,
  val expand: (conversationId: Long?) -> Unit,
  val openAssistSettings: () -> Unit,
  val chip: (AnswerChip) -> Unit,
)

/**
 * La sessione sopra un'altra app: **un orb, non un pannello**.
 *
 * Prima era una chat in miniatura — un rettangolo di vetro con gli ultimi sei messaggi appoggiato
 * sull'app di qualcun altro. Adesso e' come Siri: in basso c'e' solo l'orb, che diventa la barra a
 * cui si parla; la risposta galleggia sopra come una card leggera, **uno scambio alla volta**, e
 * l'alone lungo i bordi dello schermo dice che Aria e' li'. Chi vuole la conversazione intera
 * trascina la maniglia in alto e si ritrova nell'app, sulla stessa conversazione.
 *
 * Gli strati, dal fondo:
 *  1. lo screenshot sotto uno scrim che scurisce, l'unica cosa che il vetro campiona (finestre
 *     diverse non si vedono: da qui il "vetro discreto"). Un tocco fuori chiude, un trascinamento
 *     ritaglia una porzione di schermo da allegare.
 *  2. l'alone lungo il perimetro ([AriaHalo]), che legge il microfono per conto suo.
 *  3. la pila in basso ([SessionStack]): la card **dietro**, e davanti la nota, i chip degli
 *     allegati e la barra.
 *
 * L'ordine di disegno della pila e' il trucco dell'entrata: il fondo della card passa
 * [CardOverlap] dp sotto la capsula, quindi il seme da cui nasce il morph — una capsula di otto dp
 * sul suo bordo inferiore — e' gia' coperto dalla barra quando parte. La card sembra uscire da li'.
 *
 * **Il primo fotogramma.** La composizione sopravvive fra un'apparizione e l'altra, ferma (il ciclo
 * di vita dell'host e' in pausa mentre la sessione e' nascosta). Alla riapertura, finche' non ha
 * raggiunto l'apparizione nuova ([SessionController.freshStamp]) non si disegna niente: il
 * fotogramma che passerebbe e' quello della volta prima — la capsula, la card, lo screenshot
 * dell'app di prima sotto lo scrim pieno.
 */
@Composable
fun PampaiSessionOverlay(controller: SessionController, actions: SessionActions) {
  val state by controller.state.collectAsStateWithLifecycle()
  val screen by controller.screenState.collectAsStateWithLifecycle()
  val partial by controller.partial.collectAsStateWithLifecycle()
  val pending by controller.pendingConfirmation.collectAsStateWithLifecycle()
  val speaking by controller.speaking.collectAsStateWithLifecycle()
  val conversationId by controller.conversationId.collectAsStateWithLifecycle()
  val messages by controller.messages.collectAsStateWithLifecycle()
  val runs by controller.runs.collectAsStateWithLifecycle()
  val attachments by controller.attachments.collectAsStateWithLifecycle()
  val textMode by controller.textMode.collectAsStateWithLifecycle()
  val selecting by controller.selecting.collectAsStateWithLifecycle()
  val notice by controller.notice.collectAsStateWithLifecycle()
  // Stati di snapshot, non flussi: al primo fotogramma sono gia' quelli dell'apparizione corrente.
  val shownStamp = controller.shownStamp
  val freshStamp = controller.freshStamp
  val reducedMotion = LocalFluidMotionPolicy.current.reducedMotion

  val backdrop = rememberGlassBackdrop()
  // Lo scrim riparte da zero a ogni apparizione partita da nascosta (e' questo il suo "reset alla
  // chiusura": mentre e' nascosta la composizione e' ferma e non vedrebbe niente). Una seconda
  // pressione a overlay aperto non cambia [freshStamp], e lo sfondo non lampeggia. Si legge solo
  // nel disegno: la sua dissolvenza non ricompone l'overlay.
  val scrim = remember(freshStamp) { Animatable(0f) }
  LaunchedEffect(scrim) {
    scrim.animateTo(ScrimAlpha, spring(dampingRatio = FluidMotion.DampingStandard, stiffness = FluidMotion.ResponseSmooth))
  }
  var selection by remember { mutableStateOf<Rect?>(null) }

  Box(
    Modifier
      .fillMaxSize()
      // Il layer serve al cancello: la lettura di [SessionController.freshStamp] dentro il disegno
      // e' osservata da lui, quindi la scrittura in `onShow` lo invalida subito.
      .graphicsLayer { }
      .drawWithContent { if (shownStamp >= controller.freshStamp) drawContent() },
  ) {
    // 1. Lo sfondo: cio' che il vetro guarda. Lo screenshot e' identico a cio' che c'e' sotto la
    //    finestra, ma solo cosi' la barra ha qualcosa da rifrangere; senza, il vetro e' una tinta.
    Box(
      Modifier
        .fillMaxSize()
        // Mai congelato. Una registrazione sola per apparizione partiva prima che lo screenshot fosse
        // disegnato, e il vetro non aveva niente da sfocare: dietro la card si leggevano le etichette
        // delle icone della home. Il costo e' piccolo: questo nodo si ridisegna solo quando cambiano
        // screenshot, scrim o selezione, non quando si muovono la barra o la card.
        .glassBackdropSource(backdrop)
        .pointerInput(screen.screenshot != null) {
          detectTapGestures(onTap = { if (!controller.selecting.value) actions.hide() else controller.selecting.value = false })
        }
        .pointerInput(screen.screenshot != null) {
          if (screen.screenshot == null) return@pointerInput
          detectDragGestures(
            onDragStart = { start ->
              controller.selecting.value = true
              selection = Rect(start, Size.Zero)
            },
            onDrag = { change, _ ->
              val current = selection ?: return@detectDragGestures
              selection = Rect(current.topLeft, change.position)
              change.consume()
            },
            onDragEnd = {
              val rect = selection?.normalized()
              selection = null
              controller.selecting.value = false
              if (rect != null) controller.attachCrop(android.graphics.Rect(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt()))
            },
            onDragCancel = {
              selection = null
              controller.selecting.value = false
            },
          )
        },
    ) {
      screen.screenshot?.let { ScreenImage(it) }
      Box(
        Modifier
          .fillMaxSize()
          .drawBehind {
            val amount = scrim.value
            // A zero non si registra niente: la seconda trappola del vetro in movimento.
            if (amount > 0.004f) drawRect(Color.Black, alpha = amount)
          },
      )
      selection?.normalized()?.let { rect -> SelectionMarquee(rect, MaterialTheme.colorScheme.primary) }
    }

    // 2. L'alone lungo il bordo (legge il microfono dentro il suo loop di frame: il livello a
    //    cinquanta hertz non ricompone niente, nemmeno l'alone).
    HaloLayer(controller, mood = if (selecting) HaloMood.IDLE else state.haloMood())

    // 3. La pila in basso. Resta composta anche mentre si ritaglia lo schermo: toglierla e
    //    rimetterla rifaceva da capo l'entrata dell'orb e della card. Durante la selezione sfuma
    //    via e non riceve tocchi.
    val stackAlpha = animateFloatAsState(
      targetValue = if (selecting) 0f else 1f,
      animationSpec = when {
        reducedMotion -> snap()
        selecting -> FluidMotion.fadeOut()
        else -> FluidMotion.fadeIn()
      },
      label = "sessionStack",
    )
    BoxWithConstraints(
      Modifier
        .align(Alignment.BottomCenter)
        .fillMaxWidth()
        // Solo alpha, mai scala: e' un layer con del vetro dentro (prima trappola).
        .graphicsLayer { alpha = stackAlpha.value }
        .drawWithContent { if (stackAlpha.value > 0.004f) drawContent() }
        // Solo mentre si ritaglia: il dito che ritaglia e' partito dallo sfondo e i suoi eventi non
        // passano di qui, questo ferma un secondo dito sui tasti invisibili. Sempre presente, il
        // nodo prenderebbe anche i tocchi sui margini trasparenti della pila, che devono arrivare
        // allo sfondo (tocco fuori = chiudi, trascinamento = ritaglio).
        .then(if (selecting) Modifier.pointerInput(Unit) { consumeEverything() } else Modifier)
        .navigationBarsPadding()
        .imePadding()
        .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
      val cardMax = maxHeight * CardHeightShare
      val live = state.takeIf { it != AssistantState.Idle }
      // Solo le righe della conversazione della sessione: Room emette un giro dopo il cambio di
      // conversazione, e in quel giro la card mostrerebbe lo scambio (e la pillola) di quella prima.
      val shownMessages = remember(messages, conversationId) { messages.filter { it.conversationId == conversationId } }
      val shownRuns = remember(runs, conversationId) { runs.filter { it.conversationId == conversationId } }
      val exchange = remember(shownMessages, shownRuns, live) { currentExchange(shownMessages, shownRuns, live) }
      val earlier = remember(shownMessages) { earlierExchanges(shownMessages) }
      // L'entrata: prima l'orb diventa capsula, e solo dopo la card esce dal suo seme. Lo stato
      // nasce vero nella stessa composizione che vede lo stamp nuovo.
      var orb by remember(shownStamp) { mutableStateOf(true) }
      LaunchedEffect(shownStamp) {
        delay(OrbMillis)
        orb = false
      }

      SessionStack(Modifier.align(Alignment.BottomCenter)) {
        // Dietro: la card, con sopra la pillola.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          if (exchange != null) {
            // Esce con la card, non prima: durante l'orb la card non si vede ancora, e la pillola
            // galleggerebbe da sola. Legata all'apparizione, cosi' non sfuma via con il numero di
            // una conversazione che non c'e' piu'.
            key(shownStamp) {
              AnimatedVisibility(
                visible = earlier > 0 && !orb,
                enter = fadeIn(FluidMotion.fadeIn()),
                exit = fadeOut(FluidMotion.fadeOut()),
              ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                  EarlierPill(earlier.coerceAtLeast(1)) { actions.expand(controller.conversationId.value) }
                  Spacer(Modifier.height(8.dp))
                }
              }
              // Ogni volta che la sessione si mostra l'entrata si rifa' da capo.
              SessionCard(
                exchange = exchange,
                live = live,
                pending = pending,
                orb = orb,
                maxHeight = cardMax,
                backdrop = backdrop,
                onResolve = controller::resolve,
                onChip = actions.chip,
                onExpand = { actions.expand(controller.conversationId.value) },
              )
            }
          }
        }

        // Davanti, quindi disegnata sopra: e' la barra a coprire il fondo della card, e con esso
        // il seme del morph.
        Column {
          SessionNotice(
            notice = notice,
            live = live,
            onDismiss = controller::dismissNotice,
            onOpenAssistSettings = actions.openAssistSettings,
          )
          if (attachments.isNotEmpty()) AttachmentChips(attachments, backdrop, controller::removeAttachment)
          SessionBar(
            stamp = shownStamp,
            state = state,
            textMode = textMode,
            partial = partial,
            speaking = speaking,
            hasAttachments = attachments.isNotEmpty(),
            orb = orb,
            backdrop = backdrop,
            micLevel = controller.micLevel,
            onAsk = controller::ask,
            onVoice = controller::startVoice,
            onStopVoice = controller::stopVoice,
            onVoiceToText = controller::voiceToText,
            onStop = controller::cancel,
            onStopSpeaking = controller::stopSpeaking,
            onAttachScreen = { controller.attachScreen() },
          )
        }
      }
    }

    if (selecting) {
      Text(
        "Trascina per scegliere la porzione di schermo",
        style = MaterialTheme.typography.labelLarge,
        color = Color.White,
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp),
      )
    }
  }
}

/**
 * La pila in basso: la card dietro, la roba davanti (nota, allegati, barra) sopra, con il fondo
 * della card [CardOverlap] dp sotto il bordo alto della roba davanti. Il primo figlio e' la card,
 * il secondo la roba davanti; si disegnano in quest'ordine.
 *
 * Un layout solo, non un padding preso da `onSizeChanged`: quello arrivava un fotogramma dopo la
 * misura, e mentre la nota o i chip degli allegati entravano la card restava indietro — un filo di
 * bordo fra le due superfici che compariva e spariva. Qui la card si posa nella stessa passata in
 * cui si misura cio' che le sta davanti.
 */
@Composable
private fun SessionStack(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  Layout(content = content, modifier = modifier) { measurables, constraints ->
    val loose = constraints.copy(minWidth = 0, minHeight = 0)
    val front = measurables[1].measure(loose)
    val lift = (front.height - CardOverlap.roundToPx()).coerceAtLeast(0)
    val backMax = if (loose.hasBoundedHeight) (loose.maxHeight - lift).coerceAtLeast(0) else Constraints.Infinity
    val back = measurables[0].measure(loose.copy(maxHeight = backMax))
    val width = maxOf(front.width, back.width).coerceIn(constraints.minWidth, constraints.maxWidth)
    val height = maxOf(front.height, back.height + lift).coerceIn(constraints.minHeight, constraints.maxHeight)
    layout(width, height) {
      back.place((width - back.width) / 2, height - lift - back.height)
      front.place((width - front.width) / 2, height - front.height)
    }
  }
}

/** Prende ogni evento prima dei figli: i loro riconoscitori ignorano i tocchi gia' consumati. */
private suspend fun PointerInputScope.consumeEverything() {
  awaitPointerEventScope {
    while (true) {
      awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
    }
  }
}

/**
 * "N precedenti": la sessione mostra uno scambio, ma dice che ce n'erano altri. Toccarla apre
 * l'app, che li ha tutti.
 */
@Composable
private fun EarlierPill(count: Int, onClick: () -> Unit) {
  Text(
    text = if (count == 1) "1 precedente" else "$count precedenti",
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier
      .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f), ContinuousCornerShape(FluidRadius.Control))
      .fluidPressable(onClick = onClick, role = Role.Button, haptic = null)
      .padding(horizontal = 10.dp, vertical = 4.dp),
  )
}

/**
 * Gli allegati in attesa, sopra la barra. Il vetro anche qui: senza, i chip stanno su un pezzo
 * qualunque dell'app sotto e "schermo-ritaglio.jpg" si legge sopra il testo di un'altra app.
 */
@Composable
private fun AttachmentChips(
  attachments: List<PendingAttachment>,
  backdrop: GlassBackdropState,
  onRemove: (Int) -> Unit,
) {
  Row(
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    modifier = Modifier
      .padding(bottom = 8.dp)
      .glassSurface(state = backdrop, tint = GlassDefaults.floatingTint(), shape = ContinuousCornerShape(FluidRadius.Control), role = GlassRole.Floating)
      .padding(horizontal = 8.dp, vertical = 6.dp),
  ) {
    attachments.forEachIndexed { index, attachment ->
      FluidChip(
        label = attachment.name.take(24),
        selected = false,
        onClick = { onRemove(index) },
        leading = { Icon(Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(16.dp)) },
      )
    }
  }
}

/** L'alone, con il livello del microfono passato come lambda: lo legge solo il loop di frame. */
@Composable
private fun HaloLayer(controller: SessionController, mood: HaloMood) {
  AriaHalo(
    mood = mood,
    level = { controller.micLevel.value.level },
    colours = HaloColours.fromTheme(),
  )
}

/**
 * Lo screenshot a tutto schermo. Disegnato a mano per un controllo solo: lo store lo ricicla alla
 * chiusura (`ScreenContextStore.clear`), e la composizione — ferma mentre la sessione e' nascosta —
 * lo tiene ancora in mano alla riapertura, finche' il suo raccoglitore non riparte. Se in quel giro
 * lo scrim che riparte da zero fa ridisegnare lo sfondo, una bitmap riciclata non va toccata.
 */
@Composable
private fun ScreenImage(bitmap: Bitmap) {
  val image = remember(bitmap) { bitmap.asImageBitmap() }
  Spacer(
    Modifier
      .fillMaxSize()
      .drawBehind {
        if (!bitmap.isRecycled) drawImage(image, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
      },
  )
}

/** Il rettangolo della selezione: un buco chiaro nello scrim e un bordo del colore primario. */
@Composable
private fun SelectionMarquee(rect: Rect, colour: Color) {
  Canvas(Modifier.fillMaxSize()) {
    drawRect(color = Color.White.copy(alpha = 0.18f), topLeft = rect.topLeft, size = rect.size)
    drawRect(color = colour, topLeft = rect.topLeft, size = rect.size, style = Stroke(width = 2.dp.toPx()))
  }
}

private fun Rect.normalized(): Rect = Rect(minOf(left, right), minOf(top, bottom), maxOf(left, right), maxOf(top, bottom))

/**
 * Quanto della card passa **sotto** la barra. Quattordici dp: bastano a nascondere il seme del
 * morph (una capsula di otto) e a non lasciare mai vedere un filo di bordo fra le due superfici,
 * senza mangiare l'ultima riga di testo (che ha ventidue dp di aria sotto di se').
 */
internal val CardOverlap = 14.dp

/** Quanto puo' prendersi la card dell'altezza disponibile: oltre la meta' non e' piu' un overlay. */
private const val CardHeightShare = 0.55f

/** Quanto scurisce lo scrim sopra lo screenshot, a entrata finita. */
private const val ScrimAlpha = 0.45f
