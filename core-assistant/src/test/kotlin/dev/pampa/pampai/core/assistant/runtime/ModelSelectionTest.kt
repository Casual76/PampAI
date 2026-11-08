package dev.pampa.pampai.core.assistant.runtime

import dev.antigravity.fluidengine.ai.provider.*
import dev.antigravity.fluidengine.ai.keys.*
import org.junit.Assert.*
import org.junit.Test

class ModelSelectionTest {
  private val text = ExactModel(ProviderId.GROQ, "text")
  private val vision = ExactModel(ProviderId.OPENROUTER, "verified")
  private val options = listOf(ModelOption(text, "Text", ImageCapability.NO), ModelOption(vision, "Vision", ImageCapability.YES))
  @Test fun `model pin overrides classifier and deep escalation`() {
    val fake = object : ChatProvider {
      override val id = ProviderId.GROQ
      override suspend fun complete(request: ChatRequest): ChatTurn = error("No network")
      override fun stream(request: ChatRequest): kotlinx.coroutines.flow.Flow<ChatDelta> = error("No network")
      override suspend fun listModels(): ModelCatalogue = error("No network")
      override suspend fun transcribe(audio: java.io.File, mime: String, options: TranscribeOptions): Transcript = error("No network")
    }
    val pinned = ReadyProvider(fake, "old", "stt", "router", "deep").pinTo(text)
    ModelTier.entries.forEach { assertEquals(text.model, pinned.model(it)) }
  }
  @Test fun `helper is opt in and cache respects image helper and effort`() = kotlinx.coroutines.runBlocking {
    val cache = ImageInterpretationCache()
    val pixels = listOf(ContentPart.Image(byteArrayOf(1, 2), "image/jpeg"))
    var calls = 0
    val describe: suspend (ExactModel, ContentPart.Image) -> String = { _, _ -> calls++; "descrizione" }
    assertTrue(cache.interpret(pixels, ModelPolicy(text), describe).single() is ContentPart.Image)
    assertEquals(0, calls)
    val policy = ModelPolicy(text, helper = vision)
    val result = cache.interpret(pixels, policy, describe).single() as ContentPart.Text
    assertTrue(result.text.contains("non riceve pixel"))
    cache.interpret(pixels, policy, describe)
    assertEquals(1, calls)
    cache.interpret(pixels, policy.copy(effort = ThinkingLevel.HIGH), describe)
    cache.interpret(pixels, policy.copy(helper = vision.copy(model = "different")), describe)
    assertEquals(3, calls)
  }
  @Test fun `helper failure leaves original image intact and is retryable`() = kotlinx.coroutines.runBlocking {
    val cache = ImageInterpretationCache()
    val image = ContentPart.Image(byteArrayOf(1), "image/jpeg")
    val policy = ModelPolicy(text, helper = vision)
    try { cache.interpret(listOf(image), policy) { _, _ -> error("offline") }; fail("Must fail") } catch (_: IllegalStateException) { }
    assertEquals(1, image.bytes.size)
    val recovered = cache.interpret(listOf(image), policy) { _, _ -> "recuperato" }
    assertTrue((recovered.single() as ContentPart.Text).text.contains("recuperato"))
  }
  @Test fun `documented capabilities match exact provider identity only`() {
    assertEquals(ImageCapability.YES, imageCapability(null, ExactModel(ProviderId.GEMINI, "gemini-2.5-flash")))
    assertEquals(ImageCapability.NO, imageCapability(ModelInfo("gemini-2.5-flash", "Flash", ModelKind.CHAT, visionVerified = true), ExactModel(ProviderId.GEMINI, "gemini-2.5-flash")))
    assertEquals(ImageCapability.UNKNOWN, imageCapability(null, ExactModel(ProviderId.OPENROUTER, "gemini-2.5-flash")))
    assertEquals(ImageCapability.UNKNOWN, imageCapability(null, ExactModel(ProviderId.GEMINI, "gemini-2.5-flash-new-alias")))
  }
  @Test fun `names and legacy booleans do not establish verified vision`() {
    assertEquals(ImageCapability.UNKNOWN, imageCapability(null))
    assertEquals(ImageCapability.UNKNOWN, imageCapability(ModelInfo("vision-pro", "Vision", ModelKind.CHAT, supportsVision = true)))
    assertEquals(ImageCapability.NO, imageCapability(ModelInfo("x", "x", ModelKind.CHAT, visionVerified = true)))
    assertEquals(ImageCapability.YES, imageCapability(ModelInfo("x", "x", ModelKind.CHAT, supportsVision = true, visionVerified = true)))
  }
  @Test fun `text remains usable without configured providers`() {
    assertTrue(imagesAccepted(null, emptyList(), false))
    assertFalse(imagesAccepted(null, emptyList(), true))
  }
  @Test fun `compatible provider elsewhere never authorizes image failover`() {
    assertFalse(imagesAccepted(ModelPolicy(text), options, true))
    assertTrue(imagesAccepted(ModelPolicy(vision), options, true))
  }
  @Test fun `only explicitly selected verified helper permits text primary`() {
    assertTrue(imagesAccepted(ModelPolicy(text, helper = vision), options, true))
    assertFalse(imagesAccepted(ModelPolicy(text, helper = vision), options.filter { it.identity != vision }, true))
    assertFalse(imagesAccepted(ModelPolicy(text, helper = text), options, true))
  }
  @Test fun `conversation policy changes leave captured requests and effort unchanged`() {
    val old = ModelPolicy(text, ThinkingLevel.LOW)
    val request = AssistantRequest(1, "image", dev.antigravity.fluidengine.ai.orchestrator.AskMode.TEXT, modelPolicy = old)
    val choices = ModelPickerState(last = old, choices = mapOf(1L to old))
    val changed = choices.copy(choices = mapOf(1L to ModelPolicy(vision, ThinkingLevel.HIGH)))
    assertEquals(text, request.modelPolicy!!.identity)
    assertEquals(ThinkingLevel.LOW, request.modelPolicy!!.effort)
    assertEquals(vision, changed.selected(1)!!.identity)
  }
  @Test fun `catalog disk round trip preserves evidence and old entries stay unknown`() {
    val catalogue = ModelCatalogue(stt = emptyList(), chat = listOf(ModelInfo("v", "v", ModelKind.CHAT, supportsVision = true, visionVerified = true)))
    val restored = ModelCatalogStore.decode(ModelCatalogStore.encode(catalogue))!!
    assertEquals(ImageCapability.YES, imageCapability(restored.chat.single()))
  }
}
