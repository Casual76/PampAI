package dev.pampa.pampai.feature.assistant.settings

import dev.antigravity.fluidengine.foundation.AvailableAppUpdate
import dev.antigravity.fluidengine.ui.fluid.FluidUpdateUiState
import dev.antigravity.fluidengine.ui.fluid.release
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckOutcomeTest {

  private val found = AvailableAppUpdate(
    version = "2.0.0",
    changelog = "Fluid Engine 3.0.",
    releaseTag = "stable-pampai-v2.0.0",
    apkAsset = "pampai-2.0.0.apk",
    downloadUrl = "https://example.invalid/pampai-2.0.0.apk",
    sizeBytes = 14_000_000L,
  )

  @Test
  fun `nessuna versione piu' nuova non e' un errore`() {
    val outcome = updateCheckOutcome(Result.success(null))
    assertEquals(FluidUpdateUiState.UpToDate, outcome.state)
    assertNull(outcome.update)
  }

  @Test
  fun `una versione trovata si tiene da parte e si offre con peso e note`() {
    val outcome = updateCheckOutcome(Result.success(found))
    assertSame(found, outcome.update)
    val state = outcome.state as FluidUpdateUiState.Available
    assertEquals("2.0.0", state.release.version)
    assertEquals(14_000_000L, state.release.sizeBytes)
    assertEquals("Fluid Engine 3.0.", state.release.notes)
  }

  @Test
  fun `un controllo che non riesce dice di riprovare e non lascia una versione vecchia da installare`() {
    val outcome = updateCheckOutcome(Result.failure(java.io.IOException("offline")))
    val state = outcome.state as FluidUpdateUiState.CheckFailed
    assertEquals("offline", state.message)
    assertNull(outcome.update)
    assertNull(outcome.state.release)
    assertTrue(outcome.state !is FluidUpdateUiState.UpToDate)
  }
}
