# PIROL — Gebruikshandleiding

Akoestische soortherkenning in realtime voor Android, bedoeld voor veldwerk.

*Geldt vanaf versie v0.1.3 (stand 2026-11-25). Wat er tussen de versies is veranderd, staat in `CHANGELOG.md`.*

De app heeft vijf tabbladen: **Live**, **Analyse**, **Referenties**, **Kaart** en **Instellingen**. De knop **?** (Hulp) is geen tabblad: één tik zet de hulpmodus aan, een dubbeltik opent deze handleiding (hoofdstuk 1, onderdeel "Hulpknop").

---

## 1. Eerste start

1. Open de app. De installatiewizard leidt je door taal, niveau (beginner/expert), gebruiksdoel, soortenlijst, model (incl. geomodel), rechten (microfoon, GPS, meldingen) en samenvatting. De waarden worden pas overgenomen bij "Gereed".
2. Kies een **soortenlijst** (hoofdstuk 11): de soortgroepen (vogels, sprinkhanen, amfibieën, zoogdieren, overig) beperken de herkenning tot plausibele soorten. Later aanpasbaar via Instellingen > Soorten & alarm.
3. **Model downloaden**: er kunnen meerdere modellen tegelijk gekozen worden. Standaard is BirdNET 3.0 FP16 (aanbevolen, ca. 170 MB). De download loopt op de achtergrond door, kan worden onderbroken en later worden voortgezet. Je kunt ook handmatig een modelbestand kiezen.
4. **Opslaglocatie controleren** (Instellingen > Opname). Standaard: `Downloads/PIROL/`, zichtbaar via USB.

**Installatie (APK):** Er is per processorarchitectuur een release-APK. Voor actuele toestellen (telefoons en tablets) installeer je het bestand `Pirol-<versie>-arm64-v8a-release.apk`; alleen voor oude 32-bit-toestellen het bestand `...-armeabi-v7a-release.apk`. De APK's zijn daardoor veel kleiner dan vroeger de gezamenlijke APK. De app heeft een nieuw pictogram (gele vogel), ook als eenkleurig pictogram voor themapictogrammen. De uitgebreide beschrijving van installatie en update (ook voor Samsung-toestellen) staat in hoofdstuk 20.

**Wat is nieuw:** Na een update toont PIROL eenmalig een dialoog "Wat is nieuw" met de belangrijkste wijzigingen van de versie. Hij kan op elk moment opnieuw worden opgeroepen in de instellingen onder "Info".

### Hulpknop
De knop **?** (tablet en smalle navigatie: onder Instellingen; telefoon: helemaal rechts in de balk) biedt twee manieren:
- **Tikken** schakelt de **hulpmodus** aan of uit. Bovenaan verschijnt de balk "Hulpmodus: knop aanraken voor uitleg". Een tik op een knop of regelaar veroorzaakt dan niets, maar toont een korte uitleg, indien nodig met "Meer in de handleiding" (springt naar het bijbehorende hoofdstuk). Beëindigen: X in de balk, Terug of nogmaals **?**.
- **Dubbeltikken** opent deze handleiding offline, in de app-taal (ontbreekt de vertaling, dan in het Duits). Een inhoudsopgave leidt naar de onderdelen.

**Update van oudere versies:** Vanaf v0.0.8 is PIROL ondertekend. Wie v0.0.7 of ouder heeft geïnstalleerd, moet eenmalig de-installeren en vooraf de sessies via USB veiligstellen (`Android/data/ch.etasystems.pirol/files/PIROL/`). Van v0.0.8 tot v0.0.10 gaat de update naar v0.0.11 zonder de-installeren; sessies en modellen blijven behouden. Let op bij v0.0.11: de confidentiedrempel is nu een gezamenlijke drempel voor alle soorten (hoofdstuk 2 en 13), controleer daarna de schuifregelaar. Van v0.0.11 naar v0.1.1 gaat de update zonder de-installeren. Controleer daarna de soortgroepen (hoofdstuk 11): oude filters staan op vogels Auto, de rest Uit. Sessies uit v0.0.6 en ouder worden niet meer specifiek ondersteund.

---

## 2. Live-detectie

**Start:** Druk op de opnameknop op het tabblad Live. **Stoppen:** houd de knop lang ingedrukt. Sessie en Raven-export worden automatisch opgeslagen.

Opname en analyse lopen in één dienst. Ze gaan door wanneer de app naar de achtergrond gaat of het scherm uitgaat.

Bij het stoppen verschijnt bovenaan de melding "Lang indrukken om te stoppen" (bovenaan, zodat je vinger op de knop hem niet afdekt).

Herkende soorten verschijnen als kaarten onder het sonogram:
- Soortnaam in de gekozen taal, wetenschappelijke naam als toevoeging
- Confidentie in procent (dunne balk), tijdstempel, GPS-positie
- Alternatieven (uitklapbaar, aantikbaar)
- Meldingen "Zeldzaam", "Seizoensatypisch", watchlist-markering
- **Afspelen:** de afspeelknop op de kaart speelt het fragment van de detectie af (3 s ervoor, 5 s erna), ook tijdens de opname. Een tweede tik stopt het afspelen.
- **Knipperen:** wordt een soort opnieuw gedetecteerd, dan knippert haar kaart kort blauw. De lijst springt dan niet (zie "Rustige lijst").
- **Alarmbel:** in het menu van de volledige soortkaart zet je de soort permanent op de alarmlijst of voor 24 u. 24-uursalarmen tonen een bel met het label "24h"; na afloop verdwijnt het automatisch.
- **Compact-knop:** schakelt alle soortkaarten om tussen compact en uitgebreid. Op de telefoon zit hij in de statusregel, op de tablet in de kop. De alternatieven klap je via de kop van de kaart open en dicht.

**Compacte weergave op de telefoon:** In staand en liggend formaat gebruikt het tabblad Live de ruimte zuinig: geen titelbalk, de navigatie bestaat alleen uit pictogrammen, de knoppenrij van de kaarten is één regel. Op smalle kaarten staan Delen, Referentie, Vergelijken en "Alternatieven" in het menu "Meer acties" (pictogram met drie puntjes); op de tablet blijven alle knoppen zichtbaar. In liggend formaat staan links het sonogram en de knoppen, rechts de soortkaarten. Het paneel "Vergelijkbare soorten" is op de telefoon ingeklapt en kan worden uitgeklapt.

**Microfoon stil tijdens afspelen:** Speelt PIROL tijdens de opname een detectie via de luidspreker af, dan wordt de microfoon stilgezet, zodat het afspelen niet opnieuw wordt gedetecteerd of opgenomen. In het tabblad Live verschijnt de melding "Microfoon stil tijdens afspelen". Met aangesloten koptelefoon (kabel, USB, Bluetooth) blijft de microfoon actief.

| Knop | Toestand |
|-------|---------|
| groen, pulserend | pre-roll-buffer loopt (opname nog niet gestart) |
| rood, pulserend | opname loopt |

**Statusregel:** looptijd in mm:ss. **GPS-balk:** huidige nauwkeurigheid in ±m, rood bij meer dan 30 s zonder geldige fix.

**Opmerking:** Per opname kan een titel worden ingevoerd. Hij verschijnt in de sessielijst, in de ZIP-naam en in de Raven-export.

**Handmatig toevoegen:** Een soort met de hand invoeren (met aantal, atlascode, opmerking). Per detectie kunnen daarnaast aantal, atlascode, opmerking en een spraakmemo worden vastgelegd. Een soort kan worden vergrendeld (slechts één melding per sessie).

**Samenvoegen:** Detecties van dezelfde soort worden ruimtelijk en in de tijd tot één item samengevoegd (straal en tijdvenster instelbaar, Instellingen > Model & detectie).

### Rustige lijst
De volgorde van de soortkaarten verandert hoogstens elke 5 s. Nieuwe soorten worden verzameld en samen bovenaan ingevoegd (de allereerste kaart verschijnt meteen); ze kunnen dus tot 5 s later zichtbaar worden, alarmen komen nog steeds meteen. Wordt een soort in de bovenste 5 kaarten opnieuw gedetecteerd, dan blijft ze staan en knippert blauw. Een soort lager in de lijst schuift bij de volgende wissel omhoog en knippert. Verwijderen, verifiëren en vergrendelde soorten werken meteen. Detecties, alarm, samenvoegen en analyse worden hierdoor niet beïnvloed.

### Sessiememo
De memoknop in het tabblad Live (alleen zichtbaar tijdens een opname) werkt als een walkie-talkie: **ingedrukt houden en spreken**, bij loslaten eindigt het memo. Tijdens de opname is de knop rood en toont hij de looptijd; daarna verschijnt "Memo opgeslagen (n s)". Memo's korter dan 0,5 s worden weggegooid ("Te kort"); na 3 minuten eindigt een memo automatisch. Het memo wordt opgeslagen met zijn positie in de opname (`memos.jsonl`, bestanden onder `memos/`), verschijnt in de analyse in het blok "Memo's" (hoofdstuk 6) en als regel "Memo" in de Raven-export (hoofdstuk 7). Memo's worden nooit als stem verborgen (hoofdstuk 7). Spraakmemo's bij afzonderlijke soorten blijven hiervan gescheiden.

Op het moment van indrukken onthoudt PIROL de laatste GPS-fix (niet ouder dan 2 min) en slaat die op bij het memo; op de kaart verschijnt het memo dan als microfoonmarkering (hoofdstuk 8). Zonder actuele fix heeft het memo geen locatie. Wissen, delen en opslaan als: hoofdstuk 6.

### Meervoudige herkenning en confidentiedrempel
Binnen hetzelfde venster van 3 s worden alle soorten herkend die de drempel bereiken, niet alleen de bovenste. De schuifregelaar heet "Confidentiedrempel (alle soorten)" (Instellingen > Model & detectie, standaard 50 %) en geldt voor alle soorten gelijk. Soorten daaronder, maar boven de kandidatengrens, blijven als kandidaten bij de detecties van het venster. Vroeger stuurde de regelaar een andere drempel (TopK, standaard 0,3); controleer daarom na de update. Watchlist-alarmen worden pas vanaf de drempel geactiveerd.

### Tweede model (expertmodus)
Een tweede model (bijv. Perch v2 of BirdNET 2.4, hoofdstuk 13) berekent dezelfde vensters nog eens. De kaart toont dan naast de confidentie van het hoofdmodel het getal van het tweede model: in procent, bij Perch als rang ("Perch #2"). Soorten die het tweede model niet kent, zijn als niet-controleerbaar gemarkeerd. Het tweede model beïnvloedt noch alarm, noch filter, noch samenvoegen. De drempel "Alternatieven vanaf" (5/10/20/30 %, Instellingen > Model & detectie) bepaalt vanaf welke confidentie alternatieven worden vermeld.

### Pre-roll
Indien ingeschakeld, wordt audio **vóór** de opnamestart mee opgeslagen (lengte instelbaar) en ook geanalyseerd. De pre-roll staat naadloos aan het begin van `recording.wav`. Het sonogram toont daarvan hoogstens 5 s.

### Overlap
Analysevensters kunnen elkaar overlappen: 0 % (standaard, een venster elke 3 s), 50 % (elke 1,5 s) of 67 % (elke seconde). Meer overlap herkent korte roepjes op vensterranden beter, maar kost meer rekentijd en accu. Is het toestel te traag, dan verlaagt PIROL de overlap automatisch en meldt dit eenmaal per sessie in een snackbar. Bij modellen met een langer venster (Perch, 5 s) beperkt de regelaar "Max. stapgrootte" (3, 4 of 5 s) de afstand tussen twee vensters.

### Lange opnames (segmenten)
Na de ingestelde maximale duur (standaard 60 min, instelbaar 10-80 min) begint automatisch een nieuw bestand (`recording-002.wav` enz.). De opname loopt zonder onderbreking door. Tijdsaanduidingen in `detections.jsonl` en Raven gelden vanaf de sessiestart. De WAV-kop wordt elke 5 s bijgewerkt: wordt de app hard beëindigd, dan gaat hoogstens ca. 3 s audio verloren.

### Hoogdoorlaatfilter en bewerking
Het hoogdoorlaatfilter (200 Hz) filtert wind en voetstappen, standaard aan. Het werkt alleen op de kopie voor de detectie; de opgeslagen opname blijft onveranderd. Wijzigingen gelden vanaf de volgende opname.

---

## 3. Weergave: sonogram, spectrum, oscillogram

In het tabblad Live en Analyse. De frequentie-as toont 1/2/4/8/12 kHz. Standaard wordt tot **12 kHz** weergegeven (voorinstelling voor vogels), instelbaar tot **24 kHz**. De niveaus van het sonogram zijn in dBFS.

**Weergavemodi (tabblad Live):** In het weergavepaneel (lang indrukken) schakel je tussen **sonogram**, **spectrum** en **oscillogram**. Het spectrum toont de luidheid per frequentie met een echte FFT (dBFS, middeling, piekvasthouding met vervagende pieken, cursor). De frequentie-as van het spectrum is in te stellen op **Lineair** (standaard, gelijke Hz-afstanden) of **Mel** (lage frequenties gespreid, zoals in het sonogram); raster en cursorweergave volgen die keuze. De regelaar **Intensiteit (gamma)** werkt ook op het spectrum (hoogte van de curven en dB-raster) en is dezelfde waarde als in het sonogram. Het oscillogram toont het tijdsverloop met een niveauweergave (piek/RMS), bandpass en een rode markering bij overstuurd; het **tijdvenster** (5 tot 30 s) stel je in het oscillogram-paneel in. Gamma hoort niet bij het oscillogram. Zolang er nog geen gegevens zijn, toont elke weergave een eigen melding ("Spectrum wordt geladen…", "Oscillogram wordt geladen…"). Frequentie, dynamiek en kleurpatroon gelden gezamenlijk voor alle drie de weergaven. In de analyse zijn spectrum en oscillogram eveneens beschikbaar.

**Weergave aanpassen:** Lang indrukken op het sonogram (Live of Analyse) opent het instellingenpaneel onder het sonogram; dezelfde regelaars staan onder Instellingen > Weergave. Het paneel geldt gezamenlijk voor Live, Analyse en Instellingen. Bovenaan het paneel staat "Gereed" op een eigen regel; voorinstellingen en kleurpaletten zijn tegels die bij gebrek aan ruimte doorlopen naar de volgende regel.

| Regelaar | Werking |
|--------|---------|
| Voorinstelling | Standaard / Zachte roepjes / Luide omgeving |
| Auto-contrast | past de dB-drempel doorlopend aan het signaal aan; anders handmatig (bereik in dB) |
| Intensiteit (gamma) | kleiner = zachte delen lichter |
| Helderheidsplafond | begrenst sterke signalen |
| Kleurpalet | Magma, Viridis, grijstinten, **Papier (licht)** |
| Frequentie | weergegeven bereik in kHz |
| Tijdvenster | zichtbare seconden (5 tot 30 s) |

**Papier (licht):** lichte palet zoals een klassiek papieren sonogram, goed bij daglicht. Het palet kies je in het paneel (lang indrukken op het sonogram); op de telefoon is er in het tabblad Live geen aparte knop meer daarvoor.

**Tabwissel:** Verlaat je het tabblad Live of schakel je het scherm uit, dan berekent PIROL het sonogram bij je terugkeer opnieuw (tot het ingestelde tijdvenster), zodat het naadloos doorloopt. Ben je langer weg geweest dan het tijdvenster, dan begint het opnieuw.

**Modus** (Instellingen > Weergave): vogels, vleermuis of breedband. De vleermuismodus neemt op 96 kHz op (ultrasoon); daarbij is er geen pre-roll.

---

## 4. Verificatie

Per detectie staan deze acties ter beschikking (tabblad Live, tabblad Analyse en Kaart):

| Actie | Status | Weergave |
|--------|--------|-------------|
| Bevestigen | Bevestigd | groene rand |
| Onzeker (?) | Onzeker | oranje rand |
| Afwijzen | Afgewezen | grijs weergegeven |
| Corrigeren | Gecorrigeerd | naam vervangen door je eigen invoer |
| Alternatief kiezen | Bevestigd | De soort van de detectie wordt door het alternatief vervangen (zelfde regel, tijd en positie); de oude soort blijft als kandidaat. Ongedaan maken in de snackbar |

In het **tabblad Live** verschijnt bij het verifiëren geen snackbar meer. Na "Bevestigen" toont de kaart in plaats daarvan een groene knop Ongedaan maken; die zet de detectie terug op "Onzeker". De knoppen Bevestigen, Onzeker, Afwijzen en Bewerken blijven zichtbaar, de status is dus altijd om te schakelen. Bij het kiezen van een **alternatief**, het verschuiven op de kaart en het bevestigen via het watchlist-alarm verschijnt nog steeds een snackbar met **Ongedaan maken** (minstens 5 s). Alle acties worden vastgelegd in `verifications.jsonl`; ook afgewezen en vervangen detecties blijven in de export (controlespoor).

**Detectie delen:** fragment (3 s ervoor, 5 s erna, maximale lengte instelbaar) als WAV, M4A (AAC), FLAC (indien het toestel dat kan) of sonogram-PNG met opschrift.

---

## 5. Sessies

Elke opname maakt een sessiemap aan, standaard `Downloads/PIROL/JJJJ-MM-DD/{sessionId}/`. De mapnaam draagt de lokale tijd.

```
session.json              metadata (duur, locatie, GPS-statistieken, gebruikt model)
detections.jsonl          alle detecties
verifications.jsonl       alle verificatieacties
detections.updates.jsonl  aanvullend bestand tijdens de opname (wordt bij het stoppen in detections.jsonl samengevoegd)
recording.wav             opname (16-bit, mono, 48 kHz); bij lange sessies
recording-002.wav ...     verdere segmenten
recording.selections.txt  Raven Selection Table (automatisch bij het stoppen)
memos.jsonl               sessie-memo's met hun positie in de opname (bestanden onder memos/)
human_windows.jsonl       lijst om menselijke stemmen te verbergen (nooit geëxporteerd)
```

- **Opslagruimte:** ca. 5,5 MB per minuut opname.
- **Tijdstempels:** altijd lokale tijd met tijdzoneverschil (bijv. `+02:00`).
- **Automatisch wissen:** korte sessies zonder detectie worden gewist (minimale duur instelbaar, 0 = uit).
- **Wissen** van een sessie in de analyse: met bevestiging.
- **Gegevens van geïmporteerde sessies bewerken:** Bij geïmporteerde sessies staat in het overloopmenu van de kop (pictogram met drie puntjes) het item "Gegevens bewerken": begintijd, locatie en opmerking kunnen achteraf worden gewijzigd. Een nieuwe begintijd verschuift alle tijden van de sessie (detecties, verificaties, varianten, route) met hetzelfde verschil; posities in de opname blijven gelijk. Als de **datum** verandert, hernoemt PIROL ook de sessiemap (nieuwe kenmerk met de nieuwe lokale tijd) en verplaatst die naar de bijbehorende dagmap (als dagelijkse submappen aan staan). Begintijd en datum kies je met de datum- en tijdkiezer. Raven en GPX worden opnieuw geschreven. De locatie geldt voor de sessie en voor alle detecties zonder eigen GPS-fix; op de kaart verplaatste punten blijven behouden. Niet mogelijk tijdens een rescan; ook een datumwijziging is niet mogelijk zolang een upload van deze sessie in de wachtrij staat of bezig is.

### Opslaglocatie wijzigen
Instellingen > Opname > Opslaglocatie: "Andere map kiezen" (SAF: intern geheugen, SD-kaart, cloudmap). Optioneel met dagelijkse submappen. Is de map niet bereikbaar, dan slaat PIROL op in de reservemap van de app en meldt dit. Afzonderlijke opnames kun je ook in het tabblad Analyse verplaatsen (hoofdstuk 6).

**Sessies verplaatsen:** Bij het wijzigen vraagt PIROL of bestaande sessies naar de nieuwe locatie moeten worden **gekopieerd** ("Kopiëren") of dat alleen de locatie wordt gewijzigd ("Alleen wijzigen").

---

## 6. Tabblad Analyse

- **Sessielijst:** op datum, met duur, grootte en opmerking.
- **Sessie openen:** sonogram en detectielijst met tijdsaanduiding. De detectie die nu wordt afgespeeld, is gemarkeerd; een wijzer toont de positie.
- **Afspelen:** per detectie (fragment met zoom) of de hele opname; segmenten worden naadloos aan elkaar gerijgd. Lange opnames worden gestreamd.
- **Meerdere voorkomens:** Is een soort tijdens de opname meerdere keren herkend en tot één kaart samengevoegd, dan toont de kaart de regel "N voorkomens", die je kunt uitklappen. Opeenvolgende vensters zijn samengevoegd tot **episodes** ("N voorkomens in M episodes"). Elke episode staat met begin- en eindtijd, positie in de opname ("@ m:ss") en duur ("N vensters, M s") op één regel; haar afspeelknop speelt de hele episode in één stuk af (ook bij lang aanhoudend gezang). Bij afzonderlijke vensters is alleen het tijdstip bekend, niet de confidentie.
- **Afspelen:** Er speelt altijd maar één item. Een tweede tik op dezelfde knop stopt, een tik op een ander item wisselt, en een tabwissel beëindigt het afspelen. Dit geldt in de hele app (Live, Analyse, Kaart, Referenties, spraakmemo's).
- **Verifiëren, corrigeren, soort toevoegen:** zoals in het tabblad Live, ook achteraf.
- **Vergelijking:** detectie en referentie naast elkaar, met synchroon afspelen.
- **Zoeken:** Het zoeksymbool in de sessielijst filtert op soortnaam (deel van een woord, Nederlandse of wetenschappelijke naam). In een geopende opname filtert het zoeken de detectielijst.
- **Kop en afspeelbalk:** Terug, datum en kenmerk, Afspelen met positieaanduiding, Zoeken, Raven en Uploaden. De upload meldt het resultaat met een melding ("Geüpload", bij een fout met de reden, of dat PIROL op een beslissing wacht, hoofdstuk 7). Met vorige/volgende detectie spring je tussen de items.
- **Navigatie:** Tikken in het sonogram of oscillogram springt naar die plek; loopt er geen afspelen, dan onthoudt PIROL de startpositie (cyaankleurige wijzer) voor het volgende Afspelen.
- **Spectrum en oscillogram:** zoals in het tabblad Live (inclusief Lineair/Mel en gamma, hoofdstuk 3); in het oscillogram toont een wijzer de afspeelpositie.
- **Zoomen met twee vingers:** in het overzicht en het sonogram zoom je met knijpen (twee vingers). Het fragment is minstens 3 s (bij kortere sessies de hele sessie) en hoogstens de hele opname; uitzoomen tot de hele opname heft de zoom op. Tikken en slepen met één vinger werkt zoals voorheen.
- **+ Soort:** neemt de tijd over van de afspeelpositie (+/-10 s). Bij sessies uit meerdere bestanden met gaten tussen de segmenten rekent PIROL met de wandklok van het betreffende segment; het venster blijft binnen het segment. De locatie kan via "Op kaart kiezen" worden bepaald.
- **Overzicht:** Een overzichtsbalk toont de detecties over de hele opname; de Raven-tabel toont ze als tabel.
- **Raven-bestand opnieuw maken**, bijv. na latere verificatie.
- **Episodes delen:** elke episode heeft een eigen deelknop; gedeeld wordt het fragment van deze episode.
- **Memo's:** Zijn er memo's, dan toont de analyse onder sonogram en overzicht het uitklapbare blok "Memo's (n)" (hoogstens 200 dp hoog, scrollbaar) met afspelen. Het toont sessie-memo's en memo's bij soorten; bij soortmemo's zonder bekende opnametijd staat "ca. ... (tijd van de detectie)". Het memomenu biedt **Delen** en **Opslaan als** (het WAV-bestand ongewijzigd, naam `JJJJMMDDUUMMSS_Memo_Opmerking.wav`, zonder verbergen van stemmen) en bij sessiememo's **Verwijderen**: het memo verdwijnt meteen, een melding biedt **Ongedaan maken** (10 s); pas daarna wordt het bestand definitief gewist. Tijdens de lopende opname is verwijderen geblokkeerd ("Verwijderen (opname bezig)").
- **Rescan:** De rescan-knop naast het vergrootglas (alleen als er een opname is) opent de keuze van model, lijst of soortgroepen, confidentiedrempel, overlap en tweede model. De rescan loopt op de achtergrond als voorgronddienst met voortgang en annuleren; het resultaat verschijnt als chip naast het origineel ("datum tijd · model"). De resultaten verschijnen **doorlopend**: de variant opent meteen, de detectielijst groeit elke paar seconden en kan al tijdens de scan worden geverifieerd (kop "· bezig"). Bij annuleren blijft het tussentijdse resultaat bewaard ("afgebroken bij x min"); hetzelfde geldt als de app tijdens de scan wordt afgesloten. Het rescan is sneller geworden: het gebruikt meer rekenkernen en het tweede model draait parallel aan het hoofdmodel; de resultaten zijn dezelfde als voorheen. Reeds uitgevoerde verificaties worden nooit overschreven. Tijdens een rescan is geen opname mogelijk en is de lopende variant niet wisbaar. Na het importeren van audiobestanden volgt de scan dezelfde weg (zie "Audio importeren").
- **Audio importeren:** De importknop in de kop van de sessielijst neemt een PIROL-sessie (ZIP of map) of audiobestanden (WAV, FLAC, MP3, M4A, OGG) over als nieuwe sessie. De begintijd is vooringevuld (bijv. uit een AudioMoth-opmerking) en is aanpasbaar. Bij meerdere bestanden kies je: per bestand een sessie of alles in één sessie (segmenten, op tijd gesorteerd).
  - **Begintijd:** je kiest datum en tijd met de datum- en tijdkiezer. De bestandstijd is bij recorders vaak het einde van de opname; de schakelaar **"Bestandstijd = einde van de opname"** (standaard aan) rekent dan begin = bestandstijd min de looptijd; uit betekent dat de bestandstijd als begin geldt. Onder het veld staat waar de tijd vandaan komt.
  - **Scan na import:** daarna biedt PIROL de scan aan (Rescan-dialoog). Bij meerdere bestanden die elk als eigen sessie worden geïmporteerd, geldt de keuze voor **alle** nieuwe sessies, die na elkaar worden gescand. Is het origineel van een sessie nog leeg (zonder detecties), dan wordt een volledige scan het **origineel**: `detections.jsonl`, `verifications.jsonl` en het Raven-bestand staan dan direct in de sessiemap, zonder handmatige stap. Dit geldt ook als er al oudere scans zijn; bij het openen van zo'n sessie neemt PIROL de nieuwste volledige scan over. Latere scans blijven varianten, afgebroken scans worden nooit overgenomen.
  - **Afbreken:** breek je de import af (of mislukt die), dan wordt de halfgeïmporteerde sessie verwijderd; reeds voltooide sessies van een meervoudige import blijven bewaard. Anders dan bij de rescan, die zijn tussentijdse resultaat bewaart. Breek je de scan-dialoog af, dan vervallen de wachtende scans.
- **Opslaan als:** In het overloopmenu van de kop. Je kiest **ZIP** of **Map** en daarna het doel (ook SD-kaart, USB of cloudmap). De inhoud komt overeen met de upload-ZIP; bij een map ontstaat een submap met de exportnaam (hoofdstuk 7). Een doel in de PIROL-opslag of in de sessie zelf wordt geweigerd. Voor de lopende opname is het item grijs. Menselijke stemmen worden afhankelijk van de instelling verborgen (hoofdstuk 7).
- **Kop:** Datum · gemeente · opmerking in één regel; een tik op de opmerking opent het dialoogvenster. De compact/groot-schakelaar komt overeen met die van de soortkaart.

**Meerdere opnames selecteren:** Lang indrukken op een opname in de sessielijst start de selectiemodus; verdere opnames kies je met een tik. De kopbalk toont "N geselecteerd" met de acties "Alle", "Verplaatsen" en "Verwijderen"; de X of de terugtoets beëindigt de modus. De opname die nu loopt, kan niet worden geselecteerd.
- **Verplaatsen:** De doelmap is vrij te kiezen (mapkeuze, ook SD-kaart of USB-geheugen). Elke sessie wordt met alle bestanden gekopieerd (opnamesegmenten, memo's), de bestandsgroottes worden gecontroleerd, daarna wordt het origineel verwijderd. Bij een fout of afbreken blijft het origineel behouden en wordt de gedeeltelijke kopie verwijderd. Bestaat op het doel al een sessie met dezelfde naam, dan wordt `-2`, `-3` enz. toegevoegd. De voortgang is zichtbaar en kan worden afgebroken. Een doelmap binnen `Downloads/PIROL` (of de opname zelf) wordt geweigerd; dan wordt er niets verplaatst.
- **Verwijderen:** Na een vraag ("N opnames verwijderen?"); dit kan niet ongedaan worden gemaakt. Het afzonderlijk verwijderen van opnames via het prullenbakpictogram blijft zoals voorheen.

---

## 7. Export

**Raven Selection Table** (`recording.selections.txt`): wordt bij het stoppen automatisch naast de WAV geschreven, tabgescheiden, UTF-8. Opent in Cornell Raven, Audacity (als labelspoor) en Sonic Visualiser.

Opgenomen zijn o.a. Selection, Begin File (bij segmenten de betreffende WAV), Begin/End Time (s), Low/High Freq (Hz), Begin Date Time (met tijdzoneverschil), Species, Confidence, Status (bij tweede model Second Rank). Welke verdere kolommen worden geschreven, staat onder Instellingen > Export & upload > Raven-exportvelden. Er is geen KML-export meer.

### Raven: één regel per episode
Opeenvolgende herkenningen van een soort vormen een **episode**; de tabel heeft per episode één regel (niet meer per samengevoegd item). Een nieuwe regel begint wanneer de leemte groter is dan één analysevenster. Confidence is het maximum van de episode, Begin File het segment van haar begin. Extra kolommen: `Cluster` (kenmerk van het eerste item) en `Windows` (aantal vensters). De Raven-tabel in de app toont dezelfde regels.

### Raven: memo's
Elk sessie-memo (hoofdstuk 2) verschijnt als eigen regel met Species `Memo` op zijn positie in de opname. De kolom `Memo` bevat het pad van het memobestand (ook bij soortmemo's); ze is uit te schakelen onder Raven-exportvelden. Memoregels staan ook in de tabellen van rescanvarianten.

**ZIP-pakket:** de sessie als ZIP, voor handmatig delen of de automatische upload (hoofdstuk 12).

### Menselijke stemmen verbergen (privacy)
PIROL vervangt in **exportkopieën** (Opslaan als ZIP of map, upload-ZIP, delen van fragmenten) menselijke stemmen door ruis: waar het model mensen herkent, wordt de spraakband (ca. 300-3900 Hz) vervangen, met een rand van 0,5 s; de rest blijft ongewijzigd. De originele opname, detecties en Raven worden nooit veranderd. De lijst `human_windows.jsonl` ontstaat live en bij de rescan; nog niet beoordeelde delen (geïmporteerde of oude sessies) scant PIROL bij het exporteren na (met voortgang, afbreekbaar).
- **Instelling:** Instellingen > Export & upload > Privacy > "Menselijke stemmen in export verbergen" (standaard aan). Bij het uitschakelen waarschuwt PIROL ("Stemmen niet meer verbergen?"), omdat exports dan gesprekken van niet-betrokken personen kunnen bevatten. In de expertmodus stelt "Mens-drempel" in vanaf welke score wordt verborgen (lager = voorzichtiger).
- **Memo's** blijven altijd onbewerkt; het tijdvak van een live-memo in de opname wordt van het verbergen uitgesloten.
- **Model zonder mensklasse:** Kent het model van een sessie geen menselijke stemmen, dan vraagt PIROL bij opslaan, uploaden en delen: met BirdNET 3.0 naar mensen zoeken (niet tijdens een opname, kan minuten duren), exporteren zonder verbergen (met waarschuwing) of annuleren. De automatische upload uploadt in dit geval niets onverborgen, maar wacht op de beslissing.

**Bestandsnamen:** Gedeelde detecties heten `JJJJMMDDUUMMSS_Soort_Opmerking.ext` (bijv. `20260420143005_Merel_Tuin_achter.wav`), ZIP-pakketten `JJJJMMDDUUMMSS_Opmerking.zip` (tijd = sessiestart). Ontbreekt de opmerking, dan vervalt dat deel; spaties en speciale tekens worden `_`.

---

## 8. Kaart

Georeferentieerde detecties op een kaart. Het **kaartmenu** opent zich door van de linkerrand te vegen of via het menupictogram linksboven ("Kaartmenu openen"). Het bevat weergave, kaartbron, offline kaarten en het soortenfilter.

- **Kaartbron:** Automatisch (swisstopo in Zwitserland, OpenStreetMap daarbuiten), OpenStreetMap, swisstopo-kaart, swisstopo-luchtfoto.
- **Offline:** Voor swisstopo-kaarten kan het zichtbare gebied worden gedownload ("Gebied downloaden", met het aantal tegels en de grootte). Een afbreking behoudt reeds geladen tegels; een nieuwe start laadt alleen wat ontbreekt. "Offline kaarten verwijderen" verwijdert alle opgeslagen tegels.
- **Weergave:** alle sessies, "Alleen live" of "Laatste 3". Afgewezen waarnemingen zijn verborgen. Overlappende markers worden afhankelijk van de zoom gegroepeerd. De kaart start op je eigen locatie.
- **Punt aantikken:** Een tik op een punt opent een menu met Verifiëren (Bevestigen, Onzeker, Afwijzen), "Bewerken", "Positie verplaatsen" en Afspelen (Afspelen bzw. "Afspelen stoppen"). Een lang indrukken opent hetzelfde menu en staat bovendien toe waarnemingen als gelijktijdige waarneming te koppelen. Afgespeeld wordt het fragment van de detectie uit de sessieopname; ontbreekt die, dan verschijnt een melding.
- **Memomarkering:** sessiememo's met positie (hoofdstuk 2) verschijnen als microfoonmarkering. Een tik opent een dialoog met tijd en afspelen. Ze zijn zichtbaar bij "Alle sessies" en "Laatste 3", niet bij "Alleen live".
- **Meerdere punten op elkaar (cluster):** Een tik op een cluster toont de lijst van detecties. Per item is er Verifiëren, Afspelen en "Positie verplaatsen".
- **Positie verplaatsen:** De kaart toont bovenaan de melding "Kaart verschuiven: kruisdraad op de nieuwe positie van ..., dan Overnemen." Verschuif de kaart zo dat de kruisdraad in het midden op de nieuwe positie ligt, en tik daarna onderaan op "Overnemen" (of "Annuleren"). Daarna verschijnt een snackbar met **Ongedaan maken**. Verplaatsen kan ook bij live-punten; loopt er geen sessie, dan geldt de nieuwe positie alleen tot het sluiten van de app.
- **Soortenfilter:** In het kaartmenu staat het soortenfilter: "Alleen alarmsoorten" of afzonderlijke soorten verbergen (Alle/Geen). Het filter geldt tot de app wordt gesloten; routes blijven zichtbaar.
- **Route:** Een tijdens de opname vastgelegde route verschijnt als lijn (bij modus "Alleen bij detectie" gestippeld, alleen op detectiepunten). Zie hoofdstuk 14.
- **Locatieknop:** Centreert de kaart op je eigen positie en zoomt zo in dat de zichtbare kaarthoogte ca. 200 m is.
- **Zoomknoppen:** De +/- knoppen zijn 50 % groter en liggen boven de locatieknop.

---

## 9. Referenties en vergelijkbare soorten

Geverifieerde detecties kunnen als referentie worden opgeslagen ("Als referentie opslaan") en weer worden verwijderd. Het tabblad Referenties toont de opgeslagen opnames; ook een audio-import is mogelijk. Nieuwe detecties worden via embedding met de referenties vergeleken.

Instellingen > Vergelijkbare soorten & referenties: gelijkenisonderzoek aan/uit, aantal getoonde regels (0 = uit, 0,5 = tekstband van de laatste 5, 1-10 = lijst), maximum aantal opgeslagen vergelijkingsopnames.

---

## 10. Watchlist en alarm

**Watchlist:** Doelsoorten die een alarm activeren. Beheer onder Instellingen > Watchlist & alarm: soort zoeken en toevoegen, verwijderen, of laden uit `watchlist.json`. Hoofd- en kleine letters en spaties spelen bij de vergelijking geen rol.

**Gedeelde map:** Kies onder "PIROL-map delen" eenmalig `Downloads/PIROL`. PIROL leest dan `watchlist.json` en de map `lists/` automatisch (details in hoofdstuk 11). "Voorbeeldlijst aanmaken" maakt een sjabloon.

**Alarmuitvoer** (afzonderlijk aan-/uitschakelbaar): trilling, melding, schermvullende aanduiding. Op het **vergrendelscherm** verschijnt het schermvullende alarm alleen als "Schermvullende meldingen" voor PIROL in de Android-instellingen zijn toegestaan (knop "Toestemming openen"). Anders blijft het een melding. Met "Alarm testen" (na 5 s, toestel vergrendelen) is dit te controleren.

In het alarm kan de soort direct worden **bevestigd**; "Ongedaan maken" blijft daarna beschikbaar.

**Alarmpauze:** minimale tijd tussen alarmen van dezelfde soort (0 = elke treffer alarmeert).

---

## 11. Soortgroepen en soortenlijsten

Het soortenfilter werkt per soortgroep: **Vogels, sprinkhanen, amfibieën, zoogdieren** elk **Uit / Alle / Auto / Lijst**; **Overig** alleen **Uit / Alle** (standaard Uit). "Auto" gebruikt het geomodel (locatie en BirdNET-week). Soorten die het geomodel niet kent, en groepen zonder geomodelmasker, nemen de begeleidende lijst van de groep (vogels: CH-lijst, sprinkhanen en amfibieën: CH-lijst hieronder, zoogdieren: alle). "Lijst" kiest een van de meegeleverde of eigen lijsten. Meegeleverd is de lijst "CH sprinkhanen & amfibieën" (gegevens GBIF, CC BY 4.0).

**Auto zonder GPS:** ontbreekt een actuele locatie, dan geldt de laatst opgeslagen positie maximaal 30 dagen; daarna gebruikt "Auto" de vervanglijst van de groep. Bij de start van de opname verschijnt een melding, en in Instellingen > Soorten & alarm (geomodel) staat de basis ("Zonder GPS geldt de laatste positie van [datum] (maximaal 30 dagen), anders de vervanglijst"). De opgeslagen positie wordt alleen bijgewerkt bij een berekening met GPS-fix. Optioneel negeert de **jaarlijst** het seizoen (schakelaar in het geomodel-onderdeel).

In de lijsteditor kun je soorten kiezen op orde, familie en geslacht; "Wetenschappelijke soortnamen tonen" schakelt de naamvorm om.

### Eigen soortenlijsten

Eigen soortenlijsten kunnen als JSON-bestanden in de map `lists/` van de gedeelde PIROL-map worden opgeslagen (`Downloads/PIROL/lists/*.json`). Ze verschijnen als kiesbare lijsten bij de soortgroepen (optie "Lijst"). De lijsten worden bij **elke opnamestart opnieuw ingelezen**; wijzigingen gelden dus vanaf de volgende opname. Foutieve lijsten worden overgeslagen en met reden gemeld. Als sjabloon dient "Voorbeeldlijst aanmaken" (`lists/beispiel_liste.json`).

Oude en nieuwe wetenschappelijke namen (bijv. na taxonomiewijzigingen) worden samengevoegd, zodat lijsten over modellen heen werken.

---

## 12. Upload

Instellingen > Upload: opgenomen sessies kunnen na de opname automatisch worden geüpload.

| Doel | Gegevens |
|------|---------|
| Uit | alleen lokale kopie, handmatige export |
| Map | elke map (toestel, Drive, OneDrive ...) via mapkeuze |
| NAS (SMB / Windows-share) | host, share, pad, gebruiker, wachtwoord |

Het NAS-wachtwoord wordt versleuteld op het toestel opgeslagen. "Verbinding testen" controleert de gegevens. Opties: alleen via wifi uploaden, de lokale ZIP-kopie na de upload niet bewaren. De status van de laatste upload staat eronder.

---

## 13. Modellen

Instellingen > Model & detectie > Model:

| Model | Eigenschappen |
|--------|---------------|
| BirdNET 3.0 FP16 (aanbevolen) | standaard, 11'725 klassen, 32 kHz, venster van 3 s |
| BirdNET 3.0 FP16 compact (pruned) | kleiner (ca. 70 MB), detectie vrijwel gelijk |
| BirdNET 3.0 Preview 3.1 FP16 / FP32 | oudere voorversie, 11'560 klassen |

Het actieve model wordt per keuze omgeschakeld, vanaf de volgende analyse. Het soortenaantal naast het model klopt na het wisselen meteen. Modellen kunnen worden gedownload (onderbreken en voortzetten mogelijk), geïmporteerd en verwijderd. Elke sessie onthoudt het gebruikte model.

**Modeltabel (vanaf v0.1.1):** De kolom Primair (keuzerondje) bepaalt het hoofdmodel. Secundair (vinkje, alleen in de expertmodus) kiest het tweede model dat elke detectie meecontroleert; uitschakelbaar, nooit gelijk aan het primaire. De prullenbak verwijdert een geïnstalleerd model na bevestiging; het primaire kan niet worden verwijderd en is tijdens een opname vergrendeld. Niet geladen modellen tonen een laadpictogram met voortgang en annuleren in de rij.

**Confidentiedrempel:** De regelaar "Confidentiedrempel (alle soorten)" geldt voor alle soorten gelijk (standaard 50 %). Watchlist-alarmen worden pas vanaf deze drempel geactiveerd.

**Minimale confidentie:** De detectiedrempel is globaal instelbaar. Een model kan daarnaast een eigen minimale confidentie meebrengen (bijv. 80 % voor Perch). Geldt de hogere waarde; het model toont die.

### Eigen en andere modellen importeren
PIROL is niet op BirdNET vastgelegd. Via "Ander model importeren..." wordt een pakket van drie bestanden samen gekozen: `.onnx` (model), `.pirol.json` (profiel) en label-CSV. De app controleert checksums, in- en uitgangen en het aantal labels en meldt fouten in duidelijke taal. Samplefrequentie en venstertlengte zijn vrij.

Getest zijn **BirdNET 2.4** (6'522 soorten, 48 kHz, ongeveer de helft van de rekentijd van 3.0), **Perch v2** (Google, 14'795 klassen, venster van 5 s) en **Nighthawk 0.3.1**. De bestanden maken geen deel uit van de app. Voor de omzetting van TensorFlow-, Keras- en TFLite-modellen (ook eigen trainingen) ligt het script `tools/convert_model.py` bij, zie `tools/README_convert_model.md` en `tools/MODEL_PROFILE_FORMAT.md`.

**Licenties:** BirdNET 3.0 Apache 2.0, Perch v2 Apache 2.0, BirdNET 2.4 en Nighthawk alleen niet-commercieel (CC BY-NC resp. BY-NC-SA). De licentie wordt bij het importeren getoond.

**Let op:** Perch werkt met 32 kHz; sabelsprinkhanen die in het ultrasone bereik roepen, zijn daarmee niet hoorbaar.

**Statistiek van het tweede model (expertmodus):** in Instellingen > Model & detectie opent de knop "Statistiek tweede model" een dialoog. PIROL leest daarvoor alle sessies (alleen de originelen, alleen-lezen; rescan-varianten en handmatige items tellen niet mee) en toont een kruistabel: verificatie (bevestigd, afgewezen, onzeker, open, gecorrigeerd/vervangen) tegen het resultaat van het tweede model (Gecontroleerd, Niet controleerbaar, Spreekt tegen, Geen resultaat), totaal en per tweede model. Daarbij komen voortgangsbalken naar het doel (200 bevestigde en 50 afgewezen detecties met resultaat van het tweede model) en het aandeel tegenstrijdigheden bij bevestigde en bij afgewezen detecties. Zo zie je of het tweede model als verificatiehulp bruikbaar is. Sluiten van de dialoog breekt het inlezen af.

**Perch als tweede model:** heeft ca. 650 MB extra opslagruimte nodig. De app waarschuwt bij het inschakelen als het toestel minder dan 6 GB RAM heeft. Het tweede model werkt met het ruwe audiosignaal van de opname, zodat de 5-s-vensters de echte omgeving bevatten.

---

## 14. GPS

Instellingen > Kaart & GPS (tot v0.0.10: Opname & opslag):

| Instelling | Betekenis |
|-------------|-----------|
| GPS-modus "Doorlopend" | voortdurend fixes in het ingestelde interval (2/5/10/20/60 s, standaard 10 s) |
| GPS-modus "Alleen bij detectie" | GPS alleen bij een detectie, minstens 30 s tussen twee fixes; het interval geldt dan niet. Bespaart ongeveer een derde van de accu |
| Max. GPS-onnauwkeurigheid | minder nauwkeurige fixes worden weggegooid (standaard 50 m) |
| GPS-afvlakking | mediaan over 5 fixes, vlakt uitschieters af |

Elke detectie krijgt een verse locatie, ook bij uitgeschakeld scherm. Wijzigingen werken vanaf de volgende opname.

**Start-fix:** Bij het starten van het programma en bij het zoomen van de kaart haalt PIROL zonder lopende opname eenmalig een locatie op (geen doorlopende tracking).

**Route:** De route ("Route opnemen", standaard aan) wordt tijdens de opname meegeschreven (`track.jsonl`) en bij het stoppen als `track.gpx` naast de opname geplaatst (tijden in UTC).

**Gemeente:** De knop "Gemeente invoegen" zet de gemeente uit de positie in de opmerking (in het detailvenster, in de sessieopmerking en in de analyse). De gemeentegegevens (Zwitserland) liggen offline in de app. Daarbuiten wordt niets bepaald, tenzij de expertschakelaar Instellingen > Kaart & GPS > Geavanceerd > "Gemeente online bepalen (Google)" aan staat (standaard uit; dan worden de coördinaten naar Google gestuurd).

---

## 15. Energie en accu

Instellingen > Model & detectie > Energieprofiel:

| Profiel | Analyse |
|--------|--------|
| Nauwkeurig | elk venster van 3 s |
| Gebalanceerd | elke 6 s |
| Zuinig | elke 15 s, voor lange veldagen |
| Ultra-zuinig | elke 30 s, maximale looptijd |

**Praktijkwaarden** (tablet Galaxy Tab A11+, 6'840 mAh, doorlopende opname, wifi aan, BirdNET 3.0 FP16; richtwaarden, ca. ±15 %):

| Instelling | Verbruik | Looptijd bij volle accu |
|-------------|-----------|----------------------|
| Nauwkeurig, scherm uit, GPS doorlopend (10 s) | ca. 2,2 %/u | ca. 45 u |
| idem, 67 % overlap | ca. 3,5 %/u | ca. 28 u |
| idem, **scherm aan** | ca. 9 %/u | ca. 12 u |
| Nauwkeurig, GPS alleen bij detectie | ca. 1,5 %/u | ca. 60 u |
| Zuinig (15 s), GPS 60 s | ca. 0,9 %/u | ca. 4-5 dagen |

**Tips:**
1. **Scherm uitschakelen** is de grootste hefboom (meer dan twee keer zoveel als opname en analyse samen). De opname loopt in de dienst door.
2. Kies GPS-modus "Alleen bij detectie".
3. Laat de overlap op 0 %; bij lange inzetten het profiel "Zuinig" kiezen.
4. De modelkeuze speelt nauwelijks een rol (pruned ongeveer zoals final, Preview 3.1 ca. 10 % meer, FP32 niet meer dan FP16).

---

## 16. Talen

Instellingen > Taal: taal van de app (systeemtaal of keuze): Duits, Engels, Frans, Italiaans, Catalaans, Portugees, Nederlands, Zwitserduits. **Zwitserduits en Portugees zijn concepten**; ontbrekende teksten verschijnen in het Duits. De taal van de **soortnamen** volgt de app-taal, maar kan apart worden ingesteld (23 talen). Soortnamen verschijnen overal in de gekozen taal, ook op de kaart, in Raven en bij oudere detecties (ze worden bij het tonen opnieuw bepaald; opgeslagen bestanden blijven ongewijzigd); hernoemde soorten vind je ook onder hun oude naam. Ontbreekt een naam in de gekozen taal, dan neemt PIROL bij Duits de Duitse naam uit de meegeleverde soortenlijsten, bij andere talen eerst de Engelse naam.

---

## 17. Instellingen in het kort

De instellingen zijn in inklapbare onderdelen gegroepeerd; de stand wordt onthouden. Standaard staat alleen "Taal" open.

| Onderdeel | Inhoud |
|---------|--------|
| Taal | app-taal, taal van de soortnamen |
| Opname | audio-info, pre-roll, overlap, energieprofiel, opslaglocatie (map, dagelijkse submap, automatisch wissen, sessies verplaatsen), max. opnamelengte en fragmentlengte, resampler |
| Model & detectie | modellen (downloaden, importeren, verwijderen), detectiedrempel (alle soorten), alternatieven vanaf, max. soorten per venster, voorinstellingen, soortsuggesties, samenvoegen van detecties, vergelijkbare soorten en embedding-limiet |
| Soorten & alarm | regionaal filter en eigen lijsten, watchlist, alarmpauze en -uitvoer, alarm testen, PIROL-map, seizoensgebondenheid en zeldzaamheid |
| Weergave | taal van de soortnamen, volledig scherm, sonogram (modus, voorinstelling, contrast, gamma, plafond, palet, frequentie, tijdvenster), compacte soortkaart |
| Kaart & GPS | GPS-modus, route opnemen, GPS-interval, nauwkeurigheid en afvlakking; expert (Geavanceerd): "Gemeente online bepalen (Google)" |
| Export & upload | Raven-exportvelden (incl. memo), privacy (menselijke stemmen verbergen, mens-drempel), upload (doel map/NAS, wachtwoord, verbindingstest, alleen wifi) |
| Vergelijkbare soorten & referenties | gelijkenisonderzoek, aantal regels, vergelijkingsopnames (hoofdstuk 9) |
| Info | versie, bronnen, Wat is nieuw, diagnose (benchmark), "Steun PIROL" |

De schakelaar **Expertinstellingen** (onder "Installatie starten") verbergt in de eenvoudige modus alles wat zeldzaam is; in de expertmodus is alles zichtbaar. Er gaat daarbij geen instelling verloren. Zeldzame opties staan in het inklapbare subblok "Geavanceerd" van het betreffende onderdeel (bijv. hoogdoorlaatfilter, detectieclusters, fenologie, max. opnamelengte).

**Steun PIROL:** in Instellingen > Info opent de knop "Steun PIROL" de donatiepagina in de browser. De donatie is vrijblijvend; PIROL blijft gratis en volledig.

**Resampler:** Polyfase (aanbevolen) filtert storingsgeluiden boven 16 kHz weg, die anders als spookssignalen kunnen verschijnen. "Lineair" is de oude methode. De omschakeling toont een melding en werkt zonder de opname opnieuw te starten.

**Diagnose:** De benchmark meet de rekentijd van het actieve model met 1/2/4 threads (1-3 minuten, niet tijdens een opname). Met "ONNX-bestand kiezen en meten" kun je ook een willekeurig model vóór de import testen.

---

## 18. Technische aanwijzingen

- **Opname:** 48 kHz, 16-bit PCM, mono (96 kHz in de vleermuismodus). Detectie omgerekend naar 32 kHz (BirdNET 3.0).
- **Minimaal toestel:** Android 8.0. Compatibel met 16-KB-geheugenpagina's (Android 15+).
- **Fairphone en andere toestellen:** De samplefrequentie wordt met het toestel onderhandeld; bij problemen schakelt de app over naar een andere audiodriver.
- **Gegevensformaten:** `session.json`, `detections.jsonl`, `verifications.jsonl`, Raven-TSV; tijdstempels in lokale tijd met tijdzoneverschil.

---

## 19. Wijzigingen

De volledige lijst staat in `CHANGELOG.md` in het project.

### Versie 0.1.3
- Sessiememo's: verwijderen met Ongedaan maken, positie bij indrukken opgeslagen en als markering op de kaart, delen en opslaan als (hoofdstuk 2, 6, 8)
- Import: datum- en tijdkiezer, schakelaar "Bestandstijd = einde van de opname", scan voor alle geïmporteerde sessies, een scan wordt origineel als het origineel leeg is, met Raven in de map (hoofdstuk 6)
- Datumwijziging van een geïmporteerde sessie hernoemt de sessiemap en verplaatst die naar de juiste dagmap (hoofdstuk 5)
- "+ Soort" houdt rekening met gaten tussen segmenten (hoofdstuk 6)
- Spectrum: frequentie-as Lineair (standaard) of Mel, gamma ook in het spectrum, tijdvenster in het oscillogram (hoofdstuk 3)
- Analyse: zoomen met twee vingers, de upload meldt het resultaat met een melding (hoofdstuk 6)
- Rescan sneller, tweede model parallel (hoofdstuk 6)
- Auto zonder GPS: laatste positie tot 30 dagen, melding bij de start, basis in de instellingen, jaarlijst (hoofdstuk 11)
- Statistiek tweede model in expertmodus (hoofdstuk 13)
- Soortnamen altijd in de gekozen taal, Duitse namen uit de lijsten als terugval (hoofdstuk 16)
- Knop "Steun PIROL" (vrijblijvend) in Instellingen > Info (hoofdstuk 17)
- Hulp: de hulpmodus dekt meer bedieningselementen, ook kaartmarkeringen en dialogen (hoofdstuk 1)
- Nieuw hoofdstuk 20: installatie en update

### Versie 0.1.2
- Gegevens van geïmporteerde sessies (begintijd, locatie, opmerking) achteraf bewerken (hoofdstuk 5)
- "Opslaan als" als ZIP of map (hoofdstuk 6)
- Raven: één regel per episode, nieuwe kolommen `Cluster`, `Windows` en `Memo` (hoofdstuk 6, 7)
- Rescan en import: resultaten doorlopend zichtbaar en verifieerbaar; bij de rescan behoudt afbreken het tussentijdse resultaat, bij de import verwijdert het de halfgeïmporteerde sessie (hoofdstuk 6)
- Menselijke stemmen worden in de export verborgen, standaard aan; vraag bij model zonder mensklasse (hoofdstuk 7)
- Gemeente online via Google alleen nog als optie, standaard uit (hoofdstuk 17)
- Sessiememo door ingedrukt houden, blok "Memo's" in de analyse, memoregel in Raven (hoofdstuk 2, 6, 7)
- Rustigere live-soortkaarten: wissel hoogstens elke 5 s, treffers in de top 5 knipperen blauw (hoofdstuk 2)
- Hulpknop "?": hulpmodus en handleiding offline (hoofdstuk 1)
- Handleiding gecorrigeerd: soortenfilter in het kaartmenu (hoofdstuk 8), namen van de instellingenonderdelen (hoofdstuk 1, 5, 7, 17)

---

## 20. Installatie en update

**Welke APK?** Elke versie staat als release op GitHub (Nikiphedia/Pirol, onderdeel "Releases") met per processorarchitectuur een APK:
- `Pirol-<versie>-arm64-v8a-release.apk`: voor bijna alle actuele telefoons en tablets. Twijfel je, neem dit bestand.
- `Pirol-<versie>-armeabi-v7a-release.apk`: alleen voor oudere 32-bit-toestellen.

**Installatie:**
1. Download de passende APK uit de GitHub-release op het toestel en open hem. Android vraagt de eerste keer of de gebruikte browser of bestandsbeheerder apps mag installeren; sta dat eenmalig toe.
2. **Samsung-toestellen:** de functie **"Automatische blokkering"** (Auto Blocker; Instellingen > Beveiliging en privacy) verhindert het installeren van apps buiten de store. Schakel die voor de installatie kort uit, installeer PIROL en schakel hem daarna weer in. Dit geldt **bij elke update** opnieuw.
3. Tik op Installeren en open de app. Bij de eerste start leidt de installatieassistent je door de basisinstellingen (hoofdstuk 1).

**Update:** installeer de nieuwe APK gewoon over de bestaande installatie (dezelfde architectuur; bij Samsung weer met kort uitgeschakelde automatische blokkering). Sessies, modellen, lijsten en instellingen blijven behouden; er hoeft niets te worden verwijderd. Aanwijzingen over oudere versies staan in hoofdstuk 1 (update van oudere versies). Na de update toont PIROL eenmalig "Wat is nieuw".

---

*PIROL v0.1.3 — ETA Systems GmbH, 5004 Aarau — stand 2026-11-25*
