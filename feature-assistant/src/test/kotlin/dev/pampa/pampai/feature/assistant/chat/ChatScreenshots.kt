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
import dev.antigravity.fluidengine.ai.orchestrator.AssistantState
import dev.antigravity.fluidengine.ai.orchestrator.FailureKind
import dev.antigravity.fluidengine.ai.orchestrator.ProviderSwitch
import dev.antigravity.fluidengine.ai.orchestrator.SwitchReason
import dev.antigravity.fluidengine.ai.provider.ModelTier
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.pampa.pampai.core.assistant.db.FailureDetail
import dev.pampa.pampai.core.assistant.db.Message
import dev.pampa.pampai.core.assistant.db.MessageRole
import dev.pampa.pampai.core.assistant.db.MessageStatus
import dev.pampa.pampai.core.assistant.db.Run
import dev.pampa.pampai.core.assistant.db.RunModel
import dev.pampa.pampai.core.assistant.db.Version
import dev.pampa.pampai.core.assistant.tools.PampaiToolTrace
import dev.pampa.pampai.feature.assistant.theme.PampaiTheme
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot di prova della chat, per guardarla senza un telefono. Scrive PNG in SCREENSHOT_DIR.
 *
 * Il composer si costruisce con lo stato e poco altro ([ComposerState], [ComposerModel] e le
 * azioni hanno tutti un default): un parametro nuovo del composer non rompe piu' questo file.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
class ChatScreenshots {

  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  private val out = File(System.getenv("SCREENSHOT_DIR") ?: "build/screenshots").apply { mkdirs() }

  private fun msg(
    id: Long,
    role: MessageRole,
    text: String,
    chips: List<AnswerChip> = emptyList(),
    status: MessageStatus = MessageStatus.DONE,
    failure: FailureKind? = null,
    version: Version? = null,
  ) = Message(id, 1L, role, text, chips, status, failure, id * 1000, AskMode.TEXT, parentId = if (id == 1L) null else id - 1, version = version)

  private fun run(messageId: Long, tools: List<PampaiToolTrace> = emptyList(), switches: List<ProviderSwitch> = emptyList(), failure: FailureDetail? = null, outcome: String = "ok") = Run(
    id = messageId, conversationId = 1L, messageId = messageId, startedAtMillis = 0L, finishedAtMillis = 2_400L, steps = 2,
    provider = switches.lastOrNull()?.to ?: ProviderId.GROQ, routerModel = null, chatModel = "llama-3.3-70b-versatile", deepModel = null,
    tierReached = ModelTier.CHAT, groups = emptyList(), promptTokens = 1200, completionTokens = 180, costUsd = null, waitedSeconds = 0,
    tools = tools, outcome = outcome, error = failure?.let { "HTTP ${it.httpCode}" }, contextTokens = null, contextWindow = null,
    models = if (failure == null) listOf(RunModel(switches.lastOrNull()?.to ?: ProviderId.GROQ, ModelTier.CHAT, "gemini-2.5-flash")) else emptyList(),
    switches = switches,
    failure = failure,
  )

  private val switched = listOf(ProviderSwitch(ProviderId.GROQ, ProviderId.GEMINI, SwitchReason.RATE_LIMITED))
  private val limit = FailureDetail(ProviderId.OPENROUTER, SwitchReason.RATE_LIMITED, 429, "Rate limit exceeded: free-models-per-day. Add 10 credits to unlock 1000 free model requests per day")

  private val messages = listOf(
    msg(1, MessageRole.USER, "Che tempo fa domani a Firenze?"),
    msg(
      2, MessageRole.ASSISTANT,
      "Domani a **Firenze** sole al mattino e qualche nuvola dal pomeriggio.\n\n- Minima **14°**, massima **24°**\n- Pioggia: 10%\n\nPerfetto per uscire, porta una giacca leggera per la sera.",
      listOf(AnswerChip("apri", "meteo")),
      version = Version(2, 2, prevId = 20L, nextId = null),
    ),
    msg(3, MessageRole.USER, "E sabato?", version = Version(1, 2, prevId = null, nextId = 30L)),
    msg(4, MessageRole.ASSISTANT, "", status = MessageStatus.FAILED, failure = FailureKind.RATE_LIMITED),
    msg(5, MessageRole.USER, "imposta un timer di 10 minuti"),
    msg(6, MessageRole.ASSISTANT, "Timer di 10 minuti avviato: suona alle 18:42."),
  )

  private val runs = mapOf(
    2L to run(2, tools = listOf(PampaiToolTrace("meteo", "{\"luogo\":\"Firenze\"}", 420, true, 800, "")), switches = switched),
    4L to run(4, failure = limit, outcome = "failed"),
    6L to run(6, outcome = dev.pampa.pampai.core.assistant.runtime.LOCAL_OUTCOME),
  )

  @Composable
  private fun Screen(empty: Boolean) {
    val backdrops = rememberChatBackdrops()
    val state = ChatUiState(
      messages = if (empty) emptyList() else messages,
      settings = AiSettings(enabled = true),
      keys = mapOf(ProviderId.GROQ to KeyState(true, 1L), ProviderId.GEMINI to KeyState(true, 1L)),
    )
    val actions = AnswerActions(regenerateWith = listOf(ProviderId.GROQ, ProviderId.GEMINI))
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 80.dp, bottom = 140.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
      ) {
        if (empty) item { EmptyGreeting() }
        state.messages.forEach { m ->
          item {
            if (m.role == MessageRole.USER) UserBubble(m, onEdit = {}, showEdit = m.id == 5L)
            else AssistantMessage(m, runs[m.id], null, null, actions)
          }
        }
      }
      ChatTopBar(title = if (empty) "" else "Meteo a Firenze", facet = null, backdrop = backdrops.chrome, intensity = { 1f }, onMenu = {}, onNew = {}, temporary = false)
      Box(Modifier.align(Alignment.BottomCenter).padding(12.dp)) {
        Composer(backdrop = backdrops.chrome, state = state, composer = rememberComposerState())
      }
    }
  }

  /**
   * Il giro senza Markdown: le versioni sulle bolle e sulle risposte, una risposta fallita con
   * "Riprova" e "Dettagli", una fermata, una che si sta scrivendo (riga di stato nell'intestazione),
   * una domanda in coda, e il composer al lavoro senza chiavi (pillola delle impostazioni, stop e
   * invio in coda). Gira anche sulla JDK 17, a differenza di [Screen].
   */
  @Composable
  private fun Flow() {
    val backdrops = rememberChatBackdrops()
    val live = AssistantState.Answering("Tre idee veloci per il pranzo", "Ecco tre idee veloci:\n\n1. **Insalata di farro** con pomodorini e feta\n2. Piadina con", ProviderId.GROQ)
    val flow = listOf(
      msg(1, MessageRole.USER, "Che tempo fa domani a Firenze?", version = Version(2, 2, prevId = 10L, nextId = null)),
      msg(2, MessageRole.ASSISTANT, "", status = MessageStatus.FAILED, failure = FailureKind.RATE_LIMITED, version = Version(1, 3, prevId = null, nextId = 21L)),
      msg(3, MessageRole.USER, "E sabato?"),
      msg(4, MessageRole.ASSISTANT, "", status = MessageStatus.CANCELLED),
      msg(5, MessageRole.USER, "Tre idee veloci per il pranzo"),
      msg(6, MessageRole.ASSISTANT, "", status = MessageStatus.STREAMING),
    )
    val flowRuns = mapOf(2L to run(2, tools = listOf(PampaiToolTrace("meteo", "{\"luogo\":\"Firenze\"}", 420, true, 800, "")), failure = limit, outcome = "failed"))
    val state = ChatUiState(messages = flow, live = live, settings = AiSettings(enabled = false))
    val composer = rememberComposerState().apply { text = "E una per la merenda?" }
    val actions = AnswerActions(regenerateWith = listOf(ProviderId.GROQ, ProviderId.GEMINI))
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 80.dp, bottom = 190.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
      ) {
        flow.forEach { m ->
          item {
            if (m.role == MessageRole.USER) UserBubble(m, onEdit = {}, showEdit = m.id == 5L)
            else AssistantMessage(m, flowRuns[m.id], if (m.id == 6L) live else null, null, actions)
          }
        }
        item { QueuedBubble(QueuedQuestion("E per cena?"), onCancel = {}) }
      }
      ChatTopBar(title = "Meteo a Firenze", facet = null, backdrop = backdrops.chrome, intensity = { 1f }, onMenu = {}, onNew = {}, temporary = false)
      Box(Modifier.align(Alignment.BottomCenter).padding(12.dp)) {
        Composer(backdrop = backdrops.chrome, state = state, composer = composer)
      }
    }
  }

  /** Il foglio "Dettagli" di una risposta fallita, il contenuto sopra il fondo della pagina. */
  @Composable
  private fun Details() {
    val report = FailureReport(
      kind = FailureKind.RATE_LIMITED,
      provider = ProviderId.OPENROUTER,
      model = "minimax/minimax-m2.5:free",
      reason = SwitchReason.RATE_LIMITED,
      httpCode = 429,
      providerMessage = limit.message,
      switches = listOf(ProviderSwitch(ProviderId.GROQ, ProviderId.GEMINI, SwitchReason.RATE_LIMITED), ProviderSwitch(ProviderId.GEMINI, ProviderId.OPENROUTER, SwitchReason.SERVER)),
      technical = "HTTP 429",
    )
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
      FailureDetailsContent(report)
    }
  }

  private fun shoot(name: String, mode: ThemeMode, content: @Composable () -> Unit) {
    rule.setContent { PampaiTheme(EngineSettings(themeMode = mode, dynamicColorEnabled = false)) { content() } }
    rule.waitForIdle()
    rule.onRoot().captureRoboImage(File(out, "$name.png").absolutePath)
  }

  /**
   * Il Markdown completo (mikepenz 0.41) e' compilato per Java 21, e Robolectric gira sulla JVM di
   * Gradle: sulla JDK 17 caricarlo fallisce ("class file version 65.0"). Allora le schermate con
   * risposte finite si saltano invece di fallire, e il giro senza Markdown ([Flow]) resta.
   */
  private fun assumeMarkdownLoads() = assumeTrue("il Markdown completo vuole la JDK 21 per Robolectric", (System.getProperty("java.specification.version")?.toIntOrNull() ?: 0) >= 21)

  @Test fun chatLight() { assumeMarkdownLoads(); shoot("chat-light", ThemeMode.LIGHT) { Screen(empty = false) } }
  @Test fun chatDark() { assumeMarkdownLoads(); shoot("chat-dark", ThemeMode.DARK) { Screen(empty = false) } }
  @Test fun emptyLight() = shoot("empty-light", ThemeMode.LIGHT) { Screen(empty = true) }
  @Test fun emptyDark() = shoot("empty-dark", ThemeMode.DARK) { Screen(empty = true) }
  @Test fun flowLight() = shoot("flow-light", ThemeMode.LIGHT) { Flow() }
  @Test fun flowDark() = shoot("flow-dark", ThemeMode.DARK) { Flow() }
  @Test fun detailsLight() = shoot("details-light", ThemeMode.LIGHT) { Details() }
  @Test fun detailsDark() = shoot("details-dark", ThemeMode.DARK) { Details() }
}
