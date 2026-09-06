package dev.pampa.pampai.feature.assistant

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.antigravity.fluidengine.ai.orchestrator.AnswerChip
import dev.antigravity.fluidengine.ui.fluid.FluidFoldingTabBar
import dev.antigravity.fluidengine.ui.fluid.FluidFoldingTabBarDefaults
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalHost
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.FluidNotificationHost
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidScrollToTopBus
import dev.antigravity.fluidengine.ui.fluid.FluidTabItem
import dev.antigravity.fluidengine.ui.fluid.LocalFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.LocalFluidNotificationHostState
import dev.antigravity.fluidengine.ui.fluid.ProvideFluidChrome
import dev.antigravity.fluidengine.ui.fluid.fluidGlassModalObscured
import dev.antigravity.fluidengine.ui.fluid.rememberFluidBarFold
import dev.antigravity.fluidengine.ui.fluid.rememberFluidChromeController
import dev.antigravity.fluidengine.ui.fluid.rememberFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.rememberFluidNotificationHostState
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.antigravity.fluidengine.ui.theme.FluidRouteMotionHost
import dev.pampa.pampai.core.assistant.prompt.AriaChips
import dev.pampa.pampai.feature.assistant.assist.AssistantRole
import dev.pampa.pampai.feature.assistant.chat.ChatRoute
import dev.pampa.pampai.feature.assistant.chat.ChatViewModel
import dev.pampa.pampai.feature.assistant.consent.consentItems
import dev.pampa.pampai.feature.assistant.history.HistoryRoute
import dev.pampa.pampai.feature.assistant.onboarding.OnboardingRoute
import dev.pampa.pampai.feature.assistant.settings.AssistantSettingsViewModel
import dev.pampa.pampai.feature.assistant.settings.SettingsRoute
import dev.pampa.pampai.feature.assistant.usage.UsageRoute

/** Le rotte laterali; le tre schede della home sono stato, non rotte. */
private object Routes {
  const val Onboarding = "onboarding"
  const val Home = "home"
  const val Consent = "consent"
  const val Usage = "usage"
}

private const val TabChat = "chat"
private const val TabHistory = "history"
private const val TabSettings = "settings"

private val Tabs = listOf(
  FluidTabItem(route = TabChat, label = "Aria", icon = Icons.Rounded.AutoAwesome),
  FluidTabItem(route = TabHistory, label = "Cronologia", icon = Icons.Rounded.History),
  FluidTabItem(route = TabSettings, label = "Impostazioni", icon = Icons.Rounded.Settings),
)

/** Quanto la pagina coperta si sposta mentre quella nuova la copre. */
private const val CoveredParallax = 0.25f

/**
 * Cosa l'app chiede da fuori: aprire una conversazione (una notifica, la sessione che si espande),
 * la voce (una scorciatoia, il trampolino), una chat nuova, l'ultima, o un testo e dei file condivisi.
 */
data class EntryRequest(
  val conversationId: Long? = null,
  val voice: Boolean = false,
  val newChat: Boolean = false,
  val last: Boolean = false,
  val sharedText: String? = null,
  val sharedUris: List<Uri> = emptyList(),
  val stamp: Long = System.currentTimeMillis(),
)

/**
 * La radice dell'app: il navigation host con le transizioni opache e laterali del design system
 * (una pagina in arrivo non sfuma mai), e dentro la home con le tre schede.
 */
@Composable
fun PampaiRoot(startAtOnboarding: Boolean, entry: EntryRequest?) {
  val navController = rememberNavController()
  NavHost(
    navController = navController,
    startDestination = if (startAtOnboarding) Routes.Onboarding else Routes.Home,
    enterTransition = {
      slideInHorizontally(animationSpec = tween(FluidMotion.DurationExpand, easing = FluidMotion.EaseEmphasized), initialOffsetX = { it })
    },
    exitTransition = {
      slideOutHorizontally(animationSpec = tween(FluidMotion.DurationExpand, easing = FluidMotion.EaseEmphasized), targetOffsetX = { -(it * CoveredParallax).toInt() })
    },
    popEnterTransition = {
      slideInHorizontally(animationSpec = tween(FluidMotion.DurationCollapse, easing = FluidMotion.EaseEmphasized), initialOffsetX = { -(it * CoveredParallax).toInt() })
    },
    popExitTransition = {
      slideOutHorizontally(animationSpec = tween(FluidMotion.DurationCollapse, easing = FluidMotion.EaseEmphasized), targetOffsetX = { it })
    },
  ) {
    composable(Routes.Onboarding) {
      Page(this) {
        OnboardingRoute(onDone = { navController.navigate(Routes.Home) { popUpTo(Routes.Onboarding) { inclusive = true } } })
      }
    }
    composable(Routes.Home) {
      Page(this) { Home(navController, entry) }
    }
    composable(Routes.Consent) {
      Page(this) { ConsentRoute(onBack = { navController.popBackStack() }) }
    }
    composable(Routes.Usage) {
      Page(this) { UsageRoute(onBack = { navController.popBackStack() }) }
    }
  }
}

@Composable
private fun Page(scope: AnimatedContentScope, content: @Composable () -> Unit) {
  FluidRouteMotionHost(animatedVisibilityScope = scope, content = content)
}

/** La home: tre schede sotto una barra di vetro che si ripiega scorrendo, con i padroni di casa di modali e notifiche. */
@Composable
private fun Home(navController: NavHostController, entry: EntryRequest?, chat: ChatViewModel = hiltViewModel()) {
  val context = LocalContext.current
  var tab by rememberSaveable { mutableStateOf(TabChat) }
  val chromeController = rememberFluidChromeController()
  val scrollToTop = remember { FluidScrollToTopBus() }
  val modalHost = rememberFluidGlassModalHostState()
  val notificationHost = rememberFluidNotificationHostState()
  val fallbackBackdrop = rememberGlassBackdrop()
  val backdrop = chromeController.activeBackdrop.value ?: fallbackBackdrop
  val barFold = rememberFluidBarFold()

  // Una notifica o una scorciatoia: si apre la conversazione chiesta (o la voce) nella scheda della chat.
  LaunchedEffect(entry?.stamp) {
    val request = entry ?: return@LaunchedEffect
    when {
      request.conversationId != null -> chat.open(request.conversationId)
      request.last -> chat.openLast()
      request.newChat || request.sharedText != null || request.sharedUris.isNotEmpty() -> chat.newConversation()
    }
    tab = TabChat
    request.sharedUris.forEach { chat.attach(it) }
    request.sharedText?.let { chat.draft.value = it }
    if (request.voice) chat.startVoice()
  }

  val onChip: (AnswerChip) -> Unit = { chip ->
    when (chip.id) {
      AriaChips.URL -> chip.value?.let { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
      AriaChips.CONVERSATION -> chip.value?.toLongOrNull()?.let { chat.open(it); tab = TabChat }
      AriaChips.SETTINGS -> when (chip.value) {
        "assistente" -> AssistantRole.openSettings(context)
        "consumi" -> navController.navigate(Routes.Usage)
        else -> tab = TabSettings
      }
      AriaChips.PLACE -> chip.value?.let { chat.send("E a $it?") }
      AriaChips.APP -> chip.value?.let { name -> openApp(context, name) }
      else -> Unit
    }
  }

  CompositionLocalProvider(
    LocalFluidGlassModalHostState provides modalHost,
    LocalFluidNotificationHostState provides notificationHost,
  ) {
    Box(modifier = Modifier.fillMaxSize()) {
      ProvideFluidChrome(
        controller = chromeController,
        bottomInset = FluidFoldingTabBarDefaults.ContentInset,
        scrollToTop = scrollToTop,
      ) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .nestedScroll(barFold.connection)
            .fluidGlassModalObscured(),
        ) {
          when (tab) {
            TabHistory -> HistoryRoute(bottomInset = FluidFoldingTabBarDefaults.ContentInset, onOpenConversation = { tab = TabChat })
            TabSettings -> SettingsRoute(
              bottomInset = FluidFoldingTabBarDefaults.ContentInset,
              onOpenConsent = { navController.navigate(Routes.Consent) },
              onOpenUsage = { navController.navigate(Routes.Usage) },
            )
            else -> ChatRoute(bottomInset = FluidFoldingTabBarDefaults.ContentInset, onChip = onChip, onOpenSettings = { tab = TabSettings }, viewModel = chat)
          }
        }
      }

      Box(
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .navigationBarsPadding()
          .padding(horizontal = FluidFoldingTabBarDefaults.HorizontalMargin, vertical = FluidFoldingTabBarDefaults.BottomMargin),
      ) {
        FluidFoldingTabBar(
          items = Tabs,
          selectedRoute = tab,
          onSelect = { tab = it.route },
          onReselect = { scrollToTop.request() },
          onExpandRequest = barFold::unfold,
          backdrop = backdrop,
          fold = { barFold.progress.value },
        )
      }

      FluidGlassModalHost(state = modalHost, backdrop = backdrop)
      FluidNotificationHost(state = notificationHost, backdrop = backdrop, modifier = Modifier.align(Alignment.TopCenter))
    }
  }
}

/** Apre un'app per nome (le app Pampa hanno i loro package; le altre si cercano per etichetta). */
fun openApp(context: android.content.Context, name: String) {
  val known = mapOf(
    "classeviva" to "dev.antigravity.classevivaexpressive", "cv" to "dev.antigravity.classevivaexpressive",
    "meteo" to "dev.pampa.fluidweather", "fluidweather" to "dev.pampa.fluidweather",
    "bus" to "dev.antigravity.fluidtransit", "transit" to "dev.antigravity.fluidtransit",
    "convert" to "com.p2r3.convert", "conv" to "com.p2r3.convert",
    "store" to "com.pampa.store", "musica" to "dev.pampa.fluidify", "fluidify" to "dev.pampa.fluidify",
  )
  val packageName = known[name.lowercase().trim()] ?: name
  val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return
  runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** La pagina di consenso a se', raggiunta dall'interruttore "Aria" delle impostazioni. */
@Composable
private fun ConsentRoute(onBack: () -> Unit, viewModel: AssistantSettingsViewModel = hiltViewModel()) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  FluidScreen(title = "Il consenso", subtitle = "Cosa parte, verso chi, cosa resta qui.", onBack = onBack, itemSpacing = 12.dp) {
    consentItems(
      canAccept = state.verified.isNotEmpty(),
      onAccept = {
        viewModel.acceptConsentAndEnable()
        onBack()
      },
      onLater = onBack,
    )
  }
}
