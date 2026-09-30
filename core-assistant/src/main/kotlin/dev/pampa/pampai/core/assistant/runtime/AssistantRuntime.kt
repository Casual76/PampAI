package dev.pampa.pampai.core.assistant.runtime

import android.content.Context
import android.content.Intent
import android.util.Log
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
import dev.pampa.pampai.core.assistant.db.Anchor
import dev.pampa.pampai.core.assistant.db.ConversationsRepository
import dev.pampa.pampai.core.assistant.service.AssistantForegroundService
import dev.pampa.pampai.core.assistant.service.AssistantNotifications
import dev.pampa.pampai.core.assistant.tools.Surface
import dev.pampa.pampai.core.assistant.usage.UsageRepository
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
import kotlinx.coroutines.cancelAndJoin
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

/**
 * Con quale servizio e modello rispondere, quando l'utente lo sceglie per una singola domanda
 * (rigenera con...). [deepModel] e' il profondo per quella domanda, se scelto: null = quello delle
 * impostazioni. Una scelta esplicita fissa anche il servizio: nessuna riserva per quella domanda.
 */
data class ProviderOverride(val provider: ProviderId, val chatModel: String?, val deepModel: String? = null)

/** Una domanda da far partire: in quale conversazione (null = nuova), cosa, come e' arrivata, con cosa. */
data class AssistantRequest(
  val conversationId: Long?,
  val question: String,
  val mode: AskMode,
  val attachments: List<PendingAttachment> = emptyList(),
  val surface: Surface = Surface.APP,
  val override: ProviderOverride? = null,
  /**
   * Dove si attacca nell'albero dei messaggi: in fondo al ramo mostrato ([Anchor.Continue]), come
   * versione nuova di una domanda ([Anchor.Edit], "modifica e rinvia") o di una risposta
   * ([Anchor.Regenerate]). Con "rigenera" la domanda (testo e allegati) e' quella salvata.
   */
  val anchor: Anchor = Anchor.Continue,
  /** Il plugin scelto nel composer (id di categoria): si salva sulla conversazione e guida il primo giro. */
  val plugin: String? = null,
  /** "Pensa piu' a fondo": livello profondo e ragionamento alto per questa domanda. */
  val deep: Boolean = false,
  /**
   * Chat temporanea: vale solo quando la conversazione nasce adesso ([conversationId] nullo), ed e'
   * la riga su disco a portarsela dietro per tutte le domande dopo.
   */
  val temporary: Boolean = false,
  /**
   * La domanda e' stata fatta dal telefono bloccato. Deciso quando la si fa, non quando parte:
   * la sessione che si chiude azzera lo stato dello schermo, e una domanda partita un attimo dopo
   * avrebbe avuto tutti gli strumenti e la memoria.
   */
  val locked: Boolean = false,
)

/** Cosa e' successo a un ascolto che non ha prodotto una domanda: la barra decide cosa fare. */
sealed interface VoiceEvent {
  /** Nessuno ha parlato entro i primi secondi: la barra passa al testo senza dire niente. */
  data object InitialSilence : VoiceEvent

  /** Si e' parlato ma non e' uscito testo: la card lo dice e si chiude. */
  data object HeardNothing : VoiceEvent
}

/** Una domanda in coda per il service, con la generazione ([AssistantRuntime]) a cui appartiene. */
internal class QueuedRequest(val request: AssistantRequest, val token: Long)

/**
 * Lo stato di Aria per tutto il processo: la UI lo osserva, il service lo alimenta. Vive quanto
 * l'app; una domanda parte da qui ([submit]) e viene eseguita dal service in primo piano, cosi'
 * sopravvive alla chiusura dell'app. La voce si ascolta qui ([startListening], con
 * [DualSttEngine]) e si legge qui ([AriaSpeaker], solo per le domande fatte a voce), perche' il
 * microfono e l'altoparlante hanno senso solo con l'app (o la sessione) davanti.
 *
 * Lo stato vivo ha un padrone solo, la **generazione**: cresce a ogni domanda accodata, a ogni
 * ascolto, a ogni "ferma". Chi scrive lo stato porta la sua ([publish]), e se nel frattempo e'
 * cresciuta la scrittura non passa. Cosi' le pulizie di un lavoro fermato (il Cancelled di una
 * domanda vecchia, l'Idle di un ascolto chiuso) non coprono mai quello nuovo: prima "Nuova chat"
 * durante una risposta lasciava nella chat vuota la bolla "fermata" della domanda di prima.
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
  private val usage: UsageRepository,
  private val notifications: AssistantNotifications,
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

  /** La conversazione della chat dell'app. */
  val activeConversationId: StateFlow<Long?> = activeConversation

  /**
   * La conversazione dell'overlay di sistema, separata da quella dell'app.
   *
   * Erano la stessa: aprire Aria sopra un'altra app faceva `selectConversation(null)` e, tornando
   * nell'app, la chat che si stava leggendo era sparita dietro una pagina vuota.
   */
  private val sessionConversation = MutableStateFlow<Long?>(null)
  val sessionConversationId: StateFlow<Long?> = sessionConversation

  /**
   * Di chi e' lo stato vivo: da quale superficie e' partita la domanda e su quale conversazione.
   * La chat dell'app mostra il lavoro in corso solo se e' suo, cosi' una risposta dell'overlay non
   * compare come una bolla in una chat nuova dell'app.
   */
  private val liveOwnerFlow = MutableStateFlow(LiveOwner(Surface.APP, null))
  val liveOwner: StateFlow<LiveOwner> = liveOwnerFlow

  /**
   * Le tre cose qui sopra in un valore solo, scritto in un colpo: chi le confronta fra loro (il
   * filtro dello stato vivo della chat e dell'overlay) deve leggere questa, perche' tre flussi
   * separati si possono vedere a meta' di un cambio (il padrone nuovo con la conversazione vecchia).
   * Le proprieta' separate restano per chi ne legge una sola, e per il `.value` letto subito dopo
   * un [selectConversation].
   */
  private val liveTrackFlow = MutableStateFlow(LiveTrack(null, null, LiveOwner(Surface.APP, null)))
  val liveTrack: StateFlow<LiveTrack> = liveTrackFlow

  val pendingConfirmation: StateFlow<PendingConfirmation?> = gate.current

  /** Come e' arrivata l'ultima domanda: la lettura ad alta voce vale solo per quelle a voce. */
  private val lastModeFlow = MutableStateFlow(AskMode.TEXT)
  val lastMode: StateFlow<AskMode> = lastModeFlow

  /** Accesa = interruttore attivo e almeno una chiave verificata: la condizione per far partire qualsiasi cosa. */
  val enabled: Flow<Boolean> = combine(settingsStore.settings, keyStore.anyVerified) { s, anyKey -> s.enabled && anyKey }

  val settings: Flow<AiSettings> = settingsStore.settings

  /** Vero mentre un'Activity dell'app e' davanti: decide se la risposta va anche in notifica. */
  @Volatile var appInForeground: Boolean = false

  /** Generazione, stato, coda, lavoro corrente e ascolto cambiano insieme, sotto questo lucchetto. */
  private val lock = Any()
  private var generation = 0L
  private var pending: QueuedRequest? = null
  private var running: Job? = null
  private var beforeConfirmation: AssistantState? = null
  @Volatile private var voiceJob: Job? = null
  private var voiceToken = -1L

  init {
    scope.launch { runCatching { conversations.failStale() } }
    // Manutenzione: senza, consumi e tracce crescevano per sempre.
    scope.launch {
      val now = System.currentTimeMillis()
      runCatching { conversations.compact(now) }
      runCatching { usage.prune(now) }
    }
    // Una chat temporanea non sopravvive al processo. Di solito la cancella chi la lascia (il
    // ChatViewModel), ma un'app uccisa dal sistema mentre la chat era aperta non passa di li':
    // qui, prima che qualunque domanda possa partire, il disco torna pulito. Non serve avvisare
    // l'engine: la sua memoria per processo e' ancora vuota.
    scope.launch { runCatching { conversations.deleteTemporary() } }
    voiceConfirmation.attach(lastModeFlow)
    // Un'azione che aspetta il si': lo stato lo dice — la card mostra Conferma/Annulla — e quando
    // la risposta arriva (o scade) si torna a com'era.
    scope.launch {
      gate.current.collect { asked ->
        synchronized(lock) {
          val current = stateFlow.value
          if (asked != null) {
            if (current !is AssistantState.AwaitingConfirmation && current.isBusy) {
              beforeConfirmation = current
              stateFlow.value = AssistantState.AwaitingConfirmation(current.questionOrEmpty(), asked, current.providerOrNull() ?: ProviderId.defaultOrder.first())
            }
          } else if (current is AssistantState.AwaitingConfirmation) {
            stateFlow.value = beforeConfirmation ?: AssistantState.Working(current.question, 0, 1, "thinking", 0, current.provider)
            beforeConfirmation = null
          }
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

  /**
   * Accoda [request] con una generazione nuova e avvia il service. Con [expected] (una domanda
   * uscita da un ascolto) parte solo se la generazione e' ancora quella dell'ascolto: un "ferma"
   * arrivato mentre si trascriveva vince.
   *
   * @return false se non e' partita (vuota, superata, o il service non si e' avviato).
   */
  private fun enqueue(request: AssistantRequest, expected: Long? = null): Boolean {
    val text = request.question.trim()
    if (text.isEmpty() && request.attachments.isEmpty()) return false
    val queued = synchronized(lock) {
      if (expected != null && expected != generation) return false
      val made = QueuedRequest(request.copy(question = text.ifEmpty { "Guarda l'allegato." }), ++generation)
      pending = made
      beforeConfirmation = null
      lastModeFlow.value = request.mode
      track(request.surface, request.conversationId)
      stateFlow.value = AssistantState.Working(text, 0, 1, "thinking", 0, ProviderId.defaultOrder.first())
      made
    }
    speaker.stop()
    val started = runCatching { ContextCompat.startForegroundService(context, Intent(context, AssistantForegroundService::class.java)) }
    val error = started.exceptionOrNull() ?: return true
    notStarted(queued, error)
    return false
  }

  /**
   * Il service non e' partito: succede quando una trascrizione finisce con l'app gia' in secondo
   * piano (da Android 12 un service in primo piano non parte da li'). La domanda non si perde in
   * silenzio: lo stato lo dice e una notifica la riporta, da rifare con l'app aperta.
   */
  private fun notStarted(queued: QueuedRequest, error: Throwable) {
    Log.w(TAG, "il service non e' partito", error)
    synchronized(lock) {
      if (pending === queued) pending = null
      if (generation == queued.token) stateFlow.value = AssistantState.Failed(queued.request.question, FailureKind.UNKNOWN, null, null, null)
    }
    notifications.showNotStarted(queued.request.question)
  }

  /** Il service prende la domanda in coda (una sola: l'ultima accodata). */
  internal fun takePendingRequest(): QueuedRequest? = synchronized(lock) {
    val queued = pending
    pending = null
    queued
  }

  /**
   * Lo stato della generazione [token]: passa solo se e' ancora quella di adesso. Mentre la card
   * chiede una conferma, uno stato di lavoro che arriva resta da parte e torna dopo il si' o il no.
   *
   * @return false se la scrittura e' stata scartata perche' [token] e' superato.
   */
  internal fun publish(token: Long, state: AssistantState): Boolean = synchronized(lock) {
    if (token != generation) return false
    val shown = stateFlow.value
    if (shown is AssistantState.AwaitingConfirmation && gate.current.value != null && state.isBusy && state !is AssistantState.AwaitingConfirmation) {
      beforeConfirmation = state
    } else {
      stateFlow.value = state
    }
    true
  }

  /** Il lavoro della domanda [token] comincia: false se nel frattempo e' stata fermata o superata. */
  internal fun attach(token: Long, job: Job): Boolean = synchronized(lock) {
    if (token != generation) return false
    running = job
    true
  }

  internal fun detach(job: Job) {
    synchronized(lock) { if (running === job) running = null }
  }

  /** Quale conversazione continua la prossima domanda dell'app; null = se ne apre una nuova. */
  fun selectConversation(id: Long?) {
    synchronized(lock) {
      activeConversation.value = id
      liveTrackFlow.value = liveTrackFlow.value.copy(app = id)
    }
  }

  /** Come [selectConversation], per l'overlay di sistema. */
  fun selectSessionConversation(id: Long?) {
    synchronized(lock) {
      sessionConversation.value = id
      liveTrackFlow.value = liveTrackFlow.value.copy(session = id)
    }
  }

  /** La domanda [token] di [surface] lavora su [id]: la superficie la segue, e lo stato vivo e' suo. */
  internal fun setActiveConversation(id: Long?, surface: Surface, token: Long) {
    synchronized(lock) { if (token == generation) track(surface, id) }
  }

  /** Da chiamare sotto [lock]. */
  private fun track(surface: Surface, id: Long?) {
    val owner = LiveOwner(surface, id)
    if (surface == Surface.SESSION) sessionConversation.value = id else activeConversation.value = id
    liveOwnerFlow.value = owner
    liveTrackFlow.value = if (surface == Surface.SESSION) liveTrackFlow.value.copy(session = id, owner = owner) else liveTrackFlow.value.copy(app = id, owner = owner)
  }

  fun resolveConfirmation(id: Long, confirmed: Boolean) = gate.resolve(id, confirmed)

  /**
   * Ferma tutto: ascolto, lettura, domanda in coda o in corso, conferma in attesa. Lo stato
   * "fermata" lo scrive subito questo tasto, con il testo parziale che si vedeva; chi viene fermato
   * scrive su disco, ma il suo stato non passa piu' (la generazione e' cresciuta).
   */
  fun cancel() {
    val job = synchronized(lock) {
      generation++
      pending = null
      beforeConfirmation = null
      val shown = stateFlow.value
      if (shown.isBusy) stateFlow.value = AssistantState.Cancelled(shown.questionOrEmpty().ifEmpty { null }, (shown as? AssistantState.Answering)?.partial)
      val job = running
      running = null
      job
    }
    stt.stopNow()
    voiceJob?.cancel()
    speaker.stop()
    gate.cancel()
    job?.cancel(CancellationException("fermato dall'utente"))
  }

  /** Zittisce la lettura, senza toccare la domanda. */
  fun stopSpeaking() = speaker.stop()

  /** Torna al silenzio: dopo una risposta letta, un errore visto, una card chiusa. */
  fun reset() {
    synchronized(lock) { if (!stateFlow.value.isBusy) stateFlow.value = AssistantState.Idle }
  }

  /**
   * Il tocco sul tasto (o l'invocazione): ascolta con il motore doppio, e fa partire la domanda come
   * se fosse stata scritta. Se nessuno parla nei primi secondi esce [VoiceEvent.InitialSilence] e la
   * barra passa al testo; se si e' parlato senza esito, [VoiceEvent.HeardNothing].
   */
  fun startListening(conversationId: Long?, surface: Surface = Surface.APP, attachments: List<PendingAttachment> = emptyList(), temporary: Boolean = false, locked: Boolean = false) {
    if (isBusy) cancel()
    speaker.stop()
    val previous = voiceJob
    val token = synchronized(lock) {
      val token = ++generation
      voiceToken = token
      beforeConfirmation = null
      track(surface, conversationId)
      stateFlow.value = AssistantState.Listening(0L)
      token
    }
    voiceJob = scope.launch {
      // Un microfono solo: l'ascolto di prima si chiude del tutto (pulizie comprese) prima che
      // questo cominci, cosi' non tocca il motore della voce mentre lavora per quello nuovo.
      previous?.cancelAndJoin()
      try {
        val mirror = launch {
          stt.state.collect { s ->
            when (s) {
              is SttState.Listening -> publish(token, AssistantState.Listening(s.elapsedMillis / 1000 * 1000))
              is SttState.Transcribing -> publish(token, AssistantState.Transcribing)
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
          result != null -> enqueue(AssistantRequest(conversationId, result.text, AskMode.VOICE, attachments, surface, temporary = temporary, locked = locked), expected = token)
          stt.state.value == SttState.InitialSilence -> if (publish(token, AssistantState.Idle)) {
            stt.reset()
            voiceEventsFlow.tryEmit(VoiceEvent.InitialSilence)
          }
          else -> if (publish(token, AssistantState.HeardNothing)) {
            stt.reset()
            voiceEventsFlow.tryEmit(VoiceEvent.HeardNothing)
          }
        }
      } catch (e: CancellationException) {
        // Chi l'ha fermato ha gia' scritto lo stato (cancel, cancelListening, un ascolto nuovo che
        // aspetta questo): qui resta solo da rimettere a riposo il motore della voce.
        stt.reset()
        throw e
      } catch (e: AssistantFailure) {
        publish(token, AssistantState.Failed(null, e.kind, e.error, e.retryAfterSec, null))
      } catch (e: Throwable) {
        publish(token, AssistantState.Failed(null, FailureKind.UNKNOWN, e as? AiError, null, null))
      }
    }
  }

  /** Il secondo tocco mentre ascolta: si chiude la cattura e si trascrive quello che c'e'. */
  fun stopListening() = stt.stopNow()

  /**
   * Il tocco sulla barra (o l'overlay che si chiude): si smette di ascoltare senza dire niente, e
   * torna il campo di testo. Solo l'ascolto di adesso, e solo se non e' gia' diventato una domanda:
   * chiudere l'overlay dopo una domanda a voce non tocca niente. Prima restava acceso un "zitto"
   * che, al primo ascolto fermato dopo, faceva sparire lo stato "fermata".
   */
  fun cancelListening() {
    val job = voiceJob ?: return
    synchronized(lock) {
      if (voiceToken != generation || !job.isActive) return
      generation++
      stateFlow.value = AssistantState.Idle
    }
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
    const val TAG = "AssistantRuntime"
    const val HINT = "Domande a un assistente per il telefono e le app Pampa: meteo, autobus, registro scolastico, musica, sveglie, promemoria."
  }
}

/** Chi possiede lo stato vivo del runtime: la superficie della domanda e la sua conversazione. */
data class LiveOwner(val surface: Surface, val conversationId: Long?)

/**
 * Un'istantanea coerente di chi segue cosa: la conversazione della chat dell'app, quella
 * dell'overlay, e il padrone dello stato vivo. Scritta in un colpo solo ([AssistantRuntime.liveTrack]).
 */
data class LiveTrack(val app: Long?, val session: Long?, val owner: LiveOwner)
