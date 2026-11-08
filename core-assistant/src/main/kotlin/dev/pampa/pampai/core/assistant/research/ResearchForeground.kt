package dev.pampa.pampai.core.assistant.research

import dev.antigravity.fluidengine.ai.provider.*
import dev.pampa.pampai.core.assistant.runtime.ModelPolicy
import dev.pampa.pampai.core.assistant.durable.EvidenceAcquisition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class ResearchTask(val intent: ResearchIntent, val reads: List<ResearchRead>, val resumeJobId: String? = null)
/** Called by AssistantEngine. No runtime, service, voice or worker dependencies. */
@Singleton class ResearchForeground @Inject constructor(private val runner: ResearchRunner,
  private val setup: ResearchSetup, private val profile: ResearchProfile, private val connections: ResearchConnections) {
  private suspend fun authorizedReads(): List<ResearchRead> {
    val plans = setup.plans.first()
    val enabledGrants = profile.grants.first().filter { it.enabled }.map { it.id }.toSet()
    val interactive = connections.authorizedReads()
    // A saved autonomous area is not the owner of interactive account/period selection.
    return (interactive + plans.flatMap { it.reads }.filter { r -> interactive.none { it.grantId == r.grantId } })
      .filter { it.grantId in enabledGrants }.distinct()
  }
  suspend fun select(question: String, explicit: ResearchTask?, deep: Boolean = false): ResearchTask? {
    val query = ResearchQuestionPlanner.sourceQuery(question) ?: return null
    if (explicit != null) {
      requireResearch(explicit.reads.isNotEmpty() && explicit.reads.all { it in authorizedReads() && ResearchQuestionPlanner.pertinent(it, query.fields) }, "SOURCE_SCOPE_CHANGED")
      return explicit.takeIf { it.intent != ResearchIntent.DIRECT }
    }
    val domains = buildSet { if (question.contains("fluidify", true) && ResearchQuestionPlanner.sourceAllowed(question, "fluidify")) add("fluidify")
      if (question.contains("classeviva", true) && ResearchQuestionPlanner.sourceAllowed(question, "cv")) add("cv") }
    val reads = authorizedReads().filter { ResearchQuestionPlanner.pertinent(it, query.fields) }.distinct()
    if (reads.isEmpty()) return null
    requireResearch(domains.all { d -> reads.any { it.selection.domain == d } } && reads.groupBy { it.selection.domain }.values.all { domainReads ->
      domainReads.map { Triple(it.selection.account, it.selection.student, it.selection.year) }.distinct().size == 1
    }, "SOURCE_SCOPE_REQUIRED_OR_AMBIGUOUS")
    return ResearchTask(if (deep || reads.map { it.selection.domain }.distinct().size > 1 || question.contains("analizz", true)) ResearchIntent.RESEARCH else ResearchIntent.LOOKUP, reads)
  }
  suspend fun answer(conversation: Long, question: String, temporary: Boolean, policy: ModelPolicy?, task: ResearchTask, onAdmitted: suspend (String) -> Unit = {}): ResearchAnswer {
    val selected = policy ?: throw ResearchUnavailable("EXPLICIT_MODEL_REQUIRED")
    val query = ResearchQuestionPlanner.sourceQuery(question) ?: throw ResearchUnavailable("PERSONAL_SOURCE_INTENT_REQUIRED")
    requireResearch(task.reads.isNotEmpty() && task.reads.all { it in authorizedReads() && ResearchQuestionPlanner.pertinent(it, query.fields) }, "SOURCE_SCOPE_CHANGED")
    fun bind(answer: ResearchAnswer): ResearchAnswer {
      val d = answer.descriptor ?: throw ResearchUnavailable("ACCEPTANCE_DESCRIPTOR_MISSING")
      requireResearch(d.objective == question && d.policyKey == digest(selected.toString()) &&
        d.conversationId == conversation.takeUnless { temporary } && task.reads.all { it in d.reads } &&
        d.reads.all { r -> task.reads.any { EvidenceAcquisition.canonicalRead(it) == EvidenceAcquisition.canonicalRead(r) } }, "ACCEPTANCE_SCOPE_MISMATCH")
      requireResearch(temporary == (d.jobId == null) && (task.resumeJobId == null || task.resumeJobId == d.jobId), "ACCEPTANCE_JOB_MISMATCH")
      return answer.copy(acceptance = ResearchAcceptance(d, profile.foundation, profile.sources, profile.clock, ::authorizedReads))
    }
    if (temporary) return bind(runner.consultTemporary(question, task.intent, selected, task.reads))
    task.resumeJobId?.let { runner.validateResume(it, conversation, question, selected, task.reads) }
    val id = task.resumeJobId ?: runner.admitForeground(ForegroundResearch(conversation, question, task.intent, selected, task.reads))
    onAdmitted(id) // Durable turn association precedes the first private page/model dispatch.
    repeat(64) {
      when (val result = if (it == 0 && task.resumeJobId != null) runner.retryForeground(id, conversation) else runner.resumeForeground(id, conversation)) {
        is TrancheResult.Progress -> Unit
        is TrancheResult.Completed -> return bind(result.answer ?: throw ResearchUnavailable("RESULT_MISSING"))
        is TrancheResult.Suspended -> throw ResearchUnavailable(result.code)
      }
    }
    throw ResearchUnavailable("TRANCHE_LIMIT")
  }
  suspend fun profileBlock(query: ProfileQuery?): String = query?.let { q -> profile.promptBlock(q, authorizedReads()) } ?: ""
  suspend fun captureTurn(question: String, query: ProfileQuery?, includeProfile: Boolean): TurnPrivacy {
    val planned = ResearchQuestionPlanner.profileQuery(question)
    val effectiveQuery = planned?.let { q -> query?.copy(fields = query.fields.filter { it in q.fields }) }
      ?.takeIf { it.fields.isNotEmpty() }
    val epoch = profile.foundation.policyEpoch()
    val reads = authorizedReads()
    val items = if (includeProfile && effectiveQuery != null) profile.retrieve(effectiveQuery, reads) else emptyList()
    val turn = TurnPrivacy(question, epoch, reads, items, profile.render(items), profile.foundation, profile.sources, profile.clock, ::authorizedReads)
    turn.check()
    return turn
  }
  fun guarded(ready: ReadyProvider, privacy: TurnPrivacy): ReadyProvider {
    val original = ready.provider
    fun guardedRequest(request: ChatRequest) = request.copy(beforeDispatch = { wire ->
      privacy.check()
      request.beforeDispatch(wire)
    })
    return ready.copy(provider = object : ChatProvider by original {
      override suspend fun complete(request: ChatRequest): ChatTurn {
        privacy.check()
        val result = original.complete(guardedRequest(request))
        privacy.check()
        return result
      }
      override fun stream(request: ChatRequest): Flow<ChatDelta> = flow {
        privacy.check()
        if (!privacy.requiresPrivacyFence) {
          original.stream(guardedRequest(request)).collect { emit(it) }
          return@flow
        }
        val buffered = mutableListOf<ChatDelta>()
        original.stream(guardedRequest(request)).collect { privacy.check(false); buffered += it }
        privacy.check()
        for (delta in buffered) { privacy.check(false); emit(delta) }
      }
    })
  }
}
