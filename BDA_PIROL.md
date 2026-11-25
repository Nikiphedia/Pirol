# PIROL — Bedienungsanleitung

Akustische Echtzeit-Artenerkennung für Android, gedacht für die Feldarbeit.

*Gilt ab Version v0.1.3 (Stand 2026-11-25). Was sich zwischen den Versionen geändert hat, steht in `CHANGELOG.md`.*

Die App hat fünf Tabs: **Live**, **Analyse**, **Referenzen**, **Karte** und **Settings**. Der Knopf **?** (Hilfe) ist kein Tab: Ein Tipp schaltet den Hilfemodus ein, ein Doppeltipp öffnet diese Anleitung (Kapitel 1, Abschnitt "Hilfe-Knopf").

---

## 1. Erster Start

1. App öffnen. Der Einrichtungsassistent führt durch Sprache, Niveau (Einsteiger/Experte), Einsatzzweck, Artenliste, Modell (inkl. Geomodel), Berechtigungen (Mikrofon, GPS, Benachrichtigungen) und Zusammenfassung. Die Werte werden erst bei "Fertig" übernommen.
2. **Artenliste** wählen (Kapitel 11): Die Artgruppen (Vögel, Heuschrecken, Amphibien, Säugetiere, Andere) filtern die Erkennung auf plausible Arten. Später änderbar unter Settings > Arten & Alarm.
3. **Modell** herunterladen: Es lassen sich mehrere Modelle auf einmal auswählen. Standard ist BirdNET 3.0 FP16 (empfohlen, ca. 170 MB). Der Download läuft im Hintergrund weiter, kann abgebrochen und später fortgesetzt werden. Alternativ eine Modell-Datei manuell auswählen.
4. **Speicherort** prüfen (Settings > Aufnahme). Standard: `Downloads/PIROL/`, per USB sichtbar.

**Installation (APK):** Die Release-APK gibt es pro Prozessor-Architektur. Für aktuelle Geräte (Telefone und Tablets) die Datei `Pirol-<Version>-arm64-v8a-release.apk` installieren, nur für alte 32-Bit-Geräte die Datei `...-armeabi-v7a-release.apk`. Die APKs sind dadurch deutlich kleiner als früher die gemeinsame APK. Die App hat ein neues Symbol (gelber Vogel), auch als einfarbiges Symbol für Themed Icons. Einzelheiten zur Installation und zum Update (auch Samsung-Geräte) stehen in Kapitel 20.

**Was ist neu:** Nach einem Update zeigt PIROL einmalig einen Dialog "Was ist neu" mit den wichtigsten Änderungen der Version. Er kann jederzeit in den Einstellungen unter "Info" erneut aufgerufen werden.

### Hilfe-Knopf
Der Knopf **?** (Tablet und schmale Navigation: unter Einstellungen; Telefon: ganz rechts in der Leiste) bietet zwei Wege:
- **Antippen** schaltet den **Hilfemodus** ein oder aus. Oben erscheint die Leiste "Hilfemodus: Knopf berühren für Erklärung". Ein Tipp auf einen Knopf oder Regler löst dann nichts aus, sondern zeigt eine kurze Erklärung, bei Bedarf mit "Mehr in der Anleitung" (springt zum passenden Kapitel). Beenden: X in der Leiste, Zurück oder nochmals **?**.
- **Doppeltippen** öffnet diese Anleitung offline, in der App-Sprache (fehlt die Übersetzung, auf Deutsch). Ein Inhaltsverzeichnis führt zu den Abschnitten.

**Update von älteren Versionen:** Ab v0.0.8 ist PIROL signiert. Wer v0.0.7 oder älter installiert hat, muss einmal deinstallieren und vorher die Sessions per USB sichern (`Android/data/ch.etasystems.pirol/files/PIROL/`). Von v0.0.8 bis v0.0.10 auf v0.0.11 geht das Update ohne Deinstallation, Sessions und Modelle bleiben erhalten. Achtung bei v0.0.11: Die Konfidenz-Schwelle ist neu eine gemeinsame Schwelle für alle Arten (Kapitel 2 und 13), den Regler danach prüfen. Von v0.0.11 auf v0.1.1 geht das Update ohne Deinstallation. Danach die Artgruppen (Kapitel 11) prüfen: alte Filter stehen auf Vögel Auto, Rest Aus. Sessions aus v0.0.6 und älter werden nicht mehr speziell unterstützt.

---

## 2. Live-Erkennung

**Start:** Aufnahme-Knopf im Live-Tab drücken. **Stopp:** Knopf lang drücken. Session und Raven-Export werden automatisch gespeichert.

Aufnahme und Analyse laufen in einem Dienst. Sie gehen weiter, wenn die App in den Hintergrund geht oder der Bildschirm ausgeschaltet wird.

Beim Stoppen erscheint oben der Hinweis "Lang drücken zum Stoppen" (oben, damit der Finger am Knopf ihn nicht verdeckt).

Erkannte Arten erscheinen als Karten unter dem Sonogramm:
- Artname in der gewählten Sprache, wissenschaftlicher Name als Zusatz
- Konfidenz in Prozent (dünner Balken), Zeitstempel, GPS-Position
- Alternativen (aufklappbar, antippbar)
- Hinweise "Selten", "Saison-untypisch", Watchlist-Markierung
- **Abspielen:** Der Play-Knopf auf der Karte spielt den Ausschnitt der Detektion (3 s davor, 5 s danach) ab, auch während der Aufnahme. Ein zweiter Tipp stoppt die Wiedergabe.
- **Blinken:** Wird eine Art erneut erkannt, blinkt ihre Karte kurz blau auf. Die Liste springt dabei nicht (siehe "Ruhige Liste").
- **Alarm-Glocke:** Im Menü der vollen Artkarte legt man die Art dauerhaft in die Alarmliste oder für 24 h an. 24-h-Alarme zeigen eine Glocke mit dem Abzeichen "24h"; nach Ablauf verschwindet es automatisch.
- **Kompakt-Knopf:** Schaltet alle Artkarten zwischen kompakt und ausführlich um. Auf dem Telefon sitzt er in der Statuszeile, auf dem Tablet in der Kopfzeile. Die Alternativen lassen sich über den Kopf der Karte auf- und zuklappen.

**Kompakte Ansicht auf dem Telefon:** Im Hoch- und im Querformat nutzt der Live-Tab den Platz sparsam: keine Titelzeile, die Navigation besteht nur aus Icons, die Knopfzeile der Karten ist einzeilig. Auf schmalen Karten stehen Teilen, Referenz, Vergleichen und "Alternativen" im Menü "Weitere Aktionen" (Symbol mit drei Punkten); auf dem Tablet bleiben alle Knöpfe sichtbar. Im Querformat liegen links Sonogramm und Knöpfe, rechts die Artkarten. Das Panel "Ähnliche Arten" ist auf dem Telefon eingeklappt und lässt sich aufklappen.

**Mikrofon stumm bei Wiedergabe:** Spielt PIROL während der Aufnahme eine Detektion über den Lautsprecher ab, wird das Mikrofon stumm geschaltet, damit die Wiedergabe nicht erneut erkannt oder aufgenommen wird. Im Live-Tab erscheint der Hinweis "Mikrofon stumm während Wiedergabe". Mit angeschlossenen Kopfhörern (Kabel, USB, Bluetooth) bleibt das Mikrofon aktiv.

| Knopf | Zustand |
|-------|---------|
| grün, pulsierend | Preroll-Puffer läuft (Aufnahme noch nicht gestartet) |
| rot, pulsierend | Aufnahme läuft |

**Status-Zeile:** Laufzeit in mm:ss. **GPS-Leiste:** aktuelle Genauigkeit in ±m, rot bei länger als 30 s ohne gültigen Fix.

**Bemerkung:** Pro Aufnahme kann ein Titel eingegeben werden. Er erscheint in der Session-Liste, im ZIP-Namen und im Raven-Export.

**Manuell hinzufügen:** Eine Art von Hand eintragen (mit Stückzahl, Atlascode, Bemerkung). Pro Detektion lassen sich ausserdem Stückzahl, Atlascode, Bemerkung und ein Sprach-Memo erfassen. Eine Art kann gesperrt werden (nur eine Meldung pro Session).

**Zusammenfassen:** Detektionen derselben Art werden räumlich und zeitlich zu einem Eintrag zusammengefasst (Radius und Zeitfenster einstellbar, Settings > Modell & Erkennung).

### Ruhige Liste
Die Reihenfolge der Artkarten ändert sich höchstens alle 5 s. Neue Arten werden gesammelt und gemeinsam oben eingefügt (die allererste Karte erscheint sofort); sie können also bis zu 5 s später sichtbar werden, Alarme kommen weiterhin sofort. Wird eine Art in den obersten 5 Karten erneut erkannt, bleibt sie stehen und blinkt blau. Eine Art weiter unten rückt beim nächsten Wechsel nach oben und blinkt. Löschen, Verifizieren und gesperrte Arten wirken sofort. Detektionen, Alarm, Zusammenfassung und Analyse sind davon nicht betroffen.

### Session-Memo
Der Memo-Knopf im Live-Tab (nur während einer Aufnahme sichtbar) funktioniert wie ein Walkie-Talkie: **gedrückt halten und sprechen**, beim Loslassen endet das Memo. Während der Aufnahme ist der Knopf rot und zeigt die Laufzeit; danach erscheint "Memo gespeichert (n s)". Memos unter 0.5 s werden verworfen ("Zu kurz"), nach 3 Minuten endet ein Memo automatisch. Das Memo wird mit seiner Lage in der Aufnahme gespeichert (`memos.jsonl`, Dateien unter `memos/`), erscheint in der Analyse im Block "Memos" (Kapitel 6) und als Zeile "Memo" im Raven-Export (Kapitel 7). Memos werden nie als Stimme ausgeblendet (Kapitel 7). Sprach-Memos zu einzelnen Arten bleiben davon getrennt.

Beim Drücken merkt sich PIROL den letzten GPS-Fix (nicht älter als 2 min) und speichert ihn mit dem Memo; auf der Karte erscheint das Memo dann als Mikrofon-Marker (Kapitel 8). Ohne aktuellen Fix hat das Memo keinen Ort. Löschen, Teilen und Speichern unter: Kapitel 6.

### Mehrfacherkennung und Konfidenz-Schwelle
Im selben 3-s-Fenster werden alle Arten erkannt, die die Schwelle erreichen, nicht nur die oberste. Der Regler heisst "Konfidenz-Schwelle (alle Arten)" (Settings > Modell & Erkennung, Standard 50 %) und gilt für alle Arten gleich. Arten darunter, aber über der Kandidaten-Grenze, bleiben als Kandidaten bei den Detektionen des Fensters. Früher steuerte der Regler eine andere Schwelle (TopK, Standard 0.3); deshalb nach dem Update prüfen. Watchlist-Alarme lösen erst ab der Schwelle aus.

### Zweitmodell (Experten-Modus)
Ein Zweitmodell (z.B. Perch v2 oder BirdNET 2.4, Kapitel 13) rechnet dieselben Fenster noch einmal. Die Karte zeigt dann neben der Konfidenz des Hauptmodells die Zahl des Zweitmodells: in Prozent, bei Perch als Rang ("Perch #2"). Arten, die das Zweitmodell nicht kennt, sind als nicht prüfbar markiert. Das Zweitmodell beeinflusst weder Alarm noch Filter oder Zusammenfassung. Die Schwelle "Alternativen ab" (5/10/20/30 %, Settings > Modell & Erkennung) bestimmt, ab welcher Konfidenz Alternativen gelistet werden.

### Preroll
Wenn aktiviert, wird Audio **vor** dem Aufnahmestart mitgespeichert (Länge einstellbar) und auch analysiert. Der Preroll steht lückenlos am Anfang von `recording.wav`. Das Sonogramm zeigt davon höchstens 5 s.

### Überlappung
Analysefenster können sich überlappen: 0 % (Standard, Fenster alle 3 s), 50 % (alle 1.5 s) oder 67 % (jede Sekunde). Mehr Überlappung erkennt kurze Rufe an Fenstergrenzen besser, braucht aber mehr Rechenzeit und Akku. Ist das Gerät zu langsam, reduziert PIROL die Überlappung automatisch und meldet das einmal pro Session in einer Snackbar. Bei Modellen mit längerem Fenster (Perch, 5 s) begrenzt der Regler "Max. Schrittweite" (3, 4 oder 5 s) den Abstand zwischen zwei Fenstern.

### Lange Aufnahmen (Segmente)
Nach der eingestellten Maximaldauer (Standard 60 min, einstellbar 10-80 min) beginnt automatisch eine neue Datei (`recording-002.wav` usw.). Die Aufnahme läuft ohne Unterbruch weiter. Zeitangaben in `detections.jsonl` und Raven gelten ab Session-Start. Der WAV-Kopf wird alle 5 s nachgeführt: Wird die App hart beendet, gehen höchstens ca. 3 s Audio verloren.

### Hochpassfilter und Aufbereitung
Der Hochpassfilter (200 Hz) filtert Wind und Trittschall, standardmässig an. Er wirkt nur auf die Kopie für die Erkennung, die gespeicherte Aufnahme bleibt unverändert. Änderungen gelten ab der nächsten Aufnahme.

---

## 3. Anzeige: Sonogramm, Spektrum, Oszillogramm

Im Live- und im Analyse-Tab. Die Frequenzachse zeigt 1/2/4/8/12 kHz. Dargestellt wird standardmässig bis **12 kHz** (Voreinstellung für Vögel), einstellbar bis **24 kHz**. Die Pegel des Sonogramms sind in dBFS.

**Anzeige-Modi (Live-Tab):** Im Anzeige-Panel (Langdruck) schaltet man zwischen **Sonogramm**, **Spektrum** und **Oszillogramm** um. Das Spektrum zeigt die Lautstärke je Frequenz mit echter FFT (dBFS, Mittelung, Peak-Halten mit verblassenden Spitzen, Cursor). Die Frequenzachse lässt sich im Spektrum-Panel zwischen **Linear** (Standard, gleiche Hz-Abstände) und **Mel** (tiefe Frequenzen gedehnt, wie im Sonogramm) umschalten; Raster und Cursor-Anzeige folgen der Wahl. Der Regler **Intensität (Gamma)** wirkt auch im Spektrum (Kurvenhöhe und dB-Gitter) und ist derselbe Wert wie im Sonogramm. Das Oszillogramm zeigt den Zeitverlauf mit Pegelanzeige (Peak/RMS), Bandpass und roter Markierung bei Übersteuerung; sein **Zeitfenster** (5 bis 30 s) stellt man im Oszillogramm-Panel ein. Ein Gamma-Regler gehört nicht zum Oszillogramm. Solange noch keine Daten da sind, steht je Ansicht ein eigener Hinweis ("Spektrum wird geladen", "Oszillogramm wird geladen"). Frequenz, Dynamik und Farbmuster gelten für alle drei Ansichten gemeinsam. In der Analyse stehen Spektrum und Oszillogramm ebenfalls zur Verfügung.

**Darstellung anpassen:** Langdruck auf das Sonogramm (Live oder Analyse) öffnet das Einstellungs-Panel unterhalb des Sonogramms; dieselben Regler stehen in Settings > Anzeige. Das Panel gilt für Live, Analyse und Settings gemeinsam. Oben im Panel steht "Fertig" in einer eigenen Zeile; Voreinstellungen und Farbpaletten sind Kacheln, die bei Platzmangel umbrechen.

| Regler | Wirkung |
|--------|---------|
| Voreinstellung | Standard / Leise Rufe / Laute Umgebung |
| Auto-Kontrast | passt die dB-Schwelle laufend dem Signal an; sonst von Hand (Bereich in dB) |
| Intensität (Gamma) | kleiner = leise Anteile heller |
| Helligkeits-Deckel | begrenzt starke Signale |
| Farbpalette | Magma, Viridis, Graustufen, **Papier (hell)** |
| Frequenz | angezeigter Bereich in kHz |
| Zeitfenster | Sekunden, die sichtbar sind (5 bis 30 s) |

**Papier (hell):** helle Palette wie ein klassisches Papier-Sonagramm, gut bei Tageslicht. Die Palette wird im Panel (Langdruck auf das Sonogramm) gewählt; auf dem Telefon gibt es dafür keinen eigenen Knopf im Live-Tab mehr.

**Tab-Wechsel:** Verlässt man den Live-Tab oder schaltet den Bildschirm aus, rechnet PIROL das Sonogramm bei der Rückkehr nach (bis zum eingestellten Zeitfenster), sodass es lückenlos weiterläuft. War man länger weg als das Zeitfenster, beginnt es neu.

**Modus** (Settings > Anzeige): Vögel, Fledermaus oder Breitband. Der Fledermaus-Modus nimmt mit 96 kHz auf (Ultraschall); dabei gibt es keinen Preroll.

---

## 4. Verifikation

Pro Detektion stehen diese Aktionen zur Verfügung (Live-Tab, Analyse-Tab und Karte):

| Aktion | Status | Darstellung |
|--------|--------|-------------|
| Bestätigen | Bestätigt | grüner Rand |
| Unsicher (?) | Unsicher | oranger Rand |
| Ablehnen | Abgelehnt | ausgegraut |
| Korrigieren | Korrigiert | Name durch eigene Eingabe ersetzt |
| Alternative wählen | Bestätigt | Die Art der Detektion wird durch die Alternative ersetzt (gleiche Zeile, Zeit und Position); die alte Art bleibt als Kandidat. Rückgängig in der Snackbar |

Im **Live-Tab** gibt es beim Verifizieren keine Snackbar mehr. Nach "Bestätigen" zeigt die Karte stattdessen einen grünen Rückgängig-Knopf; er setzt die Detektion auf "Unsicher" zurück. Die Knöpfe Bestätigen, Unsicher, Ablehnen und Bearbeiten bleiben sichtbar, der Status lässt sich also jederzeit umschalten. Beim Wählen einer **Alternative**, beim Verschieben auf der Karte und beim Bestätigen über den Watchlist-Alarm erscheint weiterhin eine Snackbar mit **Rückgängig** (mindestens 5 s). Alle Aktionen werden in `verifications.jsonl` festgehalten; auch abgelehnte und ersetzte Detektionen bleiben im Export (Audit-Trail).

**Detektion teilen:** Ausschnitt (3 s davor, 5 s danach, Maximallänge einstellbar) als WAV, M4A (AAC), FLAC (falls das Gerät es kann) oder Sonogramm-PNG mit Beschriftung.

---

## 5. Sessions

Jede Aufnahme erzeugt einen Session-Ordner, standardmässig `Downloads/PIROL/YYYY-MM-DD/{sessionId}/`. Der Ordnername trägt die Ortszeit.

```
session.json              Metadaten (Dauer, Ort, GPS-Statistik, verwendetes Modell)
detections.jsonl          alle Detektionen
verifications.jsonl       alle Verifikations-Aktionen
detections.updates.jsonl Zusatzdatei während der Aufnahme (wird beim Stopp in detections.jsonl zusammengeführt)
recording.wav             Aufnahme (16-bit, Mono, 48 kHz); bei langen Sessions
recording-002.wav ...     weitere Segmente
recording.selections.txt  Raven Selection Table (automatisch beim Stopp)
memos.jsonl               Session-Memos mit Lage in der Aufnahme (Dateien unter memos/)
human_windows.jsonl       Merkliste für das Ausblenden menschlicher Stimmen (nie exportiert)
```

- **Speicherbedarf:** ca. 5.5 MB pro Minute Aufnahme.
- **Zeitstempel:** immer Ortszeit mit Zonen-Offset (z.B. `+02:00`).
- **Auto-Löschen:** Kurze Sessions ohne Detektion werden gelöscht (Mindestdauer einstellbar, 0 = aus).
- **Löschen** einer Session in der Analyse: mit Bestätigung.
- **Angaben importierter Sessions bearbeiten:** Bei importierten Sessions gibt es im Überlaufmenü der Kopfzeile (Symbol mit drei Punkten) den Eintrag "Angaben bearbeiten": Startzeit, Ort und Bemerkung lassen sich nachträglich ändern. Eine neue Startzeit verschiebt alle Zeiten der Session (Detektionen, Verifikationen, Varianten, Route) um dieselbe Differenz; Positionen in der Aufnahme bleiben. Ändert sich das **Datum**, benennt PIROL auch den Session-Ordner um (neue Kennung mit der neuen Ortszeit) und verschiebt ihn in den passenden Tagesordner (sofern Tages-Unterordner an sind). Startzeit und Datum wählt man mit Datums- und Zeitauswahl. Raven und GPX werden neu geschrieben. Der Ort gilt für die Session und alle Detektionen ohne eigenen GPS-Fix; auf der Karte verschobene Punkte bleiben. Nicht möglich während eines Rescans; eine Datumsänderung ist auch nicht möglich, solange ein Upload dieser Session eingereiht oder aktiv ist.

### Speicherort wechseln
Settings > Aufnahme > Speicherort: "Anderen Ordner wählen" (SAF: interner Speicher, SD-Karte, Cloud-Ordner). Optional mit Tages-Unterordnern. Ist der Ordner nicht erreichbar, speichert PIROL im Fallback-Ordner der App und meldet das. Einzelne Aufnahmen lassen sich auch im Analyse-Tab verschieben (Kapitel 6).

**Sessions verschieben:** Beim Wechsel fragt PIROL, ob bestehende Sessions an den neuen Ort **kopiert** werden sollen ("Kopieren") oder nur der Ort gewechselt wird ("Nur wechseln").

---

## 6. Analyse-Tab

- **Session-Liste:** nach Datum, mit Dauer, Grösse und Bemerkung.
- **Session öffnen:** Sonogramm und Detektionsliste mit Zeitangabe. Die gerade abgespielte Detektion ist markiert, ein Zeiger zeigt die Position.
- **Abspielen:** pro Detektion (Ausschnitt mit Zoom) oder die ganze Aufnahme; Segmente werden nahtlos aneinandergereiht. Lange Aufnahmen werden gestreamt.
- **Mehrere Vorkommen:** Wurde eine Art im Lauf der Aufnahme mehrmals erkannt und zu einer Karte zusammengefasst, zeigt die Karte die Zeile "N Vorkommen", die sich aufklappen lässt. Aufeinanderfolgende Fenster sind zu **Episoden** zusammengefasst ("N Vorkommen in M Episoden"). Jede Episode steht mit Start- und Endzeit, Position in der Aufnahme ("@ m:ss") und Dauer ("N Fenster, M s") in einer Zeile; ihr Play-Knopf spielt die ganze Episode am Stück ab (auch bei langem Dauersang). Zu einzelnen Fenstern ist nur der Zeitpunkt bekannt, nicht die Konfidenz.
- **Wiedergabe:** Es spielt immer nur ein Eintrag. Ein zweiter Tipp auf denselben Knopf stoppt, ein Tipp auf einen anderen Eintrag wechselt, und ein Tab-Wechsel beendet die Wiedergabe. Das gilt in der ganzen App (Live, Analyse, Karte, Referenzen, Sprach-Memos).
- **Verifizieren, korrigieren, Art hinzufügen:** wie im Live-Tab, auch nachträglich.
- **Vergleich:** Detektion und Referenz nebeneinander, mit synchroner Wiedergabe.
- **Suche:** Das Such-Symbol in der Session-Liste filtert nach Artname (Teilwort, deutscher oder wissenschaftlicher Name). In einer geöffneten Aufnahme filtert die Suche die Detektionsliste.
- **Kopfzeile und Wiedergabeleiste:** Zurück, Datum und Kennung, Play mit Positionsanzeige, Suche, Raven und Upload. Der Upload meldet sein Ergebnis per Snackbar ("Hochgeladen", bei Fehler mit Grund, oder dass PIROL auf einen Entscheid wartet, Kapitel 7). Mit vorige/nächste Detektion springt man zwischen den Einträgen.
- **Navigation:** Tippen im Sonogramm oder Oszillogramm springt an die Stelle; läuft keine Wiedergabe, merkt sich PIROL die Startposition (cyanfarbener Zeiger) für das nächste Play.
- **Spektrum und Oszillogramm:** wie im Live-Tab (inkl. Linear/Mel und Gamma, Kapitel 3); im Oszillogramm zeigt ein Zeiger die Abspielposition.
- **Zoom mit zwei Fingern:** In Übersichtsleiste und Sonogramm zoomt man per Pinch (zwei Finger). Der Ausschnitt ist mindestens 3 s (bei kürzeren Sessions die ganze Session) und höchstens die ganze Aufnahme; beim Herauszoomen auf die ganze Aufnahme ist der Zoom aufgehoben. Tippen und Ziehen mit einem Finger funktionieren wie bisher.
- **+ Art:** übernimmt die Zeit aus der Abspielposition (+/-10 s). Bei Sessions aus mehreren Dateien mit Lücken zwischen den Segmenten rechnet PIROL über die Wanduhr des jeweiligen Segments; das Fenster bleibt innerhalb des Segments. Der Ort lässt sich über "Auf Karte wählen" bestimmen.
- **Übersicht:** Eine Übersichtsleiste zeigt die Detektionen über die ganze Aufnahme; die Raven-Tabelle listet sie als Tabelle.
- **Raven-Datei neu erzeugen**, z.B. nach nachträglicher Verifikation.
- **Episoden teilen:** Jede Episode hat einen eigenen Teilen-Knopf; geteilt wird der Ausschnitt dieser Episode.
- **Memos:** Gibt es Memos, zeigt die Analyse unter Sonogramm und Übersicht den aufklappbaren Block "Memos (n)" (höchstens 200 dp hoch, scrollbar) mit Abspielen. Er listet Session-Memos und Memos zu Arten; bei Art-Memos ohne bekannte Aufnahmezeit steht "ca. ... (Zeit der Detektion)". Das Menü je Memo bietet **Teilen** und **Speichern unter** (die WAV-Datei unverändert, Name `YYYYMMDDHHMMSS_Memo_Bemerkung.wav`, ohne Ausblenden von Stimmen) und bei Session-Memos **Löschen**: Das Memo verschwindet sofort, eine Snackbar bietet **Rückgängig** (10 s); erst danach wird die Datei endgültig gelöscht. In der gerade laufenden Aufnahme ist Löschen gesperrt ("Löschen (Aufnahme läuft)").
- **Rescan:** Der Rescan-Knopf neben der Lupe (nur wenn eine Aufnahme vorhanden ist) öffnet die Wahl von Modell, Liste bzw. Artgruppen, Konfidenz-Schwelle, Overlap und Zweitmodell. Der Rescan läuft im Hintergrund als Vordergrund-Dienst mit Fortschritt und Abbrechen; das Ergebnis erscheint als Chip neben dem Original ("Datum Zeit · Modell"). Die Ergebnisse erscheinen **laufend**: Die Variante öffnet sich sofort, die Detektionsliste wächst alle paar Sekunden und lässt sich schon während des Scans verifizieren (Kopfzeile "· läuft"). Beim Abbrechen bleibt das Teilergebnis erhalten ("abgebrochen bei x min"); dasselbe gilt, wenn die App während des Scans beendet wird. Der Rescan ist schneller geworden: Er rechnet mit mehr Rechenkernen, und das Zweitmodell läuft parallel zum Hauptmodell; die Ergebnisse sind dieselben wie vorher. Bereits vorgenommene Verifikationen werden nie überschrieben. Während eines Rescans ist keine Aufnahme möglich und die laufende Variante nicht löschbar. Nach dem Import von Audiodateien nutzt der Scan denselben Weg (siehe "Audio importieren").
- **Audio importieren:** Der Import-Knopf im Kopf der Session-Liste übernimmt eine PIROL-Session (ZIP oder Ordner) oder Audiodateien (WAV, FLAC, MP3, M4A, OGG) als neue Session. Die Startzeit steht vorbelegt (z.B. aus einem AudioMoth-Kommentar) und ist änderbar. Bei mehreren Dateien wählt man: je Datei eine Session oder alle zu einer Session (Segmente, nach Zeit sortiert).
  - **Startzeit:** Datum und Uhrzeit wählt man mit Datums- und Zeitauswahl. Die Dateizeit ist bei Recordern oft das Ende der Aufnahme; der Schalter **"Dateizeit = Ende"** (Standard an) rechnet dann Start = Dateizeit minus Laufzeit, ausgeschaltet gilt die Dateizeit als Start. Unter dem Feld steht, woher die Zeit stammt.
  - **Scan nach dem Import:** Anschliessend bietet PIROL den Scan (Rescan-Dialog) an. Bei mehreren als je eigene Session importierten Dateien gilt die Wahl für **alle** neuen Sessions, die nacheinander gescannt werden. Der erste vollständige Scan einer importierten Session ohne Detektionen wird zum **Original**: `detections.jsonl`, `verifications.jsonl` und die Raven-Datei liegen dann direkt im Session-Ordner, ohne manuellen Schritt. Spätere Scans bleiben Varianten.
  - **Abbruch:** Bricht man den Import ab (oder scheitert er), wird die gerade halb importierte Session gelöscht; bereits fertige Sessions eines Mehrfachimports bleiben erhalten. Das ist anders als beim Rescan, der sein Teilergebnis behält. Bricht man den Scan-Dialog ab, werden die wartenden Scans verworfen.
- **Speichern unter:** Im Überlaufmenü der Kopfzeile. Man wählt **ZIP** oder **Ordner** und danach das Ziel (auch SD-Karte, USB oder Cloud-Ordner). Der Inhalt entspricht dem Upload-ZIP; beim Ordner entsteht ein Unterordner mit dem Exportnamen (Kapitel 7). Ein Ziel in der PIROL-Ablage oder in der Session selbst wird abgelehnt. Für die laufende Aufnahme ist der Eintrag ausgegraut. Menschliche Stimmen werden je nach Einstellung ausgeblendet (Kapitel 7).
- **Kopfzeile:** Datum · Gemeinde · Bemerkung in einer Zeile; ein Tipp auf die Bemerkung öffnet den Dialog. Der Kompakt/Gross-Umschalter entspricht dem der Artkarte.

**Mehrere Aufnahmen auswählen:** Langdruck auf eine Aufnahme in der Session-Liste startet den Auswahlmodus; weitere Aufnahmen werden per Tipp gewählt. Die Kopfleiste zeigt "N ausgewählt" mit den Aktionen "Alle", "Verschieben" und "Löschen"; das X oder die Zurück-Taste beendet den Modus. Die gerade laufende Aufnahme ist nicht auswählbar.
- **Verschieben:** Der Zielordner ist frei wählbar (Ordnerwahl, auch SD-Karte oder USB-Speicher). Jede Session wird mit allen Dateien (Aufnahme-Segmente, Memos) kopiert, die Dateigrössen werden geprüft, danach wird das Original gelöscht. Bei einem Fehler oder Abbruch bleibt das Original erhalten und die Teilkopie wird entfernt. Existiert am Ziel schon eine Session mit gleichem Namen, wird `-2`, `-3` usw. angehängt. Der Fortschritt ist sichtbar und abbrechbar. Ein Zielordner innerhalb von `Downloads/PIROL` (oder die Aufnahme selbst) wird abgelehnt; es wird dann nichts verschoben.
- **Löschen:** Nach Rückfrage ("N Aufnahmen löschen?"); das kann nicht rückgängig gemacht werden. Das Löschen einzelner Aufnahmen per Papierkorb-Symbol bleibt wie bisher.

---

## 7. Export

**Raven Selection Table** (`recording.selections.txt`): wird beim Stopp automatisch neben der WAV geschrieben, Tab-getrennt, UTF-8. Öffnet in Cornell Raven, Audacity (als Label-Spur) und Sonic Visualiser.

Enthalten sind u.a. Selection, Begin File (bei Segmenten die jeweilige WAV), Begin/End Time (s), Low/High Freq (Hz), Begin Date Time (mit Offset), Species, Confidence, Status (bei Zweitmodell Second Rank). Welche weiteren Spalten geschrieben werden, steht unter Settings > Export & Upload > Raven-Export Felder. KML-Export gibt es nicht mehr.

### Raven: eine Zeile pro Episode
Aufeinanderfolgende Erkennungen einer Art bilden eine **Episode**; die Tabelle hat pro Episode eine Zeile (nicht mehr pro zusammengefasstem Eintrag). Eine neue Zeile beginnt, wenn die Lücke grösser als ein Analysefenster ist. Confidence ist das Maximum der Episode, Begin File das Segment ihres Beginns. Zusätzliche Spalten: `Cluster` (Kennung des ersten Eintrags) und `Windows` (Anzahl Fenster). Die Raven-Tabelle in der App zeigt dieselben Zeilen.

### Raven: Memos
Jedes Session-Memo (Kapitel 2) erscheint als eigene Zeile mit Species `Memo` an seiner Position in der Aufnahme. Die Spalte `Memo` enthält den Pfad der Memo-Datei (auch bei Art-Memos); sie lässt sich unter Raven-Export Felder abschalten. Memo-Zeilen stehen auch in den Tabellen von Rescan-Varianten.

**ZIP-Paket:** die Session als ZIP, für manuelles Teilen oder den automatischen Upload (Kapitel 12).

### Menschliche Stimmen ausblenden (Datenschutz)
PIROL ersetzt in **Export-Kopien** (Speichern unter als ZIP oder Ordner, Upload-ZIP, Teilen von Ausschnitten) menschliche Stimmen durch Rauschen: Wo das Modell Menschen erkennt, wird das Sprachband (ca. 300-3900 Hz) ersetzt, mit 0.5 s Rand; der Rest bleibt unverändert. Originalaufnahme, Detektionen und Raven werden nie verändert. Die Merkliste `human_windows.jsonl` entsteht live und beim Rescan; noch nicht bewertete Bereiche (importierte oder alte Sessions) scannt PIROL beim Export nach (mit Fortschritt, abbrechbar).
- **Einstellung:** Settings > Export & Upload > Datenschutz > "Menschliche Stimmen im Export ausblenden" (Standard an). Beim Ausschalten warnt PIROL ("Stimmen nicht mehr ausblenden?"), weil Exporte dann Gespräche unbeteiligter Personen enthalten können. Im Expertenmodus stellt "Mensch-Schwelle" ein, ab welchem Score ausgeblendet wird (tiefer = vorsichtiger).
- **Memos** bleiben immer unbearbeitet; der Zeitraum eines Live-Memos in der Aufnahme wird vom Ausblenden ausgenommen.
- **Modell ohne Mensch-Klasse:** Kennt das Modell einer Session keine menschlichen Stimmen, fragt PIROL beim Speichern, Hochladen und Teilen: mit BirdNET 3.0 nach Menschen suchen (nicht während einer Aufnahme, kann Minuten dauern), ohne Ausblenden exportieren (mit Warnung) oder abbrechen. Der automatische Upload lädt in diesem Fall nichts unmaskiert hoch, sondern wartet auf den Entscheid.

**Dateinamen:** Geteilte Detektionen heissen `YYYYMMDDHHMMSS_Art_Bemerkung.ext` (z.B. `20260420143005_Amsel_Garten_hinten.wav`), ZIP-Pakete `YYYYMMDDHHMMSS_Bemerkung.zip` (Zeit = Session-Start). Fehlt die Bemerkung, entfällt der Teil; Leer- und Sonderzeichen werden zu `_`.

---

## 8. Karte

Georeferenzierte Detektionen auf einer Karte. Das **Kartenmenü** öffnet sich per Wisch vom linken Rand oder über das Menü-Symbol oben links ("Kartenmenü öffnen"). Es enthält Anzeige, Kartenquelle, Offline-Karten und den Artfilter.

- **Kartenquelle:** Automatisch (swisstopo in der Schweiz, OpenStreetMap ausserhalb), OpenStreetMap, swisstopo Karte, swisstopo Luftbild.
- **Offline:** Für swisstopo-Karten lässt sich der sichtbare Ausschnitt herunterladen ("Ausschnitt herunterladen", mit Angabe der Kachelzahl und Grösse). Ein Abbruch behält bereits geladene Kacheln; ein erneuter Start lädt nur Fehlendes. "Offline-Karten löschen" entfernt alle gespeicherten Kacheln.
- **Anzeige:** alle Sessions, "Nur Live" oder "Letzte 3". Abgelehnte Beobachtungen sind ausgeblendet. Überlappende Marker werden je nach Zoom gruppiert. Die Karte startet auf dem eigenen Standort.
- **Punkt antippen:** Ein Tipp auf einen Punkt öffnet ein Menü mit Verifizieren (Bestätigen, Unsicher, Ablehnen), "Bearbeiten", "Position verschieben" und Abspielen (Play bzw. "Wiedergabe stoppen"). Ein Langdruck öffnet dasselbe Menü und erlaubt zusätzlich, Beobachtungen als Simultanbeobachtung zu verknüpfen. Abgespielt wird der Ausschnitt der Detektion aus der Session-Aufnahme; fehlt sie, erscheint eine Meldung.
- **Memo-Marker:** Session-Memos mit Position (Kapitel 2) erscheinen als Mikrofon-Marker. Ein Tipp öffnet einen Dialog mit Zeit und Abspielen. Sie sind bei "Alle Sessions" und "Letzte 3" sichtbar, nicht bei "Nur Live".
- **Mehrere Punkte übereinander (Cluster):** Ein Tipp auf einen Cluster zeigt die Liste der Detektionen. Pro Eintrag gibt es Verifizieren, Abspielen und "Position verschieben".
- **Position verschieben:** Die Karte zeigt oben den Hinweis "Karte verschieben: Fadenkreuz auf die neue Position von ..., dann Übernehmen." Die Karte so schieben, dass das Fadenkreuz in der Mitte auf der neuen Position liegt, dann unten "Übernehmen" (oder "Abbrechen"). Danach erscheint eine Snackbar mit **Rückgängig**. Das Verschieben geht auch für Live-Punkte; läuft keine Session, gilt die neue Position nur bis zum Schliessen der App.
- **Artfilter:** Im Kartenmenü steht der Artfilter: "Nur Alarmarten" oder einzelne Arten ausblenden (Alle/Keine). Der Filter gilt bis zum Schliessen der App; Routen bleiben sichtbar.
- **Route:** Eine während der Aufnahme aufgezeichnete Route erscheint als Linie (bei Modus "Nur bei Detektion" gestrichelt, nur an Detektionspunkten). Siehe Kapitel 14.
- **Standort-Knopf:** Zentriert die Karte auf die eigene Position und zoomt so, dass die sichtbare Kartenhöhe etwa 200 m beträgt.
- **Zoom-Knöpfe:** Die +/- Knöpfe sind 50 % grösser und liegen über dem Standort-Knopf.

---

## 9. Referenzen und ähnliche Arten

Verifizierte Detektionen lassen sich als Referenz speichern ("Als Referenz speichern") und wieder entfernen. Der Referenzen-Tab zeigt die gespeicherten Aufnahmen, auch ein Audio-Import ist möglich. Neue Detektionen werden per Embedding mit den Referenzen verglichen.

Settings > Ähnliche Arten & Referenzen: Ähnlichkeitssuche an/aus, Anzahl angezeigter Zeilen (0 = aus, 0.5 = Laufschrift der letzten 5, 1-10 = Liste), Obergrenze gespeicherter Vergleichsaufnahmen.

---

## 10. Watchlist und Alarm

**Watchlist:** Zielarten, die einen Alarm auslösen. Verwaltung unter Settings > Watchlist & Alarm: Art suchen und hinzufügen, entfernen, oder aus `watchlist.json` laden. Gross-/Kleinschreibung und Leerzeichen spielen beim Abgleich keine Rolle.

**Freigegebener Ordner:** Unter "PIROL-Ordner freigeben" einmalig `Downloads/PIROL` wählen. PIROL liest dann `watchlist.json` und die Ordner `lists/` automatisch (Details Kapitel 11). "Beispielliste anlegen" erzeugt eine Vorlage.

**Alarm-Ausgabe** (einzeln ein-/ausschaltbar): Vibration, Benachrichtigung, Vollbild-Hinweis. Auf dem **Sperrbildschirm** erscheint der Vollbild-Alarm nur, wenn "Vollbild-Benachrichtigungen" für PIROL in den Android-Einstellungen erlaubt sind (Knopf "Berechtigung öffnen"). Sonst bleibt es eine Benachrichtigung. Mit "Alarm testen" (nach 5 s, Gerät sperren) lässt sich das prüfen.

Im Alarm kann die Art direkt **bestätigt** werden; "Rückgängig" bleibt danach verfügbar.

**Alarm-Pause:** Mindestabstand zwischen Alarmen derselben Art (0 = jeder Treffer alarmiert).

---

## 11. Artgruppen und Artenlisten

Der Artfilter wirkt je Artgruppe: **Vögel, Heuschrecken, Amphibien, Säugetiere** je **Aus / Alle / Auto / Liste**; **Andere** nur **Aus / Alle** (Standard Aus). "Auto" nutzt das Geomodel (Standort und BirdNET-Woche). Arten, die das Geomodel nicht kennt, sowie Gruppen ohne Geomodel-Maske nehmen die Begleitliste der Gruppe (Vögel: CH-Liste, Heuschrecken und Amphibien: CH-Liste unten, Säugetiere: alle). "Liste" wählt eine der mitgelieferten oder eigenen Listen. Mitgeliefert ist die Liste "CH Heuschrecken & Amphibien" (Daten GBIF, CC BY 4.0).

**Auto ohne GPS:** Fehlt ein aktueller Standort, gilt die zuletzt gespeicherte Position höchstens 30 Tage; danach nimmt "Auto" die Ersatzliste der Gruppe. Beim Start der Aufnahme weist ein Hinweis darauf hin, und in Settings > Arten & Alarm (Geomodel) steht die Grundlage ("Ohne GPS gilt die letzte Position vom ..." bzw. "Keine aktuelle Position: Es gilt die Ersatzliste der Gruppe"). Die gespeicherte Position wird nur bei einer Berechnung mit GPS-Fix aktualisiert. Optional ignoriert die **Jahresliste** die Jahreszeit (Schalter im Geomodel-Abschnitt).

Im Listen-Editor lassen sich Arten nach Ordnung, Familie und Gattung auswählen; "Artnamen wissenschaftlich anzeigen" schaltet die Namensform um.

### Eigene Artenlisten

Eigene Artenlisten können als JSON-Dateien im Ordner `lists/` des freigegebenen PIROL-Ordners abgelegt werden (`Downloads/PIROL/lists/*.json`). Sie erscheinen als wählbare Listen bei den Artgruppen (Option "Liste"). Die Listen werden bei **jedem Aufnahmestart neu eingelesen**; Änderungen gelten also ab der nächsten Aufnahme. Fehlerhafte Listen werden übersprungen und mit Grund gemeldet. Als Vorlage dient "Beispielliste anlegen" (`lists/beispiel_liste.json`).

Alte und neue wissenschaftliche Namen (z.B. nach Taxonomie-Änderungen) werden zusammengeführt, sodass Listen modellübergreifend funktionieren.

---

## 12. Upload

Settings > Upload: Aufgenommene Sessions können automatisch nach der Aufnahme hochgeladen werden.

| Ziel | Angaben |
|------|---------|
| Aus | nur lokale Kopie, manueller Export |
| Ordner | beliebiger Ordner (Gerät, Drive, OneDrive ...) per Ordnerwahl |
| NAS (SMB / Windows-Freigabe) | Host, Freigabe, Pfad, Benutzer, Passwort |

Das NAS-Passwort wird verschlüsselt auf dem Gerät gespeichert. "Verbindung testen" prüft die Angaben. Optionen: nur bei WLAN hochladen, lokale ZIP-Kopie nach dem Upload nicht behalten. Der Status des letzten Uploads steht darunter.

---

## 13. Modelle

Settings > Modell & Erkennung > Modell:

| Modell | Eigenschaften |
|--------|---------------|
| BirdNET 3.0 FP16 (empfohlen) | Standard, 11'725 Klassen, 32 kHz, 3-s-Fenster |
| BirdNET 3.0 FP16 kompakt (pruned) | kleiner (ca. 70 MB), Erkennung praktisch gleich |
| BirdNET 3.0 Preview 3.1 FP16 / FP32 | ältere Vorabversion, 11'560 Klassen |

Das aktive Modell wird per Auswahl umgeschaltet, ab der nächsten Analyse. Die Artenzahl neben dem Modell stimmt nach dem Wechsel sofort. Modelle können heruntergeladen (abbrechen und fortsetzen möglich), importiert und gelöscht werden. Jede Session merkt sich das verwendete Modell.

**Modelltabelle (ab v0.1.1):** Die Spalte Primär (Radio) bestimmt das Hauptmodell. Sekundär (Häkchen, nur im Experten-Modus) wählt das Zweitmodell, das jede Detektion mitprüft; abwählbar, nie gleich dem Primär. Der Papierkorb löscht ein installiertes Modell nach Bestätigung; das Primär lässt sich nicht löschen und ist während einer Aufnahme gesperrt. Nicht geladene Modelle zeigen ein Lade-Symbol mit Fortschritt und Abbrechen in der Zeile.

**Konfidenz-Schwelle:** Der Regler "Konfidenz-Schwelle (alle Arten)" gilt für alle Arten gleich (Standard 50 %). Watchlist-Alarme lösen erst ab dieser Schwelle aus.

**Mindest-Konfidenz:** Die Erkennungs-Schwelle ist global einstellbar. Ein Modell kann zusätzlich eine eigene Mindest-Konfidenz mitbringen (z.B. 80 % für Perch). Es gilt der höhere Wert; das Modell zeigt ihn an.

### Eigene und weitere Modelle importieren
PIROL ist nicht auf BirdNET festgelegt. Über "Anderes Modell importieren..." wird ein Paket aus drei Dateien gemeinsam ausgewählt: `.onnx` (Modell), `.pirol.json` (Profil) und Label-CSV. Die App prüft Prüfsummen, Ein-/Ausgänge und Labelzahl und meldet Fehler im Klartext. Abtastrate und Fensterlänge sind frei.

Geprüft sind **BirdNET 2.4** (6'522 Arten, 48 kHz, etwa halbe Rechenzeit von 3.0), **Perch v2** (Google, 14'795 Klassen, 5-s-Fenster) und **Nighthawk 0.3.1**. Die Dateien sind nicht Teil der App. Für die Umwandlung von TensorFlow-, Keras- und TFLite-Modellen (auch eigene Trainings) liegt das Skript `tools/convert_model.py` bei, siehe `tools/README_convert_model.md` und `tools/MODEL_PROFILE_FORMAT.md`.

**Lizenzen:** BirdNET 3.0 Apache 2.0, Perch v2 Apache 2.0, BirdNET 2.4 und Nighthawk nur nicht-kommerziell (CC BY-NC bzw. BY-NC-SA). Die Lizenz wird beim Import angezeigt.

**Hinweis:** Perch arbeitet mit 32 kHz; Laubheuschrecken im Ultraschall sind damit nicht hörbar.

**Zweitmodell-Statistik (Experten-Modus):** In Settings > Modell & Erkennung öffnet der Knopf "Zweitmodell-Statistik" einen Dialog. PIROL liest dafür alle Sessions (nur die Originale, nur lesend; Rescan-Varianten und manuelle Einträge zählen nicht) und zeigt eine Kreuztabelle: Verifikation (bestätigt, abgelehnt, unsicher, offen, korrigiert/ersetzt) gegen das Ergebnis des Zweitmodells (Geprüft, Nicht prüfbar, Widerspricht, Kein Ergebnis), gesamt und je Zweitmodell. Dazu kommen Fortschrittsbalken zum Ziel (200 bestätigte und 50 abgelehnte Detektionen mit Zweitmodell-Ergebnis) und der Anteil der Widersprüche bei bestätigten und bei abgelehnten Detektionen. Das hilft einzuschätzen, ob das Zweitmodell als Prüfhilfe taugt. Schliessen des Dialogs bricht das Einlesen ab.

**Perch als Zweitmodell:** braucht ca. 650 MB zusätzlichen Speicher. Die App warnt beim Einschalten, wenn das Gerät weniger als 6 GB RAM hat. Das Zweitmodell arbeitet mit dem Rohaudio der Aufnahme, damit die 5-s-Fenster echte Umgebung enthalten.

---

## 14. GPS

Settings > Karte & GPS (bis v0.0.10: Aufnahme & Speicher):

| Einstellung | Bedeutung |
|-------------|-----------|
| GPS-Modus "Dauernd" | laufend Fixes im eingestellten Intervall (2/5/10/20/60 s, Standard 10 s) |
| GPS-Modus "Nur bei Detektion" | GPS nur bei einer Detektion, mindestens 30 s zwischen zwei Fixes; das Intervall gilt dann nicht. Spart rund ein Drittel Akku |
| Max. GPS-Ungenauigkeit | ungenauere Fixes werden verworfen (Standard 50 m) |
| GPS-Smoothing | Median über 5 Fixes, glättet Ausreisser |

Jede Detektion erhält einen frischen Standort, auch bei ausgeschaltetem Bildschirm. Änderungen wirken ab der nächsten Aufnahme.

**Start-Fix:** Beim Programmstart und beim Zoomen der Karte holt PIROL ohne laufende Aufnahme einmalig einen Standort (kein Dauertracking).

**Route:** Die Route ("Route aufzeichnen", Standard an) wird während der Aufnahme mitgeschrieben (`track.jsonl`) und beim Stopp als `track.gpx` neben die Aufnahme gelegt (Zeiten in UTC).

**Gemeinde:** Der Knopf "Gemeinde einfügen" trägt die Gemeinde aus der Position in die Bemerkung ein (im Detail-Dialog, in der Session-Bemerkung und in der Analyse). Die Gemeindedaten (Schweiz) liegen offline in der App. Ausserhalb davon wird nichts ermittelt, ausser der Experten-Schalter Settings > Karte & GPS > Erweitert > "Gemeinde online ermitteln (Google)" ist an (Standard aus; dabei werden die Koordinaten an Google gesendet).

---

## 15. Energie und Akku

Settings > Modell & Erkennung > Energieprofil:

| Profil | Analyse |
|--------|---------|
| Genau | jedes 3-s-Fenster |
| Balanciert | alle 6 s |
| Sparsam | alle 15 s, für lange Feldtage |
| Ultra-Sparsam | alle 30 s, maximale Laufzeit |

**Praxiswerte** (Tablet Galaxy Tab A11+, 6'840 mAh, Daueraufnahme, WLAN an, BirdNET 3.0 FP16; Richtwerte, ca. ±15 %):

| Einstellung | Verbrauch | Laufzeit voller Akku |
|-------------|-----------|----------------------|
| Genau, Bildschirm aus, GPS dauernd (10 s) | ca. 2.2 %/h | ca. 45 h |
| wie oben, 67 % Überlappung | ca. 3.5 %/h | ca. 28 h |
| wie oben, **Bildschirm an** | ca. 9 %/h | ca. 12 h |
| Genau, GPS nur bei Detektion | ca. 1.5 %/h | ca. 60 h |
| Sparsam (15 s), GPS 60 s | ca. 0.9 %/h | ca. 4-5 Tage |

**Tipps:**
1. **Bildschirm ausschalten** ist der grösste Hebel (mehr als doppelt so viel wie Aufnahme und Analyse zusammen). Die Aufnahme läuft im Dienst weiter.
2. GPS-Modus "Nur bei Detektion" wählen.
3. Überlappung bei 0 % lassen, bei langen Einsätzen Profil "Sparsam".
4. Modellwahl spielt kaum eine Rolle (pruned etwa wie final, Preview 3.1 ca. 10 % mehr, FP32 nicht mehr als FP16).

---

## 16. Sprachen

Settings > Sprache: Sprache der App (Systemsprache oder Auswahl): Deutsch, Englisch, Französisch, Italienisch, Katalanisch, Portugiesisch, Niederländisch, Bärndeutsch. **Bärndeutsch und Portugiesisch sind Entwürfe**, fehlende Texte erscheinen auf Deutsch. Die **Artennamen**-Sprache folgt der App-Sprache, kann aber separat gesetzt werden (23 Sprachen). Artnamen erscheinen durchgehend in der gewählten Sprache, auch in Karte, Raven und bei älteren Detektionen (sie werden beim Anzeigen neu aufgelöst, gespeicherte Dateien bleiben unverändert); umbenannte Arten findet PIROL auch unter ihrem alten Namen. Fehlt ein Name in der gewählten Sprache, nimmt PIROL bei Deutsch den deutschen Namen aus den mitgelieferten Artenlisten, bei anderen Sprachen zuerst den englischen Namen.

---

## 17. Einstellungen im Überblick

Die Settings sind in einklappbare Sektionen gegliedert; der Zustand wird gemerkt. Standardmässig offen ist nur "Sprache".

| Sektion | Inhalt |
|---------|--------|
| Sprache | App-Sprache, Artennamen-Sprache |
| Aufnahme | Audio-Info, Preroll, Überlappung, Energieprofil, Speicherort (Ordner, Tages-Unterordner, Auto-Löschen, Sessions verschieben), Max. Aufnahmelänge und Ausschnittlänge, Resampler |
| Modell & Erkennung | Modelle (Download, Import, Löschen), Erkennungs-Schwelle (alle Arten), Alternativen ab, Max. Arten pro Fenster, Voreinstellungen, Artvorschläge, Zusammenfassen von Detektionen, Ähnliche Arten und Embedding-Limit |
| Arten & Alarm | Regionaler Filter und eigene Listen, Watchlist, Alarm-Pause und -Ausgabe, Alarm testen, PIROL-Ordner, Saisonalität und Seltenheit |
| Anzeige | Artnamen-Sprache, Vollbild, Sonogramm (Modus, Voreinstellung, Kontrast, Gamma, Deckel, Palette, Frequenz, Zeitfenster), kompakte Artkarte |
| Karte & GPS | GPS-Modus, Route aufzeichnen, GPS-Intervall, Genauigkeit und Glättung; Experten (Erweitert): "Gemeinde online ermitteln (Google)" |
| Export & Upload | Raven-Export-Felder (inkl. Memo), Datenschutz (menschliche Stimmen ausblenden, Mensch-Schwelle), Upload (Ziel Ordner/NAS, Passwort, Verbindungstest, nur WLAN) |
| Ähnliche Arten & Referenzen | Ähnlichkeitssuche, Zeilenzahl, Vergleichsaufnahmen (Kapitel 9) |
| Info | Version, Quellen, Was ist neu, Diagnose (Benchmark), "PIROL unterstützen" |

Der Schalter **Experteneinstellungen** (unter "Einrichtung starten") blendet im Einfach-Modus alles Seltene aus; im Expertenmodus ist alles sichtbar. Keine Einstellung geht dabei verloren. Seltene Optionen stehen im einklappbaren Unterblock "Erweitert" der jeweiligen Sektion (z.B. Hochpassfilter, Detektions-Cluster, Phänologie, Max. Aufnahmelänge).

**PIROL unterstützen:** In Settings > Info öffnet der Knopf "PIROL unterstützen" die Spendenseite im Browser. Die Spende ist freiwillig; PIROL bleibt kostenlos und vollständig.

**Resampler:** Polyphase (empfohlen) filtert Störgeräusche über 16 kHz, die sonst als Geistersignale erscheinen können. "Linear" ist die alte Methode. Die Umschaltung zeigt einen Hinweis und wirkt ohne Neustart der Aufnahme.

**Diagnose:** Der Benchmark misst die Rechenzeit des aktiven Modells mit 1/2/4 Threads (1-3 Minuten, nicht während einer Aufnahme). Mit "ONNX-Datei wählen und messen" lässt sich auch ein beliebiges Modell vor dem Import testen.

---

## 18. Technische Hinweise

- **Aufnahme:** 48 kHz, 16-bit PCM, Mono (96 kHz im Fledermaus-Modus). Erkennung auf 32 kHz umgerechnet (BirdNET 3.0).
- **Mindestgerät:** Android 8.0. Kompatibel mit 16-KB-Speicherseiten (Android 15+).
- **Fairphone und andere Geräte:** Die Abtastrate wird mit dem Gerät ausgehandelt, bei Problemen weicht die App auf einen anderen Audio-Treiber aus.
- **Datenformate:** `session.json`, `detections.jsonl`, `verifications.jsonl`, Raven-TSV; Zeitstempel in Ortszeit mit Offset.

---

## 19. Änderungen

Die vollständige Liste steht in `CHANGELOG.md` im Projekt.

### Version 0.1.3
- Session-Memos: löschen mit Rückgängig, Ort beim Drücken gespeichert und als Marker auf der Karte, Teilen und Speichern unter (Kapitel 2, 6, 8)
- Import: Datums- und Zeitauswahl, Schalter "Dateizeit = Ende", Scan für alle importierten Sessions, erster Scan wird Original mit Raven im Ordner (Kapitel 6)
- Datumsänderung einer importierten Session benennt den Ordner um und verschiebt ihn in den richtigen Tagesordner (Kapitel 5)
- "+ Art" berücksichtigt Lücken zwischen Segmenten (Kapitel 6)
- Spektrum: Linear (Standard) oder Mel, Gamma auch im Spektrum, Zeitfenster im Oszillogramm (Kapitel 3)
- Analyse: Zoom mit zwei Fingern, Upload meldet sein Ergebnis per Snackbar (Kapitel 6)
- Rescan schneller, Zweitmodell parallel (Kapitel 6)
- Auto-Filter ohne GPS: letzte Position bis 30 Tage, Hinweis beim Start, Grundlage in den Settings, Jahresliste (Kapitel 11)
- Zweitmodell-Statistik im Expertenmodus (Kapitel 13)
- Artnamen durchgehend in der gewählten Sprache, deutsche Namen aus den Artenlisten (Kapitel 16)
- Knopf "PIROL unterstützen" (freiwillig) in Settings > Info (Kapitel 17)
- Hilfemodus deckt mehr Bedienelemente ab, auch Kartenmarker und Dialoge (Kapitel 1)
- Neues Kapitel 20: Installation und Update

### Version 0.1.2
- Angaben importierter Sessions (Startzeit, Ort, Bemerkung) nachträglich bearbeiten (Kapitel 5)
- "Speichern unter" als ZIP oder Ordner (Kapitel 6)
- Raven: eine Zeile pro Episode, neue Spalten `Cluster`, `Windows` und `Memo` (Kapitel 7)
- Rescan und Import: Ergebnisse laufend sichtbar und verifizierbar; beim Rescan behält der Abbruch das Teilergebnis, beim Import löscht er die halb importierte Session (Kapitel 6)
- Menschliche Stimmen werden im Export ausgeblendet, Standard an; Rückfrage bei Modell ohne Mensch-Klasse (Kapitel 7)
- Gemeinde online über Google nur noch als Option, Standard aus (Kapitel 17)
- Session-Memo per Gedrückt-halten, Block "Memos" in der Analyse, Memo-Zeile in Raven (Kapitel 2, 6, 7)
- Live-Artkarten ruhiger: Wechsel höchstens alle 5 s, Treffer in den Top 5 blinken blau (Kapitel 2)
- Hilfe-Knopf "?": Hilfemodus und Anleitung offline (Kapitel 1)
- Anleitung korrigiert: Artfilter im Kartenmenü (Kapitel 8), Settings-Sektionsnamen (Kapitel 1, 5, 7, 17)

---

## 20. Installation und Update

**Welche APK?** Jede Version gibt es als Release auf GitHub (Nikiphedia/Pirol, Bereich "Releases") mit je einer APK pro Prozessor-Architektur:
- `Pirol-<Version>-arm64-v8a-release.apk`: für fast alle aktuellen Telefone und Tablets. Im Zweifel diese Datei nehmen.
- `Pirol-<Version>-armeabi-v7a-release.apk`: nur für ältere 32-Bit-Geräte.

**Installation:**
1. Die passende APK aus dem GitHub-Release auf das Gerät laden und öffnen. Android fragt beim ersten Mal, ob der verwendete Browser oder Dateimanager Apps installieren darf; das einmal erlauben.
2. **Samsung-Geräte:** Die Funktion **"Automatische Sperre"** (Auto Blocker; Einstellungen > Sicherheit und Datenschutz) verhindert die Installation von Apps ausserhalb des Stores. Sie für die Installation kurz ausschalten, PIROL installieren und danach wieder einschalten. Das gilt **bei jedem Update** erneut.
3. Installieren antippen und öffnen. Beim ersten Start führt der Einrichtungsassistent durch die Grundeinstellungen (Kapitel 1).

**Update:** Die neue APK einfach über die bestehende Installation installieren (gleiche Architektur, bei Samsung wieder mit kurz ausgeschalteter Automatischer Sperre). Sessions, Modelle, Listen und Einstellungen bleiben erhalten; nichts muss deinstalliert werden. Hinweise zu älteren Versionen stehen in Kapitel 1 (Update von älteren Versionen). Nach dem Update zeigt PIROL einmalig "Was ist neu".

---

*PIROL v0.1.3 — ETA Systems — Stand 2026-11-25*
