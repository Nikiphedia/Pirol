# BirdNET V3.0 Modell-Assets

## Benoetigte Dateien

### birdnet_v3.onnx
- BirdNET V3.0 ONNX-Modell (~40-50 MB)
- Nicht im Repo enthalten (zu gross)
- Manuell hierher kopieren oder via Build-Script herunterladen
- Quelle: https://github.com/kahst/BirdNET-Analyzer

### birdnet_v3_labels.txt
- Eine Art pro Zeile, Format: `ScientificName_CommonName`
- Platzhalter-Datei mit 25 DACH-Arten enthalten
- Echte Labels-Datei (11560 Arten) muss spaeter ersetzt werden

## Modell-Details

- Input: `[1, 96000]` Float32 — 3 Sekunden @ 32 kHz Mono
- Output: `[1, N]` Float32 — Sigmoid-Scores pro Art (N = Anzahl Labels)
- Zweiter Output (optional): Embeddings `[1, 1024]`
