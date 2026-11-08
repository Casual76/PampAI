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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** Real Room + actual SourcePages conversion; fixtures never contact a provider/source. */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class ScopedExclusionsAndCanonicalEvidenceTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val policy = ModelPolicy(ExactModel(ProviderId.GROQ, "fixture"))
  private fun school(refresh: Boolean = false, section: String = "grades") = ResearchRead(
    SourceSelection("school", "school.tools", "cv", "account", "student", "2026-2027", section), "registro_dati",
    buildJsonObject { put("account", "account"); put("studente", "student"); put("anno", "2026-2027"); put("sezione", section); put("aggiorna", refresh) }, refresh)
  private fun music(tool: String = "history_records") = ResearchRead(
    SourceSelection("music", "music.tools", "fluidify", "account", area = "music"), tool,
    buildJsonObject { put("account", "account"); put("from", "0"); put("until", "1000") })
  private fun service(db: PampaiDatabase) = DurableFoundation(db, SourceProofValidator { _, _ -> true })
  private suspend fun connect(f: DurableFoundation, r: ResearchRead) = f.putGrant(SourceGrant(r.grantId,
    r.selection.packageName, r.selection.authority, r.selection.account, r.selection.student, r.selection.area, r.selection.year,
    enabled = true, generationProof = "generation", proofExpiresAt = 100000))
  private fun query(r: ResearchRead) = buildJsonObject { put("read", researchJson.encodeToJsonElement(r)); put("synthesis", false) }.toString()
  private suspend fun job(f: DurableFoundation, id: String, r: ResearchRead, cap: Int = 10, steps: Int = 1) = f.createJob(
    id, null, "interactive", "RESEARCH", "FOREGROUND", "fixture", policy, listOf(r.grantId),
    List(steps) { ResearchStep(id, it, r.grantId, query(r)) }, cap, 100)
  private fun page(step: ResearchStep, r: ResearchRead, vararg keys: String): ReadPage {
    val c = CapturedSourceRead(r, RemoteHost(r.selection.packageName, r.selection.authority, r.selection.domain),
      "capability", "lease-${r.allowTechnicalRefresh}", "generation", "key", 0, 100000)
    val data = buildJsonObject {
      if (r.selection.domain == "cv") { put("account", "account"); put("student", "student"); put("year", "2026-2027"); put("section", r.selection.area) }
      put("records", buildJsonArray { keys.forEach { key -> add(buildJsonObject { put("id", key); put("value", 8) }) } })
    }
    return SourcePages.consume(step, c, RemoteResult.Done("fixture", false, emptyList(), ToolResultDetails(ToolOutcome.COMPLETED, data = data)))
  }
  private suspend fun commit(f: DurableFoundation, id: String, p: ReadPage): Boolean {
    val lease = requireNotNull(f.claim(id, "owner", 101, 1000))
    return f.commit(lease, p.step, p.evidence, emptyList(), 101)
  }
  private inline fun rejected(action: () -> Unit) {
    try { action(); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
  }
  @Test fun scopedForgetLeavesIndependentSchoolUsableAndBlocksEquivalentMusicReads() = runBlocking {
    val db = Room.inMemoryDatabaseBuilder(context, PampaiDatabase::class.java).build()
    try {
      val f = service(db); val m = music(); val cv = school(); connect(f, m); connect(f, cv)
      job(f, "old", m); val late = f.claim("old", "owner", 100, 1000)!!; val old = page(f.steps("old").single(), m, "r")
      f.forget("fluidify.history_records", m.grantId, "r")
      assertFalse(f.validate(late, 101)); assertFalse(f.commit(late, old.step, old.evidence, emptyList(), 101))
      assertFalse(f.readAllowed(m)); assertFalse(f.readAllowed(music("history_aggregate")))
      assertTrue(f.readAllowed(cv)); rejected { job(f, "blocked", music("history_artist_counts")) }
      job(f, "unrelated", cv); assertTrue(commit(f, "unrelated", page(f.steps("unrelated").single(), cv, "grade")))
      val restarted = service(db); assertNotNull(restarted.claim("unrelated", "restart", 102, 1000))
      f.exclude(ProfileExclusion("unknown", "unmapped.preference", null, null, true))
      assertFalse(f.readAllowed(cv)); rejected { job(f, "unknown", cv) }
    } finally { db.close() }
  }
  @Test fun sourceExclusionDoesNotBlockAnotherSourceAndRevocationRejectsLateCommit() = runBlocking {
    val db = Room.inMemoryDatabaseBuilder(context, PampaiDatabase::class.java).build()
    try {
      val f = service(db); val m = music(); val cv = school(); connect(f, m); connect(f, cv)
      f.exclude(ProfileExclusion("source", null, m.grantId, null, false))
      assertFalse(f.readAllowed(m)); assertTrue(f.readAllowed(cv)); job(f, "school", cv)
      val lease = f.claim("school", "owner", 100, 1000)!!; val p = page(f.steps("school").single(), cv, "g")
      f.revoke(cv.grantId); assertFalse(f.commit(lease, p.step, p.evidence, emptyList(), 101))
    } finally { db.close() }
  }
  @Test fun refreshControlsReuseCanonicalContentWithExactAcquisitionBinding() = runBlocking {
    val db = Room.inMemoryDatabaseBuilder(context, PampaiDatabase::class.java).build()
    try {
      val f = service(db); val before = school(); val after = school(true); connect(f, before)
      job(f, "first", before); val first = page(f.steps("first").single(), before, "g")
      // Simulate a schema7 row written before canonical/acquisition separation.
      val legacy = first.copy(evidence = first.evidence.map { e -> e.copy(provenance = JsonObject(
        Json.parseToJsonElement(e.provenance).jsonObject + ("read" to researchJson.encodeToJsonElement(before))).toString()) })
      assertTrue(commit(f, "first", legacy))
      job(f, "second", after); val second = page(f.steps("second").single(), after, "g")
      assertEquals(first.evidence.single().id, second.evidence.single().id)
      assertEquals(first.evidence.single().provenance, second.evidence.single().provenance)
      assertNotEquals(first.step.cursor, second.step.cursor)
      assertTrue(commit(f, "second", second))
      val lease = f.claim("second", "reader", 102, 1000)!!
      assertEquals(1, f.snapshot(lease, 103).evidence.size)
      job(f, "wrong-binding", after)
      val bad = page(f.steps("wrong-binding").single(), after, "g")
      rejected { commit(f, "wrong-binding", bad.copy(step = bad.step.copy(cursor = first.step.cursor))) }
      job(f, "wrong-content", after)
      val changed = page(f.steps("wrong-content").single(), after, "g")
      rejected { commit(f, "wrong-content", changed.copy(evidence = changed.evidence.map { it.copy(value = "tampered") })) }
      job(f, "wrong-scope", after)
      val scoped = page(f.steps("wrong-scope").single(), after, "g")
      rejected { commit(f, "wrong-scope", scoped.copy(evidence = scoped.evidence.map { it.copy(field = "cv.agenda") })) }
    } finally { db.close() }
  }
  @Test fun distinctReferencedUnionCountsReusedRowsAcrossPagesAndStepsWithoutDoubleCounting() = runBlocking {
    val db = Room.inMemoryDatabaseBuilder(context, PampaiDatabase::class.java).build()
    try {
      val f = service(db); val r = school(); connect(f, r)
      job(f, "seed", r); assertTrue(commit(f, "seed", page(f.steps("seed").single(), r, "R1", "R2")))
      job(f, "two-at-once", r, cap = 1)
      rejected { commit(f, "two-at-once", page(f.steps("two-at-once").single(), r, "R1", "R2")) }
      assertNull(f.steps("two-at-once").single().cursor)
      job(f, "pages", r, cap = 1)
      val first = page(f.steps("pages").single(), r, "R1")
      assertTrue(commit(f, "pages", first.copy(step = first.step.copy(outcome = "PARTIAL"))))
      val overlap = page(f.steps("pages").single(), r, "R1")
      assertTrue(commit(f, "pages", overlap.copy(step = overlap.step.copy(outcome = "PARTIAL"))))
      rejected { commit(f, "pages", page(f.steps("pages").single(), r, "R2")) }
      job(f, "steps", r, cap = 1, steps = 2)
      assertTrue(commit(f, "steps", page(f.steps("steps")[0], r, "R1")))
      assertTrue(commit(f, "steps", page(f.steps("steps")[1], r, "R1")))
      rejected { commit(f, "steps", page(f.steps("steps")[1], r, "R2")) }
    } finally { db.close() }
  }
  @Test fun forgottenRecordScrubsEquivalentEvidenceAndDependentProfileWhileIndependentEvidenceRemains() = runBlocking {
    val db = Room.inMemoryDatabaseBuilder(context, PampaiDatabase::class.java).build()
    try {
      val f = service(db); val m = music("history_artist_counts"); val cv = school(); connect(f, m); connect(f, cv)
      f.configure(AreaConfiguration("music", true, true, windowStart = 0, windowEnd = 100000), listOf(AreaModel("music", "GROQ", "fixture")))
      job(f, "music-profile", m)
      val p = page(f.steps("music-profile").single(), m, "artist-aggregate")
      val lease = f.claim("music-profile", "owner", 100, 1000)!!
      val item = ProfileItem("derived", "music.preference", "sensitive", "OBSERVATION", "music-profile", 0)
      assertTrue(f.commit(lease, p.step, p.evidence, listOf(ProfileWrite(item, p.evidence.map { it.id })), 101))
      assertEquals(1, f.retrieve(listOf("music.preference"), 102).size)
      f.forget("fluidify.history_records", m.grantId, "raw-record")
      assertTrue(f.retrieve(listOf("music.preference"), 102).isEmpty())
      assertEquals("FORGOTTEN", db.durable().profile("derived")!!.status)
      assertTrue(db.durable().profile("derived")!!.value.isEmpty())
      assertNull(db.durable().evidence(p.evidence.single().id))
      job(f, "school-after-forget", cv)
      assertTrue(commit(f, "school-after-forget", page(f.steps("school-after-forget").single(), cv, "grade")))
      val current = f.claim("school-after-forget", "reader", 102, 1000)!!
      assertEquals(listOf("cv.grades"), f.snapshot(current, 103).evidence.map { it.field })
    } finally { db.close() }
  }
  @Test fun reopenHonorsExclusions() = runBlocking {
    val name = "ex-${UUID.randomUUID().toString().take(12)}"; val r = school(); val m = music()
    var db = Room.databaseBuilder(context, PampaiDatabase::class.java, name).build()
    try {
      var f = service(db); connect(f, r); connect(f, m); f.forget("fluidify.history_records")
      job(f, "persisted", r); db.close(); db = Room.databaseBuilder(context, PampaiDatabase::class.java, name).build()
      f = service(db); assertNotNull(f.claim("persisted", "restart", 101, 1000)); assertFalse(f.readAllowed(m)); assertTrue(f.readAllowed(r))
    } finally { db.close(); context.deleteDatabase(name) }
  }
}
