package dev.pampa.pampai.core.assistant.runtime

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.antigravity.fluidengine.ai.orchestrator.*
import dev.antigravity.fluidengine.ai.provider.*
import dev.pampa.pampai.core.assistant.db.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImageProvenanceTest {
  private val primary = ExactModel(ProviderId.GROQ, "text")
  private val helper = ExactModel(ProviderId.GEMINI, "fixture-vision")
  private val policy = ModelPolicy(primary, helper = helper)
  private val pixels = listOf(ContentPart.Image(byteArrayOf(1, 2), "image/jpeg"))

  @Test fun freshCacheDynamicAndTextOnlyEvidenceSurvivesPolicyChangeAndReopen() = runBlocking {
    val app: Context = ApplicationProvider.getApplicationContext()
    val name = "provenance.db"
    app.deleteDatabase(name)
    var db = Room.databaseBuilder(app, PampaiDatabase::class.java, name).build()
    fun repository() = ConversationsRepository(app, db.conversations(), db.messages(), db.attachments(), db.runs(), Json, db)
    try {
      val repo = repository()
      val cid = repo.createConversation("fixture", 1)
      val turn = repo.startTurn(cid, Anchor.Continue, "image", AskMode.TEXT, emptyList(), 2)
      val cache = ImageInterpretationCache()
      val evidence = mutableListOf<RunImageInterpretation>()
      var billed = 0
      val describe: suspend (ExactModel, ContentPart.Image) -> String = { actual, _ -> assertEquals(helper, actual); billed++; "fixture description" }
      val record: (RunImageInterpretation) -> Unit = { evidence.add(it); Unit }
      cache.interpret(pixels, policy, record, describe)
      assertFalse(evidence.single().cached)
      // Repeated preflights populate cache but must not be appended to the attempt.
      repeat(2) { cache.interpret(pixels, policy, describe) }
      evidence.clear()
      cache.interpret(pixels, policy, record, describe)
      assertTrue(evidence.single().cached)
      assertEquals(1, billed)
      val options = listOf(ModelOption(helper, "fixture", ImageCapability.YES))
      val dynamic = pixels + ContentPart.Image(byteArrayOf(3), "image/png")
      val effective = preparePolicyParts(dynamic, policy, options, { error("unexpected") }) { selected, parts -> cache.interpret(parts, selected, record, describe) }
      assertTrue(effective.all { it is ContentPart.Text })
      assertEquals(listOf(true, true, false), evidence.map { it.cached })
      assertEquals(2, billed)
      assertTrue(pixels.single() is ContentPart.Image)
      val log = AiRequestLog(2, "fixture", AskMode.TEXT, ProviderId.GROQ, "text", emptyList(), emptyList(), false, emptyList(), 1, null, 1, "ok", null, null, 0)
      repo.addRun(cid, turn.assistantId, log, 3, "ok", null, emptyList(), null, null, evidence)
      // A configured helper with text-only input is no evidence of use.
      evidence.clear()
      cache.interpret(listOf(ContentPart.Text("hello")), policy, record, describe)
      assertTrue(evidence.isEmpty())
      repo.addFailedRun(cid, turn.assistantId, 4, 5, "cancelled", null, imageInterpretations = evidence)
      cache.interpret(pixels, policy.copy(helper = helper.copy(model = "changed"))) { _, _ -> "new helper description" }
      db.close()
      db = Room.databaseBuilder(app, PampaiDatabase::class.java, name).build()
      val runs = repository().observeRuns(cid).first()
      val actual = runs.first { it.outcome == "ok" }.imageInterpretations
      assertEquals(listOf(true, true, false), actual.map { it.cached })
      assertTrue(actual.all { it.provider == helper.provider && it.model == helper.model })
      assertTrue(runs.first { it.outcome == "cancelled" }.imageInterpretations.isEmpty())
    } finally { db.close(); app.deleteDatabase(name) }
  }

  @Test fun failedAndCancelledConversionsRetainOnlyCompletedEvidence() = runBlocking {
    for (cancel in listOf(false, true)) {
      val cache = ImageInterpretationCache()
      val evidence = mutableListOf<RunImageInterpretation>()
      var calls = 0
      try {
        cache.interpret(pixels + ContentPart.Image(byteArrayOf(4), "image/jpeg"), policy, { evidence.add(it); Unit }) { _, _ ->
          if (++calls == 2) { if (cancel) throw CancellationException("fixture") else error("fixture") }
          "description"
        }
        fail("must fail")
      } catch (_: CancellationException) { assertTrue(cancel) } catch (_: IllegalStateException) { assertFalse(cancel) }
      assertEquals(listOf(RunImageInterpretation(helper.provider, helper.model, false)), evidence)
      val app: Context = ApplicationProvider.getApplicationContext()
      val name = "failed-provenance-$cancel.db"
      app.deleteDatabase(name)
      var db = Room.databaseBuilder(app, PampaiDatabase::class.java, name).build()
      try {
        val repo = ConversationsRepository(app, db.conversations(), db.messages(), db.attachments(), db.runs(), Json, db)
        val cid = repo.createConversation("fixture", 1)
        val turn = repo.startTurn(cid, Anchor.Continue, "fixture", AskMode.TEXT, emptyList(), 2)
        repo.addFailedRun(cid, turn.assistantId, 2, 3, if (cancel) "cancelled" else "failed", null, imageInterpretations = evidence)
        db.close()
        db = Room.databaseBuilder(app, PampaiDatabase::class.java, name).build()
        val reopened = ConversationsRepository(app, db.conversations(), db.messages(), db.attachments(), db.runs(), Json, db)
        assertEquals(evidence, reopened.observeRuns(cid).first().single().imageInterpretations)
      } finally { db.close(); app.deleteDatabase(name) }
    }
  }
}
