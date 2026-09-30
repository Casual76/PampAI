package dev.pampa.pampai.core.assistant.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.antigravity.fluidengine.ai.orchestrator.AskMode
import dev.antigravity.fluidengine.ai.orchestrator.FailureKind
import dev.pampa.pampai.core.assistant.attachments.PendingAttachment
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Le versioni sul repository vero, con Room in memoria: domande, "rigenera", "modifica e rinvia",
 * le frecce, la storia per il modello, le versioni buttate, la cancellazione.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConversationsTreeRepositoryTest {

  private val context: Context = ApplicationProvider.getApplicationContext()
  private lateinit var db: PampaiDatabase
  private lateinit var repo: ConversationsRepository
  private var clock = 1_000L

  private fun tick(): Long {
    clock += 1_000
    return clock
  }

  @Before
  fun setUp() {
    db = Room.inMemoryDatabaseBuilder(context, PampaiDatabase::class.java).allowMainThreadQueries().build()
    repo = ConversationsRepository(context, db.conversations(), db.messages(), db.attachments(), db.runs(), Json { ignoreUnknownKeys = true; encodeDefaults = true }, db)
  }

  @After
  fun tearDown() {
    db.close()
    File(context.filesDir, "attachments").deleteRecursively()
  }

  private suspend fun ask(conversation: Long, text: String, anchor: Anchor = Anchor.Continue, attachments: List<PendingAttachment> = emptyList(), answer: String? = "risposta a $text"): Turn {
    val turn = repo.startTurn(conversation, anchor, text, AskMode.TEXT, attachments, tick())
    if (answer != null) repo.complete(turn.assistantId, answer, emptyList())
    return turn
  }

  private suspend fun shown(conversation: Long) = repo.observeMessages(conversation).first()

  private suspend fun activeLeaf(conversation: Long) = db.conversations().get(conversation)?.activeLeafId

  private fun photo() = PendingAttachment(AttachmentKind.IMAGE, "image/jpeg", "foto.jpg", byteArrayOf(1, 2, 3, 4))

  @Test
  fun `le domande si attaccano in fondo al ramo mostrato`() = runBlocking {
    val c = repo.createConversation("prova", tick())
    val first = ask(c, "uno")
    assertNull(first.historyLeafId)
    assertNull(first.previousLeafId)
    val second = ask(c, "due")
    assertEquals(first.assistantId, second.historyLeafId)
    assertEquals(first.assistantId, second.previousLeafId)
    assertEquals(listOf(first.userId, first.assistantId, second.userId, second.assistantId), shown(c).map { it.id })
    assertEquals(second.assistantId, activeLeaf(c))
    assertEquals(listOf(null, first.userId, first.assistantId, second.userId), shown(c).map { it.parentId })
  }

  @Test
  fun `rigenera aggiunge una sorella alla risposta e le frecce passano da un ramo all'altro`() = runBlocking {
    val c = repo.createConversation("prova", tick())
    val first = ask(c, "uno")
    val second = ask(c, "due")
    val again = ask(c, "due", Anchor.Regenerate(second.assistantId), answer = "un'altra risposta")
    assertNull(again.userId)
    assertEquals(second.userId, again.questionId)
    assertEquals(first.assistantId, again.historyLeafId)
    assertEquals(second.assistantId, again.previousLeafId)

    val now = shown(c)
    assertEquals(listOf(first.userId, first.assistantId, second.userId, again.assistantId), now.map { it.id })
    assertEquals(Version(2, 2, second.assistantId, null), now.last().version)
    assertNull(now.first().version)

    // La freccia indietro: si torna alla prima risposta, e poi di nuovo alla seconda.
    repo.selectVersion(c, second.assistantId, now = tick())
    assertEquals(second.assistantId, shown(c).last().id)
    assertEquals(Version(1, 2, null, again.assistantId), shown(c).last().version)
    repo.selectVersion(c, again.assistantId, now = tick())
    assertEquals(again.assistantId, shown(c).last().id)

    // Rigenerare una risposta in mezzo apre un ramo nuovo senza toccare quelli sotto.
    val third = ask(c, "tre")
    val middle = ask(c, "due", Anchor.Regenerate(again.assistantId), answer = "terza versione")
    assertEquals(listOf(first.userId, first.assistantId, second.userId, middle.assistantId), shown(c).map { it.id })
    assertEquals(3, shown(c).last().version?.count)
    // Tornando sulla versione con il seguito, si riapre fino in fondo.
    repo.selectVersion(c, again.assistantId, now = tick())
    assertEquals(listOf(first.userId, first.assistantId, second.userId, again.assistantId, third.userId, third.assistantId), shown(c).map { it.id })
  }

  @Test
  fun `modifica e rinvia crea una domanda sorella con le copie degli allegati`() = runBlocking {
    val c = repo.createConversation("prova", tick())
    val first = ask(c, "cosa c'e' in questa foto?", attachments = listOf(photo()))
    val edited = ask(c, "cosa c'e' in questa immagine?", Anchor.Edit(first.userId!!))
    assertNull(edited.historyLeafId)

    val now = shown(c)
    assertEquals(listOf(edited.userId, edited.assistantId), now.map { it.id })
    assertEquals(Version(2, 2, first.userId, null), now.first().version)
    // L'allegato e' un file nuovo, con gli stessi byte: cancellare un ramo non tocca l'altro.
    val original = repo.message(first.userId!!)!!.attachments.single()
    val copy = now.first().attachments.single()
    assertNotEquals(original.path, copy.path)
    assertArrayEquals(File(original.path).readBytes(), File(copy.path).readBytes())
    assertEquals(4, repo.pendingAttachmentsOf(edited.userId!!).single().bytes.size)

    // Un "modifica" con allegati nuovi usa quelli, non la copia.
    val other = PendingAttachment(AttachmentKind.DOCUMENT, "text/plain", "nota.txt", "ciao".toByteArray())
    val withNew = ask(c, "e questa?", Anchor.Edit(first.userId!!), attachments = listOf(other))
    assertEquals(listOf("nota.txt"), repo.message(withNew.userId!!)!!.attachments.map { it.name })
  }

  @Test
  fun `la storia per il modello e' solo il cammino sopra la domanda`() = runBlocking {
    val c = repo.createConversation("prova", tick())
    ask(c, "uno")
    val second = ask(c, "due")
    ask(c, "tre")
    // Rigenerando la risposta a "due", la storia e' solo "uno": ne' la risposta vecchia ne' "tre".
    val again = ask(c, "due", Anchor.Regenerate(second.assistantId), answer = null)
    assertEquals(listOf("uno"), repo.exchanges(c, again.questionId, limit = 8).map { it.question })
    // Un ramo nuovo dopo una modifica: la storia e' quella del ramo, non delle altre versioni.
    repo.complete(again.assistantId, "altra", emptyList())
    val fourth = ask(c, "quattro", answer = null)
    assertEquals(listOf("uno" to "risposta a uno", "due" to "altra"), repo.exchanges(c, fourth.questionId, limit = 8).map { it.question to it.answer })
    // Senza domanda: tutto il cammino attivo, con le sole risposte concluse.
    assertEquals(listOf("uno", "due"), repo.exchanges(c, null, limit = 8).map { it.question })
  }

  @Test
  fun `una versione fermata senza testo si butta e si torna a quella di prima`() = runBlocking {
    val c = repo.createConversation("prova", tick())
    ask(c, "uno")
    val second = ask(c, "due")
    val before = shown(c).map { it.id }

    val regenerated = ask(c, "due", Anchor.Regenerate(second.assistantId), answer = null)
    repo.addFailedRun(c, regenerated.assistantId, 0, 1, "cancelled", null)
    repo.discardVersion(regenerated)
    assertEquals(before, shown(c).map { it.id })
    assertEquals(second.assistantId, activeLeaf(c))
    assertNull(db.messages().get(regenerated.assistantId))
    assertTrue(db.runs().listByConversation(c).none { it.messageId == regenerated.assistantId })

    // Una modifica fermata: se ne va anche la domanda nuova, con la copia del suo allegato.
    val withPhoto = ask(c, "tre", attachments = listOf(photo()))
    val edited = ask(c, "tre bis", Anchor.Edit(withPhoto.userId!!), answer = null)
    val copied = repo.message(edited.userId!!)!!.attachments.single().path
    repo.discardVersion(edited)
    assertFalse(File(copied).exists())
    assertNull(db.messages().get(edited.userId!!))
    assertEquals(withPhoto.assistantId, activeLeaf(c))
    assertTrue(File(repo.message(withPhoto.userId!!)!!.attachments.single().path).exists())

    // Una versione che ha gia' detto qualcosa resta.
    val partial = ask(c, "tre", Anchor.Regenerate(withPhoto.assistantId), answer = null)
    repo.updatePartial(partial.assistantId, "Allora, ")
    repo.cancel(partial.assistantId, "Allora, ")
    repo.discardVersion(partial)
    assertEquals(partial.assistantId, activeLeaf(c))
  }

  @Test
  fun `le scritture di una colonna sola non riportano indietro la foglia scelta`() = runBlocking {
    val c = repo.createConversation("prova", tick())
    val first = ask(c, "uno")
    val again = ask(c, "uno", Anchor.Regenerate(first.assistantId), answer = null)
    // Mentre la versione nuova sta ancora rispondendo, l'utente torna alla prima.
    repo.selectVersion(c, first.assistantId, now = tick())
    repo.setLoadedGroups(c, listOf("meteo"))
    repo.complete(again.assistantId, "finita", emptyList())
    repo.fail(again.assistantId, FailureKind.NETWORK, null)
    repo.touch(c, tick(), null)
    assertEquals(first.assistantId, activeLeaf(c))
    assertEquals(listOf("meteo"), repo.conversation(c)!!.loadedGroups)
    // "fail" senza parziale tiene il testo che c'era.
    assertEquals("finita", db.messages().get(again.assistantId)!!.text)
  }

  @Test
  fun `un risultato della ricerca in un altro ramo diventa il ramo mostrato`() = runBlocking {
    val c = repo.createConversation("prova", tick())
    val first = ask(c, "uno")
    val hidden = ask(c, "parola rara", answer = "ecco")
    val edited = ask(c, "altro", Anchor.Edit(hidden.userId!!))
    assertTrue(shown(c).none { it.id == hidden.userId })
    assertEquals(c, repo.activateMessage(hidden.userId!!, now = tick()))
    assertEquals(listOf(first.userId, first.assistantId, hidden.userId, hidden.assistantId), shown(c).map { it.id })
    assertNull(repo.activateMessage(9_999))
    assertNotEquals(edited.assistantId, activeLeaf(c))
  }

  @Test
  fun `cancellare una conversazione porta via tutti i rami e i file`() = runBlocking {
    val c = repo.createConversation("prova", tick())
    val first = ask(c, "uno", attachments = listOf(photo()))
    val edited = ask(c, "uno bis", Anchor.Edit(first.userId!!))
    ask(c, "uno", Anchor.Regenerate(first.assistantId))
    val files = listOf(first.userId!!, edited.userId!!).flatMap { repo.message(it)!!.attachments.map { a -> a.path } }
    assertEquals(2, files.size)
    files.forEach { assertTrue(File(it).exists()) }
    repo.delete(c)
    assertNull(repo.conversation(c))
    assertTrue(db.messages().listByConversation(c).isEmpty())
    assertTrue(db.attachments().listByConversation(c).isEmpty())
    files.forEach { assertFalse(File(it).exists()) }
  }

  @Test
  fun `rigenera o modifica su un messaggio che non c'e' falliscono senza lasciare niente`() = runBlocking {
    val c = repo.createConversation("prova", tick())
    ask(c, "uno")
    val before = db.messages().listByConversation(c).size
    val failures = listOf(Anchor.Regenerate(9_999), Anchor.Edit(9_999)).map { anchor ->
      runCatching { repo.startTurn(c, anchor, "x", AskMode.TEXT, listOf(photo()), tick()) }.exceptionOrNull()
    }
    failures.forEach { assertTrue(it is IllegalArgumentException) }
    assertEquals(before, db.messages().listByConversation(c).size)
    assertTrue(File(context.filesDir, "attachments").listFiles().orEmpty().isEmpty())
  }
}
