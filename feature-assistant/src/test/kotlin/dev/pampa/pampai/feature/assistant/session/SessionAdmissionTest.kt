package dev.pampa.pampai.feature.assistant.session

import android.graphics.Bitmap
import dev.antigravity.fluidengine.ai.orchestrator.AssistantState
import dev.pampa.pampai.core.assistant.db.ConversationsRepository
import dev.pampa.pampai.core.assistant.runtime.*
import dev.pampa.pampai.core.assistant.screen.ScreenContextStore
import dev.pampa.pampai.core.assistant.tools.Surface
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real SessionController admission, including the runtime callback and attachment consumption. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class SessionAdmissionTest {
  @Test fun newerRuntimeOwnerRejectsLateOverlayWithoutConsumingDraftOrImage() = exercise("newer")
  @Test fun alreadyBusyOtherConversationIsNeverReplacedByOverlay() = exercise("busy")
  @Test fun stopDuringPreflightCannotSubmitOrConsumeAttachments() = exercise("stop")
  @Test fun hiddenSessionCannotDispatchAfterPreflight() = exercise("hidden")
  @Test fun unchangedIdleOwnerAdmitsOnceAndConsumesCapturedImage() = exercise("admit")

  private fun exercise(mode: String) = runTest {
    val runtime = mockk<AssistantRuntime>(relaxed = true)
    val selection = mockk<ModelSelection>(relaxed = true)
    val conversations = mockk<ConversationsRepository>(relaxed = true)
    val state = MutableStateFlow<AssistantState>(AssistantState.Idle)
    val owner = MutableStateFlow(LiveOwner(Surface.APP, 20))
    val conversation = MutableStateFlow<Long?>(10)
    val gate = CompletableDeferred<Unit>()
    var generation = 3L
    var busy = mode == "busy"
    var dispatches = 0
    every { runtime.state } returns state
    every { runtime.liveOwner } returns owner
    every { runtime.sessionConversationId } returns conversation
    every { runtime.voiceEvents } returns MutableSharedFlow()
    every { runtime.modelSelection } returns selection
    every { runtime.requestGeneration } answers { generation }
    every { runtime.isBusy } answers { busy }
    every { selection.state } returns MutableStateFlow(ModelPickerState())
    every { selection.loaded } returns MutableStateFlow(true)
    every { conversations.observeMessages(any()) } returns flowOf(emptyList())
    every { conversations.observeRuns(any()) } returns flowOf(emptyList())
    coEvery { runtime.preflight(any()) } coAnswers { gate.await() }
    every { runtime.submitIfCurrent(any(), any(), any()) } answers {
      val admitted = secondArg<Long>() == generation && (!busy || thirdArg<Boolean>())
      if (admitted) { dispatches++; generation++; busy = true }
      admitted
    }
    val screen = ScreenContextStore()
    val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
    screen.setScreenshot(bitmap)
    val controller = SessionController(runtime, conversations, screen, backgroundScope)
    controller.onShow(startVoice = false)
    controller.draft.value = "overlay draft"
    assertTrue(controller.attachScreen())
    val original = controller.attachments.value.single()
    val pending = async { controller.askPrepared("A") }
    runCurrent()
    when (mode) {
      "newer" -> { generation++; busy = true }
      "stop" -> { controller.cancel(); generation++ }
      "hidden" -> controller.onHide()
    }
    gate.complete(Unit)
    assertEquals(mode == "admit", pending.await())
    assertEquals(if (mode == "admit") 1 else 0, dispatches)
    assertEquals("overlay draft", controller.draft.value)
    if (mode == "admit") assertTrue(controller.attachments.value.isEmpty())
    else assertEquals(listOf(original), controller.attachments.value)
    verify(exactly = 0) { runtime.submit(any()) }
    if (mode in setOf("hidden", "stop")) verify(exactly = 0) { runtime.submitIfCurrent(any(), any(), any()) }
    else verify(exactly = 1) { runtime.submitIfCurrent(any(), 3L, false) }
    bitmap.recycle()
  }
}
