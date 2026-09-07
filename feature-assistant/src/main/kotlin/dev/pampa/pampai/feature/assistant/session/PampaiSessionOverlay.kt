package dev.pampa.pampai.feature.assistant.session

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VolumeOff
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ai.orchestrator.AnswerChip
import dev.antigravity.fluidengine.ai.orchestrator.AssistantState
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidChip
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassRole
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.glassSurface
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.antigravity.fluidengine.ui.fluidphysics.FluidForm
import dev.antigravity.fluidengine.ui.fluidphysics.FluidFormPresets
import dev.antigravity.fluidengine.ui.fluidphysics.FluidPhysicsTier
import dev.antigravity.fluidengine.ui.fluidphysics.fluidPhysicsSurface
import dev.antigravity.fluidengine.ui.fluidphysics.rememberFluidPhysicsState
import dev.pampa.pampai.core.assistant.db.Message
import dev.pampa.pampai.core.assistant.db.MessageRole
import dev.pampa.pampai.core.assistant.db.MessageStatus
import dev.pampa.pampai.core.assistant.db.Run
import dev.pampa.pampai.feature.assistant.chat.AssistantTexts
import dev.pampa.pampai.feature.assistant.chat.ConfirmationRow
import dev.pampa.pampai.feature.assistant.chat.MarkdownBody
import dev.pampa.pampai.feature.assistant.chat.RunSteps
import dev.pampa.pampai.feature.assistant.chat.VoiceVisualizer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Cosa l'overlay chiede alla sessione: chiudersi, aprirsi nell'app, aprire le impostazioni dell'assistente, un chip. */
class SessionActions(
  val hide: () -> Unit,
  val expand: (conversationId: Long?) -> Unit,
  val openAssistSettings: () -> Unit,
  val chip: (AnswerChip) -> Unit,
)

/**
 * L'overlay di sistema, in strati: lo screenshot (se c'e') sotto uno scrim che scurisce come Gemini
 * ed e' l'unica cosa che il vetro campiona (finestre diverse non si vedono: da qui il "vetro
 * discreto"); un tocco fuori chiude, un trascinamento ritaglia una porzione dello schermo;
 * l'alone lungo il perimetro; in basso la card compatta della conversazione e la barra, una
 * superficie Fluid-physics che entra come orb e diventa capsula. La card si trascina in alto
 * per continuare nella chat dell'app.
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

    // 3. La card e la barra.
    if (!selecting) {
      Column(
        Modifier
          .align(Alignment.BottomCenter)
          .navigationBarsPadding()
          .imePadding()
          .padding(horizontal = 12.dp, vertical = 10.dp),
      ) {
        val live = state.takeIf { it != AssistantState.Idle }
        val hasCard = messages.isNotEmpty() || (live != null && live !is AssistantState.Listening && live != AssistantState.Transcribing) || notice != null
        if (hasCard) {
          CompactCard(
            messages = messages,
            runs = runs,
            live = live,
            pending = pending,
            notice = notice,
            backdrop = backdrop,
            onResolve = controller::resolve,
            onChip = actions.chip,
            onExpand = { actions.expand(controller.conversationId.value) },
            onOpenAssistSettings = actions.openAssistSettings,
          )
          Spacer(Modifier.height(10.dp))
        }
        SessionBar(
          state = state,
          textMode = textMode,
          partial = partial,
          speaking = speaking,
          attachments = attachments,
          shownStamp = shownStamp,
          backdrop = backdrop,
          micLevel = controller.micLevel,
          onAsk = controller::ask,
          onVoice = controller::startVoice,
          onStopVoice = controller::stopVoice,
          onVoiceToText = controller::voiceToText,
          onStop = controller::cancel,
          onStopSpeaking = controller::stopSpeaking,
          onAttachScreen = { controller.attachScreen() },
          onRemoveAttachment = controller::removeAttachment,
        )
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
 * La conversazione compatta: le ultime battute, la risposta che si forma, la conferma, i chip, la
 * riga degli strumenti. La maniglia in alto si trascina (o si tocca) per continuare nell'app.
 */
@Composable
private fun CompactCard(
  messages: List<Message>,
  runs: List<Run>,
  live: AssistantState?,
  pending: dev.antigravity.fluidengine.ai.orchestrator.PendingConfirmation?,
  notice: String?,
  backdrop: dev.antigravity.fluidengine.ui.fluid.GlassBackdropState,
  onResolve: (Long, Boolean) -> Unit,
  onChip: (AnswerChip) -> Unit,
  onExpand: () -> Unit,
  onOpenAssistSettings: () -> Unit,
) {
  val density = LocalDensity.current
  val threshold = with(density) { 96.dp.toPx() }
  var dragged by remember { mutableStateOf(0f) }
  val scroll = rememberScrollState()
  val runsByMessage = remember(runs) { runs.associateBy { it.messageId } }
  LaunchedEffect(messages.size, (live as? AssistantState.Answering)?.partial?.length) { scroll.animateScrollTo(scroll.maxValue) }
  Column(
    Modifier
      .fillMaxWidth()
      .heightIn(max = 420.dp)
      .glassSurface(state = backdrop, tint = GlassDefaults.modalTint(), shape = ContinuousCornerShape(FluidRadius.Sheet), role = GlassRole.Modal)
      .padding(horizontal = 16.dp, vertical = 8.dp),
  ) {
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
        .padding(vertical = 6.dp),
    ) {
      Box(Modifier.size(width = 36.dp, height = 4.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f), ContinuousCornerShape(2.dp)))
      Spacer(Modifier.width(10.dp))
      Text("Aria", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
      Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Continua in PampAI", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
    Column(Modifier.verticalScroll(scroll)) {
      notice?.let { text ->
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (text.contains("impostazioni dell'assistente")) {
          Spacer(Modifier.height(6.dp))
          FluidButton(text = "Impostazioni assistente", onClick = onOpenAssistSettings, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Small)
        }
        Spacer(Modifier.height(8.dp))
      }
      val shown = messages.takeLast(6)
      shown.forEachIndexed { index, message ->
        when (message.role) {
          MessageRole.USER -> Text(
            text = message.text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
          )
          MessageRole.ASSISTANT -> {
            val streaming = message.status == MessageStatus.PENDING || message.status == MessageStatus.STREAMING
            val text = (if (streaming) (live as? AssistantState.Answering)?.partial else null)?.takeIf { it.isNotBlank() } ?: message.text
            if (streaming && live != null && live.isBusy) {
              AssistantTexts.statusLine(live)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium) }
              if (pending != null) ConfirmationRow(pending, onResolve)
            }
            when {
              text.isNotBlank() -> MarkdownBody(text)
              message.status == MessageStatus.FAILED -> Text(message.failureKind?.let { AssistantTexts.failure(it) } ?: "Qualcosa e' andato storto.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
              message.status == MessageStatus.CANCELLED -> Text("Fermata.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (message.chips.isNotEmpty()) {
              Spacer(Modifier.height(8.dp))
              FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                message.chips.forEach { chip -> FluidChip(label = AssistantTexts.chipLabel(chip), selected = false, onClick = { onChip(chip) }) }
              }
            }
            runsByMessage[message.id]?.let { run ->
              Spacer(Modifier.height(6.dp))
              RunSteps(run)
            }
          }
        }
        if (index < shown.lastIndex) Spacer(Modifier.height(10.dp))
      }
      // Una conversazione nuova: la domanda in corso non e' ancora su disco.
      if (messages.isEmpty() && live != null && live !is AssistantState.Listening && live != AssistantState.Transcribing) {
        AssistantTexts.statusLine(live)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = if (live is AssistantState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium) }
        (live as? AssistantState.Answering)?.partial?.let { MarkdownBody(it) }
        if (pending != null) ConfirmationRow(pending, onResolve)
      }
      Spacer(Modifier.height(6.dp))
    }
  }
}

/**
 * La barra: una superficie Fluid-physics che entra come orb (il cerchio del microfono) e in mezzo
 * secondo diventa la capsula con dentro il visualizzatore, o il campo di testo, e i tasti.
 */
@Composable
private fun SessionBar(
  state: AssistantState,
  textMode: Boolean,
  partial: String?,
  speaking: Boolean,
  attachments: List<dev.pampa.pampai.core.assistant.attachments.PendingAttachment>,
  shownStamp: Long,
  backdrop: dev.antigravity.fluidengine.ui.fluid.GlassBackdropState,
  micLevel: kotlinx.coroutines.flow.StateFlow<dev.antigravity.fluidengine.ai.orchestrator.MicLevel>,
  onAsk: (String) -> Unit,
  onVoice: () -> Unit,
  onStopVoice: () -> Unit,
  onVoiceToText: () -> Unit,
  onStop: () -> Unit,
  onStopSpeaking: () -> Unit,
  onAttachScreen: () -> Unit,
  onRemoveAttachment: (Int) -> Unit,
) {
  var text by remember { mutableStateOf("") }
  val listening = state is AssistantState.Listening
  val transcribing = state == AssistantState.Transcribing
  val busy = state.isBusy
  var orb by remember(shownStamp) { mutableStateOf(true) }
  LaunchedEffect(shownStamp) {
    orb = true
    delay(320)
    orb = false
  }
  Column {
    if (attachments.isNotEmpty()) {
      // Il vetro anche qui, come sulla barra: senza, i chip stanno su un pezzo qualunque
      // dell'app sotto e "schermo-ritaglio.jpg" si legge sopra il testo di un'altra app.
      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
          .padding(bottom = 8.dp)
          .glassSurface(state = backdrop, tint = GlassDefaults.modalTint(), shape = ContinuousCornerShape(FluidRadius.Control), role = GlassRole.Floating)
          .padding(horizontal = 8.dp, vertical = 6.dp),
      ) {
        attachments.forEachIndexed { index, attachment ->
          FluidChip(label = attachment.name.take(24), selected = false, onClick = { onRemoveAttachment(index) }, leading = { Icon(Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(16.dp)) })
        }
      }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().height(BarHeight)) {
      val density = LocalDensity.current
      val width = constraints.maxWidth.toFloat()
      val height = with(density) { BarHeight.toPx() }
      val orbRadius = with(density) { 34.dp.toPx() }
      val capsuleHeight = with(density) { 56.dp.toPx() }
      val orbForm = remember(width, height) { FluidForm.circle(Offset(width / 2f, height / 2f), orbRadius) }
      val capsuleForm = remember(width, height) { FluidFormPresets.capsule(Rect(0f, (height - capsuleHeight) / 2f, width, (height + capsuleHeight) / 2f)) }
      val physics = rememberFluidPhysicsState(if (orb) orbForm else capsuleForm)
      val scope = rememberCoroutineScope()
      LaunchedEffect(orb, width) { scope.launch { physics.morphTo(if (orb) orbForm else capsuleForm) } }
      Box(
        Modifier
          .fillMaxSize()
          .fluidPhysicsSurface(state = physics, backdrop = backdrop, tint = GlassDefaults.modalTint(), role = GlassRole.Floating, tier = FluidPhysicsTier.Balanced),
      ) {
        if (orb) {
          Icon(Icons.Rounded.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.Center).size(26.dp))
        } else {
          Row(
            modifier = Modifier.align(Alignment.Center).fillMaxWidth().height(56.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            BarIcon(Icons.Rounded.Screenshot, "Allega lo schermo", onAttachScreen)
            if (listening || transcribing) {
              VoiceVisualizer(micLevel = micLevel, partial = partial, transcribing = transcribing, onTap = onVoiceToText, modifier = Modifier.weight(1f))
            } else {
              FluidTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = if (busy) "Sto rispondendo…" else "Chiedi ad Aria…",
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank() || attachments.isNotEmpty()) { onAsk(text); text = "" } }),
                modifier = Modifier.weight(1f),
              )
            }
            Spacer(Modifier.width(4.dp))
            when {
              listening -> BarIcon(Icons.Rounded.Stop, "Smetti di ascoltare", onStopVoice, tint = MaterialTheme.colorScheme.error)
              busy -> BarIcon(Icons.Rounded.Stop, "Ferma", onStop)
              speaking && text.isBlank() -> BarIcon(Icons.Rounded.VolumeOff, "Zitta", onStopSpeaking)
              text.isBlank() && attachments.isEmpty() -> BarIcon(Icons.Rounded.Mic, "Parla", onVoice, tint = MaterialTheme.colorScheme.primary)
              else -> BarIcon(Icons.Rounded.ArrowUpward, "Invia", { onAsk(text); text = "" }, tint = MaterialTheme.colorScheme.primary)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun BarIcon(icon: ImageVector, description: String, onClick: () -> Unit, tint: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)) {
  Box(Modifier.size(40.dp).fluidPressable(onClick = onClick, role = Role.Button), contentAlignment = Alignment.Center) {
    Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(22.dp))
  }
}

private val BarHeight = 72.dp
