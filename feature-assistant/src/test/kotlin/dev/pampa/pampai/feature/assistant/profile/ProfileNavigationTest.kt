package dev.pampa.pampai.feature.assistant.profile

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.navigation.FluidNavHost
import dev.antigravity.fluidengine.ui.navigation.composable
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.pampa.pampai.feature.assistant.Routes
import dev.pampa.pampai.feature.assistant.theme.PampaiTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Fluid route/back-stack boundary; synthetic editable home instead of Hilt/network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
class ProfileNavigationTest {
  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
  @Test fun hubChildBackAndLegacyMemoryPreserveHomeDraft() {
    lateinit var nav: NavHostController
    rule.setContent { PampaiTheme(EngineSettings()) {
      nav = rememberNavController()
      FluidNavHost(navController = nav, startDestination = Routes.Home) {
        composable(Routes.Home) {
          var draft by rememberSaveable { mutableStateOf("Bozza conservata") }
          androidx.compose.foundation.layout.Column {
            FluidTextField(draft, { draft = it }, label = "Bozza di prova")
            FluidButton("Apri profilo", { nav.navigate(Routes.Profile) })
          }
        }
        composable(Routes.Profile) { ProfileScreen(ProfileState(loading = false), { nav.popBackStack() },
          { nav.navigate(Routes.profile(it)) }, { nav.navigate(Routes.Memory) }, {}) }
        composable(Routes.profile(ProfilePage.SOURCES)) { Text("Fonti di prova") }
        composable(Routes.Memory) { Text("Memorie esistenti") }
      }
    } }
    rule.onNodeWithText("Bozza conservata").performTextReplacement("Bozza modificata prima del profilo")
    rule.onNodeWithText("Apri profilo").performClick()
    rule.onNodeWithText("Fonti collegate").performClick()
    rule.onNodeWithText("Fonti di prova").assertIsDisplayed()
    rule.runOnIdle { assertEquals(Routes.profile(ProfilePage.SOURCES), nav.currentDestination?.route); nav.popBackStack() }
    rule.onNodeWithText("Memorie e promemoria").performClick()
    rule.onNodeWithText("Memorie esistenti").assertIsDisplayed()
    rule.runOnIdle { nav.popBackStack() }
    rule.onNodeWithText("Fonti collegate").assertIsDisplayed()
    rule.runOnIdle { nav.popBackStack() }
    rule.onNodeWithText("Bozza modificata prima del profilo").assertIsDisplayed()
  }
}
