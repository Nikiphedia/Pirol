# PIROL — Manuale d'uso

Riconoscimento acustico delle specie in tempo reale per Android, pensato per il lavoro sul campo.

*Valido dalla versione v0.1.3 (stato 2026-11-25). Le modifiche tra le versioni sono riportate in `CHANGELOG.md`.*

L'app ha cinque schede: **Live**, **Analisi**, **Riferimenti**, **Mappa** e **Impostazioni**. Il pulsante **?** (Aiuto) non è una scheda: un tocco attiva la modalità aiuto, un doppio tocco apre questo manuale (capitolo 1, sezione "Pulsante Aiuto").

---

## 1. Primo avvio

1. Aprire l'app. La procedura guidata accompagna nella scelta di lingua, livello (principiante/esperto), impiego, lista delle specie, modello (incluso il geomodello), autorizzazioni (microfono, GPS, notifiche) e riepilogo. I valori vengono applicati solo con "Fine".
2. Scegliere la **lista delle specie** (capitolo 11): i gruppi di specie (Uccelli, Ortotteri, Anfibi, Mammiferi, Altro) filtrano il riconoscimento su specie plausibili. Modificabile in seguito in Impostazioni > Specie e allarme.
3. Scaricare il **modello**: è possibile selezionare più modelli contemporaneamente. Lo standard è BirdNET 3.0 FP16 (consigliato, circa 170 MB). Il download prosegue in background, può essere interrotto e ripreso più tardi. In alternativa si può scegliere manualmente un file di modello.
4. Verificare la **posizione di salvataggio** (Impostazioni > Registrazione). Standard: `Downloads/PIROL/`, visibile via USB.

**Installazione (APK):** la APK di rilascio esiste per ogni architettura di processore. Per i dispositivi attuali (telefoni e tablet) installare il file `Pirol-<Versione>-arm64-v8a-release.apk`; solo per i vecchi dispositivi a 32 bit il file `...-armeabi-v7a-release.apk`. Le APK sono così nettamente più piccole della vecchia APK comune. L'app ha una nuova icona (uccello giallo), disponibile anche come icona monocromatica per le icone a tema. I dettagli su installazione e aggiornamento (compresi i dispositivi Samsung) si trovano nel capitolo 20.

**Novità:** dopo un aggiornamento PIROL mostra una volta una finestra "Novità" con le modifiche principali della versione. Può essere richiamata in qualsiasi momento in Impostazioni, sotto "Info".

### Pulsante Aiuto
Il pulsante **?** (tablet e navigazione stretta: in Impostazioni; telefono: all'estrema destra della barra) offre due modalità:
- **Un tocco** attiva o disattiva la **modalità aiuto**. In alto compare la barra "Modalità aiuto: tocca un pulsante per una spiegazione". Toccando un pulsante o un cursore non succede nulla, ma compare una breve spiegazione, se necessario con "Altro nel manuale" (porta al capitolo corrispondente). Per uscire: X nella barra, Indietro o di nuovo **?**.
- **Doppio tocco** apre questo manuale offline, nella lingua dell'app (se manca la traduzione, in tedesco). Un indice porta alle sezioni.

**Aggiornamento da versioni precedenti:** dalla v0.0.8 PIROL è firmata. Chi ha installato la v0.0.7 o precedente deve disinstallare una volta e prima salvare le sessioni via USB (`Android/data/ch.etasystems.pirol/files/PIROL/`). Dalla v0.0.8 alla v0.0.10 verso la v0.0.11 l'aggiornamento avviene senza disinstallazione; sessioni e modelli restano. Attenzione con la v0.0.11: la soglia di confidenza è ora una soglia comune per tutte le specie (capitoli 2 e 13), verificare il cursore dopo l'aggiornamento. Dalla v0.0.11 alla v0.1.1 l'aggiornamento avviene senza disinstallazione. Poi verificare i gruppi di specie (capitolo 11): i vecchi filtri risultano Uccelli Auto, il resto Off. Le sessioni della v0.0.6 e precedenti non sono più supportate in modo specifico.

---

## 2. Riconoscimento in diretta

**Avvio:** premere il pulsante di registrazione nella scheda Live. **Stop:** premere a lungo il pulsante. Sessione ed esportazione Raven vengono salvate automaticamente.

Registrazione e analisi girano in un unico servizio. Proseguono quando l'app va in background o lo schermo si spegne.

Allo stop compare in alto l'avviso "Pressione lunga per fermare" (in alto, così il dito sul pulsante non lo copre).

Le specie riconosciute compaiono come schede sotto il sonogramma:
- Nome della specie nella lingua scelta, nome scientifico come aggiunta
- Confidenza in percentuale (barra sottile), orario, posizione GPS
- Alternative (espandibili, selezionabili con un tocco)
- Indicazioni "Raro", "Fuori stagione", contrassegno della watchlist
- **Riproduci:** il pulsante Play sulla scheda riproduce l'estratto del rilevamento (3 s prima, 5 s dopo), anche durante la registrazione. Un secondo tocco ferma la riproduzione.
- **Lampeggio:** se una specie viene riconosciuta di nuovo, la sua scheda lampeggia brevemente di blu. L'elenco non salta in questo caso (vedi "Elenco tranquillo").
- **Campana d'allarme:** nel menu della scheda completa si inserisce la specie in modo permanente nella lista d'allarme o per 24 h. Gli allarmi di 24 h mostrano una campana con il badge "24h"; alla scadenza scompare automaticamente.
- **Pulsante compatto:** commuta tutte le schede tra compatta e dettagliata. Sul telefono si trova nella barra di stato, sul tablet nell'intestazione. Le alternative si aprono e chiudono tramite l'intestazione della scheda.

**Vista compatta sul telefono:** sia in verticale sia in orizzontale la scheda Live usa lo spazio con parsimonia: nessuna barra del titolo, la navigazione mostra solo icone, la riga di pulsanti delle schede è su una riga. Sulle schede strette, Condividi, Riferimento, Confronta e "Alternative" si trovano nel menu "Altre azioni" (icona con tre puntini); sul tablet tutti i pulsanti restano visibili. In orizzontale a sinistra ci sono sonogramma e pulsanti, a destra le schede delle specie. Il pannello "Specie simili" sul telefono è chiuso e si può aprire.

**Microfono disattivato durante la riproduzione:** se PIROL riproduce un rilevamento tramite l'altoparlante durante la registrazione, il microfono viene silenziato, così la riproduzione non viene riconosciuta né registrata di nuovo. Nella scheda Live compare l'avviso "Microfono disattivato durante la riproduzione". Con cuffie collegate (cavo, USB, Bluetooth) il microfono resta attivo.

| Pulsante | Stato |
|----------|-------|
| verde, pulsante | buffer di preroll attivo (registrazione non ancora avviata) |
| rosso, pulsante | registrazione in corso |

**Riga di stato:** durata in mm:ss. **Barra GPS:** precisione attuale in ±m, rossa se per più di 30 s non c'è un fix valido.

**Nota:** per ogni registrazione si può inserire un titolo. Compare nell'elenco delle sessioni, nel nome dello ZIP e nell'esportazione Raven.

**Aggiunta manuale:** inserire una specie a mano (con numero di esemplari, codice atlas, nota). Per ogni rilevamento si possono inoltre inserire numero, codice atlas, nota e memo vocale. Una specie può essere bloccata (una sola segnalazione per sessione).

**Raggruppamento:** i rilevamenti della stessa specie vengono raggruppati nello spazio e nel tempo in un'unica voce (raggio e finestra temporale regolabili, Impostazioni > Modello e riconoscimento).

### Elenco tranquillo
L'ordine delle schede delle specie cambia al massimo ogni 5 s. Le nuove specie vengono raccolte e inserite insieme in alto (la primissima scheda compare subito); possono quindi comparire fino a 5 s dopo, gli allarmi arrivano comunque subito. Se una specie viene riconosciuta di nuovo tra le prime 5 schede, resta ferma e lampeggia di blu. Una specie più in basso sale al cambio successivo e lampeggia. Cancellazioni, verifiche e specie bloccate agiscono subito. Rilevamenti, allarme, raggruppamento e analisi non ne sono coinvolti.

### Memo di sessione
Il pulsante Memo nella scheda Live (visibile solo durante una registrazione) funziona come un walkie-talkie: **tenere premuto e parlare**; al rilascio il memo termina. Durante la registrazione il pulsante è rosso e mostra la durata; poi compare "Memo salvato (n s)". I memo sotto 0,5 s vengono scartati ("Troppo breve"), dopo 3 minuti un memo termina automaticamente. Il memo viene salvato con la sua posizione nella registrazione (`memos.jsonl`, file in `memos/`), compare nell'analisi nel blocco "Memo" (capitolo 6) e come riga "Memo" nell'esportazione Raven (capitolo 7). I memo non vengono mai nascosti come voce (capitolo 7). I memo vocali relativi a singole specie restano separati.

Al momento della pressione PIROL memorizza l'ultimo fix GPS (non più vecchio di 2 min) e lo salva con il memo; sulla mappa il memo compare poi come marcatore di microfono (capitolo 8). Senza un fix GPS attuale il memo non ha un luogo. Eliminare, condividere e salvare con nome: capitolo 6.

### Riconoscimento multiplo e soglia di confidenza
Nella stessa finestra di 3 s vengono riconosciute tutte le specie che raggiungono la soglia, non solo la prima. Il cursore si chiama "Soglia di confidenza (tutte le specie)" (Impostazioni > Modello e riconoscimento, standard 50 %) e vale in modo uguale per tutte le specie. Le specie sotto soglia ma sopra il limite dei candidati restano come candidate nei rilevamenti della finestra. In passato il cursore regolava un'altra soglia (TopK, standard 0.3); quindi verificarlo dopo l'aggiornamento. Gli allarmi della watchlist scattano solo a partire dalla soglia.

### Secondo modello (modalità esperto)
Un secondo modello (ad es. Perch v2 o BirdNET 2.4, capitolo 13) ricalcola le stesse finestre. La scheda mostra allora, accanto alla confidenza del modello principale, il valore del secondo modello: in percentuale, per Perch come posizione ("Perch #2"). Le specie che il secondo modello non conosce sono contrassegnate come non verificabili. Il secondo modello non influisce su allarme, filtri o raggruppamento. La soglia "Alternative da" (5/10/20/30 %, Impostazioni > Modello e riconoscimento) stabilisce da quale confidenza vengono elencate le alternative.

### Preroll
Se attivo, l'audio viene salvato **prima** dell'avvio della registrazione (durata regolabile) e anche analizzato. Il preroll sta senza interruzioni all'inizio di `recording.wav`. Il sonogramma ne mostra al massimo 5 s.

### Sovrapposizione
Le finestre di analisi possono sovrapporsi: 0 % (standard, una finestra ogni 3 s), 50 % (ogni 1,5 s) o 67 % (ogni secondo). Più sovrapposizione riconosce meglio i richiami brevi ai confini delle finestre, ma richiede più potenza di calcolo e batteria. Se il dispositivo è troppo lento, PIROL riduce automaticamente la sovrapposizione e lo segnala una volta per sessione con una snackbar. Per modelli con finestra più lunga (Perch, 5 s) il cursore "Passo max." (3, 4 o 5 s) limita la distanza tra due finestre.

### Registrazioni lunghe (segmenti)
Dopo la durata massima impostata (standard 60 min, regolabile da 10 a 80 min) inizia automaticamente un nuovo file (`recording-002.wav` ecc.). La registrazione prosegue senza interruzioni. Le indicazioni di tempo in `detections.jsonl` e Raven valgono dall'inizio della sessione. L'intestazione WAV viene aggiornata ogni 5 s: se l'app viene terminata bruscamente, si perdono al massimo circa 3 s di audio.

### Filtro passa-alto ed elaborazione
Il filtro passa-alto (200 Hz) elimina vento e rumori di passi; è attivo di default. Agisce solo sulla copia usata per il riconoscimento; la registrazione salvata resta invariata. Le modifiche valgono dalla registrazione successiva.

---

## 3. Visualizzazione: sonogramma, spettro, oscillogramma

Nella scheda Live e nella scheda Analisi. L'asse delle frequenze mostra 1/2/4/8/12 kHz. La visualizzazione arriva di default fino a **12 kHz** (preimpostazione per gli uccelli), regolabile fino a **24 kHz**. I livelli del sonogramma sono in dBFS.

**Modalità di visualizzazione (scheda Live):** nel pannello di visualizzazione (pressione lunga) si passa tra **Sonogramma**, **Spettro** e **Oscillogramma**. Lo spettro mostra il volume per frequenza con una vera FFT (dBFS, media, mantenimento dei picchi con picchi che sfumano, cursore). L'asse delle frequenze dello spettro si regola tra **Lineare** (standard, distanze in Hz uguali) e **Mel** (basse frequenze dilatate, come nel sonogramma); reticolo e indicatore del cursore seguono questa scelta. Il cursore **Intensità (gamma)** agisce anche sullo spettro (altezza delle curve e griglia in dB) ed è lo stesso valore del sonogramma. L'oscillogramma mostra l'andamento nel tempo con indicazione del livello (picco/RMS), passa-banda e un segno rosso in caso di saturazione; la sua **finestra temporale** (da 5 a 30 s) si regola nel pannello dell'oscillogramma. Un gamma non fa parte dell'oscillogramma. Finché non ci sono ancora dati, ogni vista mostra un proprio avviso ("Spettro in caricamento…", "Oscillogramma in caricamento…"). Frequenza, dinamica e schema dei colori valgono in comune per tutte e tre le viste. Nell'analisi sono disponibili anche spettro e oscillogramma.

**Adattare la visualizzazione:** una pressione lunga sul sonogramma (Live o Analisi) apre il pannello di regolazione sotto il sonogramma; gli stessi cursori si trovano in Impostazioni > Visualizzazione. Il pannello vale in comune per Live, Analisi e Impostazioni. In alto nel pannello "Fine" si trova su una riga propria; le preimpostazioni e le palette sono riquadri che vanno a capo se manca lo spazio.

| Cursore | Effetto |
|---------|---------|
| Preimpostazione | Standard / Richiami deboli / Ambiente rumoroso |
| Contrasto automatico | adatta continuamente la soglia in dB al segnale; altrimenti a mano (intervallo in dB) |
| Intensità (gamma) | più piccolo = parti deboli più chiare |
| Limite di luminosità | limita i segnali forti |
| Palette colori | Magma, Viridis, Scala di grigi, **Carta (chiara)** |
| Frequenza | intervallo visualizzato in kHz |
| Finestra temporale | secondi visibili (da 5 a 30 s) |

**Carta (chiara):** palette chiara come un classico sonogramma su carta, adatta alla luce del giorno. La palette si sceglie nel pannello (pressione lunga sul sonogramma); sul telefono non esiste più un pulsante dedicato nella scheda Live.

**Cambio di scheda:** se si lascia la scheda Live o si spegne lo schermo, PIROL ricalcola il sonogramma al rientro (fino alla finestra temporale impostata), così prosegue senza interruzioni. Se si è stati via più a lungo della finestra temporale, ricomincia da capo.

**Modalità** (Impostazioni > Visualizzazione): Uccelli, Pipistrelli o Banda larga. La modalità pipistrelli registra a 96 kHz (ultrasuoni); in questo caso non c'è preroll.

---

## 4. Verifica

Per ogni rilevamento sono disponibili queste azioni (scheda Live, scheda Analisi e Mappa):

| Azione | Stato | Presentazione |
|--------|-------|---------------|
| Conferma | Confermato | bordo verde |
| Incerto (?) | Incerto | bordo arancione |
| Rifiuta | Rifiutato | grigio |
| Correggi | Corretto | nome sostituito con il proprio inserimento |
| Scegli alternativa | Confermato | La specie del rilevamento viene sostituita dall'alternativa (stessa riga, stesso orario e stessa posizione); la vecchia specie resta come candidata. Annullamento nella snackbar |

Nella **scheda Live** la verifica non mostra più una snackbar. Dopo "Conferma" la scheda mostra un pulsante verde "Annulla"; esso riporta il rilevamento su "Incerto". I pulsanti Conferma, Incerto, Rifiuta e Modifica restano visibili, quindi lo stato si può cambiare in ogni momento. Alla scelta di una **alternativa**, allo spostamento sulla mappa e alla conferma tramite l'allarme della watchlist compare ancora una snackbar con **Annulla** (almeno 5 s). Tutte le azioni vengono registrate in `verifications.jsonl`; anche i rilevamenti rifiutati e sostituiti restano nell'esportazione (traccia di audit).

**Condividere un rilevamento:** estratto (3 s prima, 5 s dopo, durata massima regolabile) come WAV, M4A (AAC), FLAC (se il dispositivo lo supporta) oppure immagine PNG del sonogramma con didascalia.

---

## 5. Sessioni

Ogni registrazione crea una cartella di sessione, di default `Downloads/PIROL/AAAA-MM-GG/{sessionId}/`. Il nome della cartella riporta l'ora locale.

```
session.json              metadati (durata, luogo, statistiche GPS, modello usato)
detections.jsonl          tutti i rilevamenti
verifications.jsonl       tutte le azioni di verifica
detections.updates.jsonl  file supplementare durante la registrazione (allo stop viene unito a detections.jsonl)
recording.wav             registrazione (16 bit, mono, 48 kHz); nelle sessioni lunghe
recording-002.wav ...     altri segmenti
recording.selections.txt  Raven Selection Table (automatica allo stop)
memos.jsonl               memo di sessione con posizione nella registrazione (file in memos/)
human_windows.jsonl       elenco per nascondere le voci umane (mai esportato)
```

- **Spazio occupato:** circa 5,5 MB per minuto di registrazione.
- **Orari:** sempre ora locale con scostamento (ad es. `+02:00`).
- **Cancellazione automatica:** le sessioni brevi senza rilevamenti vengono eliminate (durata minima regolabile, 0 = disattivato).
- **Eliminare** una sessione nell'analisi: con conferma.
- **Modificare i dati di sessioni importate:** per le sessioni importate, nel menu di overflow dell'intestazione (icona con tre puntini) c'è la voce "Modifica dati": ora di inizio, luogo e nota si possono cambiare in seguito. Una nuova ora di inizio sposta tutti gli orari della sessione (rilevamenti, verifiche, varianti, percorso) della stessa differenza; le posizioni nella registrazione restano. Se cambia la **data**, PIROL rinomina anche la cartella della sessione (nuovo identificativo con il nuovo orario locale) e la sposta nella cartella giornaliera corrispondente (se le sottocartelle giornaliere sono attive). Ora e data di inizio si scelgono con i selettori di data e ora. Raven e GPX vengono riscritti. Il luogo vale per la sessione e per tutti i rilevamenti senza un proprio fix GPS; i punti spostati sulla mappa restano. Non possibile durante un rescan; anche un cambio di data non è possibile finché un caricamento di questa sessione è in coda o in corso.

### Cambiare posizione di salvataggio
Impostazioni > Registrazione > Posizione di salvataggio: "Scegli un'altra cartella" (SAF: memoria interna, scheda SD, cartella cloud). Facoltativamente con sottocartelle giornaliere. Se la cartella non è raggiungibile, PIROL salva nella cartella di riserva dell'app e lo segnala. Le singole registrazioni si possono anche spostare nella scheda Analisi (capitolo 6).

**Spostare sessioni:** al cambio PIROL chiede se le sessioni esistenti devono essere **copiate** nella nuova posizione ("Copia") oppure se si cambia soltanto la posizione ("Solo cambia").

---

## 6. Scheda Analisi

- **Elenco sessioni:** per data, con durata, dimensione e nota.
- **Aprire una sessione:** sonogramma ed elenco dei rilevamenti con indicazione dell'ora. Il rilevamento in riproduzione è evidenziato, un indicatore mostra la posizione.
- **Riproduzione:** per rilevamento (estratto con zoom) oppure l'intera registrazione; i segmenti vengono concatenati senza interruzioni. Le registrazioni lunghe vengono riprodotte in streaming.
- **Più occorrenze:** se una specie è stata riconosciuta più volte nel corso della registrazione e raggruppata in una scheda, la scheda mostra la riga "N occorrenze", espandibile. Le finestre consecutive sono raggruppate in **episodi** ("N occorrenze in M episodi"). Ogni episodio sta su una riga con ora di inizio e fine, posizione nella registrazione ("@ m:ss") e durata ("N finestre, M s"); il suo pulsante Riproduci riproduce l'episodio intero di seguito (anche per un canto lungo e continuo). Per le singole finestre si conosce solo l'orario, non la confidenza.
- **Riproduzione:** riproduce sempre una sola voce alla volta. Un secondo tocco sullo stesso pulsante ferma, un tocco su un'altra voce cambia, e un cambio di scheda termina la riproduzione. Questo vale in tutta l'app (Live, Analisi, Mappa, Riferimenti, memo vocali).
- **Verificare, correggere, aggiungere una specie:** come nella scheda Live, anche a posteriori.
- **Confronto:** rilevamento e riferimento affiancati, con riproduzione sincronizzata.
- **Ricerca:** l'icona di lente nell'elenco sessioni filtra per nome della specie (parte di parola, nome locale o scientifico). In una registrazione aperta la ricerca filtra l'elenco dei rilevamenti.
- **Intestazione e barra di riproduzione:** indietro, data e identificativo, Riproduci con indicazione di posizione, ricerca, Raven e Carica. Il caricamento segnala il suo esito con una snackbar ("Caricato", in caso di errore con il motivo, oppure che PIROL attende una decisione, capitolo 7). Con "rilevamento precedente/successivo" si passa tra le voci.
- **Navigazione:** un tocco sul sonogramma o sull'oscillogramma salta a quel punto; se non è in corso una riproduzione, PIROL memorizza la posizione di partenza (indicatore ciano) per il prossimo Riproduci.
- **Spettro e oscillogramma:** come nella scheda Live (compresi Lineare/Mel e gamma, capitolo 3); nell'oscillogramma un indicatore mostra la posizione di riproduzione.
- **Zoom con due dita:** nella panoramica e nel sonogramma si zooma con pizzico (due dita). L'estratto è di almeno 3 s (per sessioni più brevi, l'intera sessione) e al massimo l'intera registrazione; riducendo lo zoom fino all'intera registrazione, lo zoom viene annullato. Toccare e trascinare con un dito funzionano come prima.
- **+ Specie:** prende l'ora dalla posizione di riproduzione (+/-10 s). Per sessioni composte da più file con pause tra i segmenti, PIROL calcola con l'orologio del segmento corrispondente; la finestra resta all'interno del segmento. Il luogo si può determinare con "Scegli sulla mappa".
- **Panoramica:** una barra di panoramica mostra i rilevamenti sull'intera registrazione; la tabella Raven li elenca in forma di tabella.
- **Ricreare il file Raven**, ad esempio dopo una verifica successiva.
- **Condividere episodi:** ogni episodio ha un proprio pulsante Condividi; si condivide l'estratto di quell'episodio.
- **Memo:** se esistono memo, l'analisi mostra sotto il sonogramma e la panoramica il blocco espandibile "Memo (n)" (altezza massima 200 dp, scorrevole) con riproduzione. Elenca memo di sessione e memo di specie; per i memo di specie senza orario noto della registrazione compare "ca. ... (orario del rilevamento)". Il menu di ogni memo offre **Condividi** e **Salva con nome** (file WAV invariato, nome `AAAAMMGGHHMMSS_Memo_Nota.wav`, senza mascheramento delle voci) e, per i memo di sessione, **Elimina**: il memo scompare subito, una snackbar offre **Annulla** (10 s); solo dopo il file viene eliminato definitivamente. Durante la registrazione in corso l'eliminazione è bloccata ("Elimina (registrazione in corso)").
- **Rescan:** il pulsante Rescan accanto alla lente (solo se esiste una registrazione) apre la scelta di modello, lista o gruppi di specie, soglia di confidenza, sovrapposizione e secondo modello. Il rescan viene eseguito in background come servizio in primo piano, con avanzamento e possibilità di annullamento; il risultato compare come chip accanto all'originale ("Data Ora · Modello"). I risultati compaiono **progressivamente**: la variante si apre subito, l'elenco dei rilevamenti cresce ogni pochi secondi e si può verificare già durante la scansione (intestazione "· in corso"). Se si annulla, il risultato parziale resta ("interrotto a x min"); lo stesso accade se l'app viene chiusa durante la scansione. Il rescan è diventato più veloce: usa più core di calcolo e il secondo modello gira in parallelo al modello principale; i risultati sono gli stessi di prima. Le verifiche già effettuate non vengono mai sovrascritte. Durante un rescan non è possibile registrare e la variante in corso non è eliminabile. Dopo l'importazione di file audio, lo scan segue la stessa procedura (vedi "Importare audio").
- **Importare audio:** il pulsante Importa nell'intestazione dell'elenco sessioni acquisisce una sessione PIROL (ZIP o cartella) oppure file audio (WAV, FLAC, MP3, M4A, OGG) come nuova sessione. L'ora di inizio è precompilata (ad es. da un commento AudioMoth) ed è modificabile. Con più file si sceglie: una sessione per file oppure tutti in una sessione (segmenti, ordinati per ora).
  - **Ora di inizio:** si scelgono data e ora con i selettori di data e ora. L'ora del file coincide spesso con la fine della registrazione; l'interruttore **"Ora file = fine della registrazione"** (standard attivo) calcola allora inizio = ora del file meno la durata; disattivato, l'ora del file vale come inizio. Sotto il campo compare da dove proviene l'ora.
  - **Scan dopo l'importazione:** poi PIROL propone lo scan (finestra Rescan). Con più file importati ciascuno come sessione propria, la scelta vale per **tutte** le nuove sessioni, scansionate una dopo l'altra. Se l'originale di una sessione è ancora vuoto (senza rilevamenti), uno scan completo diventa l'**originale**: `detections.jsonl`, `verifications.jsonl` e il file Raven si trovano quindi direttamente nella cartella della sessione, senza passaggi manuali. Vale anche se esistono già scan più vecchi; all'apertura di una tale sessione PIROL adotta il più recente scan completo. Gli scan successivi restano varianti, quelli interrotti non vengono mai adottati.
  - **Annullamento:** se si annulla l'importazione (o fallisce), la sessione importata a metà viene eliminata; le sessioni già completate di un'importazione multipla restano. Diversamente dal rescan, che conserva il risultato parziale. Se si annulla la finestra dello scan, gli scan in attesa vengono scartati.
- **Salva con nome:** nel menu di overflow dell'intestazione. Si sceglie **ZIP** o **Cartella** e poi la destinazione (anche scheda SD, USB o cartella cloud). Il contenuto corrisponde allo ZIP di caricamento; per la cartella si crea una sottocartella con il nome di esportazione (capitolo 7). Una destinazione nell'archivio PIROL o nella sessione stessa viene rifiutata. Per la registrazione in corso la voce è grigia. Le voci umane vengono nascoste secondo l'impostazione (capitolo 7).
- **Intestazione:** data · comune · nota su una riga; un tocco sulla nota apre la finestra di dialogo. L'interruttore Compatto/Grande corrisponde a quello della scheda specie.

**Selezionare più registrazioni:** una pressione lunga su una registrazione nell'elenco avvia la modalità di selezione; le altre registrazioni si selezionano con un tocco. L'intestazione mostra "N selezionati" con le azioni "Tutti", "Sposta" ed "Elimina"; la X o il tasto Indietro terminano la modalità. La registrazione in corso non è selezionabile.
- **Sposta:** la cartella di destinazione è liberamente scelta (selezione cartella, anche scheda SD o memoria USB). Ogni sessione viene copiata con tutti i file (segmenti di registrazione, memo); le dimensioni dei file vengono verificate, poi l'originale viene eliminato. In caso di errore o annullamento l'originale resta e la copia parziale viene rimossa. Se alla destinazione esiste già una sessione con lo stesso nome, vengono aggiunti `-2`, `-3` ecc. L'avanzamento è visibile e interrompibile. Una cartella di destinazione dentro `Downloads/PIROL` (o la registrazione stessa) viene rifiutata; in tal caso non viene spostato nulla.
- **Elimina:** dopo richiesta di conferma ("Eliminare N registrazioni?"); l'operazione non è reversibile. L'eliminazione di singole registrazioni tramite l'icona del cestino resta invariata.

---

## 7. Esportazione

**Raven Selection Table** (`recording.selections.txt`): viene scritta automaticamente accanto al WAV allo stop, separata da tabulazioni, UTF-8. Si apre in Cornell Raven, Audacity (come traccia di etichette) e Sonic Visualiser.

Contiene tra l'altro: Selection, Begin File (per i segmenti il rispettivo WAV), Begin/End Time (s), Low/High Freq (Hz), Begin Date Time (con scostamento), Species, Confidence, Status (con secondo modello: Second Rank). Quali colonne aggiuntive vengono scritte è indicato in Impostazioni > Export e caricamento > Campi di esportazione Raven. L'esportazione KML non esiste più.

### Raven: una riga per episodio
Riconoscimenti consecutivi di una specie formano un **episodio**; la tabella ha una riga per episodio (non più per voce raggruppata). Una nuova riga inizia quando la pausa è più lunga di una finestra di analisi. Confidence è il massimo dell'episodio, Begin File il segmento del suo inizio. Colonne aggiuntive: `Cluster` (identificativo della prima voce) e `Windows` (numero di finestre). La tabella Raven nell'app mostra le stesse righe.

### Raven: memo
Ogni memo di sessione (capitolo 2) compare come riga propria con Species `Memo` nella sua posizione nella registrazione. La colonna `Memo` contiene il percorso del file memo (anche per memo di specie); può essere disattivata in Campi di esportazione Raven. Le righe dei memo compaiono anche nelle tabelle delle varianti di rescan.

**Pacchetto ZIP:** la sessione come ZIP, per la condivisione manuale o per il caricamento automatico (capitolo 12).

### Nascondere le voci umane (protezione dei dati)
PIROL sostituisce nelle **copie di esportazione** (Salva con nome come ZIP o cartella, ZIP di caricamento, condivisione di estratti) le voci umane con rumore: dove il modello riconosce persone, la banda vocale (circa 300-3900 Hz) viene sostituita, con un margine di 0,5 s; il resto rimane invariato. La registrazione originale, i rilevamenti e Raven non vengono mai modificati. L'elenco `human_windows.jsonl` viene creato in diretta e durante il rescan; le aree non ancora valutate (sessioni importate o vecchie) vengono scansionate da PIROL durante l'esportazione (con avanzamento, interrompibile).
- **Impostazione:** Impostazioni > Export e caricamento > Protezione dei dati > "Nascondi voci umane nell'esportazione" (standard attivo). Disattivandola, PIROL avvisa ("Non nascondere più le voci?"), perché le esportazioni possono contenere conversazioni di persone estranee. In modalità esperto, "Soglia umana" stabilisce da quale punteggio si nasconde (più bassa = più prudente).
- **Memo:** restano sempre non modificati; il periodo di un memo dal vivo nella registrazione è escluso dal mascheramento.
- **Modello senza classe umana:** se il modello di una sessione non conosce voci umane, PIROL chiede al salvataggio, al caricamento e alla condivisione: con BirdNET 3.0 cercare persone (non durante una registrazione, può richiedere minuti), esportare senza mascheramento (con avviso) oppure annullare. Il caricamento automatico in questo caso non carica nulla senza mascheramento, ma attende la decisione.

**Nomi dei file:** i rilevamenti condivisi si chiamano `AAAAMMGGHHMMSS_Specie_Nota.ext` (ad es. `20260420143005_Merlo_Giardino_dietro.wav`), i pacchetti ZIP `AAAAMMGGHHMMSS_Nota.zip` (ora = inizio sessione). Se manca la nota, la parte viene omessa; spazi e caratteri speciali diventano `_`.

---

## 8. Mappa

Rilevamenti georeferenziati su una mappa. Il **menu della mappa** si apre con uno scorrimento dal bordo sinistro o tramite l'icona di menu in alto a sinistra ("Apri menu mappa"). Contiene visualizzazione, origine della mappa, mappe offline e filtro delle specie.

- **Origine della mappa:** Automatica (swisstopo in Svizzera, OpenStreetMap all'estero), OpenStreetMap, Mappa swisstopo, Ortofoto swisstopo.
- **Offline:** per le mappe swisstopo si può scaricare il ritaglio visibile ("Scarica ritaglio", con indicazione del numero di riquadri e della dimensione). Un'interruzione mantiene i riquadri già caricati; un nuovo avvio carica solo quelli mancanti. "Elimina mappe offline" rimuove tutti i riquadri salvati.
- **Visualizzazione:** tutte le sessioni, "Solo Live" oppure "Ultime 3". Le osservazioni rifiutate sono nascoste. I marcatori sovrapposti vengono raggruppati in base allo zoom. La mappa parte dalla propria posizione.
- **Toccare un punto:** un tocco su un punto apre un menu con Verifica (Conferma, Incerto, Rifiuta), "Modifica", "Sposta posizione" e Riproduci (Play oppure "Ferma riproduzione"). Una pressione lunga apre lo stesso menu e consente inoltre di collegare osservazioni come osservazione simultanea. Viene riprodotto l'estratto del rilevamento dalla registrazione della sessione; se manca, compare un messaggio.
- **Marcatore di memo:** i memo di sessione con posizione (capitolo 2) compaiono come marcatori di microfono. Un tocco apre una finestra con ora e riproduzione. Sono visibili con "tutte le sessioni" e "Ultime 3", non con "Solo Live".
- **Più punti sovrapposti (cluster):** un tocco su un cluster mostra l'elenco dei rilevamenti. Per ogni voce ci sono Verifica, Riproduci e "Sposta posizione".
- **Spostare la posizione:** la mappa mostra in alto l'avviso "Sposta la mappa: porta il mirino sulla nuova posizione di ..., poi Applica." Spostare la mappa in modo che il mirino al centro cada sulla nuova posizione, poi in basso "Applica" (oppure "Annulla"). Compare poi una snackbar con **Annulla**. Lo spostamento vale anche per i punti dal vivo; se non è in corso una sessione, la nuova posizione vale solo fino alla chiusura dell'app.
- **Filtro specie:** nel menu della mappa c'è il filtro specie: "Solo specie d'allarme" oppure nascondere singole specie (Tutte/Nessuna). Il filtro vale fino alla chiusura dell'app; i percorsi restano visibili.
- **Percorso:** un percorso registrato durante la registrazione compare come linea (in modalità "Solo al rilevamento" tratteggiata, solo nei punti di rilevamento). Vedi capitolo 14.
- **Pulsante posizione:** centra la mappa sulla propria posizione e ingrandisce fino a un'altezza visibile di circa 200 m.
- **Pulsanti di zoom:** i pulsanti +/- sono 50 % più grandi e si trovano sopra il pulsante di posizione.

---

## 9. Riferimenti e specie simili

I rilevamenti verificati possono essere salvati come riferimento ("Salva come riferimento") e poi rimossi. La scheda Riferimenti mostra le registrazioni salvate; è possibile anche un'importazione audio. I nuovi rilevamenti vengono confrontati con i riferimenti tramite embedding.

Impostazioni > Specie simili e riferimenti: ricerca di somiglianza attiva/disattiva, numero di righe mostrate (0 = disattivo, 0.5 = scorrimento delle ultime 5, 1-10 = elenco), limite delle registrazioni di confronto salvate.

---

## 10. Watchlist e allarme

**Watchlist:** specie obiettivo che fanno scattare un allarme. Gestione in Impostazioni > Watchlist e allarme: cercare una specie e aggiungerla, rimuoverla oppure caricarla da `watchlist.json`. Maiuscole/minuscole e spazi non hanno importanza nel confronto.

**Cartella autorizzata:** sotto "Autorizza cartella PIROL" scegliere una sola volta `Downloads/PIROL`. PIROL legge poi automaticamente `watchlist.json` e le cartelle `lists/` (dettagli nel capitolo 11). "Crea lista di esempio" genera un modello.

**Uscita allarme** (attivabile singolarmente): vibrazione, notifica, avviso a schermo intero. Sulla **schermata di blocco** l'allarme a schermo intero compare solo se le "Notifiche a schermo intero" per PIROL sono consentite nelle impostazioni Android (pulsante "Apri autorizzazione"). Altrimenti resta una notifica. Con "Prova allarme" (dopo 5 s, bloccare il dispositivo) si può verificare.

Nell'allarme la specie può essere **confermata** direttamente; "Annulla" resta poi disponibile.

**Pausa allarme:** intervallo minimo tra allarmi della stessa specie (0 = ogni riscontro fa scattare un allarme).

---

## 11. Gruppi di specie e liste di specie

Il filtro delle specie agisce per gruppo: **Uccelli, Ortotteri, Anfibi, Mammiferi** ciascuno su **Off / Tutte / Auto / Lista**; **Altro** solo su **Off / Tutte** (standard Off). "Auto" usa il geomodello (posizione e settimana BirdNET). Le specie che il geomodello non conosce, così come i gruppi senza maschera del geomodello, usano la lista di accompagnamento del gruppo (Uccelli: lista CH; Ortotteri e Anfibi: lista CH in basso; Mammiferi: tutte). "Lista" sceglie una delle liste fornite o una lista personale. È fornita la lista "CH Ortotteri & Anfibi" (dati GBIF, CC BY 4.0).

**Auto senza GPS:** se manca una posizione attuale, vale l'ultima posizione salvata per al massimo 30 giorni; poi "Auto" usa la lista sostitutiva del gruppo. All'avvio della registrazione compare un avviso, e in Impostazioni > Specie e allarme (geomodello) è indicata la base ("Senza GPS vale l'ultima posizione del [data] (al massimo 30 giorni), altrimenti la lista sostitutiva"). La posizione salvata viene aggiornata solo con un calcolo con fix GPS. Facoltativamente, l'**elenco annuale** ignora la stagione (interruttore nella sezione geomodello).

Nell'editor delle liste le specie si scelgono per ordine, famiglia e genere; "Mostra nomi scientifici" commuta la forma dei nomi.

### Liste personali
Liste personali possono essere depositate come file JSON nella cartella `lists/` della cartella PIROL autorizzata (`Downloads/PIROL/lists/*.json`). Compaiono come liste selezionabili nei gruppi di specie (opzione "Lista"). Le liste vengono **rilette a ogni avvio di registrazione**; le modifiche valgono quindi dalla registrazione successiva. Le liste difettose vengono saltate e segnalate con il motivo. Modello è "Crea lista di esempio" (`lists/beispiel_liste.json`).

I nomi scientifici vecchi e nuovi (ad es. dopo modifiche tassonomiche) vengono unificati, così le liste funzionano con tutti i modelli.

---

## 12. Caricamento

Impostazioni > Caricamento: le sessioni registrate possono essere caricate automaticamente dopo la registrazione.

| Destinazione | Indicazioni |
|--------------|-------------|
| Off | solo copia locale, esportazione manuale |
| Cartella | cartella a scelta (dispositivo, Drive, OneDrive ...) tramite selezione cartella |
| NAS (SMB / condivisione Windows) | host, condivisione, percorso, utente, password |

La password NAS viene memorizzata cifrata sul dispositivo. "Verifica connessione" controlla le indicazioni. Opzioni: caricare solo con WLAN, non conservare la copia ZIP locale dopo il caricamento. Lo stato dell'ultimo caricamento compare sotto.

---

## 13. Modelli

Impostazioni > Modello e riconoscimento > Modello:

| Modello | Caratteristiche |
|---------|-----------------|
| BirdNET 3.0 FP16 (consigliato) | Standard, 11'725 classi, 32 kHz, finestra di 3 s |
| BirdNET 3.0 FP16 compatto (pruned) | più piccolo (circa 70 MB), riconoscimento praticamente uguale |
| BirdNET 3.0 Preview 3.1 FP16 / FP32 | versione preliminare più vecchia, 11'560 classi |

Il modello attivo si cambia con la selezione, dalla prossima analisi. Il numero di specie accanto al modello si aggiorna subito dopo il cambio. I modelli possono essere scaricati (interruzione e ripresa possibili), importati ed eliminati. Ogni sessione ricorda il modello usato.

**Tabella dei modelli (da v0.1.1):** la colonna Primario (radio) determina il modello principale. Secondario (spunta, solo in modalità esperto) sceglie il secondo modello che verifica ogni rilevamento; deselezionabile, mai uguale al primario. Il cestino elimina un modello installato dopo conferma; il primario non si può eliminare ed è bloccato durante una registrazione. I modelli non caricati mostrano nella riga un'icona di caricamento con avanzamento e annullamento.

**Soglia di confidenza:** il cursore "Soglia di confidenza (tutte le specie)" vale in modo uguale per tutte le specie (standard 50 %). Gli allarmi della watchlist scattano solo a partire da questa soglia.

**Confidenza minima:** la soglia di riconoscimento è regolabile globalmente. Un modello può inoltre avere una propria confidenza minima (ad es. 80 % per Perch); vale il valore più alto, e il modello lo mostra.

### Importare modelli propri o altri
PIROL non è vincolata a BirdNET. Tramite "Importa un altro modello..." si seleziona insieme un pacchetto di tre file: `.onnx` (modello), `.pirol.json` (profilo) ed etichette CSV. L'app verifica checksum, ingressi/uscite e numero di etichette e segnala gli errori in chiaro. Frequenza di campionamento e lunghezza della finestra sono libere.

Sono verificati **BirdNET 2.4** (6'522 specie, 48 kHz, circa la metà del tempo di calcolo di 3.0), **Perch v2** (Google, 14'795 classi, finestra di 5 s) e **Nighthawk 0.3.1**. I file non fanno parte dell'app. Per la conversione di modelli TensorFlow, Keras e TFLite (anche addestramenti propri) è incluso lo script `tools/convert_model.py`, vedi `tools/README_convert_model.md` e `tools/MODEL_PROFILE_FORMAT.md`.

**Licenze:** BirdNET 3.0 Apache 2.0, Perch v2 Apache 2.0, BirdNET 2.4 e Nighthawk solo non commerciale (CC BY-NC rispettivamente BY-NC-SA). La licenza viene mostrata all'importazione.

**Avvertenza:** Perch lavora a 32 kHz; i grilli sugli ultrasuoni non sono quindi udibili.

**Statistica del secondo modello (modalità esperto):** in Impostazioni > Modello e riconoscimento, il pulsante "Statistica del secondo modello" apre una finestra di dialogo. PIROL legge a tale scopo tutte le sessioni (solo gli originali, in sola lettura; le varianti di rescan e le voci manuali non contano) e mostra una tabella incrociata: verifica (confermato, rifiutato, incerto, aperto, corretto/sostituito) rispetto al risultato del secondo modello (Verificato, Non verificabile, Contraddice, Nessun risultato), totale e per ogni secondo modello. Si aggiungono barre di avanzamento verso l'obiettivo (200 rilevamenti confermati e 50 rifiutati con risultato del secondo modello) e la quota di contraddizioni per i rilevamenti confermati e per quelli rifiutati. Aiuta a valutare se il secondo modello è utile come supporto alla verifica. Chiudere la finestra interrompe la lettura.

**Perch come secondo modello:** richiede circa 650 MB di memoria aggiuntiva. L'app avvisa all'attivazione se il dispositivo ha meno di 6 GB di RAM. Il secondo modello lavora con l'audio grezzo della registrazione, così le finestre di 5 s contengono l'ambiente reale.

---

## 14. GPS

Impostazioni > Mappa e GPS (fino alla v0.0.10: Registrazione e memoria):

| Impostazione | Significato |
|--------------|-------------|
| Modalità GPS "Continua" | fix periodici all'intervallo impostato (2/5/10/20/60 s, standard 10 s) |
| Modalità GPS "Solo al rilevamento" | GPS solo al momento di un rilevamento, almeno 30 s tra due fix; l'intervallo non vale in questo caso. Fa risparmiare circa un terzo della batteria |
| Imprecisione GPS max. | fix meno precisi vengono scartati (standard 50 m) |
| Smussamento GPS | mediana su 5 fix, attenua i valori anomali |

Ogni rilevamento riceve una posizione aggiornata, anche a schermo spento. Le modifiche valgono dalla registrazione successiva.

**Fix di avvio:** all'avvio del programma e allo zoom della mappa PIROL acquisisce una sola volta una posizione senza registrazione in corso (nessun tracciamento continuo).

**Percorso:** il percorso ("Registra percorso", standard attivo) viene scritto durante la registrazione (`track.jsonl`) e allo stop salvato come `track.gpx` accanto alla registrazione (orari in UTC).

**Comune:** il pulsante "Inserisci comune" riporta nella nota il comune della posizione (nella finestra di dettaglio, nella nota di sessione e nell'analisi). I dati comunali (Svizzera) sono disponibili offline nell'app. Al di fuori non viene determinato nulla, salvo che l'opzione esperta Impostazioni > Mappa e GPS > Avanzate > "Determina comune online (Google)" sia attiva (standard disattivato; in tal caso le coordinate vengono inviate a Google).

---

## 15. Energia e batteria

Impostazioni > Modello e riconoscimento > Profilo energetico:

| Profilo | Analisi |
|---------|---------|
| Preciso | ogni finestra di 3 s |
| Bilanciato | ogni 6 s |
| Risparmio | ogni 15 s, per lunghe giornate sul campo |
| Ultra-risparmio | ogni 30 s, massima autonomia |

**Valori pratici** (tablet Galaxy Tab A11+, 6'840 mAh, registrazione continua, WLAN attiva, BirdNET 3.0 FP16; valori indicativi, circa ±15 %):

| Impostazione | Consumo | Autonomia con batteria piena |
|--------------|---------|------------------------------|
| Preciso, schermo spento, GPS continuo (10 s) | circa 2.2 %/h | circa 45 h |
| come sopra, sovrapposizione 67 % | circa 3.5 %/h | circa 28 h |
| come sopra, **schermo acceso** | circa 9 %/h | circa 12 h |
| Preciso, GPS solo al rilevamento | circa 1.5 %/h | circa 60 h |
| Risparmio (15 s), GPS 60 s | circa 0.9 %/h | circa 4-5 giorni |

**Consigli:**
1. **Spegnere lo schermo** è la leva più grande (più del doppio di registrazione e analisi insieme). La registrazione prosegue nel servizio.
2. Scegliere la modalità GPS "Solo al rilevamento".
3. Lasciare la sovrapposizione al 0 %; per impieghi lunghi il profilo "Risparmio".
4. La scelta del modello conta poco (pruned quasi come final, Preview 3.1 circa 10 % in più, FP32 non più di FP16).

---

## 16. Lingue

Impostazioni > Lingua: lingua dell'app (lingua di sistema o scelta): tedesco, inglese, francese, italiano, catalano, portoghese, olandese, tedesco svizzero. **Il tedesco svizzero e il portoghese sono bozze**; i testi mancanti compaiono in tedesco. La lingua dei **nomi delle specie** segue la lingua dell'app, ma può essere impostata separatamente (23 lingue). I nomi delle specie compaiono sempre nella lingua scelta, anche sulla mappa, in Raven e per i rilevamenti più vecchi (vengono ricalcolati alla visualizzazione; i file salvati restano invariati); le specie rinominate si ritrovano anche con il vecchio nome. Se un nome manca nella lingua scelta, per il tedesco PIROL usa il nome tedesco delle liste di specie fornite, per le altre lingue prima il nome inglese.

---

## 17. Impostazioni in sintesi

Le impostazioni sono suddivise in sezioni richiudibili; lo stato viene ricordato. Di default è aperta solo la sezione "Lingua".

| Sezione | Contenuto |
|---------|-----------|
| Lingua | lingua dell'app, lingua dei nomi delle specie |
| Registrazione | info audio, preroll, sovrapposizione, profilo energetico, posizione di salvataggio (cartella, sottocartelle giornaliere, cancellazione automatica, spostare sessioni), durata massima della registrazione e lunghezza dell'estratto, ricampionatore |
| Modello e riconoscimento | modelli (download, importazione, eliminazione), soglia di riconoscimento (tutte le specie), alternative da, massimo di specie per finestra, preimpostazioni, suggerimenti di specie, raggruppamento dei rilevamenti, specie simili e limite di embedding |
| Specie e allarme | filtro regionale e liste personali, watchlist, pausa e uscita allarme, prova allarme, cartella PIROL, stagionalità e rarità |
| Visualizzazione | lingua dei nomi delle specie, schermo intero, sonogramma (modalità, preimpostazione, contrasto, gamma, limite, palette, frequenza, finestra temporale), scheda specie compatta |
| Mappa e GPS | modalità GPS, registra percorso, intervallo GPS, precisione e smussamento; esperti (Avanzate): "Determina comune online (Google)" |
| Export e caricamento | campi di esportazione Raven (incl. memo), protezione dei dati (nascondi voci umane, soglia umana), caricamento (destinazione cartella/NAS, password, verifica connessione, solo WLAN) |
| Specie simili e riferimenti | ricerca di somiglianza, numero di righe, registrazioni di confronto (capitolo 9) |
| Info | versione, fonti, novità, diagnostica (benchmark), "Sostieni PIROL" |

L'interruttore **Impostazioni esperte** (sotto "Avvia configurazione") nasconde in modalità semplice tutto ciò che è raro; in modalità esperto è tutto visibile. Nessuna impostazione va persa. Le opzioni rare si trovano nel sottoblocco richiudibile "Avanzate" della rispettiva sezione (ad es. filtro passa-alto, raggruppamento dei rilevamenti, fenologia, durata massima della registrazione).

**Sostieni PIROL:** in Impostazioni > Info, il pulsante "Sostieni PIROL" apre la pagina delle donazioni nel browser. La donazione è volontaria; PIROL resta gratuito e completo.

**Ricampionatore:** Polifase (consigliato) filtra i rumori disturbanti oltre 16 kHz, che altrimenti potrebbero comparire come segnali fantasma. "Lineare" è il metodo vecchio. Il cambio mostra un avviso e agisce senza riavviare la registrazione.

**Diagnostica:** il benchmark misura il tempo di calcolo del modello attivo con 1/2/4 thread (1-3 minuti, non durante una registrazione). Con "Scegli file ONNX e misura" si può testare anche un modello qualsiasi prima dell'importazione.

---

## 18. Note tecniche

- **Registrazione:** 48 kHz, PCM 16 bit, mono (96 kHz in modalità pipistrelli). Riconoscimento convertito a 32 kHz (BirdNET 3.0).
- **Dispositivo minimo:** Android 8.0. Compatibile con pagine di memoria da 16 KB (Android 15+).
- **Fairphone e altri dispositivi:** la frequenza di campionamento viene concordata con il dispositivo; in caso di problemi l'app passa a un altro driver audio.
- **Formati dei dati:** `session.json`, `detections.jsonl`, `verifications.jsonl`, TSV Raven; orari in ora locale con scostamento.

---

## 19. Modifiche

L'elenco completo si trova in `CHANGELOG.md` nel progetto.

### Versione 0.1.3
- Memo di sessione: eliminazione con Annulla, posizione salvata alla pressione e mostrata come marcatore sulla mappa, condivisione e salva con nome (capitoli 2, 6, 8)
- Importazione: selettori di data e ora, interruttore "Ora file = fine della registrazione", scan per tutte le sessioni importate, uno scan diventa l'originale se l'originale è vuoto, con Raven nella cartella (capitolo 6)
- Cambiare la data di una sessione importata rinomina la cartella della sessione e la sposta nella cartella giornaliera corretta (capitolo 5)
- "+ Specie" tiene conto delle pause tra i segmenti (capitolo 6)
- Spettro: frequenza lineare (standard) o Mel, gamma anche nello spettro, finestra temporale nell'oscillogramma (capitolo 3)
- Analisi: zoom con due dita, il caricamento segnala il suo esito con una snackbar (capitolo 6)
- Rescan più veloce, secondo modello in parallelo (capitolo 6)
- Auto senza GPS: ultima posizione fino a 30 giorni, avviso all'avvio, base nelle impostazioni, elenco annuale (capitolo 11)
- Statistica del secondo modello in modalità esperto (capitolo 13)
- Nomi delle specie sempre nella lingua scelta, nomi tedeschi delle liste come riserva (capitolo 16)
- Pulsante "Sostieni PIROL" (facoltativo) in Impostazioni > Info (capitolo 17)
- Aiuto: la modalità aiuto copre più elementi, anche marcatori della mappa e finestre di dialogo (capitolo 1)
- Nuovo capitolo 20: installazione e aggiornamento

### Versione 0.1.2
- Modificare successivamente i dati delle sessioni importate (ora di inizio, luogo, nota) (capitolo 5)
- "Salva con nome" come ZIP o cartella (capitolo 6)
- Raven: una riga per episodio, nuove colonne `Cluster`, `Windows` e `Memo` (capitolo 7)
- Rescan e importazione: risultati visibili e verificabili in progresso; con il rescan l'annullamento conserva il risultato parziale, con l'importazione elimina la sessione importata a metà (capitolo 6)
- Voci umane nascoste nell'esportazione, attivo di default; richiesta di conferma con modello senza classe umana (capitolo 7)
- Comune online tramite Google solo come opzione, standard disattivato (capitolo 17)
- Memo di sessione con pressione prolungata, blocco "Memo" nell'analisi, riga Memo in Raven (capitoli 2, 6, 7)
- Schede specie dal vivo più tranquille: cambio al massimo ogni 5 s, riscontri nelle prime 5 con lampeggio blu (capitolo 2)
- Pulsante aiuto "?": modalità aiuto e manuale offline (capitolo 1)
- Manuale corretto: filtro specie nel menu della mappa (capitolo 8), nomi delle sezioni delle Impostazioni (capitoli 1, 5, 7, 17)

---

## 20. Installazione e aggiornamento

**Quale APK?** Ogni versione è pubblicata come release su GitHub (Nikiphedia/Pirol, sezione "Releases") con un'APK per ogni architettura di processore:
- `Pirol-<Versione>-arm64-v8a-release.apk`: per quasi tutti i telefoni e tablet attuali. In caso di dubbio prendere questo file.
- `Pirol-<Versione>-armeabi-v7a-release.apk`: solo per i vecchi dispositivi a 32 bit.

**Installazione:**
1. Scaricare l'APK adatta dalla release GitHub sul dispositivo e aprirla. Android chiede la prima volta se il browser o il gestore di file usato può installare app; consentirlo una volta.
2. **Dispositivi Samsung:** la funzione **"Blocco automatico"** (Auto Blocker; Impostazioni > Sicurezza e privacy) impedisce l'installazione di app al di fuori dello store. Disattivarla brevemente per l'installazione, installare PIROL, poi riattivarla. Va ripetuto **a ogni aggiornamento**.
3. Toccare Installa e aprire l'app. Al primo avvio la procedura guidata accompagna nelle impostazioni di base (capitolo 1).

**Aggiornamento:** installare la nuova APK semplicemente sopra l'installazione esistente (stessa architettura; su Samsung di nuovo con il blocco automatico brevemente disattivato). Sessioni, modelli, liste e impostazioni restano; non occorre disinstallare nulla. Note sulle versioni precedenti: capitolo 1 (aggiornamento da versioni precedenti). Dopo l'aggiornamento PIROL mostra una volta "Novità".

---

*PIROL v0.1.3 — ETA Systems GmbH, 5004 Aarau — stato 2026-11-25*
