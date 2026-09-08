package dev.pampa.pampai.feature.assistant.session

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
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
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.glassSurface
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.pampa.pampai.core.assistant.attachments.PendingAttachment
import kotlinx.coroutines.delay

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
 *  3. la pila in basso: la card **dietro**, e davanti la nota, i chip degli allegati e la barra.
 *
 * L'ordine di disegno della pila e' il trucco dell'entrata: il fondo della card passa
 * [CardOverlap] dp sotto la capsula, quindi il seme da cui nasce il morph — una capsula di otto dp
 * sul suo bordo inferiore — e' gia' coperto dalla barra quando parte. La card sembra uscire da li'.
 */
@Composable
fun PampaiSessionOverlay(controller: SessionController, actions: SessionActions) {
  val state by controller.state.collectAsStateWithLifecycle()
  val screen by controller.screenState.collectAsStateWithLifecycle()
  val partial by controller.partial.collectAsStateWithLifecycle()
  val pending by controller.pendingConfirmation.collectAsStateWithLifecycle()
  val speaking by controller.speaking.collectAsStateWithLifecycle()
  val messages by controller.messages.collectAsStateWithLifecycle()
  val runs by controller.runs.collectAsStateWithLifecycle()
  val attachments by controller.attachments.collectAsStateWithLifecycle()
  val textMode by controller.textMode.collectAsStateWithLifecycle()
  val selecting by controller.selecting.collectAsStateWithLifecycle()
  val notice by controller.notice.collectAsStateWithLifecycle()
  val shownStamp by controller.shownStamp.collectAsStateWithLifecycle()

  val backdrop = rememberGlassBackdrop()
  val scrim by animateFloatAsState(if (shownStamp > 0L) 0.45f else 0f, spring(dampingRatio = FluidMotion.DampingStandard, stiffness = FluidMotion.ResponseSmooth), label = "scrim")
  var selection by remember { mutableStateOf<Rect?>(null) }

  Box(Modifier.fillMaxSize()) {
    // 1. Lo sfondo: cio' che il vetro guarda. Lo screenshot e' identico a cio' che c'e' sotto la
    //    finestra, ma solo cosi' la barra ha qualcosa da rifrangere; senza, il vetro e' una tinta.
    Box(
      Modifier
        .fillMaxSize()
        .glassBackdropSource(backdrop, frozen = { !selecting })
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
      Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrim)))
      selection?.normalized()?.let { rect -> SelectionMarquee(rect, MaterialTheme.colorScheme.primary) }
    }

    // 2. L'alone lungo il bordo (legge il microfono per conto suo: cinquanta volte al secondo
    //    ricompone solo se stesso, non la card con il Markdown).
    HaloLayer(controller, mood = if (selecting) HaloMood.IDLE else state.haloMood())

    // 3. La pila in basso.
    if (!selecting) {
      BoxWithConstraints(
        Modifier
          .align(Alignment.BottomCenter)
          .fillMaxWidth()
          .navigationBarsPadding()
          .imePadding()
          .padding(horizontal = 12.dp, vertical = 10.dp),
      ) {
        val density = LocalDensity.current
        val cardMax = maxHeight * CardHeightShare
        val live = state.takeIf { it != AssistantState.Idle }
        val exchange = remember(messages, runs, live) { currentExchange(messages, runs, live) }
        val earlier = remember(messages) { earlierExchanges(messages) }
        // Quanto e' alta la roba davanti: la card ci si infila sotto per CardOverlap dp.
        var frontHeight by remember { mutableStateOf(0.dp) }
        // L'entrata: prima l'orb diventa capsula, e solo dopo la card esce dal suo seme.
        var orb by remember(shownStamp) { mutableStateOf(true) }
        LaunchedEffect(shownStamp) {
          orb = true
          delay(OrbMillis)
          orb = false
        }

        if (exchange != null) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
              .align(Alignment.BottomCenter)
              .padding(bottom = (frontHeight - CardOverlap).coerceAtLeast(0.dp)),
          ) {
            if (earlier > 0) {
              EarlierPill(earlier) { actions.expand(controller.conversationId.value) }
              Spacer(Modifier.height(8.dp))
            }
            // Ogni volta che la sessione si mostra l'entrata si rifa' da capo.
            key(shownStamp) {
              SessionCard(
                exchange = exchange,
                live = live,
                pending = pending,
                speaking = speaking,
                orb = orb,
                maxHeight = cardMax,
                backdrop = backdrop,
                onResolve = controller::resolve,
                onChip = actions.chip,
                onExpand = { actions.expand(controller.conversationId.value) },
                onStopSpeaking = controller::stopSpeaking,
              )
            }
          }
        }

        // Disegnata dopo la card, quindi sopra: e' la barra a coprire il fondo della card, e con
        // esso il seme del morph.
        Column(
          Modifier
            .align(Alignment.BottomCenter)
            .onSizeChanged { frontHeight = with(density) { it.height.toDp() } },
        ) {
          SessionNotice(
            notice = notice,
            live = live,
            onDismiss = controller::dismissNotice,
            onOpenAssistSettings = actions.openAssistSettings,
          )
          if (attachments.isNotEmpty()) AttachmentChips(attachments, backdrop, controller::removeAttachment)
          SessionBar(
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
    } else {
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

@Composable
private fun HaloLayer(controller: SessionController, mood: HaloMood) {
  val mic by controller.micLevel.collectAsStateWithLifecycle()
  AriaHalo(
    mood = mood,
    level = mic.level,
    accent = MaterialTheme.colorScheme.primary,
    secondary = MaterialTheme.colorScheme.secondary,
    tertiary = MaterialTheme.colorScheme.tertiary,
    error = MaterialTheme.colorScheme.error,
  )
}

@Composable
private fun ScreenImage(bitmap: Bitmap) {
  val image = remember(bitmap) { bitmap.asImageBitmap() }
  Image(bitmap = image, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
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
