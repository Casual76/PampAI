# K3 R1-F2 / R3-3 - handoff sorgenti e test

Freeze source-only: 2026-11-08T14:40:51.736453+00:00. Sorgenti/test elencati sotto rilasciati a ROOT. Nessuna ulteriore esecuzione o modifica autonoma prevista. ROOT mantiene il compiler runner esclusivo e potra validare serialmente dopo il freeze dell'altro autore.

## Implementazione

`ResearchAcceptanceDescriptor` viene catturato prima della sintesi: epoch originario del job (o catturato prima dell'acquisizione temporanea), conversazione/obiettivo/model-policy hash, job, letture esatte e dipendenze aggiuntive del profilo realmente usato, versioni/generazioni dei grant, identita e fingerprint di item/evidenze del profilo. La fingerprint esclude scadenze rinnovabili dei grant. Il descriptor viene scritto nel campo `_acceptance` del frammento di sintesi insieme al checkpoint esistente, nella transazione del commit di sintesi. Il risultato del modello non puo sovrascriverlo.

Una ripresa DONE legge quel descriptor dal checkpoint; non ricattura il consenso corrente. Un vecchio risultato privo di descriptor viene rifiutato con `ACCEPTANCE_DESCRIPTOR_MISSING`, senza replay o tentativo di sanarlo usando un epoch nuovo. Policy, obiettivo, scope e associazione di retry preesistenti restano vincolati. La temporanea usa il medesimo descriptor solo in memoria, senza job/checkpoint/evidenze/profilo persistenti aggiuntivi.

`ResearchForeground.answer` associa un `ResearchAcceptance` al risultato. `ResearchAcceptance.check/commit` controlla prima lo stato locale, rinnova le prove firmate fuori Room, ricontrolla localmente e avvolge il blocco finale in `DurableFoundation.withResearchAcceptance`. Dentro Room si controllano epoch, grant/versioni/generazioni, reads consentite, item/evidenze del profilo, job/cancellazione/vettore. Non si fa I/O di rete in Room. Sono presenti controlli di cancellazione prima/dopo il blocco di persistenza.

`ConversationsRepository.acceptResearch` e il confine di produzione chiamato dall'Engine. Valida anche conversazione, domanda/obiettivo, policy, ruolo, parent, stato pending/streaming e foglia attiva. Salva risposta DONE, run `research` e metadati conversazione nella medesima transazione Room. Engine aggiorna stato pubblico solo dopo il ritorno con successo, espelle il replay cache e salta title generation. I percorsi d'errore non salvano partial privati. Checkpoint, contatori/settlement del runner e ledger di scritture reali/incerte non vengono cancellati dalla mancata accettazione.

Il rifiuto esplicito del profilo conserva `sourceQuery` per la consultazione autorizzata, ma la sintesi continua a usare `profileQuery` per il retrieval e ora non produce scritture opzionali di profilo quando `storedKnowledgeAllowed` e falso. Nessuna modifica al planner ROOT-owned.

## Coordinamento R2 F2/F3 e UX source-free

Tutte le decisioni read/write dell'Engine (isWrite del ledger, prepare/output, trace conservate e gestione quick) ora chiamano `SourceBridgePolicy.actionFor(toolName, fallback)`. Dipendenza sull'API concordata dell'altro autore: al controllo statico di questo freeze non era ancora presente; nessuna implementazione duplicata aggiunta al file di sua proprieta. ROOT deve integrare il suo freeze prima di compilare. I cataloghi e RicordaTool restano di sua proprieta.

`TurnPrivacy.requiresPrivacyFence` e immutabile: profilo catturato non vuoto oppure scope catturati pertinenti alla vera `sourceQuery`. Un turno source-free non puo acquisire fonti in seguito: `read` nega prima di accedere al catalogo/trasporto. Engine ripristina live progress e partial saver per questi soli turni; il wrapper provider trasmette subito il loro stream. I turni con possibile accesso privato restano bufferizzati anche prima del primo read. `privateTurn` mantiene il significato di dipendenze effettivamente consumate.

API `TurnPrivacy` compatibili per RicordaTool: elemento di CoroutineContext invariato, `privateTurn`, `check`, `commit`. `orchestrator.ask` resta in `withContext(turnPrivacy)`, quindi prepare/output/tool.run ereditano il contesto. `commit` privato usa il fence Room; il commit source-free usa la nuova `withLocalTransaction` sullo stesso DB, senza invalidare un turno pubblico per forget estranei. Ispezione statica: registry con `actionGroup = null`; ricorda nel gruppo ARIA; nessun nuovo blocco actionsEnabled nell'Engine. La nuova confirmSafety/actions-disabled di RicordaTool va validata dal secondo autore/ROOT.

## Regressioni predisposte, NON eseguite

Nuovo `ResearchAcceptanceTest`: 15 metodi. Fixture con Room reale, ResearchSources e verificatore prove reali, ResearchForeground/Runner e repository di produzione; solo trasporto e modello esterni sono sostitutivi. Copiata localmente la fixture sintetica dagli esistenti test ROOT-owned, senza modificarli.

- Forget al ritorno reale da research, revoca locale con riconnessione, deriva di autorizzazione remota.
- Successo corrente e rinnovo stabile dopo expiry; cronologia visibile ma esclusa da replay e tool search.
- Trigger SQLite che fallisce INSERT della classificazione dopo complete: rollback di entrambi, retry di persistenza senza rigenerazione.
- Ripresa DONE tramite parsing del descriptor persistito e una sola generazione.
- Temporanea senza job/profilo; forget durante produzione temporanea senza ricattura del consenso.
- Dipendenza di profilo realmente iniettata nella sintesi (correzione utente conservata), correzione al handoff; rifiuto del profilo con fonte ancora consentita.
- Ledger reale UNCERTAIN conservato e replay negato dopo rifiuto dell'accettazione.
- Stream source-free immediato contro stream potenzialmente privato bufferizzato; impossibilita di upgrade tardivo anche con una nuova connessione.
- Engine reale con stream image pubblico: partial su Room prima del completamento; caso privato bufferizzato e invalidato prima della pubblicazione.
- Commit source-free con rollback Room effettivo e successo dopo variazione estranea di epoch.

Limiti delle regressioni preparate: la ripresa ricrea il risultato dal DB nello stesso processo; un vero process kill/reopen resta NV. Il modello sostitutivo non prova accounting/billing del provider; verifica soltanto nessuna nuova generazione e nessuna alterazione delle righe ledger esistenti al rifiuto. Il caso profilo usa una USER_STATEMENT realmente recuperata, non una nuova derivazione appresa da provider reale. Nessun risultato di test o compilazione e rivendicato.

## Verifiche e vincoli

Eseguiti soltanto letture, ricerche statiche, editing, hash e `git diff --check` sui due file tracked toccati (nessun errore). Non eseguiti Gradle, Java, build, test runner, lint, provider, device, sorgenti remote, credenziali, commit, push. Nessun subagente avviato e nessun reviewer contattato. R1 e brief letti come finding input; conclusioni del peer indipendente non consultate. Nessuna modifica a engine, schema, source producers, Gradle o UI. Nessuna area autonoma abilitata, inclusa la fixture nuova.

Preservati i cambi ROOT presenti: sourceQuery/profileQuery/rifiuti, onlyProvidedContent, memoria; nessuna scrittura a ResearchQuestionPlanner, SourceBridgePolicy, OrdinaryTurnPrivacyTest, SourceIntegrationTest o ResearchConsumerTest. Questi file possono essere modificati contemporaneamente dai rispettivi proprietari.

Tutte le NV precedenti restano NV: compilazione/JUnit/lint/assemble correnti; integrazione della API actionFor e nuovo RicordaTool; signed Binder/autenticazione/account drift/cancel/readback installati; provider effettivi e billing; native/Connect/process kill; OS scheduling/Doze; overlay/back/IME, testo/immagini attivi, TalkBack/voce/effort in coda e rendering. Nessuna approvazione runtime/device o di fase e nessuna APK aggiornata.

## Manifest del freeze

Hash SHA256 dei soli file posseduti da questo autore, inclusi i contenuti ROOT preesistenti nei file condivisi. La documentazione non include un hash di se stessa. Eventuali correzioni successive richiedono un nuovo freeze da parte di ROOT.

| File | Byte | SHA256 |
|---|---:|---|
| `core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/runtime/AssistantEngine.kt` | 50825 | `9CDEA4C80B84F8CEECCA2423C3C34799BBDA5EC767E53C603B56024BF7E17F97` |
| `core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/research/ResearchForeground.kt` | 6863 | `8B91E2637C1D660399F9970C2089465BA67D8C8A21399E44E226CD60A4BFE016` |
| `core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/research/ResearchRunner.kt` | 22467 | `11C3A336F62AE723D0312513EACA635D2C68BDE8C2427F2D31B3BD89CD61F173` |
| `core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/research/ResearchContracts.kt` | 2409 | `9E87619FB343167F7BCF0D491221460FFCAF73DEE5DC215C84FE677E5C5D9032` |
| `core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/research/ResearchAcceptance.kt` | 2090 | `A0DD6B92DBAE0C6D1C31A1A48B82D1731B39173232AE31A6A9B221053B4965DB` |
| `core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/research/TurnPrivacy.kt` | 6646 | `783B22FEFB65F4160A0790A656DC5FE04F386F6BD9461920B608952D49210A9B` |
| `core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/durable/DurableFoundation.kt` | 29593 | `3FFA8CEE9A41ED872A487845AAA470342B486E60CF30EC4DBC73DC60CECDFD6B` |
| `core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/db/ConversationsRepository.kt` | 44903 | `E60C438F15079B75E89C415D1BBD9BD4B2D85AF112EDEA3C3534B953F24A232D` |
| `core-assistant/src/test/kotlin/dev/pampa/pampai/core/assistant/research/ResearchAcceptanceTest.kt` | 24027 | `D1C2E1D1F800059DFB94BC8B4D52F261E4EB69D43361DA5338726F6508506D2E` |
