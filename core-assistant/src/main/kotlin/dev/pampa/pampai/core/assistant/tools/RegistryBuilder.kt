package dev.pampa.pampai.core.assistant.tools

import android.util.Log
import dev.antigravity.fluidengine.ai.bridge.RemoteToolSet
import dev.antigravity.fluidengine.ai.orchestrator.AiRouter
import dev.antigravity.fluidengine.ai.tools.AiTool
import dev.antigravity.fluidengine.ai.tools.AiToolCategory
import dev.antigravity.fluidengine.ai.tools.AiToolGroup
import dev.antigravity.fluidengine.ai.tools.ToolRegistry
import dev.antigravity.fluidengine.ai.tools.resolvedCategory
import dev.pampa.pampai.core.assistant.prompt.PreRouter
import dev.pampa.pampai.core.assistant.runtime.LockscreenPolicy
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

  /**
   * Lo stesso catalogo visto dal telefono bloccato ([LockscreenPolicy]): solo gli strumenti
   * ammessi, un router che non propone i gruppi rimasti vuoti, e nessuna app collegata (ne' nel
   * riassunto per il prompt, ne' nelle regole del pre-router, ne' fra i pacchetti). Si costruisce
   * alla prima domanda da bloccato e vale finche' vale questo catalogo.
   */
  val lockscreen: MountedCatalog by lazy {
    val restricted = LockscreenPolicy.restrict(registry)
    MountedCatalog(restricted, ariaRouter(restricted), summaryOf(restricted), preRules = emptyList(), connectedPackages = emptySet())
  }
}

/** Il router di Aria su un registry: lo stesso per il catalogo intero e per quello da bloccato. */
internal fun ariaRouter(registry: ToolRegistry<PampaiToolContext>): AiRouter = AiRouter(
  groups = registry.groups,
  actionGroup = null,
  domainHint = "un assistente personale, Aria, che controlla il telefono, cerca sul web e usa le app Pampa collegate",
  // Il ripiego quando il router non risponde; da bloccato `aria` non c'e', e il ripiego resta vuoto.
  defaultGroups = listOf(PampaiGroup.ARIA).filter { it in registry.groups },
  categories = registry.categories,
)

/** Una riga per categoria, con le sue sottocategorie: "Aria (aria: aria); Dispositivo (dispositivo: orologio, ...)". */
internal fun summaryOf(registry: ToolRegistry<PampaiToolContext>): String = registry.categories.joinToString("; ") { category ->
  val subs = registry.topGroupsOf(category).joinToString(", ") { it.id }
  "${category.label} (${category.id}: $subs)"
}

/**
 * I gruppi da dichiarare per questi tool: quelli che ne hanno almeno uno e i loro padri (un
 * gruppo padre senza tool suoi resta, se no i figli sarebbero orfani e il registry non nasce),
 * prima quelli di Aria nell'ordine dell'enum, poi quelli delle app nell'ordine dei cataloghi.
 */
internal fun <C> declaredGroups(tools: List<AiTool<C>>, sets: List<RemoteToolSet<C>>): List<AiToolGroup> {
  val used = HashSet<AiToolGroup>()
  tools.forEach { tool ->
    var group: AiToolGroup? = tool.group
    while (group != null && used.add(group)) group = group.parent
  }
  return PampaiGroup.entries.filter { it in used } + sets.flatMap { it.groups }.filter { it in used }
}

/**
 * Le app collegate che si possono montare insieme, nell'ordine in cui sono arrivate.
 *
 * Due app che portano gli stessi nomi -- quasi sempre la build di debug e quella di release della
 * stessa app, `dev.x` e `dev.x.debug`, che hanno lo stesso dominio -- farebbero fallire il
 * registry intero ("tool duplicati"), e con lui sparirebbero tutte le app collegate. Qui ne resta
 * una: vince il pacchetto di release, poi l'ordine alfabetico; le altre stanno fuori e lo si dice
 * in [warn]. Lo stesso per un'app che riusa un nome di Aria o che ha nomi doppi nel suo catalogo,
 * e, come ultima rete, per qualunque altra regola del registry che il suo catalogo rompesse: una
 * app storta resta fuori da sola, non si porta dietro le altre.
 */
internal fun <C> mountable(local: List<AiTool<C>>, sets: List<RemoteToolSet<C>>, warn: (String) -> Unit = {}): List<RemoteToolSet<C>> {
  val names = local.mapTo(HashSet()) { it.name }
  val groupIds = PampaiGroup.entries.mapTo(HashSet()) { it.id }
  val kept = mutableListOf<RemoteToolSet<C>>()
  val byPreference = sets.sortedWith(compareBy<RemoteToolSet<C>>({ isDebugBuild(it.catalog.packageName) }, { it.catalog.packageName }))
  for (set in byPreference) {
    val app = set.catalog.packageName
    val setNames = set.tools.map { it.name }
    val setGroups = set.groups.map { it.id }
    val taken = (setNames.filter { it in names } + setGroups.filter { it in groupIds }).distinct()
    if (taken.isNotEmpty()) {
      warn("$app resta fuori: ${taken.take(5).joinToString(", ")} ci sono gia' (un'altra build della stessa app?)")
      continue
    }
    if (setNames.toSet().size != setNames.size || setGroups.toSet().size != setGroups.size) {
      warn("$app resta fuori: il suo catalogo ha nomi doppi")
      continue
    }
    val candidate = kept + set
    val check = runCatching {
      val tools = local + candidate.flatMap { it.tools }
      ToolRegistry(tools, declaredGroups(tools, candidate), actionGroup = null)
    }
    if (check.isFailure) {
      warn("$app resta fuori: ${check.exceptionOrNull()?.message}")
      continue
    }
    kept += set
    names += setNames
    groupIds += setGroups
  }
  // L'ordine di prima: la preferenza serve solo a decidere chi resta.
  return sets.filter { set -> kept.any { it === set } }
}

/** La build di debug di un'app Pampa: stesso dominio della release, pacchetto con ".debug" in fondo. */
internal fun isDebugBuild(packageName: String): Boolean = packageName.endsWith(".debug")

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

  private fun build(offered: List<RemoteToolSet<PampaiToolContext>>): MountedCatalog {
    val local: List<AiTool<PampaiToolContext>> = ariaTools() + calcTools() + webTools() + screenTools() +
      clockTools() + reminderTools() + calendarTools() + contactTools() + notificationTools() + systemTools() + openTools() + infoTools() + musicTools()
    // Due build della stessa app non devono far sparire tutte le app collegate: ne resta una.
    val sets = mountable(local, offered) { message -> runCatching { Log.w(TAG, message) } }
    val tools = local.map { TracedTool(it) } + sets.flatMap { set -> set.tools.map { TracedTool(it, app = set.catalog.appLabel) } }
    // Solo i gruppi che hanno davvero dei tool (e i loro padri): una sottocategoria vuota nel
    // menu' del modello e' una porta su una stanza vuota.
    val registry = ToolRegistry(tools, declaredGroups(tools, sets), actionGroup = null)
    return MountedCatalog(registry, ariaRouter(registry), summaryOf(registry), preRules(sets), sets.map { it.catalog.packageName }.toSet())
  }

  /**
   * Una regola per app collegata: le parole del suo vocabolario (materie, fermate, luoghi) e il suo
   * nome decidono il gruppo che si apre con la categoria, cosi' "quando passa il 23" non chiama il
   * router remoto.
   */
  private fun preRules(sets: List<RemoteToolSet<PampaiToolContext>>): List<PreRouter.Rule> = sets.flatMap { set ->
    val group = set.groups.firstOrNull { it.loadsWithCategory } ?: set.groups.firstOrNull() ?: return@flatMap emptyList()
    // Le parole del mestiere: come si chiede una cosa a quell'app, anche senza nominarla.
    val trade = DOMAIN_WORDS[set.catalog.domain]?.let { PreRouter.Rule(group, it, weight = 2) }
    val words = (set.catalog.vocabulary + listOf(set.catalog.appLabel, set.catalog.domain))
      .map { Text.normalize(it) }
      .filter { it.length >= 3 }
      .distinct()
      .take(80)
    // Il vocabolario pesa meno: un nome di fermata puo' essere una parola qualsiasi ("Duomo"),
    // mentre "quando passa" parla di autobus e basta.
    val vocabulary = words.takeIf { it.isNotEmpty() }?.let { list ->
      PreRouter.Rule(group, "\\b(" + list.joinToString("|") { w -> Regex.escape(w) } + ")\\b", weight = 1)
    }
    listOfNotNull(trade, vocabulary)
  }

  private companion object {
    const val TAG = "RegistryHolder"

    /**
     * Come si chiede una cosa a ciascuna app, con le parole di tutti i giorni. Le sovrapposizioni
     * non sono un problema: due gruppi che rispondono alla stessa parola tolgono la certezza al
     * pre-router e la domanda va al router come suggerimento — un giro in piu' costa meno di una
     * categoria sbagliata.
     */
    val DOMAIN_WORDS: Map<String, String> = mapOf(
      "cv" to "\\b(vot[oi]|media|medie|compit[oi]|verific\\w*|interrogazion\\w*|prof\\w*|scuola|scolastic\\w*|registro|circolar\\w*|bacheca|assenz\\w*|giustific\\w*|materia|materie|lezion[ei]|pagella|classe)\\b",
      "meteo" to "\\b(meteo|che tempo|tempo fa|piov\\w*|piogg\\w*|nevic\\w*|neve|temperatur\\w*|grad[oi]|vento|umidit\\w*|nuvol\\w*|prevision[ei]|radar|allert[ae]|ombrello|tramonto|alba)\\b",
      "bus" to "\\b(bus|autobus|pullman|fermata|fermate|passagg\\w*|corsa|corse|tram|quando passa|come arrivo|come ci arrivo|capolinea)\\b",
      "store" to "\\b(store|installa\\w*|disinstall\\w*|aggiornament[oi]|aggiorna le app|catalogo|apk)\\b",
      "convert" to "\\b(conversion[ei] di file|formato del file|in pdf|in jpg|in png|in mp3|in mp4|in docx|da pdf a)\\b",
    )
  }
}

/** La categoria di un gruppo, per chi ha solo il gruppo. */
fun AiToolGroup.categoryId(): String? = resolvedCategory?.id
