package dev.pampa.pampai.feature.assistant.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ai.orchestrator.AnswerChip
import dev.antigravity.fluidengine.ai.orchestrator.AssistantState
import dev.antigravity.fluidengine.ui.fluid.FluidScreenDefaults
import dev.antigravity.fluidengine.ui.fluid.LocalFluidGlassQuality
import dev.antigravity.fluidengine.ui.fluid.fluidGlassQualityScrollConnection
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberFluidGlassQuality
import dev.antigravity.fluidengine.ui.theme.FluidCard
import dev.antigravity.fluidengine.ui.theme.LocalRouteMotionSignals
import dev.pampa.pampai.core.assistant.db.Message
import dev.pampa.pampai.core.assistant.db.MessageRole
import dev.pampa.pampai.core.assistant.db.MessageStatus
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

  FollowStreaming(
    listState = listState,
    itemCount = state.messages.size + if (state.live != null) 1 else 0,
    answering = state.live is AssistantState.Answering,
  )

  val contextFacet = state.context?.let { "contesto ${(it.fraction * 100).toInt()}% di ${it.window / 1000}k" }
  val topSpace = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 56.dp

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
  val density = LocalDensity.current
  val deadZonePx = with(density) { FluidScreenDefaults.ShieldDeadZone.toPx() }
  val rampPx = with(density) { FluidScreenDefaults.ShieldRampDistance.toPx() }
  val barIntensity = remember(listState, deadZonePx, rampPx) {
    derivedStateOf {
      val travelled = if (listState.firstVisibleItemIndex > 0) Float.MAX_VALUE else listState.firstVisibleItemScrollOffset.toFloat()
      smoothStep(((travelled - deadZonePx) / rampPx.coerceAtLeast(1f)).coerceIn(0f, 1f))
    }
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
      LazyColumn(
        state = listState,
        modifier = Modifier
          .fillMaxSize()
          .nestedScroll(qualityConnection)
          .glassBackdropSource(backdrops.body),
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

      ChatTopBar(
        title = state.conversation?.title ?: "",
        // Il contesto e' un numero da guardare quando una conversazione e' lunga, non il primo
        // messaggio che l'app da' a chi apre una chat vuota.
        facet = contextFacet.takeIf { state.messages.size >= 4 },
        backdrop = backdrops.chrome,
        intensity = { barIntensity.value },
        onMenu = onOpenMenu,
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
          .padding(horizontal = 12.dp, vertical = 8.dp),
      ) {
        Composer(
          backdrop = backdrops.chrome,
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
          temporary = state.temporary,
          // Acceso: una chat nuova che non restera'. Spento: si esce dalla temporanea, e uscire
          // vuol dire che quella di prima non c'e' piu' — in entrambi i casi si riparte da bianco.
          onToggleTemporary = { if (state.temporary) viewModel.newConversation() else viewModel.newTemporaryConversation() },
          resampleIntervalMillis = if (aurora.present) ChatGlassResampleMillis else 0L,
        )
      }
    }
  }
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
