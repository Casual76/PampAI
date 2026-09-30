package dev.pampa.pampai.core.assistant.prompt

import dev.pampa.pampai.core.assistant.prompt.QuickCommands.Kind
import dev.pampa.pampai.core.assistant.prompt.QuickCommands.Match
import dev.pampa.pampai.core.assistant.prompt.QuickCommands.Outcome
import dev.pampa.pampai.core.assistant.tools.ACTIONS_OFF
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickCommandsTest {

  private fun kind(question: String): Kind? = QuickCommands.match(question)?.kind

  private fun alarmAt(question: String, now: LocalTime = LocalTime.of(22, 0)): String? = QuickCommands.match(question, now)?.args?.get("ora")

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
  fun `la cortesia dopo la virgola non manda la frase al modello`() {
    assertEquals(Kind.TIMER, kind("timer di 10 minuti, grazie"))
    assertEquals(Kind.TIMER, kind("Metti un timer di 5 minuti, per favore!"))
    assertEquals(Kind.TORCH, kind("accendi la torcia, grazie mille"))
    assertEquals(Kind.TORCH, kind("Per favore, accendi la torcia"))
    assertEquals(Kind.TORCH, kind("Aria, spegni la torcia, grazie."))
    assertEquals(Kind.TIME, kind("che ore sono, per favore?"))
    // Le altre virgole restano un segnale: due richieste, o una frase che il modello deve leggere.
    assertNull(kind("timer di 10 minuti, e dimmi il meteo"))
    assertNull(kind("accendi la torcia, poi grazie"))
    assertNull(kind("allora, accendi la torcia"))
  }

  @Test
  fun `le sveglie si impostano da sole solo con un'ora senza dubbi`() {
    // Con la parte del giorno, o in forma da 24 ore.
    assertEquals("07:00", alarmAt("svegliami alle 7 di mattina"))
    assertEquals("06:30", alarmAt("sveglia alle 6 e mezza del mattino"))
    assertEquals("06:45", alarmAt("metti una sveglia alle 06:45"))
    assertEquals("15:00", alarmAt("sveglia alle 15"))
    assertEquals("12:00", alarmAt("sveglia alle 12"))
    assertEquals("12:00", alarmAt("metti la sveglia per le mezzogiorno"))
    assertEquals("19:00", alarmAt("imposta una sveglia alle 7 di sera"))
    assertEquals("15:00", alarmAt("sveglia alle 3 del pomeriggio"))
    assertEquals("03:00", alarmAt("sveglia alle 3 di notte"))
    assertEquals("23:00", alarmAt("sveglia alle 11 di notte"))
    assertEquals("21:30", alarmAt("sveglia alle 9 e mezza stasera"))
    // "Domattina" e "domani mattina" dicono gia' la mattina.
    assertEquals("07:15", alarmAt("svegliami domattina alle 7.15"))
    assertEquals("07:00", alarmAt("svegliami domani mattina alle 7"))
    assertEquals("07:15", alarmAt("svegliami domani alle 07:15"))
    assertEquals(Kind.NEXT_ALARM, kind("a che ora suona la sveglia?"))
    assertEquals(Kind.NEXT_ALARM, kind("ho una sveglia per domani?"))
  }

  @Test
  fun `un'ora da 1 a 11 senza la parte del giorno la decide il modello`() {
    listOf(
      "sveglia alle 3",
      "svegliami alle 7",
      "sveglia alle 6 e mezza",
      "metti una sveglia alle 6:45",
      "svegliami domani alle 7.15",
      "imposta la sveglia alle 11",
      // Parti del giorno che si contraddicono, o che non dicono quale meta'.
      "svegliami domattina alle 7 di sera",
      "sveglia alle 7 di notte",
      "sveglia alle 12 di sera",
    ).forEach { assertNull(it, QuickCommands.match(it, LocalTime.of(22, 0))) }
    // Alle 10 "domani alle 11" non e' la prossima occorrenza delle 11: lo fa il modello.
    assertNull(QuickCommands.match("svegliami domani alle 11", LocalTime.of(10, 0)))
    assertNull(QuickCommands.match("svegliami domani alle 11 di mattina", LocalTime.of(10, 0)))
    assertNull(QuickCommands.match("svegliami domattina alle 7", LocalTime.of(6, 0)))
  }

  @Test
  fun `quando suona la sveglia e le domande cortesi`() {
    assertEquals(Kind.NEXT_ALARM, kind("quando suona la sveglia?"))
    assertEquals(Kind.NEXT_ALARM, kind("quando suona la prossima sveglia"))
    assertEquals(Kind.NEXT_ALARM, kind("mi dici a che ora suona la sveglia?"))
    assertEquals(Kind.TIME, kind("mi dici che ore sono?"))
    assertEquals(Kind.TIME, kind("mi sai dire che ore sono"))
    assertEquals(Kind.TIME, kind("mi puoi dire che ore sono"))
    assertEquals(Kind.TIME, kind("sapresti dirmi che ore sono?"))
    assertEquals(Kind.TIME, kind("sai che ore sono"))
    assertEquals(Kind.TIME, kind("dimmi l'ora"))
    assertEquals(Kind.DATE, kind("mi dici che giorno è oggi?"))
    assertEquals(Kind.BATTERY, kind("dimmi quanta batteria ho"))
    // Il "quando" che lega due pezzi resta del modello.
    assertNull(kind("quando suona la sveglia accendi la torcia"))
    assertNull(kind("svegliami quando arrivo"))
    // Il verbo cortese vale solo davanti alle domande: "sai accendere la torcia?" chiede se sa farlo.
    assertNull(kind("sai accendere la torcia?"))
  }

  @Test
  fun `telefono, ora e musica`() {
    assertEquals("on", QuickCommands.match("accendi la torcia")?.args?.get("stato"))
    assertEquals("off", QuickCommands.match("spegni la torcia")?.args?.get("stato"))
    assertEquals("on", QuickCommands.match("puoi accendere la torcia")?.args?.get("stato"))
    assertEquals("on", QuickCommands.match("attiva la torcia del telefono")?.args?.get("stato"))
    assertEquals("off", QuickCommands.match("torcia off")?.args?.get("stato"))
    assertEquals("su", QuickCommands.match("alza il volume")?.args?.get("livello"))
    assertEquals("giu", QuickCommands.match("abbassa un po' il volume")?.args?.get("livello"))
    assertEquals("40", QuickCommands.match("volume al 40%")?.args?.get("livello"))
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
  fun `la suoneria solo con verbo e oggetto`() {
    fun mode(question: String) = QuickCommands.match(question)?.args?.get("modo")
    assertEquals("silenzioso", mode("metti il telefono in silenzioso"))
    assertEquals("vibrazione", mode("metti il telefono in vibrazione"))
    assertEquals("silenzioso", mode("metti in silenzioso"))
    assertEquals("vibrazione", mode("attiva la vibrazione"))
    assertEquals("silenzioso", mode("attiva la modalità silenziosa"))
    assertEquals("normale", mode("togli il silenzioso"))
    assertEquals("normale", mode("disattiva il silenzioso"))
    assertEquals("normale", mode("riattiva la suoneria"))
    listOf(
      "in silenzioso",
      "la vibrazione",
      "a vibrazione",
      "vibrazione",
      "silenzioso",
      "silenzio",
      // Dal silenzioso riaccenderebbe la suoneria: non e' detto che lo voglia.
      "togli la vibrazione",
      "disattiva la vibrazione",
    ).forEach { assertNull(it, QuickCommands.match(it)) }
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
  fun `le frasi di tutti i giorni che non sono comandi`() {
    listOf(
      // Luci, lampade e flash non sono la torcia: possono essere casa, una lampada smart, la fotocamera.
      "accendi la luce in salotto",
      "accendi la luce",
      "spegni la luce",
      "accendi la lampada",
      "attiva il flash",
      "flash on",
      "accendi la luce del telefono",
      "che tempo fa quando esco",
      "silenzio",
      "timer",
      "il timer",
      "metti una sveglia",
      "sveglia",
      "la sveglia",
      "volume",
      "torcia",
      "musica",
      "grazie",
      "ok grazie",
      "apri la porta",
      "metti su un po' di musica",
      "fammi un caffè",
      "ricordami di chiamare la nonna alle 18",
      "che tempo fa domani",
      "a che ora apre la farmacia",
      "quanto manca a natale",
    ).forEach { assertNull(it, QuickCommands.match(it, LocalTime.of(22, 0))) }
  }

  @Test
  fun `le risposte sono frasi, e un errore torna al modello`() {
    val timer = QuickCommands.match("timer di 10 minuti")!!
    assertEquals(
      "Timer di 10 minuti avviato: suona alle 18:42.",
      QuickCommands.reply(timer, "fatto: timer di 10 minuti avviato\nsuona alle: 2026-09-27 (dom) 18:42", now),
    )
    assertNull(QuickCommands.reply(timer, "errore: nessuna app Orologio risponde ai timer su questo telefono", now))
    assertNull(QuickCommands.reply(timer, ACTIONS_OFF, now))

    assertEquals("Sono le 18:32.", QuickCommands.reply(QuickCommands.match("che ore sono")!!, null, now))
    assertEquals("Oggi è domenica 27 settembre 2026.", QuickCommands.reply(QuickCommands.match("che giorno è")!!, null, now))
    assertEquals("Torcia accesa.", QuickCommands.reply(QuickCommands.match("accendi la torcia")!!, "fatto: torcia accesa", now))
    assertEquals("Batteria al 76%, in carica.", QuickCommands.reply(QuickCommands.match("quanta batteria ho")!!, "batteria: 76%\nstato: in carica\nrisparmio energetico: spento", now))
    assertEquals("Sveglia impostata alle 07:00 (domani).", QuickCommands.reply(QuickCommands.match("svegliami alle 7 di mattina")!!, "fatto: sveglia impostata alle 07:00", now))
    assertNotNull(QuickCommands.reply(QuickCommands.match("quanto fa 12*7")!!, "espressione: 12*7\nrisultato: 84", now))
  }

  @Test
  fun `un'azione riuscita ha sempre una risposta, cosi' il modello non la rifa'`() {
    // Anche quando la frase su misura non si compone: basta la riga "fatto", o "Fatto.".
    val done = listOf(
      Match(Kind.TIMER, "timer_crea", mapOf("durata" to "boh")) to "fatto: timer avviato",
      Match(Kind.ALARM, "sveglia_crea", mapOf("ora" to "??")) to "fatto: sveglia impostata",
      Match(Kind.ALARM, "sveglia_crea") to "fatto: sveglia impostata",
      Match(Kind.MUSIC, "musica_controllo", mapOf("azione" to "stop")) to "fatto: stop",
      Match(Kind.OPEN_APP, "apri_app", mapOf("nome" to "x")) to "fatto: ",
      Match(Kind.VOLUME, "volume", mapOf("livello" to "su")) to "fatto:",
      Match(Kind.RINGER, "modalita_suoneria", mapOf("modo" to "vibrazione")) to "fatto: suoneria: vibrazione",
      Match(Kind.DND, "non_disturbare", mapOf("stato" to "on")) to "fatto: non disturbare: attivo (solo priorita')",
    )
    done.forEach { (match, output) ->
      val outcome = QuickCommands.outcome(match, output, now)
      assertTrue("$match -> $outcome", outcome is Outcome.Answer && outcome.text.isNotBlank())
    }
    assertEquals("Fatto.", QuickCommands.reply(Match(Kind.VOLUME, "volume", mapOf("livello" to "su")), "fatto:", now))
    assertEquals("Suoneria: vibrazione.", QuickCommands.reply(Match(Kind.RINGER, "modalita_suoneria"), "fatto: suoneria: vibrazione", now))
  }

  @Test
  fun `quando lo strumento non ha fatto niente c'e' una frase anche senza modello`() {
    val torch = QuickCommands.match("accendi la torcia")!!
    val off = QuickCommands.outcome(torch, ACTIONS_OFF, now)
    assertTrue(off is Outcome.NotDone)
    assertEquals("Le azioni sono disattivate: si riattivano nelle impostazioni di PampAI.", (off as Outcome.NotDone).fallback)
    val music = QuickCommands.match("metti in pausa la musica")!!
    val missing = QuickCommands.outcome(music, "errore: Fluidify non e' installata: si installa dal Pampa Store [[apri:store]]", now) as Outcome.NotDone
    assertEquals("Non ci sono riuscita: Fluidify non e' installata: si installa dal Pampa Store.", missing.fallback)
    // Una conferma negata non e' un "fatto".
    assertTrue(QuickCommands.outcome(torch, "l'utente ha annullato: non fatto", now) is Outcome.NotDone)
    assertTrue(QuickCommands.outcome(torch, "", now) is Outcome.NotDone)
    assertTrue(torch.acts)
    assertTrue(!QuickCommands.match("che ore sono")!!.acts)
  }
}
