package dev.pampa.pampai.core.assistant.runtime

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.antigravity.fluidengine.ai.keys.AiKeyStore
import dev.antigravity.fluidengine.ai.keys.AiSettings
import dev.antigravity.fluidengine.ai.keys.AiSettingsStore
import dev.antigravity.fluidengine.ai.net.AiError
import dev.antigravity.fluidengine.ai.orchestrator.AskMode
import dev.antigravity.fluidengine.ai.orchestrator.AssistantFailure
import dev.antigravity.fluidengine.ai.orchestrator.AssistantState
import dev.antigravity.fluidengine.ai.orchestrator.FailureKind
import dev.antigravity.fluidengine.ai.orchestrator.MicLevel
import dev.antigravity.fluidengine.ai.orchestrator.PendingConfirmation
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.antigravity.fluidengine.ai.speech.Transcriber
import dev.pampa.pampai.core.assistant.attachments.PendingAttachment
import dev.pampa.pampai.core.assistant.db.ConversationsRepository
import dev.pampa.pampai.core.assistant.service.AssistantForegroundService
import dev.pampa.pampai.core.assistant.tools.Surface
import dev.pampa.pampai.core.assistant.voice.AriaSpeaker
import dev.pampa.pampai.core.assistant.voice.DualSttEngine
import dev.pampa.pampai.core.assistant.voice.SttState
import dev.pampa.pampai.core.assistant.voice.VoiceConfirmation
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Con quale servizio e modello rispondere, quando l'utente lo sceglie per una singola domanda (rigenera con...). */
data class ProviderOverride(val provider: ProviderId, val chatModel: String?)

/** Una domanda da far partire: in quale conversazione (null = nuova), cosa, come e' arrivata, con cosa. */
data class AssistantRequest(
  val conversationId: Long?,
  val question: String,
  val mode: AskMode,
  val attachments: List<PendingAttachment> = emptyList(),
  val surface: Surface = Surface.APP,
  val override: ProviderOverride? = null,
  /** Rigenera: il messaggio dell'assistente da sostituire (la domanda resta quella salvata). */
  val regenerateMessageId: Long? = null,
)

/** Cosa e' successo a un ascolto che non ha prodotto una domanda: la barra decide cosa fare. */
sealed interface VoiceEvent {
  /** Nessuno ha parlato entro i primi secondi: la barra passa al testo senza dire niente. */
  data object InitialSilence : VoiceEvent

  /** Si e' parlato ma non e' uscito testo: la card lo dice e si chiude. */
  data object HeardNothing : VoiceEvent
}

/**
 * Lo stato di Aria per tutto il processo: la UI lo osserva, il service lo alimenta. Vive quanto
 * l'app; una domanda parte da qui ([submit]) e viene eseguita dal service in primo piano, cosi'
 * sopravvive alla chiusura dell'app. La voce si ascolta qui ([startListening], con
 * [DualSttEngine]) e si legge qui ([AriaSpeaker], solo per le domande fatte a voce), perche' il
 * microfono e l'altoparlante hanno senso solo con l'app (o la sessione) davanti.
 */
@Singleton
class AssistantRuntime @Inject constructor(
  @ApplicationContext private val context: Context,
  private val settingsStore: AiSettingsStore,
  private val keyStore: AiKeyStore,
  private val gate: PampaiConfirmationGate,
  private val conversations: ConversationsRepository,
  private val stt: DualSttEngine,
  private val speaker: AriaSpeaker,
  voiceConfirmation: VoiceConfirmation,
) {

  val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private val stateFlow = MutableStateFlow<AssistantState>(AssistantState.Idle)
  val state: StateFlow<AssistantState> = stateFlow

  /** Il livello del microfono viaggia per conto suo: cinquanta volte al secondo, e lo leggono solo l'alone e la barra. */
  val micLevel: StateFlow<MicLevel> = stt.micLevel

  /** Lo stato fine dell'ascolto (parziali, parlato, silenzio iniziale): per la barra visualizzatore. */
  val sttState: StateFlow<SttState> = stt.state

  /** Le parole riconosciute mentre si parla (parziali del sistema), prima della trascrizione finale. */
  val partialTranscript: StateFlow<String?> = stt.state
    .map { (it as? SttState.Listening)?.partial ?: (it as? SttState.Transcribing)?.partial }
    .stateIn(scope, SharingStarted.Eagerly, null)

  private val voiceEventsFlow = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 4)
  val voiceEvents: SharedFlow<VoiceEvent> = voiceEventsFlow

  /** Vero mentre Aria sta leggendo: il tasto per zittirla. */
  val speaking: StateFlow<Boolean> = speaker.speaking

  private val activeConversation = MutableStateFlow<Long?>(null)
  val activeConversationId: StateFlow<Long?> = activeConversation

  val pendingConfirmation: StateFlow<PendingConfirmation?> = gate.current

  /** Come e' arrivata l'ultima domanda: la lettura ad alta voce vale solo per quelle a voce. */
  private val lastModeFlow = MutableStateFlow(AskMode.TEXT)
  val lastMode: StateFlow<AskMode> = lastModeFlow

  /** Accesa = interruttore attivo e almeno una chiave verificata: la condizione per far partire qualsiasi cosa. */
  val enabled: Flow<Boolean> = combine(settingsStore.settings, keyStore.anyVerified) { s, anyKey -> s.enabled && anyKey }

  val settings: Flow<AiSettings> = settingsStore.settings

  /** Vero mentre un'Activity dell'app e' davanti: decide se la risposta va anche in notifica. */
  @Volatile var appInForeground: Boolean = false

  @Volatile private var pending: AssistantRequest? = null
  @Volatile internal var currentJob: Job? = null
  @Volatile private var voiceJob: Job? = null
  @Volatile private var quietCancel = false

  init {
    scope.launch { runCatching { conversations.failStale() } }
    voiceConfirmation.attach(lastModeFlow)
    // Un'azione che aspetta il si': lo stato lo dice — la card mostra Conferma/Annulla — e quando
    // la risposta arriva (o scade) si torna a com'era.
    scope.launch {
      var before: AssistantState? = null
      gate.current.collect { pending ->
        val current = stateFlow.value
        if (pending != null) {
          if (current !is AssistantState.AwaitingConfirmation && current.isBusy) {
            before = current
            stateFlow.value = AssistantState.AwaitingConfirmation(current.questionOrEmpty(), pending, current.providerOrNull() ?: ProviderId.defaultOrder.first())
          }
        } else if (current is AssistantState.AwaitingConfirmation) {
          stateFlow.value = before ?: AssistantState.Working(current.question, 0, 1, "thinking", 0, current.provider)
          before = null
        }
      }
    }
    // La lettura ad alta voce: frase per frase mentre la risposta scorre, per le domande a voce.
    scope.launch {
      var reading = false
      combine(stateFlow, settingsStore.settings) { s, cfg -> s to cfg.speakReplies }.collect { (current, speakReplies) ->
        val wanted = speakReplies && lastModeFlow.value == AskMode.VOICE
        when (current) {
          is AssistantState.Answering -> if (wanted) {
            if (!reading) {
              speaker.restart()
              reading = true
            }
            speaker.speakNewSentences(current.partial, final = false)
          }
          is AssistantState.Done -> {
            if (wanted) {
              if (!reading) speaker.restart()
              speaker.speakNewSentences(current.answer, final = true)
            }
            reading = false
          }
          is AssistantState.Failed, is AssistantState.Cancelled, is AssistantState.Listening -> {
            if (reading) speaker.stop()
            reading = false
          }
          else -> Unit
        }
      }
    }
  }

  val isBusy: Boolean get() = stateFlow.value.isBusy

  /** Fa partire una domanda: il service la esegue e la porta a termine anche ad app chiusa. */
  fun submit(request: AssistantRequest) {
    if (isBusy) cancel()
    enqueue(request)
  }

  private fun enqueue(request: AssistantRequest) {
    val text = request.question.trim()
    if (text.isEmpty() && request.attachments.isEmpty()) return
    speaker.stop()
    pending = request.copy(question = text.ifEmpty { "Guarda l'allegato." })
    lastModeFlow.value = request.mode
    activeConversation.value = request.conversationId
    stateFlow.value = AssistantState.Working(text, 0, 1, "thinking", 0, ProviderId.defaultOrder.first())
    ContextCompat.startForegroundService(context, Intent(context, AssistantForegroundService::class.java))
  }

  internal fun takePendingRequest(): AssistantRequest? {
    val request = pending
    pending = null
    return request
  }

  internal fun setState(state: AssistantState) {
    stateFlow.value = state
  }

  /** Quale conversazione continua la prossima domanda; null = se ne apre una nuova. */
  fun selectConversation(id: Long?) {
    activeConversation.value = id
  }

  internal fun setActiveConversation(id: Long?) = selectConversation(id)

  fun resolveConfirmation(id: Long, confirmed: Boolean) = gate.resolve(id, confirmed)

  /** Ferma tutto: ascolto, lettura, domanda in corso, conferma in attesa. Lo stato lo scrive chi viene fermato. */
  fun cancel() {
    stt.stopNow()
    voiceJob?.cancel()
    speaker.stop()
    gate.cancel()
    currentJob?.cancel(CancellationException("fermato dall'utente"))
    if (stateFlow.value.isBusy && currentJob == null) stateFlow.value = AssistantState.Cancelled(null, null)
  }

  /** Zittisce la lettura, senza toccare la domanda. */
  fun stopSpeaking() = speaker.stop()

  /** Torna al silenzio: dopo una risposta letta, un errore visto, una card chiusa. */
  fun reset() {
    if (!isBusy) stateFlow.value = AssistantState.Idle
  }

  /**
   * Il tocco sul tasto (o l'invocazione): ascolta con il motore doppio, e fa partire la domanda come
   * se fosse stata scritta. Se nessuno parla nei primi secondi esce [VoiceEvent.InitialSilence] e la
   * barra passa al testo; se si e' parlato senza esito, [VoiceEvent.HeardNothing].
   */
  fun startListening(conversationId: Long?, surface: Surface = Surface.APP, attachments: List<PendingAttachment> = emptyList()) {
    if (isBusy) cancel()
    speaker.stop()
    activeConversation.value = conversationId
    voiceJob = scope.launch {
      try {
        stateFlow.value = AssistantState.Listening(0L)
        val mirror = launch {
          stt.state.collect { s ->
            when (s) {
              is SttState.Listening -> {
                val elapsed = s.elapsedMillis / 1000 * 1000
                val shown = stateFlow.value
                if (shown !is AssistantState.Listening || shown.elapsedMillis != elapsed) stateFlow.value = AssistantState.Listening(elapsed)
              }
              is SttState.Transcribing -> stateFlow.value = AssistantState.Transcribing
              else -> Unit
            }
          }
        }
        val result = try {
          stt.listen(hint = Transcriber.hint(HINT, emptyList()))
        } finally {
          mirror.cancel()
        }
        when {
          result != null -> enqueue(AssistantRequest(conversationId, result.text, AskMode.VOICE, attachments, surface))
          stt.state.value == SttState.InitialSilence -> {
            stateFlow.value = AssistantState.Idle
            stt.reset()
            voiceEventsFlow.tryEmit(VoiceEvent.InitialSilence)
          }
          else -> {
            stateFlow.value = AssistantState.HeardNothing
            stt.reset()
            voiceEventsFlow.tryEmit(VoiceEvent.HeardNothing)
          }
        }
      } catch (e: CancellationException) {
        stateFlow.value = if (quietCancel) AssistantState.Idle else AssistantState.Cancelled(null, null)
        quietCancel = false
        stt.reset()
      } catch (e: AssistantFailure) {
        stateFlow.value = AssistantState.Failed(null, e.kind, e.error, e.retryAfterSec, null)
      } catch (e: Throwable) {
        stateFlow.value = AssistantState.Failed(null, FailureKind.UNKNOWN, e as? AiError, null, null)
      }
    }
  }

  /** Il secondo tocco mentre ascolta: si chiude la cattura e si trascrive quello che c'e'. */
  fun stopListening() = stt.stopNow()

  /** Il tocco sulla barra: si smette di ascoltare senza dire niente, e torna il campo di testo. */
  fun cancelListening() {
    val job = voiceJob ?: return
    quietCancel = true
    job.cancel()
    stt.stopNow()
  }

  private fun AssistantState.questionOrEmpty(): String = when (this) {
    is AssistantState.Classifying -> question
    is AssistantState.Working -> question
    is AssistantState.WaitingRateLimit -> question
    is AssistantState.SwitchingProvider -> question
    is AssistantState.Answering -> question
    is AssistantState.AwaitingConfirmation -> question
    else -> ""
  }

  private fun AssistantState.providerOrNull(): ProviderId? = when (this) {
    is AssistantState.Classifying -> provider
    is AssistantState.Working -> provider
    is AssistantState.WaitingRateLimit -> provider
    is AssistantState.SwitchingProvider -> to
    is AssistantState.Answering -> provider
    is AssistantState.AwaitingConfirmation -> provider
    else -> null
  }

  private companion object {
    const val HINT = "Domande a un assistente per il telefono e le app Pampa: meteo, autobus, registro scolastico, musica, sveglie, promemoria."
  }
}
