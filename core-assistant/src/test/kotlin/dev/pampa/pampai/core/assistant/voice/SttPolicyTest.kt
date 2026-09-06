package dev.pampa.pampai.core.assistant.voice

import dev.pampa.pampai.core.assistant.settings.SttMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SttPolicyTest {

  @Test
  fun dualNeedsFeedableRecognizerAndKeys() {
    assertEquals(SttMode.DUAL, SttPolicy.resolve(SttMode.DUAL, canBeFed = true, systemAvailable = true, sttKeys = true))
    assertEquals(SttMode.WHISPER, SttPolicy.resolve(SttMode.DUAL, canBeFed = false, systemAvailable = true, sttKeys = true))
    assertEquals(SttMode.SYSTEM, SttPolicy.resolve(SttMode.DUAL, canBeFed = true, systemAvailable = true, sttKeys = false))
    assertEquals(SttMode.WHISPER, SttPolicy.resolve(SttMode.DUAL, canBeFed = false, systemAvailable = false, sttKeys = false))
  }

  @Test
  fun whisperWithoutKeysFallsBackToSystemWhenThereIsOne() {
    assertEquals(SttMode.SYSTEM, SttPolicy.resolve(SttMode.WHISPER, canBeFed = false, systemAvailable = true, sttKeys = false))
    assertEquals(SttMode.WHISPER, SttPolicy.resolve(SttMode.WHISPER, canBeFed = false, systemAvailable = false, sttKeys = false))
    assertEquals(SttMode.WHISPER, SttPolicy.resolve(SttMode.WHISPER, canBeFed = true, systemAvailable = true, sttKeys = true))
  }

  @Test
  fun systemWithoutRecognizerGoesToWhisper() {
    assertEquals(SttMode.SYSTEM, SttPolicy.resolve(SttMode.SYSTEM, canBeFed = false, systemAvailable = true, sttKeys = true))
    assertEquals(SttMode.WHISPER, SttPolicy.resolve(SttMode.SYSTEM, canBeFed = false, systemAvailable = false, sttKeys = true))
  }

  @Test
  fun yesNoUnderstandsShortAnswers() {
    assertTrue(YesNo.parse("Sì")!!)
    assertTrue(YesNo.parse("si, vai")!!)
    assertTrue(YesNo.parse("ok procedi.")!!)
    assertTrue(YesNo.parse("va bene")!!)
    assertFalse(YesNo.parse("No")!!)
    assertFalse(YesNo.parse("no, annulla")!!)
    assertFalse(YesNo.parse("lascia stare")!!)
    assertFalse(YesNo.parse("non farlo")!!)
  }

  @Test
  fun yesNoRefusesTheAmbiguous() {
    assertNull(YesNo.parse(""))
    assertNull(YesNo.parse("che ore sono"))
    assertNull(YesNo.parse("forse"))
    // "sì" sotto una frase negativa vince il no: e' la scelta prudente.
    assertFalse(YesNo.parse("sì no aspetta")!!)
  }
}
