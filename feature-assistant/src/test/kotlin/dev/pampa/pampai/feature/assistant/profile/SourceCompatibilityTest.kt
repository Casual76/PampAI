package dev.pampa.pampai.feature.assistant.profile

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.ui.fluid.*
import dev.pampa.pampai.core.assistant.research.SourceCandidate
import dev.pampa.pampai.core.assistant.research.SourceIssue
import dev.pampa.pampai.feature.assistant.theme.PampaiTheme
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class) @Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SourceCompatibilityTest {
  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
  private fun candidate(issue: SourceIssue? = null) = SourceCandidate("dev.pampa.fluidify", "dev.pampa.fluidify.ai.tools",
    "fluidify", if (issue == null) "account-fixture" else null, null, issue == null, issue)
  private fun show(source: SourceCandidate) {
    val vm = mockk<ProfileViewModel>(relaxed = true)
    rule.setContent { PampaiTheme(EngineSettings()) {
      val host = rememberFluidGlassModalHostState()
      CompositionLocalProvider(LocalFluidGlassModalHostState provides host) {
        Box(Modifier.fillMaxSize()) {
          Box(Modifier.fluidGlassModalObscured()) { SourcesScreen(ProfileState(loading = false, candidates = listOf(source)), {}, vm) }
          FluidGlassModalHost(host, backdrop = null)
        }
      }
    } }
  }
  @Test fun installedLegacyAppShowsUpdateAndCannotOpenConnectionForm() {
    show(candidate(SourceIssue.UPDATE_REQUIRED))
    rule.onNodeWithText("Aggiornamento necessario").assertIsDisplayed()
    rule.onNodeWithText("Fluidify").performClick()
    rule.onNodeWithText("Apri app").assertIsDisplayed()
    rule.onNodeWithText("Collega questa sezione").assertDoesNotExist()
    rule.onNodeWithText("Chiudi").performClick()
    rule.onNodeWithText("Aggiornamento necessario").assertIsDisplayed()
  }
  @Test fun compatibleAppOpensExplicitScopeForm() {
    show(candidate())
    rule.onNodeWithText("Fluidify").performClick()
    rule.onAllNodes(hasScrollToNodeAction()).onLast().performScrollToNode(hasText("Account esatto"))
    rule.onNodeWithText("Account esatto").assertIsDisplayed()
    rule.onNodeWithText("Aggiornamento necessario").assertDoesNotExist()
  }
}
