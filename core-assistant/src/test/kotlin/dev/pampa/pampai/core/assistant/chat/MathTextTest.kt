package dev.pampa.pampai.core.assistant.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class MathTextTest {

  @Test fun `la formula di Gemini non sparisce`() {
    assertEquals("l'**anidride carbonica** (CO₂) dall'aria", MathText.rewrite("l'**anidride carbonica** (\$\\text{CO}_2\$) dall'aria"))
  }

  @Test fun `pedici e apici in unicode`() {
    assertEquals("6 CO₂ + 6 H₂O → C₆H₁₂O₆ + 6 O₂", MathText.toPlain("6\\,\\text{CO}_2 + 6\\,\\text{H}_2\\text{O} \\rightarrow \\text{C}_6\\text{H}_{12}\\text{O}_6 + 6\\,\\text{O}_2"))
    assertEquals("x² + 1", MathText.toPlain("x^2 + 1"))
    assertEquals("e^(iπ)", MathText.toPlain("e^{i\\pi}"))
  }

  @Test fun `frazioni, radici e simboli`() {
    assertEquals("(a + b)/2", MathText.toPlain("\\frac{a + b}{2}"))
    assertEquals("√2 ≈ 1.41", MathText.toPlain("\\sqrt{2} \\approx 1.41"))
    assertEquals("25 °C", MathText.toPlain("25\\,^\\circ\\text{C}"))
  }

  @Test fun `delimitatori a blocco`() {
    assertEquals("Energia:\n\nE = mc²\n", MathText.rewrite("Energia:\n\n\$\$E = mc^2\$\$\n"))
    assertEquals("vale a/b qui", MathText.rewrite("vale \\(\\frac{a}{b}\\) qui"))
  }

  @Test fun `i dollari veri restano dollari`() {
    val prices = "Costa \$5 e il deluxe \$10, cioe' da \$5 a \$10."
    assertEquals(prices, MathText.rewrite(prices))
    assertEquals("Ho \$ 20 in tasca", MathText.rewrite("Ho \$ 20 in tasca"))
  }

  @Test fun `il codice non si tocca`() {
    val code = "Usa `\$x_1\$` oppure:\n```\necho \$HOME_\$USER\n```\n"
    assertEquals(code, MathText.rewrite(code))
  }

  @Test fun `testo senza formule identico`() {
    val plain = "Niente matematica qui, solo **grassetto** e un elenco:\n- uno\n- due"
    assertEquals(plain, MathText.rewrite(plain))
  }
}
