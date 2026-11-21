# Changelog PIROL

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
