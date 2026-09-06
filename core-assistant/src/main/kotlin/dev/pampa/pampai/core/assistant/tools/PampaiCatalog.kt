package dev.pampa.pampai.core.assistant.tools

import dev.antigravity.fluidengine.ai.tools.AiToolCategory
import dev.antigravity.fluidengine.ai.tools.AiToolGroup

/**
 * Le categorie di Aria: le aree del telefono e del web che stanno dentro PampAI. Le app Pampa
 * collegate (registro, meteo, bus, convertitore, store) arrivano dal bridge come categorie
 * costruite a runtime dal loro catalogo (`RemoteCategory`), non da qui.
 */
enum class PampaiCategory(override val id: String, override val label: String, override val hint: String) : AiToolCategory {
  ARIA("aria", "Aria", "la memoria di Aria (cose da ricordare), le conversazioni passate, le impostazioni di PampAI, cosa sa fare"),
  DISPOSITIVO("dispositivo", "Dispositivo", "il telefono: sveglie, timer, promemoria, calendario, contatti e chiamate, messaggi, notifiche, torcia, volume, luminosita', non disturbare, aprire app e link, batteria, posizione"),
  WEB("web", "Web", "il web e la conoscenza: ricerca, lettura di pagine, Wikipedia, definizioni; calcoli, conversioni di unita', valute, fusi orari, date"),
  SCHERMO("schermo", "Schermo", "cosa c'e' sullo schermo del telefono adesso: il testo e lo screenshot dell'app in primo piano"),
  MUSICA("musica", "Fluidify", "la musica su Fluidify: riproduci, pausa, avanti, coda, playlist, recenti, download, cerca, volume"),
}

/**
 * I gruppi (sottocategorie) di Aria. `loadsWithCategory` segna quelli che si aprono con la
 * categoria: i piu' usati, cosi' una domanda che apre la categoria ha subito qualcosa in mano.
 */
enum class PampaiGroup(
  override val id: String,
  override val statusKey: String,
  override val hint: String,
  override val category: PampaiCategory,
  override val loadsWithCategory: Boolean = false,
) : AiToolGroup {
  ARIA("aria", "aria", "ricorda e dimentica fatti, cerca nelle conversazioni passate, impostazioni di PampAI, consumi, app collegate, aiuto", PampaiCategory.ARIA, loadsWithCategory = true),

  OROLOGIO("orologio", "clock", "sveglie e timer dell'app Orologio", PampaiCategory.DISPOSITIVO),
  PROMEMORIA("promemoria", "reminders", "promemoria di PampAI con notifica a un'ora, anche ricorrenti", PampaiCategory.DISPOSITIVO),
  CALENDARIO("calendario", "calendar", "eventi del calendario del telefono: leggere, creare, spostare, eliminare", PampaiCategory.DISPOSITIVO),
  CONTATTI("contatti", "contacts", "contatti, chiamate, messaggi ed email da preparare, condividere testo", PampaiCategory.DISPOSITIVO),
  NOTIFICHE("notifiche", "notifications", "le notifiche attive di tutte le app e la musica di qualsiasi app", PampaiCategory.DISPOSITIVO),
  SISTEMA("sistema", "system", "torcia, volume, suoneria, luminosita', rotazione, non disturbare", PampaiCategory.DISPOSITIVO),
  APRI("apri", "open", "aprire un'app, un link, una pagina delle impostazioni; le app installate", PampaiCategory.DISPOSITIVO, loadsWithCategory = true),
  INFO("info", "info", "batteria, dispositivo, spazio, dove sono", PampaiCategory.DISPOSITIVO),

  WEB("web", "web", "ricerca sul web con fonti, lettura di una pagina o un URL, Wikipedia, definizione di una parola", PampaiCategory.WEB, loadsWithCategory = true),
  CALCOLO("calcolo", "calc", "calcoli esatti, conversioni di unita', valute, fusi orari, conti fra date", PampaiCategory.WEB),

  SCHERMO("schermo", "screen", "il testo e lo screenshot dello schermo, l'app in primo piano, traduzione dello schermo", PampaiCategory.SCHERMO, loadsWithCategory = true),

  RIPRODUZIONE("riproduzione", "music", "riproduci, pausa, avanti, indietro, cosa sta suonando, coda, shuffle, ripeti, radio, volume, salva", PampaiCategory.MUSICA, loadsWithCategory = true),
  LIBRERIA("libreria", "music_library", "cerca brani, playlist e i loro brani, ascoltati di recente, download, dispositivi, apri Fluidify", PampaiCategory.MUSICA),
}
