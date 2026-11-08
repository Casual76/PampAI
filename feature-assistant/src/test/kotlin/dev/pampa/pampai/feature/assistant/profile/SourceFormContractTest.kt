package dev.pampa.pampai.feature.assistant.profile

import dev.pampa.pampai.core.assistant.durable.DurableFoundation
import dev.pampa.pampai.core.assistant.research.*
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Real form -> real ResearchSources admission, stopping at the external transport boundary. */
class SourceFormContractTest {
  private class ReachedTransport : RuntimeException()
  @Test fun everyPeriodFormPassesRealReadPolicyBeforeProofNegotiation() = runBlocking {
    val transport = mockk<ResearchTransport>()
    val foundation = mockk<DurableFoundation>()
    coEvery { foundation.grant(any()) } returns null
    coEvery { foundation.readAllowed(any()) } returns true
    coEvery { transport.typed(any()) } throws ReachedTransport()
    val clock = mockk<ResearchClock> {
      every { tick() } returns 100L
      every { wall() } returns 100000L
    }
    val sources = ResearchSources(transport, foundation, VerifiedSourceReceiptCache(clock), clock, mockk())
    val candidate = SourceCandidate("dev.pampa.fluidify.debug", "dev.pampa.fluidify.debug.ai.tools", "fluidify", "synthetic", null, true)
    val sections = listOf("history_records", "history_aggregate", "history_track_counts", "history_artist_counts")
    for (section in sections) {
      val read = SourceDraft(candidate, "synthetic", section = section, from = "2026-01-01", until = "2026-02-01").read()
      val failure = runCatching { sources.connect(read) }.exceptionOrNull()
      assertTrue("$section must reach transport, got $failure", failure is ReachedTransport)
      assertTrue(read.arguments.getValue("from").jsonPrimitive.isString)
      assertTrue(read.arguments.getValue("until").jsonPrimitive.isString)
      assertTrue(read.arguments.getValue("from").jsonPrimitive.content.toLong() < read.arguments.getValue("until").jsonPrimitive.content.toLong())
      val malformed = read.copy(arguments = JsonObject(read.arguments + ("from" to JsonPrimitive(0L))))
      assertEquals("INVALID_ARGUMENT_TYPE", (runCatching { sources.connect(malformed) }.exceptionOrNull() as? ResearchUnavailable)?.code)
    }
    coVerify(exactly = 4) { transport.typed(any()) }
  }
}
