package dev.pampa.pampai.core.assistant.research

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.pampa.pampai.core.assistant.db.PampaiDatabase
import dev.pampa.pampai.core.assistant.durable.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class ResearchConnectionsPersistenceTest {
  @Test fun restoredScopeHonorsRevocation() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val name = "rc-${UUID.randomUUID().toString().take(12)}.db"
    fun database() = Room.databaseBuilder(context, PampaiDatabase::class.java, name).build()
    val account = UUID.randomUUID().toString()
    val read = ResearchRead(SourceSelection("synthetic.music", "synthetic.music.tools", "fluidify", account, area = "history_records"),
      "history_records", buildJsonObject { put("account", account); put("from", "0"); put("until", "1000"); put("limit", 50) })
    var db = database()
    try {
      var foundation = DurableFoundation(db)
      foundation.putGrant(SourceGrant(read.grantId, read.selection.packageName, read.selection.authority,
        account, null, "history_records", null, enabled = true))
      ResearchConnections(context, foundation).save(read)
      db.close()
      db = database()
      foundation = DurableFoundation(db)
      val restored = ResearchConnections(context, foundation)
      assertEquals(listOf(read), restored.authorizedReads())
      foundation.revoke(read.grantId)
      assertTrue(restored.authorizedReads().isEmpty())
    } finally { db.close(); context.deleteDatabase(name) }
  }
}
