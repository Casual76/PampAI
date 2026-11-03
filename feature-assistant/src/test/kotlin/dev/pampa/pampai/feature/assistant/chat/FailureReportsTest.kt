package dev.pampa.pampai.feature.assistant.chat

import dev.antigravity.fluidengine.ai.keys.AiSettings
import dev.antigravity.fluidengine.ai.orchestrator.AnswerChip
import dev.antigravity.fluidengine.ai.orchestrator.AskMode
import dev.antigravity.fluidengine.ai.orchestrator.FailureKind
import dev.antigravity.fluidengine.ai.orchestrator.ProviderSwitch
import dev.antigravity.fluidengine.ai.orchestrator.SwitchReason
import dev.antigravity.fluidengine.ai.provider.ModelTier
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.antigravity.fluidengine.aiui.strings.FluidAssistantTexts
import dev.pampa.pampai.core.assistant.db.FailureDetail
import dev.pampa.pampai.core.assistant.db.Message
import dev.pampa.pampai.core.assistant.db.MessageRole
import dev.pampa.pampai.core.assistant.db.MessageStatus
import dev.pampa.pampai.core.assistant.db.Run
import dev.pampa.pampai.core.assistant.db.RunModel
import dev.pampa.pampai.core.assistant.service.AssistantNotifications
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il resoconto del foglio «Dettagli» e le parole di Aria sul kit dell'engine: il resoconto si ricava da
 * una riga di Room e dallo stato vivo (e deve preferire la riga, che ha il codice e il messaggio del
 * servizio), e le frasi di Aria restano al femminile e non ripetono "(modello profondo)".
 */
class FailureReportsTest {

  private fun message(failure: FailureKind? = FailureKind.RATE_LIMITED) =
    Message(2L, 1L, MessageRole.ASSISTANT, "", emptyList<AnswerChip>(), MessageStatus.FAILED, failure, 2_000L, AskMode.TEXT, parentId = 1L)

  private fun run(failure: FailureDetail?, switches: List<ProviderSwitch> = emptyList()) = Run(
    id = 2L, conversationId = 1L, messageId = 2L, startedAtMillis = 0L, finishedAtMillis = 1_000L, steps = 1,
    provider = ProviderId.GROQ, routerModel = null, chatModel = "llama-3.3-70b-versatile", deepModel = null,
    tierReached = ModelTier.CHAT, groups = emptyList(), promptTokens = 0, completionTokens = 0, costUsd = null, waitedSeconds = 0,
    tools = emptyList(), outcome = "failed", error = "HTTP 429", contextTokens = null, contextWindow = null,
    models = listOf(RunModel(ProviderId.GEMINI, ModelTier.CHAT, "gemini-2.5-flash")),
    switches = switches,
    failure = failure,
  )

  @Test
  fun `la riga del run vince sullo stato vivo e porta il codice e il messaggio del servizio`() {
    val detail = FailureDetail(ProviderId.OPENROUTER, SwitchReason.RATE_LIMITED, 429, "Rate limit exceeded")
    val report = failureReportOf(message(), run(detail, listOf(ProviderSwitch(ProviderId.GROQ, ProviderId.GEMINI, SwitchReason.RATE_LIMITED))), live = null)
    assertEquals(FailureKind.RATE_LIMITED, report.kind)
    assertEquals(ProviderId.OPENROUTER, report.provider)
    assertEquals(429, report.httpCode)
    assertEquals("Rate limit exceeded", report.providerMessage)
    assertEquals("HTTP 429", report.technical)
    assertEquals("Groq → Gemini", report.chain)
  }

  @Test
  fun `senza riga ne' stato non si inventa niente`() {
    val report = failureReportOf(message(failure = null), run = null, live = null)
    assertNull(report.kind)
    assertNull(report.provider)
    assertNull(report.httpCode)
    assertTrue(report.switches.isEmpty())
  }

  @Test
  fun `le parole di Aria restano al femminile`() {
    val timeout = FluidAssistantTexts.failure(FailureKind.TIMEOUT, AriaAssistantStrings)
    val transcription = FluidAssistantTexts.failure(FailureKind.TRANSCRIPTION, AriaAssistantStrings)
    assertTrue(timeout, timeout.startsWith("Ci ho messo troppo"))
    assertTrue(transcription, transcription.contains("riuscita"))
    assertEquals("Aria · dettagli dell'errore", AriaAssistantStrings.failureCopyHeader)
  }

  @Test
  fun `la frase di stato del livello profondo non ripete il suffisso`() {
    // `AssistantNotifications.statusFor` dice gia' "(modello profondo)": l'engine non lo aggiunge due volte.
    val status = FluidAssistantTexts.status("grades", ModelTier.DEEP, AriaAssistantStrings)
    assertEquals(AssistantNotifications.statusFor("grades", ModelTier.DEEP), status)
    assertFalse(status, status.contains("(modello profondo) (modello profondo)"))
    assertEquals(1, Regex("modello profondo").findAll(status).count())
  }
}
