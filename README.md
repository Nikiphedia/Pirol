<p align="center">
  <img src="tools/icon/pirol_icon_512.png" alt="PIROL" width="140">
</p>

<h1 align="center">PIROL</h1>

<p align="center">
  <b>Vogelstimmen erkennen – live, offline, direkt auf dem Smartphone.</b><br>
  Für die Feldarbeit gebaut: aufnehmen, erkennen, bestätigen, anhören, exportieren.
</p>

<p align="center">
  <a href="https://github.com/Nikiphedia/Pirol/releases/latest"><b>⬇ Neueste Version herunterladen</b></a> ·
  <a href="BDA_PIROL.md">Bedienungsanleitung</a> ·
  <a href="CHANGELOG.md">Was ist neu</a>
</p>

---

## Was PIROL kann

PIROL hört mit, während du unterwegs bist, und zeigt laufend, welche Arten gerade singen oder rufen. Alles läuft
**auf dem Gerät**, ohne Internet und ohne Cloud. Die Aufnahme bleibt als WAV-Datei erhalten, jede Erkennung ist
mit Zeit, Konfidenz und GPS-Position gespeichert.

- 🎙️ **Live-Erkennung** mit BirdNET 3.0 (über 11'000 Arten), regional gefiltert, z.B. auf Schweizer Brutvögel
- 📈 **Live-Sonogramm** bis 12 kHz, Zeitfenster bis 30 s, auch als helle „Papier“-Darstellung für Tageslicht
- ✅ **Bestätigen, korrigieren, zählen** direkt auf der Artkarte, mit Atlascode, Notiz und Sprachmemo
- ▶️ **Anhören** jeder Erkennung: in der Live-Liste, in der Analyse und auf der Karte. Das Mikrofon ist während der Wiedergabe stumm
- 🗺️ **Karte** mit allen Beobachtungen (OpenStreetMap, swisstopo); Punkte lassen sich verschieben
- 🔍 **Analyse** vergangener Aufnahmen: Episoden am Stück abspielen, Sonogramm zoomen, Referenzen vergleichen
- 📤 **Export** als Raven-Selection-Table (öffnet direkt in Raven, Audacity, Sonic Visualiser), ZIP-Paket, Upload auf NAS oder Cloud
- 🧩 **Eigene Modelle**: jedes ONNX-Modell mit Profil-Datei importierbar, z.B. BirdNET 2.4, Perch v2 oder Nighthawk; Konverter für TensorFlow/Keras/TFLite liegt bei
- 🔋 **Sparsam**: rund 2 % Akku pro Stunde bei Daueraufnahme mit ausgeschaltetem Bildschirm
- 🌍 **Sprachen**: Deutsch und Englisch, Artnamen in vielen Sprachen

## Installation

1. Auf der [Release-Seite](https://github.com/Nikiphedia/Pirol/releases/latest) die passende APK laden:
   - `Pirol_V…_arm64.apk` für praktisch alle aktuellen Geräte
   - `Pirol_V…_armeabi-v7a.apk` nur für alte 32-Bit-Geräte
2. APK öffnen und die Installation aus dieser Quelle erlauben.
3. Beim ersten Start führt das Onboarding durch Region, Modell-Download (ca. 170 MB) und Berechtigungen.

Voraussetzung: Android 8.0 oder neuer. Updates installieren sich über die alte Version, Aufnahmen und Modelle bleiben erhalten.

## Dateien und Formate

Jede Aufnahme landet in einem eigenen Ordner unter `Downloads/PIROL/` und ist per USB sichtbar:

| Datei | Inhalt |
|---|---|
| `recording.wav` | Aufnahme, 48 kHz, 16 bit, mono (lange Aufnahmen in Segmenten) |
| `recording.selections.txt` | Raven-Selection-Table, wird beim Stopp automatisch geschrieben |
| `detections.jsonl` | eine Zeile pro Erkennung (Art, Konfidenz, Zeit, Position) |
| `verifications.jsonl` | alle Bestätigungen und Korrekturen, nachvollziehbar |
| `session.json` | Metadaten der Aufnahme (Ort, Modell, Einstellungen) |

Zeitstempel stehen in Ortszeit mit Zonen-Offset, damit sie ohne Umrechnung lesbar sind.

## Modelle und Lizenzen

PIROL selbst enthält keine Modelldateien; sie werden beim ersten Start heruntergeladen oder importiert.

| Modell | Lizenz |
|---|---|
| BirdNET+ V3.0 (Standard) | Apache 2.0 |
| BirdNET 2.4 | nur nicht-kommerziell (CC BY-NC) |
| Perch v2 (Google) | Apache 2.0 |
| Nighthawk | nur nicht-kommerziell (CC BY-NC 4.0) |

Daten: Gemeindegrenzen © swisstopo (swissBOUNDARIES3D), vereinfacht in der App enthalten (`tools/build_municipalities.py`).

Eigene Modelle importieren: siehe [`tools/README_convert_model.md`](tools/README_convert_model.md) und [`tools/MODEL_PROFILE_FORMAT.md`](tools/MODEL_PROFILE_FORMAT.md).

## Selbst bauen

Kotlin 2.1, Jetpack Compose, Oboe (NDK), ONNX Runtime. Benötigt werden JDK 17, Android SDK 35 und NDK 27.

```bash
./gradlew assembleDebug
```

Die Debug-APK liegt danach unter `app/build/outputs/apk/debug/`. Release-Builds werden mit einem eigenen Schlüssel signiert, der nicht im Repository liegt.

## Mitmachen

Rückmeldungen aus dem Feld sind das Wertvollste: Fehlerkennungen, Bedienungsprobleme, Wünsche.
Am besten als [Issue](https://github.com/Nikiphedia/Pirol/issues/new/choose), gerne mit Screenshot und Version (Einstellungen → Info).
Code-Beiträge nach Absprache, siehe [CONTRIBUTING.md](CONTRIBUTING.md). Sicherheitslücken bitte vertraulich melden ([SECURITY.md](SECURITY.md)).

## Lizenz

Entwickelt von ETA Systems GmbH, 5004 Aarau, Schweiz.

PIROL steht unter der [GNU General Public License v3.0](LICENSE). Die Erkennungsmodelle sind nicht Teil der App und
haben eigene Lizenzen (siehe oben).

---

<p align="center"><i>PIROL – benannt nach dem Vogel, den man fast immer nur hört.</i></p>
