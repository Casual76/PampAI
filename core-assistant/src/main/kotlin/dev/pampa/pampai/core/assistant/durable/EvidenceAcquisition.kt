package dev.pampa.pampai.core.assistant.durable

import dev.pampa.pampai.core.assistant.research.ResearchRead
import kotlinx.serialization.json.*

/** Acquisition controls are checkpoint state, never canonical record content or source scope. */
internal object EvidenceAcquisition {
  fun canonicalRead(read: ResearchRead) = read.copy(allowTechnicalRefresh = false,
    arguments = JsonObject(read.arguments.filterKeys { it !in setOf("aggiorna", "cursore", "cursor", "dettaglio_offset", "dettaglio_hash") }))
  fun canonicalProvenance(value: String): String = runCatching {
    val p = Json.parseToJsonElement(value).jsonObject
    val read = Json.decodeFromJsonElement<ResearchRead>(p.getValue("read"))
    JsonObject(p + ("read" to Json.encodeToJsonElement(canonicalRead(read)))).toString()
  }.getOrDefault(value)
}
