package dev.pampa.pampai.core.assistant.research

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import dev.antigravity.fluidengine.ai.bridge.*
import dev.antigravity.fluidengine.ai.tools.*
import dev.pampa.pampai.core.assistant.durable.DurableFoundation
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Discovery exposes installed-but-incompatible apps without authorizing private reads. */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class SourceDiscoveryTest {
  private val clock = object : ResearchClock { override fun wall() = 1L; override fun tick() = 1L }
  private val music = RemoteHost("dev.pampa.fluidify", "dev.pampa.fluidify.ai.tools", "Fluidify")
  private val school = RemoteHost("dev.antigravity.classevivaexpressive", "dev.antigravity.classevivaexpressive.ai.tools", "ClasseViva")
  private class Transport(val entries: List<RemoteHost>) : ResearchTransport {
    var admission: suspend (RemoteHost) -> Boolean = { true }
    var account: String? = "account-fixture"
    val calls = mutableListOf<String>()
    override suspend fun hosts() = entries
    override suspend fun typed(host: RemoteHost) = admission(host)
    override suspend fun call(host: RemoteHost, tool: String, args: JsonObject): RemoteResult.Done {
      calls += tool
      check(tool == "source_capabilities") // Discovery must not acquire a lease or read records.
      val data = buildJsonObject {
        put("contract", "aria.source-proof"); put("version", 1); put("authority", host.authority)
        put("domain", ResearchHostPolicy.domain(host)); put("incarnation", "fixture"); put("authorization", "fixture")
        put("account", account?.let(::JsonPrimitive) ?: JsonNull)
      }
      return RemoteResult.Done("", false, emptyList(), ToolResultDetails(ToolOutcome.COMPLETED, data = data))
    }
  }
  private fun sources(transport: Transport) = ResearchSources(transport, mockk<DurableFoundation>(),
    VerifiedSourceReceiptCache(clock), clock, mockk<ResearchConnections>())

  @Test fun legacyAppRemainsVisibleBesideCompatibleSource() = runBlocking {
    val transport = Transport(listOf(music, school)).apply { admission = { it != music } }
    val found = sources(transport).discover()
    assertEquals(2, found.size)
    assertFalse(found[0].supported); assertEquals(SourceIssue.UPDATE_REQUIRED, found[0].issue)
    assertTrue(found[1].supported); assertEquals("account-fixture", found[1].account)
    assertEquals(listOf("source_capabilities"), transport.calls)
  }

  @Test fun signatureFailureIsVisibleAndDoesNotReadCapabilities() = runBlocking {
    val transport = Transport(listOf(music)).apply { admission = { throw ResearchUnavailable("UNTRUSTED_SOURCE") } }
    assertEquals(SourceIssue.SIGNATURE_MISMATCH, sources(transport).discover().single().issue)
    assertTrue(transport.calls.isEmpty())
  }

  @Test fun signedOutAndUnresponsiveAppsRemainDistinct() = runBlocking {
    val transport = Transport(listOf(music)).apply { account = null }
    val found = sources(transport).discover().single()
    assertFalse(found.supported); assertEquals(SourceIssue.ACCOUNT_REQUIRED, found.issue)
    transport.admission = { throw ResearchUnavailable("SOURCE_PROVIDER_UNAVAILABLE") }
    assertEquals(SourceIssue.NOT_READY, sources(transport).discover().single().issue)
  }

  @Test fun cancellationDoesNotBecomeAnUnavailableApp() = runBlocking {
    val transport = Transport(listOf(music)).apply { admission = { throw CancellationException("stop") } }
    try { sources(transport).discover(); fail("Cancellation swallowed") } catch (_: CancellationException) { }
  }

  @Test fun installedAppWithoutDiscoveryMetadataIsListedWithoutInventingAProvider() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val info = PackageInfo().apply {
      packageName = music.packageName
      applicationInfo = ApplicationInfo().apply { packageName = music.packageName; enabled = true }
    }
    shadowOf(context.packageManager).installPackage(info)
    val transport = SignedResearchTransport(context)
    assertEquals(music.authority, transport.hosts().single { it.packageName == music.packageName }.authority)
    assertNull(context.packageManager.resolveContentProvider(music.authority, 0))
    try { transport.typed(music); fail("Missing provider admitted") }
    catch (e: ResearchUnavailable) { assertTrue(e.code in setOf("UNTRUSTED_SOURCE", "SOURCE_PROVIDER_UNAVAILABLE")) }
  }
}
