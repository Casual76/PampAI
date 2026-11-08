package dev.pampa.pampai.feature.assistant.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.antigravity.fluidengine.ai.keys.*
import dev.antigravity.fluidengine.ai.net.AiHttp
import dev.antigravity.fluidengine.ai.orchestrator.AskMode
import dev.antigravity.fluidengine.ai.provider.*
import dev.pampa.pampai.core.assistant.attachments.PendingAttachment
import dev.pampa.pampai.core.assistant.db.*
import dev.pampa.pampai.core.assistant.runtime.*
import dev.pampa.pampai.core.assistant.di.DatabaseModule
import dev.pampa.pampai.core.assistant.usage.UsageRepository
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual boundary invoked by ChatViewModel.send, real shared runtime preflight and Room replay.
 * No duplicated image boolean, helper implementation or network provider in the fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SendAdmissionTest {
  @Test fun oldVisibleImageDoesNotBlockTextU3ButIncludedImagesStillBlock() = runBlocking {
    Dispatchers.setMain(Dispatchers.Unconfined)
    val context: Context = ApplicationProvider.getApplicationContext()
    val db = dev.pampa.pampai.core.assistant.di.DatabaseModule.provideDatabase(context, DatabaseRecovery(context))
    val repo = ConversationsRepository(context, DatabaseModule.conversations(db), DatabaseModule.messages(db), DatabaseModule.attachments(db), DatabaseModule.runs(db), Json { ignoreUnknownKeys = true; encodeDefaults = true }, db)
    val settings = AiSettingsStore(context)
    val keys = AiKeyStore(context)
    val catalog = ModelCatalogStore(File(context.cacheDir, "send-admission-fixture"))
    var usageCalls = 0
    val usageDao = Proxy.newProxyInstance(UsageEventDao::class.java.classLoader, arrayOf(UsageEventDao::class.java)) { _, _, _ -> usageCalls++; error("No helper/model calls allowed") } as UsageEventDao
    val selection = ModelSelection(context, settings, ProviderFactory(AiHttp("fixture"), keys, settings, "fixture", "fixture", catalog), keys, catalog, UsageRepository(usageDao), RuntimeBudgetCoordinator(dev.pampa.pampai.core.assistant.durable.BudgetLedger(db), db), repo)
    withTimeout(10_000) { selection.loaded.first { it } }
    try {
      for (capability in listOf(ImageCapability.NO, ImageCapability.UNKNOWN)) {
        val identity = ExactModel(ProviderId.GROQ, "text-only-${capability.name}")
        val policy = ModelPolicy(identity)
        selection.state.value = ModelPickerState(options = listOf(ModelOption(identity, "Text", capability)), last = policy)
        val image = PendingAttachment(AttachmentKind.IMAGE, "image/jpeg", "U1.jpg", byteArrayOf(1, 2, 3))
        val id = repo.createConversation("Admission fixture", 1)
        val u1 = repo.startTurn(id, Anchor.Continue, "U1 image", AskMode.TEXT, listOf(image), 2)
        repo.complete(u1.assistantId, "A1", emptyList())
        val u2 = repo.startTurn(id, Anchor.Continue, "U2 text", AskMode.TEXT, emptyList(), 3)
        repo.complete(u2.assistantId, "A2", emptyList())
        assertTrue(repo.observeMessages(id).first().any { it.attachments.isNotEmpty() })
        val boundary = SendAdmission { request -> preflightAssistantRequest(request, repo, selection) }
        val request = AssistantRequest(id, "U3 text", AskMode.TEXT, modelPolicy = policy)
        assertTrue(boundary.admit(request) { true })
        assertFalse(selection.imageChoiceNeeded.value)
        val queued = QueuedQuestion(request.question, policy = policy, conversationId = id, request = request)
        assertSame(request, queued.asRequest())
        assertEquals(identity, queued.asRequest().modelPolicy?.identity)
        assertNull(queued.asRequest().modelPolicy?.helper)
        for (included in listOf(request.copy(attachments = listOf(image)), request.copy(anchor = Anchor.Regenerate(u2.assistantId)))) {
          try { boundary.admit(included) { true }; fail("Included image admitted for $capability") }
          catch (_: IllegalStateException) { assertTrue(selection.imageChoiceNeeded.value) }
          selection.imageChoiceNeeded.value = false
        }
        assertEquals(0, usageCalls)
        assertEquals(policy, selection.state.value.selected(id))
      }
    } finally {
      // Room is a core implementation dependency. Use its public factory/DAO providers above;
      // close reflectively without adding Room to the feature compilation dependencies.
      (db as Any).javaClass.getMethod("close").invoke(db)
      Dispatchers.resetMain()
    }
  }
}
