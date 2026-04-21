# T56 — Sonogramm-Dynamik: Auto-Kontrast und manueller dB-Bereich

## Ziel

Das Live-Sonogramm im Live-Tab wird im Feld nutzbar. Aktuell sind nur sehr laute Geraeusche sichtbar — leise Vogelstimmen verschwinden im Rauschboden, weil die Magnitude-zu-Farbe-Abbildung auf einen fixen dB-Bereich spreizt. Nach T56 passt sich die Darstellung an die aktuelle Lautstaerke-Szene an, und User kann optional auf eine feste Spreizung zurueckschalten.

**User-Feedback Feldtest 2026-04-20:** *"Sonogrammanzeige ist unbrauchbar, man sieht nur absolut laute Geraeusche."*

Zwei Test-Szenen muessen funktionieren:
1. **Ruhige Morgenszene** mit einzelnen leisen Amsel/Rotkehlchen-Rufen → Rufe sind als klare Mel-Strukturen sichtbar.
2. **Lauter Mittag** mit Verkehrsrauschen + leiser Vogelstimme → Vogel weiterhin klar erkennbar, nicht vom Rauschen ueberblendet.

---

## Projekt-Kontext

**Package:** `ch.etasystems.pirol`
**Pfad:** `D:\80002\PIROL`
**Branch-Basis:** `master` (nach T51c, Commit `91c2820`+)
**Build:** `.\gradlew.bat compileDebugKotlin` / `.\gradlew.bat assembleDebug`

**Audio-Pipeline (Kontext):**
- Oboe NDK liefert 48 kHz / 16-bit PCM.
- `MelSpectrogram`: FFT 2048, Hop 512, 64 Mel-Baender, Bereich 40–15 000 Hz.
- Live-Display-Pfad und Inference-Pfad laufen auf **getrennten Kopien** — BirdNET-Inference hat eigenen Limiter/Normalisierung (T34). T56 fasst **nur den Display-Pfad** an.

**Aktuelle Anzeige** (Stand V0.0.5 nach T51):
- `SpectrogramState` puffert Mel-Frames.
- `SpectrogramCanvas` mapped jeden Frame-Wert ueber `ColorPalette` auf RGB.
- Magnitude-zu-Palette-Index-Mapping wird aktuell vermutet im Range ~ `-80 dB … 0 dB` fix. **Worker muss das im Code verifizieren** — die genaue Stelle ist Teil der Diagnose.

---

## Tablet-Debug-Freigabe

Der Worker darf das ueber USB angeschlossene Tablet nutzen:

```cmd
set PATH=%PATH%;C:\Users\nleis\AppData\Local\Android\Sdk\platform-tools
adb devices
adb logcat -c
adb logcat *:E SpectrogramCanvas:V MelSpectrogram:V > run.log
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Fuer visuelle Verifikation: Tablet-Screenshot via `adb exec-out screencap -p > sono_before.png` und `sono_after.png`, in Handover als optische Belege einbetten.

---

## Schritt 1 — Ist-Zustand verstehen

Vor jedem Fix den aktuellen Mapping-Pfad lokalisieren und dokumentieren:

1. Wo wird der Mel-Wert in eine Farbe uebersetzt? (vermutet: `SpectrogramCanvas.kt` in einer `drawIntoCanvas`-Schleife oder `AsyncImage`-Painter)
2. Welcher dB-Bereich wird aktuell gespreizt? Ist es `-80 … 0`? Log-Skala? Linear?
3. Wo liegt die Magnitude-Berechnung — roh aus FFT, oder schon log-transformiert?
4. Gibt es bereits eine Normalisierung in `MelSpectrogram.kt`?

Stelle im Handover unter "Ist-Zustand vorher" festhalten. Ohne diese Basis ist der Fix schwer bewertbar.

---

## Schritt 2 — Scope

### 2.1 Perzentil-basiertes Mapping (Kernfix)

Rolling-Window ueber die letzten **N Sekunden** Mel-Frames berechnen (N = 5 s, vom Worker per `AppPreferences` einstellbar oder als Konstante). Pro Render-Tick:
- Magnitude-Verteilung des Fensters ermitteln (ueber alle Frames × Mel-Baender).
- 5. und 95. Perzentil berechnen (`p5` = Noise-Floor, `p95` = Peak).
- Farbpalette auf `[p5, p95]` spreizen statt auf fixen dB-Bereich.
- Werte < `p5` → Farbpalette-Index 0 (Hintergrund), Werte > `p95` → max.

**Empfohlene Implementierung:**
- In `SpectrogramState` oder einem neuen `DynamicRangeMapper` die Perzentile halten, pro neuem Frame inkrementell updaten (z. B. alle 250 ms komplett neu berechnen statt bei jedem Frame — Performance).
- Smooth-Uebergang zwischen zwei Perzentil-Werten (IIR-Filter `alpha = 0.1 … 0.3`), damit die Palette bei kurzen lauten Ereignissen nicht sofort "einspringt".

### 2.2 Auto-Gain / AGC (ergaenzend)

Rolling-Mittelwert der **Frame-Maxima** als Referenz. Attack schnell (~50 ms), Release langsam (~2 s).
- Falls Perzentil-Mapping allein nicht ausreicht (lange stumme Phasen → Rauschboden-Display wird uebersaturiert), AGC als zusaetzliche Normalisierung davorschalten.
- Der Worker entscheidet: Perzentil + AGC kombiniert, oder nur Perzentil, falls das schon reicht. Begruendung ins Handover.

### 2.3 Settings-Toggle "Auto-Kontrast" + manueller dB-Slider

Neue Sektion im `SettingsScreen` unter "Sonogramm":

- **Toggle "Auto-Kontrast"** (default AN). AN = Perzentil/AGC-Pfad. AUS = fester dB-Bereich (alter Pfad).
- **Manueller dB-Bereich** (nur aktiv wenn Toggle AUS): zwei gekoppelte Slider `minDb` und `maxDb` oder ein Range-Slider. Default `-80 … 0`, Limits z. B. `-100 … +10`.
- **Preview-Zeile** mit Anzeige der aktuellen Werte.

`AppPreferences`-Keys (neu):
- `spectrogramAutoContrast: Boolean` (default `true`)
- `spectrogramMinDb: Float` (default `-80f`)
- `spectrogramMaxDb: Float` (default `0f`)

### 2.4 Palette unveraendert lassen

Die vorhandenen Paletten (MAGMA/VIRIDIS/GRAYSCALE, Auswahl via `SpectrogramPalette`) bleiben **bit-identisch**. T56 fasst nur die **Wert-zu-Palette-Index-Abbildung** an, nicht die Paletten selbst.

### 2.5 Performance

- Ziel: 60 FPS Render bleibt erhalten, kein sichtbarer Frame-Drop.
- Perzentil-Berechnung **nicht** im Compose-Draw-Pfad — entweder im `SpectrogramState` bei `appendFrames()` oder in einem `Dispatchers.Default`-Flow.
- Bei Bedarf: Ringpuffer mit vorsortierter Histogramm-Approximation statt vollstaendigem Sort pro Tick.

---

## Scope-Ausschluss

- **Keine Aenderung an FFT-Parametern** (N_FFT 2048, Hop 512, 64 Mel-Baender) — BirdNET haengt daran.
- **Keine Aenderung am Inference-Pfad** (`InferenceWorker`, `AudioResampler`, `AudioDspUtils` — die haben T34-Limiter).
- **Kein Redesign des Canvas-Layouts** — Groesse, Zeit-Achse, Frequenz-Achse bleiben wie sie sind.
- **Keine Palette-Aenderung** (MAGMA/VIRIDIS/GRAYSCALE bleiben).
- **Keine Aenderung am WAV-Format oder Session-Persistenz.**
- **Analyse-Tab-Sonogramm** (`openSession` in `AnalysisViewModel`) darf vom neuen Mapping profitieren, wenn trivial. Falls es Aufwand bedeutet: **nur Live-Tab in T56**, Analyse-Tab in einem Folge-Task — im Handover begruenden.

---

## Acceptance Criteria

1. **Ruhe-Szene:** In einer Test-Session mit leisem Grundrauschen und einzelnen Amsel/Rotkehlchen-Rufen (z. B. via Lautsprecher-Playback einer Xeno-Canto-Aufnahme auf moderater Lautstaerke) sind die Rufe als klare Mel-Strukturen im Live-Sonogramm sichtbar — deutlicher als vor T56.
2. **Laut-Szene:** In einer Test-Session mit Verkehrs-/Menschen-Rauschen und einzelnen leisen Vogelrufen bleiben die Rufe sichtbar, nicht vom Rauschen ueberblendet.
3. **Auto-Kontrast-Toggle** in Settings wirkt: AUS → alter Pfad (fixer dB-Bereich), AN → neuer Perzentil/AGC-Pfad. Wechsel ist ohne App-Neustart wirksam (z. B. beim Tab-Wechsel nach Settings → Live).
4. **Manueller dB-Slider** (nur wenn Toggle AUS) spreizt die Palette ueber den gewaehlten Bereich. Extrem-Werte (z. B. `-30 … 0`) zeigen sichtbaren Effekt.
5. **Performance:** 60 FPS bleiben erhalten, kein Frame-Drop waehrend 5-Min-Session beobachtbar. Verifikation via `adb shell dumpsys gfxinfo ch.etasystems.pirol framestats` oder `Choreographer`-Warnings in Logcat (keine Warnings > Baseline).
6. **Palette-Auswahl** (MAGMA/VIRIDIS/GRAYSCALE) in Settings funktioniert orthogonal zum neuen Mapping — alle drei Paletten zeigen die Szene korrekt.
7. **Keine Regression im Inference-Pfad:** Detektionsrate in Testaufnahme ist gleich oder besser als V0.0.5-Baseline (BirdNET-Pipeline bleibt unveraendert — das ist ein Check, kein Feature).
8. **Build gruen:** `compileDebugKotlin` + `assembleDebug`.
9. **AppPreferences-Keys** `spectrogramAutoContrast`, `spectrogramMinDb`, `spectrogramMaxDb` persistieren ueber App-Neustart.

---

## Test-Plan (manuell auf Tablet)

**Setup:**
- Ein Handy mit Xeno-Canto-MP3 einer Amsel (z. B. XC123456), auf moderater Lautstaerke.
- Ein Handy/Lautsprecher mit Strassen-/Cafeteria-Aufnahme fuer Hintergrund (YouTube "traffic noise 10 hours").
- Tablet mit PIROL im Live-Tab.

**Testlauf 1 — Ruhe-Szene:**
1. PIROL starten, Toggle "Auto-Kontrast" AN (default). Live-Tab, FAB → Aufnahme laeuft.
2. Amsel-Handy ~30 cm vom Tablet, niedrige Lautstaerke (kaum hoerbar).
3. 30 s aufnehmen, Sonogramm-Screenshot nehmen.
4. FAB Stop. Session abschliessen.
5. Vergleich-Screenshot aus V0.0.5 (Pre-T56) zur Referenz — Worker macht einen eigenen Pre-Fix-Screenshot zur visuellen Doku.

**Testlauf 2 — Laut-Szene:**
1. Hintergrundrauschen-Handy an, ~50 dB SPL auf dem Tablet-Mikro.
2. Amsel-Handy zeitversetzt einspielen.
3. 30 s Aufnahme, Screenshot.

**Testlauf 3 — Toggle AUS:**
1. Settings → Auto-Kontrast AUS. `minDb = -60`, `maxDb = -10`.
2. Live-Tab, Aufnahme mit gleicher Ruhe-Szene wie Testlauf 1.
3. Screenshot — muss deutlich anders aussehen als Testlauf 1 (fixer Bereich).

**Testlauf 4 — Regressionscheck Inference:**
- 3-Min-Aufnahme mit Xeno-Canto-Playback von 3 Arten (Amsel, Buchfink, Rotkehlchen).
- Detektionen zaehlen → vor/nach T56 ≈ gleich.

---

## Normung

- Kotlin 2.1.0, Jetpack Compose, Material 3.
- **Kommentare Deutsch, Bezeichner Englisch.**
- Keine `Thread.sleep`, kein `GlobalScope`.
- Koroutinen via `viewModelScope` / `Dispatchers.Default` fuer DSP.
- Commit-Format: `T56: Kurzbeschreibung` + Bullet-Details. Gerne mehrere Commits (z. B. 1. Diagnose + Refactor, 2. Perzentil, 3. Settings-UI).

---

## Relevante Dateien — vor Aenderung vollstaendig lesen

- `app/src/main/kotlin/ch/etasystems/pirol/audio/dsp/MelSpectrogram.kt` (Magnitude-Berechnung, evtl. log-Umwandlung)
- `app/src/main/kotlin/ch/etasystems/pirol/audio/dsp/SpectrogramConfig.kt` (Parameter, evtl. neue Range-Felder)
- `app/src/main/kotlin/ch/etasystems/pirol/ui/components/SpectrogramCanvas.kt` (Render-Pfad, Wert-zu-Palette-Mapping — **Hauptschauplatz**)
- `app/src/main/kotlin/ch/etasystems/pirol/ui/components/SpectrogramState.kt` (Frame-Puffer, hier koennte das Perzentil-Tracking leben)
- `app/src/main/kotlin/ch/etasystems/pirol/ui/components/ColorPalette.kt` (Palette-Definitionen — unveraendert)
- `app/src/main/kotlin/ch/etasystems/pirol/ui/settings/SettingsScreen.kt` (neue Sonogramm-Sektion)
- `app/src/main/kotlin/ch/etasystems/pirol/data/AppPreferences.kt` (drei neue Keys)

**Nicht anfassen** (Scope-Schutz):
- `ml/InferenceWorker.kt`
- `audio/dsp/AudioDspUtils.kt` (Inference-Seite)
- `audio/dsp/AudioResampler.kt`
- `cpp/` komplett

---

## Handover

`Handover_T56.md` im Projekt-Root mit:

1. **Ist-Zustand vorher** (Schritt 1) — wo saß das Mapping, was war der Bereich?
2. **Implementierte Loesung** — Perzentil allein oder + AGC, Begruendung.
3. **Dateiliste** (neu / geaendert).
4. **Visuelle Belege** — `sono_before.png` (alter Code) und `sono_after.png` (neuer Code) fuer beide Szenen (ruhig + laut), optional als Anhang oder als eingebettete Bild-Referenz.
5. **Performance-Check** — gfxinfo-Stats oder Choreographer-Log.
6. **Inference-Regressionscheck** — Detektionsrate vor/nach.
7. **Acceptance Criteria abgehakt.**
8. **Offene Punkte / Seiteneffekte.**
9. **Naechster Schritt:** "T56 abgenommen, T52 folgt."
