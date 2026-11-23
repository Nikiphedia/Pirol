# Changelog PIROL

## v0.1.1 — 2026-11-23

Update ueber v0.0.11 ohne Deinstallation (gleiche Signatur); Sessions und Modelle bleiben erhalten. Nach dem Update zeigt die App einmalig "Was ist neu" (auch unter Einstellungen > Info).

### Hauptunterschied zu v0.0.11
**Zweites Modell und Artgruppen:** Ein Zweitmodell (z.B. Perch v2 oder BirdNET 2.4) prueft jede Detektion mit und zeigt Prozent bzw. Rang. Die Artenerkennung laesst sich nach Artgruppen einschraenken (Voegel, Heuschrecken, Amphibien, Saeugetiere, Andere). **Achtung:** Alte Filtereinstellungen werden auf Artgruppen umgestellt (Voegel Auto, Rest Aus). Nach dem Update pruefen; Konfidenz-Schwelle und "Alternativen ab" ebenfalls pruefen. Perch als Zweitmodell braucht ca. 650 MB zusaetzlich (Warnung bei weniger als 6 GB RAM).

### Erster Start und Einstellungen
- Einrichtungsassistent: Sprache, Niveau (Einsteiger/Experte), Einsatzzweck, Artenliste, Modell und Geomodel, Berechtigungen, Zusammenfassung (T226, T227, T232, T241)
- Schalter "Experteneinstellungen": aus zeigt das Wichtigste, an zeigt alles; keine Einstellung geht verloren (T227, T241)
- Einstellungen auf Sektionsdateien aufgeteilt; Modelltabelle mit Primaer/Sekundaer, Loeschen und Laden (T213, T223, T224, T231, T241, T253)

### Live-Tab
- Zweitmodell: Prozent bzw. Rang ("Perch #2") auf Karte und kompakter Karte (T224, T253)
- Alternativen mit eigener Untergrenze "Alternativen ab" 5/10/20/30 % (T225)
- Alarm-Knopf auf der Artkarte: dauerhaft oder fuer 24 h, 24-h-Alarme mit Abzeichen (T244, T247)
- Kompakt-Knopf: Artkarten kompakt oder ausfuehrlich, Alternativen auf- und zuklappbar (T229, T230)
- Alternative waehlen ersetzt die Art der Detektion (gleiche Zeile); Rueckgaengig in der Snackbar (T237)
- Aufnahme-Start und -Stopp in Folge sauber getrennt; Speichern gebuendelt (T250, T251)

### Analyse
- Rescan mit Modell, Liste bzw. Artgruppen, Schwelle, Overlap und Zweitmodell; laeuft als Vordergrund-Dienst mit Abbrechen (T221, T248, T250, T252)
- Audio-Import: PIROL-Session als ZIP oder Ordner, Audiodateien (WAV, FLAC, MP3, M4A, OGG) mit Startzeit (T255)
- Episoden einzeln teilen (T222)
- Kopfzeile mit Datum, Gemeinde und Bemerkung; Kompakt/Gross-Umschalter (T254)
- Upload nebenlaeufig und sicher (Namensreservierung, Cache-ZIP) (T234)

### Artgruppen und Artenlisten
- Filter je Artgruppe: Voegel, Heuschrecken, Amphibien, Saeugetiere je Aus/Alle/Auto/Liste; Andere Aus/Alle (T232, T246)
- Auto nutzt das Geomodel (Standort und Woche); Arten ohne Geomodel-Angabe nehmen die Begleitliste der Gruppe (T218, T239)
- Mitgelieferte Liste "CH Heuschrecken & Amphibien" (GBIF, CC BY 4.0) (T228)
- Listen-Editor: Auswahl nach Ordnung, Familie, Gattung; wissenschaftliche Namen umschaltbar; Artenlisten v4 (T189, T190, T220, T235)

### Export
- Dateinamen `YYYYMMDDHHMM_Art_Bemerkung` beim Teilen, `YYYYMMDDHHMM_Bemerkung.zip` fuer Sessions (T233, T234)
- Raven-Export mit Spalte "Second Rank" (T253)

### Karte
- Zoom-Knoepfe 50 % groesser (T245)
- Sonogramme nutzen die echte Abtastrate der Aufnahme (T247)

### Robustheit und Technik
- Atomares Speichern (Schattenkopie bei SAF), Session-Updates waehrend der Aufnahme append-only (T236, T249, T251)
- Filterzustand atomar, Main-Thread entlastet, Regionsfilter vor Top-N (T238)
- Modellwechsel-Absturzschutz, Thread-Sicherheit der Filter, Watchlist unter einem Mutex (T239, T250)
- Lint-Fehler von 285 auf 0 gesenkt; zwei Code-Reviews umgesetzt (T243, T249–T251)
- Ungenutzter Code und Strings entfernt (T240)
- Speicherwarnung vor dem Zweitmodell bei wenig RAM (T254)

## v0.0.11 — 2026-11-23

Update ueber v0.0.10 ohne Deinstallation (gleiche Signatur); Sessions und Modelle bleiben erhalten. Nach dem Update zeigt die App einmalig "Was ist neu" (auch unter Einstellungen > Info).

### Hauptunterschied zu v0.0.10
**Mehrfacherkennung und neue Anzeige:** Im selben 3-s-Fenster werden alle Arten erkannt, die die Konfidenz-Schwelle erreichen. Die Live-Anzeige wechselt zwischen Sonogramm, Spektrum und Oszillogramm. **Achtung:** Die Schwelle gilt neu gemeinsam fuer alle Arten (Standard 50 %). Den Regler "Konfidenz-Schwelle (alle Arten)" nach dem Update pruefen; Watchlist-Alarme loesen erst ab dieser Schwelle aus.

### Live-Tab
- Mehrfacherkennung: alle Arten im Fenster, die die gemeinsame Konfidenz-Schwelle erreichen (T207, T211)
- Anzeige-Modi Sonogramm, Spektrum und Oszillogramm, umschaltbar im Anzeige-Panel (T193)
- Spektrum mit echter FFT: dBFS, lineare kHz-Skala, Cursor, Mittelung, Peak-Halten mit verblassenden Spitzen (T198, T201)
- Oszillogramm mit Pegelmodi, Bandpass, Peak/RMS, rote Markierung bei Uebersteuerung, Cursor und Einfrieren (T200, T201)
- Sonogramm in dBFS und bis 24 kHz; Frequenz, Dynamik und Farbmuster gelten fuer alle drei Ansichten gemeinsam (T199, T200)
- Option "Artkarte kompakt" mit einer Zeile und "+"-Menue fuer seltene Aktionen (T192)

### Analyse
- Artsuche in der Session-Liste und innerhalb einer Aufnahme (T202)
- Kopfzeile mit Wiedergabe-Position, Play, Raven und Upload; Wiedergabeleiste mit vorige/naechste Detektion (T202)
- Tippen im Sonogramm oder Oszillogramm springt zur Stelle; ohne laufende Wiedergabe wird die Startposition gemerkt (T210)
- Spektrum und Oszillogramm wie im Live-Tab, mit Abspielzeiger im Oszillogramm (T205, T211)
- "+ Art" uebernimmt die Zeit aus der Abspielposition (+/-10 s); der Ort laesst sich auf der Karte waehlen (T203)
- Uebersichtsleiste und Raven-Tabelle (T194)
- Mehrfach erkannte Arten zeigen das beste Fenster fuer Raven-Export und Zeitangaben (T186)

### Karte
- Legende als Filter: einzelne Arten ausblenden oder "Nur Alarmarten" (T204, T204b)

### GPS und Route
- Route wird waehrend der Aufnahme mitgeschrieben (`track.jsonl`) und beim Stopp als `track.gpx` (Zeiten UTC) abgelegt (T191)
- GPS-Standort beim Programmstart und beim Karten-Zoom, ohne laufende Aufnahme (T206)
- Gemeinde per Knopf in Bemerkung einfuegen: im Detail-Dialog, in der Session-Bemerkung und in der Analyse (T195, T197)

### Einstellungen
- Neu in 8 Sektionen gegliedert, seltene Optionen im einklappbaren Unterblock "Erweitert" (T209)
- Vollbild-Anzeige (T195)
- Resampler-Umschaltung mit Hinweis (T208)

### Installation und Technik
- Modell wird 45 s nach dem Wechsel in den Hintergrund freigegeben und bei Bedarf neu geladen; spart Akku (T187)
- Embedding-Datenbank robust: eine defekte Datei wird als `.corrupt` beiseitegelegt (T196)
- Embeddings nur noch fuer Fenster ueber der Schwelle (weniger Rechenaufwand) (T211)
- Artenlisten-Format v4 (Spezifikation `tools/SPECIES_LIST_FORMAT.md`) (T188)

## v0.0.10 — 2026-11-21

Update ueber v0.0.9 ohne Deinstallation (gleiche Signatur); Sessions und Modelle bleiben erhalten. Nach dem Update zeigt die App einmalig "Was ist neu" (auch unter Einstellungen > Info).

### Hauptunterschied zu v0.0.9
**Feldtauglich auf dem Smartphone:** Der Live-Tab ist hoch und quer deutlich kompakter, Detektionen lassen sich direkt
auf der Artkarte, in der Analyse und auf der Karte anhoeren, und das Mikrofon ist waehrend der Wiedergabe ueber
Lautsprecher stumm. Mit eingeschaltetem Bildschirm braucht die App rund zwei Drittel weniger Rechenleistung.

### Live-Tab
- Kompaktes Layout fuer Telefone hoch und quer: keine Titelzeile, schmale Navigation nur mit Symbolen, Knoepfe der Artkarte auf einer Zeile, seltene Aktionen im Menue (T164, T170, T174)
- Detektion auf der Artkarte abspielen (T164)
- Mikrofon stumm waehrend Wiedergabe ueber Lautsprecher, auch im Vorlauf-Puffer; mit Kopfhoerer bleibt es an (T168, T182)
- Erneut erkannte Art: Karte blinkt kurz auf (T178)
- Bestaetigen/Unsicher/Ablehnen ohne Popup, Rueckgaengig direkt auf der Karte (T174)
- Hinweis "lange druecken zum Stoppen" oben statt unter dem Finger (T172)
- Aufnahme-Knopf: Symbol bei dynamischen Farben sichtbar (T169)

### Sonogramm
- Zeitfenster bis 30 s (T163)
- Wird nach einem Tab-Wechsel sofort aus den letzten Sekunden nachgerechnet; robust gegen Ausfall nach der Rueckkehr (T163, T176)
- Deutlich sparsamer: nur neue Spalten zeichnen, keine Dauer-Animation am Aufnahme-Knopf, keine Berechnung bei verdeckter Ansicht (T160, T163)
- Einstellungs-Panel mit Kacheln und "Fertig" oben (T177); Einstellungsaenderungen wirken auch waehrend der Aufnahme (T182)

### Wiedergabe
- Ein Player fuer die ganze App: zweiter Tipp stoppt, neuer Eintrag stoppt den alten, Tab-Wechsel stoppt (T173)

### Analyse
- Mehrfach erkannte Arten aufklappbar, zusammenhaengende Rufe als Episode am Stueck abspielbar, mit Konfidenz je Episode (T167, T171, T181)
- Aufnahmen mehrfach auswaehlen (Langdruck): in einen frei waehlbaren Ordner verschieben oder loeschen (T180, T181)

### Karte
- Punkt antippen: Verschieben mit Fadenkreuz und Rueckgaengig, Abspielen; verschobene Positionen bleiben nach dem Neuladen erhalten (T165, T173)
- Standort-Knopf zeigt einen Ausschnitt von ca. 200 m Hoehe (T175)

### Installation und Technik
- Release-APK pro Architektur: `arm64-v8a` (aktuelle Geraete, 27 MB statt 65 MB) und `armeabi-v7a` (alte 32-Bit-Geraete) (T159)
- Neues App-Symbol (T179)
- "Was ist neu"-Dialog nach Updates (T183)
- CI-Actions auf Node 24 (T162)

### Daten
- `verifications.jsonl`: CLUSTER_MERGED traegt zusaetzlich `conf` und `start` des absorbierten Fensters (additiv, alte Eintraege bleiben lesbar) (T181)
- Uebrige Formate (detections.jsonl, Raven, session.json) unveraendert

## v0.0.9 — 2026-11-19

Update ueber v0.0.8 ohne Deinstallation (gleiche Signatur); Sessions und Modelle bleiben erhalten.

### Hauptunterschied zu v0.0.8
**Eigene und weitere Modelle:** PIROL ist nicht mehr auf BirdNET 3 festgelegt. Jedes Modell laesst sich als
`.onnx` + Profil-JSON (`pirol-model/1`) + Label-CSV importieren, mit beliebiger Abtastrate und Fensterlaenge.
Geprueft auf dem Tablet: BirdNET 2.4, Perch v2 (Google) und Nighthawk. Ein Konvertierungsskript macht aus
TensorFlow-/Keras-/TFLite-Modellen importierbare Pakete — auch fuer selbst trainierte Modelle.

### Erkennung und Modelle
- Generische Modellschnittstelle: Import `.onnx` + `.pirol.json` + Labels per Mehrfachauswahl, Pruefung von Pruefsummen, Ein-/Ausgaengen und Labelzahl (T151)
- `tools/convert_model.py`: TF/Keras/TFLite -> ONNX (optional FP16), erzeugt Profil-JSON, vergleicht Original und ONNX (T151)
- BirdNET 2.4 (6'522 Arten, 48 kHz, halb so viel Rechenzeit wie 3.0), Perch v2 (14'795 Klassen, 5-s-Fenster), Nighthawk 0.3.1 als Beispielprofile in `tools/examples/` (T147, T148, T151)
- Mindest-Konfidenz pro Modell (`minConfidence`), z.B. Perch 80 % (T156)
- Benchmark beliebiger ONNX-Dateien unter Settings > Diagnose (T146)

### Akku und GPS
- GPS-Modus "Nur bei Detektion": GPS nur bei einer Detektion, mindestens 30 s zwischen zwei Fixes — rund ein Drittel weniger Akkuverbrauch (T152)
- Akkumessung: ca. 2 %/h bei Daueraufnahme mit ausgeschaltetem Bildschirm, ca. 1.5 %/h mit GPS bei Detektion (`Bericht_T149_Akku.md`) (T149)

### Bedienung
- Sonogramm bis 12 kHz (vorher 8 kHz), Frequenzachse mit 1/2/4/8/12 kHz in Live- und Analyse-Tab (T144)
- Helle Palette "Papier" wie ein Papier-Sonagramm, gut bei Tageslicht; Palettennamen uebersetzt (T144, T155)
- Onboarding: mehrere Modelle auf einmal auswaehlen und herunterladen; Artenzahl passend zum Modell (T145)
- Settings zeigen nach Modellwechsel sofort die richtige Artenzahl (T145)
- "Rueckgaengig" auch nach Bestaetigen ueber den Watchlist-Alarm (T157)
- Eigene Artenlisten werden bei jedem Aufnahmestart neu eingelesen (T157)

### Daten
- Session-Ordner tragen die Ortszeit im Namen statt UTC (T155)
- Datenformate (detections.jsonl, verifications.jsonl, Raven, session.json) unveraendert; `modelId` nennt das verwendete Modell

### Hinweise
- Lizenzen der Zusatzmodelle: BirdNET 2.4 und Nighthawk nur nicht-kommerziell (CC BY-NC bzw. BY-NC-SA), Perch v2 Apache 2.0. Die Modelldateien sind nicht Teil der App.
- Laubheuschrecken im Ultraschall sind fuer Perch (32 kHz) nicht hoerbar.

## v0.0.8 — 2026-11-19
Siehe GitHub-Release "PIROL V0.0.8": BirdNET 3.0 final als Standard, Preroll in WAV, Segmente, Pipeline im Dienst,
8 Sprachen, Karte, Watchlist-Alarm auf Sperrbildschirm, NAS-Upload, 16-KB-Seiten, erste signierte Version.
