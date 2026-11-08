package dev.pampa.pampai.core.assistant.receipts

import android.provider.AlarmClock
import dev.antigravity.fluidengine.ai.tools.*
import kotlinx.serialization.json.*

object ClockReceipts {
  /** Call only AFTER successful intent dispatch. Android exposes no timer readback or timer ID. */
  fun timer(seconds: Int, title: String?): ToolOutput = ToolOutput(
    "Richiesta timer di $seconds secondi inviata a Orologio. Avvio e scadenza non verificabili.",
    result = ToolResultDetails(ToolOutcome.ACCEPTED,
      data = buildJsonObject { put("kind", "timer"); put("requestedDurationSeconds", seconds); title?.let { put("title", it) }; put("openAction", AlarmClock.ACTION_SHOW_TIMERS) },
      sources = listOf(ToolSourceRef("android.clock"))))
}
