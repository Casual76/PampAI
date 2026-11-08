package dev.pampa.pampai.core.assistant.usage

import dev.antigravity.fluidengine.ai.net.RateLimitInfo
import dev.antigravity.fluidengine.ai.provider.*
import dev.pampa.pampai.core.assistant.db.UsageEventDao
import dev.pampa.pampai.core.assistant.db.UsageEventEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Test

class VisionUsageTest {
  private class Dao : UsageEventDao {
    val rows = mutableListOf<UsageEventEntity>()
    override suspend fun insert(entity: UsageEventEntity): Long { rows += entity; return rows.size.toLong() }
    override fun observeSince(fromMillis: Long) = flowOf(rows.toList())
    override suspend fun countSince(fromMillis: Long, provider: String) = rows.count { it.provider == provider }
    override suspend fun deleteAll() { rows.clear() }
    override suspend fun prune(beforeMillis: Long) {}
  }

  @Test fun `helper usage preserves provider cost and correlation`() = runBlocking {
    val dao = Dao()
    val turn = ChatTurn(Message.Assistant("description"), FinishReason.STOP, Usage(12, 8, 20, .002), RateLimitInfo(remainingRequests = 4))
    UsageRepository(dao).recordVision(ProviderId.GEMINI, "vision-model", turn, 31, 9, null)
    val row = dao.rows.single()
    assertEquals("VISION_HELPER", row.kind)
    assertEquals(9L, row.conversationId)
    assertEquals(.002, row.costUsd!!, 0.0)
    assertEquals(12, row.promptTokens)
    assertEquals(4, row.remainingRequests)
  }

  @Test fun `cancelled helper still records attempt without private error text`() = runBlocking {
    val dao = Dao()
    val repository = UsageRepository(dao)
    val job = launch {
      currentCoroutineContext().cancel()
      repository.recordVision(ProviderId.GROQ, "vision-model", null, 10, null, CancellationException("private request detail"))
    }
    job.join()
    val row = dao.rows.single()
    assertEquals("CancellationException", row.error)
    assertNull(row.promptTokens)
    assertNull(row.costUsd)
  }
}
