package dev.pampa.pampai.core.assistant.chat

/**
 * La frase che Aria mette sotto il proprio nome quando la chat e' vuota.
 *
 * Cambia con l'ora — "cosa facciamo questa mattina?" alle otto non e' la stessa cosa a mezzanotte —
 * e cambia fra un'apertura e l'altra, perche' la stessa frase ripetuta ogni volta smette di essere
 * un saluto e diventa un'intestazione.
 *
 * Sta qui e non nella schermata perche' le fasce orarie sono l'unica parte con una risposta giusta
 * e una sbagliata, e si provano senza accendere Compose.
 */
object Greetings {

  /** Le fasce della giornata. I confini sono quelli del parlato, non quelli dell'orologio. */
  enum class Band { NOTTE, MATTINA, MEZZOGIORNO, POMERIGGIO, SERA }

  fun band(hour: Int): Band = when (hour) {
    in 5..11 -> Band.MATTINA
    in 12..13 -> Band.MEZZOGIORNO
    in 14..18 -> Band.POMERIGGIO
    in 19..22 -> Band.SERA
    else -> Band.NOTTE
  }

  /**
   * Tre spunti toccabili sotto il saluto, adatti all'ora: uno per il telefono (che parte subito,
   * senza modello), uno per le app, uno per ragionare. Fanno vedere cosa si puo' chiedere senza
   * un muro di esempi, e restano utili anche dopo la prima chat.
   */
  fun suggestions(band: Band): List<String> = when (band) {
    Band.MATTINA -> listOf("Che tempo fa oggi?", "Timer di 10 minuti", "Spiegami un argomento in 5 punti")
    Band.MEZZOGIORNO -> listOf("Timer di 12 minuti per la pasta", "Che tempo fa nel pomeriggio?", "Idee veloci per il pranzo")
    Band.POMERIGGIO -> listOf("Aiutami con i compiti", "Timer di 25 minuti", "Che tempo fa domani?")
    Band.SERA -> listOf("Svegliami alle 7", "Che tempo fa domani?", "Consigliami un film")
    Band.NOTTE -> listOf("Svegliami alle 7:30", "Spegni la torcia", "Raccontami una curiosita'")
  }

  fun lines(band: Band): List<String> = when (band) {
    Band.MATTINA -> listOf(
      "Cosa facciamo questa mattina?",
      "Buongiorno. Da dove cominciamo?",
      "Che si fa stamattina?",
      "Giornata lunga? Dimmi pure.",
    )
    Band.MEZZOGIORNO -> listOf(
      "Ora di pranzo. Cosa ti serve?",
      "Pausa. Da dove cominciamo?",
      "Che si fa a quest'ora?",
      "Mezza giornata fatta. Dimmi pure.",
    )
    Band.POMERIGGIO -> listOf(
      "Cosa facciamo questo pomeriggio?",
      "Buon pomeriggio. Da dove cominciamo?",
      "Che si fa nel pomeriggio?",
      "Il grosso e' passato. Dimmi pure.",
    )
    Band.SERA -> listOf(
      "Cosa facciamo stasera?",
      "Buonasera. Cosa ti serve?",
      "Che si fa stasera?",
      "Serata tranquilla? Dimmi pure.",
    )
    Band.NOTTE -> listOf(
      "Ancora sveglio? Dimmi pure.",
      "E' tardi. Cosa ti serve?",
      "Notte fonda. Da dove cominciamo?",
      "A quest'ora si fa piano. Che si fa?",
    )
  }

  /**
   * La frase di questa apertura. [pick] e' un numero qualsiasi — nella schermata un casuale tenuto
   * in `remember`, nei test un indice — cosi' la frase resta ferma finche' la pagina resta aperta.
   */
  fun greeting(hour: Int, pick: Long): String {
    val lines = lines(band(hour))
    return lines[(Math.floorMod(pick, lines.size.toLong())).toInt()]
  }
}
