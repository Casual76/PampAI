package dev.pampa.pampai.core.assistant.runtime

import dev.antigravity.fluidengine.ai.provider.ToolSpec
import dev.antigravity.fluidengine.ai.tools.AiTool
import dev.antigravity.fluidengine.ai.tools.AiToolGroup
import dev.antigravity.fluidengine.ai.tools.Args.str
import dev.antigravity.fluidengine.ai.tools.Schema
import dev.antigravity.fluidengine.ai.tools.ToolOutput
import dev.antigravity.fluidengine.ai.tools.ToolRegistry
import dev.pampa.pampai.core.assistant.tools.PampaiGroup
import dev.pampa.pampai.core.assistant.tools.PampaiToolContext
import kotlinx.serialization.json.JsonObject

/**
 * Cosa Aria puo' fare dal telefono bloccato.
 *
 * L'overlay si apre anche sulla schermata di blocco, cioe' per chiunque abbia il telefono in mano.
 * Da bloccato restano solo le cose che non dicono niente del proprietario e non cambiano come il
 * telefono lo raggiunge: creare sveglie e timer, torcia, volume dei media, batteria, calcoli, il
 * web (anche il meteo, cercato li'), il controllo della musica.
 *
 * E' un elenco di nomi ammessi, non di gruppi: uno strumento nuovo, anche in un gruppo "innocuo",
 * resta fuori finche' qualcuno non lo aggiunge qui sapendo cosa fa. Prima valeva il contrario, e
 * con il gruppo `sistema` passavano anche suoneria e non disturbare (un estraneo poteva mettere il
 * telefono in silenzioso), con `orologio` anche togliere le sveglie.
 */
internal object LockscreenPolicy {

  /** Gli unici strumenti ammessi da bloccato. Tutti locali: le app collegate parlano del proprietario. */
  val allowedTools: Set<String> = setOf(
    // Orologio: creare si', leggere quelle impostate o toglierle no. Il posticipo agisce solo
    // sulla sveglia che sta suonando, come il tasto sulla schermata di blocco.
    "sveglia_crea", "sveglia_posticipa", "timer_crea",
    // Il telefono: torcia, volume (solo dei media, vedi [LockedVolume]), batteria.
    "torcia", "volume", "batteria",
    // Il web e i conti: niente di personale entra, niente esce.
    "cerca_web", "leggi_pagina", "wikipedia", "definizione",
    "calcola", "converti_unita", "fuso_orario", "data_calcola", "valuta",
    // La musica: il controllo della riproduzione. Non la coda (cosa sta per ascoltare) ne' i
    // preferiti (`musica_salva` scrive nella sua libreria), ne' la libreria.
    "musica_riproduci", "musica_controllo", "musica_adesso", "musica_shuffle_ripeti", "musica_radio", "musica_volume",
  )

  /** Vero se lo strumento si puo' usare da bloccato: per nome, e solo se e' di Aria (non di un'app collegata). */
  fun allows(tool: AiTool<*>): Boolean = tool.name in allowedTools && tool.group is PampaiGroup

  /**
   * Il registry da bloccato: gli strumenti ammessi e solo i gruppi che ne contengono qualcuno (con
   * i loro padri), cosi' ne' `apri_categoria` ne' `apri_sottocategoria` propongono porte chiuse.
   */
  fun restrict(registry: ToolRegistry<PampaiToolContext>): ToolRegistry<PampaiToolContext> {
    val tools = registry.tools.filter { allows(it) }.map { if (it.name == "volume") LockedVolume(it) else it }
    val kept = HashSet<AiToolGroup>()
    tools.forEach { tool ->
      var group: AiToolGroup? = tool.group
      while (group != null && kept.add(group)) group = group.parent
    }
    return ToolRegistry(
      tools = tools,
      groups = registry.groups.filter { it in kept },
      actionGroup = registry.actionGroup?.takeIf { it in kept },
    )
  }

  /**
   * Il volume da bloccato tocca solo i media. Suoneria e notifiche a zero sono un silenzioso
   * mascherato (che da bloccato non si puo' mettere), la sveglia a zero una sveglia che non suona.
   */
  private class LockedVolume(private val inner: AiTool<PampaiToolContext>) : AiTool<PampaiToolContext> by inner {
    override val description = "Legge o imposta il volume dei media (musica, video). Livello in percento, oppure su/giu'/muto/massimo. Dal telefono bloccato solo questo volume."
    override val parameters = Schema.obj(mapOf("livello" to Schema.str("0-100, oppure \"su\", \"giu'\", \"muto\", \"massimo\" (senza: dice il livello)")))

    // La delega passerebbe lo schema di `inner`, con il `flusso`: lo schema lo rifa' qui.
    override val spec: ToolSpec get() = ToolSpec(name, description, parameters)

    override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput = volumeRefusal(args) ?: inner.run(args, ctx)
  }

  /** Il rifiuto per un volume diverso dai media, o null se si puo' procedere. */
  fun volumeRefusal(args: JsonObject): ToolOutput? {
    val stream = args.str("flusso") ?: return null
    return if (stream == "media") null else ToolOutput.error("dal telefono bloccato si cambia solo il volume dei media: per la suoneria, le notifiche o la sveglia va sbloccato")
  }
}
