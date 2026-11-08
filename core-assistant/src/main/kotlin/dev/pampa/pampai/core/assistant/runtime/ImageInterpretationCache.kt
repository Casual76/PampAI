package dev.pampa.pampai.core.assistant.runtime

import dev.antigravity.fluidengine.ai.provider.ContentPart
import dev.pampa.pampai.core.assistant.db.RunImageInterpretation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** In-memory only; errors are never cached and concurrent preflights cannot duplicate a call. */
internal class ImageInterpretationCache {
  private val lock = Mutex()
  private val descriptions = object : LinkedHashMap<String, String>(24, .75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 24
  }
  suspend fun interpret(parts: List<ContentPart>, policy: ModelPolicy, describe: suspend (ExactModel, ContentPart.Image) -> String): List<ContentPart> = interpret(parts, policy, {}, describe)

  suspend fun interpret(parts: List<ContentPart>, policy: ModelPolicy, onInterpretation: (RunImageInterpretation) -> Unit, describe: suspend (ExactModel, ContentPart.Image) -> String): List<ContentPart> {
    val helper = policy.helper ?: return parts
    return lock.withLock {
      parts.map { part ->
        if (part !is ContentPart.Image) part else {
          val hash = java.security.MessageDigest.getInstance("SHA-256").digest(part.bytes).joinToString("") { "%02x".format(it) }
          val key = "$hash|${part.mime}|${helper.key}|${policy.effort}|description-v1"
          val cached = descriptions[key]
          val text = cached ?: describe(helper, part).also { check(it.isNotBlank()); descriptions[key] = it }
          onInterpretation(RunImageInterpretation(helper.provider, helper.model, cached != null))
          ContentPart.Text("[Interpretazione immagine da ${helper.provider}/${helper.model}; il modello principale non riceve pixel]\n$text")
        }
      }
    }
  }
}
