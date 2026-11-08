package dev.pampa.pampai.feature.assistant.chat

import dev.antigravity.fluidengine.ai.orchestrator.AskMode
import dev.pampa.pampai.core.assistant.db.Anchor
import dev.pampa.pampai.core.assistant.runtime.AssistantRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TextAdmissionOwnershipTest {
  @Test fun suspendedEditAndRegenerationCannotDispatchAfterNavigationEvenBackToSameChat() = runTest {
    for (anchor in listOf(Anchor.Edit(1), Anchor.Regenerate(2))) {
      var epoch = 0
      var conversation = 10L
      val capturedEpoch = epoch
      val capturedConversation = conversation
      val helper = CompletableDeferred<Unit>()
      var cancelOrSubmit = 0
      val pending = async {
        SendAdmission { helper.await() }.dispatch(AssistantRequest(10, "A", AskMode.TEXT, anchor = anchor), {
          epoch == capturedEpoch && conversation == capturedConversation
        }) { cancelOrSubmit++; true }
      }
      runCurrent()
      conversation = 20L; epoch++ // B owns a new request now
      conversation = 10L; epoch++ // same id alone cannot revive the old composer
      helper.complete(Unit)
      assertFalse(pending.await())
      assertEquals(0, cancelOrSubmit)
    }
  }

  @Test fun preparingQueuedItemRemainsRecoverableAndLateHelperCannotCancelB() = runTest {
    val a = QueuedQuestion("A", conversationId = 10)
    val queue = MutableStateFlow(listOf(a))
    var epoch = 0
    var conversation = 10L
    var busy = false
    var dispatches = 0
    var restored = emptyList<QueuedQuestion>()
    val helper = CompletableDeferred<Unit>()
    val boundary = QueuedSendAdmission(this, queue, { withContext(NonCancellable) { helper.await() } },
      { !busy }, {
        val captured = epoch; val owner = conversation
        val owns = { captured == epoch && conversation == owner }; owns
      }, { dispatches++; true }, { _, error -> throw AssertionError(error) })
    boundary.flush(); runCurrent()
    assertEquals(listOf(a), queue.value) // not dropped while helper is suspended
    boundary.flush(); runCurrent() // repeated idle events do not create another preparation
    boundary.cancel()
    epoch++; conversation = 20L; busy = true
    restored = restored + queue.value; queue.value = emptyList() // real releaseQueue order
    helper.complete(Unit); runCurrent()
    assertEquals(listOf(a), restored)
    assertTrue(queue.value.isEmpty())
    assertEquals(0, dispatches)
  }

  @Test fun anotherBusyRequestKeepsPreparedQueueAndLaterFlushDispatchesExactlyOnce() = runTest {
    val a = QueuedQuestion("A", conversationId = 10)
    val queue = MutableStateFlow(listOf(a))
    val helper = CompletableDeferred<Unit>()
    var busy = false
    var dispatches = 0
    val boundary = QueuedSendAdmission(this, queue, { helper.await() }, { !busy }, { { true } },
      { assertEquals(a, it); dispatches++; true }, { _, error -> throw AssertionError(error) })
    boundary.flush(); runCurrent()
    busy = true; helper.complete(Unit); runCurrent()
    assertEquals(listOf(a), queue.value)
    assertEquals(0, dispatches)
    busy = false; boundary.flush(); runCurrent()
    assertTrue(queue.value.isEmpty())
    assertEquals(1, dispatches)
  }

  @Test fun stoppedNonCooperativePreparationCannotRemoveAReplacementQueueItem() = runTest {
    val a = QueuedQuestion("A", conversationId = 10)
    val b = QueuedQuestion("B", conversationId = 20)
    val queue = MutableStateFlow(listOf(a))
    val oldHelper = CompletableDeferred<Unit>()
    var owner = 10L
    var calls = 0
    val dispatched = mutableListOf<QueuedQuestion>()
    val boundary = QueuedSendAdmission(this, queue, {
      if (++calls == 1) withContext(NonCancellable) { oldHelper.await() }
    }, { true }, { val captured = owner; val owns = { owner == captured }; owns },
      { dispatched += it; true }, { _, error -> throw AssertionError(error) })
    boundary.flush(); runCurrent()
    boundary.cancel(); owner = 20L; queue.value = listOf(b)
    boundary.flush(); runCurrent()
    oldHelper.complete(Unit); runCurrent()
    assertEquals(listOf(b), dispatched)
    assertTrue(queue.value.isEmpty())
  }
}
