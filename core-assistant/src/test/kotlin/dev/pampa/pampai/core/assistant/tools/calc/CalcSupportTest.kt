package dev.pampa.pampai.core.assistant.tools.calc

import dev.pampa.pampai.core.assistant.tools.web.Currencies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalcSupportTest {

  @Test
  fun `le unita' si convertono per alias italiani e simboli`() {
    val km = UnitConverter.convert(1500.0, "metri", "km") as UnitConverter.Result.Ok
    assertEquals(1.5, km.value, 1e-9)
    val f = UnitConverter.convert(100.0, "°C", "fahrenheit") as UnitConverter.Result.Ok
    assertEquals(212.0, f.value, 1e-9)
    val lb = UnitConverter.convert(1.0, "kg", "libbre") as UnitConverter.Result.Ok
    assertEquals(2.2046, lb.value, 1e-3)
    val gb = UnitConverter.convert(2048.0, "MB", "GB") as UnitConverter.Result.Ok
    assertEquals(2.0, gb.value, 1e-9)
    assertTrue(UnitConverter.convert(1.0, "kg", "km") is UnitConverter.Result.Incompatible)
    assertTrue(UnitConverter.convert(1.0, "parsec", "km") is UnitConverter.Result.UnknownUnit)
  }

  @Test
  fun `i numeri si scrivono all'italiana e senza zeri inutili`() {
    assertEquals("1,5", 1.5.pretty())
    assertEquals("2", 2.0.pretty())
    assertEquals("0,333333", (1.0 / 3).pretty())
  }

  @Test
  fun `le zone si trovano per citta' e per id`() {
    assertEquals("Asia/Tokyo", TimeZones.resolve("Tokyo")?.id)
    assertEquals("America/New_York", TimeZones.resolve("nuova york")?.id)
    assertEquals("Europe/London", TimeZones.resolve("Europe/London")?.id)
    assertNull(TimeZones.resolve("Atlantide"))
  }

  @Test
  fun `le valute si riconoscono per nome e per codice`() {
    assertEquals("USD", Currencies.code("dollari"))
    assertEquals("EUR", Currencies.code("euro"))
    assertEquals("GBP", Currencies.code("gbp"))
    assertEquals("CHF", Currencies.code("franchi svizzeri"))
    assertNull(Currencies.code("conchiglie"))
  }
}
