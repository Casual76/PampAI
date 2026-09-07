package dev.pampa.pampai.feature.assistant.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mikepenz.markdown.compose.components.markdownComponents
import dev.antigravity.fluidengine.ai.keys.ThinkingLevel
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPortal
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPresentation
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
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
import dev.pampa.pampai.feature.assistant.settings.label
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
  onOpenMenu: () -> Unit,
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
  val backdrop = rememberGlassBackdrop()
  val topSpace = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 56.dp

  Box(Modifier.fillMaxSize()) {
    // Il corpo e' cio' che il vetro della barra e del composer rifrange, e deve avere un fondo
    // opaco suo: la registrazione di testo sul nulla, sotto il vetro, diventa una sbavatura.
    Box(
      Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .chatWash()
        .glassBackdropSource(backdrop, frozen = { listState.isScrollInProgress }),
    ) {
      LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topSpace, bottom = bottomInset + 128.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
      ) {
        if (state.messages.isEmpty() && state.live == null) {
          if (!suggestionsSeen) {
            item(key = "hint") {
              // Segnati come visti appena compaiono: la prima chat e' l'unica in cui servono, e
              // aspettare che l'utente li legga davvero non e' una cosa che si puo' sapere.
              LaunchedEffect(Unit) { viewModel.markSuggestionsSeen() }
              FluidCard(glass = false) {
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
              MessageRole.ASSISTANT -> AssistantMessage(
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

    ChatTopBar(
      title = state.conversation?.title ?: "",
      // Il contesto e' un numero da guardare quando una conversazione e' lunga, non il primo
      // messaggio che l'app da' a chi apre una chat vuota.
      facet = contextFacet.takeIf { state.messages.size >= 4 },
      backdrop = backdrop,
      onMenu = onOpenMenu,
      onNew = if (state.isNew) null else viewModel::newConversation,
    )

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
        onProvider = viewModel::useProvider,
        onThinking = viewModel::setThinking,
      )
    }
  }
}

/**
 * La velatura sotto la conversazione: due aloni morbidi nei colori del marchio.
 *
 * Il fondo di una chat deve restare fondo — il testo ci sta sopra per pagine intere — ma grigio
 * liscio non e' questa app. Due macchie ferme, appena accennate, agli angoli che il testo non
 * occupa mai; e stanno **dentro** la registrazione, cosi' il vetro della barra e del composer le
 * rifrange invece di galleggiarci sopra.
 */
@Composable
private fun Modifier.chatWash(): Modifier {
  val warm = MaterialTheme.colorScheme.primary
  val cool = MaterialTheme.colorScheme.tertiary
  return this.drawBehind {
    val topCentre = Offset(size.width * 0.88f, size.height * 0.06f)
    val topRadius = size.width * 0.95f
    drawCircle(
      brush = Brush.radialGradient(listOf(warm.copy(alpha = 0.11f), Color.Transparent), center = topCentre, radius = topRadius),
      radius = topRadius,
      center = topCentre,
    )
    val bottomCentre = Offset(size.width * 0.06f, size.height * 0.80f)
    val bottomRadius = size.width * 1.05f
    drawCircle(
      brush = Brush.radialGradient(listOf(cool.copy(alpha = 0.09f), Color.Transparent), center = bottomCentre, radius = bottomRadius),
      radius = bottomRadius,
      center = bottomCentre,
    )
  }
}

/**
 * La barra in cima: il menu, il titolo della conversazione, una chat nuova.
 *
 * Trasparente, con i due tasti su due dischi di vetro. La chat scorre sotto e continua a vedersi:
 * un'intestazione piena in cima a una conversazione ruba lo spazio che serve alle parole.
 */
@Composable
private fun ChatTopBar(
  title: String,
  facet: String?,
  backdrop: GlassBackdropState,
  onMenu: () -> Unit,
  onNew: (() -> Unit)?,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .statusBarsPadding()
      .height(56.dp)
      .padding(horizontal = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    GlassIcon(Icons.Rounded.Menu, "Conversazioni", backdrop, onMenu)
    Column(
      modifier = Modifier
        .weight(1f)
        .padding(horizontal = 8.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      if (title.isNotBlank()) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      facet?.let {
        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
    if (onNew != null) {
      GlassIcon(Icons.Rounded.Add, "Nuova conversazione", backdrop, onNew)
    } else {
      Spacer(Modifier.size(40.dp))
    }
  }
}

@Composable
private fun GlassIcon(icon: ImageVector, description: String, backdrop: GlassBackdropState, onClick: () -> Unit) {
  Box(
    modifier = Modifier
      .size(40.dp)
      .glassControlSurface(backdrop = backdrop, shape = FluidCapsuleShape)
      .fluidPressable(onClick = onClick, role = Role.Button),
    contentAlignment = Alignment.Center,
  ) {
    Icon(icon, contentDescription = description, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
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

/**
 * La domanda: una bolla colorata a destra, come in una chat.
 *
 * Non e' una card di vetro a tutta larghezza. In una conversazione le due voci si distinguono per
 * forma prima che per etichetta, e una domanda che occupa la riga intera quanto la risposta toglie
 * proprio quel segnale. La matita sta fuori, a sinistra: dentro rubava spazio a ogni messaggio.
 */
@Composable
private fun UserBubble(message: Message, onEdit: () -> Unit) {
  BoxWithConstraints(Modifier.fillMaxWidth()) {
    val maxBubble = maxWidth * 0.86f
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
      SmallAction(Icons.Rounded.Edit, "Modifica", onEdit)
      Column(
        modifier = Modifier
          .widthIn(max = maxBubble)
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
              FluidChip(label = attachment.name.take(28), selected = false, onClick = {}, leading = { Icon(if (attachment.kind == AttachmentKind.IMAGE) Icons.Rounded.Image else Icons.Rounded.AttachFile, contentDescription = null, modifier = Modifier.size(16.dp)) })
            }
          }
        }
      }
    }
  }
}

/**
 * La risposta: testo a tutta larghezza, senza card.
 *
 * Una risposta chiusa in un riquadro si legge come una scheda; una conversazione vuole che le
 * parole stiano sulla pagina. Sopra, quando c'e' stato del lavoro, una riga richiudibile dice cosa
 * ha fatto; sotto stanno le azioni e la telemetria, in piccolo, per chi la va a cercare.
 */
@Composable
private fun AssistantMessage(
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
  val busy = live != null && live.isBusy
  Column(Modifier.fillMaxWidth()) {
    run?.let { RunSteps(it) }
    if (busy) {
      AssistantTexts.statusLine(live!!)?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
      }
      if (pending != null) ConfirmationRow(pending, onResolve)
    }
    val liveText = (live as? AssistantState.Answering)?.partial
    val text = liveText?.takeIf { it.isNotBlank() } ?: message.text
    if (busy && text.isNotBlank()) Spacer(Modifier.height(8.dp))
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
    if (!busy && text.isNotBlank()) {
      Spacer(Modifier.height(2.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        SmallAction(Icons.Rounded.ContentCopy, "Copia", onCopy)
        SmallAction(Icons.Rounded.Share, "Condividi", onShare)
        SmallAction(Icons.Rounded.Refresh, "Rigenera", onRegenerate)
        run?.let {
          Spacer(Modifier.width(4.dp))
          Text(telemetry(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
      }
    }
  }
}

/**
 * Cosa ha fatto Aria prima di rispondere, in una riga che si apre.
 *
 * Sta **sopra** la risposta, non sotto: e' il lavoro che l'ha prodotta, e leggerlo dopo vuol dire
 * leggerlo quando non serve piu'. Chiusa dice una cosa sola ("Ho chiesto a Convert to it!");
 * aperta mostra modelli, gruppi e ogni chiamata con i suoi argomenti.
 */
@Composable
fun RunSteps(run: Run) {
  val hasDetails = run.tools.isNotEmpty() || run.error != null
  if (!hasDetails) return
  var details by rememberSaveable(run.id) { mutableStateOf(false) }
  Column(Modifier.padding(bottom = 8.dp)) {
    Row(
      modifier = Modifier
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), ContinuousCornerShape(FluidRadius.Control))
        .fluidPressable(onClick = { details = !details }, pressedScale = 1f, role = Role.Button, haptic = null)
        .padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
      Spacer(Modifier.width(8.dp))
      Text(
        text = toolSummary(run),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false),
      )
      Spacer(Modifier.width(6.dp))
      Icon(
        imageVector = if (details) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
        contentDescription = if (details) "Chiudi i passi" else "Mostra i passi",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(16.dp),
      )
    }
    if (details) {
      Spacer(Modifier.height(8.dp))
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
}

/**
 * La riga chiusa, in italiano corrente.
 *
 * Quando il lavoro e' andato tutto a una sola app lo dice per nome — "Ho chiesto a Convert to it!"
 * e' informazione, "2 strumenti" e' contabilita'.
 */
private fun toolSummary(run: Run): String {
  if (run.tools.isEmpty()) return run.error?.let { "Non ci sono riuscita" } ?: "Ho risposto da sola"
  val apps = run.tools.mapNotNull { it.app }.distinct()
  return when {
    apps.size == 1 && apps.first().isNotBlank() -> "Ho chiesto a ${apps.first()}"
    run.tools.size == 1 -> "Ho usato ${run.tools.first().name.replace('_', ' ')}"
    else -> "Ho usato ${run.tools.size} strumenti"
  }
}

@Composable
private fun LiveBubble(live: AssistantState, pending: PendingConfirmation?, onResolve: (Long, Boolean) -> Unit) {
  Column(Modifier.fillMaxWidth()) {
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
  val components = remember {
    markdownComponents(
      codeFence = { AriaCodeBlock(it) },
      codeBlock = { AriaCodeBlock(it) },
      table = { AriaTable(it) },
    )
  }
  Markdown(content = markdown, colors = markdownColor(), typography = markdownTypography(), components = components)
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
  onProvider: (ProviderId) -> Unit,
  onThinking: (ThinkingLevel) -> Unit,
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
  var modelSheet by remember { mutableStateOf(false) }

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
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .glassSurface(state = backdrop, tint = GlassDefaults.modalTint(), shape = ContinuousCornerShape(FluidRadius.Sheet), role = GlassRole.Modal)
        .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
      if (listening || transcribing) {
        VoiceVisualizer(micLevel = micLevel, partial = partial, transcribing = transcribing, onTap = onCancelVoice, modifier = Modifier.fillMaxWidth().height(44.dp))
      } else {
        FluidTextField(
          value = text,
          onValueChange = { text = it },
          placeholder = when {
            !state.enabled -> "Aggiungi una chiave nelle impostazioni"
            busy -> "Sto rispondendo..."
            editing != null -> "Modifica e rinvia..."
            else -> "Chiedi ad Aria..."
          },
          singleLine = false,
          maxLines = 6,
          enabled = !busy && state.enabled,
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
          keyboardActions = KeyboardActions(onSend = { submit() }),
          modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        BarIcon(icon = if (attachMenu) Icons.Rounded.Close else Icons.Rounded.Add, description = "Allega", onClick = { attachMenu = !attachMenu })
        // Chi risponde e quanto ci pensa, a portata di pollice: si cambia guardando la risposta
        // che non va, non tre schermate piu' in la'.
        FluidChip(label = modelLabel(state), selected = false, onClick = { modelSheet = true })
        Spacer(Modifier.weight(1f))
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

  ModelSheet(
    open = modelSheet,
    state = state,
    onProvider = { onProvider(it); modelSheet = false },
    onThinking = onThinking,
    onDismiss = { modelSheet = false },
  )
}

/** L'etichetta del chip: il modello che risponde adesso e quanto ci pensa. */
private fun modelLabel(state: ChatUiState): String {
  val provider = state.settings.chatOrder.firstOrNull { state.keys[it]?.verified == true }
    ?: state.settings.chatOrder.firstOrNull()
    ?: return "Nessun modello"
  val id = state.settings.chatModel(provider)
  val name = id?.let { state.catalogues[provider]?.chat(it)?.displayName ?: it.substringAfterLast('/') } ?: provider.label
  return name.take(22) + " \u00b7 " + state.settings.thinking.label()
}

/**
 * Il foglio del modello: chi risponde, e quanto ci pensa.
 *
 * Non e' il picker delle impostazioni, che sceglie un modello per ogni livello di ogni servizio.
 * Qui si risponde alla domanda che uno si fa davanti a una risposta storta -- "e se lo chiedo a un
 * altro?" -- e le leve sono queste due.
 */
@Composable
private fun ModelSheet(
  open: Boolean,
  state: ChatUiState,
  onProvider: (ProviderId) -> Unit,
  onThinking: (ThinkingLevel) -> Unit,
  onDismiss: () -> Unit,
) {
  FluidGlassModalPortal(
    item = if (open) Unit else null,
    onDismissRequest = onDismiss,
    presentation = FluidGlassModalPresentation.Sheet,
    paneTitle = "Modello",
  ) {
    val first = state.settings.chatOrder.firstOrNull { state.keys[it]?.verified == true }
    Text("Chi risponde", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
    Text(
      "Gli altri restano la riserva, nell'ordine, quando il primo e' al limite o non risponde.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 4.dp),
    )
    Spacer(Modifier.height(8.dp))
    FluidListGroup {
      state.settings.chatOrder.forEachIndexed { index, provider ->
        if (index > 0) FluidListDivider()
        val verified = state.keys[provider]?.verified == true
        val id = state.settings.chatModel(provider)
        FluidListRow(
          title = provider.label,
          subtitle = if (!verified) "Serve una chiave verificata." else (id?.let { state.catalogues[provider]?.chat(it)?.displayName ?: it } ?: "modello predefinito"),
          tone = if (provider == first) FluidTone.Primary else FluidTone.Neutral,
          onClick = if (verified) ({ onProvider(provider) }) else null,
        )
      }
    }
    Spacer(Modifier.height(16.dp))
    Text("Impegno", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 4.dp))
    Text(
      "Quanto il modello pensa prima di rispondere: piu' alto, piu' lento e piu' preciso.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 4.dp),
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
      ThinkingLevel.entries.forEach { level ->
        FluidChip(label = level.label(), selected = state.settings.thinking == level, onClick = { onThinking(level) })
      }
    }
    Spacer(Modifier.height(16.dp))
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
