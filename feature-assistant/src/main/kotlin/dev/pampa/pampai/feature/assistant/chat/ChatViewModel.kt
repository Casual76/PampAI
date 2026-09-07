package dev.pampa.pampai.feature.assistant.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.antigravity.fluidengine.ai.keys.AiSettings
import dev.antigravity.fluidengine.ai.keys.AiSettingsStore
import dev.antigravity.fluidengine.ai.keys.KeyState
import dev.antigravity.fluidengine.ai.keys.AiKeyStore
import dev.antigravity.fluidengine.ai.keys.ModelCatalogStore
import dev.antigravity.fluidengine.ai.orchestrator.AskMode
import dev.antigravity.fluidengine.ai.orchestrator.AssistantState
import dev.antigravity.fluidengine.ai.orchestrator.PendingConfirmation
import dev.antigravity.fluidengine.ai.provider.ModelCatalogue
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.pampa.pampai.core.assistant.attachments.AttachmentReader
import dev.pampa.pampai.core.assistant.attachments.PendingAttachment
import dev.pampa.pampai.core.assistant.db.AttachmentKind
import dev.pampa.pampai.core.assistant.db.Conversation
import dev.pampa.pampai.core.assistant.db.ConversationsRepository
import dev.pampa.pampai.core.assistant.db.Message
import dev.pampa.pampai.core.assistant.db.MessageRole
import dev.pampa.pampai.core.assistant.db.Run
import dev.pampa.pampai.core.assistant.runtime.AssistantEngine
import dev.pampa.pampai.core.assistant.runtime.AssistantRequest
import dev.pampa.pampai.core.assistant.runtime.AssistantRuntime
import dev.pampa.pampai.core.assistant.runtime.ContextEstimate
import dev.pampa.pampai.core.assistant.runtime.ProviderOverride
import dev.pampa.pampai.core.assistant.tools.Surface
import dev.pampa.pampai.core.assistant.settings.PampaiSettingsStore
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
) {
  val enabled: Boolean get() = settings.enabled && keys.any { it.value.verified }
  val isNew: Boolean get() = conversation == null
}

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
) : ViewModel() {

  private val pendingAttachments = MutableStateFlow<List<PendingAttachment>>(emptyList())
  private val contextEstimate = MutableStateFlow<ContextEstimate?>(null)

  /**
   * Gli esempi della prima chat: una volta e basta.
   *
   * Sta fuori da [state] apposta. Quel `combine` e' gia' al limite dei suoi argomenti, e questo e'
   * un valore che cambia una volta sola nella vita dell'app: non ha niente da fare in un flusso
   * ricalcolato a ogni token della risposta.
   */
  val suggestionsSeen: StateFlow<Boolean> = pampaiSettings.settings
    .map { it.suggestionsSeen }
    .stateIn(viewModelScope, SharingStarted.Eagerly, true)

  /** Chiamato dalla schermata quando gli esempi sono stati mostrati per la prima volta. */
  fun markSuggestionsSeen() {
    viewModelScope.launch { pampaiSettings.setSuggestionsSeen() }
  }

  private val conversationFlow = runtime.activeConversationId.flatMapLatest { id ->
    if (id == null) {
      flowOf(Triple<Conversation?, List<Message>, List<Run>>(null, emptyList(), emptyList()))
    } else {
      combine(conversations.observeConversation(id), conversations.observeMessages(id), conversations.observeRuns(id)) { c, m, r -> Triple(c, m, r) }
    }
  }

  private val settingsFlow = combine(settingsStore.settings, keyStore.states, catalogs.catalogues) { s, k, c -> Triple(s, k, c) }

  val state: StateFlow<ChatUiState> = combine(conversationFlow, runtime.state, runtime.pendingConfirmation, settingsFlow, combine(pendingAttachments, contextEstimate) { a, c -> a to c }) { (conversation, messages, runs), live, pending, (settings, keys, catalogues), (attachments, estimate) ->
    ChatUiState(
      conversation = conversation,
      messages = messages,
      runs = runs.associateBy { it.messageId },
      live = live.takeIf { it != AssistantState.Idle },
      pending = pending,
      settings = settings,
      keys = keys,
      catalogues = catalogues,
      context = estimate,
      attachments = attachments,
    )
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

  val micLevel = runtime.micLevel
  val lastMode = runtime.lastMode
  val sttState = runtime.sttState
  val runtimePartial = runtime.partialTranscript
  val voiceEvents = runtime.voiceEvents
  val speaking = runtime.speaking

  init {
    viewModelScope.launch {
      runtime.activeConversationId.collect { id -> contextEstimate.value = runCatching { engine.estimateContext(id) }.getOrNull() }
    }
    viewModelScope.launch {
      runtime.state.collect { if (it is AssistantState.Done) contextEstimate.value = runCatching { engine.estimateContext(runtime.activeConversationId.value) }.getOrNull() }
    }
  }

  val isBusy: Boolean get() = runtime.isBusy

  fun send(text: String) {
    val attachments = pendingAttachments.value
    pendingAttachments.value = emptyList()
    runtime.submit(AssistantRequest(runtime.activeConversationId.value, text, AskMode.TEXT, attachments, Surface.APP))
  }

  fun startVoice() {
    val attachments = pendingAttachments.value
    pendingAttachments.value = emptyList()
    runtime.startListening(runtime.activeConversationId.value, Surface.APP, attachments)
  }

  fun stopVoice() = runtime.stopListening()

  fun cancelVoice() = runtime.cancelListening()

  fun stopSpeaking() = runtime.stopSpeaking()

  fun cancel() = runtime.cancel()

  fun dismiss() = runtime.reset()

  fun resolve(id: Long, confirmed: Boolean) = runtime.resolveConfirmation(id, confirmed)

  fun newConversation() {
    if (runtime.isBusy) runtime.cancel()
    runtime.selectConversation(null)
    runtime.reset()
  }

  fun open(conversationId: Long) {
    if (runtime.isBusy && runtime.activeConversationId.value != conversationId) runtime.cancel()
    runtime.selectConversation(conversationId)
    runtime.reset()
  }

  /** La scorciatoia "Ultima": riapre la conversazione piu' recente (o ne inizia una, se non ce ne sono). */
  fun openLast() = viewModelScope.launch {
    val last = conversations.observeConversations().first().firstOrNull()
    if (last != null) open(last.id) else newConversation()
  }

  /** Un testo arrivato da fuori ("Condividi con Aria"): finisce nel campo, e l'utente lo manda quando vuole. */
  val draft = MutableStateFlow<String?>(null)

  /** Modifica e rinvia: cade tutto cio' che segue, e la domanda riparte con il nuovo testo. */
  fun editAndResend(message: Message, newText: String) = viewModelScope.launch {
    if (runtime.isBusy) runtime.cancel()
    conversations.updateUserText(message.id, newText)
    conversations.truncateAfter(message.conversationId, message.id)
    engine.forget(message.conversationId)
    runtime.submit(AssistantRequest(message.conversationId, newText, AskMode.TEXT, emptyList(), Surface.APP, regenerateMessageId = null).let { request ->
      // Il messaggio dell'utente c'e' gia' (modificato): la domanda riparte senza riscriverlo.
      request.copy(regenerateMessageId = REGENERATE_AFTER_EDIT)
    })
  }

  /** Rigenera la risposta, anche con un altro servizio o modello. */
  fun regenerate(message: Message, override: ProviderOverride? = null) = viewModelScope.launch {
    if (runtime.isBusy) runtime.cancel()
    val messages = state.value.messages
    val index = messages.indexOfFirst { it.id == message.id }
    val question = messages.take(index).lastOrNull { it.role == MessageRole.USER } ?: return@launch
    conversations.truncateAfter(message.conversationId, message.id)
    engine.forget(message.conversationId)
    val attachments = withContext(Dispatchers.IO) {
      question.attachments.mapNotNull { a -> runCatching { PendingAttachment(a.kind, a.mime, a.name, java.io.File(a.path).readBytes()) }.getOrNull() }
    }
    runtime.submit(AssistantRequest(message.conversationId, question.text, AskMode.TEXT, attachments, Surface.APP, override, regenerateMessageId = message.id))
  }

  /** Un file scelto dal picker: immagini rimpicciolite, PDF e testi come documenti. */
  fun attach(uri: Uri) = viewModelScope.launch {
    val attachment = withContext(Dispatchers.IO) { load(uri) } ?: return@launch
    pendingAttachments.value = (pendingAttachments.value + attachment).take(MAX_ATTACHMENTS)
  }

  fun attachImage(bytes: ByteArray, name: String) = viewModelScope.launch {
    val shrunk = reader.shrinkImage(bytes)
    pendingAttachments.value = (pendingAttachments.value + PendingAttachment(AttachmentKind.IMAGE, "image/jpeg", name, shrunk)).take(MAX_ATTACHMENTS)
  }

  fun removeAttachment(index: Int) {
    pendingAttachments.value = pendingAttachments.value.filterIndexed { i, _ -> i != index }
  }

  private suspend fun load(uri: Uri): PendingAttachment? {
    val resolver = context.contentResolver
    val mime = resolver.getType(uri) ?: "application/octet-stream"
    val name = runCatching {
      resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment ?: "allegato"
    val bytes = runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: return null
    if (bytes.size > MAX_BYTES) return null
    return if (mime.startsWith("image/")) {
      PendingAttachment(AttachmentKind.IMAGE, "image/jpeg", name, reader.shrinkImage(bytes))
    } else {
      PendingAttachment(AttachmentKind.DOCUMENT, if (mime == "application/octet-stream" && name.endsWith(".pdf", true)) "application/pdf" else mime, name, bytes)
    }
  }

  companion object {
    const val MAX_ATTACHMENTS = 5
    const val MAX_BYTES = 25 * 1024 * 1024

    /** Un id impossibile: dice all'engine "il messaggio dell'utente c'e' gia', crea solo la risposta". */
    const val REGENERATE_AFTER_EDIT = -1L
  }
}
