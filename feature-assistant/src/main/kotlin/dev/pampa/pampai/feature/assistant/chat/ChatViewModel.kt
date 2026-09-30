package dev.pampa.pampai.feature.assistant.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.antigravity.fluidengine.ai.keys.AiKeyStore
import dev.antigravity.fluidengine.ai.keys.AiSettings
import dev.antigravity.fluidengine.ai.keys.AiSettingsStore
import dev.antigravity.fluidengine.ai.keys.KeyState
import dev.antigravity.fluidengine.ai.keys.ModelCatalogStore
import dev.antigravity.fluidengine.ai.keys.ThinkingLevel
import dev.antigravity.fluidengine.ai.orchestrator.AskMode
import dev.antigravity.fluidengine.ai.orchestrator.AssistantState
import dev.antigravity.fluidengine.ai.orchestrator.PendingConfirmation
import dev.antigravity.fluidengine.ai.provider.ModelCatalogue
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.pampa.pampai.core.assistant.attachments.AttachmentReader
import dev.pampa.pampai.core.assistant.attachments.PendingAttachment
import dev.pampa.pampai.core.assistant.db.Anchor
import dev.pampa.pampai.core.assistant.db.AttachmentKind
import dev.pampa.pampai.core.assistant.db.Conversation
import dev.pampa.pampai.core.assistant.db.ConversationsRepository
import dev.pampa.pampai.core.assistant.db.DatabaseRecovery
import dev.pampa.pampai.core.assistant.db.Message
import dev.pampa.pampai.core.assistant.db.MessageRole
import dev.pampa.pampai.core.assistant.db.RecoveryNotice
import dev.pampa.pampai.core.assistant.db.Run
import dev.pampa.pampai.core.assistant.remote.RemoteStatus
import dev.pampa.pampai.core.assistant.remote.RemoteSwitches
import dev.pampa.pampai.core.assistant.runtime.AssistantEngine
import dev.pampa.pampai.core.assistant.runtime.AssistantRequest
import dev.pampa.pampai.core.assistant.runtime.AssistantRuntime
import dev.pampa.pampai.core.assistant.runtime.ContextEstimate
import dev.pampa.pampai.core.assistant.runtime.LiveTrack
import dev.pampa.pampai.core.assistant.runtime.ProviderOverride
import dev.pampa.pampai.core.assistant.runtime.VoiceEvent
import dev.pampa.pampai.core.assistant.settings.PampaiSettingsStore
import dev.pampa.pampai.core.assistant.tools.RegistryHolder
import dev.pampa.pampai.core.assistant.tools.Surface
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Un plugin scegliibile nel composer: una categoria del catalogo. */
data class PluginOption(val id: String, val label: String, val hint: String)

/**
 * Una domanda scritta mentre Aria era ancora al lavoro: parte da sola quando il lavoro finisce.
 * [editOf] e' la domanda che corregge, se era un "modifica e rinvia".
 */
data class QueuedQuestion(
  val text: String,
  val attachments: List<PendingAttachment> = emptyList(),
  val deep: Boolean = false,
  val editOf: Message? = null,
)

data class ChatUiState(
  val conversation: Conversation? = null,
  val messages: List<Message> = emptyList(),
  val runs: Map<Long, Run> = emptyMap(),
  /** Lo stato vivo del runtime, se sta lavorando proprio su questa conversazione. */
  val live: AssistantState? = null,
  val pending: PendingConfirmation? = null,
  val settings: AiSettings = AiSettings(),
  val keys: Map<ProviderId, KeyState> = emptyMap(),
  val catalogues: Map<ProviderId, ModelCatalogue> = emptyMap(),
  val context: ContextEstimate? = null,
  val attachments: List<PendingAttachment> = emptyList(),
  /**
   * La riserva automatica di PampAI (`PampaiSettings.failoverEnabled`): il menu del modello lo
   * dice, perche' con la riserva accesa "chi risponde" puo' non essere il servizio con la spunta.
   */
  val failoverEnabled: Boolean = false,
  /**
   * La chat aperta e' temporanea: la conversazione su disco lo dice, e prima che nasca lo dice la
   * scelta fatta nel menu "+". La barra in cima ci mette il distintivo.
   */
  val temporary: Boolean = false,
  /**
   * Aria sta lavorando per l'overlay di sistema (o su un'altra conversazione): la chat non lo
   * mostra, ma il composer deve saperlo, perche' una domanda mandata adesso aspetta la fine di
   * quel lavoro invece di fermarlo.
   */
  val busyElsewhere: Boolean = false,
  /** Le domande scritte mentre Aria lavorava, in ordine: partono una alla volta quando finisce. */
  val queued: List<QueuedQuestion> = emptyList(),
  /** Il database non si e' aperto ed e' ripartito da zero: la chat lo dice, finche' non si tocca "Ok". */
  val recovery: RecoveryNotice? = null,
) {
  /** Almeno una chiave verificata: senza, rispondono solo i comandi rapidi. */
  val hasKeys: Boolean get() = keys.any { it.value.verified }

  /**
   * Aria e' spenta ma le chiavi ci sono: una domanda andrebbe a un servizio senza il consenso che
   * l'interruttore rappresenta. Senza chiavi invece non parte niente verso fuori (i comandi rapidi
   * girano sul telefono, il resto finisce in "nessuna chiave"), e il campo resta usabile.
   */
  val blockedByConsent: Boolean get() = !settings.enabled && hasKeys

  /** Aria sta lavorando, qui o altrove: un invio adesso va in coda. */
  val anyBusy: Boolean get() = live?.isBusy == true || busyElsewhere

  val isNew: Boolean get() = conversation == null
}

/** Le impostazioni che la chat legge insieme: engine, chiavi, cataloghi, la riserva di PampAI e l'avviso del database. */
private data class ChatSettings(
  val settings: AiSettings,
  val keys: Map<ProviderId, KeyState>,
  val catalogues: Map<ProviderId, ModelCatalogue>,
  val failoverEnabled: Boolean,
  val recovery: RecoveryNotice?,
)

/** Cio' che il composer tiene per se': allegati in attesa, anello del contesto, chat temporanea, coda. */
private data class ComposerInputs(
  val attachments: List<PendingAttachment>,
  val context: ContextEstimate?,
  val temporary: Boolean,
  val busy: Boolean,
  val queued: List<QueuedQuestion>,
)

/**
 * La chat con Aria: la conversazione attiva (quella del runtime), i suoi messaggi e la telemetria,
 * lo stato vivo mentre risponde, gli allegati in attesa, l'anello del contesto. Con la
 * conversazione attiva nulla e' una chat nuova: nasce alla prima domanda, e da quel momento la
 * schermata la segue.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
  @ApplicationContext private val context: Context,
  private val conversations: ConversationsRepository,
  private val runtime: AssistantRuntime,
  private val engine: AssistantEngine,
  private val reader: AttachmentReader,
  private val settingsStore: AiSettingsStore,
  private val keyStore: AiKeyStore,
  private val catalogs: ModelCatalogStore,
  private val pampaiSettings: PampaiSettingsStore,
  private val recovery: DatabaseRecovery,
  registryHolder: RegistryHolder,
  remote: RemoteSwitches,
) : ViewModel() {

  /** Il file di controllo remoto: kill switch e aggiornamenti, per il banner in cima alla chat. */
  val remoteStatus: StateFlow<RemoteStatus> = remote.status.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RemoteStatus())

  /** Il plugin scelto nel composer per la prossima conversazione (o quella aperta). */
  val plugin = MutableStateFlow<String?>(null)

  /** "Pensa piu' a fondo" armato per la prossima domanda. */
  val deepNext = MutableStateFlow(false)

  /**
   * La prossima conversazione nasce temporanea.
   *
   * Vive qui e non su disco perche' una chat temporanea, finche' non le si scrive dentro, non
   * esiste: la riga in Room la crea la prima domanda, ed e' la' che il flag si posa. Da quel
   * momento comanda la conversazione, e questo flusso la segue soltanto.
   */
  private val temporaryNext = MutableStateFlow(false)

  /** I plugin fra cui scegliere: le categorie del catalogo, app collegate e aree del telefono. */
  val plugins: StateFlow<List<PluginOption>> = registryHolder.catalog
    .map { catalog -> catalog.categories.map { PluginOption(it.id, it.label, it.hint) } }
    .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

  val thinkingAuto: StateFlow<Boolean> = pampaiSettings.settings
    .map { it.thinkingAuto }
    .stateIn(viewModelScope, SharingStarted.Eagerly, true)

  fun setPlugin(id: String?) {
    plugin.value = id
    val conversationId = runtime.activeConversationId.value ?: return
    viewModelScope.launch { conversations.setPlugin(conversationId, id) }
  }

  fun toggleDeepNext() {
    deepNext.value = !deepNext.value
  }

  fun setThinkingAuto(auto: Boolean) {
    viewModelScope.launch { pampaiSettings.setThinkingAuto(auto) }
  }

  private val pendingAttachments = MutableStateFlow<List<PendingAttachment>>(emptyList())
  private val contextEstimate = MutableStateFlow<ContextEstimate?>(null)

  /** La coda delle domande scritte mentre Aria lavorava. Vedi [send]. */
  private val queue = MutableStateFlow<List<QueuedQuestion>>(emptyList())

  /**
   * Chi risponde e quanto ci pensa, dal composer.
   *
   * Scrivono le impostazioni vere, non un'eccezione per questa conversazione: chi cambia modello
   * a meta' chat lo fa perche' ha cambiato idea su quale vuole, non per un messaggio solo.
   */
  fun useProvider(provider: ProviderId) {
    viewModelScope.launch {
      val order = settingsStore.current().chatOrder
      settingsStore.setChatOrder(listOf(provider) + order.filter { it != provider })
    }
  }

  fun setThinking(level: ThinkingLevel) {
    viewModelScope.launch { settingsStore.setThinking(level) }
  }

  private val conversationFlow = runtime.activeConversationId.flatMapLatest { id ->
    if (id == null) {
      flowOf(Triple<Conversation?, List<Message>, List<Run>>(null, emptyList(), emptyList()))
    } else {
      combine(conversations.observeConversation(id), conversations.observeMessages(id), conversations.observeRuns(id)) { c, m, r -> Triple(c, m, r) }
    }
  }

  // La riserva passa da qui e non da un flow suo: e' un dettaglio dello stesso menu che mostra
  // chiavi e cataloghi, e `distinctUntilChanged` evita di ricomporre la chat per ogni altra
  // impostazione di PampAI che cambia.
  private val settingsFlow = combine(
    settingsStore.settings,
    keyStore.states,
    catalogs.catalogues,
    pampaiSettings.settings.map { it.failoverEnabled }.distinctUntilChanged(),
    recovery.notice,
  ) { s, k, c, f, r -> ChatSettings(s, k, c, f, r) }

  /**
   * Lo stato del runtime che questa chat puo' mostrare, con un freno mentre scrive.
   *
   * Il padrone si legge da `liveTrack`, un valore solo scritto in un colpo: tre flussi separati si
   * potevano vedere a meta' di un cambio. La chat mostra il lavoro vivo solo se e' sulla
   * conversazione aperta: una domanda dell'app finita in un'altra conversazione ("Nuova chat" a
   * meta' risposta) non lascia una bolla nella chat nuova. Il lavoro dell'overlay si vede solo se
   * la sua conversazione e' questa (il gesto "espandi"), e mai il suo ascolto: quello e' della
   * barra dell'overlay.
   *
   * Il freno: ogni token che arriva e' un nuovo `Answering`, e ogni `Answering` ricompone la
   * risposta e la ri-analizza come Markdown. Su un testo lungo sono centinaia di analisi al
   * secondo, e si vede: lo scorrimento scatta e la tastiera arranca. Uno `StateFlow` tiene da se'
   * solo l'ultimo valore mentre chi lo legge e' fermo, e si sta fermi 66 ms soltanto dopo un
   * `Answering`: gli altri stati passano subito.
   */
  private val throttledState = combine(runtime.state, runtime.liveTrack) { s, track -> if (owns(track, s)) s else AssistantState.Idle }
    .transform { s ->
      emit(s)
      if (s is AssistantState.Answering) delay(66)
    }

  val state: StateFlow<ChatUiState> = combine(
    conversationFlow,
    throttledState,
    runtime.pendingConfirmation,
    settingsFlow,
    combine(pendingAttachments, contextEstimate, temporaryNext, runtime.state.map { it.isBusy }.distinctUntilChanged(), queue) { a, c, t, b, q -> ComposerInputs(a, c, t, b, q) },
  ) { (conversation, messages, runs), live, pending, chat, inputs ->
    ChatUiState(
      conversation = conversation,
      messages = messages,
      runs = runs.associateBy { it.messageId },
      live = live.takeIf { it != AssistantState.Idle },
      pending = pending,
      settings = chat.settings,
      keys = chat.keys,
      catalogues = chat.catalogues,
      context = inputs.context,
      attachments = inputs.attachments,
      failoverEnabled = chat.failoverEnabled,
      // Prima della prima domanda la conversazione non c'e' ancora: vale la scelta del menu "+".
      temporary = conversation?.temporary ?: inputs.temporary,
      busyElsewhere = inputs.busy && live == AssistantState.Idle,
      queued = inputs.queued,
      recovery = chat.recovery,
    )
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

  val micLevel = runtime.micLevel
  val lastMode = runtime.lastMode
  val sttState = runtime.sttState

  /** Le parole mentre si parla: le raccoglie solo la riga della voce del composer, non la pagina. */
  val runtimePartial = runtime.partialTranscript

  /**
   * Gli esiti degli ascolti partiti dall'app. Quelli dell'overlay (il tasto di accensione sopra
   * un'altra app) non sono di questa chat: prima un silenzio nella sessione metteva il cursore nel
   * campo dell'app rimasta dietro.
   */
  val voiceEvents: Flow<VoiceEvent> = runtime.voiceEvents.filter { runtime.liveTrack.value.owner.surface == Surface.APP }
  val speaking = runtime.speaking

  init {
    viewModelScope.launch {
      runtime.activeConversationId.collect { id -> contextEstimate.value = runCatching { engine.estimateContext(id) }.getOrNull() }
    }
    viewModelScope.launch {
      runtime.state.collect { if (it is AssistantState.Done) contextEstimate.value = runCatching { engine.estimateContext(runtime.activeConversationId.value) }.getOrNull() }
    }
    // La coda: quando Aria smette di lavorare parte la prossima domanda. Solo cosi', e non subito
    // dopo il `Done` di chi e' in coda: lo stato del runtime e' uno solo, e una domanda mandata
    // mentre ne corre un'altra la fermerebbe (era il vecchio "invio ignorato", o peggio la risposta
    // dell'overlay interrotta da un tocco nell'app).
    viewModelScope.launch {
      runtime.state.collect { flushQueue(it) }
    }
    // La conversazione attiva puo' cambiare senza passare da qui: il cassetto, un chip
    // [[conversazione:ID]], una notifica. Se quella nuova esiste e non e' temporanea, la chat
    // temporanea di prima e' stata lasciata: si spegne il flag e sparisce. Il controllo su
    // `temporary` e' anche cio' che rende sicura la cancellazione: non tocca mai la temporanea
    // che si sta aprendo, perche' in quel caso non si cancella niente.
    viewModelScope.launch {
      runtime.activeConversationId.collect { id ->
        val opened = id?.let { runCatching { conversations.conversation(it) }.getOrNull() } ?: return@collect
        if (!opened.temporary) {
          temporaryNext.value = false
          engine.dropTemporary()
        }
      }
    }
  }

  val isBusy: Boolean get() = runtime.isBusy

  /**
   * Manda una domanda, o la mette in coda se Aria sta ancora lavorando (qui, nell'overlay, o su
   * un'altra conversazione): parte da sola quando il lavoro finisce, e intanto la chat la mostra
   * "in coda". Fermare Aria la riporta nel campo, perche' chi ferma di solito vuole cambiare
   * qualcosa.
   *
   * @return false se non e' partita ne' e' in coda (vuota, o Aria spenta con le chiavi): il
   *   composer allora tiene il testo.
   */
  fun send(text: String): Boolean {
    val attachments = pendingAttachments.value
    if (text.isBlank() && attachments.isEmpty()) return false
    if (state.value.blockedByConsent) {
      notices.tryEmit("Aria e' spenta: accendila nelle impostazioni.")
      return false
    }
    pendingAttachments.value = emptyList()
    val deep = deepNext.value
    deepNext.value = false
    val question = QueuedQuestion(text, attachments, deep)
    if (runtime.isBusy) queue.value = queue.value + question else submit(question)
    return true
  }

  private fun submit(question: QueuedQuestion) {
    val edited = question.editOf
    if (edited != null) {
      runtime.submit(AssistantRequest(edited.conversationId, question.text, AskMode.TEXT, question.attachments, Surface.APP, anchor = Anchor.Edit(edited.id)))
    } else {
      runtime.submit(
        AssistantRequest(
          runtime.activeConversationId.value, question.text, AskMode.TEXT, question.attachments, Surface.APP,
          plugin = plugin.value, deep = question.deep, temporary = temporaryNext.value,
        ),
      )
    }
  }

  /**
   * La coda davanti a uno stato nuovo del runtime. Parte la prima domanda solo con Aria ferma e
   * l'app davanti: un service in primo piano non si avvia dal secondo piano (da Android 12), e la
   * domanda aspetta che si torni nella chat ([onChatVisible]). Una risposta fermata a mano rimette
   * la coda nel campo.
   */
  private fun flushQueue(current: AssistantState) {
    if (current.isBusy || queue.value.isEmpty()) return
    if (current is AssistantState.Cancelled) {
      releaseQueue()
      return
    }
    if (!runtime.appInForeground) return
    val next = queue.value.first()
    queue.value = queue.value.drop(1)
    submit(next)
  }

  /** La chat e' tornata davanti: se c'era una domanda in coda e Aria e' ferma, parte adesso. */
  fun onChatVisible() {
    if (!runtime.isBusy) flushQueue(runtime.state.value)
  }

  /** Toglie una domanda dalla coda e la rimette nel campo, per correggerla o per non mandarla. */
  fun unqueue(question: QueuedQuestion) {
    if (question !in queue.value) return
    queue.value = queue.value - question
    backToField(listOf(question))
  }

  /** Tutta la coda torna nel campo: una risposta fermata, un'altra conversazione aperta. */
  private fun releaseQueue() {
    val released = queue.value
    if (released.isEmpty()) return
    queue.value = emptyList()
    backToField(released)
  }

  private fun backToField(questions: List<QueuedQuestion>) {
    draft.value = (listOfNotNull(draft.value) + questions.map { it.text }).filter { it.isNotBlank() }.joinToString("\n\n").ifBlank { null }
    pendingAttachments.value = (questions.flatMap { it.attachments } + pendingAttachments.value).take(MAX_ATTACHMENTS)
    if (questions.any { it.deep }) deepNext.value = true
  }

  fun startVoice() {
    val attachments = pendingAttachments.value
    pendingAttachments.value = emptyList()
    runtime.startListening(runtime.activeConversationId.value, Surface.APP, attachments, temporary = temporaryNext.value)
  }

  fun stopVoice() = runtime.stopListening()

  fun cancelVoice() = runtime.cancelListening()

  fun stopSpeaking() = runtime.stopSpeaking()

  fun cancel() = runtime.cancel()

  fun dismiss() = runtime.reset()

  fun resolve(id: Long, confirmed: Boolean) = runtime.resolveConfirmation(id, confirmed)

  /** L'avviso del database ripartito da zero e' stato letto. I file messi da parte restano. */
  fun dismissRecovery() = recovery.dismiss()

  /**
   * Una chat nuova. Il lavoro in corso **non** si ferma: la risposta finisce nella sua
   * conversazione (lo stato vivo resta suo, vedi [throttledState]) e la si ritrova nel cassetto.
   * Prima "Nuova chat" la interrompeva, anche quando era dell'overlay.
   */
  fun newConversation() {
    releaseQueue()
    plugin.value = null
    deepNext.value = false
    leaveCurrent()
    temporaryNext.value = false
    dropTemporary()
    runtime.selectConversation(null)
    runtime.reset()
  }

  /**
   * Una chat che non resta: fuori dalla cronologia, senza titolo dal modello, senza memoria, e
   * cancellata appena la si lascia. Come una chat nuova in tutto il resto — nasce alla prima
   * domanda, e finche' non arriva non c'e' niente su disco.
   */
  fun newTemporaryConversation() {
    newConversation()
    temporaryNext.value = true
  }

  fun open(conversationId: Long) {
    viewModelScope.launch { plugin.value = conversations.conversation(conversationId)?.plugin }
    // Aprire un'altra conversazione e' lasciare quella di adesso: se era temporanea, sparisce. Il
    // lavoro in corso invece continua, come per "Nuova chat": aprire nell'app quella dell'overlay
    // (il gesto "espandi", una notifica) la lascia finire, e aprirne un'altra non la interrompe.
    if (conversationId != runtime.activeConversationId.value) {
      releaseQueue()
      leaveCurrent()
      temporaryNext.value = false
      dropTemporary()
    }
    runtime.selectConversation(conversationId)
    runtime.reset()
  }

  /**
   * Cosa si ferma lasciando la chat aperta: solo cio' che non avrebbe senso altrove. L'ascolto
   * dell'app (la domanda che ne uscirebbe finirebbe nella chat lasciata), e una risposta in una
   * chat temporanea, che sta per sparire: cancellarle il pavimento sotto i piedi mentre scrive
   * lascerebbe messaggi orfani.
   */
  private fun leaveCurrent() {
    val track = runtime.liveTrack.value
    val owner = track.owner
    if (owner.surface != Surface.APP || owner.conversationId != track.app) return
    val live = runtime.state.value
    when {
      live is AssistantState.Listening || live == AssistantState.Transcribing -> runtime.cancelListening()
      live.isBusy && state.value.temporary -> runtime.cancel()
    }
  }

  /**
   * La cancellazione delle temporanee: da disco e dalla memoria dell'engine.
   *
   * Sta nelle azioni che lasciano la chat (chat nuova, apertura di un'altra), cosi' avviene
   * *prima* che la prossima possa nascere: nessuna cancellazione in ritardo puo' portarsi via la
   * temporanea appena creata. L'osservatore in `init` copre le uscite che non passano di qui (il
   * cassetto, un chip, una notifica) e per la stessa ragione cancella solo quando la conversazione
   * appena aperta non e' temporanea. Chi chiude l'app e' coperto da [onCleared] e, se il processo
   * muore, dalla pulizia all'avvio in `AssistantRuntime`.
   */
  private fun dropTemporary() {
    viewModelScope.launch { engine.dropTemporary() }
  }

  override fun onCleared() {
    // L'app chiusa per davvero (non un giro di schermo, non le impostazioni sopra la chat): la
    // temporanea aperta se ne va. Nello scope del runtime, che a quest'ora e' l'unico ancora vivo.
    // Non mentre sta rispondendo: cancellarle il pavimento sotto i piedi lascerebbe messaggi
    // orfani, e al prossimo avvio ci pensa il runtime.
    if (!runtime.isBusy) runtime.scope.launch { engine.dropTemporary() }
    super.onCleared()
  }

  /** La scorciatoia "Ultima": riapre la conversazione piu' recente (o ne inizia una, se non ce ne sono). */
  fun openLast() = viewModelScope.launch {
    val last = conversations.observeConversations().first().firstOrNull()
    if (last != null) open(last.id) else newConversation()
  }

  /**
   * Un testo che deve finire nel campo: "Condividi con Aria", o una domanda tolta dalla coda. Lo
   * consuma la chat, che lo aggiunge a quello che c'e' gia' scritto invece di sostituirlo.
   */
  val draft = MutableStateFlow<String?>(null)

  /**
   * Modifica e rinvia: una versione nuova della domanda, sorella di quella di prima, con un ramo
   * suo. Le risposte di prima non si cancellano: restano fra le versioni ("‹ 1/2 ›").
   *
   * Gli allegati li ricopia l'engine dalla domanda di prima, file compresi: "cosa c'e' in questa
   * foto?" corretto in "…in questa immagine?" arriva al modello con l'immagine. Con Aria al
   * lavoro va in coda come ogni altro invio.
   *
   * @return come [send]: false se non e' partita ne' e' in coda.
   */
  /**
   * Il tocco su "Aria e' spenta: accendila". Con il consenso gia' dato basta riaccenderla, qui, senza
   * mandare nessuno a cercare l'interruttore in fondo alle impostazioni; senza consenso torna falso e
   * chi chiama apre la pagina del consenso.
   */
  fun turnOnAria(): Boolean {
    if (!state.value.settings.consentAccepted) return false
    viewModelScope.launch { settingsStore.setEnabled(true) }
    return true
  }

  fun editAndResend(message: Message, newText: String): Boolean {
    if (newText.isBlank()) return false
    if (state.value.blockedByConsent) {
      notices.tryEmit("Aria e' spenta: accendila nelle impostazioni.")
      return false
    }
    val question = QueuedQuestion(newText, editOf = message)
    if (runtime.isBusy) queue.value = queue.value + question else submit(question)
    return true
  }

  /**
   * Rigenera la risposta, anche con un altro servizio o modello: una versione nuova, sorella di
   * quella di prima, alla stessa domanda (testo e allegati li rilegge l'engine dal disco).
   *
   * Un [override] che nomina solo la chat porta con se' il profondo di quel servizio dalle
   * impostazioni: altrimenti la domanda difficile rigenerata "con Gemini" andrebbe al modello
   * profondo di default, non a quello che l'utente ha scelto.
   */
  fun regenerate(message: Message, override: ProviderOverride? = null) = viewModelScope.launch {
    if (state.value.blockedByConsent) {
      notices.tryEmit("Aria e' spenta: accendila nelle impostazioni.")
      return@launch
    }
    if (runtime.isBusy) runtime.cancel()
    // La domanda e' il padre della risposta: niente ricerca per posizione nella lista, che dopo un
    // cambio di conversazione a meta' tocco poteva non contenerla piu'.
    val question = if (message.role == MessageRole.USER) message else message.parentId?.let { conversations.message(it) }
    if (question == null) return@launch
    val completed = override?.let { if (it.deepModel == null) it.copy(deepModel = settingsStore.current().deepModel(it.provider)) else it }
    runtime.submit(AssistantRequest(message.conversationId, question.text, AskMode.TEXT, emptyList(), Surface.APP, completed, anchor = Anchor.Regenerate(message.id)))
  }

  /**
   * Le frecce delle versioni: si passa al ramo di [messageId] (una sorella di un messaggio del
   * cammino). Non mentre Aria lavora su questa conversazione: la domanda in corso si attacca alla
   * foglia di adesso, e spostarla sotto i suoi piedi la farebbe finire in un altro ramo.
   */
  fun selectVersion(messageId: Long) = viewModelScope.launch {
    if (state.value.live?.isBusy == true) return@launch
    val conversationId = state.value.conversation?.id ?: runtime.activeConversationId.value ?: return@launch
    runCatching { conversations.selectVersion(conversationId, messageId) }
  }

  /**
   * Avvisi brevi per chi usa la chat (un allegato troppo grande, uno di troppo): prima questi casi
   * finivano nel nulla, e il file scelto semplicemente non compariva.
   */
  val notices = MutableSharedFlow<String>(extraBufferCapacity = 4)

  /** Un file scelto dal picker: immagini rimpicciolite, PDF e testi come documenti. */
  fun attach(uri: Uri) = viewModelScope.launch {
    if (pendingAttachments.value.size >= MAX_ATTACHMENTS) {
      notices.tryEmit("Al massimo $MAX_ATTACHMENTS allegati per messaggio.")
      return@launch
    }
    val attachment = withContext(Dispatchers.IO) { load(uri) } ?: return@launch
    pendingAttachments.value = (pendingAttachments.value + attachment).take(MAX_ATTACHMENTS)
  }

  fun removeAttachment(index: Int) {
    pendingAttachments.value = pendingAttachments.value.filterIndexed { i, _ -> i != index }
  }

  private suspend fun load(uri: Uri): PendingAttachment? {
    val resolver = context.contentResolver
    // Un provider che lancia (permesso revocato, URI scaduto) non deve far cadere la chat.
    val mime = runCatching { resolver.getType(uri) }.getOrNull() ?: "application/octet-stream"
    val name = runCatching {
      resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment ?: "allegato"
    val size = runCatching {
      resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null }
    }.getOrNull()
    // Prima di leggerlo tutto in memoria: un video da un giga non deve arrivare a readBytes().
    if (size != null && size > MAX_BYTES) {
      notices.tryEmit("\"$name\" e' troppo grande: il limite e' ${MAX_BYTES / 1024 / 1024} MB.")
      return null
    }
    val bytes = runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }
      .onFailure { Log.w(TAG, "allegato illeggibile: $uri", it) }
      .getOrNull()
    if (bytes == null) {
      notices.tryEmit("Non riesco a leggere \"$name\".")
      return null
    }
    if (bytes.size > MAX_BYTES) {
      notices.tryEmit("\"$name\" e' troppo grande: il limite e' ${MAX_BYTES / 1024 / 1024} MB.")
      return null
    }
    return if (mime.startsWith("image/")) {
      PendingAttachment(AttachmentKind.IMAGE, "image/jpeg", name, reader.shrinkImage(bytes))
    } else {
      PendingAttachment(AttachmentKind.DOCUMENT, if (mime == "application/octet-stream" && name.endsWith(".pdf", true)) "application/pdf" else mime, name, bytes)
    }
  }

  companion object {
    private const val TAG = "ChatViewModel"
    const val MAX_ATTACHMENTS = 5
    const val MAX_BYTES = 25 * 1024 * 1024

    /**
     * Lo stato vivo e' di questa chat? Il padrone deve lavorare sulla conversazione aperta. Se e'
     * l'app, basta (anche a conversazione non ancora nata: tutte e due nulle). Se e' l'overlay,
     * solo su una conversazione che esiste, e mai con l'ascolto o la trascrizione, che sono della
     * sua barra.
     */
    internal fun owns(track: LiveTrack, state: AssistantState): Boolean {
      val owner = track.owner
      if (owner.conversationId != track.app) return false
      if (owner.surface == Surface.APP) return true
      return owner.conversationId != null &&
        state !is AssistantState.Listening && state != AssistantState.Transcribing && state != AssistantState.HeardNothing
    }
  }
}
