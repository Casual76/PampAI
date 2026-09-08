package dev.pampa.pampai.feature.assistant.session

import android.graphics.Rect
import dev.antigravity.fluidengine.ai.orchestrator.AskMode
import dev.antigravity.fluidengine.ai.orchestrator.AssistantState
import dev.pampa.pampai.core.assistant.attachments.PendingAttachment
import dev.pampa.pampai.core.assistant.db.AttachmentKind
import dev.pampa.pampai.core.assistant.db.ConversationsRepository
import dev.pampa.pampai.core.assistant.db.Message
import dev.pampa.pampai.core.assistant.db.Run
import dev.pampa.pampai.core.assistant.runtime.AssistantRequest
import dev.pampa.pampai.core.assistant.runtime.AssistantRuntime
import dev.pampa.pampai.core.assistant.runtime.VoiceEvent
import dev.pampa.pampai.core.assistant.screen.ScreenContextStore
import dev.pampa.pampai.core.assistant.tools.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
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

  val state: StateFlow<AssistantState> = runtime.state
  val micLevel = runtime.micLevel
  val partial = runtime.partialTranscript
  val pendingConfirmation = runtime.pendingConfirmation
  val speaking = runtime.speaking
  val screenState = screen.state

  val conversationId: StateFlow<Long?> = runtime.activeConversationId

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

  /** Quante volte si e' mostrata: la UI la usa per far ripartire l'entrata (orb → barra). */
  val shownStamp = MutableStateFlow(0L)

  private var hiddenAt = 0L
  private var eventsJob: Job? = null

  fun onShow(startVoice: Boolean, startInText: Boolean) {
    val now = System.currentTimeMillis()
    val continues = hiddenAt > 0L && now - hiddenAt < CONTINUE_WINDOW_MILLIS && conversationId.value != null
    if (!continues) runtime.selectConversation(null)
    attachmentsFlow.value = emptyList()
    notice.value = null
    selecting.value = false
    textMode.value = startInText || !startVoice
    shownStamp.value = now
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
    if (!runtime.isBusy) runtime.reset()
    if (startVoice && !startInText) startVoice()
  }

  fun onHide() {
    hiddenAt = System.currentTimeMillis()
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
    runtime.submit(AssistantRequest(conversationId.value, question, AskMode.TEXT, attached, Surface.SESSION))
  }

  fun startVoice() {
    textMode.value = false
    notice.value = null
    val attached = attachmentsFlow.value
    attachmentsFlow.value = emptyList()
    runtime.startListening(conversationId.value, Surface.SESSION, attached)
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

  /** Il ritaglio col dito, in coordinate della finestra (che coincidono con lo screenshot). */
  fun attachCrop(rect: Rect): Boolean {
    val jpeg = screen.cropJpeg(rect) ?: run {
      notice.value = if (screen.current.screenshot == null) "Nessuno screenshot da ritagliare: attiva \"Usa screenshot\" nelle impostazioni dell'assistente." else "Ritaglio troppo piccolo."
      return false
    }
    replaceScreenAttachment(PendingAttachment(AttachmentKind.IMAGE, "image/jpeg", "schermo-ritaglio.jpg", jpeg))
    textMode.value = true
    return true
  }

  private fun replaceScreenAttachment(attachment: PendingAttachment) {
    attachmentsFlow.value = attachmentsFlow.value.filterNot { it.name.startsWith("schermo") } + attachment
    notice.value = null
  }

  private companion object {
    const val CONTINUE_WINDOW_MILLIS = 120_000L
  }
}
