# Offline-Auswertung Zweitmodell (T213)

Werkzeug fuer die Go/No-Go-Entscheidung "BirdNET 2.4 als Zweitmodell" (Konzept: Bericht T150). Liest PIROL-Sessions nur
lesend, schneidet das beste Fenster jeder verifizierten Detektion aus der WAV, rechnet BirdNET 2.4 und vergleicht mit
der Verifikation.

## Voraussetzungen

- Python 3, `numpy`. Fuer `--evaluate` zusaetzlich `onnxruntime` (`pip install -r requirements.txt`, am besten in einer venv).
- `--count` braucht nur die Standardbibliothek.

## Sessions vom Geraet holen

Nur `session.json`, `detections.jsonl`, `verifications.jsonl` und die WAV-Segmente (`recording*.wav`) werden gebraucht:

```
adb pull /sdcard/Download/PIROL/2026-11-19 D:\Sessions\2026-11-19
```

(Der ganze Tagesordner geht auch; `recording.selections.txt`, `track.*` usw. werden ignoriert.)

## Aufruf

Zaehlen (kein Modell noetig):

```
python evaluate.py --count D:\Sessions
```

Auswerten (Zweitmodell mit Profil-JSON; Abtastrate, Fenster, Transform und Labels kommen aus dem Profil):

```
python evaluate.py --evaluate D:\Sessions --model birdnet24_global6k.onnx ^
    --profile ..\examples\birdnet24_global6k.pirol.json --csv ergebnis.csv
```

Oder ohne Profil (Standard: 48 kHz, 3 s, Sigmoid):

```
python evaluate.py --evaluate D:\Sessions --model birdnet24_global6k.onnx --labels birdnet24_global6k_species.csv
```

Das Argument `sessions` nimmt Session-Ordner oder Ordner, die Sessions enthalten (rekursiv). Optionen:
`--with-merged` (zusaetzlich absorbierte Cluster-Fenster rechnen, Maximum zaehlt), `--aliases` (Standard:
`app/src/main/assets/species_aliases.json`), `--min-pos/--min-neg` (Mindestmenge, Standard 200/50).

## Wahrheit und Ausgabe

- Letzter Status je Detektion aus `verifications.jsonl` (UNDO traegt den wiederhergestellten Status; Metadaten-Events wie
  CLUSTER_MERGED aendern ihn nicht). CONFIRMED = richtig; REJECTED und CORRECTED = falsch fuer die Ursprungsart;
  UNCERTAIN, unverifiziert und manuelle Eintraege zaehlen nicht.
- Fenster: `bestStartSec`, sonst `chunkStartSec` (session-relativ, Segmente `recording-00N.wav` werden ueber
  `recordingSegments` zusammengesetzt). Peak-Normalisierung auf 0.95; bei anderer Abtastrate lineares Resampling.
- Artabgleich wie `SpeciesKey` (Unterstrich/Leerzeichen, Kleinschreibung, Aliasliste). Art nicht im Zweitmodell = "nicht pruefbar".
- CSV je Detektion (Score, 2.4-Top-1) plus Zusammenfassung: Fehler-Trefferquote und Fehlalarmquote je Schwelle (gesamt und
  bei 3.0-Konfidenz > 0.7), AUC 3.0 allein / 2.4 / Kombination, Anteil nicht pruefbar, Schwellenvorschlag (Youden).
- **Go**: AUC der Kombination +0.05 gegenueber 3.0 allein, **oder** mindestens ein Drittel der Fehler bei <= 10 %
  Fehlalarm (nur 3.0-Konfidenz > 0.7); in beiden Faellen "nicht pruefbar" <= 10 %. Unter der Mindestmenge: "ZU WENIG DATEN".

## BirdNET 2.4 als ONNX

Das Modell ist nicht im Repo (Lizenz CC BY-NC-SA 4.0). Quelle: Zenodo-Record 15050749 (Protobuf-SavedModel);
Konvertierung nach ONNX mit `tools/convert_model.py` (Anleitung `tools/README_convert_model.md`, Ablauf und Pruefwerte in
Bericht T147). Das fertige Profil liegt als `tools/examples/birdnet24_global6k.pirol.json`, die Labels als
`birdnet24_global6k_species.csv` (vom Konvertierungsskript erzeugt).

## Tests

```
python -m unittest test_evaluate -v
```
(ohne Modell, ohne onnxruntime; nicht mit `python -I`, sonst wird das Modul nicht gefunden)
