package dev.pampa.pampai.feature.assistant.chat

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.navigation.FluidNavHost
import dev.antigravity.fluidengine.ui.navigation.composable
import dev.antigravity.fluidengine.ui.page.FluidPageHost
import dev.antigravity.fluidengine.ui.page.FluidPageHostState
import dev.antigravity.fluidengine.ui.page.rememberFluidPageHostState
import dev.pampa.pampai.feature.assistant.*
import dev.pampa.pampai.feature.assistant.settings.SettingsPage
import dev.pampa.pampai.feature.assistant.settings.SettingsPageContent
import dev.pampa.pampai.feature.assistant.theme.PampaiTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real Fluid hosts, Android back dispatcher and pointer hit tests; external app services excluded. */
@RunWith(RobolectricTestRunner::class) @GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
class NavigationInputTest {
  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
  private lateinit var motion: FluidPageHostState
  private var stack by mutableStateOf(listOf("root"))
  private val actions = mutableListOf<String>()
  private val backs = mutableListOf<String>()
  private fun pages() {
    rule.setContent { PampaiTheme(EngineSettings()) {
      motion = rememberFluidPageHostState()
      FluidPageHost(stack, onBack = { stack = stack.dropLast(1) }, state = motion, modifier = Modifier.fillMaxSize()) { page ->
        FluidScreen(page, onBack = if (page == "root") null else ({ backs += page; stack = stack.dropLast(1) }),
          actions = { FluidButton("Azione $page", { actions += page }, Modifier.testTag("action-$page")) }) {
          if (page == "root") item { FluidButton("Apri pagina", { stack = listOf("root", "one") }) }
        }
      }
    } }
    rule.waitForIdle()
    rule.onNodeWithTag("action-root").assertIsDisplayed()
  }
  @Test fun destinationActionAcceptsPointerWhilePushIsStillMoving() {
    pages()
    rule.mainClock.autoAdvance = false
    rule.onNodeWithText("Apri pagina").performTouchInput { click(center) }
    rule.waitForIdle()
    rule.mainClock.advanceTimeUntil(timeoutMillis = 1_000) { motion.isMoving }
    rule.mainClock.advanceTimeBy(32)
    rule.runOnIdle { assertTrue("Expected push: kind=${motion.kind}, from=${motion.from}, to=${motion.to}, stack=$stack", motion.isMoving) }
    rule.onNodeWithTag("action-one").performTouchInput { click(center) }
    rule.runOnIdle { assertEquals(listOf("one"), actions); assertTrue(motion.isMoving) }
    rule.onNodeWithTag("action-root").assertDoesNotExist()
    rule.mainClock.autoAdvance = true
  }
  @Test fun popActionAndRepeatedBackTargetCurrentDestinationBeforeSpringSettles() {
    pages()
    rule.runOnIdle { stack = listOf("root", "one", "two") }
    rule.waitForIdle()
    rule.mainClock.autoAdvance = false
    rule.onNodeWithContentDescription("Indietro").performTouchInput { click(center) }
    rule.mainClock.advanceTimeBy(64)
    rule.runOnIdle { assertEquals(listOf("root", "one"), stack); assertTrue(motion.isMoving) }
    rule.onNodeWithTag("action-one").performTouchInput { click(center) }
    rule.runOnIdle { assertEquals(listOf("one"), actions) }
    rule.onNodeWithTag("action-two").assertDoesNotExist()
    rule.onNodeWithContentDescription("Indietro").performTouchInput { click(center) }
    rule.mainClock.advanceTimeBy(64)
    rule.runOnIdle { assertEquals(listOf("root"), stack); assertEquals(listOf("two", "one"), backs) }
    rule.onNodeWithContentDescription("Indietro").assertDoesNotExist()
    rule.mainClock.autoAdvance = true
  }

  @Test fun settingsPredictiveBackFollowsFingerCancelsThenCommitsWithoutLosingDraft() {
    lateinit var nav: NavHostController
    rule.setContent { PampaiTheme(EngineSettings()) {
      nav = rememberNavController(); motion = rememberFluidPageHostState()
      FluidNavHost(nav, Routes.Home, state = motion) {
        composable(Routes.Home) {
          var draft by remember { mutableStateOf("Bozza intatta") }
          dev.antigravity.fluidengine.ui.fluid.FluidTextField(draft, { draft = it }, label = "Bozza")
        }
        composable(Routes.SettingsWithSection,
          arguments = listOf(navArgument("section") { type = NavType.StringType; nullable = true; defaultValue = null })) { entry ->
          SettingsPageContent(SettingsPage.fromSection(entry.arguments?.getString("section")),
            onBack = { nav.backFromSettings() }, onOpenSection = nav::openSettingsSection, onOpenMemory = {}, onOpenUsage = {})
        }
      }
    } }
    rule.runOnIdle { nav.openSettingsSection("chiavi") }
    rule.waitForIdle(); rule.mainClock.autoAdvance = false
    val dispatcher = rule.activity.onBackPressedDispatcher
    fun begin() = dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 300f, 0f, BackEventCompat.EDGE_LEFT))
    fun move() = dispatcher.dispatchOnBackProgressed(BackEventCompat(180f, 300f, .35f, BackEventCompat.EDGE_LEFT))
    rule.runOnIdle { begin(); move(); assertTrue(motion.isInteractive); assertTrue(motion.progress > 0f) }
    rule.runOnIdle { dispatcher.dispatchOnBackCancelled(); assertEquals("chiavi", nav.currentBackStackEntry?.arguments?.getString("section")) }
    rule.mainClock.autoAdvance = true; rule.waitForIdle()
    rule.runOnIdle { assertFalse(motion.isMoving) }
    rule.mainClock.autoAdvance = false
    rule.runOnIdle { begin(); move(); dispatcher.onBackPressed() }
    rule.mainClock.autoAdvance = true; rule.waitForIdle()
    rule.runOnIdle { assertNull(nav.currentBackStackEntry?.arguments?.getString("section")); dispatcher.onBackPressed() }
    rule.onNodeWithText("Bozza intatta").assertIsDisplayed()
  }
}
