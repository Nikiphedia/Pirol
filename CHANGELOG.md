# Changelog PIROL

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
