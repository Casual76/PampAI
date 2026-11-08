package dev.pampa.pampai.core.assistant.durable

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.pampa.pampai.core.assistant.db.*
import dev.pampa.pampai.core.assistant.runtime.ExactModel
import dev.pampa.pampai.core.assistant.runtime.ModelPolicy
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DurableFoundationTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private fun memory() = Room.inMemoryDatabaseBuilder(context, PampaiDatabase::class.java).build()
  private val policy = ModelPolicy(ExactModel(ProviderId.GROQ, "synthetic-exact"))
  private fun service(db: PampaiDatabase) = DurableFoundation(db, SourceProofValidator { g, _ -> g.generationProof == "verified-A" })
  private suspend fun seed(s: DurableFoundation, id: String = "j", execution: String = "FOREGROUND", conversation: Long? = null): ResearchJob {
    s.putGrant(SourceGrant("g", "synthetic.app", "synthetic.authority", "account-A", "student-A", "music", "2026", enabled = true, generationProof = "verified-A", proofExpiresAt = 10000))
    return s.createJob(id, conversation, "music", "RESEARCH", execution, "synthetic objective", policy, listOf("g"), listOf(ResearchStep(id, 0, "g", "bounded records")), 10, 100)
  }
  private fun evidence(job: String = "j", id: String = "e") = ResearchEvidence(id, job, "g", "record-1", "2026-11", "artist", "synthetic artist", null, 0, 100, "UTC", 100, 100, "PARTIAL", "KNOWN", "synthetic source", "sha256")
  private fun item(id: String = "p", field: String = "preference", kind: String = "INFERENCE") = ProfileItem(id, field, "synthetic inference", kind, "j", 0, generatedProvider = "GROQ", generatedModel = "synthetic-exact", derivation = "v1")
  private suspend fun publish(db: PampaiDatabase, s: DurableFoundation) {
    s.configure(AreaConfiguration("music", enabled=true, setupComplete=true, windowStart=0, windowEnd=10000), listOf(AreaModel("music", "GROQ", "synthetic-exact")))
    val lease = requireNotNull(s.claim("j", "owner", 100, 100))
    assertTrue(s.commit(lease, s.checkpoint("j").single().copy(outcome = "COMPLETE"), listOf(evidence()), listOf(ProfileWrite(item(), listOf("e"))), 101))
  }
  @Test fun leaseReclaimRejectsLateOwnerAndPreservesCheckpoint() = runBlocking {
    val db = memory(); try {
      val s = service(db); seed(s)
      val first = requireNotNull(s.claim("j", "one", 100, 10))
      assertNull(s.claim("j", "two", 109, 10))
      val second = requireNotNull(s.claim("j", "two", 110, 10))
      assertFalse(s.commit(first, s.checkpoint("j").single(), emptyList(), emptyList(), 111))
      assertTrue(s.commit(second, s.checkpoint("j").single().copy(cursor = "page2", fragment = "abc", fragmentOffset = 3, progress = 1, outcome = "PARTIAL"), emptyList(), emptyList(), 111))
      assertEquals("page2", s.checkpoint("j").single().cursor)
      assertEquals("synthetic-exact", db.durable().job("j")!!.model)
    } finally { db.close() }
  }
  @Test fun temporaryCreationAndLaterTemporaryTransitionAreRejected() = runBlocking {
    val db = memory(); try {
      val s = service(db)
      val id = db.conversations().insert(ConversationEntity(title="temp", createdAtMillis=0, updatedAtMillis=0, temporary=true))
      try { seed(s, conversation=id); fail("temporary accepted") } catch (_: IllegalArgumentException) { }
      db.conversations().update(db.conversations().get(id)!!.copy(temporary=false))
      seed(s, conversation=id)
      val lease = requireNotNull(s.claim("j", "o", 100, 100))
      db.conversations().update(db.conversations().get(id)!!.copy(temporary=true))
      assertFalse(s.commit(lease, s.checkpoint("j").single(), emptyList(), emptyList(), 101))
      assertNull(s.claim("j", "other", 201, 100))
    } finally { db.close() }
  }
  @Test fun defaultProofAndUnknownSourceGenerationFailClosed() = runBlocking {
    val db = memory(); try {
      val s = DurableFoundation(db)
      try { seed(s); fail("unverified proof accepted") } catch (_: IllegalArgumentException) { }
      val verified = service(db); seed(verified); publish(db, verified)
      assertTrue(DurableFoundation(db).retrieve(listOf("preference"), 102).isEmpty())
    } finally { db.close() }
  }
  @Test fun backgroundRequiresSetupButForegroundDoesNot() = runBlocking {
    val db = memory(); try {
      val s = service(db); seed(s, execution="BACKGROUND")
      assertNull(s.claim("j", "o", 100, 100))
      s.configure(AreaConfiguration("music", enabled=true, setupComplete=true, requestCap=2, tokenCap=100, windowStart=0, windowEnd=10000), listOf(AreaModel("music", "GROQ", "synthetic-exact")))
      assertNotNull(s.claim("j", "o", 100, 100))
    } finally { db.close() }
  }
  @Test fun revokeAndAccountChangeFenceLateResultsAndRetrieval() = runBlocking {
    val db = memory(); try {
      val s = service(db); seed(s); publish(db, s)
      assertEquals(1, s.retrieve(listOf("preference"), 102).size)
      val lease = requireNotNull(s.claim("j", "o", 102, 100))
      val grant = requireNotNull(db.durable().grant("g"))
      s.putGrant(grant.copy(account="account-B"))
      assertTrue(s.retrieve(listOf("preference"), 103).isEmpty())
      assertFalse(s.commit(lease, s.checkpoint("j").single(), emptyList(), emptyList(), 103))
      s.revoke("g"); assertFalse(db.durable().grant("g")!!.enabled)
    } finally { db.close() }
  }
  @Test fun correctionDominatesAndCanonicalForgetInvalidatesDependentInference() = runBlocking {
    val db = memory(); try {
      val s = service(db); seed(s); publish(db, s)
      s.correct("statement", "preference", "user corrected")
      assertEquals("user corrected", s.retrieve(listOf("preference"), 102).single().item.value)
      s.exclude(ProfileExclusion("forget-artist", "artist", null, null, false))
      assertEquals("FORGOTTEN", db.durable().profile("p")!!.status)
      assertNull(db.durable().evidence("e"))
      assertEquals("user corrected", s.retrieve(listOf("preference"), 102).single().item.value)
    } finally { db.close() }
  }
  @Test fun unmappedExclusionBlocksAcquisitionUntilExplicitReenable() = runBlocking {
    val db = memory(); try {
      val s = service(db); seed(s)
      s.exclude(ProfileExclusion("unmapped", null, null, null, true))
      assertNull(s.claim("j", "o", 100, 100))
      try { seed(s, "new"); fail("unmapped exclusion bypassed") } catch (_: IllegalArgumentException) { }
      s.reenable("unmapped"); seed(s, "after"); assertNotNull(s.claim("after", "o", 100, 100))
    } finally { db.close() }
  }
  @Test fun failedReadIsUnknownAndRecordDedupeDoesNotSumPageAggregates() = runBlocking {
    val db = memory(); try {
      val s = service(db); seed(s); publish(db, s)
      val lease = requireNotNull(s.claim("j", "o", 102, 100))
      assertTrue(s.commit(lease, s.checkpoint("j").single().copy(outcome="UNKNOWN"), emptyList(), emptyList(), 103))
      assertEquals(1, s.retrieve(listOf("preference"), 104).size)
      val next = requireNotNull(s.claim("j", "o", 104, 100))
      assertTrue(s.commit(next, s.checkpoint("j").single().copy(outcome="COMPLETE"), listOf(evidence()), emptyList(), 105))
      assertEquals(1, db.durable().evidenceCount("j"))
    } finally { db.close() }
  }
  @Test fun forgetSurvivesReopenAndRejectsInflightResult() = runBlocking {
    val name="durable-forget-${System.nanoTime()}.db"
    var db=Room.databaseBuilder(context, PampaiDatabase::class.java, name).build()
    try {
      var s=service(db); seed(s); val lease=requireNotNull(s.claim("j", "o", 100, 100))
      s.exclude(ProfileExclusion("field", "artist", null, null, false))
      db.close(); db=Room.databaseBuilder(context, PampaiDatabase::class.java, name).build(); s=service(db)
      assertFalse(s.commit(lease, s.checkpoint("j").single(), listOf(evidence()), emptyList(), 101))
      assertTrue(s.retrieve(listOf("artist"), 101).isEmpty())
      assertEquals(1, db.durable().exclusions().size)
    } finally { db.close(); context.deleteDatabase(name) }
  }
  @Test fun explicitRegrantCanReacquireInvalidRecordsWithoutRestoringOldProfile() = runBlocking {
    val db=memory(); try {
      val s=service(db); seed(s); publish(db,s); s.revoke("g")
      seed(s, "new")
      val lease=requireNotNull(s.claim("new", "owner", 100, 100))
      assertTrue(s.commit(lease, s.checkpoint(lease, 101).single().copy(outcome="COMPLETE"), listOf(evidence("new", "e2")), listOf(ProfileWrite(item("p2").copy(jobId="new"), listOf("e2"))), 101))
      val retrieved=s.retrieve(listOf("preference"), 102)
      assertEquals(listOf("p2"), retrieved.map { it.item.id })
      assertNull(db.durable().evidence("e"))
    } finally { db.close() }
  }

  @Test fun repeatedResearchReusesCanonicalEvidenceWithoutDoubleCounting() = runBlocking {
    val db=memory(); try {
      val s=service(db); seed(s); publish(db,s)
      s.createJob("repeat", null, "music", "RESEARCH", "FOREGROUND", "same bounded records", policy, listOf("g"), listOf(ResearchStep("repeat", 0, "g", "bounded records")), 10, 102)
      val lease=requireNotNull(s.claim("repeat", "owner", 102, 100))
      assertTrue(s.commit(lease, s.checkpoint(lease, 103).single().copy(outcome="COMPLETE"), listOf(evidence("repeat", "e")), listOf(ProfileWrite(item("p2").copy(jobId="repeat"), listOf("e"))), 103))
      assertEquals(0, db.durable().evidenceCount("repeat"))
      assertEquals(1, s.retrieve(listOf("preference"), 104).flatMap { it.evidence }.map { it.id }.distinct().size)
      s.forget("artist")
      assertTrue(s.retrieve(listOf("preference"), 105).isEmpty())
    } finally { db.close() }
  }

  @Test fun checkpointAndExactPolicySurviveReopenAndExpiredLeaseRecovery() = runBlocking {
    val name="checkpoint-reopen-${System.nanoTime()}.db"
    var db=Room.databaseBuilder(context, PampaiDatabase::class.java, name).build()
    try {
      var s=service(db); seed(s)
      val first=requireNotNull(s.claim("j", "one", 100, 10))
      assertTrue(s.commit(first, s.checkpoint(first, 101).single().copy(cursor="page-two", fragment="partial", fragmentOffset=7, progress=1, outcome="PARTIAL"), emptyList(), emptyList(), 101))
      val interrupted=requireNotNull(s.claim("j", "two", 102, 10))
      db.close(); db=Room.databaseBuilder(context, PampaiDatabase::class.java, name).build(); s=service(db)
      assertNull(s.claim("j", "three", 111, 10))
      val recovered=requireNotNull(s.claim("j", "three", 112, 10))
      assertEquals("page-two", s.checkpoint(recovered, 113).single().cursor)
      assertEquals("partial", s.checkpoint(recovered, 113).single().fragment)
      assertEquals("synthetic-exact", recovered.job.model)
      assertFalse(s.commit(interrupted, s.checkpoint(recovered, 113).single(), emptyList(), emptyList(), 113))
    } finally { db.close(); context.deleteDatabase(name) }
  }

  @Test fun verifiedFreshnessRenewalAfterExpiryPreservesVectorProfileAndResumesJob() = runBlocking {
    val db=memory(); try {
      val s=service(db); seed(s); publish(db,s)
      val grant=requireNotNull(db.durable().grant("g"))
      val epoch=db.durable().state()
      val vector=db.durable().vectors("j")
      val job=db.durable().job("j")
      val profile=db.durable().profile("p")
      val checkpoint=s.checkpoint("j")
      assertNull(s.claim("j", "resumed", 10001, 100))
      assertTrue(s.retrieve(listOf("preference"), 10001).isEmpty())
      assertTrue(s.refreshProof("g", grant.version, "verified-A", 20000, 10001))
      assertEquals(grant.copy(proofExpiresAt=20000), db.durable().grant("g"))
      assertEquals(epoch, db.durable().state()); assertEquals(vector, db.durable().vectors("j"))
      assertEquals(job, db.durable().job("j")); assertEquals(profile, db.durable().profile("p"))
      assertEquals(checkpoint, s.checkpoint("j"))
      assertEquals(1, s.retrieve(listOf("preference"), 10002).size)
      val lease=requireNotNull(s.claim("j", "resumed", 10002, 100))
      assertTrue(s.commit(lease, s.checkpoint(lease, 10003).single(), emptyList(), emptyList(), 10003))
    } finally { db.close() }
  }
  @Test fun renewalRejectsMissingStaleUnverifiedChangedIdentityAndWrongVersion() = runBlocking {
    val db=memory(); try {
      val s=service(db); seed(s)
      val old=requireNotNull(db.durable().grant("g")); val epoch=db.durable().state()
      assertFalse(s.refreshProof("missing", old.version, "verified-A", 20000, 10001))
      assertFalse(s.refreshProof("g", old.version, "", 20000, 10001))
      assertFalse(s.refreshProof("g", old.version, "changed-incarnation", 20000, 10001))
      assertFalse(s.refreshProof("g", old.version+1, "verified-A", 20000, 10001))
      assertFalse(s.refreshProof("g", old.version, "verified-A", 10000, 10001))
      assertFalse(s.refreshProof("g", old.version, "verified-A", 9999, 100))
      assertFalse(DurableFoundation(db).refreshProof("g", old.version, "verified-A", 20000, 10001))
      assertEquals(old, db.durable().grant("g")); assertEquals(epoch, db.durable().state())
      assertEquals("READY", db.durable().job("j")!!.state)
    } finally { db.close() }
  }
  @Test fun renewalChecksProposedReceiptAndCannotUndoRevocationOrIdentityChange() = runBlocking {
    val db=memory(); try {
      val s=service(db); seed(s); val old=requireNotNull(db.durable().grant("g"))
      var inspected: SourceGrant?=null
      val checked=DurableFoundation(db, SourceProofValidator { g, now -> inspected=g; g==old.copy(proofExpiresAt=20000) && now==10001L })
      assertTrue(checked.refreshProof("g", old.version, "verified-A", 20000, 10001))
      assertEquals(old.copy(proofExpiresAt=20000), inspected)
      s.revoke("g")
      assertFalse(s.refreshProof("g", old.version, "verified-A", 30000, 20001))
      val disabled=requireNotNull(db.durable().grant("g"))
      assertFalse(s.refreshProof("g", disabled.version, "verified-A", 30000, 20001))
      s.putGrant(old.copy(generationProof="new-incarnation"))
      val changed=requireNotNull(db.durable().grant("g"))
      assertFalse(s.refreshProof("g", changed.version, "verified-A", 30000, 20001))
      assertEquals("CANCELLED", db.durable().job("j")!!.state)
    } finally { db.close() }
  }

}
