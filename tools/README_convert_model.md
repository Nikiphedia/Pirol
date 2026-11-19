# convert_model.py: eigene Modelle fuer PIROL konvertieren

Das Skript wandelt ein TensorFlow-SavedModel, Keras-Modell (`.keras`, `.h5`) oder TFLite-Modell
nach ONNX um, schreibt das PIROL-Profil (`<id>.pirol.json`, Format `pirol-model/1`, siehe
`Arbeitspaket_T151.md`) und prueft, dass das ONNX dasselbe rechnet wie das Original.

## Installation

Python 3.11 verwenden (TensorFlow 2.15 gibt es nicht fuer 3.13/3.14):

```powershell
py -3.11 -m venv venv
.\venv\Scripts\python.exe -m pip install -r tools\requirements-convert.txt
```

## Beispiel (Nighthawk)

```powershell
.\venv\Scripts\python.exe tools\convert_model.py nighthawk\saved_model_with_preprocessing `
  --out-dir out --id nighthawk_v1 --name "Nighthawk 0.3.1" --family nighthawk `
  --license "CC BY-NC 4.0" --sample-rate 22050 --window-sec 1.0 `
  --scores-output output_4 --transform sigmoid `
  --labels species.csv --scientific-column scientific_name --common-column common_name `
  --aux-output output_3:groups.csv:sigmoid --aux-output output_2:families.csv:sigmoid `
  --aux-output output_1:orders.csv:sigmoid `
  --test-audio aufnahme.wav
```

Die Namen der Ausgaenge stehen in der Konsole ("ONNX Ausgaenge"). Stimmt `--scores-output` nicht,
bricht das Skript mit der Liste der vorhandenen Namen ab. Bei SavedModels sind es die Schluessel der
Signatur (`tf.saved_model.save(..., signatures=...)`): dort sprechende Namen vergeben.

Weitere Optionen:

| Option | Bedeutung |
|---|---|
| `--embedding-output NAME` | Ausgang als Embedding eintragen (App: Aehnlichkeitssuche) |
| `--fp16` | Gewichte in Float16 (Datei etwa halb so gross), Ein-/Ausgang bleibt Float32; Pruefung dann nur Top-1 und Toleranz x10 |
| `--opset N` | ONNX-Opset, Default 17 (passt zu onnxruntime-android 1.22) |
| `--labels-format birdnet24` | Labels als Textdatei `Wissenschaftlich_Common` pro Zeile ohne Kopf (BirdNET 2.4); wird in `scientific_name,common_name` umgewandelt, `--scientific-column` entfaellt |
| `--no-header` | Labeldateien ohne Kopfzeile; Spalten dann als 0-basierter Index angeben |
| `--preprocess datei.py` | siehe Abschnitt Vorverarbeitung |
| `--tol X` | erlaubte max. Abweichung (Default 1e-3) |

Labeldateien: erste Zeile = Kopfzeile, danach pro Zeile eine Klasse in der Reihenfolge der Modellausgabe.
Das Trennzeichen (`,` `;` Tab) wird erkannt. Bei Zusatzausgaengen (`--aux-output`) gilt die erste Spalte als Name.

## Beispiel BirdNET 2.4 (T147)

Die TFLite-Datei von Zenodo laesst sich mit tf2onnx nicht umwandeln (RFFT-Knoten der Mel-Schicht). Stattdessen das
Protobuf-Modell (`BirdNET_v2.4_protobuf.zip`, Zenodo 15050749) nehmen; es hat die Signaturen `basic` (Scores) und
`embeddings`. `make_birdnet24_savedmodel.py` fasst beide zu einem SavedModel zusammen:

```powershell
.\venv\Scripts\python.exe tools\make_birdnet24_savedmodel.py pb\audio-model combined
.\venv\Scripts\python.exe tools\convert_model.py combined --out-dir out --id birdnet24_global6k `
  --name "BirdNET 2.4 (Global 6K)" --family birdnet --license "CC BY-NC-SA 4.0" `
  --sample-rate 48000 --window-sec 3.0 --scores-output scores --embedding-output embeddings `
  --transform sigmoid --labels labels\en_us.txt --labels-format birdnet24 --test-audio ausschnitt.wav
```

Ausfuehrliche Angaben: `Bericht_T147_BirdNET24.md`.

## Ausgabe

Im `--out-dir`: `<id>.onnx`, `<id>.pirol.json`, `<id>_species.csv` (und `<id>_<ausgang>.csv` fuer
Zusatzausgaenge) sowie `<id>_bericht.md` (Pruefergebnis, SHA-256 aller Dateien). Exit-Code 0 nur,
wenn alle Pruefungen bestehen.

## Was geprueft wird

- **Eingangslaenge** im ONNX = round(Abtastrate x Fensterdauer), Float32, Rang 1 oder 2.
- **Ausgangsform**: alle im Profil genannten Ausgaenge muessen `[1, N]` sein (die App liest nur das).
  Das Skript formt andere Raenge (z.B. `[1,1,N]`) selbst um und meldet es; bleibt ein Ausgang nicht `[1,N]`, ist das ein Fehler.
- **Labelanzahl** = Groesse des Ausgangs (sonst passen Namen und Klassen nicht zusammen).
- **Max. Abweichung** pro Ausgang zwischen Originalmodell (TensorFlow) und ONNX auf demselben
  Testfenster. Float32: sollte unter 1e-4 liegen (Grenze 1e-3). Deutlich mehr heisst, dass die
  Konvertierung etwas anders rechnet (z.B. Vorverarbeitung): Modell dann nicht verwenden.
- **Top-5 identisch**: dieselben fuenf wahrscheinlichsten Klassen in gleicher Reihenfolge (entfaellt bei Ausgaengen mit weniger als 5 Klassen).
- Testsignal: `--test-audio` (16-bit-WAV; das lauteste Fenster wird verwendet), sonst Rauschen + 3-kHz-Sinus.
  Echtes Vogelaudio ist aussagekraeftiger.

## Vorverarbeitung

PIROL liefert dem Modell nur Roh-Audio (`layout: "waveform"`, Float32, -1..1). Erwartet das Originalmodell
ein Spektrogramm, bricht das Skript mit einer klaren Meldung ab. Zwei Wege:

1. Das Modell mit eingebauter Vorverarbeitung exportieren (so ist das Nighthawk-SavedModel
   `saved_model_with_preprocessing` aufgebaut: STFT, Mel, Resize stecken im Graph).
2. `--preprocess datei.py` mit einer Funktion `preprocess(x)` in TensorFlow-Operationen
   (`[1, N]` Roh-Audio -> Eingang des Modells). Sie wird vor der Konvertierung eingebaut. Geht fuer
   SavedModel und Keras (bei TFLite die Vorverarbeitung ins Modell einbauen). Der Vergleich
   Original/ONNX laeuft dann ebenfalls mit Roh-Audio.

## Bekannte Eigenheiten (tf2onnx 1.16.1)

Das Skript behebt automatisch und meldet in der Konsole:

- **SAME-Padding** bei Convs/Pools mit Stride > 1: explizite Pads werden durch `auto_pad=SAME_UPPER` ersetzt.
- **Resize**: `tf.image.resize` wird mit Float-Skalierung exportiert, ORT rundet damit eine Spalte ab; das Skript
  setzt die Zielgroesse (`sizes`) fest.
- Ungenutzte Zusatz-Eingaenge im Graph werden entfernt.
- **RFFT mit Cast auf float** (BirdNET 2.4: nur Realteil des Spektrums): tf2onnx laesst einen ungueltigen `RFFT`-Knoten stehen;
  das Skript ersetzt ihn durch ONNX-`DFT` (opset 17, Realteil).
- TFLite-Eingaben gehen nur, wenn tf2onnx alle Operatoren kennt (Mel-Schicht mit RFFT nicht): dann das SavedModel/Keras-Original verwenden.

Ohne diese Korrekturen laedt das Nighthawk-ONNX nicht oder weicht um bis zu 0.5 ab. Die Abweichungspruefung
faengt aehnliche Probleme bei anderen Modellen ab.

## In die App importieren

1. `<id>.onnx`, `<id>.pirol.json` und die `<id>_*.csv` auf das Geraet kopieren.
2. In PIROL: Settings → Modell & Erkennung → "Anderes Modell importieren..." → im Dateidialog die erste Datei
   lang antippen, dann **alle** zugehoerigen Dateien markieren (`.onnx`, `.pirol.json`, alle `<id>_*.csv`) → "Auswaehlen".
   Die App prueft SHA-256, Eingangs-/Ausgangsformen und Labelanzahl und zeigt die Lizenz an.
   Erscheinen per USB/adb kopierte Dateien nicht im Dateidialog, hat Android sie noch nicht indexiert:
   Tablet neu starten oder `adb shell content call --method scan_volume --uri content://media --arg external_primary`.
3. Modell auswaehlen; die Session-Metadaten tragen die `id` als `modelId`.

Die Lizenz des Originalmodells gilt auch fuer das ONNX. Nighthawk ist CC BY-NC 4.0 (nur nicht-kommerziell, Namensnennung).
