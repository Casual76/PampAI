package dev.pampa.pampai.core.assistant.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** L'albero delle versioni, sulla JVM: cammino, fratelli, frecce, dati storti. */
class MessageTreeTest {

  private fun user(id: Long, parent: Long?, at: Long = id * 10, selected: Long = at) =
    MessageEntity(id = id, conversationId = 1, role = "USER", text = "d$id", status = "DONE", createdAtMillis = at, parentId = parent, selectedAtMillis = selected)

  private fun answer(id: Long, parent: Long?, at: Long = id * 10, selected: Long = at) =
    MessageEntity(id = id, conversationId = 1, role = "ASSISTANT", text = "r$id", status = "DONE", createdAtMillis = at, parentId = parent, selectedAtMillis = selected)

  private fun ids(path: List<PathNode>) = path.map { it.message.id }

  @Test
  fun `una conversazione lineare e' la catena dalla prima domanda all'ultima risposta`() {
    val all = listOf(user(1, null), answer(2, 1), user(3, 2), answer(4, 3))
    val path = MessageTree.path(all.shuffled(), activeLeafId = 4)
    assertEquals(listOf(1L, 2L, 3L, 4L), ids(path))
    assertTrue(path.all { it.version == Version(1, 1, null, null) })
    // Senza foglia salvata vale la piu' fresca, che in una catena e' l'ultima.
    assertEquals(listOf(1L, 2L, 3L, 4L), ids(MessageTree.path(all, activeLeafId = null)))
  }

  @Test
  fun `le versioni sono in ordine di nascita, e a parita' di millisecondo decide l'id`() {
    // Tre risposte alla stessa domanda: la 5 e la 3 nate nello stesso millisecondo.
    val all = listOf(user(1, null), answer(2, 1, at = 100), answer(5, 1, at = 300), answer(3, 1, at = 300))
    val path = MessageTree.path(all, activeLeafId = 3)
    assertEquals(listOf(1L, 3L), ids(path))
    assertEquals(Version(index = 2, count = 3, prevId = 2, nextId = 5), path[1].version)
    assertEquals(Version(1, 3, null, 3), MessageTree.path(all, 2)[1].version)
    assertEquals(Version(3, 3, 3, null), MessageTree.path(all, 5)[1].version)
  }

  @Test
  fun `cambiare ramo e tornare riapre la foglia dove si era rimasti`() {
    // d1 -> r2 -> d3 -> r4 (ramo A, con due versioni di r4: r4 e r6) ; d1 -> r5 (rigenerata) -> d7 -> r8.
    val all = listOf(
      user(1, null, at = 10),
      answer(2, 1, at = 20, selected = 20),
      user(3, 2, at = 30),
      answer(4, 3, at = 40, selected = 40),
      answer(6, 3, at = 60, selected = 90),
      answer(5, 1, at = 50, selected = 50),
      user(7, 5, at = 70),
      answer(8, 7, at = 80, selected = 80),
    )
    // Dalla versione r5 si torna a r2: vince la foglia piu' fresca sotto r2, cioe' r6 (scelta a 90).
    assertEquals(6L, MessageTree.bestLeaf(all, 2))
    assertEquals(listOf(1L, 2L, 3L, 6L), ids(MessageTree.path(all, MessageTree.bestLeaf(all, 2))))
    // E da r2 a r5: il suo ramo intero.
    assertEquals(listOf(1L, 5L, 7L, 8L), ids(MessageTree.path(all, MessageTree.bestLeaf(all, 5))))
    // Una foglia attiva che non e' una foglia scende fino alla sua foglia piu' fresca.
    assertEquals(listOf(1L, 2L, 3L, 6L), ids(MessageTree.path(all, activeLeafId = 3)))
    // Il cammino e le versioni lungo di esso.
    val path = MessageTree.path(all, 6)
    assertEquals(Version(1, 2, null, 5), path[1].version)
    assertEquals(Version(2, 2, 4, null), path[3].version)
    // Senza foglia: la piu' fresca di tutta la conversazione.
    assertEquals(6L, MessageTree.leafOf(all, null))
  }

  @Test
  fun `una foglia che non c'e' piu' vale come nessuna foglia`() {
    val all = listOf(user(1, null), answer(2, 1, selected = 500), answer(3, 1, selected = 100))
    assertEquals(listOf(1L, 2L), ids(MessageTree.path(all, activeLeafId = 99)))
    assertNull(MessageTree.bestLeaf(all, 99))
    assertTrue(MessageTree.ancestors(all, 99).isEmpty())
    assertTrue(MessageTree.subtree(all, 99).isEmpty())
    assertTrue(MessageTree.path(emptyList(), 1).isEmpty())
  }

  @Test
  fun `un padre sparito fa del figlio una radice, e un ciclo non gira all'infinito`() {
    val orphan = listOf(user(1, null), answer(2, 1), user(3, parent = 42), answer(4, 3))
    // La 3 punta a un padre che non c'e': e' una radice, sorella della 1.
    val path = MessageTree.path(orphan, 4)
    assertEquals(listOf(3L, 4L), ids(path))
    assertEquals(Version(2, 2, 1, null), path[0].version)

    val cycle = listOf(user(1, parent = 2), answer(2, parent = 1), user(3, parent = 3))
    val fromCycle = MessageTree.path(cycle, 1)
    assertEquals(setOf(1L, 2L), ids(fromCycle).toSet())
    assertEquals(2, fromCycle.size)
    // La 3 e' padre di se stessa: vale come radice, e senza foglia salvata si trova lei.
    assertEquals(listOf(3L), ids(MessageTree.path(cycle, null)))
    assertEquals(setOf(1L, 2L), MessageTree.subtree(cycle, 1))
    // Tutto in un ciclo, nessuna radice: si parte dal messaggio scelto per ultimo.
    val onlyCycle = listOf(user(1, parent = 2, selected = 5), answer(2, parent = 1, selected = 9))
    assertEquals(2, MessageTree.path(onlyCycle, null).size)
  }

  @Test
  fun `le prime domande sorelle sono versioni della prima domanda`() {
    // "Modifica e rinvia" sulla prima domanda: due radici.
    val all = listOf(user(1, null, at = 10), answer(2, 1, at = 20), user(3, null, at = 30, selected = 30), answer(4, 3, at = 40, selected = 40))
    val path = MessageTree.path(all, null)
    assertEquals(listOf(3L, 4L), ids(path))
    assertEquals(Version(2, 2, 1, null), path[0].version)
    assertEquals(Version(1, 1, null, null), path[1].version)
    assertEquals(listOf(1L, 2L), ids(MessageTree.path(all, MessageTree.bestLeaf(all, 1))))
  }

  @Test
  fun `antenati e sottoalbero`() {
    val all = listOf(user(1, null), answer(2, 1), user(3, 2), answer(4, 3), answer(5, 3), user(6, 5))
    assertEquals(listOf(1L, 2L, 3L), MessageTree.ancestors(all, 3).map { it.id })
    assertEquals(setOf(3L, 4L, 5L, 6L), MessageTree.subtree(all, 3))
    assertEquals(setOf(4L), MessageTree.subtree(all, 4))
  }
}
