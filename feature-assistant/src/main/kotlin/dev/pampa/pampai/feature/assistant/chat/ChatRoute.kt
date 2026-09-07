package dev.pampa.pampai.feature.assistant.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.pampa.pampai.core.assistant.chat.Greetings
import dev.pampa.pampai.core.assistant.runtime.VoiceEvent
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import dev.antigravity.fluidengine.ai.orchestrator.AnswerChip
import dev.antigravity.fluidengine.ai.orchestrator.AssistantState
import dev.antigravity.fluidengine.ai.orchestrator.PendingConfirmation
import dev.antigravity.fluidengine.ui.fluid.FluidAmbient
import dev.antigravity.fluidengine.ui.fluid.FluidBarAction
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidChip
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMotif
import dev.antigravity.fluidengine.ui.fluid.FluidHeroTone
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassRole
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.fluid.glassControlSurface
import dev.antigravity.fluidengine.ui.fluid.glassSurface
import dev.antigravity.fluidengine.ui.theme.FluidCard
import dev.pampa.pampai.core.assistant.attachments.PendingAttachment
import dev.pampa.pampai.core.assistant.db.AttachmentKind
import dev.pampa.pampai.core.assistant.db.Message
import dev.pampa.pampai.core.assistant.db.MessageRole
import dev.pampa.pampai.core.assistant.db.MessageStatus
import dev.pampa.pampai.core.assistant.db.Run
import java.time.LocalTime
import java.util.Locale
import kotlin.random.Random

/**
 * La chat con Aria: le domande, le risposte con la loro telemetria, e in fondo la barra per
 * continuare. Mentre una risposta arriva la si vede formarsi qui; lo stato vivo viene dal runtime,
 * il testo che resta da Room.
 */
@Composable
fun ChatRoute(
  bottomInset: Dp,
  onChip: (AnswerChip) -> Unit,
  onOpenSettings: () -> Unit,
  viewModel: ChatViewModel = hiltViewModel(),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val partial by viewModel.runtimePartial.collectAsStateWithLifecycle()
  val draft by viewModel.draft.collectAsStateWithLifecycle()
  val speaking by viewModel.speaking.collectAsStateWithLifecycle()
  val suggestionsSeen by viewModel.suggestionsSeen.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val listState = rememberLazyListState()
  var editing by remember { mutableStateOf<Message?>(null) }

  // La lista segue la risposta che si forma: l'ultimo elemento resta in vista.
  val count = state.messages.size + if (state.live != null) 1 else 0
  LaunchedEffect(count, (state.live as? AssistantState.Answering)?.partial?.length) {
    if (count > 0) runCatching { listState.animateScrollToItem(count + 1) }
  }

  val contextFacet = state.context?.let { "contesto ${(it.fraction * 100).toInt()}% di ${it.window / 1000}k" }
  FluidScreen(
    // In una chat nuova il nome scende al centro della pagina insieme al saluto: ripeterlo anche
    // qui sopra lo farebbe leggere due volte in mezzo schermo vuoto.
    title = state.conversation?.title?.take(40) ?: if (state.isNew) "" else "Aria",
    subtitle = if (!state.enabled) "Serve una chiave verificata: la aggiungi nelle impostazioni." else null,
    titleFacets = listOfNotNull(contextFacet),
    listState = listState,
    extraBottomPadding = bottomInset + 84.dp,
    itemSpacing = 12.dp,
    ambient = remember { FluidAmbient(tone = FluidHeroTone.Primary, motif = FluidHeroMotif.Cards) },
    actions = {
      if (!state.isNew) FluidBarAction(icon = Icons.Rounded.Add, contentDescription = "Nuova conversazione", onClick = viewModel::newConversation)
    },
    overlay = { backdrop ->
      Box(
        Modifier
          .align(Alignment.BottomCenter)
          .navigationBarsPadding()
          .imePadding()
          .padding(bottom = bottomInset)
          .padding(horizontal = 12.dp, vertical = 8.dp),
      ) {
        Composer(
          backdrop = backdrop,
          state = state,
          editing = editing,
          onSend = { text ->
            val target = editing
            editing = null
            if (target != null) viewModel.editAndResend(target, text) else viewModel.send(text)
          },
          onCancelEdit = { editing = null },
          onStop = viewModel::cancel,
          onVoice = viewModel::startVoice,
          onStopVoice = viewModel::stopVoice,
          onCancelVoice = viewModel::cancelVoice,
          onStopSpeaking = viewModel::stopSpeaking,
          micLevel = viewModel.micLevel,
          partial = partial,
          speaking = speaking,
          voiceEvents = viewModel.voiceEvents,
          draft = draft,
          onDraftConsumed = { viewModel.draft.value = null },
          onAttach = viewModel::attach,
          onRemoveAttachment = viewModel::removeAttachment,
          onOpenSettings = onOpenSettings,
        )
      }
    },
  ) {
    if (state.messages.isEmpty() && state.live == null) {
      if (!suggestionsSeen) {
        item(key = "hint") {
          // Segnati come visti appena compaiono: la prima chat e' l'unica in cui servono, e
          // aspettare che l'utente li legga davvero non e' una cosa che si puo' sapere.
          LaunchedEffect(Unit) { viewModel.markSuggestionsSeen() }
          FluidCard(glass = true) {
            Text(
              "Prova con: \"che tempo fa domani?\", \"metti una sveglia alle 7\", \"quanto fa il 15% di 340?\", \"ricordati che la mia fermata e' Dalmazia\", \"cosa vuol dire 'sciatteria'?\"",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      } else {
        item(key = "saluto") { EmptyGreeting() }
      }
    }
    state.messages.forEach { message ->
      item(key = message.id) {
        when (message.role) {
          MessageRole.USER -> UserBubble(message, onEdit = { editing = message })
          MessageRole.ASSISTANT -> AssistantBubble(
            message = message,
            run = state.runs[message.id],
            live = if (message.status == MessageStatus.PENDING || message.status == MessageStatus.STREAMING) state.live else null,
            pending = state.pending,
            onResolve = viewModel::resolve,
            onChip = onChip,
            onRegenerate = { viewModel.regenerate(message) },
            onCopy = { copy(context, message.text) },
            onShare = { share(context, message.text) },
          )
        }
      }
    }
    // Una conversazione nuova: la domanda in corso non e' ancora su disco.
    if (state.messages.isEmpty() && state.live != null) {
      item(key = "live") { LiveBubble(state.live!!, state.pending, viewModel::resolve) }
    }
  }
}

/**
 * La pagina vuota di una chat gia' aperta altre volte: il nome al centro e una frase sotto.
 *
 * La frase si sceglie una volta per apertura (`remember` senza chiave): rigenerarla a ogni
 * ricomposizione la farebbe cambiare mentre si scrive, che e' esattamente il contrario di un saluto.
 */
@Composable
private fun LazyItemScope.EmptyGreeting() {
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

@Composable
private fun UserBubble(message: Message, onEdit: () -> Unit) {
  FluidCard(glass = true) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("Tu", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
      if (message.mode.name == "VOICE") Text("a voce", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      SmallAction(Icons.Rounded.Edit, "Modifica", onEdit)
    }
    Spacer(Modifier.height(4.dp))
    Text(message.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
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

@Composable
private fun AssistantBubble(
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
  FluidCard(highlighted = message.status == MessageStatus.FAILED) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("Aria", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
      if (live == null || !live.isBusy) {
        SmallAction(Icons.Rounded.ContentCopy, "Copia", onCopy)
        SmallAction(Icons.Rounded.Share, "Condividi", onShare)
        SmallAction(Icons.Rounded.Refresh, "Rigenera", onRegenerate)
      }
    }
    Spacer(Modifier.height(4.dp))
    val liveText = (live as? AssistantState.Answering)?.partial
    val text = liveText?.takeIf { it.isNotBlank() } ?: message.text
    if (live != null && live.isBusy) {
      AssistantTexts.statusLine(live)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium) }
      if (pending != null) ConfirmationRow(pending, onResolve)
      if (text.isNotBlank()) Spacer(Modifier.height(8.dp))
    }
    when {
      text.isNotBlank() -> MarkdownBody(text)
      message.status == MessageStatus.FAILED -> Text(message.failureKind?.let { AssistantTexts.failure(it) } ?: "Qualcosa e' andato storto.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
      message.status == MessageStatus.CANCELLED -> Text("Fermata prima della risposta.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      live == null -> Text("Interrotta: l'app si e' chiusa prima della risposta.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (message.status == MessageStatus.FAILED && text.isNotBlank()) {
      Spacer(Modifier.height(6.dp))
      Text(message.failureKind?.let { AssistantTexts.failure(it) } ?: "Interrotta.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    if (message.chips.isNotEmpty()) {
      Spacer(Modifier.height(10.dp))
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        message.chips.forEach { chip -> FluidChip(label = AssistantTexts.chipLabel(chip), selected = false, onClick = { onChip(chip) }) }
      }
    }
    run?.let { r ->
      Spacer(Modifier.height(8.dp))
      RunSteps(r)
    }
  }
}

/** "Ho usato N strumenti · Groq · 6 s" e, sotto, a richiesta, i passi: cosa il modello ha chiesto a ciascuno e come ha risposto. */
@Composable
fun RunSteps(run: Run) {
  val hasDetails = run.tools.isNotEmpty() || run.error != null
  var details by rememberSaveable(run.id) { mutableStateOf(false) }
  Text(
    text = telemetry(run) + if (hasDetails) (if (details) " · meno" else " · passi") else "",
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = if (hasDetails) Modifier.fluidPressable(onClick = { details = !details }, pressedScale = 1f, role = Role.Button, haptic = null) else Modifier,
  )
  if (details) {
    Spacer(Modifier.height(6.dp))
    run.error?.let { Text("errore: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    val models = listOfNotNull(run.routerModel?.let { "router $it" }, run.chatModel?.let { "chat $it" }, run.deepModel?.takeIf { it != run.chatModel }?.let { "profondo $it" })
    if (models.isNotEmpty()) Text(models.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (run.groups.isNotEmpty()) Text("gruppi: ${run.groups.joinToString(", ")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    run.tools.forEach { trace ->
      Spacer(Modifier.height(4.dp))
      Text(
        text = "${trace.app?.let { "$it · " } ?: ""}${trace.name} ${trace.args} · ${trace.millis} ms · ${if (trace.ok) "ok" else "errore"}",
        style = MaterialTheme.typography.labelSmall,
        color = if (trace.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
      )
      if (trace.preview.isNotBlank()) Text(trace.preview, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun LiveBubble(live: AssistantState, pending: PendingConfirmation?, onResolve: (Long, Boolean) -> Unit) {
  FluidCard {
    Text("Aria", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(4.dp))
    AssistantTexts.statusLine(live)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = if (live is AssistantState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium) }
    (live as? AssistantState.Answering)?.partial?.let {
      Spacer(Modifier.height(8.dp))
      MarkdownBody(it)
    }
    if (pending != null) ConfirmationRow(pending, onResolve)
  }
}

@Composable
fun ConfirmationRow(pending: PendingConfirmation, onResolve: (Long, Boolean) -> Unit) {
  Spacer(Modifier.height(8.dp))
  Text(pending.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
  pending.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
  Spacer(Modifier.height(6.dp))
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    FluidButton(text = "Conferma", onClick = { onResolve(pending.id, true) }, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Small)
    FluidButton(text = "Annulla", onClick = { onResolve(pending.id, false) }, style = FluidButtonStyle.Plain, size = FluidButtonSize.Small)
  }
}

/** Il Markdown completo delle risposte, con i colori e la tipografia del tema. */
@Composable
fun MarkdownBody(markdown: String) {
  Markdown(content = markdown, colors = markdownColor(), typography = markdownTypography())
}

/** La telemetria di uno scambio in una riga: strumenti, servizio, tempo, token, costo. */
fun telemetry(run: Run): String = buildList {
  add(if (run.tools.isEmpty()) "nessuno strumento" else "${run.tools.size} ${if (run.tools.size == 1) "strumento" else "strumenti"}")
  run.provider?.let { add(it.label) }
  run.durationMillis?.let { add("${it / 1000} s") }
  run.totalTokens?.let { add(if (it >= 1000) String.format(Locale.getDefault(), "%.1fk token", it / 1000.0) else "$it token") }
  run.costUsd?.takeIf { it > 0.0 }?.let { add(String.format(Locale.getDefault(), "%.4f $", it)) }
  if (run.outcome != "ok") add(run.outcome)
}.joinToString(" · ")

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

/**
 * La barra in fondo: allegati in attesa come chip, la capsula di vetro con il tasto degli
 * allegati, il campo, e a destra il microfono (campo vuoto), l'invio (campo pieno) o lo stop
 * (mentre risponde). Nella modifica di un messaggio la capsula lo dice e si puo' annullare.
 */
@Composable
private fun Composer(
  backdrop: GlassBackdropState,
  state: ChatUiState,
  editing: Message?,
  onSend: (String) -> Unit,
  onCancelEdit: () -> Unit,
  onStop: () -> Unit,
  onVoice: () -> Unit,
  onStopVoice: () -> Unit,
  onCancelVoice: () -> Unit,
  onStopSpeaking: () -> Unit,
  micLevel: kotlinx.coroutines.flow.StateFlow<dev.antigravity.fluidengine.ai.orchestrator.MicLevel>,
  partial: String?,
  speaking: Boolean,
  voiceEvents: kotlinx.coroutines.flow.SharedFlow<VoiceEvent>,
  draft: String?,
  onDraftConsumed: () -> Unit,
  onAttach: (android.net.Uri) -> Unit,
  onRemoveAttachment: (Int) -> Unit,
  onOpenSettings: () -> Unit,
) {
  val context = LocalContext.current
  var text by rememberSaveable { mutableStateOf("") }
  LaunchedEffect(editing?.id) { editing?.let { text = it.text } }
  LaunchedEffect(draft) {
    if (draft != null) {
      text = draft
      onDraftConsumed()
    }
  }
  val busy = state.live?.isBusy == true
  val listening = state.live is AssistantState.Listening
  val transcribing = state.live is AssistantState.Transcribing
  val focus = remember { FocusRequester() }
  // Il silenzio iniziale: la barra e' gia' tornata testo, qui si mette il cursore nel campo.
  LaunchedEffect(Unit) { voiceEvents.collect { if (it is VoiceEvent.InitialSilence) runCatching { focus.requestFocus() } } }
  val micGranted = remember { context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
  val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) onVoice() }
  val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(3)) { uris -> uris.forEach(onAttach) }
  val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> uris.forEach(onAttach) }
  var attachMenu by remember { mutableStateOf(false) }

  fun submit() {
    val query = text.trim()
    if ((query.isEmpty() && state.attachments.isEmpty()) || busy) return
    onSend(query)
    text = ""
  }

  Column {
    if (state.attachments.isNotEmpty() || editing != null) {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        if (editing != null) FluidChip(label = "Modifico il messaggio", selected = true, onClick = onCancelEdit, leading = { Icon(Icons.Rounded.Close, contentDescription = "Annulla la modifica", modifier = Modifier.size(16.dp)) })
        state.attachments.forEachIndexed { index, attachment -> AttachmentChip(attachment) { onRemoveAttachment(index) } }
      }
    }
    if (attachMenu) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        FluidChip(label = "Foto", selected = false, onClick = { attachMenu = false; photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, leading = { Icon(Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(16.dp)) })
        FluidChip(label = "File o PDF", selected = false, onClick = { attachMenu = false; filePicker.launch(arrayOf("application/pdf", "text/*", "image/*")) }, leading = { Icon(Icons.Rounded.AttachFile, contentDescription = null, modifier = Modifier.size(16.dp)) })
      }
    }
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .glassSurface(state = backdrop, tint = GlassDefaults.modalTint(), shape = FluidCapsuleShape, role = GlassRole.Modal)
        .padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      BarIcon(icon = if (attachMenu) Icons.Rounded.Close else Icons.Rounded.Add, description = "Allega", onClick = { attachMenu = !attachMenu })
      if (listening || transcribing) {
        VoiceVisualizer(micLevel = micLevel, partial = partial, transcribing = transcribing, onTap = onCancelVoice, modifier = Modifier.weight(1f))
      } else {
        FluidTextField(
          value = text,
          onValueChange = { text = it },
          placeholder = when {
            !state.enabled -> "Aggiungi una chiave nelle impostazioni"
            busy -> "Sto rispondendo…"
            editing != null -> "Modifica e rinvia…"
            else -> "Chiedi ad Aria…"
          },
          singleLine = false,
          maxLines = 5,
          enabled = !busy && state.enabled,
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
          keyboardActions = KeyboardActions(onSend = { submit() }),
          modifier = Modifier.weight(1f).focusRequester(focus),
        )
      }
      Spacer(Modifier.width(4.dp))
      when {
        !state.enabled -> BarIcon(icon = Icons.Rounded.ArrowUpward, description = "Impostazioni", onClick = onOpenSettings)
        listening -> Box(Modifier.size(40.dp).glassControlSurface(backdrop = backdrop, shape = FluidCapsuleShape).fluidPressable(onClick = onStopVoice, role = Role.Button), contentAlignment = Alignment.Center) {
          Icon(Icons.Rounded.Stop, contentDescription = "Smetti di ascoltare", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
        }
        busy -> Box(Modifier.size(40.dp).glassControlSurface(backdrop = backdrop, shape = FluidCapsuleShape).fluidPressable(onClick = onStop, role = Role.Button), contentAlignment = Alignment.Center) {
          Icon(Icons.Rounded.Stop, contentDescription = "Ferma", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
        }
        speaking && text.isBlank() -> BarIcon(icon = Icons.Rounded.VolumeOff, description = "Zitta", onClick = onStopSpeaking)
        text.isBlank() && state.attachments.isEmpty() -> BarIcon(icon = Icons.Rounded.Mic, description = "Parla", onClick = { if (micGranted) onVoice() else micLauncher.launch(Manifest.permission.RECORD_AUDIO) })
        else -> BarIcon(icon = Icons.Rounded.ArrowUpward, description = "Invia", onClick = { submit() }, primary = true)
      }
    }
  }
}

@Composable
private fun AttachmentChip(attachment: PendingAttachment, onRemove: () -> Unit) {
  FluidChip(
    label = attachment.name.take(24) + " · ${attachment.bytes.size / 1024} KB",
    selected = false,
    onClick = onRemove,
    leading = { Icon(if (attachment.kind == AttachmentKind.IMAGE) Icons.Rounded.Image else Icons.Rounded.AttachFile, contentDescription = null, modifier = Modifier.size(16.dp)) },
  )
}

@Composable
private fun BarIcon(icon: ImageVector, description: String, onClick: () -> Unit, primary: Boolean = false) {
  Box(
    modifier = Modifier
      .size(40.dp)
      .fluidPressable(onClick = onClick, role = Role.Button),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = description,
      tint = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
      modifier = Modifier.size(22.dp),
    )
  }
}

private fun copy(context: android.content.Context, text: String) {
  val clipboard = context.getSystemService(android.content.ClipboardManager::class.java) ?: return
  clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Aria", text))
}

private fun share(context: android.content.Context, text: String) {
  val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
    type = "text/plain"
    putExtra(android.content.Intent.EXTRA_TEXT, text)
  }
  runCatching { context.startActivity(android.content.Intent.createChooser(intent, "Condividi la risposta").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
