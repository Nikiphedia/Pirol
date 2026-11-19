# Profil-Format pirol-model/1 (eigene Modelle fuer PIROL)

Modelle werden als `<id>.onnx` + `<id>.pirol.json` + Label-CSV in PIROL importiert (Settings > Modell & Erkennung > "Anderes Modell importieren..."). Erzeugen mit `tools/convert_model.py` (Anleitung `README_convert_model.md`), Beispiele in `examples/`.

## Vertrag: Profil-JSON `pirol-model/1`

Dateiname: `<id>.pirol.json`, liegt neben `<modelFile>`. UTF-8. Unbekannte Felder werden ignoriert.

```json
{
  "schema": "pirol-model/1",
  "id": "nighthawk_v1",                  // [a-z0-9_]+, eindeutig; landet als modelId in session.json
  "name": "Nighthawk 1.0",               // Anzeige
  "family": "nighthawk",                 // frei; gleiche Familie = verwandte Modelle
  "runtime": "onnx",                     // nur "onnx" wird akzeptiert
  "modelFile": "nighthawk_v1.onnx",
  "modelSha256": "<hex>",                // Pflicht; wird beim Import geprueft
  "license": "…",                        // Pflicht, wird in der App angezeigt
  "input": {
    "name": "inputs",                    // Eingangsname im ONNX-Graph
    "sampleRate": 22050,                 // Hz; 48000 = ohne Resampling
    "windowSec": 1.0,                    // Fensterlaenge; Eingang = round(sampleRate*windowSec) Samples
    "layout": "waveform"                 // nur "waveform": Float32 [1, N] bzw. [N], Werte -1..1
  },
  "outputs": [
    { "name": "species", "role": "scores", "transform": "sigmoid", "labels": "species" },
    { "name": "family",  "role": "aux",    "transform": "sigmoid", "labels": "family" },
    { "name": "embedding", "role": "embedding" }
  ],
  "labels": {
    "species": {
      "file": "nighthawk_v1_species.csv",
      "sha256": "<hex>",
      "delimiter": ",",                  // "," ";" oder "\t"
      "header": true,
      "scientificColumn": "scientific_name",   // Name (bei header) oder 0-basierter Index
      "commonColumn": "common_name"            // optional
    }
  }
}
```

Regeln:
- Genau **ein** Ausgang mit `role: "scores"`; er erzeugt die Detektionen. `transform`: `none` | `sigmoid` | `softmax`.
- Optional (T156) am `scores`-Ausgang: `"minConfidence": 0.8` (0.0-1.0, sonst Importfehler). Wirksame Schwelle = max(globale Erkennungs-Schwelle, minConfidence); fehlt das Feld, gilt nur die globale. Beispiel: `{ "name": "label", "role": "scores", "transform": "softmax", "labels": "species", "minConfidence": 0.8 }`
- `role: "aux"` (z.B. Ordnung/Familie bei Nighthawk) wird geladen und geprueft, aber in T151 **nicht** ausgewertet (nur Log). Spaetere Nutzung offen.
- `role: "embedding"` optional; ohne Embedding keine Aehnlichkeitssuche fuer dieses Modell. Embedding-DB-Datei: `embeddings_<id>.bsed`.
- Labelzeile n (nach Kopfzeile) = Klasse n des zugehoerigen Ausgangs. Anzahl muss zur Ausgangsgroesse passen.
- Artnamen laufen wie bisher ueber `SpeciesKey.of(scientificName)`; Namen in der App-Sprache via `SpeciesNameResolver`, sonst `commonColumn`, sonst wissenschaftlich.
- Layouts ausser `waveform` (z.B. Spektrogramm-Eingang) sind nicht vorgesehen: Vorverarbeitung gehoert ins ONNX-Modell (Aufgabe des Konvertierungsskripts).
- Datenformate bleiben: detections.jsonl/verifications.jsonl/Raven unveraendert (AMSEL-kompatibel); session.json `modelId` = `id`.
