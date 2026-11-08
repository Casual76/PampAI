package dev.pampa.pampai.core.assistant.research

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.antigravity.fluidengine.ai.bridge.*
import dev.antigravity.fluidengine.ai.keys.*
import dev.antigravity.fluidengine.ai.net.*
import dev.antigravity.fluidengine.ai.provider.*
import dev.antigravity.fluidengine.ai.tools.*
import dev.pampa.pampai.core.assistant.db.*
import dev.pampa.pampai.core.assistant.durable.*
import dev.pampa.pampai.core.assistant.runtime.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class ResearchConsumerTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val policy = ModelPolicy(ExactModel(ProviderId.GROQ, "llama-fixture"), ThinkingLevel.LOW)
  private class Clock : ResearchClock {
    var at = System.currentTimeMillis(); var elapsed = 100L
    override fun wall() = at
    override fun tick() = elapsed
    fun advance(ms: Long) { at += ms; elapsed += ms }
  }
  private class Transport(private val clock: Clock) : ResearchTransport {
    var authorization = "auth-A"; var incarnation = "process-A"; var typed = true; var wrongProof = false
    var account = "account-A"
    var calls = mutableListOf<Pair<String, JsonObject>>()
    val caps = mutableMapOf<String, Pair<RemoteHost, Long>>()
    val leases = mutableMapOf<String, JsonObject>()
    var onRead: suspend (RemoteHost, JsonObject) -> Pair<JsonObject, ToolPagination?> = { host, request ->
      buildJsonObject { put(if (host.label == "cv") "records" else "items", buildJsonArray { add(buildJsonObject { put("id", "record-1"); put("text", "synthetic 😀"); put("durationMs", JsonNull) }) })
        if(host.label=="cv"){put("account","account-A");put("student","student-A");put("year","2026-2027");put("section",request["arguments"]!!.jsonObject.string("sezione"))}
      } to null
    }
    override suspend fun hosts() = listOf(RemoteHost("synthetic.music", "synthetic.music.tools", "fluidify"))
    override suspend fun typed(host: RemoteHost) = typed
    override suspend fun call(host: RemoteHost, tool: String, args: JsonObject): RemoteResult.Done {
      calls += tool to args
      fun proof(lease: String): JsonObject {
        val cap = args.string("capability")!!
        return buildJsonObject {
          put("contract", "aria.source-proof"); put("version", 1); put("domain", host.label); put("authority", host.authority)
          put("incarnation", incarnation); put("authorization", authorization); put("capability", cap); put("lease", lease)
          put("account", if (wrongProof) "WRONG" else account); put("student", if (host.label == "cv") JsonPrimitive("student-A") else JsonNull)
          put("scope", leases[lease]!!); put("checkedAtMillis", clock.wall()); put("expiresAtMillis", caps[cap]!!.second); put("valid", true)
        }
      }
      var pagination: ToolPagination? = null
      val data = when(tool) {
        "source_capabilities" -> {
          val cap = UUID.randomUUID().toString(); caps[cap] = host to (clock.wall() + 60000)
          buildJsonObject {
            put("contract", "aria.source-proof"); put("version", 1); put("domain", host.label); put("authority", host.authority)
            put("incarnation", incarnation); put("authorization", authorization); put("capability", cap); put("ttlMillis", 60000)
            put("account", account); put("student", if (host.label == "cv") JsonPrimitive("student-A") else JsonNull)
            put("readTools", JsonArray(listOf("history_records", "history_aggregate", "registro_dati").map(::JsonPrimitive)))
          }
        }
        "source_lease" -> {
          val id = UUID.randomUUID().toString(); leases[id] = ResearchReadPolicy.scope(args.string("tool")!!, args["arguments"]!!.jsonObject); proof(id)
        }
        "source_validate" -> proof(args.string("lease")!!)
        "source_read" -> {
          val (page, paging) = onRead(host, args); pagination = paging
          JsonObject(page + ("sourceProof" to proof(args.string("lease")!!)))
        }
        else -> error("unexpected write/control: $tool")
      }
      return RemoteResult.Done("synthetic", false, emptyList(), ToolResultDetails(ToolOutcome.COMPLETED, data = data,
        pagination = pagination, coverage = ToolCoverage(false), freshness = ToolFreshness(clock.wall())))
    }
  }
  private inner class Fixture(name: String? = null) : AutoCloseable {
    val db = if (name == null) Room.inMemoryDatabaseBuilder(context, PampaiDatabase::class.java).build()
      else Room.databaseBuilder(context, PampaiDatabase::class.java, name).build()
    val clock = Clock(); val transport = Transport(clock); val cache = VerifiedSourceReceiptCache(clock)
    val foundation = DurableFoundation(db, cache); val budget = RuntimeBudgetCoordinator(BudgetLedger(db, proofs = cache), db)
    val setup = ResearchSetup(context, foundation, budget)
    val connections = ResearchConnections(context, foundation)
    val sources = ResearchSources(transport, foundation, cache, clock, connections)
    var generations = 0; var generatedPolicy: ModelPolicy? = null
    var generatedObjective = ""
    var beforeModel: suspend () -> Unit = {}
    var profileOutput: (List<ResearchEvidence>) -> JsonArray = { buildJsonArray {} }
    var idsOutput: ((List<ResearchEvidence>) -> JsonArray)? = null
    val model = object : ResearchModel {
      override suspend fun generate(policy: ModelPolicy, objective: String, evidence: List<ResearchEvidence>, guard: suspend () -> Unit): String {
        guard(); beforeModel(); generations++; generatedPolicy = policy; generatedObjective = objective
        return buildJsonObject { put("answer", "synthetic partial coverage [${evidence.first().id}]"); put("evidenceIds", idsOutput?.invoke(evidence) ?: JsonArray(evidence.map { JsonPrimitive(it.id) })); put("profile", profileOutput(evidence)) }.toString()
      }
    }
    val profile = ResearchProfile(foundation, sources, clock, setup)
    val runner = ResearchRunner(foundation, sources, budget, model, setup, clock, profile)
    val area = "music-${UUID.randomUUID()}"
    fun music(tool: String = "history_records") = ResearchRead(SourceSelection("synthetic.music", "synthetic.music.tools", "fluidify", "account-A", area = area), tool,
      buildJsonObject { put("account", "account-A"); put("from", "0"); put("until", "1000") })
    fun school() = ResearchRead(SourceSelection("synthetic.school", "synthetic.school.tools", "cv", "account-A", "student-A", "2026-2027", "agenda"), "registro_dati",
      buildJsonObject { put("account", "account-A"); put("studente", "student-A"); put("anno", "2026-2027"); put("sezione", "agenda") })
    suspend fun conversation(temporary: Boolean = false) = db.conversations().insert(ConversationEntity(title="synthetic", createdAtMillis=0, updatedAtMillis=0, temporary=temporary))
    suspend fun admitted(reads: List<ResearchRead>): Pair<Long, String> {
      reads.forEach { sources.connect(it) }
      val cid = conversation()
      return cid to runner.admitForeground(ForegroundResearch(cid, "synthetic two sources", ResearchIntent.RESEARCH, policy, reads))
    }
    suspend fun finish(id: String): ResearchAnswer {
      repeat(12) { when(val result = runner.runTranche(id)) {
        is TrancheResult.Completed -> return result.answer!!
        is TrancheResult.Suspended -> error(result.code)
        is TrancheResult.Progress -> Unit
      } }; error("not completed")
    }
    override fun close() { db.close() }
  }
  @Test fun connectedTwoSourceForegroundAndFollowupUseFixedModelWithoutProfiling() = runBlocking {
    Fixture().use { f ->
      val reads = listOf(f.music(), f.school()); val (cid, id) = f.admitted(reads)
      val first = f.finish(id)
      assertEquals(2, first.evidenceIds.size); assertEquals(policy, f.generatedPolicy); assertTrue(f.profile.management.first().isEmpty())
      val follow = f.runner.admitForeground(ForegroundResearch(cid, "follow up", ResearchIntent.LOOKUP, policy, reads))
      assertEquals(first.evidenceIds, f.finish(follow).evidenceIds)
      assertEquals(2, f.db.durable().jobEvidence(id).size)
      val calls = f.transport.calls.filter { it.first == "source_read" }
      assertTrue(calls.all { it.second["arguments"]!!.jsonObject["limit"] == null && it.second["arguments"]!!.jsonObject["limite"] == null })
    }
  }
  @Test fun sameAuthorizationRenewalPreservesVersionEpochAndCheckpoint() = runBlocking {
    Fixture().use { f ->
      val r = f.music(); val (_, id) = f.admitted(listOf(r)); f.runner.runTranche(id)
      val before = f.foundation.grant(r.grantId)!!; val epoch = f.db.durable().state()?.epoch
      f.clock.advance(61000); f.sources.renew(listOf(r))
      assertEquals(before.version, f.foundation.grant(r.grantId)!!.version)
      assertEquals(epoch, f.db.durable().state()?.epoch); assertEquals("COMPLETE", f.foundation.steps(id).first().outcome)
      assertTrue(f.finish(id).text.contains("partial"))
    }
  }
  @Test fun profileRenewalAfterExpiryNeverSubstitutesAnExactAcquisitionRead() = runBlocking {
    Fixture().use { f ->
      val form = f.school().let { it.copy(selection = it.selection.copy(area = "grades"),
        arguments = JsonObject(it.arguments + mapOf("sezione" to JsonPrimitive("grades"), "aggiorna" to JsonPrimitive(false)))) }
      f.sources.connect(form)
      f.profile.savePlan(AreaResearchPlan(AreaConfiguration("grades", enabled = true, setupComplete = true,
        requestCap = 10, tokenCap = 100000, windowStart = 0, windowEnd = Long.MAX_VALUE), policy, listOf(form)))
      f.profileOutput = { values -> val e = values.first(); buildJsonArray { add(buildJsonObject {
        put("field", e.field); put("value", e.value); put("valueType", e.valueType); put("kind", "OBSERVATION"); put("periodKey", e.periodKey)
        put("evidenceIds", buildJsonArray { add(e.id) })
      }) } }
      val cid = f.conversation()
      val first = f.runner.admitForeground(ForegroundResearch(cid, "come vanno i miei voti?", ResearchIntent.LOOKUP, policy, listOf(form)))
      f.finish(first)
      assertEquals(1, f.profile.retrieve(ProfileQuery(listOf("cv.grades")), listOf(form)).size)
      for (refresh in listOf(false, true)) {
        val exact = form.copy(allowTechnicalRefresh = refresh, arguments = JsonObject(form.arguments + ("aggiorna" to JsonPrimitive(refresh))))
        f.sources.connect(exact)
        f.clock.advance(61000)
        // Retrieval renews a canonical read without aggiorna before the form read is reused.
        assertTrue(f.profile.retrieve(ProfileQuery(listOf("cv.grades")), listOf(exact)).isNotEmpty())
        assertEquals(exact, f.sources.acquire(exact).read)
        val next = f.runner.admitForeground(ForegroundResearch(cid, "come vanno i miei voti?", ResearchIntent.LOOKUP, policy, listOf(exact)))
        assertTrue(f.finish(next).text.isNotBlank())
        val acquisition = researchJson.parseToJsonElement(f.foundation.steps(next).first().cursor!!).jsonObject.getValue("acquisition").jsonObject
        assertEquals(exact, researchJson.decodeFromJsonElement<ResearchRead>(acquisition.getValue("read")))
      }
    }
  }
  @Test fun changedAuthorizationRevokesSameStableGrantAndCancelsJob() = runBlocking {
    Fixture().use { f ->
      val r = f.music(); val (_, id) = f.admitted(listOf(r)); f.transport.authorization = "new-auth"; f.clock.advance(61000)
      try { f.sources.renew(listOf(r)); fail("changed auth accepted") } catch(e: ResearchUnavailable) { assertEquals("AUTHORIZATION_CHANGED", e.code) }
      assertFalse(f.foundation.grant(r.grantId)!!.enabled); assertEquals("CANCELLED", f.foundation.job(id)!!.state)
      try { f.sources.connect(r); fail("implicit reconnect") } catch(e: ResearchUnavailable) { assertEquals("LOCAL_REVOKED", e.code) }
      assertEquals(r.grantId, f.sources.connect(r, reconnect=true).id)
    }
  }
  @Test fun unsupportedTypedOrMisclassifiedWriteNeverReachesRead() = runBlocking {
    Fixture().use { f ->
      f.transport.typed = false
      try { f.sources.connect(f.music()); fail("old host") } catch(_: ResearchUnavailable) { }
      assertTrue(f.transport.calls.isEmpty()); f.transport.typed = true
      try { f.sources.connect(f.music("playlist_append")); fail("write reached source") } catch(_: ResearchUnavailable) { }
      assertTrue(f.transport.calls.isEmpty())
    }
  }
  @Test fun wrongAccountReceiptCannotPopulateValidator() = runBlocking {
    Fixture().use { f -> f.transport.wrongProof = true
      try { f.sources.connect(f.music()); fail("forged proof") } catch(e: ResearchUnavailable) { assertEquals("INVALID_SOURCE_PROOF", e.code) }
      assertTrue(f.foundation.grants().isEmpty())
    }
  }
  @Test fun cvWrongYearStudentAndUnknownArgumentFailBeforeTransport() = runBlocking {
    Fixture().use { f ->
      for (r in listOf(f.school().let { it.copy(selection=it.selection.copy(year="2026-2028")) }, f.school().let { it.copy(arguments=JsonObject(it.arguments + ("studente" to JsonPrimitive("other")))) },
        f.music().let { it.copy(arguments=JsonObject(it.arguments + ("play" to JsonPrimitive(true)))) })) {
        try { f.sources.connect(r); fail("scope accepted") } catch(_: ResearchUnavailable) { }
      }
      assertTrue(f.transport.calls.isEmpty())
    }
  }
  @Test fun forgetDuringSynthesisRejectsLateCommitAndDeletesEvidence() = runBlocking {
    Fixture().use { f ->
      val r = f.music(); val (_, id) = f.admitted(listOf(r)); f.runner.runTranche(id)
      f.beforeModel = { f.profile.forget("fluidify.history_records") }
      assertTrue(f.runner.runTranche(id) is TrancheResult.Suspended)
      assertTrue(f.db.durable().jobEvidence(id).isEmpty())
      assertTrue(f.profile.exclusions.first().isNotEmpty())
    }
  }
  @Test fun temporaryConsultationNeverCreatesResearchOrProfileRows() = runBlocking {
    Fixture().use { f -> val r=f.music(); f.sources.connect(r)
      val answer = f.runner.consultTemporary("synthetic", ResearchIntent.LOOKUP, policy, listOf(r))
      assertTrue(answer.text.isNotBlank()); assertTrue(f.foundation.jobs.first().isEmpty()); assertTrue(f.foundation.profile.first().isEmpty())
      assertTrue(f.db.durable().jobEvidence("temporary").isEmpty())
      try { f.runner.admitForeground(ForegroundResearch(f.conversation(true), "temp", ResearchIntent.LOOKUP, policy, listOf(r))); fail("temporary durable") } catch(_: IllegalArgumentException) { }
    }
  }
  @Test fun backgroundRequiresExactAreaPlanConsentAndNeverAdmitsCrossArea() = runBlocking {
    Fixture().use { f -> val r=f.music(); f.sources.connect(r)
      try { f.runner.admitBackground(r.selection.area, "synthetic"); fail("setup bypass") } catch(_: ResearchUnavailable) { }
      f.profile.savePlan(AreaResearchPlan(AreaConfiguration(r.selection.area, enabled=true, setupComplete=true,
        requestCap=2, tokenCap=10000, windowStart=0, windowEnd=Long.MAX_VALUE), policy, listOf(r)))
      val id=f.runner.admitBackground(r.selection.area, "synthetic")
      assertEquals("BACKGROUND", f.foundation.job(id)!!.execution)
      assertTrue(f.runner.eligibleBackgroundJobs(f.clock.wall()).ids.contains(id))
      f.profile.disable(r.selection.area)
      assertEquals("CANCELLED", f.foundation.job(id)!!.state)
      val school=f.school(); f.sources.connect(school)
      try { f.foundation.createJob("bad", null, r.selection.area, "RESEARCH", "BACKGROUND", "bad", policy,
        listOf(r.grantId, school.grantId), listOf(ResearchStep("bad",0,r.grantId,"x")),100,f.clock.wall()); fail("cross area") } catch(_: IllegalArgumentException) { }
    }
  }
  @Test fun savedPlanCapturesPolicyScopesAndChangesCancelActiveWork() = runBlocking {
    Fixture().use { f -> val r=f.music(); val (_,id)=f.admitted(listOf(r))
      val config=AreaConfiguration(r.selection.area)
      f.profile.savePlan(AreaResearchPlan(config, policy, listOf(r), 30))
      val loaded=f.profile.plan(r.selection.area)!!
      assertEquals(policy,loaded.policy); assertEquals(listOf(r),loaded.reads); assertEquals(30,loaded.maxRecords)
      assertFalse(loaded.configuration.enabled); assertEquals("CANCELLED",f.foundation.job(id)!!.state)
    }
  }
  @Test fun unicodeFragmentsWithholdCursorThenHashAndDeduplicate() = runBlocking {
    Fixture().use { f -> val r=f.music(); f.sources.connect(r); val c=f.sources.acquire(r)
      val text=buildJsonObject { put("items", buildJsonArray { add(buildJsonObject { put("id","😀"); put("text","è\\\"😀") }) }) }.toString()
      val split=text.indexOf("😀"); val hash=digest(text)
      fun chunk(start:Int,end:Int)=RemoteResult.Done("fragment",false,emptyList(),ToolResultDetails(ToolOutcome.PARTIAL,data=buildJsonObject {
        put("snapshot","s"); put("queryHash","q"); put("hash",hash); put("encoding","concatenate_json_text_utf16")
        put("offset",start);put("end",end);put("totalChars",text.length);put("fragment",text.substring(start,end));put("truncated",end<text.length)
        if(end<text.length){put("nextOffset",end);put("nextTool","result_fragment")} else put("nextPageCursor","next")
      },pagination=if(end==text.length) ToolPagination("next",true) else ToolPagination(hasMore=true)))
      val first=SourcePages.consume(ResearchStep("j",0,r.grantId,"q"),c,chunk(0,split))
      assertTrue(first.evidence.isEmpty()); assertFalse(first.step.cursor!!.contains("nextPageCursor"))
      val last=SourcePages.consume(first.step,c,chunk(split,text.length))
      assertEquals(1,last.evidence.size);assertTrue(last.evidence.single().value.contains("😀"));assertEquals("PARTIAL",last.step.outcome)
      val wrong=chunk(split,text.length).let { it.copy(result=it.result!!.copy(data=JsonObject(it.result!!.data!! + ("hash" to JsonPrimitive("wrong"))))) }
      try { SourcePages.consume(first.step,c,wrong);fail("bad hash") } catch(_:ResearchUnavailable) { }
    }
  }
  @Test fun partialFragmentCannotPublishPrematurePageCursor() = runBlocking {
    Fixture().use { f -> val r=f.music();f.sources.connect(r);val c=f.sources.acquire(r)
      val result=RemoteResult.Done("x",false,emptyList(),ToolResultDetails(ToolOutcome.PARTIAL,data=buildJsonObject {
        put("encoding","concatenate_json_text_utf16");put("offset",0);put("end",1);put("totalChars",2);put("fragment","{");put("nextOffset",1);put("nextTool","result_fragment");put("nextPageCursor","bad")
      }))
      try { SourcePages.consume(ResearchStep("j",0,r.grantId,"q"),c,result);fail("early cursor") }catch(e:ResearchUnavailable){assertEquals("PREMATURE_CURSOR",e.code)}
    }
  }
  @Test fun fragmentLeaseExpiryRestartsBoundedReadInsteadOfReusingOldSnapshot() = runBlocking {
    Fixture().use { f ->val r=f.music();f.sources.connect(r);val first=f.sources.acquire(r)
      val step=ResearchStep("j",0,r.grantId,"q",cursor=buildJsonObject {put("lease",first.lease);put("fragment",buildJsonObject{put("snapshot","old");put("hash","h");put("queryHash","q")})}.toString(),fragmentOffset=5,fragment="first")
      f.clock.advance(61000);f.sources.renew(listOf(r));val next=f.sources.acquire(r)
      assertNotEquals(first.lease,next.lease);assertEquals("history_records",SourcePages.continuation(step,next).first)
    }
  }
  @Test fun boundedModelCallTraversesRealProviderLifecycleAndBudget() = runBlocking {
    Fixture().use { f ->TinyHttpServer().use { server ->
      var calls=0
      server.handle("/chat/completions") { request,response ->
        calls++;val payload=researchJson.parseToJsonElement(request.body.toString(Charsets.UTF_8)).jsonObject
        val content=payload["messages"]!!.jsonArray.last().jsonObject.string("content")!!
        val ids=Regex("\\[([a-f0-9]{64})]").findAll(content).map{it.groupValues[1]}.toList()
        val answer=buildJsonObject{put("answer","bounded [${ids.first()}]");put("evidenceIds",JsonArray(ids.map(::JsonPrimitive)));put("profile",buildJsonArray{})}.toString()
        response.reply(200,buildJsonObject {put("choices",buildJsonArray{add(buildJsonObject{put("message",buildJsonObject{put("role","assistant");put("content",answer)});put("finish_reason","stop")})});put("usage",buildJsonObject{put("prompt_tokens",3);put("completion_tokens",2);put("total_tokens",5)})}.toString())
      }
      val provider=object:OpenAiCompatProvider(AiHttp("fixture"),server.base,"fixture") {
        override val id=ProviderId.GROQ
        override fun JsonObjectBuilder.providerFields(request:ChatRequest,stream:Boolean,dropped:Set<String>) {}
        override suspend fun listModels()=ModelCatalogue(emptyList(),emptyList())
        override suspend fun transcribe(audio:File,mime:String,options:TranscribeOptions):Transcript=error("unused")
      }
      val factory=mockk<ProviderFactory>();val settings=mockk<AiSettingsStore>()
      coEvery { settings.current() } returns AiSettings()
      coEvery { factory.build(any(),any()) } returns ReadyProvider(provider,policy.identity.model,"unused",null,catalogue=ModelCatalogue(listOf(ModelInfo(policy.identity.model,"fixture",ModelKind.CHAT,contextWindow=4000,maxOutputTokens=2000)),emptyList()))
      val realModel=ProviderResearchModel(factory,settings,ResearchAttemptBounds(),f.budget)
      val runner=ResearchRunner(f.foundation,f.sources,f.budget,realModel,f.setup,f.clock,f.profile)
      val (_,id)=f.admitted(listOf(f.music()))
      f.budget.configure(BudgetCap("global",false,0,Long.MAX_VALUE,2,10000,null))
      repeat(4){runner.runTranche(id)}
      assertEquals(1,calls);val attempt=f.db.durable().attempts(0,Long.MAX_VALUE).single()
      assertEquals(5600,attempt.reservedTokens.toInt());assertEquals(5,attempt.chargedTokens.toInt());assertEquals(id,attempt.jobId)
      server.handle("/chat/completions") { _,response -> calls++;response.reply(429,"""{"error":{"message":"synthetic rate limit"}}""",mapOf("Retry-After" to "60")) }
      val cid=f.foundation.job(id)!!.conversationId!!
      val next=runner.admitForeground(ForegroundResearch(cid,"rate limited followup",ResearchIntent.RESEARCH,policy,listOf(f.music())))
      assertTrue(runner.runTranche(next) is TrancheResult.Progress)
      val yielded=runner.runTranche(next) as TrancheResult.Suspended
      assertEquals("COOLDOWN",yielded.code);assertNotNull(yielded.eligibleAt);assertEquals(2,calls)
      assertEquals(yielded.eligibleAt,f.foundation.job(next)!!.eligibleAt)
      assertTrue(runner.runTranche(next) is TrancheResult.Suspended);assertEquals(2,calls)
    } }
  }
  @Test fun explicitProfileConsentAndTargetedRetrievalRenewActualScopes() = runBlocking {
    Fixture().use { f ->
      val r=f.music();f.sources.connect(r)
      f.profile.savePlan(AreaResearchPlan(AreaConfiguration(r.selection.area,enabled=true,setupComplete=true,requestCap=2,
        tokenCap=10000,windowStart=0,windowEnd=Long.MAX_VALUE),policy,listOf(r)))
      f.profileOutput={ list -> val e=list.first();buildJsonArray {add(buildJsonObject {
        put("field",e.field);put("value",e.value);put("valueType",e.valueType);put("kind","OBSERVATION")
        put("periodKey",e.periodKey);put("evidenceIds",buildJsonArray {add(e.id)})
      })} }
      val (_,id)=f.admitted(listOf(r));f.finish(id)
      assertEquals(1,f.profile.management.first().size)
      f.clock.advance(61000);val calls=f.transport.calls.size
      val retrieved=f.profile.retrieve(ProfileQuery(listOf("fluidify.history_records")))
      assertEquals(1,retrieved.size);assertTrue(f.transport.calls.size>calls)
      assertTrue(f.profile.retrieve(ProfileQuery(listOf("cv.grades"))).isEmpty())
      f.profile.correct("user","fluidify.history_records","corrected")
      assertEquals("corrected",f.profile.retrieve(ProfileQuery(listOf("fluidify.history_records"))).single().item.value)
      f.profile.forget("fluidify.history_records")
      assertTrue(f.profile.retrieve(ProfileQuery(listOf("fluidify.history_records"))).isEmpty())
    }
  }
  @Test fun modelCannotInventEvidenceAndConsentCannotBeGrantedMidRun() = runBlocking {
    Fixture().use { f ->val r=f.music();val (_,id)=f.admitted(listOf(r))
      f.runner.runTranche(id);f.idsOutput={buildJsonArray {add("invented")}}
      val failed=f.runner.runTranche(id) as TrancheResult.Suspended
      assertEquals("INVALID_EVIDENCE_REFERENCE",failed.code);assertTrue(f.profile.management.first().isEmpty())
      assertEquals(Long.MAX_VALUE,f.foundation.job(id)!!.eligibleAt)
    }
  }
  @Test fun cvDetailPagesReuseFullRecordsAndAggregateExactlyOnce() = runBlocking {
    Fixture().use { f ->val r=f.school();f.sources.connect(r);val c=f.sources.acquire(r)
      val page=buildJsonObject {
        put("account","account-A");put("student","student-A");put("year","2026-2027");put("section","agenda")
        put("records",buildJsonArray {add(buildJsonObject {put("id","record");put("text","synthetic")})})
        put("aggregate",buildJsonObject{put("count",2)})
      }
      val hash="a".repeat(64)
      val details=RemoteResult.Done("Pagina suddivisa: ripeti stessi filtri/cursore/limite, aggiorna=false, dettaglio_offset=1500, dettaglio_hash=$hash.",false,emptyList(),
        ToolResultDetails(ToolOutcome.PARTIAL,data=page,pagination=ToolPagination(hasMore=true)))
      val first=SourcePages.consume(ResearchStep("j",0,r.grantId,"q"),c,details)
      assertEquals(2,first.evidence.size);assertEquals("PARTIAL",first.step.outcome)
      assertEquals("UNKNOWN",first.evidence.first().timeZone)
      val next=SourcePages.continuation(first.step,c)
      assertEquals(JsonPrimitive(1500),next.second["dettaglio_offset"])
      val final=SourcePages.consume(first.step,c,details.copy(text="Fine",result=details.result!!.copy(pagination=ToolPagination(hasMore=false))))
      assertEquals(first.evidence.map {it.id},final.evidence.map {it.id})
      assertEquals(2,researchJson.parseToJsonElement(final.step.cursor!!).jsonObject["evidence"]!!.jsonArray.size)
    }
  }
  @Test fun failedBackgroundLimitsNeverPerformSourceReadAndCapsReopenFromFlow() = runBlocking {
    Fixture().use { f ->val r=f.music();f.sources.connect(r)
      f.profile.savePlan(AreaResearchPlan(AreaConfiguration(r.selection.area,enabled=true,setupComplete=true,requestCap=2,tokenCap=10000,
        windowStart=0,windowEnd=Long.MAX_VALUE),policy,listOf(r)))
      val id=f.runner.admitBackground(r.selection.area,"synthetic")
      assertEquals("BACKGROUND_LIMITS_REQUIRED",(f.runner.runBackgroundTranche(id) as TrancheResult.Suspended).code)
      assertFalse(f.transport.calls.any{it.first=="source_read"})
      f.setup.configureCap(BudgetCap("synthetic",false,0,Long.MAX_VALUE,2,10000,null))
      assertTrue(f.setup.caps.first().any{it.id=="synthetic"})
    }
  }
  @Test fun sourceRevokedWhileReadReturnsCannotCommitEvidence() = runBlocking {
    Fixture().use { f ->val r=f.music();val (_,id)=f.admitted(listOf(r))
      f.transport.onRead={_,_->
        f.foundation.revoke(r.grantId)
        buildJsonObject {put("items",buildJsonArray{add(buildJsonObject{put("id","late")})})} to null
      }
      assertTrue(f.runner.runTranche(id) is TrancheResult.Suspended)
      assertTrue(f.db.durable().jobEvidence(id).isEmpty());assertEquals("CANCELLED",f.foundation.job(id)!!.state)
    }
  }
  @Test fun reopenResumesCommittedPage() = runBlocking {
    val name="r-${System.nanoTime()}.db"
    val first=Fixture(name)
    val r=first.music();val (_,id)=first.admitted(listOf(r))
    assertTrue(first.runner.runTranche(id) is TrancheResult.Progress)
    val version=first.foundation.grant(r.grantId)!!.version
    first.close()
    try {Fixture(name).use { next ->
      val result=next.finish(id)
      assertTrue(result.text.isNotBlank());assertFalse(next.transport.calls.any{it.first=="source_read"})
      assertEquals(version,next.foundation.grant(r.grantId)!!.version)
    }}finally{context.deleteDatabase(name)}
  }
  @Test fun disablingConsentDuringSynthesisDiscardsAutomaticProfileWrite() = runBlocking {
    Fixture().use { f ->val r=f.music();f.sources.connect(r)
      f.profile.savePlan(AreaResearchPlan(AreaConfiguration(r.selection.area,enabled=true,setupComplete=true,requestCap=2,
        tokenCap=10000,windowStart=0,windowEnd=Long.MAX_VALUE),policy,listOf(r)))
      val (_,id)=f.admitted(listOf(r));f.runner.runTranche(id)
      f.profileOutput={list->val e=list.first();buildJsonArray{add(buildJsonObject {
        put("field",e.field);put("value",e.value);put("valueType",e.valueType);put("kind","OBSERVATION");put("periodKey",e.periodKey)
        put("evidenceIds",buildJsonArray{add(e.id)})
      })}}
      f.beforeModel={f.profile.disable(r.selection.area)}
      val rejected = f.runner.runTranche(id)
      assertTrue(rejected is TrancheResult.Suspended)
      assertEquals("RESULT_FENCED", (rejected as TrancheResult.Suspended).code)
      assertTrue(f.profile.management.first().isEmpty())
    }
  }
  @Test fun sourceHostAllowlistRejectsUnknownPackageAndAuthorityAlias() {
    assertEquals("fluidify",ResearchHostPolicy.domain(RemoteHost("dev.pampa.fluidify","dev.pampa.fluidify.ai.tools","any label")))
    assertEquals("cv",ResearchHostPolicy.domain(RemoteHost("dev.antigravity.classevivaexpressive","dev.antigravity.classevivaexpressive.ai.tools","any label")))
    assertNull(ResearchHostPolicy.domain(RemoteHost("unknown.signed.app","unknown.signed.app.ai.tools","cv")))
    assertNull(ResearchHostPolicy.domain(RemoteHost("dev.pampa.fluidify","other.authority","fluidify")))
  }
  @Test fun expiredPageCursorRestartsTwiceAndKeepsCanonicalEvidence() = runBlocking {
    Fixture().use { f ->val r=f.music();val (_,id)=f.admitted(listOf(r))
      var reads=0
      f.transport.onRead={_,args->
        reads++
        if(args["arguments"]!!.jsonObject.string("cursor")!=null) throw ResearchUnavailable("expired_cursor")
        buildJsonObject {put("items",buildJsonArray{add(buildJsonObject{put("id","record")})})} to ToolPagination("expires",true)
      }
      repeat(5){assertTrue(f.runner.runTranche(id) is TrancheResult.Progress)}
      val stopped=f.runner.runTranche(id) as TrancheResult.Suspended
      assertEquals("expired_cursor",stopped.code);assertEquals(6,reads)
      assertEquals(1,f.db.durable().jobEvidence(id).size)
    }
  }
  @Test fun naturalGradeQuestionUsesOnlySavedGradeScopeWithoutBrandOrSetup() = runBlocking {
    Fixture().use { f ->
      val original=f.school()
      val grade=original.copy(selection=original.selection.copy(area="grades"),arguments=JsonObject(original.arguments+("sezione" to JsonPrimitive("grades"))))
      f.sources.connect(grade)
      val foreground=ResearchForeground(f.runner,f.setup,f.profile,f.connections)
      val question="come vanno i miei voti?"
      val task=foreground.select(question,null)!!
      assertEquals(listOf(grade),task.reads)
      val answer=foreground.answer(f.conversation(),question,false,policy,task)
      assertTrue(answer.text.isNotBlank());assertTrue(f.transport.calls.any{it.first=="source_read"})
      assertEquals(policy,f.generatedPolicy);assertTrue(f.profile.management.first().isEmpty())
    }
  }
  @Test fun normalMusicQuestionUsesPertinentStoredKnowledgeWhileUnrelatedQuestionUsesNone() = runBlocking {
    Fixture().use { f ->val r=f.music();f.sources.connect(r)
      f.profile.savePlan(AreaResearchPlan(AreaConfiguration(r.selection.area,enabled=true,setupComplete=true,requestCap=2,
        tokenCap=10000,windowStart=0,windowEnd=Long.MAX_VALUE),policy,listOf(r)))
      f.profileOutput={list->val e=list.first();buildJsonArray{add(buildJsonObject{
        put("field",e.field);put("value",e.value);put("valueType",e.valueType);put("kind","OBSERVATION");put("periodKey",e.periodKey)
        put("evidenceIds",buildJsonArray{add(e.id)})
      })}}
      val (cid,id)=f.admitted(listOf(r));f.finish(id)
      val foreground=ResearchForeground(f.runner,f.setup,f.profile,f.connections)
      val question="quale musica preferisco?"
      val query=ResearchQuestionPlanner.profileQuery(question)!!
      assertTrue(query.fields.all{it.startsWith("fluidify.")})
      assertTrue(foreground.profileBlock(query).contains("synthetic"))
      val task=foreground.select(question,null)!!
      foreground.answer(cid,question,false,policy,task)
      assertTrue(f.generatedObjective.contains("Conoscenza pertinente gia conservata"))
      assertTrue(f.generatedObjective.contains("OBSERVATION"))
      val calls=f.transport.calls.size
      assertNull(foreground.select("come preparo una torta?",null))
      assertNull(ResearchQuestionPlanner.profileQuery("come preparo una torta?"))
      assertEquals("",foreground.profileBlock(null));assertEquals(calls,f.transport.calls.size)
      assertNull(foreground.select("apri Fluidify",null))
    }
  }
  @Test fun actualWorkerResumesFiniteRunnerAndCompletesOnceWithoutForegroundEntry() = runBlocking {
    Fixture().use { f ->
      val r = f.music(); f.sources.connect(r)
      f.profile.savePlan(AreaResearchPlan(AreaConfiguration(r.selection.area, enabled = true, setupComplete = true,
        requestCap = 10, tokenCap = 100000, windowStart = 0, windowEnd = Long.MAX_VALUE), policy, listOf(r)))
      f.setup.configureCap(BudgetCap("worker-global", false, 0, Long.MAX_VALUE, 20, 100000, null))
      f.setup.configureCap(BudgetCap("worker-background", true, 0, Long.MAX_VALUE, 20, 100000, null))
      val store = ResearchScheduleStore(context)
      val queue = object : ResearchWorkQueue {
        override suspend fun periodic(policy: SchedulePolicy) { }
        override suspend fun enqueue(policy: SchedulePolicy, row: AreaScheduleStatus) = "synthetic-${row.slot}"
        override suspend fun cancel(area: String) { }
      }
      val scheduler = ResearchScheduler(f.setup, f.foundation, f.runner, f.budget, store, queue, f.clock)
      scheduler.reconcile()
      val factory = ResearchWorkerFactory(javax.inject.Provider { scheduler })
      repeat(4) {
        val generation = store.get(r.selection.area)!!.generation
        val worker = androidx.work.testing.TestListenableWorkerBuilder<ResearchWorker>(context).setWorkerFactory(factory)
          .setInputData(androidx.work.workDataOf("area" to r.selection.area, "generation" to generation)).build()
        assertEquals(androidx.work.ListenableWorker.Result.success(), worker.doWork())
      }
      assertEquals(1, f.generations)
      assertEquals(1, f.transport.calls.count { it.first == "source_read" })
      assertNotNull(store.get(r.selection.area)!!.lastSuccessAt)
      assertTrue(store.get(r.selection.area)!!.nextEligibleAt!! > f.clock.wall())
      val rows = f.foundation.jobs.first()
      assertEquals(1, rows.size); assertEquals("BACKGROUND", rows.single().execution); assertEquals("DONE", rows.single().state)
    }
  }
  @Test fun generalDomainWordsNeverCrossForegroundOrProfileBoundary() = runBlocking {
    Fixture().use { f ->
      val original = f.school()
      val grades = original.copy(selection = original.selection.copy(area = "grades"),
        arguments = JsonObject(original.arguments + ("sezione" to JsonPrimitive("grades"))))
      f.sources.connect(grades); f.sources.connect(f.music())
      val fg = ResearchForeground(f.runner, f.setup, f.profile, f.connections)
      val baseline = f.transport.calls.size
      listOf("Che cos’è un voto di fiducia?", "Spiegami la storia della musica", "Chi sono gli artisti del Rinascimento?",
        "Cos’è l’ascolto nella musica?", "Cos’è Fluidify e come gestisce la musica?", "Che cosa sono i voti in ClasseViva?",
        "Analizza in profondità la storia della musica", "Cosa devo studiare per diventare ingegnere?",
        "Quali lezioni devo fare per imparare la chitarra?", "Che cosa devo fare per capire i voti nei talent show?").forEach { q ->
        assertNull(q, ResearchQuestionPlanner.profileQuery(q)); assertNull(q, fg.select(q, null, deep = true))
      }
      assertEquals(baseline, f.transport.calls.size)
      assertTrue(f.db.durable().jobEvidence("unused").isEmpty())
      assertEquals(ResearchIntent.RESEARCH, fg.select("come vanno i miei voti?", null, deep = true)!!.intent)
      assertNotNull(fg.select("quali brani ascolto?", null))
      assertNotNull(fg.select("controlla i voti in ClasseViva", null))
      assertNull(fg.select("come vado a scuola?", null))
      assertNotNull(fg.select("come va il mio rendimento scolastico?", null))
      assertNotNull(fg.select("che voto ho preso?", null))
      assertTrue("cv.agenda" in ResearchQuestionPlanner.profileQuery("quali compiti ho per domani?")!!.fields)
      assertNull(fg.select("cosa devo fare nella vita?", null))
      assertEquals(ResearchIntent.RESEARCH, fg.select("confronta i miei voti e la musica che ascolto", null)!!.intent)
    }
  }

  @Test fun personalAdviceAndCommutingDoNotConsultSchoolRecordsOrStoredProfile() = runBlocking {
    Fixture().use { f ->
      val r = f.school().let { it.copy(selection = it.selection.copy(area = "lessons"),
        arguments = JsonObject(it.arguments + ("sezione" to JsonPrimitive("lessons")))) }
      f.sources.connect(r)
      f.profile.correct("private-fact", "cv.lessons", "Private school evidence must not enter generic advice")
      val fg = ResearchForeground(f.runner, f.setup, f.profile, f.connections)
      val baseline = f.transport.calls.size
      for (q in listOf("Come posso migliorare le mie lezioni di chitarra?", "Come vado a scuola in bicicletta?", "Come vado a scuola?",
        "Come sto andando a scuola in autobus?", "Dammi consigli per le mie lezioni di pianoforte",
        "Analizza le mie lezioni di chitarra", "Come posso migliorare la mia musica?",
        "Cerca consigli per migliorare i miei voti", "Leggi istruzioni per i miei voti in ClasseViva",
        "Che suggerimenti hai per i miei voti?", "Quali esercizi servono per le mie lezioni scolastiche?",
        "Cosa significa la frase 'analizza i miei voti in ClasseViva'?",
        "Spiegami la frase 'in base ai miei voti'")) {
        val query = ResearchQuestionPlanner.profileQuery(q)
        assertNull(q, query); assertNull(q, fg.select(q, null))
        assertEquals("", fg.profileBlock(query))
      }
      assertEquals(baseline, f.transport.calls.size)
      assertNotNull(fg.select("controlla le mie lezioni in ClasseViva", null))
      assertNotNull(fg.select("quali sono le mie lezioni a scuola?", null))
      assertNotNull(fg.select("puoi analizzare le mie lezioni scolastiche?", null))
      assertTrue("fluidify.history_records" in ResearchQuestionPlanner.profileQuery("consigliami musica in base ai miei ascolti")!!.fields)
      assertEquals("Private school evidence must not enter generic advice", f.profile.detail("private-fact")!!.item.value)
    }
  }

  @Test fun disabledAutonomousPlanDoesNotHideReplacementInteractiveAccount() = runBlocking {
    Fixture().use { f ->
      val first = f.music(); f.sources.connect(first)
      f.profile.savePlan(AreaResearchPlan(AreaConfiguration(first.selection.area, enabled = false, setupComplete = true), policy, listOf(first)))
      f.sources.revoke(first.grantId)
      f.transport.account = "account-B"
      val replacement = first.copy(selection = first.selection.copy(account = "account-B"),
        arguments = JsonObject(first.arguments + ("account" to JsonPrimitive("account-B"))))
      f.sources.connect(replacement)
      val fg = ResearchForeground(f.runner, f.setup, f.profile, f.connections)
      assertEquals(listOf(replacement), fg.select("quali brani ascolto?", null)!!.reads)
      assertFalse(f.foundation.grant(first.grantId)!!.enabled)
      assertFalse(f.setup.configuration(first.selection.area)!!.enabled)
    }
  }

  @Test fun latestInteractivePeriodWinsWithoutChangingTheAutonomousPlan() = runBlocking {
    Fixture().use { f ->
      val first = f.music(); f.sources.connect(first)
      f.profile.savePlan(AreaResearchPlan(AreaConfiguration(first.selection.area, enabled = false, setupComplete = true), policy, listOf(first)))
      val recent = first.copy(arguments = JsonObject(first.arguments + mapOf("from" to JsonPrimitive("1000"), "until" to JsonPrimitive("2000"))))
      f.sources.connect(recent)
      val fg = ResearchForeground(f.runner, f.setup, f.profile, f.connections)
      assertEquals(listOf(recent), fg.select("quali brani ascolto?", null)!!.reads)
      assertEquals(listOf(first), f.setup.plan(first.selection.area)!!.reads)
    }
  }

  @Test fun persistedChatRetryResumesBeyond64PagesWithoutInitialReread() = runBlocking {
    val name = "retry-${UUID.randomUUID()}.db"
    val q = "quali brani ascolto?"
    var savedRead: ResearchRead? = null
    var cid = 0L; var oldAnswer = 0L; var questionId = 0L; var jobId = ""
    val acquired = mutableListOf<Int>()
    fun pages(f: Fixture) {
      f.transport.onRead = { _, request ->
        val n = request["arguments"]!!.jsonObject.string("cursor")?.toInt() ?: 0
        acquired += n
        buildJsonObject { put("items", buildJsonArray { add(buildJsonObject { put("id", "page-$n"); put("text", "page-$n") }) }) } to
          if (n < 69) ToolPagination((n + 1).toString(), true) else null
      }
    }
    try {
      Fixture(name).use { f ->
        val r = f.music(); savedRead = r; f.sources.connect(r); pages(f)
        val repo = ConversationsRepository(context, f.db.conversations(), f.db.messages(), f.db.attachments(), f.db.runs(), researchJson, f.db)
        cid = f.conversation()
        val turn = repo.startTurn(cid, Anchor.Continue, q, dev.antigravity.fluidengine.ai.orchestrator.AskMode.TEXT, emptyList(), f.clock.wall())
        oldAnswer = turn.assistantId; questionId = turn.questionId
        val fg = ResearchForeground(f.runner, f.setup, f.profile, f.connections)
        try {
          fg.answer(cid, q, false, policy, fg.select(q, null)!!) { id -> jobId = id; repo.associateResearch(turn, id, f.clock.wall()) }
          fail("64-tranche boundary must suspend")
        } catch (e: ResearchUnavailable) { assertEquals("TRANCHE_LIMIT", e.code) }
        repo.fail(oldAnswer, dev.antigravity.fluidengine.ai.orchestrator.FailureKind.UNKNOWN, null)
        assertEquals(64, acquired.size)
      }
      Fixture(name).use { f ->
        pages(f)
        val repo = ConversationsRepository(context, f.db.conversations(), f.db.messages(), f.db.attachments(), f.db.runs(), researchJson, f.db)
        val retry = repo.startTurn(cid, Anchor.Regenerate(oldAnswer), q, dev.antigravity.fluidengine.ai.orchestrator.AskMode.TEXT, emptyList(), f.clock.wall())
        assertEquals(questionId, retry.questionId)
        val linked = repo.researchRetry(cid, oldAnswer, retry.questionId)
        assertEquals(jobId, linked)
        val fg = ResearchForeground(f.runner, f.setup, f.profile, f.connections)
        val task = fg.select(q, null)!!.copy(resumeJobId = linked)
        val answer = fg.answer(cid, q, false, policy, task) { repo.associateResearch(retry, it, f.clock.wall()) }
        assertTrue(answer.text.isNotBlank()); assertEquals(policy, f.generatedPolicy)
        assertEquals((0..69).toList(), acquired)
        assertEquals(1, f.generations)
        // DONE jobs may provide their saved synthesis for retry of an interrupted answer, without re-acquisition.
        assertTrue(fg.answer(cid, q, false, policy, task).text.isNotBlank())
        assertEquals(70, acquired.size); assertEquals(1, f.generations)
        suspend fun rejected(code: String, block: suspend () -> Unit) {
          try { block(); fail("must reject $code") } catch (e: ResearchUnavailable) { assertEquals(code, e.code) }
        }
        rejected("CAPTURED_MODEL_MISMATCH") { fg.answer(cid, q, false, policy.copy(identity = ExactModel(ProviderId.GROQ, "different")), task) }
        rejected("JOB_SCOPE_MISMATCH") { fg.answer(f.conversation(), q, false, policy, task) }
        rejected("JOB_SCOPE_MISMATCH") { fg.answer(cid, "quali artisti ascolto?", false, policy, task) }
        rejected("SOURCE_SCOPE_CHANGED") { f.runner.validateResume(jobId, cid, q, policy, emptyList()) }
        try { repo.researchRetry(cid, oldAnswer, questionId + 123); fail("wrong turn") } catch (_: IllegalStateException) { }
        f.sources.revoke(savedRead!!.grantId)
        rejected("SOURCE_SCOPE_CHANGED") { fg.answer(cid, q, false, policy, task) }
        assertEquals(70, acquired.size)
      }
    } finally { context.deleteDatabase(name) }
  }

  @Test fun temporaryForegroundDoesNotPersistAssociationAndCompletedRegenerationDoesNotRetry() = runBlocking {
    Fixture().use { f ->
      val r = f.music(); f.sources.connect(r)
      val repo = ConversationsRepository(context, f.db.conversations(), f.db.messages(), f.db.attachments(), f.db.runs(), researchJson, f.db)
      val fg = ResearchForeground(f.runner, f.setup, f.profile, f.connections)
      val cid = f.conversation(temporary = true)
      var associations = 0
      fg.answer(cid, "quali brani ascolto?", true, policy, fg.select("quali brani ascolto?", null)!!) { associations++ }
      assertEquals(0, associations); assertTrue(f.db.runs().listByConversation(cid).isEmpty())
      val regular = f.conversation()
      val turn = repo.startTurn(regular, Anchor.Continue, "quali brani ascolto?", dev.antigravity.fluidengine.ai.orchestrator.AskMode.TEXT, emptyList(), f.clock.wall())
      repo.associateResearch(turn, "synthetic-job", f.clock.wall())
      repo.complete(turn.assistantId, "complete", emptyList())
      assertNull(repo.researchRetry(regular, turn.assistantId, turn.questionId))
    }
  }

  @Test fun targetedProfileDependenciesCountAgainstSynthesisRecordLimit() = runBlocking {
    Fixture().use { f ->
      val r = f.music(); f.sources.connect(r)
      f.profile.savePlan(AreaResearchPlan(AreaConfiguration(r.selection.area, enabled = true, setupComplete = true,
        requestCap = 2, tokenCap = 10000, windowStart = 0, windowEnd = Long.MAX_VALUE), policy, listOf(r)))
      f.profileOutput = { list -> val e = list.first(); buildJsonArray { add(buildJsonObject {
        put("field", e.field); put("value", e.value); put("valueType", e.valueType); put("kind", "OBSERVATION"); put("periodKey", e.periodKey)
        put("evidenceIds", buildJsonArray { add(e.id) })
      }) } }
      val cid = f.conversation()
      val first = f.runner.admitForeground(ForegroundResearch(cid, "quali brani ascolto?", ResearchIntent.LOOKUP, policy, listOf(r)))
      f.finish(first); assertEquals(1, f.generations)
      f.transport.onRead = { _, _ -> buildJsonObject { put("items", buildJsonArray { add(buildJsonObject { put("id", "second-record"); put("text", "second") }) }) } to null }
      val second = f.runner.admitForeground(ForegroundResearch(cid, "quali brani ascolto?", ResearchIntent.LOOKUP, policy, listOf(r), maxRecords = 1))
      assertTrue(f.runner.resumeForeground(second, cid) is TrancheResult.Progress)
      val stopped = f.runner.resumeForeground(second, cid) as TrancheResult.Suspended
      assertEquals("RECORD_LIMIT", stopped.code); assertEquals(1, f.generations)
    }
  }

  @Test fun forgottenSourceCheckpointCannotBeResumed() = runBlocking {
    Fixture().use { f ->
      val r = f.music(); f.sources.connect(r)
      val cid = f.conversation(); val q = "quali brani ascolto?"
      val id = f.runner.admitForeground(ForegroundResearch(cid, q, ResearchIntent.LOOKUP, policy, listOf(r)))
      assertTrue(f.runner.resumeForeground(id, cid) is TrancheResult.Progress)
      val calls = f.transport.calls.count { it.first == "source_read" }
      f.profile.forget("fluidify.history_records", r.grantId)
      try { f.runner.validateResume(id, cid, q, policy, listOf(r)); fail("forgotten job resumed") }
      catch (e: ResearchUnavailable) { assertEquals("JOB_INVALIDATED", e.code) }
      assertEquals(calls, f.transport.calls.count { it.first == "source_read" })
    }
  }

  @Test fun temporaryConsultationRejectsExcludedReadBeforeAcquisitionAndLateModelResponse() = runBlocking {
    Fixture().use { f ->
      val r = f.music(); f.sources.connect(r)
      f.profile.exclude(ProfileExclusion("synthetic-music", "fluidify.history_records", r.grantId, null, false))
      val calls = f.transport.calls.size
      try { f.runner.consultTemporary("quali brani ascolto?", ResearchIntent.LOOKUP, policy, listOf(r)); fail("excluded read") }
      catch (_: ResearchUnavailable) { }
      assertEquals(calls, f.transport.calls.size); assertEquals(0, f.generations)
      f.profile.reenable("synthetic-music")
      f.beforeModel = { f.profile.exclude(ProfileExclusion("synthetic-late", "fluidify.history_records", r.grantId, null, false)) }
      try { f.runner.consultTemporary("quali brani ascolto?", ResearchIntent.LOOKUP, policy, listOf(r)); fail("late excluded answer") }
      catch (_: ResearchUnavailable) { }
      assertEquals(1, f.generations)
      assertTrue(f.db.runs().listByConversation(f.conversation(temporary = true)).isEmpty())
    }
  }

}
