package dev.pampa.pampai.core.assistant.research

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Installed only by background entry points, checked immediately before each source wire. */
internal class BackgroundResearchGuard(val check: suspend () -> Unit) : AbstractCoroutineContextElement(Key) {
  companion object Key : CoroutineContext.Key<BackgroundResearchGuard>
}
