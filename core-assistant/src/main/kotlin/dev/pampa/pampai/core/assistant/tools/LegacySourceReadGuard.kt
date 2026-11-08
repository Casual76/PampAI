package dev.pampa.pampai.core.assistant.tools

import dev.antigravity.fluidengine.ai.tools.*
import kotlinx.serialization.json.JsonObject

/** MediaBrowser has no provable account/student/period lease. Do not guess from model arguments. */
internal fun guardLegacySourceRead(tool: AiTool<PampaiToolContext>): AiTool<PampaiToolContext> {
  if (tool.name !in setOf("musica_adesso", "musica_coda", "musica_cerca", "musica_playlist", "playlist_brani",
      "musica_recenti", "musica_download", "musica_dispositivi")) return tool
  return object : AiTool<PampaiToolContext> by tool {
    private fun denied() = ToolOutput("Lettura privata legacy senza scope verificabile: usa una connessione con source-proof.",
      result = ToolResultDetails(ToolOutcome.ERROR, error = ToolError("UNSCOPED_LEGACY_SOURCE_READ", "Scope non verificabile", false)))
    override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput = denied()
    override suspend fun run(args: JsonObject, ctx: PampaiToolContext, execution: ToolExecution): ToolOutput = denied()
  }
}
