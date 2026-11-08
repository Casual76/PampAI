package dev.pampa.pampai.core.assistant.research

import dev.pampa.pampai.core.assistant.durable.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable

/** Captured before synthesis, persisted with that synthesis, never reconstructed from current consent. */
@Serializable data class ResearchAcceptanceDescriptor(
  val epoch: Long, val conversationId: Long?, val objective: String, val policyKey: String,
  val jobId: String?, val reads: List<ResearchRead>, val grants: List<ResearchAcceptanceGrant>,
  val profiles: List<ResearchAcceptanceProfile>,
)
@Serializable data class ResearchAcceptanceGrant(val id: String, val version: Long, val generation: String?)
@Serializable data class ResearchAcceptanceProfile(val id: String, val fingerprint: String)
internal fun RetrievedProfile.acceptanceFingerprint() = digest(item.toString() + "\n" + evidence.toString())

/** External renewal and scope selection precede the local Room acceptance transaction. */
class ResearchAcceptance internal constructor(
  val descriptor: ResearchAcceptanceDescriptor, private val foundation: DurableFoundation,
  private val sources: ResearchSources, private val clock: ResearchClock,
  private val currentReads: suspend () -> List<ResearchRead>,
) {
  suspend fun check() {
    foundation.withResearchAcceptance(descriptor, clock.wall(), false) { }
    val current = currentReads().map(EvidenceAcquisition::canonicalRead).toSet()
    requireResearch(descriptor.reads.all { EvidenceAcquisition.canonicalRead(it) in current }, "SOURCE_SCOPE_CHANGED")
    sources.renew(descriptor.reads)
    foundation.withResearchAcceptance(descriptor, clock.wall()) { }
  }
  suspend fun <T> commit(block: suspend () -> T): T {
    currentCoroutineContext().ensureActive()
    check()
    return foundation.withResearchAcceptance(descriptor, clock.wall()) {
      currentCoroutineContext().ensureActive()
      val result = block()
      currentCoroutineContext().ensureActive()
      result
    }
  }
}
