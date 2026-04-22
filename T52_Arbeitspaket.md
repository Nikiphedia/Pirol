# T52 — Live-Tab UX-Haertung + Analyse-Liste mm:ss/MB + FAB-3-State + Regress-Fix

## Ziel

V0.0.5-Feldtest hat konkrete UX-Schwaechen aufgedeckt. Verifikation im Feld ist taplastig, muss schnell und eindeutig sein. Daneben drei konkrete User-Wuensche vom 2026-04-20 und ein Regress aus T51b, der noch auf T52 wartet. Feldtest durch User **morgen Abend (2026-04-22)**, deshalb kein Scope-Creep.

---

## Projekt-Kontext

**Branch:** `master`. T51a+b+c abgenommen, T54 abgenommen, T56+T56b werden parallel/davor gemergt. T52 setzt auf dem T56+T56b-Stand auf.

**Lies vor Start:**
- `PROJEKT_UEBERBLICK.md`
- `CLAUDE.md` (Tap-Target-Konvention 48dp/56dp, Zeitzone ISO 8601 mit Offset, Sprache via `SpeciesNameResolver`)
- `Handover_T51c.md` §4 und §5 (drei User-UX-Punkte + Instant.parse-Regress)
- `Handover_T51b.md` §4 (SessionCard-Timestamp-Regress)

Standard-T52-Scope steht ausserdem im Plan-Dokument `C:\Users\nleis\.claude\plans\joyful-mixing-starlight.md`, Abschnitt T52 — hier unten konsolidiert.

---

## Scope

### 1. Verifikations-Buttons (SpeciesCard)

Datei: `app/src/main/kotlin/ch/etasystems/pirol/ui/components/SpeciesCard.kt`

- Tap-Target min. **48 dp**, Feld-Kontext **56 dp** fuer ✓ / ? / ✗ / ✎. Sichtbare Button-Container: `FilledIconButton` bzw. `FilledTonalIconButton` statt reines Icon.
- Rand-Dicke der Card `1 dp` → **`2 dp`**. Farbcode bleibt (CLAUDE.md §"UI-Konventionen"): CONFIRMED gruen, UNCERTAIN orange, REJECTED ausgegraut 0.6, CORRECTED ersetzt Namen, REPLACED ausgegraut + roter Rand.
- Feedback bei ✎ und Kandidaten-Tap: `HapticFeedbackType.LongPress`.
- Ripple bleibt Default.

### 2. Snackbar + Undo bei jeder Verifikations-Aktion

Datei: `ui/live/LiveScreen.kt`, `ui/live/LiveViewModel.kt`, `ui/live/LiveUiState.kt`

- Jede der vier Aktionen (CONFIRMED / UNCERTAIN / REJECTED / CORRECTED) und **Alternative-Pick** (REPLACED) zeigt Snackbar mit Artname.
- Undo-Action (≥ 5 s, laut CLAUDE.md):
  - stellt vorherigen Status wieder her,
  - entfernt den neuesten Eintrag aus `verifications.jsonl` (`SessionManager.removeLastVerification(detectionId)`-API ggf. neu erstellen),
  - rollback ist idempotent, kein Fehler falls inzwischen weitere Verifikation geschah → Undo disabled / Snackbar nicht mehr anzeigen.
- Snackbar via `SnackbarHostState`. Nachrichten kommen per Channel / SharedFlow aus dem ViewModel, nicht aus Compose-State.

### 3. FAB-3-State (Preroll blau → Running gruen)

Datei: `ui/live/LiveScreen.kt` (FAB ab ~Zeile 720 — `RecordingFabState`), `ui/live/LiveViewModel.kt`, `audio/RecordingService.kt`

Heutiger Zustand: `IDLE / READY / CONNECTING / RECORDING`. Problem: Waehrend Preroll-Puffer sich fuellt (5/10/30 s) zeigt der FAB wahrscheinlich schon `RECORDING` oder liegt auf `CONNECTING`. User will:

- **Idle:** Mic-Icon, neutrale Farbe (bleibt).
- **Preroll-Buffering:** **blau**, Mic-Icon mit kleinem Fortschrittsring (indeterminate Progress). Sobald Service "Ringbuffer mindestens Preroll-Laenge voll" meldet:
- **Recording aktiv:** **gruen**, Stop-Icon, pulsierende Umrandung.
- **Stop-Tap:** zurueck zu Idle.

Technisch:
- `RecordingService` erhaelt neues Feld `prerollBufferFilledSamples: StateFlow<Long>` oder einen diskreten `RecordingPhase`-Enum (`IDLE / PREROLL_FILLING / RUNNING`). Erste Variante braucht weniger Logik im Service; Worker entscheidet.
- `LiveViewModel` mapped `RecordingService.isRunning` + Phase auf `RecordingFabState` mit neuem Zustand `PREROLL_BUFFERING`.
- Doppeltap-Debounce **500 ms** auf FAB-Tap — harter Gate im ViewModel (`lastFabTapAt`-Timestamp).

### 4. Status-Zeile

Datei: `ui/live/LiveScreen.kt`

- **Session-Laufzeit live als mm:ss**, Update 1 Hz aus `RecordingService.sessionStartedAtMillis` + `LaunchedEffect` mit `delay(1000)`.
- **GPS-Fix-Alter:** "Fix: vor Xs". Rot wenn `> 30 s` oder `null`. Bereits geplanter T53-Umfang geht tiefer — hier nur das Display, keine LocationProvider-Aenderung.
- Sample-Counter hinter Settings-Toggle "Debug-Anzeigen" (default aus). Falls zu aufwendig → rauslassen, Scope-Minus ins Handover.

### 5. GPS-Bar

Datei: `ui/live/LiveScreen.kt`, ggf. `location/LocationProvider.kt` fuer Accuracy-Durchreichung

- Anzeige: `lat, lon ± Xm`. Genauigkeit kommt vom aktuellen Fix, nicht aus `DetectionResult`.
- **Tap** auf GPS-Bar oeffnet Permission-Dialog falls denied (nutze bestehende `LocationPermissionHandler`-Logik).

### 6. Top-N-Kandidaten

Dateien: `ui/components/SpeciesCard.kt`, `ui/components/DetectionList.kt`

- Expand/Collapse-State persistent ueber Re-Sortierung (Key via `DetectionResult.id` — z.B. `rememberSaveable` oder ViewModel-Map `detectionId → expanded`).
- `animateContentSize` Modifier fuer den Expand-Container.
- Kandidaten-Zeilen Tap-Target `≥ 48 dp`.
- **Kandidaten-Namen in Anzeigesprache** via `SpeciesNameResolver.resolveName(scientific, prefs.speciesLanguage)`. Wissenschaftlicher Name als Subtitle/Tooltip. Aktuell: nur Latein.

### 7. Settings-Toggle "Artvorschlaege anzeigen"

Datei: `ui/settings/SettingsScreen.kt`, `data/AppPreferences.kt`

- Neue Pref `showTopNCandidates: Boolean`, Key `pirol_show_top_n_candidates`, Default **true**.
- Bei `false` rendert `SpeciesCard` keinen Expand-Bereich, Expand-Icon verschwindet.
- **`DetectionResult.candidates` bleibt vollstaendig in `detections.jsonl`** — nur Display-Effekt, kein JSONL-Stripping.

### 8. Analyse-Liste: mm:ss + MB

Datei: `ui/components/SessionCard.kt` (Hauptstelle), ggf. `ui/analysis/AnalysisViewModel.kt` fuer Groesse

- **Dauer mm:ss** statt "X Min":
  - Heute: `Duration.between(meta.startedAt, meta.endedAt).toMinutes()` (Zeile ~58–60) + `"$durationMin Min"` (Zeile 102).
  - Neu: `val dur = Duration.between(...); val mm = dur.toMinutesPart(); val ss = dur.toSecondsPart(); "%02d:%02d".format(mm, ss)` (Java 9+ API ok auf min SDK 26? — **nein**, `toMinutesPart` ist API 31. Manuell: `val total = dur.seconds; "%02d:%02d".format(total/60, total%60)`).
- **Groesse in MB:**
  - Aktuell kein Feld. Beim Laden der `SessionSummary` in `AnalysisViewModel` die Groesse der `recording.wav` im Session-Ordner via `File.length()` bzw. `DocumentFile.length()` (SAF) abfragen und in `SessionSummary` persistieren (in-memory, kein Schema-Change).
  - Anzeige: `"%.1f MB".format(bytes / 1_048_576f)`.
  - SAF-Pfad: `ContentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), ...)`. Fallback 0/"?" wenn nicht ermittelbar.

### 9. Regress-Fix: SessionCard Instant.parse

Datei: `ui/components/SessionCard.kt:47` und `:52`

- Aktuell: `Instant.parse(meta.startedAt)` / `Instant.parse(it)` — crasht nicht, zeigt aber **"—"** statt Dauer bei neuen `+02:00`-Sessions (T51-Format).
- Fix (gleiches Muster wie T51b/T51c):
```kotlin
val instant = try {
    OffsetDateTime.parse(value).toInstant()
} catch (e: DateTimeParseException) {
    try { Instant.parse(value) } catch (_: Exception) { null }
}
```
  Oder per Helper in einem Utility-File (falls schon vorhanden aus T51b/T51c — wiederverwenden statt kopieren).

---

## Scope-Ausschluss

- **Keine Sonogramm-Aenderung** (T56 / T56b zustaendig).
- **Keine GPS-Logik-Aenderung** (Accuracy-Filter, Smoothing, Intervall → T53).
- **Keine Verifikations-Semantik** aendern — Status-Enum bleibt, `verifications.jsonl` bit-identisch. Undo entfernt nur den letzten Eintrag, aendert kein Schema.
- **Kein neues Canvas-Layout / Skalierung** (→ T59).
- **Keine Recording-Start-Stabilitaet** (→ T54b, T55).

---

## Acceptance Criteria

1. Verifikations-Buttons (SpeciesCard) messbar ≥ 48 dp, 56 dp bevorzugt. Rand 2 dp. Haptik bei ✎ und Alternative-Tap.
2. Jede Verifikations-Aktion zeigt Snackbar (≥ 5 s) mit Undo; Undo stellt Status wieder her und entfernt letzten `verifications.jsonl`-Eintrag.
3. FAB-Status:
   - Preroll aktiv + Aufnahme gedrueckt → **blau** mit Progress-Ring, bis Puffer voll.
   - Danach → **gruen** mit Stop-Icon.
   - Stop → Idle.
   - Doppeltap erzeugt max. eine Aktion.
4. Session-Laufzeit mm:ss tickt live. GPS-Fix-Alter sichtbar, rot > 30 s.
5. GPS-Bar zeigt `± m`. Tap auf GPS-Bar ohne Permission oeffnet Permission-Request.
6. Top-N-Expand bleibt bei Re-Ranking stabil (gleiche Detection-ID bleibt expanded).
7. Top-N-Kandidaten-Namen in Anzeigesprache (nicht Latein), Subtitle = wissenschaftlich.
8. Settings-Toggle "Artvorschlaege anzeigen" versteckt Top-N in Card + Expand-Icon; `detections.jsonl` bleibt vollstaendig.
9. Analyse-Tab: Session-Cards zeigen Dauer als **mm:ss** und Groesse in **MB** (1 Dezimalstelle).
10. `SessionCard` rendert Dauer und Daten auch bei T51-Sessions mit `+02:00`-Stempeln korrekt (kein "—").
11. `compileDebugKotlin` + `assembleDebug` gruen. Keine neuen Warnings > V0.0.6-Baseline.
12. Manueller 5-Min-Live-Test + 1 Analyse-Tab-Besuch ohne Crash, ohne sichtbares Ruckeln.

---

## Testplan (auf Tablet)

1. **Install APK**, Onboarding ueberspringen / durchlaufen.
2. **Live-Tab:** Preroll-Laenge 10 s. FAB druecken → **blauer** FAB ~10 s → **gruener** FAB. Stop.
3. **Doppeltap-FAB** schnell → nur eine Start-Aktion.
4. **Verifikation:** 5 Detektionen, je eine ✓ ? ✗ ✎ Alternative. Jede Snackbar sichtbar, Undo jeweils einmal → Status zurueck.
5. **Top-N:** Eine Detektion expanden, eine weitere Detektion faellt dazwischen → erste bleibt expanded.
6. **Sprache:** Settings → Artennamen-Sprache auf Deutsch, zurueck zu Live. Top-N-Kandidaten auf Deutsch.
7. **Settings-Toggle** Artvorschlaege aus → Expand-Icon verschwindet. Neue Session pruefen: `detections.jsonl` enthaelt weiterhin `candidates`-Array.
8. **Analyse-Tab:** Session-Liste → Dauer in mm:ss, Groesse in MB. Alte T51-Session (+02:00-Stempel) korrekt.
9. **GPS-Bar:** `± m` sichtbar. In Android-Settings Location-Permission revoken → Tap auf GPS-Bar → Dialog.

---

## Normung

- Kotlin 2.1, Material 3, Koin. Kommentare deutsch, Bezeichner englisch.
- Keine `Thread.sleep`, kein `GlobalScope`, keine hardcodierten Pfade.
- Snackbar via `SnackbarHostState`, Messages via SharedFlow aus ViewModel.
- `rememberSaveable` fuer UI-State der Expands.
- Commit-Format: `T52: Kurzbeschreibung` + Bullets. Gerne mehrere Commits (z.B. 1. Buttons+Snackbar, 2. FAB-3-State, 3. Analyse mm:ss+MB, 4. Regress-Fix).

---

## Handover

`Handover_T52.md` im Projekt-Root mit:

1. Zusammenfassung
2. Dateiliste (neu / geaendert)
3. Pro AC einzeln: erfuellt / teilweise / offen
4. Screenshots: FAB-Blau-Phase, FAB-Gruen-Phase, Snackbar mit Undo, Session-Card mit mm:ss und MB
5. Entscheidungen (z.B. `RecordingPhase` vs. `prerollBufferFilledSamples`)
6. Offene Punkte
7. Scope-Einhaltung bestaetigt (keine Sonogramm-, GPS-, Recording-Start-Aenderungen)

---

## Kritische Dateien

**Haupt:**
- `ui/live/LiveScreen.kt` (FAB, Status-Zeile, GPS-Bar, Snackbar)
- `ui/live/LiveViewModel.kt` (FAB-State-Mapping, Debounce, Snackbar-Channel, Undo-Stack)
- `ui/live/LiveUiState.kt` (neue Felder)
- `ui/components/SpeciesCard.kt` (Buttons, Rand, Haptik, Top-N-Lokalisierung)
- `ui/components/DetectionList.kt` (Top-N-State-Key)
- `ui/components/SessionCard.kt` (mm:ss, MB, Instant.parse-Regress)
- `ui/analysis/AnalysisViewModel.kt` (WAV-Groesse ermitteln)
- `ui/settings/SettingsScreen.kt` (Toggle "Artvorschlaege anzeigen")
- `data/AppPreferences.kt` (neuer Key)
- `audio/RecordingService.kt` (RecordingPhase oder prerollBufferFilled-StateFlow)

**Nicht anfassen:**
- Sonogramm-Pfad (`SpectrogramCanvas.kt`, `SpectrogramState.kt`, `DynamicRangeMapper.kt`, `MelSpectrogram.kt`)
- Inference (`ml/**`)
- GPS-Logik (`LocationProvider.kt`, nur Consumer-Seite in LiveScreen darf Accuracy zeigen)
- WAV / Session-Persistenz / JSONL-Format
- C++ / NDK
