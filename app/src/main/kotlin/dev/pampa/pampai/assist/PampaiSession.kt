package dev.pampa.pampai.assist

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import dagger.hilt.android.EntryPointAccessors
import dev.antigravity.fluidengine.ai.orchestrator.AnswerChip
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.pampa.pampai.MainActivity
import dev.pampa.pampai.R
import dev.pampa.pampai.core.assistant.prompt.AriaChips
import dev.pampa.pampai.core.assistant.service.AssistantNotifications
import dev.pampa.pampai.feature.assistant.assist.AssistantRole
import dev.pampa.pampai.feature.assistant.openApp
import dev.pampa.pampai.feature.assistant.session.PampaiSessionOverlay
import dev.pampa.pampai.feature.assistant.session.SessionActions
import dev.pampa.pampai.feature.assistant.session.SessionComposeHost
import dev.pampa.pampai.feature.assistant.session.SessionController
import dev.pampa.pampai.feature.assistant.theme.PampaiTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * La sessione di sistema: la finestra trasparente sopra qualsiasi app, con dentro l'overlay di Aria
 * in Compose. Il sistema consegna qui lo screenshot e la struttura dello schermo (se l'utente li
 * ha attivati), che finiscono nello store letto dai tool `schermo_*`. La domanda gira nel service
 * in primo piano: se la sessione si chiude a meta', la risposta arriva in notifica.
 */
class PampaiSession(context: Context) : VoiceInteractionSession(context) {

  private val entry = EntryPointAccessors.fromApplication(context.applicationContext, SessionEntryPoint::class.java)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val screen = entry.screen()
  private lateinit var host: SessionComposeHost
  private lateinit var controller: SessionController

  override fun onCreate() {
    super.onCreate()
    setTheme(R.style.Theme_PampAI_Session)
    setUiEnabled(true)
    controller = SessionController(entry.runtime(), entry.conversations(), screen, scope)
  }

  override fun onCreateContentView(): View {
    host = SessionComposeHost(context)
    window?.window?.let { w ->
      w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
      WindowCompat.setDecorFitsSystemWindows(w, false)
      w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
      w.statusBarColor = Color.TRANSPARENT
      w.navigationBarColor = Color.TRANSPARENT
    }
    val actions = SessionActions(
      hide = { hide() },
      expand = { conversationId -> expandToApp(conversationId) },
      openAssistSettings = { AssistantRole.openSettings(context); hide() },
      chip = { chip -> onChip(chip) },
    )
    host.setContent {
      val engine by entry.engineSettings().settings.collectAsState(initial = EngineSettings())
      PampaiTheme(settings = engine) {
        PampaiSessionOverlay(controller = controller, actions = actions)
      }
    }
    return host
  }

  override fun onShow(args: Bundle?, showFlags: Int) {
    super.onShow(args, showFlags)
    val source = args?.getString(PampaiInteractionService.EXTRA_SOURCE) ?: PampaiInteractionService.SOURCE_ASSIST
    val keyguard = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
    screen.beginShow(
      hasAssist = showFlags and SHOW_WITH_ASSIST != 0,
      hasScreenshot = showFlags and SHOW_WITH_SCREENSHOT != 0,
      source = source,
      lockscreen = keyguard,
    )
    if (::host.isInitialized) host.resume()
    scope.launch {
      val settings = entry.pampaiSettings().settings.first()
      controller.onShow(startVoice = source != PampaiInteractionService.SOURCE_TEXT, startInText = settings.startInText)
    }
  }

  override fun onHandleAssist(state: AssistState) {
    val packageName = state.assistData?.getString(Intent.EXTRA_ASSIST_PACKAGE)
    screen.addWindow(state.assistStructure, packageName)
    if (state.index >= state.count - 1) screen.markAssistDone()
  }

  override fun onHandleScreenshot(screenshot: Bitmap?) {
    screen.setScreenshot(screenshot)
  }

  override fun onLockscreenShown() {
    screen.setLockscreen(true)
  }

  override fun onHide() {
    controller.onHide()
    screen.clear()
    if (::host.isInitialized) host.pause()
    super.onHide()
  }

  override fun onBackPressed() {
    if (!controller.onBack()) hide()
  }

  override fun onComputeInsets(outInsets: Insets) {
    super.onComputeInsets(outInsets)
    outInsets.contentInsets.set(0, 0, 0, 0)
    outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_FRAME
  }

  override fun onDestroy() {
    if (::host.isInitialized) host.dispose()
    scope.cancel()
    super.onDestroy()
  }

  /** Il trascinamento in alto: la stessa conversazione continua nella chat a pagina intera. */
  private fun expandToApp(conversationId: Long?) {
    val intent = Intent(context, MainActivity::class.java)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    if (conversationId != null) intent.putExtra(AssistantNotifications.EXTRA_CONVERSATION, conversationId) else intent.putExtra(MainActivity.EXTRA_NEW, true)
    runCatching { startAssistantActivity(intent) }.onFailure { runCatching { context.startActivity(intent) } }
    hide()
  }

  private fun onChip(chip: AnswerChip) {
    when (chip.id) {
      AriaChips.URL -> chip.value?.let { runCatching { startAssistantActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; hide() }
      AriaChips.APP -> chip.value?.let { openApp(context, it); hide() }
      AriaChips.CONVERSATION -> chip.value?.toLongOrNull()?.let { expandToApp(it) }
      AriaChips.SETTINGS -> if (chip.value == "assistente") { AssistantRole.openSettings(context); hide() } else expandToApp(null)
      AriaChips.PLACE -> chip.value?.let { controller.ask("E a $it?") }
      else -> Unit
    }
  }
}
