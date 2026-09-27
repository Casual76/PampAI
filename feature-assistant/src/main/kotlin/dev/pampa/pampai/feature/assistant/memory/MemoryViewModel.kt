package dev.pampa.pampai.feature.assistant.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.pampai.core.assistant.db.Memory
import dev.pampa.pampai.core.assistant.db.MemoryRepository
import dev.pampa.pampai.core.assistant.db.ReminderEntity
import dev.pampa.pampai.core.assistant.reminders.ReminderRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Cosa Aria ricorda e cosa deve ricordarti: i fatti della memoria a lungo termine e i promemoria.
 * Prima esistevano solo dentro la chat ("cosa sai di me?", "cancella il promemoria delle 8"):
 * qui si vedono tutti insieme, si correggono e si cancellano con un tocco.
 */
@HiltViewModel
class MemoryViewModel @Inject constructor(
  private val memory: MemoryRepository,
  private val reminders: ReminderRepository,
) : ViewModel() {

  /** null finche' Room non ha risposto: la pagina non dice "vuoto" prima di saperlo. */
  val memories: StateFlow<List<Memory>?> = memory.observeAll()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

  /** I promemoria ancora attivi, dal piu' vicino. */
  val upcoming: StateFlow<List<ReminderEntity>?> = reminders.observeAll()
    .map { list -> list.filter { it.enabled }.sortedBy { it.atMillis } }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

  fun add(text: String) = viewModelScope.launch { if (text.isNotBlank()) memory.add(text, null, System.currentTimeMillis()) }

  fun update(id: Long, text: String) = viewModelScope.launch { memory.update(id, text) }

  fun remove(id: Long) = viewModelScope.launch { memory.remove(id) }

  fun setPinned(id: Long, pinned: Boolean) = viewModelScope.launch { memory.setPinned(id, pinned) }

  fun clearMemory() = viewModelScope.launch { memory.clear() }

  fun removeReminder(id: Long) = viewModelScope.launch { reminders.remove(id) }
}
