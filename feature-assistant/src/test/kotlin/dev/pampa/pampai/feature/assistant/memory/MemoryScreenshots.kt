package dev.pampa.pampai.feature.assistant.memory

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.pampai.core.assistant.db.Memory
import dev.pampa.pampai.core.assistant.db.ReminderEntity
import dev.pampa.pampai.feature.assistant.theme.PampaiTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
class MemoryScreenshots {

  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  private val out = File(System.getenv("SCREENSHOT_DIR") ?: "build/screenshots").apply { mkdirs() }

  private val now = System.currentTimeMillis()
  private val memories = listOf(
    Memory(1, "La mia fermata e' Dalmazia", now - 86_400_000L * 3, null, pinned = true),
    Memory(2, "Sono allergico alle noci", now - 86_400_000L * 10, null, pinned = false),
    Memory(3, "Faccio la quarta F al liceo Agnoletti", now - 86_400_000L * 30, null, pinned = false),
  )
  private val reminders = listOf(
    ReminderEntity(1, "Chiamare la nonna", now + 3_600_000L),
    ReminderEntity(2, "Palestra", now + 86_400_000L * 2, repeat = "WEEKLY"),
  )

  private val noop = MemoryActions({}, { _, _ -> }, {}, { _, _ -> }, {}, {})

  private fun shoot(name: String, mode: ThemeMode) {
    rule.setContent { PampaiTheme(EngineSettings(themeMode = mode, dynamicColorEnabled = false)) { MemoryScreen(memories, reminders, onBack = {}, actions = noop) } }
    rule.waitForIdle()
    rule.onRoot().captureRoboImage(File(out, "$name.png").absolutePath)
  }

  @Test fun memoryLight() = shoot("memory-light", ThemeMode.LIGHT)
  @Test fun memoryDark() = shoot("memory-dark", ThemeMode.DARK)
}
