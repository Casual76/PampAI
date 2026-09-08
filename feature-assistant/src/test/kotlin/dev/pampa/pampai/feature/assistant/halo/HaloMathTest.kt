package dev.pampa.pampai.feature.assistant.halo

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HaloMathTest {

  @Test
  fun leMacchieRestanoEquispaziateInOgniMomento() {
    val n = 14
    for (time in listOf(0f, 0.4f, 3.7f, 41f, 250f)) {
      val fractions = (0 until n).map { blobFraction(it, n, time) }.sorted()
      fractions.forEach { assertTrue("fuori da [0,1): $it", it >= 0f && it < 1f) }
      // Il passo e' 1/n; l'ondeggiare sposta ogni macchia al massimo di HaloWobble, quindi due
      // vicine si avvicinano al massimo di 2*HaloWobble e non si sovrappongono mai.
      val gaps = fractions.zipWithNext { a, b -> b - a } + (fractions.first() + 1f - fractions.last())
      gaps.forEach { gap ->
        assertTrue("passo $gap a t=$time", gap > 1f / n - 2f * HaloWobble - 1e-4f && gap < 1f / n + 2f * HaloWobble + 1e-4f)
      }
    }
  }

  @Test
  fun leMacchieAvanzanoConIlTempoNelVersoGiusto() {
    val n = 14
    val before = blobFraction(0, n, 0f)
    val after = blobFraction(0, n, 1f)
    // In un secondo al tempo 1 la macchia fa HaloBaseSpeed giri, piu' o meno l'ondeggiare.
    assertEquals(HaloBaseSpeed, after - before, 2f * HaloWobble)
  }

  @Test
  fun ilBersaglioSegueLaVoceSoloInAscolto() {
    assertEquals(0.30f, HaloMood.LISTENING.amplitudeTarget(0f), 1e-6f)
    assertEquals(1.00f, HaloMood.LISTENING.amplitudeTarget(1f), 1e-6f)
    assertEquals(1.00f, HaloMood.LISTENING.amplitudeTarget(4f), 1e-6f)
    assertEquals(0.30f, HaloMood.LISTENING.amplitudeTarget(-1f), 1e-6f)
    assertEquals(0f, HaloMood.HIDDEN.amplitudeTarget(1f), 0f)
    assertEquals(0.35f, HaloMood.IDLE.amplitudeTarget(1f), 1e-6f)
    assertEquals(0.6f, HaloMood.ERROR.amplitudeTarget(0f), 1e-6f)
    assertEquals(HaloMood.WORKING.amplitudeTarget(0f), HaloMood.WRITING.amplitudeTarget(1f), 1e-6f)
  }

  @Test
  fun laVelocitaCresceConLUmoreEIlTempoLaRispetta() {
    assertTrue(HaloMood.WRITING.perimeterSpeed() > HaloMood.LISTENING.perimeterSpeed())
    assertTrue(HaloMood.LISTENING.perimeterSpeed() > HaloMood.WORKING.perimeterSpeed())
    assertTrue(HaloMood.WORKING.perimeterSpeed() > HaloMood.IDLE.perimeterSpeed())
    assertTrue(HaloMood.IDLE.perimeterSpeed() > 0f)
    HaloMood.entries.forEach { mood -> assertEquals(mood.perimeterSpeed(), mood.tempo() * HaloBaseSpeed, 1e-6f) }
    assertEquals(1f, HaloMood.LISTENING.tempo(), 1e-6f)
  }

  @Test
  fun lAmpiezzaSaleSveltaEScendePianoEScalaSulDt() {
    val frame = 1f / ReferenceHz
    assertEquals(AttackPerFrame, approach(0f, 1f, frame), 1e-5f)
    assertEquals(1f - ReleasePerFrame, approach(1f, 0f, frame), 1e-5f)
    // Due mezzi fotogrammi fanno un fotogramma: a 120 Hz l'alone si muove uguale.
    val twoHalves = approach(approach(0f, 1f, frame / 2), 1f, frame / 2)
    assertEquals(approach(0f, 1f, frame), twoHalves, 1e-5f)
    // Non supera mai il bersaglio, nemmeno con un dt lungo.
    assertTrue(approach(0f, 1f, 1f) <= 1f)
    assertTrue(approach(1f, 0f, 1f) >= 0f)
  }

  @Test
  fun ilRaggioRestaFraMezzoEUnoVirgolaDue() {
    val n = 14
    for (amplitude in listOf(0f, 0.35f, 0.6f, 1f)) {
      for (time in listOf(0f, 1.3f, 7.9f, 100f)) {
        for (i in 0 until n) {
          val factor = blobRadiusFactor(amplitude, blobBreath(i, n, time))
          assertTrue("raggio $factor", factor >= 0.75f * 0.7f - 1e-5f && factor <= 1.2f + 1e-5f)
        }
      }
    }
    assertEquals(BlobFloorAlpha, blobAlpha(0f, 1f, 0.45f), 1e-6f)
    assertEquals(0.45f, blobAlpha(1f, 1f, 0.45f), 1e-6f)
    assertEquals(0f, blobAlpha(1f, 0f, 0.45f), 0f)
  }

  @Test
  fun ilCicloDeiColoriGiraEInterpola() {
    val red = Color(0xFFFF0000)
    val green = Color(0xFF00FF00)
    val blue = Color(0xFF0000FF)
    val cycle = listOf(red, green, blue)
    assertEquals(red, sampleCycle(cycle, 0f))
    assertEquals(green, sampleCycle(cycle, 1f / 3f))
    assertEquals(sampleCycle(cycle, 0.2f), sampleCycle(cycle, 1.2f))
    assertEquals(sampleCycle(cycle, 0.2f), sampleCycle(cycle, -0.8f))
    assertEquals(red, sampleCycle(listOf(red), 0.77f))
    // A meta' strada fra rosso e verde non e' ne' l'uno ne' l'altro.
    val mid = sampleCycle(cycle, 1f / 6f)
    assertTrue(mid != red && mid != green)
    assertEquals(0f, wrap01(1f), 0f)
    assertEquals(0.25f, wrap01(-0.75f), 1e-6f)
  }
}
