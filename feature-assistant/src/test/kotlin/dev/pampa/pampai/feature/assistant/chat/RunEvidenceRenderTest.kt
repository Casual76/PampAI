package dev.pampa.pampai.feature.assistant.chat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.antigravity.fluidengine.ai.provider.*
import dev.antigravity.fluidengine.ai.tools.ToolOutcome
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.pampa.pampai.core.assistant.db.*
import dev.pampa.pampai.core.assistant.runtime.*
import dev.pampa.pampai.core.assistant.tools.PampaiToolTrace
import dev.pampa.pampai.feature.assistant.theme.PampaiTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RunEvidenceRenderTest {
  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
  private fun storedRun() = Run(1, 42, 2, 0, 1000, 1, ProviderId.GROQ,
    null, "primary", null, ModelTier.CHAT, emptyList(), null, null, null,
    0, emptyList(), "ok", null, null, null)

  @Test fun typedErrorAndUncertainNeverRenderOkDespitePositiveProse() {
    val traces = listOf(
      PampaiToolTrace("positive", "", 1, true, 7, "Operazione riuscita", outcome = ToolOutcome.ERROR),
      PampaiToolTrace("done", "", 1, true, 7, "done", outcome = ToolOutcome.UNCERTAIN))
    rule.setContent { PampaiTheme(EngineSettings()) { RunSteps(storedRun().copy(tools = traces)) } }
    rule.onNodeWithContentDescription("Mostra i passi").performClick()
    rule.onNodeWithText("positive", substring = true).assertTextContains("errore", substring = true)
    rule.onNode(hasText("done", substring = true) and hasText("esito incerto", substring = true)).assertTextContains("esito incerto", substring = true)
    rule.onAllNodesWithText("ok", substring = true).assertCountEquals(0)
    assertEquals(5, ToolOutcome.entries.map { traceOutcomeLabel(traces[0].copy(outcome = it)) }.distinct().size)
    assertEquals("ok (traccia precedente)", traceOutcomeLabel(traces[0].copy(outcome = null)))
  }

  @Test fun storedCachedHelperSurvivesCurrentConfigurationChangeAndReopen() {
    val stored = storedRun().copy(imageInterpretations = listOf(RunImageInterpretation(ProviderId.GEMINI, "original-vision", true)))
    var current by mutableStateOf(ModelPolicy(ExactModel(ProviderId.GROQ, "primary"), helper = ExactModel(ProviderId.GEMINI, "original-vision")))
    var open by mutableStateOf(true)
    rule.setContent { PampaiTheme(EngineSettings()) {
      // Reading a changing current picker state must not affect stored attempt attribution.
      key(current) { if (open) RunSteps(stored) }
    } }
    rule.onNodeWithText("Supporto visivo:", substring = true).assertTextContains("original-vision", substring = true)
    rule.runOnIdle { current = current.copy(helper = null); open = false }
    rule.runOnIdle { open = true }
    rule.onNodeWithContentDescription("Mostra i passi").performClick()
    rule.onNodeWithText("ha interpretato le immagini", substring = true)
      .assertTextContains("original-vision", substring = true)
      .assertTextContains("riutilizzata dalla cache", substring = true)
      .assertTextContains("al posto dei pixel", substring = true)
      .assertTextContains("descrizione preparata", substring = true)
    rule.onNodeWithText("Ho risposto da sola", substring = true).assertDoesNotExist()
  }

  @Test fun textOnlyStoredRunHasNoHelperAttribution() {
    rule.setContent { PampaiTheme(EngineSettings()) { RunSteps(storedRun()) } }
    rule.onNodeWithText("Ho risposto da sola").assertExists()
    rule.onNodeWithText("Supporto visivo:", substring = true).assertDoesNotExist()
  }

  @Test fun failedPreparationDisclosesPreparedInterpretationWithoutClaimingDelivery() {
    val stopped = storedRun().copy(outcome = "failed", imageInterpretations = listOf(
      RunImageInterpretation(ProviderId.GEMINI, "prepared-before-next-image-failed", false)))
    rule.setContent { PampaiTheme(EngineSettings()) { RunSteps(stopped) } }
    rule.onNodeWithContentDescription("Mostra i passi").performClick()
    rule.onNodeWithText("ha interpretato le immagini", substring = true)
      .assertTextContains("descrizione preparata", substring = true)
    rule.onAllNodesWithText("ha ricevuto", substring = true).assertCountEquals(0)
  }
}
