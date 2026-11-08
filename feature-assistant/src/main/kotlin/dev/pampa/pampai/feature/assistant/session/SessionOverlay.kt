package dev.pampa.pampai.feature.assistant.session

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Close
import dev.antigravity.fluidengine.aiui.chat.FluidGlassRoundButton
import dev.pampa.pampai.feature.assistant.chat.AssistantModelControl
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ai.orchestrator.AnswerChip
import dev.antigravity.fluidengine.ai.orchestrator.AssistantState
import dev.antigravity.fluidengine.ui.fluid.fluidReadingWidth
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
import kotlin.math.roundToInt

/** Cosa l'overlay chiede alla sessione: chiudersi, aprirsi nell'app, aprire le impostazioni dell'assistente, un chip. */
class SessionActions(
  val hide: () -> Unit,
  val expand: (conversationId: Long?) -> Unit,
  val openAssistSettings: () -> Unit,
  val chip: (AnswerChip) -> Unit,
)

/** Bounded bottom session surface. Expansion only transfers the current conversation ID.
 * The screenshot/crop background stays outside the panel; only the answer scrolls internally.
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
    if (reducedMotion) scrim.snapTo(ScrimAlpha) else scrim.animateTo(ScrimAlpha, FluidMotion.smooth())
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

    BoxWithConstraints(
      Modifier.align(Alignment.BottomCenter).fillMaxWidth()
        .navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
      val availableHeight = maxHeight
      val live = state.takeIf { it != AssistantState.Idle }
      val shownMessages = remember(messages, conversationId) { messages.filter { it.conversationId == conversationId } }
      val shownRuns = remember(runs, conversationId) { runs.filter { it.conversationId == conversationId } }
      val exchange = remember(shownMessages, shownRuns, live) { currentExchange(shownMessages, shownRuns, live) }
      run {
        Column(
          Modifier.fluidReadingWidth(max = 640.dp).fillMaxWidth().heightIn(max = availableHeight * 0.85f)
            .graphicsLayer { alpha = if (selecting) 0f else 1f }
            .pointerInput(selecting) {
              if (selecting) awaitPointerEventScope {
                while (true) awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial).changes.forEach { it.consume() }
              }
            }
            .glassSurface(state = backdrop, tint = GlassDefaults.modalTint(),
              shape = ContinuousCornerShape(FluidRadius.Sheet), role = GlassRole.Floating),
        ) {
          Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Aria", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text("Continua nell'app", style = MaterialTheme.typography.labelMedium,
              modifier = Modifier.heightIn(min = 48.dp)
                .fluidPressable(onClick = { actions.expand(controller.conversationId.value) }, role = Role.Button)
                .padding(horizontal = 12.dp, vertical = 16.dp))
            FluidGlassRoundButton(Icons.Rounded.Close, "Chiudi Aria", backdrop,
              modifier = Modifier.size(48.dp), onClick = actions.hide)
          }
          if (exchange != null) {
            Box(Modifier.weight(1f, fill = false)) {
              SessionCard(exchange, live, pending, orb = false, maxHeight = availableHeight,
                backdrop = backdrop, onResolve = controller::resolve, onChip = actions.chip,
                onExpand = { actions.expand(controller.conversationId.value) })
            }
          }
          SessionNotice(notice, live, controller::dismissNotice, actions.openAssistSettings)
          if (attachments.isNotEmpty()) AttachmentChips(attachments, backdrop, controller::removeAttachment)
          Box(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            SessionBar(preparingVoice = controller.preparingVoice.collectAsStateWithLifecycle().value, text = controller.draft.collectAsStateWithLifecycle().value, currentText = { controller.draft.value }, onTextChange = { controller.draft.value = it }, stamp = shownStamp, state = state, textMode = textMode, partial = partial,
              speaking = speaking, hasAttachments = attachments.isNotEmpty(), orb = false,
              backdrop = backdrop, micLevel = controller.micLevel, onAsk = controller::askPrepared,
              onVoice = controller::startVoice, onStopVoice = controller::stopVoice,
              onVoiceToText = controller::voiceToText, onStop = controller::cancel,
              onStopSpeaking = controller::stopSpeaking, onAttachScreen = { controller.attachScreen() },
              modelControl = { dev.pampa.pampai.feature.assistant.chat.ExactModelControl(controller.modelSelection, conversationId) })
          }
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
      .horizontalScroll(rememberScrollState())
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

private const val ScrimAlpha = 0.45f
