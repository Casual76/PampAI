package dev.pampa.pampai.core.assistant.prompt

import dev.antigravity.fluidengine.ai.orchestrator.AnswerChip

/** I chip che Aria puo' proporre in fondo a una risposta, e cosa vogliono dire per la UI. */
object AriaChips {
  const val APP = "apri"
  const val URL = "url"
  const val CONVERSATION = "conversazione"
  const val PLACE = "luogo"
  const val SETTINGS = "impostazioni"
  const val REMINDER = "promemoria"

  private val known = setOf(APP, URL, CONVERSATION, PLACE, SETTINGS, REMINDER)

  fun accepts(chip: AnswerChip): Boolean = chip.id in known && when (chip.id) {
    URL -> chip.value?.startsWith("http") == true
    APP, CONVERSATION, PLACE -> !chip.value.isNullOrBlank()
    else -> true
  }
}
