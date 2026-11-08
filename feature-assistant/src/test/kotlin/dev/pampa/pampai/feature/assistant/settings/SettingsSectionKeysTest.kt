package dev.pampa.pampai.feature.assistant.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import dev.antigravity.fluidengine.ai.keys.KeyState
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.antigravity.fluidengine.aiui.settings.FluidAiSettingsPart
import dev.antigravity.fluidengine.aiui.settings.FluidAiSettingsActions
import dev.antigravity.fluidengine.aiui.settings.FluidAiSettingsOptions
import dev.antigravity.fluidengine.aiui.settings.FluidAiSettingsState
import dev.antigravity.fluidengine.aiui.settings.fluidAiSettingsSection
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.ui.fluid.fluidAppearanceSection
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il chip `[[impostazioni:chiavi]]` porta a una voce della lista per **chiave**: se la sezione
 * dell'engine cambia il prefisso, il chip porta in cima senza dire niente. Qui si registra la lista
 * che la pagina costruirebbe (senza disegnarla) e si controlla che ogni destinazione di the engine defaults
 * esista davvero.
 */
class SettingsSectionKeysTest {

  /** Una `LazyListScope` che ricorda solo le chiavi degli item. */
  private class KeyRecorder : LazyListScope {
    val keys = mutableListOf<Any?>()

    override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
      keys += key
    }

    override fun items(count: Int, key: ((Int) -> Any)?, contentType: (Int) -> Any?, itemContent: @Composable LazyItemScope.(Int) -> Unit) {
      repeat(count) { keys += key?.invoke(it) }
    }

    @ExperimentalFoundationApi
    override fun stickyHeader(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
      keys += key
    }
  }

  private val verified = mapOf(ProviderId.GROQ to KeyState(true, 1L), ProviderId.GEMINI to KeyState(true, 1L))

  private fun listKeys(): Set<Any?> {
    val recorder = KeyRecorder()
    recorder.fluidAppearanceSection(EngineSettings(), onSettingsChange = {}, glass = true, keyPrefix = "section-aspetto")
    recorder.fluidAiSettingsSection(
      state = FluidAiSettingsState(keys = verified, thinkingAuto = true, failoverEnabled = true),
      actions = FluidAiSettingsActions(saveAndVerify = { _, _ -> error("non serve") }, removeKey = {}, setEnabled = {}),
      strings = AriaAiStrings,
      options = FluidAiSettingsOptions(requireConsent = true, speakReplies = false, microphone = false),
      glass = true,
      keyPrefix = "aria",
    )
    return recorder.keys.toSet()
  }

  @Test
  fun `ogni destinazione di un chip esiste nella lista, con almeno due chiavi verificate`() {
    val keys = listKeys()
    // Le intestazioni che la pagina scrive da se' (voce, permessi, azioni fidate, app collegate, Aria).
    val own = setOf("section-aria", "section-voce", "section-permessi", "section-fidate", "section-app")
    mapOf("chiavi" to "aria-keys-header", "modelli" to "aria-models-header", "preferenze" to "aria-prefs-header").forEach { (name, key) ->
      assertTrue("il chip «$name» punta a «$key», che la lista non ha: $keys", key in keys || key in own)
    }
  }
  @Test fun focusedKeysRegistersOnlyKeysAndPrivacyEvenWithVerifiedModels() {
    val recorder = KeyRecorder()
    recorder.fluidAiSettingsSection(
      state = FluidAiSettingsState(keys = verified, thinkingAuto = true, failoverEnabled = true),
      actions = FluidAiSettingsActions(saveAndVerify = { _, _ -> error("No network") }, removeKey = {}, setEnabled = {}),
      parts = setOf(FluidAiSettingsPart.Keys, FluidAiSettingsPart.Privacy), keyPrefix = "aria")
    org.junit.Assert.assertEquals(listOf("aria-keys-header", "aria-keys", "aria-privacy"), recorder.keys)
  }

}
