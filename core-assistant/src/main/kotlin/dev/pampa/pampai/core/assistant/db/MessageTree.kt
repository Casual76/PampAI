package dev.pampa.pampai.core.assistant.db

/**
 * Quale versione e' un messaggio fra i suoi fratelli, per le frecce "‹ 2/3 ›": [index] parte da 1,
 * [prevId]/[nextId] sono i fratelli accanto (null ai bordi). Passare a un fratello e' passare al suo
 * ramo intero ([ConversationsRepository.selectVersion]).
 */
data class Version(val index: Int, val count: Int, val prevId: Long?, val nextId: Long?)

/** Un messaggio del cammino mostrato, con la sua posizione fra le versioni. */
data class PathNode(val message: MessageEntity, val version: Version)

/**
 * L'albero dei messaggi di una conversazione, in funzioni pure (niente Room, niente Android: si
 * provano sulla JVM). Un messaggio punta al padre; i fratelli sono le versioni, in ordine di
 * (createdAtMillis, id); il cammino mostrato va da una prima domanda a una foglia.
 *
 * Tutto e' tollerante ai dati storti, perche' viene da un disco che puo' averne: un padre che non
 * c'e' piu' fa del figlio una radice (meglio mostrarlo che perderlo), e un ciclo non manda in
 * giro all'infinito nessuno — ogni visita tiene l'elenco di chi ha gia' visto.
 */
object MessageTree {

  /**
   * Il cammino da mostrare: dalla radice fino alla foglia di [activeLeafId]. Se [activeLeafId] non
   * e' una foglia si scende fino alla sua foglia scelta piu' di recente ([bestLeaf]), cosi' il
   * cammino e' sempre completo; se e' nullo o non esiste, vale la foglia piu' recente di tutta la
   * conversazione.
   */
  fun path(all: List<MessageEntity>, activeLeafId: Long?): List<PathNode> {
    val tree = Tree(all)
    val leaf = tree.resolveLeaf(activeLeafId) ?: return emptyList()
    return tree.chainTo(leaf).map { PathNode(it, tree.versionOf(it)) }
  }

  /** La foglia su cui finisce il cammino mostrato ([path]): e' li' che si attacca la prossima domanda. */
  fun leafOf(all: List<MessageEntity>, activeLeafId: Long?): Long? = Tree(all).resolveLeaf(activeLeafId)

  /**
   * La foglia scelta piu' di recente nel sottoalbero di [nodeId] (lui compreso): la piu' alta per
   * (selectedAtMillis, id). Null se [nodeId] non c'e'.
   */
  fun bestLeaf(all: List<MessageEntity>, nodeId: Long): Long? {
    val tree = Tree(all)
    if (nodeId !in tree.byId) return null
    return tree.bestLeaf(listOf(nodeId))
  }

  /** I messaggi dalla radice fino a [nodeId], lui compreso. Vuoto se [nodeId] non c'e'. */
  fun ancestors(all: List<MessageEntity>, nodeId: Long): List<MessageEntity> {
    val tree = Tree(all)
    if (nodeId !in tree.byId) return emptyList()
    return tree.chainTo(nodeId)
  }

  /** Gli id del sottoalbero di [nodeId], lui compreso: cio' che cade insieme a lui. */
  fun subtree(all: List<MessageEntity>, nodeId: Long): Set<Long> {
    val tree = Tree(all)
    if (nodeId !in tree.byId) return emptySet()
    val seen = LinkedHashSet<Long>()
    val stack = ArrayDeque<Long>().apply { addLast(nodeId) }
    while (stack.isNotEmpty()) {
      val id = stack.removeLast()
      if (!seen.add(id)) continue
      tree.childrenOf(id).forEach { stack.addLast(it.id) }
    }
    return seen
  }

  /** L'albero indicizzato una volta sola: per id, e per padre con i figli gia' in ordine. */
  private class Tree(all: List<MessageEntity>) {
    val byId: Map<Long, MessageEntity> = all.associateBy { it.id }
    private val children: Map<Long?, List<MessageEntity>> = all.groupBy { parentOf(it) }.mapValues { (_, list) -> list.sortedWith(ORDER) }

    /** Il padre, se esiste davvero: uno sparito (o se stesso) vuol dire radice. */
    fun parentOf(message: MessageEntity): Long? = message.parentId?.takeIf { it != message.id && it in byId }

    fun childrenOf(id: Long?): List<MessageEntity> = children[id].orEmpty()

    fun resolveLeaf(activeLeafId: Long?): Long? {
      val start = activeLeafId?.takeIf { it in byId }
      if (start != null) return bestLeaf(listOf(start))
      val roots = childrenOf(null)
      if (roots.isNotEmpty()) return bestLeaf(roots.map { it.id })
      // Nessuna radice: tutto e' in un ciclo. Si parte dal messaggio scelto per ultimo.
      return byId.values.maxWithOrNull(FRESHNESS)?.id
    }

    /** La foglia piu' fresca raggiungibile da [starts]; se sono tutte in un ciclo, il primo punto di partenza. */
    fun bestLeaf(starts: List<Long>): Long? {
      val seen = HashSet<Long>()
      val stack = ArrayDeque(starts)
      var best: MessageEntity? = null
      while (stack.isNotEmpty()) {
        val id = stack.removeLast()
        if (!seen.add(id)) continue
        val message = byId[id] ?: continue
        val kids = childrenOf(id)
        if (kids.isEmpty()) {
          if (best == null || FRESHNESS.compare(message, best) > 0) best = message
        } else {
          kids.forEach { stack.addLast(it.id) }
        }
      }
      return best?.id ?: starts.firstOrNull()
    }

    /** Dalla radice a [nodeId]: si risale dai padri e si gira, fermandosi al primo gia' visto. */
    fun chainTo(nodeId: Long): List<MessageEntity> {
      val chain = mutableListOf<MessageEntity>()
      val seen = HashSet<Long>()
      var current = byId[nodeId]
      while (current != null && seen.add(current.id)) {
        chain += current
        current = parentOf(current)?.let { byId[it] }
      }
      chain.reverse()
      return chain
    }

    fun versionOf(message: MessageEntity): Version {
      val siblings = childrenOf(parentOf(message))
      val index = siblings.indexOfFirst { it.id == message.id }
      if (index < 0) return Version(1, 1, null, null)
      return Version(
        index = index + 1,
        count = siblings.size,
        prevId = siblings.getOrNull(index - 1)?.id,
        nextId = siblings.getOrNull(index + 1)?.id,
      )
    }
  }

  /** L'ordine delle versioni: quella nata prima viene prima. */
  private val ORDER = compareBy<MessageEntity>({ it.createdAtMillis }, { it.id })

  /** Quale foglia vince: quella scelta per ultima, e a parita' la piu' nuova. */
  private val FRESHNESS = compareBy<MessageEntity>({ it.selectedAtMillis }, { it.id })
}
