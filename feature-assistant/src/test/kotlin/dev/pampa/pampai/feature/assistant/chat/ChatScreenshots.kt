package dev.pampa.pampai.feature.assistant.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.ai.keys.AiSettings
import dev.antigravity.fluidengine.ai.keys.KeyState
import dev.antigravity.fluidengine.ai.orchestrator.AnswerChip
import dev.antigravity.fluidengine.ai.orchestrator.AskMode
import dev.antigravity.fluidengine.ai.orchestrator.MicLevel
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.pampai.core.assistant.db.Message
import dev.pampa.pampai.core.assistant.db.MessageRole
import dev.pampa.pampai.core.assistant.db.MessageStatus
import dev.pampa.pampai.core.assistant.runtime.VoiceEvent
import dev.pampa.pampai.feature.assistant.theme.PampaiTheme
import java.io.File
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Screenshot di prova della chat, per guardarla senza un telefono. Scrive PNG in SCREENSHOT_DIR. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
class ChatScreenshots {

  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  private val out = File(System.getenv("SCREENSHOT_DIR") ?: "build/screenshots").apply { mkdirs() }

  private fun msg(id: Long, role: MessageRole, text: String, chips: List<AnswerChip> = emptyList()) =
    Message(id, 1L, role, text, chips, MessageStatus.DONE, null, id * 1000, AskMode.TEXT)

  private val messages = listOf(
    msg(1, MessageRole.USER, "Che tempo fa domani a Firenze?"),
    msg(2, MessageRole.ASSISTANT, "Domani a **Firenze** sole al mattino e qualche nuvola dal pomeriggio.\n\n- Minima **14°**, massima **24°**\n- Pioggia: 10%\n- Vento debole da ovest\n\nPerfetto per uscire, porta una giacca leggera per la sera.", listOf(AnswerChip("apri", "meteo"))),
    msg(3, MessageRole.USER, "imposta un timer di 10 minuti"),
    msg(4, MessageRole.ASSISTANT, "Timer di 10 minuti avviato: suona alle 18:42."),
  )

  @Composable
  private fun Screen(empty: Boolean) {
    val backdrops = rememberChatBackdrops()
    val state = ChatUiState(
      messages = if (empty) emptyList() else messages,
      settings = AiSettings(enabled = true),
      keys = mapOf(ProviderId.GROQ to KeyState(true, 1L), ProviderId.GEMINI to KeyState(true, 1L)),
    )
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 80.dp, bottom = 140.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
      ) {
        if (empty) item { EmptyGreeting() }
        state.messages.forEach { m ->
          item {
            if (m.role == MessageRole.USER) UserBubble(m, onEdit = {}, showEdit = m.id == 3L)
            else AssistantMessage(m, null, null, null, { _, _ -> }, {}, {}, {}, {}, regenerateWith = listOf(ProviderId.GROQ, ProviderId.GEMINI))
          }
        }
      }
      ChatTopBar(title = if (empty) "" else "Meteo a Firenze", facet = null, backdrop = backdrops.chrome, intensity = { 1f }, onMenu = {}, onNew = {}, temporary = false)
      Box(Modifier.align(Alignment.BottomCenter).padding(12.dp)) {
        Composer(
          backdrop = backdrops.chrome, state = state, editing = null, onSend = {}, onCancelEdit = {}, onStop = {}, onVoice = {}, onStopVoice = {},
          onCancelVoice = {}, onStopSpeaking = {}, micLevel = MutableStateFlow(MicLevel()), partial = null, speaking = false,
          voiceEvents = MutableSharedFlow<VoiceEvent>(), draft = null, onDraftConsumed = {}, onAttach = {}, onRemoveAttachment = {},
          onOpenSettings = {}, onProvider = {}, onThinking = {}, thinkingAuto = true, onThinkingAuto = {}, plugins = emptyList(), plugin = null,
          onPlugin = {}, deepNext = false, onToggleDeep = {}, temporary = false, onToggleTemporary = {},
        )
      }
    }
  }

  private fun shoot(name: String, mode: ThemeMode, empty: Boolean) {
    rule.setContent { PampaiTheme(EngineSettings(themeMode = mode, dynamicColorEnabled = false)) { Screen(empty) } }
    rule.waitForIdle()
    rule.onRoot().captureRoboImage(File(out, "$name.png").absolutePath)
  }

  @Test fun chatLight() = shoot("chat-light", ThemeMode.LIGHT, empty = false)
  @Test fun chatDark() = shoot("chat-dark", ThemeMode.DARK, empty = false)
  @Test fun emptyLight() = shoot("empty-light", ThemeMode.LIGHT, empty = true)
  @Test fun emptyDark() = shoot("empty-dark", ThemeMode.DARK, empty = true)
}
