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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
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
import dev.antigravity.fluidengine.ui.fluid.FluidSegmentedControl
import dev.antigravity.fluidengine.ui.fluid.GlassFalloff
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
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
import kotlinx.coroutines.delay

/**
 * La chat con Aria: le domande, le risposte con la loro telemetria, e in fondo la barra per
 * continuare. Mentre una risposta arriva la si vede formarsi qui; lo stato vivo viene dal runtime,
 * il testo che resta da Room.
 */
@Composable
fun ChatRoute(
  bottomInset: Dp,
  backdrop: GlassBackdropState,
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
  val plugins by viewModel.plugins.collectAsStateWithLifecycle()
  val plugin by viewModel.plugin.collectAsStateWithLifecycle()
  val deepNext by viewModel.deepNext.collectAsStateWithLifecycle()
  val thinkingAuto by viewModel.thinkingAuto.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val listState = rememberLazyListState()
  var editing by remember { mutableStateOf<Message?>(null) }

  // Un messaggio nuovo porta la lista in fondo, con l'animazione.
  val count = state.messages.size + if (state.live != null) 1 else 0
  LaunchedEffect(count) {
    if (count > 0) runCatching { listState.animateScrollToItem(count + 1) }
  }
  // Mentre la risposta si forma, la lista la segue: senza animazione (una per token era il
  // singhiozzo che si vedeva) e solo se l'utente e' ancora in fondo. Chi e' risalito a rileggere
  // non va riportato giu' a forza.
  val answering = state.live is AssistantState.Answering
  LaunchedEffect(answering) {
    if (!answering) return@LaunchedEffect
    while (true) {
      val info = listState.layoutInfo
      val last = info.totalItemsCount - 1
      if (last >= 0 && info.visibleItemsInfo.lastOrNull()?.index == last) {
        runCatching { listState.scrollToItem(last, Int.MAX_VALUE) }
      }
      delay(120)
    }
  }

  val contextFacet = state.context?.let { "contesto ${(it.fraction * 100).toInt()}% di ${it.window / 1000}k" }
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
        thinkingAuto = thinkingAuto,
        onThinkingAuto = viewModel::setThinkingAuto,
        plugins = plugins,
        plugin = plugin,
        onPlugin = viewModel::setPlugin,
        deepNext = deepNext,
        onToggleDeep = viewModel::toggleDeepNext,
        onAttachImage = viewModel::attachImage,
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
    // Un velo verticale dall'alto, poi due aloni ai due angoli che il testo non occupa mai.
    drawRect(Brush.verticalGradient(listOf(warm.copy(alpha = 0.14f), Color.Transparent), startY = 0f, endY = size.height * 0.55f))
    val topCentre = Offset(size.width * 0.88f, size.height * 0.08f)
    val topRadius = size.width * 0.95f
    drawCircle(
      brush = Brush.radialGradient(listOf(warm.copy(alpha = 0.22f), Color.Transparent), center = topCentre, radius = topRadius),
      radius = topRadius,
      center = topCentre,
    )
    val bottomCentre = Offset(size.width * 0.06f, size.height * 0.82f)
    val bottomRadius = size.width * 1.05f
    drawCircle(
      brush = Brush.radialGradient(listOf(cool.copy(alpha = 0.18f), Color.Transparent), center = bottomCentre, radius = bottomRadius),
      radius = bottomRadius,
      center = bottomCentre,
    )
  }
}

/**
 * La barra in cima: il menu, il titolo della conversazione, una chat nuova.
 *
 * Non una lastra: un blur **progressivo**, pieno dove stanno titolo e tasti e che sfuma a niente
 * un po' piu' in basso, come le barre di iOS. La zona sfumata si prende 28 dp oltre la riga dei
 * tasti, cosi' la sfumatura succede *sotto* il titolo e non attraverso: era quello che faceva
 * filtrare il testo della pagina dentro alle parole. Una lastra uniforme, provata, aveva un bordo
 * netto in fondo, e in una chat il bordo netto e' esattamente la cosa che stona.
 */
@Composable
private fun ChatTopBar(
  title: String,
  facet: String?,
  backdrop: GlassBackdropState,
  onMenu: () -> Unit,
  onNew: (() -> Unit)?,
) {
  Column(
    Modifier
      .fillMaxWidth()
      .glassSurface(state = backdrop, tint = GlassDefaults.modalTint(), shape = RectangleShape, falloff = GlassFalloff.FadeDown, role = GlassRole.Bar)
      .statusBarsPadding()
      .padding(bottom = 28.dp),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .height(56.dp)
        .padding(horizontal = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      BarIcon(Icons.Rounded.Menu, "Conversazioni", onMenu)
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
      if (onNew != null) BarIcon(Icons.Rounded.Add, "Nuova conversazione", onNew) else Spacer(Modifier.size(40.dp))
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
 * La barra in fondo, nella forma delle chat che si usano: il campo sopra, e sotto la riga con il
 * "+", il modello che risponde e il microfono (o l'invio, o lo stop). Sopra la barra, quando ci
 * sono, le pillole: allegati, plugin scelto, "pensa piu' a fondo", modifica in corso.
 *
 * Il "+" apre un menu di vetro **sul tasto** (Fotocamera, Foto, File, Plugin, Pensa piu' a
 * fondo); il modello apre un pop-up sul suo chip. Entrambi vengono dal padrone di casa dei
 * modali alla radice, sullo stesso vetro della chat.
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
  thinkingAuto: Boolean,
  onThinkingAuto: (Boolean) -> Unit,
  plugins: List<PluginOption>,
  plugin: String?,
  onPlugin: (String?) -> Unit,
  deepNext: Boolean,
  onToggleDeep: () -> Unit,
  onAttachImage: (ByteArray, String) -> Unit,
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
  // L'anteprima basta: e' una foto per il modello, non per l'album. E non vuole ne' il permesso
  // della fotocamera ne' un FileProvider.
  val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
    if (bitmap != null) {
      val out = java.io.ByteArrayOutputStream()
      bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
      onAttachImage(out.toByteArray(), "foto.jpg")
    }
  }
  var plusMenu by remember { mutableStateOf(false) }
  var pluginPage by remember { mutableStateOf(false) }
  var modelMenu by remember { mutableStateOf(false) }
  // L'ancora dei due pop-up e' il composer intero, non il tasto: ancorati a un tasto in fondo allo
  // schermo si aprivano addosso al campo di testo. Contro il bordo alto del composer stanno sopra.
  var composerRect by remember { mutableStateOf<Rect?>(null) }
  // Il "+" invece si apre *su se stesso*: il menu parte dal suo rettangolo e ci ritorna.
  var plusRect by remember { mutableStateOf<Rect?>(null) }
  // Non il rettangolo intero ma il suo bordo alto, spesso un pixel: il pop-up nasce *contro*
  // l'ancora, e contro una riga sottile vuol dire sopra il composer, non a cavallo.
  val menuAnchor: () -> Rect? = { composerRect?.let { Rect(it.left, it.top - 4f, it.right, it.top) } }
  val chosenPlugin = plugins.firstOrNull { it.id == plugin }

  fun submit() {
    val query = text.trim()
    if ((query.isEmpty() && state.attachments.isEmpty()) || busy) return
    onSend(query)
    text = ""
  }

  Column {
    val pills = editing != null || state.attachments.isNotEmpty() || chosenPlugin != null || deepNext
    if (pills) {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)) {
        if (editing != null) Pill(Icons.Rounded.Edit, "Modifico il messaggio", accent = true, onClick = onCancelEdit)
        chosenPlugin?.let { Pill(Icons.Rounded.Extension, it.label, accent = true, onClick = { onPlugin(null) }) }
        if (deepNext) Pill(Icons.Rounded.Psychology, "Pensa piu' a fondo", accent = true, onClick = onToggleDeep)
        state.attachments.forEachIndexed { index, attachment -> AttachmentChip(attachment) { onRemoveAttachment(index) } }
      }
    }
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .onGloballyPositioned { composerRect = it.boundsInRoot() }
        // `Modal`, con riserva. La lente e' la stessa del `Floating` (20/28 dp contro 19/29): cambia
        // la sfocatura, 3.5 contro 1.8. Il `Floating` sarebbe il ruolo giusto per una capsula che
        // galleggia sulla chat, ma sull'emulatore ogni build con `Floating` su una superficie di
        // questa misura e' finita in ANR nel disegno, e quelle con `Modal` no. Le tracce dicono
        // "main thread affamato", non un loop: sul telefono va provato `Floating`, ed e' una parola.
        .glassSurface(state = backdrop, tint = GlassDefaults.modalTint(), shape = ContinuousCornerShape(26.dp), role = GlassRole.Modal)
        .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 8.dp),
    ) {
      if (listening || transcribing) {
        VoiceVisualizer(micLevel = micLevel, partial = partial, transcribing = transcribing, onTap = onCancelVoice, modifier = Modifier.fillMaxWidth().height(44.dp))
      } else {
        ComposerField(
          value = text,
          onValueChange = { text = it },
          placeholder = when {
            !state.enabled -> "Aggiungi una chiave nelle impostazioni"
            busy -> "Sto rispondendo..."
            editing != null -> "Modifica e rinvia..."
            else -> "Chiedi ad Aria..."
          },
          enabled = !busy && state.enabled,
          onSend = { submit() },
          modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
      }
      Spacer(Modifier.height(6.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        GlassRound(
          icon = Icons.Rounded.Add,
          description = "Allega o scegli",
          backdrop = backdrop,
          modifier = Modifier.onGloballyPositioned { plusRect = it.boundsInRoot() },
          onClick = { pluginPage = false; plusMenu = true },
        )
        Spacer(Modifier.width(8.dp))
        ModelPill(label = modelLabel(state, thinkingAuto), onClick = { modelMenu = true })
        Spacer(Modifier.weight(1f))
        when {
          !state.enabled -> GlassRound(Icons.Rounded.ArrowUpward, "Impostazioni", backdrop, onClick = onOpenSettings)
          listening -> GlassRound(Icons.Rounded.Stop, "Smetti di ascoltare", backdrop, tint = MaterialTheme.colorScheme.error, onClick = onStopVoice)
          busy -> GlassRound(Icons.Rounded.Stop, "Ferma", backdrop, onClick = onStop)
          speaking && text.isBlank() -> GlassRound(Icons.Rounded.VolumeOff, "Zitta", backdrop, onClick = onStopSpeaking)
          text.isBlank() && state.attachments.isEmpty() -> GlassRound(Icons.Rounded.Mic, "Parla", backdrop, onClick = { if (micGranted) onVoice() else micLauncher.launch(Manifest.permission.RECORD_AUDIO) })
          else -> GlassRound(Icons.Rounded.ArrowUpward, "Invia", backdrop, tint = MaterialTheme.colorScheme.primary, onClick = { submit() })
        }
      }
    }
  }

  // Il menu del "+": il tasto *diventa* il menu (presentazione `Expand`: parte dal suo
  // rettangolo, cresce con le molle della Fluid-physics, e ci ritorna quando si sceglie).
  // Due pagine: le azioni, e la scelta del plugin.
  FluidGlassModalPortal(
    visible = plusMenu,
    onDismissRequest = { plusMenu = false; pluginPage = false },
    origin = { plusRect },
    presentation = FluidGlassModalPresentation.Expand,
    paneTitle = if (pluginPage) "Plugin" else "Allega",
  ) {
    Column(Modifier.width(264.dp).padding(vertical = 4.dp)) {
      if (!pluginPage) {
        MenuRow(Icons.Rounded.PhotoCamera, "Fotocamera") { plusMenu = false; cameraLauncher.launch(null) }
        MenuRow(Icons.Rounded.Image, "Foto") { plusMenu = false; photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        MenuRow(Icons.Rounded.AttachFile, "File") { plusMenu = false; filePicker.launch(arrayOf("application/pdf", "text/*", "image/*")) }
        MenuDivider()
        MenuRow(Icons.Rounded.Extension, "Plugin", detail = chosenPlugin?.label, trailing = Icons.Rounded.ChevronRight) { pluginPage = true }
        MenuRow(Icons.Rounded.Psychology, "Pensa piu' a fondo", detail = "Livello profondo e ragionamento alto", checked = deepNext) { onToggleDeep(); plusMenu = false }
      } else {
        MenuRow(Icons.Rounded.ArrowBack, "Indietro") { pluginPage = false }
        MenuDivider()
        MenuRow(Icons.Rounded.Close, "Nessun plugin", detail = "Aria sceglie da sola", checked = plugin == null) { onPlugin(null); plusMenu = false; pluginPage = false }
        plugins.forEach { option ->
          MenuRow(Icons.Rounded.Extension, option.label, detail = option.hint.take(48), checked = option.id == plugin) { onPlugin(option.id); plusMenu = false; pluginPage = false }
        }
      }
    }
  }

  // Il modello: chi risponde e quanto ci pensa, in un pop-up sul chip.
  FluidGlassModalPortal(
    visible = modelMenu,
    onDismissRequest = { modelMenu = false },
    origin = menuAnchor,
    presentation = FluidGlassModalPresentation.Popover,
    paneTitle = "Modello",
  ) {
    ModelMenu(
      state = state,
      thinkingAuto = thinkingAuto,
      onProvider = { onProvider(it); modelMenu = false },
      onEffort = { effort ->
        when (effort) {
          Effort.AUTO -> onThinkingAuto(true)
          Effort.LOW -> { onThinkingAuto(false); onThinking(ThinkingLevel.LOW) }
          Effort.MEDIUM -> { onThinkingAuto(false); onThinking(ThinkingLevel.MEDIUM) }
          Effort.HIGH -> { onThinkingAuto(false); onThinking(ThinkingLevel.HIGH) }
        }
      },
    )
  }
}

/**
 * Il campo del composer: le parole direttamente sul vetro.
 *
 * `FluidTextField` disegna un pozzo grigio sotto il testo, giusto in un modulo e sbagliato qui: un
 * rettangolo pieno dentro una capsula di vetro e' quello che fa leggere il vetro come "una
 * trasparenza". Nelle chat che si usano il testo sta sulla superficie, e il bordo della capsula e'
 * l'unico contorno.
 */
@Composable
private fun ComposerField(
  value: String,
  onValueChange: (String) -> Unit,
  placeholder: String,
  enabled: Boolean,
  onSend: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val scheme = MaterialTheme.colorScheme
  BasicTextField(
    value = value,
    onValueChange = onValueChange,
    enabled = enabled,
    maxLines = 6,
    textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
    cursorBrush = SolidColor(scheme.primary),
    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
    keyboardActions = KeyboardActions(onSend = { onSend() }),
    modifier = modifier.padding(horizontal = 10.dp, vertical = 12.dp),
    decorationBox = { inner ->
      Box {
        if (value.isEmpty()) {
          Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant.copy(alpha = 0.75f))
        }
        inner()
      }
    },
  )
}

/** L'etichetta del chip: il modello che risponde adesso e quanto ci pensa. */
private fun modelLabel(state: ChatUiState, thinkingAuto: Boolean): String {
  val provider = state.settings.chatOrder.firstOrNull { state.keys[it]?.verified == true }
    ?: state.settings.chatOrder.firstOrNull()
    ?: return "Nessun modello"
  val id = state.settings.chatModel(provider)
  val name = id?.let { state.catalogues[provider]?.chat(it)?.displayName ?: it.substringAfterLast('/') } ?: provider.label
  val effort = if (thinkingAuto) "Auto" else state.settings.thinking.label()
  return name.take(20) + " \u00b7 " + effort
}

/** Le quattro posizioni del ragionamento nel pop-up: Auto piu' i tre livelli dell'engine. */
private enum class Effort(val label: String) { AUTO("Auto"), LOW("Basso"), MEDIUM("Medio"), HIGH("Alto") }

/**
 * Il contenuto del pop-up del modello: una riga per servizio (chi risponde adesso ha la spunta,
 * chi non ha la chiave e' spento) e il controllo dell'impegno.
 */
@Composable
private fun ModelMenu(
  state: ChatUiState,
  thinkingAuto: Boolean,
  onProvider: (ProviderId) -> Unit,
  onEffort: (Effort) -> Unit,
) {
  val first = state.settings.chatOrder.firstOrNull { state.keys[it]?.verified == true }
  Column(Modifier.width(300.dp).padding(vertical = 4.dp)) {
    Text("Chi risponde", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
    state.settings.chatOrder.forEach { provider ->
      val verified = state.keys[provider]?.verified == true
      val id = state.settings.chatModel(provider)
      val model = id?.let { state.catalogues[provider]?.chat(it)?.displayName ?: it } ?: "modello predefinito"
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .then(if (verified) Modifier.fluidPressable(onClick = { onProvider(provider) }, role = Role.Button, pressedScale = 1f, haptic = null) else Modifier)
          .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(Modifier.size(10.dp).background(providerColour(provider), FluidCapsuleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
          Text(provider.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = if (verified) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
          Text(if (verified) model else "Serve una chiave", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (provider == first) Icon(Icons.Rounded.Check, contentDescription = "In uso", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
      }
    }
    MenuDivider()
    Text("Impegno", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
    val selected = if (thinkingAuto) Effort.AUTO else when (state.settings.thinking) {
      ThinkingLevel.LOW -> Effort.LOW
      ThinkingLevel.MEDIUM -> Effort.MEDIUM
      ThinkingLevel.HIGH -> Effort.HIGH
    }
    Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
      FluidSegmentedControl(options = Effort.entries, selected = selected, onSelect = onEffort, label = { it.label })
    }
    Text(
      if (thinkingAuto) "Lo decide Aria: alto sulle domande profonde, basso su quelle secche." else "Fisso: vale per tutte le domande finche' non lo cambi.",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
  }
}

/** Un colore per servizio, per riconoscerli a colpo d'occhio nel pop-up. */
private fun providerColour(provider: ProviderId): Color = when (provider) {
  ProviderId.GROQ -> Color(0xFFF55036)
  ProviderId.GEMINI -> Color(0xFF4285F4)
  ProviderId.OPENROUTER -> Color(0xFF6E56CF)
}

/** Una riga di menu: icona, testo, dettaglio, e a destra una spunta o una freccia. */
@Composable
private fun MenuRow(
  icon: ImageVector,
  label: String,
  detail: String? = null,
  trailing: ImageVector? = null,
  checked: Boolean? = null,
  onClick: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .fluidPressable(onClick = onClick, role = Role.Button, pressedScale = 1f, haptic = null)
      .padding(horizontal = 16.dp, vertical = 11.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
    Spacer(Modifier.width(14.dp))
    Column(Modifier.weight(1f)) {
      Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
      detail?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
    when {
      checked == true -> Icon(Icons.Rounded.Check, contentDescription = "Attivo", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
      trailing != null -> Icon(trailing, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
  }
}

@Composable
private fun MenuDivider() {
  Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)))
}

/** Il chip del modello: una pillola bassa, che non ruba la riga al campo di testo. */
@Composable
private fun ModelPill(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
  Row(
    modifier = modifier
      .height(30.dp)
      .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), FluidCapsuleShape)
      .fluidPressable(onClick = onClick, role = Role.Button, haptic = null)
      .padding(start = 12.dp, end = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.width(2.dp))
    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
  }
}

/** Una pillola sopra la barra: un allegato, il plugin, la modalita'. Toccarla la toglie. */
@Composable
private fun Pill(icon: ImageVector, label: String, accent: Boolean = false, onClick: () -> Unit) {
  val bg = if (accent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
  val fg = if (accent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
  Row(
    modifier = Modifier
      .height(30.dp)
      .background(bg, FluidCapsuleShape)
      .fluidPressable(onClick = onClick, role = Role.Button, haptic = null)
      .padding(start = 10.dp, end = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
    Spacer(Modifier.width(6.dp))
    Text(label, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.width(4.dp))
    Icon(Icons.Rounded.Close, contentDescription = "Togli", tint = fg.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
  }
}

/**
 * Un tasto tondo di vetro, come quelli della barra nella sessione: il "+", il microfono, l'invio,
 * lo stop. Lo stesso disco per tutti, cambia solo l'icona e, quando serve, il colore.
 */
@Composable
private fun GlassRound(
  icon: ImageVector,
  description: String,
  backdrop: GlassBackdropState,
  modifier: Modifier = Modifier,
  tint: Color = MaterialTheme.colorScheme.onSurface,
  onClick: () -> Unit,
) {
  Box(
    modifier = modifier
      .size(40.dp)
      .glassControlSurface(backdrop = backdrop, shape = FluidCapsuleShape)
      .fluidPressable(onClick = onClick, role = Role.Button),
    contentAlignment = Alignment.Center,
  ) {
    Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(20.dp))
  }
}

@Composable
private fun AttachmentChip(attachment: PendingAttachment, onRemove: () -> Unit) {
  Pill(
    icon = if (attachment.kind == AttachmentKind.IMAGE) Icons.Rounded.Image else Icons.Rounded.AttachFile,
    label = attachment.name.take(22),
    onClick = onRemove,
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
