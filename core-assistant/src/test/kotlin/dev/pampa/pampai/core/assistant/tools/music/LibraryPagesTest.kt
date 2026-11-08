package dev.pampa.pampai.core.assistant.tools.music

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LibraryPagesTest {
  @Test fun `reads zero based pages past first and preserves repeated tracks`() = runBlocking {
    val requested = mutableListOf<Int>()
    val result = readLibraryPages(2) { page, size -> requested += page; assertEquals(2, size); when(page) { 0 -> listOf("a", "b"); 1 -> listOf("b", "c"); else -> emptyList() } }
    assertEquals(listOf(0, 1, 2), requested)
    assertEquals(listOf("a", "b", "b", "c"), result)
  }
  @Test fun `failure in later page never becomes empty or complete partial list`() = runBlocking {
    assertNull(readLibraryPages(2) { page, _ -> if (page == 0) listOf("a", "b") else null })
    assertEquals(emptyList<String>(), readLibraryPages<String>(2) { _, _ -> emptyList() })
  }
  @Test fun `repeated page or exceeded bounded read has unknown coverage`() = runBlocking {
    assertNull(readLibraryPages(2, maxPages = 3) { _, _ -> listOf("a", "b") })
    assertEquals(listOf("a", "a", "a", "a"), readLibraryPages(2) { page, _ -> if (page < 2) listOf("a", "a") else emptyList() })
    assertNull(readLibraryPages(1, maxPages = 2) { page, _ -> listOf(page) })
  }
}
