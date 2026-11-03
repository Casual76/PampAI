package dev.pampa.pampai.feature.assistant.chat

import dev.antigravity.fluidengine.aiui.strings.FluidAssistantStrings
import dev.antigravity.fluidengine.aiui.strings.FluidChatStrings
import dev.pampa.pampai.core.assistant.service.AssistantNotifications

/**
 * Le parole del kit della chat dell'engine (`engine-ai-ui`) dette da Aria: i default italiani, con le
 * frasi che hanno un genere (Aria parla al femminile: "Non sono riuscita a trascrivere", "Fermata",
 * "Ci ho messo troppo") e quella che dice di chi si parla ("parte quando Aria ha finito"). Le
 * frasi di stato dei gruppi di strumenti sono dominio dell'app: arrivano da [AssistantNotifications].
 */
internal val AriaAssistantStrings = FluidAssistantStrings(
  title = "Aria",
  stopped = "Fermata",
  // `AssistantNotifications.statusFor` dice gia' "(modello profondo)": l'engine non lo ripete.
  deepSuffix = "",
  failTimeout = "Ci ho messo troppo. Riprova, o dividi la domanda in pezzi piu' piccoli.",
  failTranscription = "Non sono riuscita a trascrivere: riprova.",
  failureCopyHeader = "Aria · dettagli dell'errore",
  statusFor = { key, tier -> AssistantNotifications.statusFor(key, tier) },
)

internal val AriaChatStrings = FluidChatStrings(
  queued = "In coda: parte quando Aria ha finito",
  composerPlaceholder = "Chiedi ad Aria...",
  composerBusyPlaceholder = "Intanto scrivi la prossima...",
  composerEditing = "Modifica e rinvia...",
  assistantLabel = "Aria",
)
