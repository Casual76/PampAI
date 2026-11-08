package dev.pampa.pampai.core.assistant.research

import dev.pampa.pampai.core.assistant.runtime.ModelPolicy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

enum class ResearchIntent { DIRECT, LOOKUP, RESEARCH }
@Serializable data class SourceSelection(val packageName: String, val authority: String, val domain: String,
  val account: String, val student: String? = null, val year: String? = null, val area: String)
@Serializable data class ResearchRead(val selection: SourceSelection, val tool: String, val arguments: JsonObject,
  val allowTechnicalRefresh: Boolean = false) {
  val grantId: String get() = "source:" + digest(listOf(selection.packageName, selection.authority, selection.domain,
    selection.account, selection.student, selection.year, selection.area).joinToString("|") { it?.let { s -> "${s.length}:$s" } ?: "null" })
}
data class SourceCandidate(val packageName: String, val authority: String, val domain: String,
  val account: String?, val student: String?, val supported: Boolean)
class ResearchUnavailable(val code: String) : IllegalStateException(code)
data class ForegroundResearch(val conversationId: Long, val objective: String, val intent: ResearchIntent,
  val policy: ModelPolicy, val reads: List<ResearchRead>, val maxRecords: Int = 100)
data class ResearchAnswer(val text: String, val evidenceIds: Set<String>, val policy: ModelPolicy,
  val descriptor: ResearchAcceptanceDescriptor? = null, val acceptance: ResearchAcceptance? = null)
sealed interface TrancheResult {
  data class Progress(val jobId: String) : TrancheResult
  data class Completed(val jobId: String, val answer: ResearchAnswer?) : TrancheResult
  data class Suspended(val jobId: String, val code: String, val eligibleAt: Long?) : TrancheResult
}
data class JobCursor(val eligibleAt: Long, val id: String)
data class JobBatch(val ids: List<String>, val next: JobCursor?)
internal val researchJson = Json { ignoreUnknownKeys = false; encodeDefaults = true }
internal fun digest(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
  .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
internal fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
internal fun requireResearch(value: Boolean, code: String) { if (!value) throw ResearchUnavailable(code) }
