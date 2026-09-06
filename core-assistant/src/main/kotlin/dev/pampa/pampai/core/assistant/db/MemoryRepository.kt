package dev.pampa.pampai.core.assistant.db

import dev.pampa.pampai.core.assistant.tools.Text
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class Memory(val id: Long, val text: String, val createdAtMillis: Long, val sourceConversationId: Long?, val pinned: Boolean)

/**
 * La memoria a lungo termine di Aria: fatti che l'utente le ha chiesto di ricordare ("sono
 * allergico alle noci", "la mia fermata e' Santa Maria Novella"). Entrano nel prompt di ogni
 * domanda, con un tetto: le piu' recenti prima, quelle fissate sempre.
 */
@Singleton
class MemoryRepository @Inject constructor(private val dao: MemoryDao) {

  fun observeAll(): Flow<List<Memory>> = dao.observeAll().map { list -> list.map { it.toModel() } }

  suspend fun list(): List<Memory> = dao.listAll().map { it.toModel() }

  suspend fun add(text: String, sourceConversationId: Long?, nowMillis: Long): Long {
    val clean = text.trim().take(MAX_CHARS)
    // Lo stesso fatto detto due volte resta uno: si aggiorna la data.
    dao.listAll().firstOrNull { Text.normalize(it.text) == Text.normalize(clean) }?.let { existing ->
      dao.update(existing.copy(createdAtMillis = nowMillis))
      return existing.id
    }
    return dao.insert(MemoryEntity(text = clean, createdAtMillis = nowMillis, sourceConversationId = sourceConversationId))
  }

  suspend fun remove(id: Long) = dao.delete(id)

  /** Cancella i fatti che contengono queste parole: torna quanti. */
  suspend fun removeMatching(query: String): Int {
    val matches = dao.listAll().filter { Text.matches(query, it.text) }
    matches.forEach { dao.delete(it.id) }
    return matches.size
  }

  suspend fun setPinned(id: Long, pinned: Boolean) {
    val all = dao.listAll()
    all.firstOrNull { it.id == id }?.let { dao.update(it.copy(pinned = pinned)) }
  }

  suspend fun clear() = dao.deleteAll()

  /** Il blocco per il prompt di sistema: vuoto se non c'e' niente da ricordare. */
  suspend fun promptBlock(): String {
    val all = dao.listAll()
    if (all.isEmpty()) return ""
    val lines = mutableListOf<String>()
    var chars = 0
    for (memory in all) {
      val line = "- ${memory.text}"
      if (lines.size >= MAX_ITEMS || chars + line.length > MAX_PROMPT_CHARS) break
      lines += line
      chars += line.length + 1
    }
    return lines.joinToString("\n")
  }

  private fun MemoryEntity.toModel() = Memory(id, text, createdAtMillis, sourceConversationId, pinned)

  companion object {
    const val MAX_CHARS = 300
    const val MAX_ITEMS = 40
    const val MAX_PROMPT_CHARS = 1_500
  }
}
