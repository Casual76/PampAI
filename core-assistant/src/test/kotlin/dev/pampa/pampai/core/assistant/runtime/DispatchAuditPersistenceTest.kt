package dev.pampa.pampai.core.assistant.runtime

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.antigravity.fluidengine.ai.keys.ThinkingLevel
import dev.antigravity.fluidengine.ai.net.RateLimitInfo
import dev.antigravity.fluidengine.ai.provider.*
import dev.pampa.pampai.core.assistant.db.PampaiDatabase
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DispatchAuditPersistenceTest {
  @Test fun queuedPoliciesSurviveReopen() = runBlocking {
    val context: Context = ApplicationProvider.getApplicationContext()
    val name = "audit.db"
    context.getDatabasePath(name).parentFile?.mkdirs()
    var db = Room.databaseBuilder(context, PampaiDatabase::class.java, name).build()
    var effects = 0
    val provider = object : ChatProvider {
      override val id = ProviderId.GROQ
      override suspend fun complete(request: ChatRequest): ChatTurn {
        request.beforeDispatch(ProviderDispatch(id, request.model, request.reasoning, request.reasoning.name, "SENT_UNVERIFIED"))
        assertEquals(effects + 1, db.dispatches().forMessage(2).size)
        effects++
        return ChatTurn(Message.Assistant("ok"), FinishReason.STOP, null, RateLimitInfo.EMPTY)
      }
      override fun stream(request: ChatRequest): Flow<ChatDelta> = flow { error("unused") }
      override suspend fun listModels() = ModelCatalogue(emptyList(), emptyList())
      override suspend fun transcribe(audio: File, mime: String, options: TranscribeOptions): Transcript = error("unused")
    }
    try {
      val selected = ExactModel(ProviderId.GROQ, "exact")
      val low = ModelPolicy(selected, ThinkingLevel.LOW)
      val high = ModelPolicy(selected, ThinkingLevel.HIGH)
      val ready = ReadyProvider(provider, "exact", "unused", "exact")
      val queuedLow = ready.audited("low-attempt", 1, 2, low) { db.dispatches().insert(it); Unit }
      val queuedHigh = ready.audited("high-attempt", 1, 2, high) { db.dispatches().insert(it); Unit }
      queuedLow.provider.complete(ChatRequest("exact", listOf(Message.User("one")), reasoning = ReasoningLevel.LOW))
      queuedHigh.provider.complete(ChatRequest("exact", listOf(Message.User("two")), reasoning = ReasoningLevel.HIGH))
      db.close()
      db = Room.databaseBuilder(context, PampaiDatabase::class.java, name).build()
      val rows = db.dispatches().forMessage(2)
      assertEquals(listOf("LOW", "HIGH"), rows.map { it.requested })
      assertEquals(listOf("low-attempt", "high-attempt"), rows.map { it.attemptId })
      assertEquals(listOf("exact", "exact"), rows.map { it.model })
    } finally { db.close(); context.deleteDatabase(name) }
  }
}
