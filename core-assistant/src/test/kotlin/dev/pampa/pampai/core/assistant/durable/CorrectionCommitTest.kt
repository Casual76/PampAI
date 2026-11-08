package dev.pampa.pampai.core.assistant.durable

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.pampa.pampai.core.assistant.db.PampaiDatabase
import dev.pampa.pampai.core.assistant.runtime.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class CorrectionCommitTest {
  @Test fun priorCorrectionDropsConflictingLearningWithoutLosingAnswerCheckpoint() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, PampaiDatabase::class.java).build()
    try {
      val foundation = DurableFoundation(db, SourceProofValidator { _, _ -> true })
      foundation.putGrant(SourceGrant("g", "synthetic", "synthetic.tools", "account", null, "music", null,
        enabled = true, generationProof = "verified", proofExpiresAt = 10000))
      foundation.configure(AreaConfiguration("music", enabled = true, setupComplete = true, windowStart = 0, windowEnd = 10000), listOf(AreaModel("music", "GROQ", "synthetic")))
      foundation.correct("user", "music.artist", "La mia correzione")
      val policy = ModelPolicy(ExactModel(ProviderId.GROQ, "synthetic"))
      foundation.createJob("job", null, "music", "RESEARCH", "FOREGROUND", "objective", policy,
        listOf("g"), listOf(ResearchStep("job", 0, "g", "read")), 10, 100)
      val lease = requireNotNull(foundation.claim("job", "owner", 100, 100))
      val evidence = ResearchEvidence("e", "job", "g", "record", "period", "fluidify.history_records", "value", null,
        0, 100, "UTC", 100, 100, "PARTIAL", "KNOWN", "source", "hash")
      fun write(id: String, field: String) = ProfileWrite(ProfileItem(id, field, "Generated", "INFERENCE", "job", 0,
        generatedProvider = "GROQ", generatedModel = "synthetic", derivation = "v1"), listOf("e"))
      val step = foundation.checkpoint("job").single().copy(outcome = "COMPLETE", fragment = "answer checkpoint")
      assertTrue(foundation.commit(lease, step, listOf(evidence), listOf(write("conflict", "music.artist"), write("valid", "music.other")), 101))
      assertEquals("answer checkpoint", foundation.checkpoint("job").single().fragment)
      assertNull(db.durable().profile("conflict"))
      assertEquals("La mia correzione", db.durable().profile("user")!!.value)
      assertNotNull(db.durable().profile("valid"))
    } finally { db.close() }
  }
}
