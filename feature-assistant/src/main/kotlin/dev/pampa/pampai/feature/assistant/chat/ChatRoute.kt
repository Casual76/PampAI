package dev.pampa.pampai.feature.assistant.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.antigravity.fluidengine.ai.orchestrator.AnswerChip
import dev.antigravity.fluidengine.ai.orchestrator.AssistantState
import dev.antigravity.fluidengine.foundation.EngineCompatibility
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidNotification
import dev.antigravity.fluidengine.ui.fluid.FluidScreenDefaults
import dev.antigravity.fluidengine.ui.fluid.LocalFluidGlassQuality
import dev.antigravity.fluidengine.ui.fluid.LocalFluidNotificationHostState
import dev.antigravity.fluidengine.ui.fluid.fluidGlassQualityScrollConnection
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberFluidGlassQuality
import dev.antigravity.fluidengine.ui.theme.FluidCard
import dev.antigravity.fluidengine.ui.theme.LocalRouteMotionSignals
import dev.pampa.pampai.core.assistant.db.Attachment
import dev.pampa.pampai.core.assistant.db.AttachmentKind
import dev.pampa.pampai.core.assistant.db.Message
import dev.pampa.pampai.core.assistant.db.MessageRole
import dev.pampa.pampai.core.assistant.db.MessageStatus
import dev.pampa.pampai.core.assistant.remote.RemoteStatus
import dev.pampa.pampai.core.assistant.remote.RemoteSwitches
import dev.pampa.pampai.core.assistant.runtime.ProviderOverride
import dev.pampa.pampai.feature.assistant.halo.HaloMood
import dev.pampa.pampai.feature.assistant.halo.haloMood

/**
 * La chat con Aria: le domande, le risposte con la loro telemetria, e in fondo la barra per
 * continuare. Mentre una risposta arriva la si vede formarsi qui; lo stato vivo viene dal runtime,
 * il testo che resta da Room.
 *
 * Gli strati sono quelli di `FluidScreen` con `ambient`: un fondale opaco registrato per primo
 * (fondo, velatura, e l'aurora quando arrivera'), il corpo trasparente registrato a parte, e sopra
 * la barra e il composer che rifrangono la pila dei due. Vedi [ChatBackdrops].
 */
@Composable
fun ChatRoute(
  bottomInset: Dp,
  backdrops: ChatBackdrops,
  onChip: (AnswerChip) -> Unit,
  onOpenMenu: () -> Unit,
  onOpenSettings: () -> Unit,
  /** La pagina del consenso, per "Aria e' spenta" quando il consenso non c'e' ancora. */
  onOpenConsent: () -> Unit = onOpenSettings,
  viewModel: ChatViewModel = hiltViewModel(),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val draft by viewModel.draft.collectAsStateWithLifecycle()
  val speaking by viewModel.speaking.collectAsStateWithLifecycle()
  val plugins by viewModel.plugins.collectAsStateWithLifecycle()
  val plugin by viewModel.plugin.collectAsStateWithLifecycle()
  val deepNext by viewModel.deepNext.collectAsStateWithLifecycle()
  val thinkingAuto by viewModel.thinkingAuto.collectAsStateWithLifecycle()
  val remote by viewModel.remoteStatus.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val composer = rememberComposerState()
  var viewing by remember { mutableStateOf<Attachment?>(null) }
  var details by remember { mutableStateOf<FailureReport?>(null) }
  val notifications = LocalFluidNotificationHostState.current
  LaunchedEffect(viewModel) {
    viewModel.notices.collect { text ->
      val host = notifications
      if (host != null) host.show(FluidNotification(id = "chat-${System.nanoTime()}", title = "Aria", message = text, durationMillis = 3_500L))
      else android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
    }
  }
  // Tornando nella chat, una domanda rimasta in coda mentre l'app era dietro parte adesso: dal
  // secondo piano il service in primo piano non si sarebbe avviato.
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  LaunchedEffect(lifecycle, viewModel) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.onChatVisible() }
  }
  // Un testo da fuori ("Condividi con Aria", una domanda tolta dalla coda) si aggiunge al campo.
  LaunchedEffect(draft) {
    val incoming = draft ?: return@LaunchedEffect
    composer.fill(incoming)
    viewModel.draft.value = null
  }

  // Una lista per conversazione: si apre in fondo, e tornando dalle impostazioni resta dov'era.
  val scrollKeys = rememberChatScrollKeys()
  val scroll = rememberChatScroll(scrollKeys.keyFor(state.conversation?.id, liveShown = state.live != null))
  val listState = scroll.list
  FollowBottom(scroll)

  val lastUserId = remember(state.messages) { state.messages.lastOrNull { it.role == MessageRole.USER }?.id }
  // I servizi pronti per "Rigenera con…", nell'ordine dell'utente.
  val readyProviders = remember(state.settings.chatOrder, state.keys) { state.settings.chatOrder.filter { state.keys[it]?.verified == true } }
  // Le lambda della home cambiano a ogni sua ricomposizione: si legge sempre l'ultima, e le azioni
  // restano lo stesso oggetto (altrimenti ogni item della lista si ricomporrebbe per niente).
  val currentOnChip by rememberUpdatedState(onChip)
  val currentOnOpenSettings by rememberUpdatedState(onOpenSettings)
  val currentOnOpenConsent by rememberUpdatedState(onOpenConsent)
  // "Rigenera" aspetta finche' Aria lavora (qui o per l'overlay): partirebbe fermandola. Le frecce
  // aspettano solo il lavoro su questa conversazione, che si attacca al ramo di adesso.
  val locked = state.anyBusy
  val versionsLocked = state.live?.isBusy == true
  val answerActions = remember(viewModel, scroll, readyProviders, locked, versionsLocked) {
    AnswerActions(
      onResolve = viewModel::resolve,
      onChip = { currentOnChip(it) },
      // La versione nuova e' l'ultima del ramo mostrato (quelle dopo restano nel ramo di prima):
      // si va in fondo a vederla nascere.
      onRegenerate = { message -> viewModel.regenerate(message); scroll.revealLatest() },
      onRegenerateWith = { message, provider -> viewModel.regenerate(message, ProviderOverride(provider, chatModel = null)); scroll.revealLatest() },
      onCopy = { message -> copy(context, message.text) },
      onShare = { message -> share(context, message.text) },
      onVersion = viewModel::selectVersion,
      onDetails = { details = it },
      regenerateWith = readyProviders,
      locked = locked,
      versionsLocked = versionsLocked,
    )
  }
  // A quale risposta appartiene lo stato vivo (vedi [LiveAnchor]).
  val liveAnchor = remember { LiveAnchor() }
  val liveId = liveAnchor.resolve(state.messages, state.live)

  val contextFacet = state.context?.let { "contesto ${(it.fraction * 100).toInt()}% di ${it.window / 1000}k" }
  val topSpace = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 56.dp

  // Il padding in fondo alla lista e' l'area che il composer copre davvero: la sua altezza misurata
  // (cresce con le righe, le pillole, gli allegati), piu' tastiera o barra di navigazione. Prima era
  // un numero fisso, e con tre righe nel campo o la tastiera aperta le ultime parole finivano sotto
  // il vetro. Si legge in misura, non in composizione: il campo che cresce non ricompone la pagina.
  val density = LocalDensity.current
  val composerHeight = remember { mutableIntStateOf(0) }
  val navigationBars = WindowInsets.navigationBars
  val ime = WindowInsets.ime
  val belowComposer = remember(navigationBars, ime) { navigationBars.union(ime) }
  val listPadding = remember(density, belowComposer, topSpace, bottomInset) {
    ChatListPadding(top = topSpace, horizontal = 16.dp) {
      with(density) { (composerHeight.intValue + belowComposer.getBottom(density)).toDp() } + bottomInset + ListBottomGap
    }
  }

  // Il vetro si assottiglia mentre la lista corre e torna intero quando si ferma, come in
  // `FluidScreen`: la velocita' e' un fatto della pagina, e sta sul `nestedScroll` del corpo cosi'
  // vede trascinamento, inerzia e overscroll insieme. Il tetto viene dal movimento fra pagine.
  // E' questo, non un riflesso congelato, a tenere giu' il conto di uno scorrimento.
  val routeSignals = LocalRouteMotionSignals.current
  val glassQuality = rememberFluidGlassQuality { routeSignals.glassQuality }
  val qualityConnection = remember(glassQuality) { fluidGlassQualityScrollConnection(glassQuality) }

  // L'aurora sul fondale segue l'umore di Aria: c'e' mentre ascolta, lavora o scrive, e non c'e' a
  // riposo, a risposta finita, o con il moto ridotto. Vedi ChatAmbient.
  val mood = state.live?.haloMood() ?: HaloMood.HIDDEN
  val aurora = rememberChatAurora(mood)

  // La barra in cima e' assente finche' la lista sta in cima e si addensa nei primi 64 dp di
  // scorrimento, con la zona morta e la rampa della barra di `FluidScreen`. Sopra il saluto di una
  // chat appena aperta non c'e' niente da sfocare, e una lastra li' era soltanto un film.
  val deadZonePx = with(density) { FluidScreenDefaults.ShieldDeadZone.toPx() }
  val rampPx = with(density) { FluidScreenDefaults.ShieldRampDistance.toPx() }
  val barIntensity = remember(listState, deadZonePx, rampPx) {
    derivedStateOf {
      val travelled = if (listState.firstVisibleItemIndex > 0) Float.MAX_VALUE else listState.firstVisibleItemScrollOffset.toFloat()
      smoothStep(((travelled - deadZonePx) / rampPx.coerceAtLeast(1f)).coerceIn(0f, 1f))
    }
  }

  val feeds = remember(viewModel) { ComposerFeeds(micLevel = viewModel.micLevel, partial = viewModel.runtimePartial, voiceEvents = viewModel.voiceEvents) }
  val composerActions = remember(viewModel, composer, scroll) {
    ComposerActions(
      onSend = { text ->
        val target = composer.editing
        val accepted = if (target != null) viewModel.editAndResend(target, text) else viewModel.send(text)
        if (accepted) scroll.revealLatest()
        accepted
      },
      onStop = viewModel::cancel,
      onVoice = viewModel::startVoice,
      onStopVoice = viewModel::stopVoice,
      onCancelVoice = viewModel::cancelVoice,
      onStopSpeaking = viewModel::stopSpeaking,
      onAttach = { viewModel.attach(it) },
      onRemoveAttachment = viewModel::removeAttachment,
      // La pillola sopra il composer: senza chiavi porta alle impostazioni; con Aria spenta la
      // riaccende subito se il consenso c'e', altrimenti apre il consenso.
      onOpenSettings = {
        if (state.blockedByConsent) {
          if (!viewModel.turnOnAria()) currentOnOpenConsent()
        } else {
          currentOnOpenSettings()
        }
      },
      onProvider = viewModel::useProvider,
      onThinking = viewModel::setThinking,
      onThinkingAuto = viewModel::setThinkingAuto,
      onPlugin = viewModel::setPlugin,
      onToggleDeep = viewModel::toggleDeepNext,
      // Acceso: una chat nuova che non restera'. Spento: si esce dalla temporanea, e uscire
      // vuol dire che quella di prima non c'e' piu' — in entrambi i casi si riparte da bianco.
      onToggleTemporary = { if (viewModel.state.value.temporary) viewModel.newConversation() else viewModel.newTemporaryConversation() },
    )
  }

  CompositionLocalProvider(LocalFluidGlassQuality provides glassQuality) {
    Box(Modifier.fillMaxSize()) {
      // 1. Il fondale: registrato per primo, opaco, senza un pixel della lista. La sorgente sta
      //    PRIMA di fondo e velatura nella catena, perche' `glassBackdropSource` registra solo i
      //    modifier dopo di se' e i figli: con il fondo fuori dalla registrazione il vetro
      //    campionava testo su trasparenza, ed era quello il "vetro che non sfoca".
      //    A riposo il fondale e' congelato: non cambia, e ri-registrarlo sarebbe lavoro per niente.
      //    Con l'aurora si rifa' a ogni fotogramma, ed e' l'unica cosa che si rifa': la lista sta
      //    nella registrazione accanto, e il testo non lo tocca nessuno.
      Box(
        Modifier
          .fillMaxSize()
          .glassBackdropSource(backdrops.canvas, frozen = { !aurora.present })
          .background(MaterialTheme.colorScheme.background)
          .chatWash(),
      ) {
        // L'aurora (ChatAmbient): vive in questa registrazione, mai in quella del corpo, cosi' un
        // fondale che si muove non rifa' mai il testo. Il microfono lo legge il loop di frame.
        ChatAurora(state = aurora, mood = mood, level = { viewModel.micLevel.value.level })
      }
      // 2. Il corpo: solo la lista, trasparente sul fondale, registrata a parte e sempre. Niente
      //    `frozen`: un riflesso fermo mentre la lista scorre e' la prima cosa che si vede sotto
      //    la barra, e il costo lo tiene giu' la qualita' che scala con la velocita'.
      //    Finche' una conversazione appena aperta non e' in fondo la lista non si disegna: sono
      //    uno o due fotogrammi, e senza si vedeva la cima prima del salto.
      LazyColumn(
        state = listState,
        modifier = Modifier
          .fillMaxSize()
          .nestedScroll(qualityConnection)
          .glassBackdropSource(backdrops.body)
          .drawWithContent { if (scroll.positioned) drawContent() },
        contentPadding = listPadding,
        verticalArrangement = Arrangement.spacedBy(20.dp),
      ) {
        state.recovery?.let {
          item(key = "recupero") {
            ChatBanner(
              title = "Conversazioni ripartite da zero",
              message = "Non sono riuscita a leggere le conversazioni salvate: ne ho fatto una copia e sono ripartita da zero.",
              urgent = false,
              action = "Ok",
              onAction = viewModel::dismissRecovery,
            )
          }
        }
        remoteBanner(remote)?.let { banner ->
          item(key = "remoto") {
            ChatBanner(banner.title, banner.message, banner.urgent, action = if (banner.update) "Cerca l'aggiornamento" else null, onAction = onOpenSettings)
          }
        }
        if (state.messages.isEmpty() && state.live == null && state.queued.isEmpty()) {
          item(key = "saluto") {
            EmptyGreeting(
              onSuggestion = { suggestion ->
                // Con Aria al lavoro (anche per l'overlay) il suggerimento va nel campo: mandarlo
                // adesso vorrebbe dire metterlo in coda senza che lo si sia scelto davvero.
                if (viewModel.state.value.anyBusy) composer.fill(suggestion) else if (viewModel.send(suggestion)) scroll.revealLatest()
              },
            )
          }
        }
        state.messages.forEach { message ->
          // Una chiave per posto, non per messaggio: le versioni di un messaggio si scambiano nello
          // stesso item (con il crossfade di MessageSlot) e la lista non salta.
          item(key = slotKey(message), contentType = message.role) {
            MessageSlot(message) { shown ->
              when (shown.role) {
                MessageRole.USER -> UserBubble(
                  shown,
                  showEdit = shown.id == lastUserId,
                  onEdit = { composer.startEditing(shown) },
                  onOpenAttachment = { attachment -> if (attachment.kind == AttachmentKind.IMAGE) viewing = attachment else AttachmentFiles.open(context, attachment) },
                  versionsEnabled = !versionsLocked,
                  onVersion = viewModel::selectVersion,
                )
                MessageRole.ASSISTANT -> AssistantMessage(
                  message = shown,
                  run = state.runs[shown.id],
                  live = liveFor(shown, state.live, liveId),
                  pending = state.pending,
                  actions = answerActions,
                )
              }
            }
          }
        }
        // Una conversazione nuova: la domanda in corso non e' ancora su disco.
        if (state.messages.isEmpty() && state.live != null) {
          item(key = "live") { LiveBubble(state.live!!, state.pending, viewModel::resolve) }
        }
        state.queued.forEachIndexed { index, question ->
          item(key = "coda-$index") { QueuedBubble(question, onCancel = { viewModel.unqueue(question) }) }
        }
      }

      ChatTopBar(
        title = state.conversation?.title ?: "",
        // Il contesto e' un numero da guardare quando una conversazione e' lunga, non il primo
        // messaggio che l'app da' a chi apre una chat vuota.
        facet = contextFacet.takeIf { state.messages.size >= 4 },
        backdrop = backdrops.chrome,
        intensity = { barIntensity.value },
        onMenu = onOpenMenu,
        // "Nuova chat" non ferma il lavoro in corso: la risposta finisce nella sua conversazione.
        onNew = if (state.isNew) null else viewModel::newConversation,
        temporary = state.temporary,
        // Sopra un fondale animato i vetri ricampionano a intervalli, non a ogni fotogramma.
        resampleIntervalMillis = if (aurora.present) ChatGlassResampleMillis else 0L,
      )

      Box(
        Modifier
          .align(Alignment.BottomCenter)
          .navigationBarsPadding()
          .imePadding()
          .padding(bottom = bottomInset)
          // La misura comprende il margine attorno alla capsula: e' quanto della lista copre davvero.
          .onSizeChanged { composerHeight.intValue = it.height }
          .padding(horizontal = 12.dp, vertical = 8.dp),
      ) {
        Composer(
          backdrop = backdrops.chrome,
          state = state,
          composer = composer,
          model = ComposerModel(
            speaking = speaking,
            thinkingAuto = thinkingAuto,
            plugins = plugins,
            plugin = plugin,
            deepNext = deepNext,
            temporary = state.temporary,
          ),
          feeds = feeds,
          actions = composerActions,
          resampleIntervalMillis = if (aurora.present) ChatGlassResampleMillis else 0L,
        )
      }
    }
  }
  viewing?.let { attachment -> ImageViewer(attachment, onDismiss = { viewing = null }) }
  FailureDetailsSheet(report = details, onDismiss = { details = null })
}

/** L'aria fra l'ultima riga e il bordo del composer. */
private val ListBottomGap = 12.dp

/**
 * Il padding della lista, con il fondo letto in misura: [bottom] legge l'altezza del composer e gli
 * inset, due stati che cambiano mentre si scrive o si apre la tastiera, e letti qui rimisurano la
 * lista senza ricomporre la pagina.
 */
@Stable
private class ChatListPadding(private val top: Dp, private val horizontal: Dp, private val bottom: () -> Dp) : PaddingValues {
  override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp = horizontal
  override fun calculateTopPadding(): Dp = top
  override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp = horizontal
  override fun calculateBottomPadding(): Dp = bottom()
}

/**
 * Quale risposta sta ricevendo lo stato vivo. Lo stato non porta l'id del messaggio: e' l'ultima
 * risposta vista in corso su disco (`PENDING`/`STREAMING`), e resta sua anche dopo che Room l'ha
 * chiusa, finche' il runtime resta sullo stato finale — cosi' la fine della risposta anima fino in
 * fondo invece di spegnersi al primo `DONE` salvato. Si libera quando lo stato vivo non c'e' piu'.
 *
 * Campi semplici: li scrive la composizione con gli stessi ingressi e lo stesso esito.
 */
private class LiveAnchor {
  private var id: Long? = null

  fun resolve(messages: List<Message>, live: AssistantState?): Long? {
    if (live == null) {
      id = null
      return null
    }
    messages.lastOrNull { it.role == MessageRole.ASSISTANT && (it.status == MessageStatus.PENDING || it.status == MessageStatus.STREAMING) }?.let { id = it.id }
    return id
  }
}

/**
 * Lo stato vivo da dare a [message]. A una risposta ancora aperta su disco, tutto. A una gia'
 * chiusa, solo cio' che ne racconta la fine: `Done`, `Failed`, `Cancelled`, e l'ultimo
 * `Answering` (Room puo' chiuderla un attimo prima che il runtime pubblichi `Done`). Mai il
 * lavoro della domanda dopo: quello, finche' la sua risposta non e' su disco, non e' di nessuno.
 */
private fun liveFor(message: Message, live: AssistantState?, liveId: Long?): AssistantState? {
  if (live == null || message.id != liveId) return null
  val open = message.status == MessageStatus.PENDING || message.status == MessageStatus.STREAMING
  return if (open || live is AssistantState.Answering || !live.isBusy) live else null
}

/**
 * La curva della rampa della barra, la stessa di `FluidScreen` (`t * t * (3 - 2t)`): parte e
 * arriva piatta, cosi' il materiale non scatta ne' al primo pixel ne' all'ultimo. Copiata perche'
 * quella dell'engine e' `internal`, e sono tre operazioni.
 */
private fun smoothStep(value: Float): Float {
  val t = value.coerceIn(0f, 1f)
  return t * t * (3f - 2f * t)
}

/**
 * Un avviso in cima alla chat: il file di controllo remoto, il database ripartito da zero. Una
 * card opaca (il vetro nel contenuto qui non serve: e' testo da leggere), con un tasto se c'e'
 * qualcosa da fare.
 */
@Composable
private fun ChatBanner(title: String, message: String, urgent: Boolean, action: String?, onAction: () -> Unit) {
  FluidCard(glass = false) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    Spacer(Modifier.height(4.dp))
    Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (action != null) {
      Spacer(Modifier.height(10.dp))
      FluidButton(text = action, onClick = onAction, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Small)
    }
  }
}

private data class RemoteBanner(val title: String, val message: String, val urgent: Boolean, val update: Boolean)

/**
 * Cosa dire in cima alla chat quando il file di controllo ha qualcosa da dire: Aria ferma, una
 * versione troppo vecchia, un avviso. Prima tutto questo finiva nel log e nessuno lo vedeva.
 */
private fun remoteBanner(status: RemoteStatus): RemoteBanner? = when {
  status.stopped -> RemoteBanner("Aria e' in pausa", status.message ?: RemoteSwitches.DEFAULT_STOP, urgent = true, update = true)
  status.compatibility == EngineCompatibility.UPDATE_REQUIRED -> RemoteBanner("Serve un aggiornamento", "Questa versione di PampAI e' troppo vecchia: alcune cose potrebbero non funzionare. Aggiorna dal Pampa Store.", urgent = true, update = true)
  status.compatibility == EngineCompatibility.UPDATE_RECOMMENDED -> RemoteBanner("C'e' un aggiornamento", status.notice ?: "Una versione nuova di PampAI e' pronta nel Pampa Store.", urgent = false, update = true)
  else -> status.notice?.takeIf { it.isNotBlank() }?.let { RemoteBanner("Avviso", it, urgent = false, update = false) }
}
