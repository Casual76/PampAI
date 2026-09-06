package dev.pampa.pampai.core.assistant.tools

import dev.antigravity.fluidengine.ai.bridge.RemoteToolSet
import dev.antigravity.fluidengine.ai.orchestrator.AiRouter
import dev.antigravity.fluidengine.ai.tools.AiTool
import dev.antigravity.fluidengine.ai.tools.AiToolCategory
import dev.antigravity.fluidengine.ai.tools.AiToolGroup
import dev.antigravity.fluidengine.ai.tools.ToolRegistry
import dev.antigravity.fluidengine.ai.tools.resolvedCategory
import dev.pampa.pampai.core.assistant.prompt.PreRouter
import dev.pampa.pampai.core.assistant.tools.aria.ariaTools
import dev.pampa.pampai.core.assistant.tools.calc.calcTools
import dev.pampa.pampai.core.assistant.tools.device.calendarTools
import dev.pampa.pampai.core.assistant.tools.device.clockTools
import dev.pampa.pampai.core.assistant.tools.device.contactTools
import dev.pampa.pampai.core.assistant.tools.device.infoTools
import dev.pampa.pampai.core.assistant.tools.device.notificationTools
import dev.pampa.pampai.core.assistant.tools.device.openTools
import dev.pampa.pampai.core.assistant.tools.device.reminderTools
import dev.pampa.pampai.core.assistant.tools.device.systemTools
import dev.pampa.pampai.core.assistant.tools.music.musicTools
import dev.pampa.pampai.core.assistant.tools.screen.screenTools
import dev.pampa.pampai.core.assistant.tools.web.webTools
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Un catalogo montato: il registry, il router costruito su di esso, le righe per il prompt, le regole del pre-router. */
class MountedCatalog(
  val registry: ToolRegistry<PampaiToolContext>,
  val router: AiRouter,
  /** Una riga per categoria, per il prompt di sistema e per `aiuto`. */
  val summary: String,
  /** Le regole del pre-router che arrivano dalle app collegate (i loro vocabolari). */
  val preRules: List<PreRouter.Rule>,
  /** I pacchetti delle app collegate che hanno montato dei tool. */
  val connectedPackages: Set<String>,
) {
  val categories: List<AiToolCategory> get() = registry.categories
}

/**
 * Chi mette insieme i tool di Aria: quelli locali e quelli delle app collegate letti dai loro
 * cataloghi attraverso il bridge. Il registry si ricostruisce quando cambia qualcosa; l'engine ne
 * prende l'ultimo a ogni domanda.
 */
@Singleton
class RegistryHolder @Inject constructor() {

  private val current = MutableStateFlow(build(emptyList()))
  val catalog: StateFlow<MountedCatalog> = current

  /** Le app collegate montano e smontano i loro tool da qui. */
  fun setRemote(sets: List<RemoteToolSet<PampaiToolContext>>) {
    current.value = build(sets)
  }

  private fun build(sets: List<RemoteToolSet<PampaiToolContext>>): MountedCatalog {
    val local: List<AiTool<PampaiToolContext>> = ariaTools() + calcTools() + webTools() + screenTools() +
      clockTools() + reminderTools() + calendarTools() + contactTools() + notificationTools() + systemTools() + openTools() + infoTools() + musicTools()
    val tools = local.map { TracedTool(it) } + sets.flatMap { set -> set.tools.map { TracedTool(it, app = set.catalog.appLabel) } }
    // Solo i gruppi che hanno davvero dei tool: una sottocategoria vuota nel menu' del modello
    // e' una porta su una stanza vuota.
    val used = tools.map { it.group }.toSet()
    val groups: List<AiToolGroup> = PampaiGroup.entries.filter { it in used } + sets.flatMap { it.groups }.filter { it in used }
    val registry = ToolRegistry(tools, groups, actionGroup = null)
    val router = AiRouter(
      groups = groups,
      actionGroup = null,
      domainHint = "un assistente personale, Aria, che controlla il telefono, cerca sul web e usa le app Pampa collegate",
      defaultGroups = listOf(PampaiGroup.ARIA),
      categories = registry.categories,
    )
    val summary = registry.categories.joinToString("; ") { category ->
      val subs = registry.topGroupsOf(category).joinToString(", ") { it.id }
      "${category.label} (${category.id}: $subs)"
    }
    return MountedCatalog(registry, router, summary, preRules(sets), sets.map { it.catalog.packageName }.toSet())
  }

  /**
   * Una regola per app collegata: le parole del suo vocabolario (materie, fermate, luoghi) e il suo
   * nome decidono il gruppo che si apre con la categoria, cosi' "quando passa il 23" non chiama il
   * router remoto.
   */
  private fun preRules(sets: List<RemoteToolSet<PampaiToolContext>>): List<PreRouter.Rule> = sets.mapNotNull { set ->
    val group = set.groups.firstOrNull { it.loadsWithCategory } ?: set.groups.firstOrNull() ?: return@mapNotNull null
    val words = (set.catalog.vocabulary + listOf(set.catalog.appLabel, set.catalog.domain))
      .map { Text.normalize(it) }
      .filter { it.length >= 3 }
      .distinct()
      .take(80)
    if (words.isEmpty()) return@mapNotNull null
    PreRouter.Rule(group, "\\b(" + words.joinToString("|") { Regex.escape(it) } + ")\\b", weight = 2)
  }
}

/** La categoria di un gruppo, per chi ha solo il gruppo. */
fun AiToolGroup.categoryId(): String? = resolvedCategory?.id
