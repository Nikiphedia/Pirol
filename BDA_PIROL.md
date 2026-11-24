# PIROL — Bedienungsanleitung

Akustische Echtzeit-Artenerkennung fuer Android, gedacht fuer die Feldarbeit.

*Gilt ab Version v0.1.2 (in Arbeit, Stand 2026-11-24). Was sich zwischen den Versionen geaendert hat, steht in `CHANGELOG.md`.*

Die App hat fuenf Tabs: **Live**, **Analyse**, **Referenzen**, **Karte** und **Settings**. Der Knopf **?** (Hilfe) ist kein Tab: Ein Tipp schaltet den Hilfemodus ein, ein Doppeltipp oeffnet diese Anleitung (Kapitel 1, Abschnitt "Hilfe-Knopf").

---

## 1. Erster Start

1. App oeffnen. Der Einrichtungsassistent fuehrt durch Sprache, Niveau (Einsteiger/Experte), Einsatzzweck, Artenliste, Modell (inkl. Geomodel), Berechtigungen (Mikrofon, GPS, Benachrichtigungen) und Zusammenfassung. Die Werte werden erst bei "Fertig" uebernommen.
2. **Artenliste** waehlen (Kapitel 11): Die Artgruppen (Voegel, Heuschrecken, Amphibien, Saeugetiere, Andere) filtern die Erkennung auf plausible Arten. Spaeter aenderbar unter Settings > Arten & Alarm.
3. **Modell** herunterladen: Es lassen sich mehrere Modelle auf einmal auswaehlen. Standard ist BirdNET 3.0 FP16 (empfohlen, ca. 170 MB). Der Download laeuft im Hintergrund weiter, kann abgebrochen und spaeter fortgesetzt werden. Alternativ eine Modell-Datei manuell auswaehlen.
4. **Speicherort** pruefen (Settings > Aufnahme). Standard: `Downloads/PIROL/`, per USB sichtbar.

**Installation (APK):** Die Release-APK gibt es pro Prozessor-Architektur. Fuer aktuelle Geraete (Telefone und Tablets) die Datei `Pirol-<Version>-arm64-v8a-release.apk` installieren, nur fuer alte 32-Bit-Geraete die Datei `...-armeabi-v7a-release.apk`. Die APKs sind dadurch deutlich kleiner als frueher die gemeinsame APK. Die App hat ein neues Symbol (gelber Vogel), auch als einfarbiges Symbol fuer Themed Icons.

**Was ist neu:** Nach einem Update zeigt PIROL einmalig einen Dialog "Was ist neu" mit den wichtigsten Aenderungen der Version. Er kann jederzeit in den Einstellungen unter "Info" erneut aufgerufen werden.

### Hilfe-Knopf
Der Knopf **?** (Tablet und schmale Navigation: unter Einstellungen; Telefon: ganz rechts in der Leiste) bietet zwei Wege:
- **Antippen** schaltet den **Hilfemodus** ein oder aus. Oben erscheint die Leiste "Hilfemodus: Knopf beruehren fuer Erklaerung". Ein Tipp auf einen Knopf oder Regler loest dann nichts aus, sondern zeigt eine kurze Erklaerung, bei Bedarf mit "Mehr in der Anleitung" (springt zum passenden Kapitel). Beenden: X in der Leiste, Zurueck oder nochmals **?**.
- **Doppeltippen** oeffnet diese Anleitung offline, in der App-Sprache (fehlt die Uebersetzung, auf Deutsch). Ein Inhaltsverzeichnis fuehrt zu den Abschnitten.

**Update von aelteren Versionen:** Ab v0.0.8 ist PIROL signiert. Wer v0.0.7 oder aelter installiert hat, muss einmal deinstallieren und vorher die Sessions per USB sichern (`Android/data/ch.etasystems.pirol/files/PIROL/`). Von v0.0.8 bis v0.0.10 auf v0.0.11 geht das Update ohne Deinstallation, Sessions und Modelle bleiben erhalten. Achtung bei v0.0.11: Die Konfidenz-Schwelle ist neu eine gemeinsame Schwelle fuer alle Arten (Kapitel 2 und 13), den Regler danach pruefen. Von v0.0.11 auf v0.1.1 geht das Update ohne Deinstallation. Danach die Artgruppen (Kapitel 11) pruefen: alte Filter stehen auf Voegel Auto, Rest Aus. Sessions aus v0.0.6 und aelter werden nicht mehr speziell unterstuetzt.

---

## 2. Live-Erkennung

**Start:** Aufnahme-Knopf im Live-Tab druecken. **Stopp:** Knopf lang druecken. Session und Raven-Export werden automatisch gespeichert.

Aufnahme und Analyse laufen in einem Dienst. Sie gehen weiter, wenn die App in den Hintergrund geht oder der Bildschirm ausgeschaltet wird.

Beim Stoppen erscheint oben der Hinweis "Lang druecken zum Stoppen" (oben, damit der Finger am Knopf ihn nicht verdeckt).

Erkannte Arten erscheinen als Karten unter dem Sonogramm:
- Artname in der gewaehlten Sprache, wissenschaftlicher Name als Zusatz
- Konfidenz in Prozent (duenner Balken), Zeitstempel, GPS-Position
- Alternativen (aufklappbar, antippbar)
- Hinweise "Selten", "Saison-untypisch", Watchlist-Markierung
- **Abspielen:** Der Play-Knopf auf der Karte spielt den Ausschnitt der Detektion (3 s davor, 5 s danach) ab, auch waehrend der Aufnahme. Ein zweiter Tipp stoppt die Wiedergabe.
- **Blinken:** Wird eine Art erneut erkannt, blinkt ihre Karte kurz blau auf. Die Liste springt dabei nicht (siehe "Ruhige Liste").
- **Alarm-Glocke:** Im Menue der vollen Artkarte legt man die Art dauerhaft in die Alarmliste oder fuer 24 h an. 24-h-Alarme zeigen eine Glocke mit dem Abzeichen "24h"; nach Ablauf verschwindet es automatisch.
- **Kompakt-Knopf:** Schaltet alle Artkarten zwischen kompakt und ausfuehrlich um. Auf dem Telefon sitzt er in der Statuszeile, auf dem Tablet in der Kopfzeile. Die Alternativen lassen sich ueber den Kopf der Karte auf- und zuklappen.

**Kompakte Ansicht auf dem Telefon:** Im Hoch- und im Querformat nutzt der Live-Tab den Platz sparsam: keine Titelzeile, die Navigation besteht nur aus Icons, die Knopfzeile der Karten ist einzeilig. Auf schmalen Karten stehen Teilen, Referenz, Vergleichen und "Alternativen" im Menue "Weitere Aktionen" (Symbol mit drei Punkten); auf dem Tablet bleiben alle Knoepfe sichtbar. Im Querformat liegen links Sonogramm und Knoepfe, rechts die Artkarten. Das Panel "Aehnliche Arten" ist auf dem Telefon eingeklappt und laesst sich aufklappen.

**Mikrofon stumm bei Wiedergabe:** Spielt PIROL waehrend der Aufnahme eine Detektion ueber den Lautsprecher ab, wird das Mikrofon stumm geschaltet, damit die Wiedergabe nicht erneut erkannt oder aufgenommen wird. Im Live-Tab erscheint der Hinweis "Mikrofon stumm waehrend Wiedergabe". Mit angeschlossenen Kopfhoerern (Kabel, USB, Bluetooth) bleibt das Mikrofon aktiv.

| Knopf | Zustand |
|-------|---------|
| gruen, pulsierend | Preroll-Puffer laeuft (Aufnahme noch nicht gestartet) |
| rot, pulsierend | Aufnahme laeuft |

**Status-Zeile:** Laufzeit in mm:ss. **GPS-Leiste:** aktuelle Genauigkeit in ±m, rot bei laenger als 30 s ohne gueltigen Fix.

**Bemerkung:** Pro Aufnahme kann ein Titel eingegeben werden. Er erscheint in der Session-Liste, im ZIP-Namen und im Raven-Export.

**Manuell hinzufuegen:** Eine Art von Hand eintragen (mit Stueckzahl, Atlascode, Bemerkung). Pro Detektion lassen sich ausserdem Stueckzahl, Atlascode, Bemerkung und ein Sprach-Memo erfassen. Eine Art kann gesperrt werden (nur eine Meldung pro Session).

**Zusammenfassen:** Detektionen derselben Art werden raeumlich und zeitlich zu einem Eintrag zusammengefasst (Radius und Zeitfenster einstellbar, Settings > Modell & Erkennung).

### Ruhige Liste
Die Reihenfolge der Artkarten aendert sich hoechstens alle 5 s. Neue Arten werden gesammelt und gemeinsam oben eingefuegt (die allererste Karte erscheint sofort); sie koennen also bis zu 5 s spaeter sichtbar werden, Alarme kommen weiterhin sofort. Wird eine Art in den obersten 5 Karten erneut erkannt, bleibt sie stehen und blinkt blau. Eine Art weiter unten rueckt beim naechsten Wechsel nach oben und blinkt. Loeschen, Verifizieren und gesperrte Arten wirken sofort. Detektionen, Alarm, Zusammenfassung und Analyse sind davon nicht betroffen.

### Session-Memo
Der Memo-Knopf im Live-Tab (nur waehrend einer Aufnahme sichtbar) funktioniert wie ein Walkie-Talkie: **gedrueckt halten und sprechen**, beim Loslassen endet das Memo. Waehrend der Aufnahme ist der Knopf rot und zeigt die Laufzeit; danach erscheint "Memo gespeichert (n s)". Memos unter 0.5 s werden verworfen ("Zu kurz"), nach 3 Minuten endet ein Memo automatisch. Das Memo wird mit seiner Lage in der Aufnahme gespeichert (`memos.jsonl`, Dateien unter `memos/`), erscheint in der Analyse im Block "Memos" (Kapitel 6) und als Zeile "Memo" im Raven-Export (Kapitel 7). Memos werden nie als Stimme ausgeblendet (Kapitel 7). Sprach-Memos zu einzelnen Arten bleiben davon getrennt.

### Mehrfacherkennung und Konfidenz-Schwelle
Im selben 3-s-Fenster werden alle Arten erkannt, die die Schwelle erreichen, nicht nur die oberste. Der Regler heisst "Konfidenz-Schwelle (alle Arten)" (Settings > Modell & Erkennung, Standard 50 %) und gilt fuer alle Arten gleich. Arten darunter, aber ueber der Kandidaten-Grenze, bleiben als Kandidaten bei den Detektionen des Fensters. Frueher steuerte der Regler eine andere Schwelle (TopK, Standard 0.3); deshalb nach dem Update pruefen. Watchlist-Alarme loesen erst ab der Schwelle aus.

### Zweitmodell (Experten-Modus)
Ein Zweitmodell (z.B. Perch v2 oder BirdNET 2.4, Kapitel 13) rechnet dieselben Fenster noch einmal. Die Karte zeigt dann neben der Konfidenz des Hauptmodells die Zahl des Zweitmodells: in Prozent, bei Perch als Rang ("Perch #2"). Arten, die das Zweitmodell nicht kennt, sind als nicht pruefbar markiert. Das Zweitmodell beeinflusst weder Alarm noch Filter oder Zusammenfassung. Die Schwelle "Alternativen ab" (5/10/20/30 %, Settings > Modell & Erkennung) bestimmt, ab welcher Konfidenz Alternativen gelistet werden.

### Preroll
Wenn aktiviert, wird Audio **vor** dem Aufnahmestart mitgespeichert (Laenge einstellbar) und auch analysiert. Der Preroll steht lueckenlos am Anfang von `recording.wav`. Das Sonogramm zeigt davon hoechstens 5 s.

### Ueberlappung
Analysefenster koennen sich ueberlappen: 0 % (Standard, Fenster alle 3 s), 50 % (alle 1.5 s) oder 67 % (jede Sekunde). Mehr Ueberlappung erkennt kurze Rufe an Fenstergrenzen besser, braucht aber mehr Rechenzeit und Akku. Ist das Geraet zu langsam, reduziert PIROL die Ueberlappung automatisch und meldet das einmal pro Session in einer Snackbar. Bei Modellen mit laengerem Fenster (Perch, 5 s) begrenzt der Regler "Max. Schrittweite" (3, 4 oder 5 s) den Abstand zwischen zwei Fenstern.

### Lange Aufnahmen (Segmente)
Nach der eingestellten Maximaldauer (Standard 60 min, einstellbar 10-80 min) beginnt automatisch eine neue Datei (`recording-002.wav` usw.). Die Aufnahme laeuft ohne Unterbruch weiter. Zeitangaben in `detections.jsonl` und Raven gelten ab Session-Start. Der WAV-Kopf wird alle 5 s nachgefuehrt: Wird die App hart beendet, gehen hoechstens ca. 3 s Audio verloren.

### Hochpassfilter und Aufbereitung
Der Hochpassfilter (200 Hz) filtert Wind und Trittschall, standardmaessig an. Er wirkt nur auf die Kopie fuer die Erkennung, die gespeicherte Aufnahme bleibt unveraendert. Aenderungen gelten ab der naechsten Aufnahme.

---

## 3. Anzeige: Sonogramm, Spektrum, Oszillogramm

Im Live- und im Analyse-Tab. Die Frequenzachse zeigt 1/2/4/8/12 kHz. Dargestellt wird standardmaessig bis **12 kHz** (Voreinstellung fuer Voegel), einstellbar bis **24 kHz**. Die Pegel des Sonogramms sind in dBFS.

**Anzeige-Modi (Live-Tab):** Im Anzeige-Panel (Langdruck) schaltet man zwischen **Sonogramm**, **Spektrum** und **Oszillogramm** um. Das Spektrum zeigt die Lautstaerke je Frequenz mit echter FFT (dBFS, Mittelung, Peak-Halten mit verblassenden Spitzen, Cursor). Das Oszillogramm zeigt den Zeitverlauf mit Pegelanzeige (Peak/RMS), Bandpass und roter Markierung bei Uebersteuerung. Frequenz, Dynamik und Farbmuster gelten fuer alle drei Ansichten gemeinsam. In der Analyse stehen Spektrum und Oszillogramm ebenfalls zur Verfuegung.

**Darstellung anpassen:** Langdruck auf das Sonogramm (Live oder Analyse) oeffnet das Einstellungs-Panel unterhalb des Sonogramms; dieselben Regler stehen in Settings > Anzeige. Das Panel gilt fuer Live, Analyse und Settings gemeinsam. Oben im Panel steht "Fertig" in einer eigenen Zeile; Voreinstellungen und Farbpaletten sind Kacheln, die bei Platzmangel umbrechen.

| Regler | Wirkung |
|--------|---------|
| Voreinstellung | Standard / Leise Rufe / Laute Umgebung |
| Auto-Kontrast | passt die dB-Schwelle laufend dem Signal an; sonst von Hand (Bereich in dB) |
| Intensitaet (Gamma) | kleiner = leise Anteile heller |
| Helligkeits-Deckel | begrenzt starke Signale |
| Farbpalette | Magma, Viridis, Graustufen, **Papier (hell)** |
| Frequenz | angezeigter Bereich in kHz |
| Zeitfenster | Sekunden, die sichtbar sind (5 bis 30 s) |

**Papier (hell):** helle Palette wie ein klassisches Papier-Sonagramm, gut bei Tageslicht. Die Palette wird im Panel (Langdruck auf das Sonogramm) gewaehlt; auf dem Telefon gibt es dafuer keinen eigenen Knopf im Live-Tab mehr.

**Tab-Wechsel:** Verlaesst man den Live-Tab oder schaltet den Bildschirm aus, rechnet PIROL das Sonogramm bei der Rueckkehr nach (bis zum eingestellten Zeitfenster), sodass es lueckenlos weiterlaeuft. War man laenger weg als das Zeitfenster, beginnt es neu.

**Modus** (Settings > Anzeige): Voegel, Fledermaus oder Breitband. Der Fledermaus-Modus nimmt mit 96 kHz auf (Ultraschall); dabei gibt es keinen Preroll.

---

## 4. Verifikation

Pro Detektion stehen diese Aktionen zur Verfuegung (Live-Tab, Analyse-Tab und Karte):

| Aktion | Status | Darstellung |
|--------|--------|-------------|
| Bestaetigen | Bestaetigt | gruener Rand |
| Unsicher (?) | Unsicher | oranger Rand |
| Ablehnen | Abgelehnt | ausgegraut |
| Korrigieren | Korrigiert | Name durch eigene Eingabe ersetzt |
| Alternative waehlen | Bestaetigt | Die Art der Detektion wird durch die Alternative ersetzt (gleiche Zeile, Zeit und Position); die alte Art bleibt als Kandidat. Rueckgaengig in der Snackbar |

Im **Live-Tab** gibt es beim Verifizieren keine Snackbar mehr. Nach "Bestaetigen" zeigt die Karte stattdessen einen gruenen Rueckgaengig-Knopf; er setzt die Detektion auf "Unsicher" zurueck. Die Knoepfe Bestaetigen, Unsicher, Ablehnen und Bearbeiten bleiben sichtbar, der Status laesst sich also jederzeit umschalten. Beim Waehlen einer **Alternative**, beim Verschieben auf der Karte und beim Bestaetigen ueber den Watchlist-Alarm erscheint weiterhin eine Snackbar mit **Rueckgaengig** (mindestens 5 s). Alle Aktionen werden in `verifications.jsonl` festgehalten; auch abgelehnte und ersetzte Detektionen bleiben im Export (Audit-Trail).

**Detektion teilen:** Ausschnitt (3 s davor, 5 s danach, Maximallaenge einstellbar) als WAV, M4A (AAC), FLAC (falls das Geraet es kann) oder Sonogramm-PNG mit Beschriftung.

---

## 5. Sessions

Jede Aufnahme erzeugt einen Session-Ordner, standardmaessig `Downloads/PIROL/YYYY-MM-DD/{sessionId}/`. Der Ordnername traegt die Ortszeit.

```
session.json              Metadaten (Dauer, Ort, GPS-Statistik, verwendetes Modell)
detections.jsonl          alle Detektionen
verifications.jsonl       alle Verifikations-Aktionen
detections.updates.jsonl Zusatzdatei waehrend der Aufnahme (wird beim Stopp in detections.jsonl zusammengefuehrt)
recording.wav             Aufnahme (16-bit, Mono, 48 kHz); bei langen Sessions
recording-002.wav ...     weitere Segmente
recording.selections.txt  Raven Selection Table (automatisch beim Stopp)
memos.jsonl               Session-Memos mit Lage in der Aufnahme (Dateien unter memos/)
human_windows.jsonl       Merkliste fuer das Ausblenden menschlicher Stimmen (nie exportiert)
```

- **Speicherbedarf:** ca. 5.5 MB pro Minute Aufnahme.
- **Zeitstempel:** immer Ortszeit mit Zonen-Offset (z.B. `+02:00`).
- **Auto-Loeschen:** Kurze Sessions ohne Detektion werden geloescht (Mindestdauer einstellbar, 0 = aus).
- **Loeschen** einer Session in der Analyse: mit Bestaetigung.
- **Angaben importierter Sessions bearbeiten:** Bei importierten Sessions gibt es im Ueberlaufmenue der Kopfzeile (Symbol mit drei Punkten) den Eintrag "Angaben bearbeiten": Startzeit, Ort und Bemerkung lassen sich nachtraeglich aendern. Eine neue Startzeit verschiebt alle Zeiten der Session (Detektionen, Verifikationen, Varianten, Route) um dieselbe Differenz; Positionen in der Aufnahme bleiben. Raven und GPX werden neu geschrieben. Der Ort gilt fuer die Session und alle Detektionen ohne eigenen GPS-Fix; auf der Karte verschobene Punkte bleiben. Nicht moeglich waehrend eines Rescans.

### Speicherort wechseln
Settings > Aufnahme > Speicherort: "Anderen Ordner waehlen" (SAF: interner Speicher, SD-Karte, Cloud-Ordner). Optional mit Tages-Unterordnern. Ist der Ordner nicht erreichbar, speichert PIROL im Fallback-Ordner der App und meldet das. Einzelne Aufnahmen lassen sich auch im Analyse-Tab verschieben (Kapitel 6).

**Sessions verschieben:** Beim Wechsel fragt PIROL, ob bestehende Sessions an den neuen Ort **kopiert** werden sollen ("Kopieren") oder nur der Ort gewechselt wird ("Nur wechseln").

---

## 6. Analyse-Tab

- **Session-Liste:** nach Datum, mit Dauer, Groesse und Bemerkung.
- **Session oeffnen:** Sonogramm und Detektionsliste mit Zeitangabe. Die gerade abgespielte Detektion ist markiert, ein Zeiger zeigt die Position.
- **Abspielen:** pro Detektion (Ausschnitt mit Zoom) oder die ganze Aufnahme; Segmente werden nahtlos aneinandergereiht. Lange Aufnahmen werden gestreamt.
- **Mehrere Vorkommen:** Wurde eine Art im Lauf der Aufnahme mehrmals erkannt und zu einer Karte zusammengefasst, zeigt die Karte die Zeile "N Vorkommen", die sich aufklappen laesst. Aufeinanderfolgende Fenster sind zu **Episoden** zusammengefasst ("N Vorkommen in M Episoden"). Jede Episode steht mit Start- und Endzeit, Position in der Aufnahme ("@ m:ss") und Dauer ("N Fenster, M s") in einer Zeile; ihr Play-Knopf spielt die ganze Episode am Stueck ab (auch bei langem Dauersang). Zu einzelnen Fenstern ist nur der Zeitpunkt bekannt, nicht die Konfidenz.
- **Wiedergabe:** Es spielt immer nur ein Eintrag. Ein zweiter Tipp auf denselben Knopf stoppt, ein Tipp auf einen anderen Eintrag wechselt, und ein Tab-Wechsel beendet die Wiedergabe. Das gilt in der ganzen App (Live, Analyse, Karte, Referenzen, Sprach-Memos).
- **Verifizieren, korrigieren, Art hinzufuegen:** wie im Live-Tab, auch nachtraeglich.
- **Vergleich:** Detektion und Referenz nebeneinander, mit synchroner Wiedergabe.
- **Suche:** Das Such-Symbol in der Session-Liste filtert nach Artname (Teilwort, deutscher oder wissenschaftlicher Name). In einer geoeffneten Aufnahme filtert die Suche die Detektionsliste.
- **Kopfzeile und Wiedergabeleiste:** Zurueck, Datum und Kennung, Play mit Positionsanzeige, Suche, Raven und Upload. Mit vorige/naechste Detektion springt man zwischen den Eintraegen.
- **Navigation:** Tippen im Sonogramm oder Oszillogramm springt an die Stelle; laeuft keine Wiedergabe, merkt sich PIROL die Startposition (cyanfarbener Zeiger) fuer das naechste Play.
- **Spektrum und Oszillogramm:** wie im Live-Tab; im Oszillogramm zeigt ein Zeiger die Abspielposition.
- **+ Art:** uebernimmt die Zeit aus der Abspielposition (+/-10 s). Der Ort laesst sich ueber "Auf Karte waehlen" bestimmen.
- **Uebersicht:** Eine Uebersichtsleiste zeigt die Detektionen ueber die ganze Aufnahme; die Raven-Tabelle listet sie als Tabelle.
- **Raven-Datei neu erzeugen**, z.B. nach nachtraeglicher Verifikation.
- **Episoden teilen:** Jede Episode hat einen eigenen Teilen-Knopf; geteilt wird der Ausschnitt dieser Episode.
- **Memos:** Gibt es Memos, zeigt die Analyse unter Sonogramm und Uebersicht den aufklappbaren Block "Memos (n)" (hoechstens 200 dp hoch, scrollbar) mit Abspielen. Er listet Session-Memos und Memos zu Arten; bei Art-Memos ohne bekannte Aufnahmezeit steht "ca. ... (Zeit der Detektion)".
- **Rescan:** Der Rescan-Knopf neben der Lupe (nur wenn eine Aufnahme vorhanden ist) oeffnet die Wahl von Modell, Liste bzw. Artgruppen, Konfidenz-Schwelle, Overlap und Zweitmodell. Der Rescan laeuft im Hintergrund als Vordergrund-Dienst mit Fortschritt und Abbrechen; das Ergebnis erscheint als Chip neben dem Original ("Datum Zeit · Modell"). Die Ergebnisse erscheinen **laufend**: Die Variante oeffnet sich sofort, die Detektionsliste waechst alle paar Sekunden und laesst sich schon waehrend des Scans verifizieren (Kopfzeile "· laeuft"). Beim Abbrechen bleibt das Teilergebnis erhalten ("abgebrochen bei x min"); dasselbe gilt, wenn die App waehrend des Scans beendet wird. Bereits vorgenommene Verifikationen werden nie ueberschrieben. Waehrend eines Rescans ist keine Aufnahme moeglich und die laufende Variante nicht loeschbar. Der Import von Audiodateien nutzt denselben Weg.
- **Audio importieren:** Der Import-Knopf im Kopf der Session-Liste uebernimmt eine PIROL-Session (ZIP oder Ordner) oder Audiodateien (WAV, FLAC, MP3, M4A, OGG) als neue Session. Die Startzeit steht vorbelegt (z.B. aus einem AudioMoth-Kommentar) und ist aenderbar. Bei mehreren Dateien waehlt man: je Datei eine Session oder alle zu einer Session (Segmente, nach Zeit sortiert).
- **Speichern unter:** Im Ueberlaufmenue der Kopfzeile. Man waehlt **ZIP** oder **Ordner** und danach das Ziel (auch SD-Karte, USB oder Cloud-Ordner). Der Inhalt entspricht dem Upload-ZIP; beim Ordner entsteht ein Unterordner mit dem Exportnamen (Kapitel 7). Ein Ziel in der PIROL-Ablage oder in der Session selbst wird abgelehnt. Fuer die laufende Aufnahme ist der Eintrag ausgegraut. Menschliche Stimmen werden je nach Einstellung ausgeblendet (Kapitel 7).
- **Kopfzeile:** Datum · Gemeinde · Bemerkung in einer Zeile; ein Tipp auf die Bemerkung oeffnet den Dialog. Der Kompakt/Gross-Umschalter entspricht dem der Artkarte.

**Mehrere Aufnahmen auswaehlen:** Langdruck auf eine Aufnahme in der Session-Liste startet den Auswahlmodus; weitere Aufnahmen werden per Tipp gewaehlt. Die Kopfleiste zeigt "N ausgewaehlt" mit den Aktionen "Alle", "Verschieben" und "Loeschen"; das X oder die Zurueck-Taste beendet den Modus. Die gerade laufende Aufnahme ist nicht auswaehlbar.
- **Verschieben:** Der Zielordner ist frei waehlbar (Ordnerwahl, auch SD-Karte oder USB-Speicher). Jede Session wird mit allen Dateien (Aufnahme-Segmente, Memos) kopiert, die Dateigroessen werden geprueft, danach wird das Original geloescht. Bei einem Fehler oder Abbruch bleibt das Original erhalten und die Teilkopie wird entfernt. Existiert am Ziel schon eine Session mit gleichem Namen, wird `-2`, `-3` usw. angehaengt. Der Fortschritt ist sichtbar und abbrechbar. Ein Zielordner innerhalb von `Downloads/PIROL` (oder die Aufnahme selbst) wird abgelehnt; es wird dann nichts verschoben.
- **Loeschen:** Nach Rueckfrage ("N Aufnahmen loeschen?"); das kann nicht rueckgaengig gemacht werden. Das Loeschen einzelner Aufnahmen per Papierkorb-Symbol bleibt wie bisher.

---

## 7. Export

**Raven Selection Table** (`recording.selections.txt`): wird beim Stopp automatisch neben der WAV geschrieben, Tab-getrennt, UTF-8. Oeffnet in Cornell Raven, Audacity (als Label-Spur) und Sonic Visualiser.

Enthalten sind u.a. Selection, Begin File (bei Segmenten die jeweilige WAV), Begin/End Time (s), Low/High Freq (Hz), Begin Date Time (mit Offset), Species, Confidence, Status (bei Zweitmodell Second Rank). Welche weiteren Spalten geschrieben werden, steht unter Settings > Export & Upload > Raven-Export Felder. KML-Export gibt es nicht mehr.

### Raven: eine Zeile pro Episode
Aufeinanderfolgende Erkennungen einer Art bilden eine **Episode**; die Tabelle hat pro Episode eine Zeile (nicht mehr pro zusammengefasstem Eintrag). Eine neue Zeile beginnt, wenn die Luecke groesser als ein Analysefenster ist. Confidence ist das Maximum der Episode, Begin File das Segment ihres Beginns. Zusaetzliche Spalten: `Cluster` (Kennung des ersten Eintrags) und `Windows` (Anzahl Fenster). Die Raven-Tabelle in der App zeigt dieselben Zeilen.

### Raven: Memos
Jedes Session-Memo (Kapitel 2) erscheint als eigene Zeile mit Species `Memo` an seiner Position in der Aufnahme. Die Spalte `Memo` enthaelt den Pfad der Memo-Datei (auch bei Art-Memos); sie laesst sich unter Raven-Export Felder abschalten. Memo-Zeilen stehen auch in den Tabellen von Rescan-Varianten.

**ZIP-Paket:** die Session als ZIP, fuer manuelles Teilen oder den automatischen Upload (Kapitel 12).

### Menschliche Stimmen ausblenden (Datenschutz)
PIROL ersetzt in **Export-Kopien** (Speichern unter als ZIP oder Ordner, Upload-ZIP, Teilen von Ausschnitten) menschliche Stimmen durch Rauschen: Wo das Modell Menschen erkennt, wird das Sprachband (ca. 300-3900 Hz) ersetzt, mit 0.5 s Rand; der Rest bleibt unveraendert. Originalaufnahme, Detektionen und Raven werden nie veraendert. Die Merkliste `human_windows.jsonl` entsteht live und beim Rescan; noch nicht bewertete Bereiche (importierte oder alte Sessions) scannt PIROL beim Export nach (mit Fortschritt, abbrechbar).
- **Einstellung:** Settings > Export & Upload > Datenschutz > "Menschliche Stimmen im Export ausblenden" (Standard an). Beim Ausschalten warnt PIROL ("Stimmen nicht mehr ausblenden?"), weil Exporte dann Gespraeche unbeteiligter Personen enthalten koennen. Im Expertenmodus stellt "Mensch-Schwelle" ein, ab welchem Score ausgeblendet wird (tiefer = vorsichtiger).
- **Memos** bleiben immer unbearbeitet; der Zeitraum eines Live-Memos in der Aufnahme wird vom Ausblenden ausgenommen.
- **Modell ohne Mensch-Klasse:** Kennt das Modell einer Session keine menschlichen Stimmen, fragt PIROL beim Speichern, Hochladen und Teilen: mit BirdNET 3.0 nach Menschen suchen (nicht waehrend einer Aufnahme, kann Minuten dauern), ohne Ausblenden exportieren (mit Warnung) oder abbrechen. Der automatische Upload laedt in diesem Fall nichts unmaskiert hoch, sondern wartet auf den Entscheid.

**Dateinamen:** Geteilte Detektionen heissen `YYYYMMDDHHMMSS_Art_Bemerkung.ext` (z.B. `20260420143005_Amsel_Garten_hinten.wav`), ZIP-Pakete `YYYYMMDDHHMMSS_Bemerkung.zip` (Zeit = Session-Start). Fehlt die Bemerkung, entfaellt der Teil; Leer- und Sonderzeichen werden zu `_`.

---

## 8. Karte

Georeferenzierte Detektionen auf einer Karte. Das **Kartenmenue** oeffnet sich per Wisch vom linken Rand oder ueber das Menue-Symbol oben links ("Kartenmenue oeffnen"). Es enthaelt Anzeige, Kartenquelle, Offline-Karten und den Artfilter.

- **Kartenquelle:** Automatisch (swisstopo in der Schweiz, OpenStreetMap ausserhalb), OpenStreetMap, swisstopo Karte, swisstopo Luftbild.
- **Offline:** Fuer swisstopo-Karten laesst sich der sichtbare Ausschnitt herunterladen ("Ausschnitt herunterladen", mit Angabe der Kachelzahl und Groesse). Ein Abbruch behaelt bereits geladene Kacheln; ein erneuter Start laedt nur Fehlendes. "Offline-Karten loeschen" entfernt alle gespeicherten Kacheln.
- **Anzeige:** alle Sessions, "Nur Live" oder "Letzte 3". Abgelehnte Beobachtungen sind ausgeblendet. Ueberlappende Marker werden je nach Zoom gruppiert. Die Karte startet auf dem eigenen Standort.
- **Punkt antippen:** Ein Tipp auf einen Punkt oeffnet ein Menue mit Verifizieren (Bestaetigen, Unsicher, Ablehnen), "Bearbeiten", "Position verschieben" und Abspielen (Play bzw. "Wiedergabe stoppen"). Ein Langdruck oeffnet dasselbe Menue und erlaubt zusaetzlich, Beobachtungen als Simultanbeobachtung zu verknuepfen. Abgespielt wird der Ausschnitt der Detektion aus der Session-Aufnahme; fehlt sie, erscheint eine Meldung.
- **Mehrere Punkte uebereinander (Cluster):** Ein Tipp auf einen Cluster zeigt die Liste der Detektionen. Pro Eintrag gibt es Verifizieren, Abspielen und "Position verschieben".
- **Position verschieben:** Die Karte zeigt oben den Hinweis "Karte verschieben: Fadenkreuz auf die neue Position von ..., dann Uebernehmen." Die Karte so schieben, dass das Fadenkreuz in der Mitte auf der neuen Position liegt, dann unten "Uebernehmen" (oder "Abbrechen"). Danach erscheint eine Snackbar mit **Rueckgaengig**. Das Verschieben geht auch fuer Live-Punkte; laeuft keine Session, gilt die neue Position nur bis zum Schliessen der App.
- **Artfilter:** Im Kartenmenue steht der Artfilter: "Nur Alarmarten" oder einzelne Arten ausblenden (Alle/Keine). Der Filter gilt bis zum Schliessen der App; Routen bleiben sichtbar.
- **Route:** Eine waehrend der Aufnahme aufgezeichnete Route erscheint als Linie (bei Modus "Nur bei Detektion" gestrichelt, nur an Detektionspunkten). Siehe Kapitel 14.
- **Standort-Knopf:** Zentriert die Karte auf die eigene Position und zoomt so, dass die sichtbare Kartenhoehe etwa 200 m betraegt.
- **Zoom-Knoepfe:** Die +/- Knoepfe sind 50 % groesser und liegen ueber dem Standort-Knopf.

---

## 9. Referenzen und aehnliche Arten

Verifizierte Detektionen lassen sich als Referenz speichern ("Als Referenz speichern") und wieder entfernen. Der Referenzen-Tab zeigt die gespeicherten Aufnahmen, auch ein Audio-Import ist moeglich. Neue Detektionen werden per Embedding mit den Referenzen verglichen.

Settings > Aehnliche Arten & Referenzen: Aehnlichkeitssuche an/aus, Anzahl angezeigter Zeilen (0 = aus, 0.5 = Laufschrift der letzten 5, 1-10 = Liste), Obergrenze gespeicherter Vergleichsaufnahmen.

---

## 10. Watchlist und Alarm

**Watchlist:** Zielarten, die einen Alarm ausloesen. Verwaltung unter Settings > Watchlist & Alarm: Art suchen und hinzufuegen, entfernen, oder aus `watchlist.json` laden. Gross-/Kleinschreibung und Leerzeichen spielen beim Abgleich keine Rolle.

**Freigegebener Ordner:** Unter "PIROL-Ordner freigeben" einmalig `Downloads/PIROL` waehlen. PIROL liest dann `watchlist.json` und die Ordner `lists/` automatisch (Details Kapitel 11). "Beispielliste anlegen" erzeugt eine Vorlage.

**Alarm-Ausgabe** (einzeln ein-/ausschaltbar): Vibration, Benachrichtigung, Vollbild-Hinweis. Auf dem **Sperrbildschirm** erscheint der Vollbild-Alarm nur, wenn "Vollbild-Benachrichtigungen" fuer PIROL in den Android-Einstellungen erlaubt sind (Knopf "Berechtigung oeffnen"). Sonst bleibt es eine Benachrichtigung. Mit "Alarm testen" (nach 5 s, Geraet sperren) laesst sich das pruefen.

Im Alarm kann die Art direkt **bestaetigt** werden; "Rueckgaengig" bleibt danach verfuegbar.

**Alarm-Pause:** Mindestabstand zwischen Alarmen derselben Art (0 = jeder Treffer alarmiert).

---

## 11. Artgruppen und Artenlisten

Der Artfilter wirkt je Artgruppe: **Voegel, Heuschrecken, Amphibien, Saeugetiere** je **Aus / Alle / Auto / Liste**; **Andere** nur **Aus / Alle** (Standard Aus). "Auto" nutzt das Geomodel (Standort und BirdNET-Woche). Arten, die das Geomodel nicht kennt, sowie Gruppen ohne Geomodel-Maske nehmen die Begleitliste der Gruppe (Voegel: CH-Liste, Heuschrecken und Amphibien: CH-Liste unten, Saeugetiere: alle). "Liste" waehlt eine der mitgelieferten oder eigenen Listen. Mitgeliefert ist die Liste "CH Heuschrecken & Amphibien" (Daten GBIF, CC BY 4.0).

Im Listen-Editor lassen sich Arten nach Ordnung, Familie und Gattung auswaehlen; "Artnamen wissenschaftlich anzeigen" schaltet die Namensform um.

### Eigene Artenlisten

Eigene Artenlisten koennen als JSON-Dateien im Ordner `lists/` des freigegebenen PIROL-Ordners abgelegt werden (`Downloads/PIROL/lists/*.json`). Sie erscheinen als waehlbare Listen bei den Artgruppen (Option "Liste"). Die Listen werden bei **jedem Aufnahmestart neu eingelesen**; Aenderungen gelten also ab der naechsten Aufnahme. Fehlerhafte Listen werden uebersprungen und mit Grund gemeldet. Als Vorlage dient "Beispielliste anlegen" (`lists/beispiel_liste.json`).

Alte und neue wissenschaftliche Namen (z.B. nach Taxonomie-Aenderungen) werden zusammengefuehrt, sodass Listen modelluebergreifend funktionieren.

---

## 12. Upload

Settings > Upload: Aufgenommene Sessions koennen automatisch nach der Aufnahme hochgeladen werden.

| Ziel | Angaben |
|------|---------|
| Aus | nur lokale Kopie, manueller Export |
| Ordner | beliebiger Ordner (Geraet, Drive, OneDrive ...) per Ordnerwahl |
| NAS (SMB / Windows-Freigabe) | Host, Freigabe, Pfad, Benutzer, Passwort |

Das NAS-Passwort wird verschluesselt auf dem Geraet gespeichert. "Verbindung testen" prueft die Angaben. Optionen: nur bei WLAN hochladen, lokale ZIP-Kopie nach dem Upload nicht behalten. Der Status des letzten Uploads steht darunter.

---

## 13. Modelle

Settings > Modell & Erkennung > Modell:

| Modell | Eigenschaften |
|--------|---------------|
| BirdNET 3.0 FP16 (empfohlen) | Standard, 11'725 Klassen, 32 kHz, 3-s-Fenster |
| BirdNET 3.0 FP16 kompakt (pruned) | kleiner (ca. 70 MB), Erkennung praktisch gleich |
| BirdNET 3.0 Preview 3.1 FP16 / FP32 | aeltere Vorabversion, 11'560 Klassen |

Das aktive Modell wird per Auswahl umgeschaltet, ab der naechsten Analyse. Die Artenzahl neben dem Modell stimmt nach dem Wechsel sofort. Modelle koennen heruntergeladen (abbrechen und fortsetzen moeglich), importiert und geloescht werden. Jede Session merkt sich das verwendete Modell.

**Modelltabelle (ab v0.1.1):** Die Spalte Primaer (Radio) bestimmt das Hauptmodell. Sekundaer (Haekchen, nur im Experten-Modus) waehlt das Zweitmodell, das jede Detektion mitprueft; abwaehlbar, nie gleich dem Primaer. Der Papierkorb loescht ein installiertes Modell nach Bestaetigung; das Primaer laesst sich nicht loeschen und ist waehrend einer Aufnahme gesperrt. Nicht geladene Modelle zeigen ein Lade-Symbol mit Fortschritt und Abbrechen in der Zeile.

**Konfidenz-Schwelle:** Der Regler "Konfidenz-Schwelle (alle Arten)" gilt fuer alle Arten gleich (Standard 50 %). Watchlist-Alarme loesen erst ab dieser Schwelle aus.

**Mindest-Konfidenz:** Die Erkennungs-Schwelle ist global einstellbar. Ein Modell kann zusaetzlich eine eigene Mindest-Konfidenz mitbringen (z.B. 80 % fuer Perch). Es gilt der hoehere Wert; das Modell zeigt ihn an.

### Eigene und weitere Modelle importieren
PIROL ist nicht auf BirdNET festgelegt. Ueber "Anderes Modell importieren..." wird ein Paket aus drei Dateien gemeinsam ausgewaehlt: `.onnx` (Modell), `.pirol.json` (Profil) und Label-CSV. Die App prueft Pruefsummen, Ein-/Ausgaenge und Labelzahl und meldet Fehler im Klartext. Abtastrate und Fensterlaenge sind frei.

Geprueft sind **BirdNET 2.4** (6'522 Arten, 48 kHz, etwa halbe Rechenzeit von 3.0), **Perch v2** (Google, 14'795 Klassen, 5-s-Fenster) und **Nighthawk 0.3.1**. Die Dateien sind nicht Teil der App. Fuer die Umwandlung von TensorFlow-, Keras- und TFLite-Modellen (auch eigene Trainings) liegt das Skript `tools/convert_model.py` bei, siehe `tools/README_convert_model.md` und `tools/MODEL_PROFILE_FORMAT.md`.

**Lizenzen:** BirdNET 3.0 Apache 2.0, Perch v2 Apache 2.0, BirdNET 2.4 und Nighthawk nur nicht-kommerziell (CC BY-NC bzw. BY-NC-SA). Die Lizenz wird beim Import angezeigt.

**Hinweis:** Perch arbeitet mit 32 kHz; Laubheuschrecken im Ultraschall sind damit nicht hoerbar.

**Perch als Zweitmodell:** braucht ca. 650 MB zusaetzlichen Speicher. Die App warnt beim Einschalten, wenn das Geraet weniger als 6 GB RAM hat. Das Zweitmodell arbeitet mit dem Rohaudio der Aufnahme, damit die 5-s-Fenster echte Umgebung enthalten.

---

## 14. GPS

Settings > Karte & GPS (bis v0.0.10: Aufnahme & Speicher):

| Einstellung | Bedeutung |
|-------------|-----------|
| GPS-Modus "Dauernd" | laufend Fixes im eingestellten Intervall (2/5/10/20/60 s, Standard 10 s) |
| GPS-Modus "Nur bei Detektion" | GPS nur bei einer Detektion, mindestens 30 s zwischen zwei Fixes; das Intervall gilt dann nicht. Spart rund ein Drittel Akku |
| Max. GPS-Ungenauigkeit | ungenauere Fixes werden verworfen (Standard 50 m) |
| GPS-Smoothing | Median ueber 5 Fixes, glaettet Ausreisser |

Jede Detektion erhaelt einen frischen Standort, auch bei ausgeschaltetem Bildschirm. Aenderungen wirken ab der naechsten Aufnahme.

**Start-Fix:** Beim Programmstart und beim Zoomen der Karte holt PIROL ohne laufende Aufnahme einmalig einen Standort (kein Dauertracking).

**Route:** Die Route ("Route aufzeichnen", Standard an) wird waehrend der Aufnahme mitgeschrieben (`track.jsonl`) und beim Stopp als `track.gpx` neben die Aufnahme gelegt (Zeiten in UTC).

**Gemeinde:** Der Knopf "Gemeinde einfuegen" traegt die Gemeinde aus der Position in die Bemerkung ein (im Detail-Dialog, in der Session-Bemerkung und in der Analyse). Die Gemeindedaten (Schweiz) liegen offline in der App. Ausserhalb davon wird nichts ermittelt, ausser der Experten-Schalter Settings > Karte & GPS > Erweitert > "Gemeinde online ermitteln (Google)" ist an (Standard aus; dabei werden die Koordinaten an Google gesendet).

---

## 15. Energie und Akku

Settings > Modell & Erkennung > Energieprofil:

| Profil | Analyse |
|--------|---------|
| Genau | jedes 3-s-Fenster |
| Balanciert | alle 6 s |
| Sparsam | alle 15 s, fuer lange Feldtage |
| Ultra-Sparsam | alle 30 s, maximale Laufzeit |

**Praxiswerte** (Tablet Galaxy Tab A11+, 6'840 mAh, Daueraufnahme, WLAN an, BirdNET 3.0 FP16; Richtwerte, ca. ±15 %):

| Einstellung | Verbrauch | Laufzeit voller Akku |
|-------------|-----------|----------------------|
| Genau, Bildschirm aus, GPS dauernd (10 s) | ca. 2.2 %/h | ca. 45 h |
| wie oben, 67 % Ueberlappung | ca. 3.5 %/h | ca. 28 h |
| wie oben, **Bildschirm an** | ca. 9 %/h | ca. 12 h |
| Genau, GPS nur bei Detektion | ca. 1.5 %/h | ca. 60 h |
| Sparsam (15 s), GPS 60 s | ca. 0.9 %/h | ca. 4-5 Tage |

**Tipps:**
1. **Bildschirm ausschalten** ist der groesste Hebel (mehr als doppelt so viel wie Aufnahme und Analyse zusammen). Die Aufnahme laeuft im Dienst weiter.
2. GPS-Modus "Nur bei Detektion" waehlen.
3. Ueberlappung bei 0 % lassen, bei langen Einsaetzen Profil "Sparsam".
4. Modellwahl spielt kaum eine Rolle (pruned etwa wie final, Preview 3.1 ca. 10 % mehr, FP32 nicht mehr als FP16).

---

## 16. Sprachen

Settings > Sprache: Sprache der App (Systemsprache oder Auswahl): Deutsch, Englisch, Franzoesisch, Italienisch, Katalanisch, Portugiesisch, Niederlaendisch, Baerndeutsch. **Baerndeutsch und Portugiesisch sind Entwuerfe**, fehlende Texte erscheinen auf Deutsch. Die **Artennamen**-Sprache folgt der App-Sprache, kann aber separat gesetzt werden (23 Sprachen).

---

## 17. Einstellungen im Ueberblick

Die Settings sind in einklappbare Sektionen gegliedert; der Zustand wird gemerkt. Standardmaessig offen ist nur "Sprache".

| Sektion | Inhalt |
|---------|--------|
| Sprache | App-Sprache, Artennamen-Sprache |
| Aufnahme | Audio-Info, Preroll, Ueberlappung, Energieprofil, Speicherort (Ordner, Tages-Unterordner, Auto-Loeschen, Sessions verschieben), Max. Aufnahmelaenge und Ausschnittlaenge, Resampler |
| Modell & Erkennung | Modelle (Download, Import, Loeschen), Erkennungs-Schwelle (alle Arten), Alternativen ab, Max. Arten pro Fenster, Voreinstellungen, Artvorschlaege, Zusammenfassen von Detektionen, Aehnliche Arten und Embedding-Limit |
| Arten & Alarm | Regionaler Filter und eigene Listen, Watchlist, Alarm-Pause und -Ausgabe, Alarm testen, PIROL-Ordner, Saisonalitaet und Seltenheit |
| Anzeige | Artnamen-Sprache, Vollbild, Sonogramm (Modus, Voreinstellung, Kontrast, Gamma, Deckel, Palette, Frequenz, Zeitfenster), kompakte Artkarte |
| Karte & GPS | GPS-Modus, Route aufzeichnen, GPS-Intervall, Genauigkeit und Glaettung; Experten (Erweitert): "Gemeinde online ermitteln (Google)" |
| Export & Upload | Raven-Export-Felder (inkl. Memo), Datenschutz (menschliche Stimmen ausblenden, Mensch-Schwelle), Upload (Ziel Ordner/NAS, Passwort, Verbindungstest, nur WLAN) |
| Aehnliche Arten & Referenzen | Aehnlichkeitssuche, Zeilenzahl, Vergleichsaufnahmen (Kapitel 9) |
| Info | Version, Quellen, Was ist neu, Diagnose (Benchmark) |

Der Schalter **Experteneinstellungen** (unter "Einrichtung starten") blendet im Einfach-Modus alles Seltene aus; im Expertenmodus ist alles sichtbar. Keine Einstellung geht dabei verloren. Seltene Optionen stehen im einklappbaren Unterblock "Erweitert" der jeweiligen Sektion (z.B. Hochpassfilter, Detektions-Cluster, Phaenologie, Max. Aufnahmelaenge).

**Resampler:** Polyphase (empfohlen) filtert Stoergeraeusche ueber 16 kHz, die sonst als Geistersignale erscheinen koennen. "Linear" ist die alte Methode. Die Umschaltung zeigt einen Hinweis und wirkt ohne Neustart der Aufnahme.

**Diagnose:** Der Benchmark misst die Rechenzeit des aktiven Modells mit 1/2/4 Threads (1-3 Minuten, nicht waehrend einer Aufnahme). Mit "ONNX-Datei waehlen und messen" laesst sich auch ein beliebiges Modell vor dem Import testen.

---

## 18. Technische Hinweise

- **Aufnahme:** 48 kHz, 16-bit PCM, Mono (96 kHz im Fledermaus-Modus). Erkennung auf 32 kHz umgerechnet (BirdNET 3.0).
- **Mindestgeraet:** Android 8.0. Kompatibel mit 16-KB-Speicherseiten (Android 15+).
- **Fairphone und andere Geraete:** Die Abtastrate wird mit dem Geraet ausgehandelt, bei Problemen weicht die App auf einen anderen Audio-Treiber aus.
- **Datenformate:** `session.json`, `detections.jsonl`, `verifications.jsonl`, Raven-TSV; Zeitstempel in Ortszeit mit Offset.

---

## 19. Aenderungen

Die vollstaendige Liste steht in `CHANGELOG.md` im Projekt.

### Version 0.1.2 (in Arbeit)
- Angaben importierter Sessions (Startzeit, Ort, Bemerkung) nachtraeglich bearbeiten (Kapitel 5)
- "Speichern unter" als ZIP oder Ordner (Kapitel 6)
- Raven: eine Zeile pro Episode, neue Spalten `Cluster`, `Windows` und `Memo` (Kapitel 7)
- Rescan und Import: Ergebnisse laufend sichtbar und verifizierbar, Abbruch behaelt das Teilergebnis (Kapitel 6)
- Menschliche Stimmen werden im Export ausgeblendet, Standard an; Rueckfrage bei Modell ohne Mensch-Klasse (Kapitel 7)
- Gemeinde online ueber Google nur noch als Option, Standard aus (Kapitel 17)
- Session-Memo per Gedrueckt-halten, Block "Memos" in der Analyse, Memo-Zeile in Raven (Kapitel 2, 6, 7)
- Live-Artkarten ruhiger: Wechsel hoechstens alle 5 s, Treffer in den Top 5 blinken blau (Kapitel 2)
- Hilfe-Knopf "?": Hilfemodus und Anleitung offline (Kapitel 1)
- Anleitung korrigiert: Artfilter im Kartenmenue (Kapitel 8), Settings-Sektionsnamen (Kapitel 1, 5, 7, 17)

---

*PIROL v0.1.2 (in Arbeit) — ETA Systems — Stand 2026-11-24*
