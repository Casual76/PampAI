package dev.pampa.pampai.core.assistant.prompt

import dev.pampa.pampai.core.assistant.prompt.QuickCommands.Kind
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class QuickCommandsTest {

  private fun kind(question: String): Kind? = QuickCommands.match(question)?.kind

  private val now = ZonedDateTime.of(2026, 9, 27, 18, 32, 0, 0, ZoneId.of("Europe/Rome"))

  @Test
  fun `i timer detti in tanti modi partono senza modello`() {
    listOf(
      "imposta un timer di 10 minuti",
      "Imposta un timer di 10 minuti.",
      "timer 5 minuti",
      "timer di un'ora e mezza",
      "metti un timer di 45 secondi",
      "puoi impostare un timer di 3 minuti?",
      "mi metti un timer di 20 minuti per favore",
      "Aria, timer di 1 ora e 30",
      "conto alla rovescia di 2 minuti",
      "fai partire un timer di 12 minuti per la pasta",
      "10 minuti di timer",
    ).forEach { assertEquals(it, Kind.TIMER, kind(it)) }
    val pasta = QuickCommands.match("fai partire un timer di 12 minuti per la pasta")!!
    assertEquals("timer_crea", pasta.tool)
    assertEquals("12 minuti", pasta.args["durata"])
    assertEquals("pasta", pasta.args["etichetta"])
  }

  @Test
  fun `le sveglie con un'ora chiara si impostano da sole`() {
    assertEquals("07:00", QuickCommands.match("svegliami alle 7")?.args?.get("ora"))
    assertEquals("06:30", QuickCommands.match("sveglia alle 6 e mezza")?.args?.get("ora"))
    assertEquals("06:45", QuickCommands.match("metti una sveglia alle 6:45")?.args?.get("ora"))
    assertEquals("19:00", QuickCommands.match("imposta una sveglia alle 7 di sera")?.args?.get("ora"))
    assertEquals("07:15", QuickCommands.match("svegliami domani alle 7.15", LocalTime.of(22, 0))?.args?.get("ora"))
    // Alle 10 "domani alle 11" non e' la prossima occorrenza delle 11: lo fa il modello.
    assertNull(QuickCommands.match("svegliami domani alle 11", LocalTime.of(10, 0)))
    assertEquals(Kind.NEXT_ALARM, kind("a che ora suona la sveglia?"))
    assertEquals(Kind.NEXT_ALARM, kind("ho una sveglia per domani?"))
  }

  @Test
  fun `telefono, ora e musica`() {
    assertEquals("on", QuickCommands.match("accendi la torcia")?.args?.get("stato"))
    assertEquals("off", QuickCommands.match("spegni la torcia")?.args?.get("stato"))
    assertEquals("on", QuickCommands.match("puoi accendere la torcia")?.args?.get("stato"))
    assertEquals("su", QuickCommands.match("alza il volume")?.args?.get("livello"))
    assertEquals("giu", QuickCommands.match("abbassa un po' il volume")?.args?.get("livello"))
    assertEquals("40", QuickCommands.match("volume al 40%")?.args?.get("livello"))
    assertEquals("vibrazione", QuickCommands.match("metti il telefono in vibrazione")?.args?.get("modo"))
    assertEquals("normale", QuickCommands.match("togli il silenzioso")?.args?.get("modo"))
    assertEquals("on", QuickCommands.match("attiva non disturbare")?.args?.get("stato"))
    assertEquals("off", QuickCommands.match("disattiva il non disturbare")?.args?.get("stato"))
    assertEquals(Kind.TIME, kind("che ore sono?"))
    assertEquals(Kind.DATE, kind("che giorno è oggi"))
    assertEquals(Kind.BATTERY, kind("quanta batteria ho?"))
    assertEquals("pausa", QuickCommands.match("metti in pausa la musica")?.args?.get("azione"))
    assertEquals("riproduci", QuickCommands.match("riprendi la musica")?.args?.get("azione"))
    assertEquals("indietro", QuickCommands.match("canzone precedente")?.args?.get("azione"))
    assertEquals("avanti", QuickCommands.match("prossima canzone")?.args?.get("azione"))
    assertEquals("whatsapp", QuickCommands.match("apri WhatsApp")?.args?.get("nome"))
    assertEquals("calcolatrice", QuickCommands.match("apri la calcolatrice")?.args?.get("nome"))
    assertEquals("spotify", QuickCommands.match("puoi aprire spotify?")?.args?.get("nome"))
  }

  @Test
  fun `i calcoli secchi si fanno da soli`() {
    assertEquals("12*7", QuickCommands.match("quanto fa 12*7?")?.args?.get("espressione"))
    assertEquals("3*4", QuickCommands.match("3x4")?.args?.get("espressione"))
    assertEquals(Kind.CALC, kind("2 + 2"))
    assertNull(kind("12/05"))
    assertNull(kind("-3"))
  }

  @Test
  fun `nel dubbio decide il modello`() {
    listOf(
      "imposta un timer di 10 minuti e poi cercami una ricetta",
      "timer di 10 minuti, e dimmi il meteo",
      "se piove svegliami alle 7",
      "come si imposta un timer?",
      "cos'è un timer",
      "apri la pagina di wikipedia sui gatti",
      "apri il link che mi hai mandato",
      "avvia la musica",
      "accendi la luce del salotto",
      "quanto fa il 15% di 340",
      "che ore sono a tokyo",
      "svegliami quando arrivo",
      "timer",
      "metti una sveglia",
      "spiegami come funziona la batteria del telefono",
      "avvisami alle 18",
      "la prossima",
      "prossima",
      "salta",
      "pausa",
      "riprendi",
      "lancia una moneta",
      "",
    ).forEach { assertNull(it, QuickCommands.match(it)) }
  }

  @Test
  fun `le risposte sono frasi, e un errore torna al modello`() {
    val timer = QuickCommands.match("timer di 10 minuti")!!
    assertEquals(
      "Timer di 10 minuti avviato: suona alle 18:42.",
      QuickCommands.reply(timer, "fatto: timer di 10 minuti avviato\nsuona alle: 2026-09-27 (dom) 18:42", now),
    )
    assertNull(QuickCommands.reply(timer, "errore: nessuna app Orologio risponde ai timer su questo telefono", now))
    assertNull(QuickCommands.reply(timer, "le azioni sono disattivate nelle impostazioni di PampAI: dillo all'utente", now))

    assertEquals("Sono le 18:32.", QuickCommands.reply(QuickCommands.match("che ore sono")!!, null, now))
    assertEquals("Oggi è domenica 27 settembre 2026.", QuickCommands.reply(QuickCommands.match("che giorno è")!!, null, now))
    assertEquals("Torcia accesa.", QuickCommands.reply(QuickCommands.match("accendi la torcia")!!, "fatto: torcia accesa", now))
    assertEquals("Batteria al 76%, in carica.", QuickCommands.reply(QuickCommands.match("quanta batteria ho")!!, "batteria: 76%\nstato: in carica\nrisparmio energetico: spento", now))
    assertEquals("Sveglia impostata alle 07:00 (domani).", QuickCommands.reply(QuickCommands.match("svegliami alle 7")!!, "fatto: sveglia impostata alle 07:00", now))
    assertNotNull(QuickCommands.reply(QuickCommands.match("quanto fa 12*7")!!, "espressione: 12*7\nrisultato: 84", now))
  }
}
