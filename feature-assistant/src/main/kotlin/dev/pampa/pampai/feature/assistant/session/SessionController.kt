package dev.pampa.pampai.feature.assistant.session

import android.graphics.Rect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import dev.antigravity.fluidengine.ai.orchestrator.AskMode
import dev.antigravity.fluidengine.ai.orchestrator.AssistantState
import dev.pampa.pampai.core.assistant.attachments.PendingAttachment
import dev.pampa.pampai.core.assistant.db.AttachmentKind
import dev.pampa.pampai.core.assistant.db.ConversationsRepository
import dev.pampa.pampai.core.assistant.db.Message
import dev.pampa.pampai.core.assistant.db.Run
import dev.pampa.pampai.core.assistant.runtime.AssistantRequest
import dev.pampa.pampai.core.assistant.runtime.AssistantRuntime
import dev.pampa.pampai.core.assistant.runtime.LiveOwner
import dev.pampa.pampai.core.assistant.runtime.VoiceEvent
import dev.pampa.pampai.core.assistant.screen.ScreenContextStore
import dev.pampa.pampai.core.assistant.tools.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch

/**
 * Lo stato dell'overlay di sistema, senza ViewModel (nella sessione non c'e' un'Activity a
 * fornirlo): la conversazione della sessione, gli allegati in attesa (lo schermo, un ritaglio), la
 * barra in testo o in voce, la selezione in corso. Una sessione riaperta entro due minuti continua
 * la stessa conversazione; oltre, ne apre una nuova, come fa Gemini.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionController(
  private val runtime: AssistantRuntime,
  private val conversations: ConversationsRepository,
  private val screen: ScreenContextStore,
  private val scope: CoroutineScope,
) {

  val conversationId: StateFlow<Long?> = runtime.sessionConversationId

  /**
   * Lo stato vivo **della sessione**, con un freno mentre scrive.
   *
   * Il runtime e' uno per tutto il processo: passarlo com'era voleva dire che una risposta chiesta
   * nell'app compariva anche nell'overlay — e sul telefono bloccato. Qui passa solo il lavoro che
   * l'overlay possiede: una domanda partita dalla sessione sulla sua conversazione, o una dell'app
   * sulla stessa conversazione (dopo "Apri in PampAI" le due superfici ne condividono una). Tutto il
   * resto e' `Idle`.
   *
   * Il freno e' quello della chat ([AnsweringThrottleMillis] dopo ogni `Answering`): ogni token e'
   * un `Answering` nuovo, e ognuno ricomporrebbe la card e rianalizzerebbe il Markdown. Gli altri
   * stati passano subito, `Done` compreso.
   */
  val state: StateFlow<AssistantState> = combine(runtime.state, runtime.liveOwner, conversationId) { s, owner, session -> owned(s, owner, session) }
    .transform { s ->
      emit(s)
      if (s is AssistantState.Answering) delay(AnsweringThrottleMillis)
    }
    .stateIn(scope, SharingStarted.Eagerly, owned(runtime.state.value, runtime.liveOwner.value, runtime.sessionConversationId.value))

  val micLevel = runtime.micLevel
  val partial = runtime.partialTranscript
  val pendingConfirmation = runtime.pendingConfirmation
  val speaking = runtime.speaking
  val screenState = screen.state

  val messages: StateFlow<List<Message>> = conversationId
    .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else conversations.observeMessages(id) }
    .stateIn(scope, SharingStarted.Eagerly, emptyList())

  val runs: StateFlow<List<Run>> = conversationId
    .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else conversations.observeRuns(id) }
    .stateIn(scope, SharingStarted.Eagerly, emptyList())

  private val attachmentsFlow = MutableStateFlow<List<PendingAttachment>>(emptyList())
  val attachments: StateFlow<List<PendingAttachment>> = attachmentsFlow

  /** Vero quando la barra e' un campo di testo (dopo il silenzio, dopo un tocco, o per scelta). */
  val textMode = MutableStateFlow(false)

  /** Vero mentre il dito sta ritagliando lo schermo. */
  val selecting = MutableStateFlow(false)

  /** Un avviso breve sopra la barra (lo schermo che manca, il ritaglio troppo piccolo). */
  val notice = MutableStateFlow<String?>(null)

  /** Un tocco sulla nota la manda via: e' una riga di servizio, non qualcosa da leggere due volte. */
  fun dismissNotice() {
    notice.value = null
  }

  /**
   * Quante volte si e' mostrata: la UI la usa per far ripartire l'entrata (orb → barra).
   *
   * Uno stato di Compose, non un `StateFlow`: un flusso arriva alla composizione solo quando il suo
   * raccoglitore riparte (a ciclo di vita ripreso, un giro di dispatcher dopo), e in quel giro i
   * primi fotogrammi mostravano lo scambio e la capsula della volta prima. Uno stato di snapshot
   * scritto qui, con la notifica mandata subito, e' gia' in mano al Recomposer quando la finestra
   * disegna il suo primo fotogramma.
   */
  var shownStamp by mutableLongStateOf(0L)
    private set

  /**
   * Lo stamp dell'ultima apparizione **partita da nascosta**. Due usi, nell'overlay: finche' la
   * composizione non l'ha raggiunto non si disegna niente (niente fotogrammi della volta prima), e
   * lo scrim riparte da zero solo quando cambia — una seconda pressione a overlay gia' aperto non
   * deve far lampeggiare lo sfondo.
   */
  var freshStamp by mutableLongStateOf(0L)
    private set

  private var visible = false
  private var hiddenAt = 0L

  /** L'ultima apparizione e' sul telefono bloccato: le domande di adesso lo portano con se'. */
  private var locked = false
  private var eventsJob: Job? = null

  /**
   * La parte **sincrona** dell'apparizione: tutto cio' che il primo fotogramma deve gia' sapere.
   * Va chiamata prima che la composizione riprenda (vedi `PampaiSession.onShow`); cosa fare dopo —
   * la voce, il campo di testo — lo decide [begin], che puo' dover aspettare le impostazioni.
   *
   * [locked]: il telefono e' bloccato. Allora si parte sempre da una conversazione nuova: chi
   * prende in mano un telefono bloccato non deve ritrovare sullo schermo la domanda di prima.
   *
   * @return lo stamp di questa apparizione, da ripassare a [begin].
   */
  fun onShow(startVoice: Boolean, locked: Boolean = false): Long {
    val now = System.currentTimeMillis()
    this.locked = locked
    // Una risposta dell'overlay ancora in corso, chiesta a telefono sbloccato, non si mostra su
    // quello bloccato: si ferma.
    if (locked && runtime.isBusy && runtime.liveOwner.value.surface == Surface.SESSION) runtime.cancel()
    // Riaperta mentre e' ancora a schermo (una seconda pressione) continua sempre.
    val recent = visible || (hiddenAt > 0L && now - hiddenAt < CONTINUE_WINDOW_MILLIS)
    val continues = !locked && recent && conversationId.value != null
    if (!continues) runtime.selectSessionConversation(null)
    attachmentsFlow.value = emptyList()
    notice.value = null
    selecting.value = false
    // Provvisorio: [begin] lo corregge con l'impostazione "parti in testo". Durante l'orb la barra
    // non mostra ne' campo ne' voce, quindi il cambio non si vede.
    textMode.value = !startVoice
    if (!runtime.isBusy) runtime.reset()
    eventsJob?.cancel()
    eventsJob = scope.launch {
      runtime.voiceEvents.collect { event ->
        when (event) {
          VoiceEvent.InitialSilence -> textMode.value = true
          VoiceEvent.HeardNothing -> {
            notice.value = "Non ho sentito niente."
            textMode.value = true
          }
        }
      }
    }
    val stamp = shownStamp + 1
    if (!visible) freshStamp = stamp
    shownStamp = stamp
    visible = true
    // Le scritture di sopra arrivano alla composizione (e al cancello di disegno dell'overlay)
    // adesso, non al prossimo giro del dispatcher: e' quel giro che lasciava passare i fotogrammi
    // vecchi.
    Snapshot.sendApplyNotifications()
    return stamp
  }

  /**
   * La seconda meta' dell'apparizione, quando si sa se partire in testo. Ignorata se nel frattempo
   * la sessione si e' chiusa o riaperta: il microfono non deve accendersi per un'apparizione che
   * non c'e' piu'.
   */
  fun begin(stamp: Long, startVoice: Boolean, startInText: Boolean) {
    if (!visible || stamp != shownStamp) return
    textMode.value = startInText || !startVoice
    if (startVoice && !startInText) startVoice()
  }

  fun onHide() {
    hiddenAt = System.currentTimeMillis()
    visible = false
    eventsJob?.cancel()
    eventsJob = null
    runtime.cancelListening()
    selecting.value = false
  }

  /** Il tasto indietro: chiude la selezione, poi la tastiera, poi la sessione (torna false). */
  fun onBack(): Boolean {
    if (selecting.value) {
      selecting.value = false
      return true
    }
    return false
  }

  fun ask(text: String) {
    val question = text.trim()
    if (question.isEmpty() && attachmentsFlow.value.isEmpty()) return
    val attached = attachmentsFlow.value
    attachmentsFlow.value = emptyList()
    notice.value = null
    runtime.submit(AssistantRequest(conversationId.value, question, AskMode.TEXT, attached, Surface.SESSION, locked = locked))
  }

  fun startVoice() {
    textMode.value = false
    notice.value = null
    val attached = attachmentsFlow.value
    attachmentsFlow.value = emptyList()
    runtime.startListening(conversationId.value, Surface.SESSION, attached, locked = locked)
  }

  fun stopVoice() = runtime.stopListening()

  /** Il tocco sulla barra mentre ascolta: si torna al testo senza dire niente. */
  fun voiceToText() {
    runtime.cancelListening()
    textMode.value = true
  }

  fun cancel() = runtime.cancel()

  fun stopSpeaking() = runtime.stopSpeaking()

  fun resolve(id: Long, confirmed: Boolean) = runtime.resolveConfirmation(id, confirmed)

  fun removeAttachment(index: Int) {
    attachmentsFlow.value = attachmentsFlow.value.filterIndexed { i, _ -> i != index }
  }

  /** Il tasto "schermo": lo screenshot intero diventa un allegato del prossimo messaggio. */
  fun attachScreen(): Boolean {
    val jpeg = screen.screenshotJpeg() ?: run {
      notice.value = if (screen.current.lockscreen) "Dal blocco schermo non si legge lo schermo." else "Nessuno screenshot: attiva \"Usa screenshot\" nelle impostazioni dell'assistente."
      return false
    }
    replaceScreenAttachment(PendingAttachment(AttachmentKind.IMAGE, "image/jpeg", "schermo.jpg", jpeg))
    return true
  }

  /**
   * Il ritaglio col dito, in coordinate della finestra (che coincidono con lo screenshot).
   *
   * Non tocca [textMode]: la barra e' gia' un campo quando non ascolta, e forzare il testo qui
   * apriva la tastiera sopra lo schermo appena ritagliato. Chi vuole scrivere tocca il campo.
   */
  fun attachCrop(rect: Rect): Boolean {
    val jpeg = screen.cropJpeg(rect) ?: run {
      notice.value = if (screen.current.screenshot == null) "Nessuno screenshot da ritagliare: attiva \"Usa screenshot\" nelle impostazioni dell'assistente." else "Ritaglio troppo piccolo."
      return false
    }
    replaceScreenAttachment(PendingAttachment(AttachmentKind.IMAGE, "image/jpeg", "schermo-ritaglio.jpg", jpeg))
    return true
  }

  private fun replaceScreenAttachment(attachment: PendingAttachment) {
    attachmentsFlow.value = attachmentsFlow.value.filterNot { it.name.startsWith("schermo") } + attachment
    notice.value = null
  }

  private companion object {
    const val CONTINUE_WINDOW_MILLIS = 120_000L

    /** Lo stesso freno della chat (`ChatViewModel`): circa quindici aggiornamenti al secondo. */
    const val AnsweringThrottleMillis = 66L

    /**
     * Di chi e' lo stato vivo, dal punto di vista dell'overlay. Una domanda della sessione e' sua se
     * lavora sulla sua conversazione, o su nessuna ancora (la conversazione nuova non e' nata); una
     * dell'app solo se la conversazione e' la stessa, e nota.
     *
     * Il "nessuna ancora" vale anche contro una conversazione della sessione gia' nota: quando la
     * conversazione nasce il runtime scrive prima quella della sessione e poi il padrone, e in mezzo
     * — un attimo, ma un raccoglitore ci puo' cadere — la coppia e' (nuova, padrone senza id). Uno
     * `Idle` li' toglieva la card e la rifaceva entrare.
     */
    fun owned(state: AssistantState, owner: LiveOwner, session: Long?): AssistantState {
      val mine = when (owner.surface) {
        Surface.SESSION -> owner.conversationId == null || owner.conversationId == session
        else -> owner.conversationId != null && owner.conversationId == session
      }
      return if (mine) state else AssistantState.Idle
    }
  }
}
