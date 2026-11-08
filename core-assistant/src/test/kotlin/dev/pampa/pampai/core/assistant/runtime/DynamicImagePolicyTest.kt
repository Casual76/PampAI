package dev.pampa.pampai.core.assistant.runtime

import dev.antigravity.fluidengine.ai.provider.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DynamicImagePolicyTest {
  @Test fun noAndUnknownPrimaryUseOnlyExplicitVerifiedHelperAndCache() = runBlocking {
    val main = ExactModel(ProviderId.GROQ, "text")
    val helper = ExactModel(ProviderId.GEMINI, "vision")
    for (capability in listOf(ImageCapability.NO, ImageCapability.UNKNOWN)) {
      val policy = ModelPolicy(main, helper = helper)
      val options = listOf(ModelOption(main, "text", capability), ModelOption(helper, "vision", ImageCapability.YES))
      val image = ContentPart.Image(byteArrayOf(1, 2), "image/jpeg")
      val cache = ImageInterpretationCache()
      var accounted = 0
      repeat(2) {
        val sent = preparePolicyParts(listOf(image), policy, options, { error("unexpected selection") }) { selected, parts ->
          cache.interpret(parts, selected) { actual, _ -> assertEquals(helper, actual); accounted++; "observed pixels" }
        }
        assertTrue(sent.single() is ContentPart.Text)
        assertTrue(sent.single().toString().contains("GEMINI/vision"))
      }
      assertEquals(1, accounted)
      var selection = false
      try {
        preparePolicyParts(listOf(image), ModelPolicy(main), options, { selection = true }) { _, _ -> error("implicit helper") }
        throw AssertionError("silently discarded pixels")
      } catch (_: IllegalStateException) { assertTrue(selection) }
    }
  }
}
