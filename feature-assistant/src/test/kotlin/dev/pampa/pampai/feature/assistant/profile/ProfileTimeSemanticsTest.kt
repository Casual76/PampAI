package dev.pampa.pampai.feature.assistant.profile

import dev.pampa.pampai.core.assistant.research.SourceCandidate
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ProfileTimeSemanticsTest {
  @Test fun actualStringEpochFormHasVisibleAndDistinctSetupPeriods() {
    val candidate = SourceCandidate("synthetic", "synthetic.tools", "fluidify", "account", null, true)
    val form = SourceDraft(candidate, "account", section = "history_records", from = "2026-01-01", until = "2026-02-01")
    val label = scopeLabel(form.read())
    assertEquals(2, Regex("01 .+? 2026 00:00").findAll(label).count())
    assertTrue(label.contains("fine esclusa"))
    assertFalse(label.contains("solo l’ambito"))
    assertNotEquals(label, scopeLabel(form.copy(from = "2026-02-01", until = "2026-03-01").read()))
  }
  @Test fun utcProvenanceKeepsUtcTimesInWinterAndSummer() {
    val zone = profileTimeZone("UTC")
    assertEquals("UTC", zone.label)
    for (instant in listOf("2026-01-01T00:00:00Z", "2026-07-01T00:00:00Z")) {
      val at = Instant.parse(instant).toEpochMilli()
      assertTrue(dateLabel(at, zone.zone).endsWith("00:00"))
      val fact = readableValue("""{"track":{"name":"Synthetic"},"startedAt":$at,"measuredLocalMs":null}""", zone.zone)
      assertTrue(fact.contains("00:00 (UTC)"))
    }
  }
  @Test fun namedLocalZoneAppliesSeasonalOffset() {
    val zone = profileTimeZone("Europe/Rome")
    assertEquals("Europe/Rome", zone.label)
    assertTrue(dateLabel(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(), zone.zone).endsWith("01:00"))
    assertTrue(dateLabel(Instant.parse("2026-07-01T00:00:00Z").toEpochMilli(), zone.zone).endsWith("02:00"))
  }
  @Test fun unknownSourceZoneIsLabelledAsDeviceFallback() {
    val zone = profileTimeZone("UNKNOWN")
    assertEquals(ZoneId.systemDefault(), zone.zone)
    assertTrue(zone.label.contains("fuso del dispositivo"))
    assertTrue(zone.label.contains("fonte non disponibile"))
  }
}
