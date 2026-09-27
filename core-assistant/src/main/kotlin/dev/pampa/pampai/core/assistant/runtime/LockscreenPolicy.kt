package dev.pampa.pampai.core.assistant.runtime

import dev.antigravity.fluidengine.ai.tools.ToolRegistry
import dev.pampa.pampai.core.assistant.tools.PampaiGroup
import dev.pampa.pampai.core.assistant.tools.PampaiToolContext

/**
 * Cosa Aria puo' fare dal telefono bloccato.
 *
 * L'overlay si apre anche sulla schermata di blocco, cioe' per chiunque abbia il telefono in mano.
 * Prima li' valevano tutti gli strumenti tranne lo schermo: bastava tenere premuto il tasto di
 * accensione per farsi leggere notifiche, contatti, calendario, promemoria, la memoria di Aria o i
 * voti del registro. Ora da bloccato restano solo le cose che non dicono niente del proprietario:
 * sveglie e timer, torcia e volume, batteria, calcoli, il web, la musica.
 */
internal object LockscreenPolicy {

  val allowedGroups = setOf(PampaiGroup.OROLOGIO, PampaiGroup.SISTEMA, PampaiGroup.INFO, PampaiGroup.WEB, PampaiGroup.CALCOLO, PampaiGroup.RIPRODUZIONE)

  /** Dentro i gruppi ammessi, quello che resta personale: dove si trova il telefono. */
  val deniedTools = setOf("posizione")

  fun restrict(registry: ToolRegistry<PampaiToolContext>): ToolRegistry<PampaiToolContext> =
    ToolRegistry(
      tools = registry.tools.filter { it.group in allowedGroups && it.name !in deniedTools },
      groups = registry.groups,
      actionGroup = registry.actionGroup,
    )
}
