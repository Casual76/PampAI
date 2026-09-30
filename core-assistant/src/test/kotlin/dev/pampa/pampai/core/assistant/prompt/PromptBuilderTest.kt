package dev.pampa.pampai.core.assistant.prompt

import dev.antigravity.fluidengine.ai.orchestrator.AskMode
import dev.pampa.pampai.core.assistant.tools.Surface
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {

  private fun context(lockscreen: Boolean) = PromptContext(
    nowLabel = "2026-09-30 (mer), mercoledi' 30 settembre 2026, ore 07:10, fuso Europe/Rome",
    language = "it",
    memoryBlock = "",
    connectedApps = "ClasseViva (classeviva: cv_voti); Fluid Transit (bus: bus_fermate)",
    surface = Surface.SESSION,
    mode = AskMode.VOICE,
    actionsEnabled = true,
    loadedCategories = emptyList(),
    maxSteps = 8,
    screenNote = null,
    attachmentsNote = null,
    conversationTitle = null,
    lockscreen = lockscreen,
  )

  @Test
  fun `dal telefono bloccato le app collegate non entrano nel prompt`() {
    val locked = PromptBuilder.build(context(lockscreen = true))
    assertFalse(locked.contains("classeviva"))
    assertFalse(locked.contains("bus_fermate"))
    assertTrue(locked.contains("BLOCCATO"))
    val unlocked = PromptBuilder.build(context(lockscreen = false))
    assertTrue(unlocked.contains("App e aree: ClasseViva (classeviva: cv_voti)"))
    assertFalse(unlocked.contains("BLOCCATO"))
  }
}
