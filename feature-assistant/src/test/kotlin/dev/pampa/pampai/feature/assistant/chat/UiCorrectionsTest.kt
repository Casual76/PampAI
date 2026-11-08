package dev.pampa.pampai.feature.assistant.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.*
import androidx.compose.ui.platform.LocalDensity
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import dev.antigravity.fluidengine.ai.keys.*
import dev.antigravity.fluidengine.ai.net.AiHttp
import dev.antigravity.fluidengine.ai.provider.*
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.ui.fluid.*
import dev.pampa.pampai.core.assistant.runtime.*
import dev.pampa.pampai.core.assistant.db.UsageEventDao
import dev.pampa.pampai.core.assistant.usage.UsageRepository
import dev.pampa.pampai.feature.assistant.session.SessionModalRoot
import dev.pampa.pampai.feature.assistant.theme.PampaiTheme
import java.lang.reflect.Proxy
import java.io.File
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.foundation.ThemeMode
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercises the production session root and real ModelSelection; no provider/key/device calls. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
class UiCorrectionsTest {
  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
  private val model = ExactModel(ProviderId.GROQ, "text-fixture")
  private val vision = ExactModel(ProviderId.GEMINI, "vision-fixture")

  private fun selection(): ModelSelection {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val settings = AiSettingsStore(context)
    val keys = AiKeyStore(context) // empty store; never verify/decrypt/provide a credential
    val catalog = ModelCatalogStore(File(context.cacheDir, "picker-fixture"))
    val dao = Proxy.newProxyInstance(UsageEventDao::class.java.classLoader, arrayOf(UsageEventDao::class.java)) { _, _, _ -> error("No usage expected") } as UsageEventDao
    val db = dev.pampa.pampai.core.assistant.di.DatabaseModule.provideDatabase(context, dev.pampa.pampai.core.assistant.db.DatabaseRecovery(context))
    val module = dev.pampa.pampai.core.assistant.di.DatabaseModule
    val repo = dev.pampa.pampai.core.assistant.db.ConversationsRepository(context, module.conversations(db), module.messages(db), module.attachments(db), module.runs(db), kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }, db)
    return ModelSelection(context, settings, ProviderFactory(AiHttp("fixture"), keys, settings, "fixture", "fixture", catalog), keys, catalog, UsageRepository(dao), RuntimeBudgetCoordinator(dev.pampa.pampai.core.assistant.durable.BudgetLedger(db), db), repo)
  }

  @Test fun rootPickerFavoriteSearchAndImageRecovery() {
    val selection = selection()
    rule.setContent { PampaiTheme(EngineSettings()) {
      SessionModalRoot(1L) { ExactModelControl(selection, 42L) }
    } }
    rule.waitUntil(10_000) {
      rule.waitForIdle()
      selection.loaded.value
    }
    rule.runOnIdle {
      selection.state.value = ModelPickerState(
        options = listOf(ModelOption(model, "Text fixture", ImageCapability.NO), ModelOption(vision, "Vision fixture", ImageCapability.YES)),
        last = ModelPolicy(model), recent = listOf(vision.key))
    }
    rule.onNodeWithContentDescription("Modello: groq / text-fixture").performClick()
    rule.onNodeWithText("Modello della conversazione").assertIsDisplayed()
    rule.onNode(hasSetTextAction()).performTextInput("Vision")
    rule.onNodeWithText("Text fixture").assertDoesNotExist()
    rule.onNodeWithText("Vision fixture").assertIsDisplayed()
    rule.onNode(hasSetTextAction()).performTextClearance()
    val favorite = rule.onNodeWithContentDescription("Aggiungi Text fixture ai preferiti")
    favorite.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
      .performClick()
    rule.waitUntil(10_000) {
      rule.waitForIdle()
      model.key in selection.state.value.favorites
    }
    rule.runOnIdle { selection.state.value = selection.state.value.copy(
      options = listOf(ModelOption(model, "Text fixture", ImageCapability.NO), ModelOption(vision, "Vision fixture", ImageCapability.YES)), last = ModelPolicy(model)) }
    rule.onNodeWithContentDescription("Rimuovi Text fixture dai preferiti")
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
    // Actual root back handler closes the sheet, leaving the model anchor accessible.
    rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
    rule.waitForIdle()
    rule.onNodeWithText("Modello della conversazione").assertDoesNotExist()
    // A previous text-model search must not hide compatible choices in automatic image recovery.
    rule.onNodeWithContentDescription("Modello: groq / text-fixture").performClick()
    rule.onNode(hasSetTextAction()).performTextInput("text-fixture")
    rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
    rule.runOnIdle { selection.imageChoiceNeeded.value = true }
    rule.onNodeWithText("Queste immagini richiedono una scelta").assertIsDisplayed()
    rule.onNodeWithText("Text fixture").assertDoesNotExist()
    rule.onNodeWithText("Vision fixture").assertIsDisplayed()
    rule.onNodeWithText("Supporto visivo").performScrollTo().performClick()
    rule.onNodeWithText("Vision fixture").performScrollTo().performClick()
    rule.waitUntil(10_000) {
      rule.waitForIdle()
      selection.state.value.selected(42L)?.helper == vision
    }
    rule.runOnIdle {
      assertFalse(selection.imageChoiceNeeded.value)
      assertEquals(model, selection.state.value.selected(42L)?.identity)
    }
    rule.onNodeWithContentDescription("Modello: groq / text-fixture").performClick()
    rule.onNodeWithText("Disattiva supporto visivo").performScrollTo().performClick()
    rule.waitUntil(10_000) { rule.waitForIdle(); selection.state.value.selected(42L)?.helper == null }
    rule.runOnIdle { assertEquals(model, selection.state.value.selected(42L)?.identity) }
  }

  @Test fun longOverlayAnswerHasScrollableReadableEndAtLargeFont() {
    val answer = dev.pampa.pampai.core.assistant.db.Message(
      2L, 42L, dev.pampa.pampai.core.assistant.db.MessageRole.ASSISTANT,
      (1..30).joinToString("\n\n") { "Paragrafo $it: risultato verificato e leggibile." } + "\n\nUltimo esito leggibile",
      emptyList(), dev.pampa.pampai.core.assistant.db.MessageStatus.DONE, null, 0L,
      dev.antigravity.fluidengine.ai.orchestrator.AskMode.TEXT)
    rule.setContent { PampaiTheme(EngineSettings()) {
      CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.8f),
        LocalFluidMotionPolicy provides FluidMotionPolicy(0f)) {
        Box(Modifier.fillMaxWidth().height(340.dp)) { SessionModalRoot(1L) {
          dev.pampa.pampai.feature.assistant.session.SessionCard(
            dev.pampa.pampai.feature.assistant.session.Exchange("fixture", "Risposta lunga", answer),
            null, null, false, 300.dp, rememberGlassBackdrop(), { _, _ -> }, {}, {})
        } }
      }
    } }
    rule.onNodeWithText("Ultimo esito leggibile", substring = true).performScrollTo().assertIsDisplayed()
  }

  @Test fun pickerFitsShortViewportLargeFontWithReducedMotion() {
    val selection = selection()
    rule.setContent { PampaiTheme(EngineSettings()) {
      CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.8f),
        LocalFluidMotionPolicy provides FluidMotionPolicy(0f)) {
        Box(Modifier.fillMaxWidth().height(340.dp)) {
          SessionModalRoot(1L) { ExactModelControl(selection, null) }
        }
      }
    } }
    rule.waitUntil(10_000) {
      rule.waitForIdle()
      selection.loaded.value
    }
    rule.runOnIdle { selection.imageChoiceNeeded.value = true }
    // Bottom action must remain reachable above the simulated keyboard-sized viewport.
    rule.onNodeWithTag("picker-sheet").performScrollToNode(hasText("Chiudi"))
    rule.onNodeWithText("Chiudi").assertIsDisplayed().performClick()
    rule.runOnIdle { assertFalse(selection.imageChoiceNeeded.value) }
  }

  @Test fun failedPreflightLeavesDraftForExplicitRetry() {
    lateinit var composer: ComposerState
    var calls = 0
    rule.setContent { PampaiTheme(EngineSettings()) {
      composer = remember { ComposerState("Bozza da conservare") }
      Composer(rememberGlassBackdrop(), ChatUiState(), composer,
        actions = remember { ComposerActions(onSend = { ++calls > 1 }) })
    } }
    rule.onNodeWithContentDescription("Invia").performClick()
    rule.runOnIdle { assertEquals("Bozza da conservare", composer.text); assertEquals(1, calls) }
    rule.onNodeWithContentDescription("Invia").performClick()
    rule.runOnIdle { assertEquals("", composer.text); assertEquals(2, calls) }
  }

  @Test fun composerKeepsEditWhileSendSuspends() {
    val deferred = CompletableDeferred<Boolean>()
    lateinit var composer: ComposerState
    var calls = 0
    rule.setContent { PampaiTheme(EngineSettings()) {
      val backdrop = rememberGlassBackdrop()
      composer = remember { ComposerState("Prima domanda") }
      Composer(backdrop, ChatUiState(), composer, actions = remember { ComposerActions(onSend = { calls++; deferred.await() }) })
    } }
    rule.onNodeWithContentDescription("Invia").performClick()
    rule.runOnIdle { assertEquals(1, calls); composer.text = "Nuova bozza"; deferred.complete(true) }
    rule.runOnIdle { assertEquals("Nuova bozza", composer.text) }
  }
  @Test fun realPickerGroupsSearchAndEffortBeforeLongCatalog() {
    val selection = selection()
    val fixtureOptions = listOf(ModelOption(model, "Favorite fixture", ImageCapability.NO), ModelOption(vision, "Recent fixture", ImageCapability.YES)) +
      (1..60).map { ModelOption(ExactModel(ProviderId.GROQ, "catalog-$it"), "Catalog $it", ImageCapability.UNKNOWN) }
    rule.setContent { PampaiTheme(EngineSettings()) { SessionModalRoot(1L) { ExactModelControl(selection, 42L) } } }
    rule.waitUntil(10_000) { rule.waitForIdle(); selection.loaded.value }
    rule.runOnIdle { selection.state.value = ModelPickerState(
      options = fixtureOptions,
      last = ModelPolicy(model), favorites = setOf(model.key), recent = listOf(vision.key)) }
    rule.onNodeWithContentDescription("Modello: groq / text-fixture").performClick()
    rule.onNodeWithText("Impegno per i prossimi invii").assertIsDisplayed()
    rule.onNodeWithText("Alto").performClick()
    rule.waitUntil(10_000) { rule.waitForIdle(); selection.state.value.selected(42L)?.effort == ThinkingLevel.HIGH }
    rule.runOnIdle {
      assertEquals(model, selection.state.value.selected(42L)?.identity)
      // This fixture has no verified keys; restore only its synthetic catalog after real preferences emit.
      selection.state.value = selection.state.value.copy(options = fixtureOptions, favorites = setOf(model.key), recent = listOf(vision.key))
    }
    rule.onNodeWithText("Preferiti").assertIsDisplayed()
    rule.onNodeWithTag("picker-catalog").performScrollToNode(hasText("Recenti"))
    rule.onNodeWithText("Recenti").assertIsDisplayed()
    rule.onNodeWithTag("picker-catalog").performScrollToNode(hasText("Tutti i modelli"))
    rule.onNodeWithText("Tutti i modelli").assertIsDisplayed()
    rule.onNode(hasSetTextAction()).performTextInput("catalog-60")
    rule.onNodeWithTag("picker-catalog").performScrollToNode(hasText("Catalog 60"))
    rule.onNodeWithText("Catalog 60").assertIsDisplayed()
    rule.onNodeWithText("Alto").assertIsDisplayed()
    rule.runOnIdle { assertEquals(model, selection.state.value.selected(42L)?.identity) }
  }

  @Test fun preparingRequestShowsFeedbackAndCanStopWithoutConsumingDraft() {
    val pending = CompletableDeferred<Boolean>()
    lateinit var composer: ComposerState
    var cancelled = false
    var submits = 0
    rule.setContent { PampaiTheme(EngineSettings()) {
      composer = remember { ComposerState("Bozza da conservare") }
      Composer(rememberGlassBackdrop(), ChatUiState(), composer, actions = remember {
        ComposerActions(onSend = {
          submits++
          try { pending.await() } catch (e: kotlinx.coroutines.CancellationException) { cancelled = true; throw e }
        })
      })
    } }
    rule.onNodeWithContentDescription("Invia").performClick()
    rule.onNodeWithText("Preparo la richiesta…").assertIsDisplayed()
    rule.onNodeWithContentDescription(AriaChatStrings.stop).performClick()
    rule.runOnIdle {
      assertTrue(cancelled)
      assertEquals(1, submits)
      assertEquals("Bozza da conservare", composer.text)
    }
    rule.onNodeWithText("Preparo la richiesta…").assertDoesNotExist()
    rule.onNodeWithContentDescription("Invia").assertIsDisplayed()
  }

  @Test fun preferencesUseConversationControlAndDoNotRewriteQueuedRequests() {
    val selection = selection()
    val captured = ModelPolicy(model, ThinkingLevel.LOW)
    val request = AssistantRequest(42L, "Gia in coda", dev.antigravity.fluidengine.ai.orchestrator.AskMode.TEXT, modelPolicy = captured)
    val queued = QueuedQuestion(request.question, policy = captured, conversationId = 42, request = request)
    var settingsOpen by mutableStateOf(true)
    rule.setContent { PampaiTheme(EngineSettings()) { SessionModalRoot(1L) {
      if (settingsOpen) dev.pampa.pampai.feature.assistant.settings.ConversationPolicySettings(selection, 42L)
      else ExactModelControl(selection, 42L)
    } } }
    rule.waitUntil(10_000) { rule.waitForIdle(); selection.loaded.value }
    rule.runOnIdle { selection.state.value = ModelPickerState(last = captured) }
    rule.onNodeWithText("Nessun cambio automatico di modello", substring = true).assertIsDisplayed()
    rule.onNodeWithContentDescription("Modello: groq / text-fixture").performClick()
    rule.onNodeWithText("Alto").performClick()
    rule.waitUntil(10_000) { rule.waitForIdle(); selection.state.value.selected(42)?.effort == ThinkingLevel.HIGH }
    rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed(); settingsOpen = false }
    rule.onNodeWithContentDescription("Modello: groq / text-fixture").assertIsDisplayed().performClick()
    rule.onNodeWithText("Alto").assertIsDisplayed()
    rule.runOnIdle {
      assertEquals(model, selection.state.value.selected(42)?.identity)
      assertEquals(ThinkingLevel.HIGH, selection.state.value.selected(42)?.effort)
      assertSame(request, queued.asRequest())
      assertEquals(captured, queued.asRequest().modelPolicy)
      assertEquals(ThinkingLevel.LOW, queued.asRequest().modelPolicy?.effort)
    }
  }

  private fun pickerShot(name: String, mode: ThemeMode, fontScale: Float = 1f) {
    val selection = selection()
    rule.setContent { PampaiTheme(EngineSettings(themeMode = mode, dynamicColorEnabled = false)) {
      CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
        SessionModalRoot(1L) { ExactModelControl(selection, 42L) }
      }
    } }
    rule.waitUntil(10_000) { rule.waitForIdle(); selection.loaded.value }
    rule.runOnIdle { selection.state.value = ModelPickerState(
      options = listOf(ModelOption(model, "Text fixture", ImageCapability.NO), ModelOption(vision, "Vision fixture", ImageCapability.YES)),
      last = ModelPolicy(model), favorites = setOf(model.key), recent = listOf(vision.key)) }
    rule.onNodeWithContentDescription("Modello: groq / text-fixture").performClick()
    rule.waitForIdle()
    val out = File(System.getenv("SCREENSHOT_DIR") ?: "build/screenshots").apply { mkdirs() }
    rule.onRoot().captureRoboImage(File(out, name + ".png").absolutePath)
  }
  @Test fun experiencePickerLight() = pickerShot("experience-picker-light", ThemeMode.LIGHT)
  @Test fun experiencePickerDark() = pickerShot("experience-picker-dark", ThemeMode.DARK)
  @Test fun experiencePickerLargeFont() = pickerShot("experience-picker-large-font", ThemeMode.LIGHT, 1.8f)

}
