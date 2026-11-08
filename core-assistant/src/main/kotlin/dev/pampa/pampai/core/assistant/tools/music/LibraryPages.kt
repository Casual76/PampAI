package dev.pampa.pampai.core.assistant.tools.music

/** Null denotes failure/incomplete coverage. Empty page is valid exhaustion; pages start at zero. */
internal suspend fun <T> readLibraryPages(pageSize: Int, maxPages: Int = 200,
  fetch: suspend (page: Int, pageSize: Int) -> List<T>?): List<T>? {
  require(pageSize > 0 && maxPages > 0)
  val out = mutableListOf<T>()
  repeat(maxPages) { page ->
    val items = fetch(page, pageSize) ?: return null
    if (items.isEmpty()) return out
    out += items
    if (items.size < pageSize) return out
  }
  return null
}
