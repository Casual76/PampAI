package dev.pampa.pampai.feature.assistant.profile

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.pampai.core.assistant.durable.ProfileItem
import dev.pampa.pampai.core.assistant.durable.SourceGrant
import dev.pampa.pampai.core.assistant.durable.ResearchEvidence
import dev.pampa.pampai.core.assistant.durable.RetrievedProfile
import dev.pampa.pampai.feature.assistant.theme.PampaiTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Prepared synthetic-only captures; no personal data, device or provider access. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
class ProfileScreenshots {
  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
  private val facts = listOf(
    ProfileItem("stated", "music.artist", "Preferisco musica strumentale", "USER_STATEMENT", null, 1),
    ProfileItem("observed", "fluidify.history_aggregate", "{\"countedPlays\":3,\"localCountedPlays\":2,\"importedOnlyPlays\":1,\"measuredLocalMs\":40000,\"importedDurationMs\":null,\"trackCount\":2,\"artistCount\":1}", "OBSERVATION", null, 1),
    ProfileItem("deduced", "music.artist", "Potresti preferire ascolti serali", "INFERENCE", null, 1,
      limitations = "Campione parziale; preferenza non confermata"),
  )
  private fun capture(name: String) {
    rule.waitForIdle()
    val out = File(System.getenv("SCREENSHOT_DIR") ?: "build/screenshots").apply { mkdirs() }
    rule.onRoot().captureRoboImage(File(out, "$name.png").absolutePath)
  }
  private fun profile(mode: ThemeMode, scale: Float = 1f, empty: Boolean = false) {
    rule.setContent { PampaiTheme(EngineSettings(themeMode = mode, dynamicColorEnabled = false)) {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
        ProfileScreen(ProfileState(loading = false, items = if (empty) emptyList() else facts), {}, {}, {}, {})
      }
    } }
  }
  @Test fun profileLight() { profile(ThemeMode.LIGHT); rule.onNodeWithText("Preferisco musica strumentale").performScrollTo(); capture("profile-light") }
  @Test fun profileDark() { profile(ThemeMode.DARK); capture("profile-dark") }
  @Test fun partialDeduction() { profile(ThemeMode.DARK); rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Potresti preferire ascolti serali")); capture("profile-deduction-dark") }
  @Test fun emptyLight() { profile(ThemeMode.LIGHT, empty = true); capture("profile-empty-light") }
  @Test fun emptyDark() { profile(ThemeMode.DARK, empty = true); capture("profile-empty-dark") }
  @Test fun actualMusicObservationDark() {
    val value = "{\"track\":{\"name\":\"Brano di esempio\",\"artist\":\"Artista sintetico\",\"durationMs\":60000},\"startedAt\":1767225600000,\"measuredLocalMs\":null,\"qualified\":false,\"provenance\":\"spotify_observation\"}"
    rule.setContent { PampaiTheme(EngineSettings(themeMode = ThemeMode.DARK, dynamicColorEnabled = false)) {
      ProfileScreen(ProfileState(loading = false, items = listOf(
        ProfileItem("real-shaped", "fluidify.history_records", value, "OBSERVATION", null, 1))), {}, {}, {}, {})
    } }
    rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Brano di esempio", substring = true))
    rule.onNodeWithText("Tempo locale misurato: sconosciuto", substring = true).assertIsDisplayed()
    capture("profile-music-observation-dark")
  }
  @Test fun sourceScopeLight() {
    val grant = SourceGrant("fixture", "fixture.fluidify", "fixture.authority", "Account di esempio", null, "history_records", null, enabled = true)
    rule.setContent { PampaiTheme(EngineSettings(themeMode = ThemeMode.LIGHT, dynamicColorEnabled = false)) {
      SourcesScreen(ProfileState(loading = false, grants = listOf(grant)), {}, io.mockk.mockk(relaxed = true))
    } }
    capture("profile-sources-light")
  }
  @Test fun deductionProvenanceDark() {
    val grant = SourceGrant("fixture", "fixture.fluidify", "fixture.authority", "Account di esempio", null, "history_records", null, enabled = true)
    val evidence = ResearchEvidence("e", "j", "fixture", "record-fixture", "period-fixture", "fluidify.history_records",
      "{\"track\":{\"uri\":\"spotify:track:example\",\"name\":\"Brano di esempio\",\"artist\":\"Artista sintetico\",\"durationMs\":60000},\"startedAt\":1767225600000,\"measuredLocalMs\":null,\"qualified\":false,\"provenance\":\"spotify_observation\"}", null, 1767225600000, 1767312000000, "UTC",
      1767312000000, 1767312000000, "PARTIAL", "UNKNOWN", "{}", "fixture-hash")
    rule.setContent { PampaiTheme(EngineSettings(themeMode = ThemeMode.DARK, dynamicColorEnabled = false)) {
      dev.pampa.pampai.feature.assistant.session.SessionModalRoot(1L) {
        ProfileScreen(ProfileState(loading = false, items = facts), {}, {}, {}, {})
        ProfileDetail(RetrievedProfile(facts.last(), listOf(evidence), listOf(grant)), false, {}, {}, {}, {})
      }
    } }
    capture("profile-provenance-dark")
  }
  @Test fun largeText() { profile(ThemeMode.LIGHT, 2f); capture("profile-font-2x") }
  @Test @Config(qualifiers = "w1024dp-h900dp-xhdpi") fun wideDark() { profile(ThemeMode.DARK); capture("profile-wide-dark") }
  @Test @Config(qualifiers = "w320dp-h760dp-xhdpi") fun compactSetup() {
    rule.setContent { PampaiTheme(EngineSettings(themeMode = ThemeMode.LIGHT, dynamicColorEnabled = false)) {
      AreaSetupScreen(AreaDraft("grades"), ProfileState(loading = false), {}, {}, {}, {}, {})
    } }
    capture("profile-setup-compact")
  }
  @Test fun realMusicPeriodInSetup() {
    val candidate = dev.pampa.pampai.core.assistant.research.SourceCandidate("synthetic.fluidify", "synthetic.tools", "fluidify", "Account di esempio", null, true)
    val read = SourceDraft(candidate, "Account di esempio", section = "history_records", from = "2026-01-01", until = "2026-02-01").read()
    val grant = SourceGrant(read.grantId, candidate.packageName, candidate.authority, "Account di esempio", null, "history_records", null, enabled = true)
    rule.setContent { PampaiTheme(EngineSettings(themeMode = ThemeMode.LIGHT, dynamicColorEnabled = false)) {
      AreaSetupScreen(AreaDraft("history_records", reads = listOf(read)), ProfileState(loading = false, grants = listOf(grant), connectedReads = listOf(read)), {}, {}, {}, {}, {})
    } }
    rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("fine esclusa", substring = true))
    rule.onNodeWithContentDescription("fine esclusa", substring = true).assertIsDisplayed()
    rule.onNodeWithContentDescription("solo l’ambito selezionato", substring = true).assertDoesNotExist()
    capture("profile-setup-music-period-light")
  }
  @Test @Config(qualifiers = "w1024dp-h900dp-xhdpi") fun wideSetupLargeText() {
    rule.setContent { PampaiTheme(EngineSettings(themeMode = ThemeMode.DARK, dynamicColorEnabled = false)) {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
        AreaSetupScreen(AreaDraft("grades"), ProfileState(loading = false), {}, {}, {}, {}, {})
      }
    } }
    capture("profile-setup-wide-2x-dark")
  }
  @Test fun hubDispatchesStableDestinationsAndLegacyEntry() {
    var destination: ProfilePage? = null; var memory = false; var back = false
    rule.setContent { PampaiTheme(EngineSettings()) {
      ProfileScreen(ProfileState(loading = false), { back = true }, { destination = it }, { memory = true }, {})
    } }
    rule.onNodeWithText("Fonti collegate").performClick()
    assertEquals(ProfilePage.SOURCES, destination)
    rule.onNodeWithText("Memorie e promemoria").performClick()
    assertTrue(memory)
    assertFalse(back)
    assertEquals("memory", dev.pampa.pampai.feature.assistant.Routes.Memory)
    assertEquals(4, ProfilePage.entries.map { dev.pampa.pampai.feature.assistant.Routes.profile(it) }.distinct().size)
  }
  @Test fun workerQueuedStatusLight() {
    rule.setContent { PampaiTheme(EngineSettings(themeMode = ThemeMode.LIGHT, dynamicColorEnabled = false)) {
      ProfileScaffold("Aggiornamenti del profilo", "Orari indicativi, nessuna notifica spontanea.", {}) {
        item { AreaUpdatesRow("grades", dev.pampa.pampai.core.assistant.durable.AreaConfiguration("grades", enabled = true),
          dev.pampa.pampai.core.assistant.research.AreaScheduleStatus("grades", dev.pampa.pampai.core.assistant.research.ScheduleState.ENQUEUED,
            nextEligibleAt = 1794128400000, lastAttemptAt = 1794124800000, lastSuccessAt = 1794038400000), {}, {}) }
      }
    } }
    rule.onNodeWithText("In attesa di Android, rete e batteria").assertExists()
    capture("profile-worker-enqueued-light")
  }
  @Test fun workerUncertainStatusDark() {
    rule.setContent { PampaiTheme(EngineSettings(themeMode = ThemeMode.DARK, dynamicColorEnabled = false)) {
      ProfileScaffold("Aggiornamenti del profilo", "Esiti e disponibilità osservati.", {}) {
        item { AreaUpdatesRow("grades", dev.pampa.pampai.core.assistant.durable.AreaConfiguration("grades", enabled = true),
          dev.pampa.pampai.core.assistant.research.AreaScheduleStatus("grades", dev.pampa.pampai.core.assistant.research.ScheduleState.SUSPENDED,
            "RESULT_UNCERTAIN", lastAttemptAt = 1794124800000), {}, {}) }
      }
    } }
    rule.onNodeWithText("esito da verificare; nessuna ripetizione automatica", substring = true).assertExists()
    capture("profile-worker-uncertain-dark")
  }
}
