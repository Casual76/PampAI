package dev.pampa.pampai.debug

import dev.antigravity.fluidengine.ai.net.AiError
import dev.antigravity.fluidengine.ai.net.BadReason
import dev.antigravity.fluidengine.ai.net.RateLimitInfo
import dev.antigravity.fluidengine.ai.provider.ChatDelta
import dev.antigravity.fluidengine.ai.provider.ChatProvider
import dev.antigravity.fluidengine.ai.provider.ChatRequest
import dev.antigravity.fluidengine.ai.provider.ChatTurn
import dev.antigravity.fluidengine.ai.provider.FinishReason
import dev.antigravity.fluidengine.ai.provider.Message
import dev.antigravity.fluidengine.ai.provider.ModelCatalogue
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.antigravity.fluidengine.ai.provider.TranscribeOptions
import dev.antigravity.fluidengine.ai.provider.Transcript
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FaultInjectorTest {

  private val chat = ChatRequest(model = "llama-3.3-70b-versatile", messages = listOf(Message.User("ciao")))
  private val router = chat.copy(jsonSchema = JsonObject(emptyMap()))

  // --- il comando ----------------------------------------------------------------------------------

  @Test
  fun `parse legge kind, provider, count e call`() {
    assertEquals(FaultCommand.Off, FaultCommand.parse("off", null, null, null))
    assertEquals(FaultCommand.Off, FaultCommand.parse(" OFF ", "groq", 3, null))
    assertEquals(
      FaultCommand.Arm(listOf(ProviderId.GROQ), FaultKind.TOOL_USE_FAILED, 1, CallTarget.CHAT),
      FaultCommand.parse("tool_use_failed", "groq", null, null),
    )
    assertEquals(
      FaultCommand.Arm(ProviderId.entries.toList(), FaultKind.RATE_LIMIT, 2, CallTarget.ROUTER),
      FaultCommand.parse("rate_limit", "all", 2, "router"),
    )
  }

  @Test
  fun `parse rifiuta quello che non capisce`() {
    assertTrue(FaultCommand.parse("boom", "groq", null, null) is FaultCommand.Invalid)
    assertTrue(FaultCommand.parse(null, "groq", null, null) is FaultCommand.Invalid)
    assertTrue(FaultCommand.parse("server", null, null, null) is FaultCommand.Invalid)
    assertTrue(FaultCommand.parse("server", "anthropic", null, null) is FaultCommand.Invalid)
    assertTrue(FaultCommand.parse("server", "groq", 0, null) is FaultCommand.Invalid)
    assertTrue(FaultCommand.parse("server", "groq", 1, "voce") is FaultCommand.Invalid)
  }

  // --- il piano ------------------------------------------------------------------------------------

  @Test
  fun `il piano consuma count chiamate e poi si spegne`() {
    val plan = FaultPlan()
    plan.arm(FaultCommand.Arm(listOf(ProviderId.GROQ), FaultKind.SERVER, 2, CallTarget.CHAT))
    assertTrue(plan.isArmed(ProviderId.GROQ))
    assertFalse(plan.isArmed(ProviderId.GEMINI))
    assertNull(plan.take(ProviderId.GEMINI, chat))
    assertEquals(FaultPlan.Shot(FaultKind.SERVER, 1), plan.take(ProviderId.GROQ, chat))
    assertEquals(FaultPlan.Shot(FaultKind.SERVER, 0), plan.take(ProviderId.GROQ, chat))
    assertNull(plan.take(ProviderId.GROQ, chat))
    assertFalse(plan.isArmed(ProviderId.GROQ))
  }

  @Test
  fun `il router non consuma un guasto della chat, e viceversa`() {
    val plan = FaultPlan()
    plan.arm(FaultCommand.Arm(listOf(ProviderId.GROQ), FaultKind.TOOL_USE_FAILED, 1, CallTarget.CHAT))
    plan.arm(FaultCommand.Arm(listOf(ProviderId.GEMINI), FaultKind.SERVER, 1, CallTarget.ROUTER))
    assertNull(plan.take(ProviderId.GROQ, router))
    assertNull(plan.take(ProviderId.GEMINI, chat))
    assertEquals(FaultKind.TOOL_USE_FAILED, plan.take(ProviderId.GROQ, chat)?.kind)
    assertEquals(FaultKind.SERVER, plan.take(ProviderId.GEMINI, router)?.kind)
  }

  @Test
  fun `off spegne tutto e un comando nuovo sostituisce il vecchio`() {
    val lines = mutableListOf<String>()
    val injector = FaultInjector { lines += it }
    injector.apply(FaultCommand.parse("server", "all", 5, null))
    injector.apply(FaultCommand.parse("empty", "groq", 1, null))
    assertTrue(injector.state(), "groq=empty x1 chat" in injector.state())
    assertTrue(injector.state(), "gemini=server x5 chat" in injector.state())
    injector.apply(FaultCommand.Off)
    assertEquals("[]", injector.state())
    assertEquals(3, lines.size)
  }

  @Test
  fun `a riposo il provider esce identico`() {
    val injector = FaultInjector { }
    val real = FakeProvider(ProviderId.GROQ)
    assertSame(real, injector.decorate(real))
    injector.apply(FaultCommand.parse("server", "groq", 1, null))
    assertTrue(injector.decorate(real) is FaultyProvider)
    val gemini = FakeProvider(ProviderId.GEMINI)
    assertSame(gemini, injector.decorate(gemini))
  }

  // --- i guasti, classificati dal mapper vero ------------------------------------------------------

  @Test
  fun `tool_use_failed e' TOOL_USE_FAILED su Groq e OpenRouter, una risposta malformata su Gemini`() = runBlocking<Unit> {
    val groqStream = streamError(ProviderId.GROQ, FaultKind.TOOL_USE_FAILED) as AiError.BadRequest
    assertEquals(BadReason.TOOL_USE_FAILED, groqStream.reason)
    assertEquals(200, groqStream.httpCode)
    val groqComplete = completeError(ProviderId.GROQ, FaultKind.TOOL_USE_FAILED) as AiError.BadRequest
    assertEquals(BadReason.TOOL_USE_FAILED, groqComplete.reason)
    assertEquals(400, groqComplete.httpCode)
    assertTrue(groqComplete.providerMessage.orEmpty().startsWith("tool_use_failed: "))
    val openRouter = streamError(ProviderId.OPENROUTER, FaultKind.TOOL_USE_FAILED) as AiError.BadRequest
    assertEquals(BadReason.TOOL_USE_FAILED, openRouter.reason)

    val gemini = faulty(ProviderId.GEMINI, FaultKind.TOOL_USE_FAILED, count = 2)
    val finish = gemini.stream(chat).toList().single() as ChatDelta.Finish
    assertEquals(FinishReason.OTHER, finish.reason)
    val turn = gemini.complete(chat)
    assertNull(turn.message.text)
    assertEquals(FinishReason.OTHER, turn.finishReason)
  }

  @Test
  fun `model_gone e' MODEL_UNAVAILABLE su tutti e tre`() = runBlocking<Unit> {
    for (provider in ProviderId.entries) {
      val error = streamError(provider, FaultKind.MODEL_GONE) as AiError.BadRequest
      assertEquals(provider.id, BadReason.MODEL_UNAVAILABLE, error.reason)
    }
  }

  @Test
  fun `context e' CONTEXT_TOO_LONG su tutti e tre, col 413 su Groq`() = runBlocking<Unit> {
    for (provider in ProviderId.entries) {
      val error = completeError(provider, FaultKind.CONTEXT) as AiError.BadRequest
      assertEquals(provider.id, BadReason.CONTEXT_TOO_LONG, error.reason)
    }
    assertEquals(413, completeError(ProviderId.GROQ, FaultKind.CONTEXT).httpCode)
  }

  @Test
  fun `rate_limit e' un 429 con la sua attesa e senza il tetto giornaliero`() = runBlocking<Unit> {
    for (provider in ProviderId.entries) {
      val error = streamError(provider, FaultKind.RATE_LIMIT) as AiError.RateLimited
      assertEquals(provider.id, FaultWire.RETRY_AFTER_SEC.toDouble(), error.retryAfterSec ?: -1.0, 0.01)
      assertFalse(provider.id, error.freeModelCap)
    }
    val groq = streamError(ProviderId.GROQ, FaultKind.RATE_LIMIT) as AiError.RateLimited
    assertEquals(0, groq.rateLimit.remainingTokens)
  }

  @Test
  fun `server e' un 503`() = runBlocking<Unit> {
    for (provider in ProviderId.entries) {
      val error = completeError(provider, FaultKind.SERVER) as AiError.Server
      assertEquals(provider.id, 503, error.code)
    }
  }

  @Test
  fun `empty e' una risposta senza testo che finisce per LENGTH, in stream e senza`() = runBlocking<Unit> {
    for (provider in ProviderId.entries) {
      val faulty = faulty(provider, FaultKind.EMPTY, count = 2)
      val deltas = faulty.stream(chat).toList()
      assertTrue(provider.id, deltas.none { it is ChatDelta.Text || it is ChatDelta.ToolCallPart })
      assertEquals(provider.id, FinishReason.LENGTH, (deltas.last() as ChatDelta.Finish).reason)
      val turn = faulty.complete(chat)
      assertNull(provider.id, turn.message.text)
      assertTrue(provider.id, turn.message.toolCalls.isEmpty())
      assertEquals(provider.id, FinishReason.LENGTH, turn.finishReason)
    }
  }

  // --- stall ---------------------------------------------------------------------------------------

  @Test
  fun `stall col timeout corto tace fino al timeout e poi e' un Timeout`() = runBlocking<Unit> {
    val slept = mutableListOf<Long>()
    val real = FakeProvider(ProviderId.GROQ)
    val faulty = faulty(ProviderId.GROQ, FaultKind.STALL, real = real, sleep = { slept += it })
    try {
      faulty.stream(chat.copy(readTimeoutMillis = 45_000)).toList()
      fail("doveva andare in timeout")
    } catch (e: AiError.Timeout) {
      // atteso
    }
    assertEquals(listOf(45_000L), slept)
    assertEquals(0, real.calls)
  }

  @Test
  fun `stall col timeout lungo tace 50 secondi e poi risponde il servizio vero`() = runBlocking<Unit> {
    val slept = mutableListOf<Long>()
    val real = FakeProvider(ProviderId.GEMINI)
    val faulty = faulty(ProviderId.GEMINI, FaultKind.STALL, real = real, sleep = { slept += it })
    val deltas = faulty.stream(chat.copy(readTimeoutMillis = 120_000)).toList()
    assertEquals(listOf(FaultWire.STALL_MILLIS), slept)
    assertEquals("vero", (deltas.first() as ChatDelta.Text).text)
    assertEquals(1, real.calls)
  }

  @Test
  fun `stall senza pazienza nella richiesta usa quelle di AiHttp`() {
    assertEquals(FaultWire.Silence(45_000L, timesOut = true), FaultWire.silence(chat, streaming = true))
    assertEquals(FaultWire.Silence(FaultWire.STALL_MILLIS, timesOut = false), FaultWire.silence(chat, streaming = false))
  }

  // --- il resto passa ------------------------------------------------------------------------------

  @Test
  fun `finiti i guasti, e per il router, risponde il provider vero`() = runBlocking<Unit> {
    val real = FakeProvider(ProviderId.GROQ)
    val lines = mutableListOf<String>()
    val plan = FaultPlan().apply { arm(FaultCommand.Arm(listOf(ProviderId.GROQ), FaultKind.SERVER, 1, CallTarget.CHAT)) }
    val faulty = FaultyProvider(real, plan, report = { lines += it })
    assertEquals("vero", faulty.complete(router).message.text)
    try {
      faulty.complete(chat)
      fail("doveva fallire")
    } catch (e: AiError.Server) {
      // atteso
    }
    assertEquals("vero", faulty.complete(chat).message.text)
    assertEquals(2, real.calls)
    assertEquals(1, lines.size)
    assertTrue(lines.single(), lines.single().startsWith("groq: iniettato server (complete"))
  }

  // --- attrezzi ------------------------------------------------------------------------------------

  private fun faulty(
    provider: ProviderId,
    kind: FaultKind,
    count: Int = 1,
    real: FakeProvider = FakeProvider(provider),
    sleep: suspend (Long) -> Unit = {},
  ): FaultyProvider {
    val plan = FaultPlan().apply { arm(FaultCommand.Arm(listOf(provider), kind, count, CallTarget.CHAT)) }
    return FaultyProvider(real, plan, report = {}, sleep = sleep)
  }

  private suspend fun streamError(provider: ProviderId, kind: FaultKind): AiError {
    try {
      faulty(provider, kind).stream(chat).toList()
    } catch (e: AiError) {
      return e
    }
    throw AssertionError("${provider.id} $kind: lo stream doveva fallire")
  }

  private suspend fun completeError(provider: ProviderId, kind: FaultKind): AiError {
    try {
      faulty(provider, kind).complete(chat)
    } catch (e: AiError) {
      return e
    }
    throw AssertionError("${provider.id} $kind: la chiamata doveva fallire")
  }

  private class FakeProvider(override val id: ProviderId) : ChatProvider {
    var calls = 0

    override suspend fun complete(request: ChatRequest): ChatTurn {
      calls++
      return ChatTurn(Message.Assistant("vero"), FinishReason.STOP, null, RateLimitInfo.EMPTY)
    }

    override fun stream(request: ChatRequest): Flow<ChatDelta> {
      calls++
      return flowOf(ChatDelta.Text("vero"), ChatDelta.Finish(FinishReason.STOP, null, RateLimitInfo.EMPTY))
    }

    override suspend fun listModels(): ModelCatalogue = error("non serve")

    override suspend fun transcribe(audio: File, mime: String, options: TranscribeOptions): Transcript = error("non serve")
  }
}
