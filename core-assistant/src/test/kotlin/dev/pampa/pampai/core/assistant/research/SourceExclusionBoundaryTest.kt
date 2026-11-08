package dev.pampa.pampai.core.assistant.research

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.antigravity.fluidengine.ai.bridge.*
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.antigravity.fluidengine.ai.tools.*
import dev.pampa.pampai.core.assistant.db.PampaiDatabase
import dev.pampa.pampai.core.assistant.durable.*
import dev.pampa.pampai.core.assistant.runtime.*
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** Production source admission, receipt validation and Room; only the external transport is synthetic. */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class SourceExclusionBoundaryTest {
  private class Clock : ResearchClock {
    override fun wall() = 100000L
    override fun tick() = 100L
  }
  private class Transport(private val clock: ResearchClock) : ResearchTransport {
    val calls = mutableListOf<String>()
    private val leases = mutableMapOf<String, JsonObject>()
    var beforeRead: suspend () -> Unit = {}
    override suspend fun hosts() = emptyList<RemoteHost>()
    override suspend fun typed(host: RemoteHost) = true
    override suspend fun call(host: RemoteHost, tool: String, args: JsonObject): RemoteResult.Done {
      calls += tool
      fun proof(lease: String) = buildJsonObject {
        put("contract", "aria.source-proof"); put("version", 1); put("domain", host.label); put("authority", host.authority)
        put("incarnation", "process"); put("authorization", "authorization"); put("capability", "capability"); put("lease", lease)
        put("account", "account"); put("student", if (host.label == "cv") JsonPrimitive("student") else JsonNull)
        put("scope", leases.getValue(lease)); put("checkedAtMillis", clock.wall()); put("expiresAtMillis", clock.wall() + 60000); put("valid", true)
      }
      val data = when (tool) {
        "source_capabilities" -> buildJsonObject {
          put("contract", "aria.source-proof"); put("version", 1); put("domain", host.label); put("authority", host.authority)
          put("incarnation", "process"); put("authorization", "authorization"); put("capability", "capability"); put("ttlMillis", 60000)
          put("account", "account"); put("student", if (host.label == "cv") JsonPrimitive("student") else JsonNull)
          put("readTools", JsonArray(listOf("history_records", "history_aggregate", "registro_dati").map(::JsonPrimitive)))
        }
        "source_lease" -> {
          val lease = UUID.randomUUID().toString()
          leases[lease] = ResearchReadPolicy.scope(args.string("tool")!!, args.getValue("arguments").jsonObject)
          proof(lease)
        }
        "source_validate" -> proof(args.string("lease")!!)
        "source_read" -> {
          beforeRead()
          buildJsonObject {
            put("records", buildJsonArray { add(buildJsonObject { put("id", "record"); put("value", 8) }) })
            if (host.label == "cv") {
              put("account", "account"); put("student", "student"); put("year", "2026-2027"); put("section", "grades")
            }
            put("sourceProof", proof(args.string("lease")!!))
          }
        }
        else -> error("Unexpected external call")
      }
      return RemoteResult.Done("synthetic", false, emptyList(), ToolResultDetails(ToolOutcome.COMPLETED,
        data = data, coverage = ToolCoverage(false), freshness = ToolFreshness(clock.wall())))
    }
  }
  private class Fixture(context: Context) : AutoCloseable {
    val db = Room.inMemoryDatabaseBuilder(context, PampaiDatabase::class.java).build()
    val clock = Clock()
    val transport = Transport(clock)
    val cache = VerifiedSourceReceiptCache(clock)
    val foundation = DurableFoundation(db, cache)
    val sources = ResearchSources(transport, foundation, cache, clock, ResearchConnections(context, foundation))
    fun music(tool: String = "history_records") = ResearchRead(SourceSelection("synthetic.music", "synthetic.music.tools", "fluidify", "account", area = tool),
      tool, buildJsonObject { put("account", "account"); put("from", "0"); put("until", "1000") })
    fun school(refresh: Boolean = false) = ResearchRead(SourceSelection("synthetic.school", "synthetic.school.tools", "cv", "account", "student", "2026-2027", "grades"),
      "registro_dati", buildJsonObject { put("account", "account"); put("studente", "student"); put("anno", "2026-2027"); put("sezione", "grades"); put("aggiorna", refresh) }, refresh)
    override fun close() = db.close()
  }
  private val context: Context = ApplicationProvider.getApplicationContext()
  private suspend fun excluded(action: suspend () -> Unit) {
    assertEquals("EXCLUDED", (runCatching { action() }.exceptionOrNull() as? ResearchUnavailable)?.code)
  }
  @Test fun excludedCachedReadAndEquivalentAggregateNeverDispatchButSchoolStillConnects() = runBlocking {
    Fixture(context).use { f ->
      val read = f.music(); f.sources.connect(read); f.sources.acquire(read)
      f.foundation.forget("fluidify.history_records")
      val calls = f.transport.calls.size
      excluded { f.sources.acquire(read) }
      excluded { f.sources.renew(listOf(read)) }
      excluded { f.sources.connect(f.music("history_aggregate")) }
      assertEquals(calls, f.transport.calls.size)
      assertTrue(f.sources.connect(f.school()).enabled)
    }
  }
  @Test fun exclusionDuringExternalReadRejectsTheReturningPayload() = runBlocking {
    Fixture(context).use { f ->
      val read = f.music(); f.sources.connect(read); val captured = f.sources.acquire(read)
      f.transport.beforeRead = { f.foundation.forget("fluidify.history_records") }
      excluded { f.sources.read(captured, read.tool, read.arguments) }
      assertEquals(1, f.transport.calls.count { it == "source_read" })
      assertTrue(f.foundation.profileCandidates(listOf("fluidify.history_records")).isEmpty())
    }
  }
  @Test fun profileCanonicalScopeReusesEvidenceWithoutAnotherSourceRead() = runBlocking {
    Fixture(context).use { f ->
      val read = f.school(refresh = true); f.sources.connect(read)
      val policy = ModelPolicy(ExactModel(ProviderId.GROQ, "synthetic"))
      f.foundation.configure(AreaConfiguration("grades", enabled = true, setupComplete = true, windowStart = 0, windowEnd = 200000),
        listOf(AreaModel("grades", "GROQ", "synthetic")))
      val query = buildJsonObject { put("read", researchJson.encodeToJsonElement(read)); put("synthesis", false) }.toString()
      f.foundation.createJob("job", null, "interactive", "LOOKUP", "FOREGROUND", "synthetic", policy,
        listOf(read.grantId), listOf(ResearchStep("job", 0, read.grantId, query)), 10, f.clock.wall())
      val lease = f.foundation.claim("job", "owner", f.clock.wall(), 10000)!!
      val captured = f.sources.acquire(read)
      val page = SourcePages.consume(f.foundation.steps("job").single(), captured, f.sources.read(captured, read.tool, read.arguments))
      val fact = ProfileItem("fact", "cv.grades", "synthetic fact", "OBSERVATION", "job", 0)
      assertTrue(f.foundation.commit(lease, page.step, page.evidence, listOf(ProfileWrite(fact, page.evidence.map { it.id })), f.clock.wall()))
      val profile = ResearchProfile(f.foundation, f.sources, f.clock, mockk())
      val allowed = read.copy(allowTechnicalRefresh = false, arguments = JsonObject(read.arguments + ("aggiorna" to JsonPrimitive(false))))
      assertEquals("fact", profile.retrieve(ProfileQuery(listOf("cv.grades")), listOf(allowed)).single().item.id)
      assertEquals(1, f.transport.calls.count { it == "source_read" })
      val other = allowed.copy(arguments = JsonObject(allowed.arguments + ("materia" to JsonPrimitive("Other"))))
      assertTrue(profile.retrieve(ProfileQuery(listOf("cv.grades")), listOf(other)).isEmpty())
    }
  }
}
