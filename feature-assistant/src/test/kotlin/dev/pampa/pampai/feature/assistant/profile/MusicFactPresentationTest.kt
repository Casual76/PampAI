package dev.pampa.pampai.feature.assistant.profile

import org.junit.Assert.*
import org.junit.Test

class MusicFactPresentationTest {
  @Test fun actualHistoryShapeShowsTrackMeasuredTimeAndProvenance() {
    val description = readableValue("""{"id":"example","track":{"uri":"spotify:track:example","name":"Notturno è blu","artist":"Artista sintetico","durationMs":60000},"startedAt":1767225600000,"endedAt":1767225640000,"measuredLocalMs":40000,"qualified":true,"provenance":"local_measurement"}""")
    assertTrue(description.contains("Notturno è blu"))
    assertTrue(description.contains("Artista sintetico"))
    assertTrue(description.contains("Tempo locale misurato: 40 s"))
    assertTrue(description.contains("misura locale"))
    assertFalse(description.contains("Dettagli leggibili non disponibili"))
    assertFalse(description.contains("60 s"))
  }
  @Test fun importedObservationDoesNotInventDurationFromTrackLength() {
    val description = readableValue("""{"track":{"name":"Importato","artist":"Artista","durationMs":60000},"measuredLocalMs":null,"qualified":false,"provenance":"spotify_observation"}""")
    assertTrue(description.contains("Tempo locale misurato: sconosciuto"))
    assertTrue(description.contains("import Spotify"))
    assertFalse(description.contains("0 s"))
    assertFalse(description.contains("60"))
    assertFalse(description.contains("Soglia locale"))
    assertFalse(description.contains("non qualificato"))
  }
  @Test fun actualAggregateKeepsImportedCountsAndUnknownDurationSeparate() {
    val description = readableValue("""{"from":1767225600000,"until":1767312000000,"countedPlays":5,"localCountedPlays":2,"importedOnlyPlays":3,"measuredLocalMs":0,"importedDurationMs":null,"durationPrecision":"observed_local_intervals_only","trackCount":4,"artistCount":2}""")
    assertTrue(description.contains("Ascolti conteggiati: 5"))
    assertTrue(description.contains("Ascolti solo importati: 3"))
    assertTrue(description.contains("Tempo locale misurato: 0 s"))
    assertTrue(description.contains("Durata degli ascolti importati: sconosciuta"))
    assertTrue(description.contains("Brani distinti: 4"))
  }
  @Test fun nestedTrackAndArtistCountsStayReadable() {
    assertTrue(readableValue("""{"track":{"name":"Esempio","artists":[{"name":"Primo"},{"name":"Secondo"}]},"plays":3,"localPlays":2,"importedOnlyPlays":1}""").contains("Primo, Secondo"))
    assertEquals("Artista: Esempio · Ascolti: 3", readableValue("""{"artist":"Esempio","plays":3}"""))
  }
  @Test fun reconciledShortMeasurementDoesNotClaimLocalThreshold() {
    val description = readableValue("""{"track":{"name":"Reconciled","durationMs":60000},"measuredLocalMs":5000,"qualified":true,"provenance":"local_measurement_and_spotify_observation"}""")
    assertTrue(description.contains("Tempo locale misurato: 5 s"))
    assertTrue(description.contains("qualificato per il conteggio"))
    assertTrue(description.contains("Spotify"))
    assertFalse(description.contains("Soglia locale"))
  }
}
