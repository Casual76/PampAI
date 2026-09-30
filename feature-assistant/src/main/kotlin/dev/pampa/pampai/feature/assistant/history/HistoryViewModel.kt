package dev.pampa.pampai.feature.assistant.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.pampai.core.assistant.db.Conversation
import dev.pampa.pampai.core.assistant.db.ConversationsRepository
import dev.pampa.pampai.core.assistant.db.SearchHit
import dev.pampa.pampai.core.assistant.runtime.AssistantEngine
import dev.pampa.pampai.core.assistant.runtime.AssistantRuntime
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** La lista delle conversazioni, dalla piu' recente, e la ricerca dentro di esse. */
@OptIn(FlowPreview::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
  private val conversations: ConversationsRepository,
  private val runtime: AssistantRuntime,
  private val engine: AssistantEngine,
) : ViewModel() {

  val items: StateFlow<List<Conversation>> = conversations.observeConversations()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  val activeConversationId: StateFlow<Long?> = runtime.activeConversationId

  val busy: StateFlow<Boolean> = runtime.state.map { it.isBusy }.distinctUntilChanged()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

  private val queryFlow = MutableStateFlow("")
  val query: StateFlow<String> = queryFlow

  private val hitsFlow = MutableStateFlow<List<SearchHit>>(emptyList())
  val hits: StateFlow<List<SearchHit>> = hitsFlow

  init {
    viewModelScope.launch {
      queryFlow.debounce(250).collect { q -> hitsFlow.value = if (q.length < 3) emptyList() else runCatching { conversations.search(q) }.getOrDefault(emptyList()) }
    }
  }

  fun setQuery(query: String) {
    queryFlow.value = query
  }

  fun pin(id: Long, pinned: Boolean) = viewModelScope.launch { conversations.setPinned(id, pinned) }

  fun rename(id: Long, title: String) = viewModelScope.launch { conversations.rename(id, title, auto = false) }

  /**
   * Un passaggio trovato cercando: il ramo che lo contiene diventa quello attivo, poi [open] apre la
   * conversazione (passando dalla chat, che tiene plugin e temporanea). Se il messaggio non c'e'
   * piu' si apre lo stesso la conversazione del risultato.
   */
  fun openHit(hit: SearchHit, open: (Long) -> Unit) = viewModelScope.launch {
    val id = runCatching { conversations.activateMessage(hit.messageId) }.getOrNull() ?: hit.conversationId
    open(id)
  }

  fun delete(id: Long) = viewModelScope.launch {
    // Si ferma solo il lavoro su questa conversazione: una risposta dell'overlay su un'altra resta.
    if (runtime.isBusy && runtime.liveTrack.value.owner.conversationId == id) runtime.cancel()
    if (runtime.activeConversationId.value == id) runtime.selectConversation(null)
    engine.forget(id)
    conversations.delete(id)
  }

  fun deleteAll() = viewModelScope.launch {
    if (runtime.isBusy) runtime.cancel()
    runtime.selectConversation(null)
    items.value.forEach { engine.forget(it.id) }
    // La lista non contiene le temporanee, ma `deleteAll` le cancella comunque: cosi' l'engine non
    // resta con la loro storia in memoria su un id che SQLite puo' riassegnare.
    engine.dropTemporary()
    conversations.deleteAll()
  }
}
