# T56b — Sonogramm-Kompression (Gamma) + Tuning fuer leise Voegel

## Ziel

T56 hat Auto-Kontrast (5./95. Perzentil, 5-s-Rolling, IIR) und manuellen dB-Range eingefuehrt. Feldtest-Ergebnis vom 2026-04-21: **leise Voegel sind weiterhin nicht klar sichtbar**. User-Wunsch:

> "Neben Dynamik-Begrenzung der Anzeige (von-bis in dB) kann eine Kompression der logarithmischen dB-Intensitaet Sinn machen, wenn sich da nachschaerfen laesst ist das ein Win."

Konkret: **Gamma-Kompression** auf dem normalisierten Palette-Index — gamma < 1 hebt leise Anteile an, ohne die Palette oder die Range-Logik zu aendern. Die neue Kompression greift **orthogonal** zu Auto-Kontrast AN/AUS, d.h. auch bei fixem Range wirksam.

Feldtest durch User ist **morgen Abend** (2026-04-22). Dieser Hotfix muss davor drin sein.

---

## Projekt-Kontext

**Branch:** `master` — T56-Aenderungen liegen derzeit **uncommitted** im Main-Tree (siehe `git status`). T56b wird **auf diesem Stand obendrauf** gebaut. Am Ende ein Commit `T56+T56b` oder zwei getrennte (Worker entscheidet, nachvollziehbar bleiben).

**Relevante T56-Aenderungen, auf denen du aufsetzt:**
- `app/src/main/kotlin/ch/etasystems/pirol/audio/dsp/DynamicRangeMapper.kt` (neu, Rolling-Perzentil + `computeStatic`)
- `app/src/main/kotlin/ch/etasystems/pirol/ui/components/SpectrogramCanvas.kt` (Range-Entscheidungslogik, `BitmapRenderer.renderFrames()`)
- `app/src/main/kotlin/ch/etasystems/pirol/ui/components/SpectrogramState.kt`
- `app/src/main/kotlin/ch/etasystems/pirol/data/AppPreferences.kt` (3 Prefs)
- `app/src/main/kotlin/ch/etasystems/pirol/ui/live/LiveViewModel.kt` + `LiveUiState.kt` + `LiveScreen.kt`
- `app/src/main/kotlin/ch/etasystems/pirol/ui/settings/SettingsScreen.kt` (Auto-Kontrast-Sektion)
- `app/src/main/kotlin/ch/etasystems/pirol/ui/analysis/AnalysisViewModel.kt` + `AnalysisUiState.kt` + `AnalysisScreen.kt` + `di/AppModule.kt`

Lies `Handover_T56.md` Abschnitte 2, 4 und 10 komplett, bevor du editierst.

---

## Scope

### 1. Gamma-Kompression im Display-Pfad

**Ort:** `SpectrogramCanvas.kt`, in `BitmapRenderer.renderFrames()`, nach der Normalisierung und vor dem LUT-Lookup.

Pseudo-Code:
```kotlin
val normalized = ((value - minDbUsed) / rangeUsed).coerceIn(0f, 1f)
val compressed = if (gamma == 1f) normalized else normalized.pow(gamma)
val index = (compressed * 255f).toInt().coerceIn(0, 255)
```

- Gamma < 1.0 → leise Werte werden heller (Ziel: leise Voegel sichtbar).
- Gamma == 1.0 → identisch zum T56-Verhalten (kein Effekt).
- Performance: `Math.pow()` bzw. `Float.pow()` ist in Kotlin langsam. Wenn messbar teuer (Frame-Drops), **LUT pro Frame-Render vorberechnen** (256 Float-Werte) — Recompute nur wenn sich Gamma aendert (via `remember(gamma)` oder Cached-Value in `BitmapRenderer`).

### 2. Settings-Toggle + Slider

Neue Zeile in der bestehenden "Auto-Kontrast"-Sub-Sektion in `SettingsScreen.kt`:

- Label: **"Kontrast-Kompression (Gamma)"**
- Hilfezeile: *"Hebt leise Anteile an. 1.0 = aus, 0.5 = Standard fuer leise Voegel, 0.3 = stark."*
- Slider Range `0.3f .. 1.0f`, Steps `14` (0.05-Schritte), Default `0.5f`.
- Live-Wert-Anzeige: z.B. `"γ = 0.50"`.
- **Immer aktiv**, unabhaengig vom Auto-Kontrast-Toggle.

### 3. AppPreferences

Neue Property (gleiches Muster wie T56):
```kotlin
var spectrogramGamma: Float   // default 0.5f, pirol_spectrogram_gamma
```

### 4. State-Propagation

- `LiveUiState` + `AnalysisUiState` um `spectrogramGamma: Float` erweitern.
- `LiveViewModel.init{}` und `reloadSpectrogramPrefs()` laden `appPreferences.spectrogramGamma` und pushen in `_uiState`.
- Analog `AnalysisViewModel`.
- `SpectrogramCanvas` erhaelt neuen Param `gamma: Float = 1f` (Default = alter Pfad fuer eventuelle andere Caller).
- `LiveScreen` + alle drei Canvas-Aufrufe in `AnalysisScreen` reichen `gamma = uiState.spectrogramGamma` durch.

### 5. Parameter-Tuning Rolling-Window (optional, nur falls Gamma allein nicht reicht)

Wenn du bei visuellem Test mit Gamma 0.5 **immer noch** leise Voegel im Rauschboden siehst, zusaetzlich:

- **Perzentil-Grenzen** `DynamicRangeMapper.kt`: `p5` → **`p2`** und `p95` → **`p98`** (aggressiver, macht die Palette breiter fuer die Mitte).
  - **Variante:** als Konstanten in Datei oben gut lesbar lassen, kein Settings-Slider (Feldtuning-Iteration, kein User-Feature).
- **windowSeconds** `5f` → evtl. `3f` falls Szenen kuerzer wechseln.
- Entscheidung treffen durch Vergleichs-Screenshots (ruhig + laut) vor/nach — ins Handover einbetten.

Kein Settings-Slider fuer diese Parameter. Sie sind Tuning, nicht User-Feature.

### 6. Visuelle Verifikation

Vier Screenshots via `adb exec-out screencap -p > fileN.png`:

1. `sono_t56b_ruhig_gamma100.png` — Auto-Kontrast AN, Gamma = 1.0, ruhige Szene
2. `sono_t56b_ruhig_gamma050.png` — Auto-Kontrast AN, Gamma = 0.5, ruhige Szene
3. `sono_t56b_laut_gamma050.png` — Auto-Kontrast AN, Gamma = 0.5, laute Szene
4. `sono_t56b_fixrange_gamma050.png` — Auto-Kontrast AUS, `-60 … -10` dB, Gamma = 0.5

Screenshots im Projekt-Root ablegen und in der Handover-Datei referenzieren. Falls kein Xeno-Canto-Playback moeglich: eigene Stimme pfeifen, oder irgendein Geraeusch mit weitem Dynamikumfang. Wichtig: sichtbare Differenz Gamma 1.0 vs 0.5.

---

## Scope-Ausschluss

- **Keine Aenderung an FFT, Mel, C++, Palette, Inference-Pfad.**
- **Kein Canvas-Layout-Redesign** (Merlin-Stil / 5s × 6cm — kommt in T59, nicht hier).
- **Keine Aenderung an T56-Rolling-Perzentil-Logik**, ausser den optionalen p2/p98-Konstanten unter Schritt 5.
- **Kein Soft-Knee-Kompressor auf dB-Werten** vor dem Perzentil-Mapping — Gamma auf dem normalisierten Wert reicht und ist billiger.
- **Kein eigenes AGC ueber Frame-Maxima** (T56-Handover §10.1 hatte das als Option — mit Gamma nicht mehr noetig, falls doch: Scope-Verletzung, eigenes AP).

---

## Acceptance Criteria

1. **Gamma-Slider wirkt sichtbar:** Gamma 1.0 = T56-Verhalten; Gamma 0.5 = leise Signale deutlich heller; Gamma 0.3 = nahezu flach (alles hell). Vergleichs-Screenshots belegen das.
2. **Ruhe-Szene mit leisen Voegeln:** Mit Default Gamma 0.5 sind leise Rufe als klare Mel-Struktur sichtbar (nicht im Rauschboden verschwunden). Ueberpruefung per Screenshot.
3. **Laut-Szene:** Leise Voegel bleiben ueber dem Verkehrsrauschen erkennbar.
4. **Auto-Kontrast AN + Gamma** und **Auto-Kontrast AUS + Gamma** wirken beide — Gamma ist orthogonal.
5. **Performance:** Keine sichtbaren Frame-Drops, keine neuen Choreographer-Warnings beim 5-Min-Live-Test. `adb logcat | grep Choreographer` vor/nach vergleichen.
6. **Analyse-Tab:** Gamma greift auch im Session-Replay (beide Sonogramme) und im Dual-Vergleich — konsistent zu Live.
7. **Prefs-Persistenz:** Gamma-Wert ueberlebt Force-Stop + Neustart. Einmal manuell verifiziert auf Tablet.
8. **Build gruen:** `compileDebugKotlin` + `assembleDebug`.
9. **Inference unveraendert:** `InferenceWorker.kt`, `BirdNetV3Classifier`, `AudioClassifier`-Interface, C++-Code **nicht** editiert (Scope-Check im Handover bestaetigen).

---

## Testplan (auf Tablet, Samsung R5GL14LSJFT)

**Setup vorher:**
```
set PATH=%PATH%;C:\Users\nleis\AppData\Local\Android\Sdk\platform-tools
adb devices
adb logcat -c
```

**Testlauf A — Default:**
1. Install APK (`pirol-deploy.ps1` oder `adb install -r`).
2. Settings → Sonogramm → Auto-Kontrast AN, Gamma 0.5 (Default).
3. Live-Tab → FAB → ruhige Szene ~30 s. Screenshot.

**Testlauf B — Gamma 1.0 Vergleich:**
1. Settings → Gamma auf 1.0. Live-Tab zurueck → ON_RESUME greift.
2. Gleiche Szene, 30 s. Screenshot.
3. Differenz zu A muss klar sichtbar sein.

**Testlauf C — Laute Szene:**
1. Gamma 0.5. Radio / Verkehrsgeraeusch im Hintergrund, leise Stimme/Pfeifen davor.
2. 30 s, Screenshot.

**Testlauf D — Fixer Range + Gamma:**
1. Auto-Kontrast AUS. Range Slider auf `-60 … -10`. Gamma 0.5.
2. Ruhige Szene, 30 s, Screenshot.

**Testlauf E — Persistenz:**
1. Gamma auf 0.3. App Force-Stop (`adb shell am force-stop ch.etasystems.pirol`).
2. App neu oeffnen → Settings → Gamma zeigt 0.3.

**Testlauf F — Performance:**
1. `adb shell dumpsys gfxinfo ch.etasystems.pirol reset`.
2. 5-Min-Session mit Auto-Kontrast AN + Gamma 0.5.
3. `adb shell dumpsys gfxinfo ch.etasystems.pirol framestats > gfxinfo_t56b.txt` — Handover anhaengen.
4. `adb logcat | grep Choreographer` parallel aufgezeichnet — keine `Skipped frames` > T56-Baseline.

---

## Normung

- Kotlin 2.1, Compose, Material 3. Kommentare deutsch, Bezeichner englisch.
- Kein `Thread.sleep`, kein `GlobalScope`.
- Slider via Material 3 `Slider` mit `valueRange` + `steps`.
- Commit-Format: `T56b: Gamma-Kompression + leichte Tuning-Anpassung` + Bullets.

---

## Handover

`Handover_T56b.md` im Projekt-Root, Aufbau analog zu T56:

1. Implementierung (Gamma-LUT ja/nein, Caching-Entscheidung)
2. Dateiliste (neu / geaendert)
3. Vier Screenshots A/B/C/D + Kurzbefund pro Testlauf
4. Performance-Snippet aus `gfxinfo_t56b.txt`
5. Persistenz-Check-Notiz (Testlauf E)
6. Entscheidung zu Schritt 5 (Tuning p5/p95 → p2/p98): ja/nein, Begruendung
7. Acceptance-Criteria-Tabelle abgehakt
8. Offene Punkte
9. Scope-Einhaltung bestaetigt

---

## Kritische Dateien — vor Aenderung vollstaendig lesen

- `app/src/main/kotlin/ch/etasystems/pirol/ui/components/SpectrogramCanvas.kt` (Hauptaenderung: `BitmapRenderer.renderFrames()`)
- `app/src/main/kotlin/ch/etasystems/pirol/data/AppPreferences.kt`
- `app/src/main/kotlin/ch/etasystems/pirol/ui/live/LiveViewModel.kt` + `LiveUiState.kt` + `LiveScreen.kt`
- `app/src/main/kotlin/ch/etasystems/pirol/ui/analysis/AnalysisViewModel.kt` + `AnalysisUiState.kt` + `AnalysisScreen.kt`
- `app/src/main/kotlin/ch/etasystems/pirol/ui/settings/SettingsScreen.kt`
- `app/src/main/kotlin/ch/etasystems/pirol/audio/dsp/DynamicRangeMapper.kt` (nur falls Schritt 5 greift)
- `Handover_T56.md` (Kontext)

**Nicht anfassen:**
- `ml/**`, `cpp/**`, `ColorPalette.kt`, `MelSpectrogram.kt`, `FFT.kt`, `AudioResampler.kt`, `AudioDspUtils.kt`, `RecordingService.kt`, `SessionManager.kt`, WAV-Format, JSONL-Format.
