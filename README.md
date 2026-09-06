# PampAI

**Aria**, l'assistente delle app Pampa. Una chat quotidiana come quelle che usi già, che però conosce
il tuo telefono e le tue app: il registro scolastico, il meteo, gli autobus, la musica, lo store, il
convertitore di file. Risponde a voce o per scritto, e si può richiamare tenendo premuto il tasto di
accensione sopra qualsiasi app.

Le chiavi dei modelli sono tue (Groq, Gemini, OpenRouter): restano sul telefono, cifrate con il
Keystore, e viaggiano solo verso il servizio a cui appartengono.

## Cosa sa fare

- **Chat con memoria.** Le conversazioni restano, si riprendono, si cercano. Sotto ogni risposta c'è
  la riga degli strumenti usati, apribile: cosa ha chiesto a chi, quanto ci ha messo, quanti token.
- **Voce doppia.** Mentre parli le parole compaiono subito (il riconoscitore del telefono), e alla
  fine la trascrizione di Whisper le sostituisce. Le risposte si fanno leggere ad alta voce, con la
  voce di sistema o una voce cloud.
- **Assistente di sistema.** Dal tasto di accensione compare sopra l'app che stai usando: legge lo
  schermo se glielo chiedi, ne ritaglia una porzione col dito, e la conversazione continua nell'app
  trascinando la card in alto.
- **Il telefono.** Sveglie, timer, promemoria, calendario, contatti, chiamate, messaggi da rileggere
  prima di inviare, notifiche, torcia, volume, luminosità, Non disturbare, app da aprire, batteria,
  posizione.
- **Il web.** Ricerca con le fonti, lettura di una pagina, Wikipedia, dizionario, calcoli esatti,
  conversioni, valute, fusi orari.
- **Le app Pampa.** Ogni app espone i propri strumenti ad Aria: 46 dal registro, 34 dal meteo, 27
  dagli autobus, 15 dalla musica, 9 dal convertitore, 8 dallo store.

## Come si mette in piedi

1. Installa PampAI dal Pampa Store, insieme alle app che vuoi collegare.
2. Alla prima apertura: una chiave (basta quella gratuita di Groq o di Gemini), il consenso, il
   microfono.
3. **Assistente predefinito**, se lo vuoi al tasto di accensione. Android non permette a un'app di
   chiedersi quel ruolo da sola: si va in *Impostazioni › App › App predefinite › Assistente
   digitale* e si sceglie PampAI. Nella stessa pagina conviene attivare **Usa testo dallo schermo** e
   **Usa screenshot**: sono ciò che permette ad Aria di leggere quello che hai davanti.
4. I permessi si concedono quando servono, uno per volta, e si vedono tutti insieme in
   *Impostazioni › Permessi*.

## I permessi, e a cosa servono

| Permesso | Quando serve |
|---|---|
| Microfono | Le domande a voce. |
| Notifiche | La risposta che arriva anche ad app chiusa, e i promemoria. |
| Calendario | Leggere e creare eventi. |
| Contatti, Telefono | Trovare un numero, chiamare (sempre con conferma). |
| Posizione | "Dove sono", e i posti per meteo e autobus. |
| Accesso alle notifiche | Leggere la tendina e controllare la musica di qualsiasi app. |
| Modifica impostazioni di sistema | Luminosità e rotazione. |
| Accesso a Non disturbare | Silenzioso e Non disturbare. |
| Sveglie precise | I promemoria che suonano al minuto. |

Niente di tutto questo viene chiesto all'avvio, e nessuno di questi dati lascia il telefono: quello
che viaggia verso i modelli è solo la domanda, la conversazione recente e il risultato degli
strumenti che servono a rispondere.

## Le app collegate

Aria trova da sola le app Pampa installate e ne monta gli strumenti. Il canale è un
`ContentProvider` protetto da un permesso a livello **firma**: solo app firmate con la stessa chiave
possono parlarsi, e ogni strumento gira nel processo della sua app, dove i dati già stanno. Le
azioni che contano (una chiamata, un'installazione, una cancellazione) chiedono conferma qui, prima
che l'altra app faccia qualcosa.

Se un'app non compare in *Impostazioni › App collegate*, di solito è perché va aggiornata dal Pampa
Store: il ponte esiste dalla versione con il Fluid Engine 1.27.0 in poi.

## Per chi sviluppa

Tre moduli: `:app` (la sessione di sistema, il tile, le scorciatoie), `:core-assistant` (strumenti,
memoria, voce, runtime, tracker) e `:feature-assistant` (chat, overlay, impostazioni). Sotto c'è il
[Fluid Engine](https://github.com/Casual76/fluid-engine) come submodule: design system, provider dei
modelli, orchestratore degli strumenti e il modulo `engine-ai-bridge` che regge i tool federati.

```bash
git submodule update --init --recursive
./gradlew.bat :app:assembleDebug :core-assistant:testDebugUnitTest
```

Serve un `local.properties` con `sdk.dir`. Se la cartella del progetto è sincronizzata da un servizio
cloud, conviene spostare le cartelle di build fuori: `pampai.buildRoot=C:/percorso/fuori/dal/cloud`.
