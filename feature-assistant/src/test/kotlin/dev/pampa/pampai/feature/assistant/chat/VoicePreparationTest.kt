package dev.pampa.pampai.feature.assistant.chat

import dev.pampa.pampai.core.assistant.attachments.PendingAttachment
import dev.pampa.pampai.core.assistant.db.AttachmentKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VoicePreparationTest {
  @Test fun stoppedNonCooperativeHelperCannotListenOrConsumeAttachments() = runTest {
    val owner = VoicePreparation(this)
    val late = CompletableDeferred<Unit>()
    var calls = 0
    var listens = 0
    var submits = 0
    val image = PendingAttachment(AttachmentKind.IMAGE, "image/jpeg", "draft.jpg", byteArrayOf(1, 2))
    var attachments = listOf(image)
    val text = "Non inviare"
    repeat(3) { owner.start(
      preflight = { calls++; withContext(NonCancellable) { late.await() } },
      stillOwned = { true },
      listen = { listens++; attachments = emptyList(); submits++ }) }
    runCurrent()
    assertTrue(owner.preparing.value)
    assertEquals(1, calls)
    owner.cancel()
    assertFalse(owner.preparing.value)
    late.complete(Unit)
    runCurrent()
    assertEquals(0, listens)
    assertEquals(0, submits)
    assertEquals(listOf(image), attachments)
    assertEquals("Non inviare", text)
  }

  @Test fun hideOrConversationHandoffInvalidatesOwnershipBeforeListen() = runTest {
    val owner = VoicePreparation(this)
    val late = CompletableDeferred<Unit>()
    var sameConversation = true
    var listens = 0
    owner.start({ late.await() }, { sameConversation }, { listens++ })
    runCurrent()
    sameConversation = false
    late.complete(Unit)
    runCurrent()
    assertEquals(0, listens)
    assertFalse(owner.preparing.value)
  }

  @Test fun oldCompletionDoesNotClearNewPreparationAndNormalAdmissionListensOnce() = runTest {
    val owner = VoicePreparation(this)
    val old = CompletableDeferred<Unit>()
    val next = CompletableDeferred<Unit>()
    var listens = 0
    owner.start({ withContext(NonCancellable) { old.await() } }, { true }, { error("old listen") })
    runCurrent(); owner.cancel()
    owner.start({ next.await() }, { true }, { listens++ })
    runCurrent(); old.complete(Unit); runCurrent()
    assertTrue(owner.preparing.value)
    next.complete(Unit); runCurrent()
    assertEquals(1, listens)
    assertFalse(owner.preparing.value)
  }
}
