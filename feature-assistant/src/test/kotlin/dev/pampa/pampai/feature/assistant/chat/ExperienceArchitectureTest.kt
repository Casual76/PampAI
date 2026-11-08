package dev.pampa.pampai.feature.assistant.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import dev.antigravity.fluidengine.ui.navigation.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.aiui.settings.*
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.antigravity.fluidengine.ui.fluid.*
import dev.antigravity.fluidengine.ui.navigation.FluidNavHost
import dev.pampa.pampai.feature.assistant.*
import dev.pampa.pampai.feature.assistant.history.DrawerWorkspace
import dev.pampa.pampai.feature.assistant.settings.*
import dev.pampa.pampai.feature.assistant.session.SessionModalRoot
import dev.pampa.pampai.feature.assistant.theme.PampaiTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Production navigation operations, composer, category shell and drawer layout, without Hilt/network. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
class ExperienceArchitectureTest {
  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  @Test fun composerKeysRecoveryAndCategoryBackPreserveDraftAndViewportWithoutSend() {
    lateinit var nav: NavHostController
    lateinit var draft: ComposerState
    lateinit var viewport: androidx.compose.foundation.lazy.LazyListState
    var sends = 0
    rule.setContent { PampaiTheme(EngineSettings()) {
      nav = rememberNavController()
      var seeded by remember { mutableStateOf(false) }
      FluidNavHost(navController = nav, startDestination = Routes.Home) {
        composable(Routes.Home) {
          draft = rememberComposerState()
          viewport = rememberLazyListState()
          LaunchedEffect(Unit) { if (!seeded) { draft.text = "Bozza da ritrovare"; seeded = true } }
          Column {
            LazyColumn(Modifier.weight(1f), state = viewport) {
              items(100) { Text("Messaggio $it") }
            }
            Composer(rememberGlassBackdrop(), ChatUiState(), draft,
              actions = ComposerActions(onOpenSettings = { nav.openSettingsSection("chiavi") },
                onSend = { sends++; true }))
          }
        }
        composable(Routes.SettingsWithSection,
          arguments = listOf(navArgument("section") { type = NavType.StringType; nullable = true; defaultValue = null })) { entry ->
          val page = SettingsPage.fromSection(entry.arguments?.getString("section"))
          SettingsPageContent(page = page, onBack = { nav.backFromSettings() },
            onOpenSection = nav::openSettingsSection, onOpenMemory = {}, onOpenUsage = {}) {
            if (page == SettingsPage.KEYS) fluidAiSettingsSection(
              state = FluidAiSettingsState(),
              actions = FluidAiSettingsActions(saveAndVerify = { _, _ -> error("No live verification") }, removeKey = {}, setEnabled = {}),
              parts = setOf(FluidAiSettingsPart.Keys, FluidAiSettingsPart.Privacy))
          }
        }
      }
    } }
    rule.runOnIdle { viewport.requestScrollToItem(20, 7) }
    rule.waitForIdle()
    rule.onNodeWithText("Aggiungi una chiave").performClick()
    rule.onNode(hasText("Chiavi e privacy") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertIsDisplayed()
    rule.onNodeWithText("Aspetto").assertDoesNotExist()
    rule.runOnIdle { assertEquals("chiavi", nav.currentBackStackEntry?.arguments?.getString("section")); nav.backFromSettings() }
    rule.onNodeWithText("AI e modelli").assertIsDisplayed()
    rule.runOnIdle { nav.backFromSettings() }
    rule.onNodeWithText("Bozza da ritrovare").assertIsDisplayed()
    rule.runOnIdle {
      assertEquals(Routes.Home, nav.currentBackStackEntry?.destination?.route)
      assertEquals(20, viewport.firstVisibleItemIndex)
      assertEquals(7, viewport.firstVisibleItemScrollOffset)
      assertEquals("Bozza da ritrovare", draft.text)
      assertEquals(0, sends)
    }
  }

  @Test fun overlaySettingsHandoffImportsBeforeCategoryAndBackKeepsUnsentPixelsAndPolicy() {
    val policy = dev.pampa.pampai.core.assistant.runtime.ModelPolicy(
      dev.pampa.pampai.core.assistant.runtime.ExactModel(dev.antigravity.fluidengine.ai.provider.ProviderId.GROQ, "exact"))
    val image = dev.pampa.pampai.core.assistant.attachments.PendingAttachment(
      dev.pampa.pampai.core.assistant.db.AttachmentKind.IMAGE, "image/jpeg", "screen.jpg", byteArrayOf(1, 2))
    val token = dev.pampa.pampai.feature.assistant.session.DraftHandoffs.put(
      dev.pampa.pampai.feature.assistant.session.DraftHandoff(42, "Non inviare", listOf(image), policy))
    val request = EntryRequest(handoffToken = token, conversationId = 42, settingsSection = "permessi")
    lateinit var nav: NavHostController
    lateinit var draft: ComposerState
    var imported: dev.pampa.pampai.feature.assistant.session.DraftHandoff? = null
    var sends = 0
    rule.setContent { PampaiTheme(EngineSettings()) {
      nav = rememberNavController()
      FluidNavHost(navController = nav, startDestination = Routes.Home) {
        composable(Routes.Home) {
          draft = rememberComposerState()
          Composer(rememberGlassBackdrop(), ChatUiState(attachments = imported?.attachments.orEmpty()), draft,
            actions = ComposerActions(onSend = { sends++; true }))
        }
        composable(Routes.SettingsWithSection,
          arguments = listOf(navArgument("section") { type = NavType.StringType; nullable = true; defaultValue = null })) { entry ->
          SettingsPageContent(SettingsPage.fromSection(entry.arguments?.getString("section")), onBack = { nav.backFromSettings() },
            onOpenSection = nav::openSettingsSection, onOpenMemory = {}, onOpenUsage = {})
        }
      }
    } }
    val claim: (String, Long?) -> Boolean = { value, id ->
      dev.pampa.pampai.feature.assistant.session.DraftHandoffs.consume(value, id)?.let {
        imported = it; draft.fill(it.text); true
      } ?: false
    }
    rule.runOnIdle {
      assertTrue(importEntryHandoff(request, claim) { section ->
        assertNotNull(imported) // routing must follow import
        nav.openSettingsSection(section)
      })
      assertEquals("permessi", nav.currentBackStackEntry?.arguments?.getString("section"))
    }
    rule.onNode(hasText("Permessi") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertIsDisplayed()
    rule.runOnIdle { nav.backFromSettings() }
    rule.onNodeWithText("AI e modelli").assertIsDisplayed()
    rule.runOnIdle { nav.backFromSettings() }
    rule.onNodeWithText("Non inviare").assertIsDisplayed()
    rule.runOnIdle {
      assertEquals(policy, imported?.policy)
      assertArrayEquals(image.bytes, imported!!.attachments.single().bytes)
      assertFalse(importEntryHandoff(request, claim) { error("stale recreation routed") })
      assertEquals(0, sends)
      nav.openSettingsSection("unknown-category")
      assertNull(nav.currentBackStackEntry?.arguments?.getString("section"))
    }
  }

  @Test fun drawerSearchKeepsNewChatAndGlobalNavigationPositions() {
    rule.setContent { PampaiTheme(EngineSettings()) {
      var query by remember { mutableStateOf("") }
      DrawerWorkspace(query, { query = it }, {}, {}, {}) {
        LazyColumn(Modifier.weight(1f)) {
          if (query.isEmpty()) items(70) { Text("Conversazione $it") }
          else item { Text("Nessun risultato") }
        }
      }
    } }
    val labels = listOf("Nuova chat", "Il tuo profilo", "Impostazioni")
    val before = labels.map { rule.onNodeWithText(it).fetchSemanticsNode().boundsInRoot }
    rule.onNode(hasSetTextAction()).performTextInput("ricerca senza risultato")
    rule.onNodeWithText("Nessun risultato").assertIsDisplayed()
    labels.forEachIndexed { index, label ->
      assertEquals(before[index], rule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot)
    }
  }

  @Test fun attachmentMenuSeparatesConversationAndHasNoCompetingEffort() {
    var temporaryStarts = 0
    rule.setContent { PampaiTheme(EngineSettings()) {
      SessionModalRoot(1L) {
        Composer(rememberGlassBackdrop(), ChatUiState(), remember { ComposerState("Conserva") },
          actions = ComposerActions(onToggleTemporary = { temporaryStarts++ }))
      }
    } }
    rule.onNodeWithContentDescription("Allega o scegli").performClick()
    rule.onNodeWithText("Allegati").assertIsDisplayed()
    rule.onNodeWithText("Fotocamera").assertIsDisplayed()
    rule.onNodeWithText("Conversazione").assertIsDisplayed()
    rule.onNodeWithText("Contesto e plugin").assertIsDisplayed()
    rule.onNodeWithText("Pensa piu' a fondo").assertDoesNotExist()
    rule.onNodeWithText("Nuova chat temporanea").performClick()
    rule.runOnIdle { assertEquals(1, temporaryStarts) }
  }

  private fun hubShot(name: String, mode: ThemeMode, fontScale: Float = 1f) {
    rule.setContent { PampaiTheme(EngineSettings(themeMode = mode, dynamicColorEnabled = false)) {
      CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
        SettingsPageContent(SettingsPage.HUB, onBack = {}, onOpenSection = {}, onOpenMemory = {}, onOpenUsage = {})
      }
    } }
    rule.waitForIdle()
    val out = File(System.getenv("SCREENSHOT_DIR") ?: "build/screenshots").apply { mkdirs() }
    rule.onRoot().captureRoboImage(File(out, name + ".png").absolutePath)
  }
  @Test fun experienceHubLight() = hubShot("experience-hub-light", ThemeMode.LIGHT)
  @Test fun experienceHubDark() = hubShot("experience-hub-dark", ThemeMode.DARK)
  @Test fun experienceHubLargeFont() = hubShot("experience-hub-large-font", ThemeMode.LIGHT, 1.8f)
}
