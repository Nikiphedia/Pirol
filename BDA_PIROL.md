# PIROL — Bedienungsanleitung

Akustische Echtzeit-Artenerkennung fuer Android, gedacht fuer die Feldarbeit.

*Gilt ab Version v0.0.9 (Stand 2026-11-19). Was sich zwischen den Versionen geaendert hat, steht in `CHANGELOG.md`.*

Die App hat fuenf Tabs: **Live**, **Analyse**, **Referenzen**, **Karte** und **Settings**.

---

## 1. Erster Start

1. App oeffnen. Das Onboarding fuehrt durch Region, Modell und Berechtigungen (Mikrofon, GPS, Benachrichtigungen).
2. **Region** waehlen (z.B. Schweizer Brutvoegel, Mitteleuropa oder alle Arten). Sie filtert die Artenliste auf plausible Arten.
3. **Modell** herunterladen: Es lassen sich mehrere Modelle auf einmal auswaehlen. Standard ist BirdNET 3.0 FP16 (empfohlen, ca. 170 MB). Der Download laeuft im Hintergrund weiter, kann abgebrochen und spaeter fortgesetzt werden. Alternativ eine Modell-Datei manuell auswaehlen.
4. **Speicherort** pruefen (Settings > Aufnahme & Speicher). Standard: `Downloads/PIROL/`, per USB sichtbar.

**Update von aelteren Versionen:** Ab v0.0.8 ist PIROL signiert. Wer v0.0.7 oder aelter installiert hat, muss einmal deinstallieren und vorher die Sessions per USB sichern (`Android/data/ch.etasystems.pirol/files/PIROL/`). Von v0.0.8 auf v0.0.9 geht das Update ohne Deinstallation, Sessions und Modelle bleiben erhalten. Sessions aus v0.0.6 und aelter werden nicht mehr speziell unterstuetzt.

---

## 2. Live-Erkennung

**Start:** Aufnahme-Knopf im Live-Tab druecken. **Stopp:** Knopf lang druecken. Session und Raven-Export werden automatisch gespeichert.

Aufnahme und Analyse laufen in einem Dienst. Sie gehen weiter, wenn die App in den Hintergrund geht oder der Bildschirm ausgeschaltet wird.

Erkannte Arten erscheinen als Karten unter dem Sonogramm:
- Artname in der gewaehlten Sprache, wissenschaftlicher Name als Zusatz
- Konfidenz in Prozent, Zeitstempel, GPS-Position
- Alternativen (aufklappbar, antippbar)
- Hinweise "Selten", "Saison-untypisch", Watchlist-Markierung

| Knopf | Zustand |
|-------|---------|
| gruen, pulsierend | Preroll-Puffer laeuft (Aufnahme noch nicht gestartet) |
| rot, pulsierend | Aufnahme laeuft |

**Status-Zeile:** Laufzeit in mm:ss. **GPS-Leiste:** aktuelle Genauigkeit in ±m, rot bei laenger als 30 s ohne gueltigen Fix.

**Ruhige Liste:** Eine bereits gesehene Art rueckt nur dann wieder an Platz 1, wenn sie seit mindestens 10 s nicht mehr gehoert wurde.

**Bemerkung:** Pro Aufnahme kann ein Titel eingegeben werden. Er erscheint in der Session-Liste, im ZIP-Namen und im Raven-Export.

**Manuell hinzufuegen:** Eine Art von Hand eintragen (mit Stueckzahl, Atlascode, Bemerkung). Pro Detektion lassen sich ausserdem Stueckzahl, Atlascode, Bemerkung und ein Sprach-Memo erfassen. Eine Art kann gesperrt werden (nur eine Meldung pro Session).

**Zusammenfassen:** Detektionen derselben Art werden raeumlich und zeitlich zu einem Eintrag zusammengefasst (Radius und Zeitfenster einstellbar, Settings > Modell & Erkennung).

### Preroll
Wenn aktiviert, wird Audio **vor** dem Aufnahmestart mitgespeichert (Laenge einstellbar) und auch analysiert. Der Preroll steht lueckenlos am Anfang von `recording.wav`. Das Sonogramm zeigt davon hoechstens 5 s.

### Ueberlappung
Analysefenster koennen sich ueberlappen: 0 % (Standard, Fenster alle 3 s), 50 % (alle 1.5 s) oder 67 % (jede Sekunde). Mehr Ueberlappung erkennt kurze Rufe an Fenstergrenzen besser, braucht aber mehr Rechenzeit und Akku. Ist das Geraet zu langsam, reduziert PIROL die Ueberlappung automatisch und meldet das einmal pro Session in einer Snackbar.

### Lange Aufnahmen (Segmente)
Nach der eingestellten Maximaldauer (Standard 60 min, einstellbar 10-80 min) beginnt automatisch eine neue Datei (`recording-002.wav` usw.). Die Aufnahme laeuft ohne Unterbruch weiter. Zeitangaben in `detections.jsonl` und Raven gelten ab Session-Start. Der WAV-Kopf wird alle 5 s nachgefuehrt: Wird die App hart beendet, gehen hoechstens ca. 3 s Audio verloren.

### Hochpassfilter und Aufbereitung
Der Hochpassfilter (200 Hz) filtert Wind und Trittschall, standardmaessig an. Er wirkt nur auf die Kopie fuer die Erkennung, die gespeicherte Aufnahme bleibt unveraendert. Aenderungen gelten ab der naechsten Aufnahme.

---

## 3. Sonogramm

Im Live- und im Analyse-Tab. Die Frequenzachse zeigt 1/2/4/8/12 kHz. Dargestellt wird bis **12 kHz** (Voreinstellung fuer Voegel).

**Darstellung anpassen:** Langdruck auf das Sonogramm (Live oder Analyse) oeffnet das Einstellungs-Panel unterhalb des Sonogramms; dieselben Regler stehen in Settings > Sonogramm. Das Panel gilt fuer Live, Analyse und Settings gemeinsam.

| Regler | Wirkung |
|--------|---------|
| Voreinstellung | Standard / Leise Rufe / Laute Umgebung |
| Auto-Kontrast | passt die dB-Schwelle laufend dem Signal an; sonst von Hand (Bereich in dB) |
| Intensitaet (Gamma) | kleiner = leise Anteile heller |
| Helligkeits-Deckel | begrenzt starke Signale |
| Farbpalette | Magma, Viridis, Graustufen, **Papier (hell)** |
| Frequenz | angezeigter Bereich in kHz |
| Zeitfenster | Sekunden, die sichtbar sind |

**Papier (hell):** helle Palette wie ein klassisches Papier-Sonagramm, gut bei Tageslicht. Ein Tipp auf das Palettensymbol im Live-Tab wechselt die Palette schnell.

**Modus** (Settings > Sonogramm): Voegel, Fledermaus oder Breitband. Der Fledermaus-Modus nimmt mit 96 kHz auf (Ultraschall); dabei gibt es keinen Preroll.

---

## 4. Verifikation

Pro Detektion stehen diese Aktionen zur Verfuegung (Live-Tab, Analyse-Tab und Karte):

| Aktion | Status | Darstellung |
|--------|--------|-------------|
| Bestaetigen | Bestaetigt | gruener Rand |
| Unsicher (?) | Unsicher | oranger Rand |
| Ablehnen | Abgelehnt | ausgegraut |
| Korrigieren | Korrigiert | Name durch eigene Eingabe ersetzt |
| Alternative waehlen | Ersetzt | Original ausgegraut mit rotem Rand, Alternative erscheint als neue Detektion |

Jede Aktion zeigt eine Snackbar mit **Rueckgaengig** (mindestens 5 s). Das gilt auch fuer das Bestaetigen ueber den Watchlist-Alarm. Alle Aktionen werden in `verifications.jsonl` festgehalten; auch abgelehnte und ersetzte Detektionen bleiben im Export (Audit-Trail).

**Detektion teilen:** Ausschnitt (3 s davor, 5 s danach, Maximallaenge einstellbar) als WAV, M4A (AAC), FLAC (falls das Geraet es kann) oder Sonogramm-PNG mit Beschriftung.

---

## 5. Sessions

Jede Aufnahme erzeugt einen Session-Ordner, standardmaessig `Downloads/PIROL/YYYY-MM-DD/{sessionId}/`. Der Ordnername traegt die Ortszeit.

```
session.json              Metadaten (Dauer, Ort, GPS-Statistik, verwendetes Modell)
detections.jsonl          alle Detektionen
verifications.jsonl       alle Verifikations-Aktionen
recording.wav             Aufnahme (16-bit, Mono, 48 kHz); bei langen Sessions
recording-002.wav ...     weitere Segmente
recording.selections.txt  Raven Selection Table (automatisch beim Stopp)
```

- **Speicherbedarf:** ca. 5.5 MB pro Minute Aufnahme.
- **Zeitstempel:** immer Ortszeit mit Zonen-Offset (z.B. `+02:00`).
- **Auto-Loeschen:** Kurze Sessions ohne Detektion werden geloescht (Mindestdauer einstellbar, 0 = aus).
- **Loeschen** einer Session in der Analyse: mit Bestaetigung.

### Speicherort wechseln
Settings > Aufnahme & Speicher > Speicherort: "Anderen Ordner waehlen" (SAF: interner Speicher, SD-Karte, Cloud-Ordner). Optional mit Tages-Unterordnern. Ist der Ordner nicht erreichbar, speichert PIROL im Fallback-Ordner der App und meldet das.

**Sessions verschieben:** Beim Wechsel fragt PIROL, ob bestehende Sessions an den neuen Ort **kopiert** werden sollen ("Kopieren") oder nur der Ort gewechselt wird ("Nur wechseln").

---

## 6. Analyse-Tab

- **Session-Liste:** nach Datum, mit Dauer, Groesse und Bemerkung.
- **Session oeffnen:** Sonogramm und Detektionsliste mit Zeitangabe. Die gerade abgespielte Detektion ist markiert, ein Zeiger zeigt die Position.
- **Abspielen:** pro Detektion (Ausschnitt mit Zoom) oder die ganze Aufnahme; Segmente werden nahtlos aneinandergereiht. Lange Aufnahmen werden gestreamt.
- **Verifizieren, korrigieren, Art hinzufuegen:** wie im Live-Tab, auch nachtraeglich.
- **Vergleich:** Detektion und Referenz nebeneinander, mit synchroner Wiedergabe.
- **Raven-Datei neu erzeugen**, z.B. nach nachtraeglicher Verifikation.

---

## 7. Export

**Raven Selection Table** (`recording.selections.txt`): wird beim Stopp automatisch neben der WAV geschrieben, Tab-getrennt, UTF-8. Oeffnet in Cornell Raven, Audacity (als Label-Spur) und Sonic Visualiser.

Enthalten sind u.a. Selection, Begin File (bei Segmenten die jeweilige WAV), Begin/End Time (s), Low/High Freq (Hz), Begin Date Time (mit Offset), Species, Confidence, Status. Welche weiteren Spalten geschrieben werden, steht unter Settings > Analyse & Export > Raven-Export Felder. KML-Export gibt es nicht mehr.

**ZIP-Paket:** die Session als ZIP, fuer manuelles Teilen oder den automatischen Upload (Kapitel 12).

---

## 8. Karte

Georeferenzierte Detektionen auf einer Karte. Das Menue oeffnet sich per Wisch vom Rand oder ueber das Kartenmenue-Symbol.

- **Kartenquelle:** Automatisch (swisstopo in der Schweiz, OpenStreetMap ausserhalb), OpenStreetMap, swisstopo Karte, swisstopo Luftbild.
- **Offline:** Fuer swisstopo-Karten laesst sich der sichtbare Ausschnitt herunterladen ("Ausschnitt herunterladen", mit Angabe der Kachelzahl und Groesse). Ein Abbruch behaelt bereits geladene Kacheln; ein erneuter Start laedt nur Fehlendes. "Offline-Karten loeschen" entfernt alle gespeicherten Kacheln.
- **Anzeige:** alle Sessions, "Nur Live" oder "Letzte 3". Abgelehnte Beobachtungen sind ausgeblendet. Ueberlappende Marker werden je nach Zoom gruppiert. Die Karte startet auf dem eigenen Standort.
- **Verifizieren auf der Karte:** Marker antippen, bestaetigen oder ablehnen (mit Rueckgaengig). Langdruck erlaubt, die Position zu verschieben oder Beobachtungen als Simultanbeobachtung zu verknuepfen.

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

## 11. Eigene Artenlisten

Neben der Region koennen eigene Artenlisten als JSON-Dateien im Ordner `lists/` des freigegebenen PIROL-Ordners abgelegt werden (`Downloads/PIROL/lists/*.json`). Sie erscheinen als waehlbare Listen im Artfilter ("Eigene Liste"). Die Listen werden bei **jedem Aufnahmestart neu eingelesen**; Aenderungen gelten also ab der naechsten Aufnahme. Fehlerhafte Listen werden uebersprungen und mit Grund gemeldet. Als Vorlage dient "Beispielliste anlegen" (`lists/beispiel_liste.json`).

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

**Mindest-Konfidenz:** Die Erkennungs-Schwelle ist global einstellbar. Ein Modell kann zusaetzlich eine eigene Mindest-Konfidenz mitbringen (z.B. 80 % fuer Perch). Es gilt der hoehere Wert; das Modell zeigt ihn an.

### Eigene und weitere Modelle importieren
PIROL ist nicht auf BirdNET festgelegt. Ueber "Anderes Modell importieren..." wird ein Paket aus drei Dateien gemeinsam ausgewaehlt: `.onnx` (Modell), `.pirol.json` (Profil) und Label-CSV. Die App prueft Pruefsummen, Ein-/Ausgaenge und Labelzahl und meldet Fehler im Klartext. Abtastrate und Fensterlaenge sind frei.

Geprueft sind **BirdNET 2.4** (6'522 Arten, 48 kHz, etwa halbe Rechenzeit von 3.0), **Perch v2** (Google, 14'795 Klassen, 5-s-Fenster) und **Nighthawk 0.3.1**. Die Dateien sind nicht Teil der App. Fuer die Umwandlung von TensorFlow-, Keras- und TFLite-Modellen (auch eigene Trainings) liegt das Skript `tools/convert_model.py` bei, siehe `tools/README_convert_model.md` und `tools/MODEL_PROFILE_FORMAT.md`.

**Lizenzen:** BirdNET 3.0 Apache 2.0, Perch v2 Apache 2.0, BirdNET 2.4 und Nighthawk nur nicht-kommerziell (CC BY-NC bzw. BY-NC-SA). Die Lizenz wird beim Import angezeigt.

**Hinweis:** Perch arbeitet mit 32 kHz; Laubheuschrecken im Ultraschall sind damit nicht hoerbar.

---

## 14. GPS

Settings > Aufnahme & Speicher:

| Einstellung | Bedeutung |
|-------------|-----------|
| GPS-Modus "Dauernd" | laufend Fixes im eingestellten Intervall (2/5/10/20/60 s, Standard 10 s) |
| GPS-Modus "Nur bei Detektion" | GPS nur bei einer Detektion, mindestens 30 s zwischen zwei Fixes; das Intervall gilt dann nicht. Spart rund ein Drittel Akku |
| Max. GPS-Ungenauigkeit | ungenauere Fixes werden verworfen (Standard 50 m) |
| GPS-Smoothing | Median ueber 5 Fixes, glaettet Ausreisser |

Jede Detektion erhaelt einen frischen Standort, auch bei ausgeschaltetem Bildschirm. Aenderungen wirken ab der naechsten Aufnahme.

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
| Modell & Erkennung | Modelle (Download, Import, Loeschen), Region/Artfilter, Erkennungs-Schwelle, Max. Arten pro Fenster, Hochpassfilter, Artvorschlaege, Energieprofil, Ueberlappung, Resampler, Zusammenfassen von Detektionen |
| Sonogramm | Modus (Voegel/Fledermaus/Breitband), Voreinstellung, Kontrast, Gamma, Deckel, Palette, Frequenz, Zeitfenster |
| Aufnahme & Speicher | Preroll, Max. Aufnahmelaenge pro WAV, Max. Laenge Detektions-Ausschnitt, GPS (Modus, Intervall, Genauigkeit, Smoothing), Speicherort, Tages-Unterordner, Auto-Loeschen, Sessions verschieben |
| Analyse & Export | Raven-Export Felder, Simultanbeobachtungen |
| Watchlist & Alarm | Watchlist, PIROL-Ordner, Saisonalitaet und Seltenheit, Alarm-Pause, Alarm-Ausgabe, Alarm testen |
| Aehnliche Arten & Referenzen | Aehnlichkeitssuche, Zeilenzahl, Obergrenze Referenzen |
| Upload | Ziel (Ordner/NAS), Passwort, Verbindung testen, nur WLAN |
| Diagnose | Inferenz-Benchmark |

**Resampler:** Polyphase (empfohlen) filtert Stoergeraeusche ueber 16 kHz, die sonst als Geistersignale erscheinen koennen. "Linear" ist die alte Methode.

**Diagnose:** Der Benchmark misst die Rechenzeit des aktiven Modells mit 1/2/4 Threads (1-3 Minuten, nicht waehrend einer Aufnahme). Mit "ONNX-Datei waehlen und messen" laesst sich auch ein beliebiges Modell vor dem Import testen.

---

## 18. Technische Hinweise

- **Aufnahme:** 48 kHz, 16-bit PCM, Mono (96 kHz im Fledermaus-Modus). Erkennung auf 32 kHz umgerechnet (BirdNET 3.0).
- **Mindestgeraet:** Android 8.0. Kompatibel mit 16-KB-Speicherseiten (Android 15+).
- **Fairphone und andere Geraete:** Die Abtastrate wird mit dem Geraet ausgehandelt, bei Problemen weicht die App auf einen anderen Audio-Treiber aus.
- **Datenformate:** `session.json`, `detections.jsonl`, `verifications.jsonl`, Raven-TSV; Zeitstempel in Ortszeit mit Offset.

---

## 19. Aenderungen

Die Liste der Aenderungen pro Version steht in `CHANGELOG.md` im Projekt.

---

*PIROL v0.0.9 — ETA Systems — Stand 2026-11-19*
