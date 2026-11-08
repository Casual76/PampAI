package dev.pampa.pampai.feature.assistant.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.antigravity.fluidengine.foundation.AppUpdater
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidUpdateController
import dev.antigravity.fluidengine.ui.fluid.fluidAppearanceSection
import dev.antigravity.fluidengine.ui.fluid.fluidLicensesSection
import dev.antigravity.fluidengine.ui.fluid.fluidUpdateSection
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.pampai.feature.assistant.theme.PampaiBrand
import dev.pampa.pampai.feature.assistant.chat.ExactModelControl
import dev.pampa.pampai.feature.assistant.chat.ChatViewModel
import javax.inject.Inject

/**
 * L'aggiornamento in-app: lo stesso manifest che il Pampa Store legge. L'`AppUpdater` vive nell'app e
 * arriva qui via Hilt; il `FluidUpdateController` dell'engine (3.0.1) traduce i suoi passi negli stati della
 * sezione «Aggiornamenti». Sta nel ViewModel e non in `rememberFluidUpdateController`: uno scaricamento
 * non si ferma uscendo dalla pagina e tornandoci, e la sezione dice ancora a che punto e' arrivato.
 */
@HiltViewModel
class AboutViewModel @Inject constructor(updater: AppUpdater, @ApplicationContext context: Context) : ViewModel() {
  val updates = FluidUpdateController(updater, viewModelScope, appVersion(context))
}

/** Routes contain no chat state; leaving a page never changes or submits the draft. */
internal enum class SettingsPage(val title: String) {
  HUB("Impostazioni"), AI("AI e modelli"), KEYS("Chiavi e privacy"),
  MODELS("Modelli predefiniti"), SERVICES("Ordine dei servizi"), POLICY("Preferenze AI"), VOICE("Voce e assistente"),
  ACTIONS("App e azioni"), PERMISSIONS("Permessi"), TRUSTED("Azioni fidate"),
  APPS("App collegate"), APPEARANCE("Aspetto"), ABOUT("Informazioni");
  companion object {
    fun fromSection(section: String?): SettingsPage = when (section?.lowercase()) {
      "aria", "ai" -> AI
      "chiavi" -> KEYS
      "ordine", "servizi" -> SERVICES
      "modelli" -> MODELS
      "riserva", "preferenze" -> POLICY
      "voce", "assistente" -> VOICE
      "azioni" -> ACTIONS
      "permessi" -> PERMISSIONS
      "fidate" -> TRUSTED
      "app", "collegate" -> APPS
      "aspetto" -> APPEARANCE
      "informazioni" -> ABOUT
      else -> HUB
    }
  }
}

@Composable
fun SettingsRoute(
  bottomInset: Dp,
  onOpenConsent: () -> Unit,
  onBack: (() -> Unit)? = null,
  onOpenUsage: () -> Unit = {},
  onOpenMemory: () -> Unit = {},
  section: String? = null,
  onOpenSection: (String) -> Unit = {},
  viewModel: AssistantSettingsViewModel = hiltViewModel(),
  about: AboutViewModel = hiltViewModel(),
  chat: ChatViewModel = hiltViewModel(),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val page = SettingsPage.fromSection(section)
  SettingsPageContent(page, bottomInset, onBack, onOpenSection, onOpenMemory, onOpenUsage,
    content = {
      assistantSettingsItems(viewModel, state, onOpenConsent, page)
      if (page == SettingsPage.POLICY) item {
        val ui by chat.state.collectAsStateWithLifecycle()
        ui.modelSelection?.let { ConversationPolicySettings(it, ui.conversation?.id) }
      }
      if ((page == SettingsPage.MODELS && state.verified.isEmpty()) ||
        (page == SettingsPage.SERVICES && state.verified.size < 2)) {
        item { FluidListGroup(glass = true) {
          FluidListRow(title = "Configura i servizi", subtitle = if (state.verified.isEmpty())
            "Verifica una chiave per scegliere i modelli." else "Con due servizi verificati puoi scegliere il loro ordine.",
            onClick = { onOpenSection("chiavi") })
        } }
      }
      if (page == SettingsPage.APPEARANCE) fluidAppearanceSection(
        settings = state.engine, onSettingsChange = viewModel::setEngineSettings,
        brand = PampaiBrand, glass = true, keyPrefix = "section-aspetto")
      if (page == SettingsPage.ABOUT) {
        item { AboutGroup() }
        fluidUpdateSection(state = about.updates.state, onCheck = about.updates::check,
          onInstall = about.updates::install, lastCheckedAtMillis = about.updates.lastCheckedAtMillis, glass = true)
        fluidLicensesSection()
      }
    })
}

/** Shared by production routes and navigation fixtures, with independently scrolling category pages. */
@Composable
internal fun SettingsPageContent(
  page: SettingsPage, bottomInset: Dp = 0.dp, onBack: (() -> Unit)?,
  onOpenSection: (String) -> Unit, onOpenMemory: () -> Unit, onOpenUsage: () -> Unit,
  content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {},
) {
  FluidScreen(title = page.title, onBack = onBack, extraBottomPadding = bottomInset, itemSpacing = 12.dp) {
    fun links(vararg destinations: Pair<String, SettingsPage>) {
      item { FluidListGroup(glass = true) {
        destinations.forEachIndexed { index, (key, target) ->
          if (index > 0) FluidListDivider()
          FluidListRow(title = target.title, subtitle = "", onClick = { onOpenSection(key) })
        }
      } }
    }
    when (page) {
      SettingsPage.HUB -> {
        links("ai" to SettingsPage.AI, "voce" to SettingsPage.VOICE,
          "azioni" to SettingsPage.ACTIONS, "aspetto" to SettingsPage.APPEARANCE,
          "informazioni" to SettingsPage.ABOUT)
        item { FluidListGroup(glass = true) {
          FluidListRow(title = "Profilo e memoria", subtitle = "Fonti, informazioni e promemoria", onClick = onOpenMemory)
        } }
      }
      SettingsPage.AI -> {
        links("chiavi" to SettingsPage.KEYS, "modelli" to SettingsPage.MODELS, "servizi" to SettingsPage.SERVICES, "preferenze" to SettingsPage.POLICY)
        item { FluidListGroup(glass = true) { FluidListRow(title = "Consumi", subtitle = "", onClick = onOpenUsage) } }
      }
      SettingsPage.ACTIONS -> links("app" to SettingsPage.APPS,
        "permessi" to SettingsPage.PERMISSIONS, "fidate" to SettingsPage.TRUSTED)
      else -> Unit
    }
    content()
  }
}

@Composable
private fun AboutGroup() {
  val context = LocalContext.current
  FluidListGroup(glass = true) {
    FluidListRow(title = "Versione", subtitle = "PampAI ${appVersion(context)}")
    FluidListDivider()
    FluidListRow(
      title = "Sorgente",
      subtitle = "github.com/Casual76/PampAI · GPL-3.0",
      onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Casual76/PampAI"))) } },
    )
  }
}

private fun appVersion(context: Context): String = runCatching {
  context.packageManager.getPackageInfo(context.packageName, 0).versionName
}.getOrNull() ?: "?"

/** The same functional exact-model control used by the conversation composer. */
@Composable
internal fun ConversationPolicySettings(selection: dev.pampa.pampai.core.assistant.runtime.ModelSelection, conversationId: Long?) {
  FluidListGroup(glass = true) {
    FluidListRow(title = "Modello della conversazione", subtitle = "Modello, impegno e supporto visivo valgono per i prossimi invii della conversazione. Nessun cambio automatico di modello; gli invii attivi e in coda mantengono la loro scelta.")
    ExactModelControl(selection, conversationId)
  }
}
