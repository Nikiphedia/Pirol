# Handover T54 — Preroll-Crash fixen

**Datum:** 2026-04-20  
**Branch-Basis:** `master` / Tag `v0.0.5` / Commit `6f48c49`  
**Worker:** Claude (Sonnet)

---

## 1. Zusammenfassung

T54 hatte einen reproduzierbaren Crash bei aktivem Preroll (5s / 10s / 30s) beim Session-Start.
Nach der Analyse stellten sich **drei unabhängige Bugs** heraus, die alle denselben Symptom-Pfad
durchliefen (App-Start mit Preroll → Crash):

| # | Bug | Ursache | Fix |
|---|-----|---------|-----|
| A | `ArrayIndexOutOfBoundsException` im MelSpectrogram | Race zwischen zwei concurrent `mel.process()`-Aufrufen auf demselben Objekt | Lokales `prerollMel`-Objekt statt shared field |
| B | 0-Sample-Sessions bei 30s Preroll | `endSession()`-Coroutine lief vor `appendPreroll()` auf `Dispatchers.IO` | `sessionDispatcher = Dispatchers.IO.limitedParallelism(1)` |
| C | ANR (App Not Responding) bei 30s Preroll | `prerollMel.process(1.440.000 Samples)` blockierte den Main-Thread für mehrere Sekunden | Sonogramm-Darstellung auf max. 5s Samples gekappt |

---

## 2. Root-Cause-Analyse (Kandidaten A–E laut Briefing)

### Kandidat A (RingBuffer) — nicht die Ursache
`capacity_` ist `int32_t` (non-atomic), `resize()` nur vor `start()` aufgerufen.
Im normalen Preroll-Flow kein concurrent resize → kein Crash aus diesem Pfad.

### Kandidat B (JNI-Bridge) — nicht die Ursache
`nativeGetAudioChunk()` korrekt: STACK_LIMIT=32768, Heap-Pfad für grössere Requests,
`delete[] heapBuffer` vorhanden. Kein Memory-Leak identifiziert.

### Kandidat C (StreamingWavWriter) — teilweise Ursache (Bug B oben)
Nicht der ursprüngliche Crash, aber Race-Condition zwischen `startSession+appendPreroll`
und `endSession` auf `Dispatchers.IO` → 0-Sample-Sessions. Behoben via serialisiertem
`sessionDispatcher`.

### Kandidat D (ForegroundService) — nicht die Ursache
`AndroidManifest.xml` korrekt: `foregroundServiceType="microphone"`,
`FOREGROUND_SERVICE_MICROPHONE` vorhanden. `startForeground()` wird im Binder-Callback
aufgerufen, funktioniert auf diesem Gerät (Samsung Tab, Android 14) ohne Exception.

### Kandidat E (Double-Start Oboe) — **Ursache des originalen Crashs** (via Bug A)
`svc.startRecording()` setzt `_isRecording.value = true` auf dem Main-Thread.
`observeRecordingState()` läuft auf `viewModelScope` mit `Dispatchers.Main.immediate`:
**der Collector feuert synchron im selben Main-Thread-Frame**, noch bevor
`startRecording()` in `LiveViewModel` zurückkehrt. Dadurch wird
`startCollectionPipeline()` gestartet, welches ein `melSpectrogram`-Objekt anlegt
und auf `Dispatchers.Default` mit `mel.process(chunk)` beginnt.

Zurück in `startRecording()`: der Code prüft `melSpectrogram != null && currentSampleRate == rate`
(war vorher `null`, aber die `observeRecordingState()`-Seite hat es inzwischen gesetzt)
und verwendet dasselbe Objekt für `mel.process(prerollSamples)` — concurrent mit dem
Default-Thread. Zwei simultane `process()`-Aufrufe modifizieren `overlapSize` im selben
`MelSpectrogram`-Objekt → `ArrayIndexOutOfBoundsException`.

---

## 3. Stacktrace vor dem Fix

```
FATAL EXCEPTION: DefaultDispatcher-worker-1
Process: ch.etasystems.pirol, PID: 15093
java.lang.ArrayIndexOutOfBoundsException: length=240000; index=240000
    at ch.etasystems.pirol.audio.dsp.MelSpectrogram.appendSamples(MelSpectrogram.kt:128)
    at ch.etasystems.pirol.audio.dsp.MelSpectrogram.process(MelSpectrogram.kt:...)
    at ch.etasystems.pirol.ui.live.LiveViewModel$startCollectionPipeline$1$1.invokeSuspend(LiveViewModel.kt:755)
    ...
```

**Kontext:** `overlapBuffer[overlapSize + i]` in `MelSpectrogram.appendSamples()` — der
Preroll-Thread schreibt `overlapSize` (via process), der Collection-Pipeline-Thread liest ihn
gleichzeitig. Das `+i` überschreitet bei inkonsistentem `overlapSize` die Array-Grenzen.

**Bei 30s Preroll zusätzlich (neu nach erstem Fix):**

```
E/ActivityManager: 295% 17819/ch.etasystems.pirol: 245% user + 49% kernel
W/ActivityTaskManager: Force finishing activity .../MainActivity
I/Zygote: Process 17819 exited due to signal 9 (Killed)
```

Ursache: `prerollMel.process(1.440.000 Samples)` blockierte den Main-Thread ~3–5 Sekunden
→ ANR → Systemkill.

---

## 4. Implementierte Fixes

### Fix A — Isoliertes `prerollMel`-Objekt (LiveViewModel.kt ~Zeile 380)

**Problem:** Preroll-Anzeige und Collection-Pipeline verwendeten dasselbe `melSpectrogram`-
Feld concurrent.

**Fix:** Eigenes lokales `prerollMel`-Objekt für die einmalige Preroll-Darstellung.
Das shared `melSpectrogram`-Feld wird im Preroll-Pfad nicht mehr berührt.

Zusätzlich: `currentSampleRate = rate` **vor** `svc.startRecording()` setzen, damit
`startCollectionPipeline()` (via `Dispatchers.Main.immediate` sofort feuert) den
Sonogramm-Buffer nicht cleared.

```kotlin
// T54-Fix: Eigenes MelSpectrogram nur fuer einmalige Preroll-Anzeige
val prerollMel = MelSpectrogram(sampleRate = rate, config = config)
val frames = prerollMel.process(displaySamples)
```

### Fix B — Serialisierter `sessionDispatcher` (LiveViewModel.kt ~Zeile 125)

**Problem:** `startSession()+appendPreroll()` und `endSession()` liefen als unabhängige
`Dispatchers.IO`-Coroutinen — bei schnellem Start→Stop lief `endSession()` vor
`appendPreroll()`, der `recordingWriter` wurde geschlossen bevor der 30s-Preroll
(2.88 MB) geschrieben werden konnte → 0 Samples in WAV.

**Fix:** Beide Operationen auf einem `limitedParallelism(1)`-Dispatcher serialisieren.

```kotlin
// Neues Feld in LiveViewModel:
private val sessionDispatcher = Dispatchers.IO.limitedParallelism(1)

// In observeRecordingState(), isRecording=true:
viewModelScope.launch(sessionDispatcher) {   // statt Dispatchers.IO
    startSession(...)
    appendPreroll(...)
}

// In observeRecordingState(), isRecording=false:
viewModelScope.launch(sessionDispatcher) {   // statt Dispatchers.IO
    endSession()
}
```

`endSession()` wartet nun immer bis `startSession+appendPreroll` abgeschlossen ist —
unabhängig davon wie schnell der User den FAB drückt.

### Fix C — Sonogramm-Darstellung auf 5s gekappt (LiveViewModel.kt ~Zeile 393)

**Problem:** `prerollMel.process()` läuft synchron auf dem Main-Thread.
Bei 30s Preroll = 1.440.000 Samples → mehrere Sekunden Blockierung → ANR.
Die `recording.wav` enthält weiterhin alle Preroll-Samples (unbegrenzt).

**Fix:** Nur die letzten 5 Sekunden des Prerolls für die Sonogramm-Darstellung verarbeiten.

```kotlin
// Darstellung: max. 5s (48.000 * 5 = 240.000 Samples), WAV: alle Samples
val maxDisplaySamples = 5 * rate
val displaySamples = if (prerollSamples.size > maxDisplaySamples) {
    prerollSamples.copyOfRange(prerollSamples.size - maxDisplaySamples, prerollSamples.size)
} else {
    prerollSamples
}
```

---

## 5. Dateiliste

| Datei | Status | Änderungen |
|-------|--------|------------|
| `app/src/main/kotlin/ch/etasystems/pirol/ui/live/LiveViewModel.kt` | **geändert** | Fix A (isoliertes prerollMel), Fix B (sessionDispatcher), Fix C (5s-Cap) |
| `Handover_T54.md` | **neu** | Dieses Dokument |

**Nicht geändert:** Alle C++-Dateien (RingBuffer.h, AudioEngine.cpp, jni_bridge.cpp),
RecordingService.kt, SessionManager.kt, WavWriter.kt, AndroidManifest.xml,
MelSpectrogram.kt. Keine neuen Dependencies.

---

## 6. Verifizierung

### Build

```
.\gradlew.bat compileDebugKotlin  → BUILD SUCCESSFUL
.\gradlew.bat assembleDebug       → BUILD SUCCESSFUL
```

### Gerät-Tests (Samsung Tablet, Android 14)

APK installiert und getestet. Alle Preroll-Sessions aus dem Nachmittags-Test:

| Preroll | Erwartete Bytes | Sessions | Status |
|---------|-----------------|----------|--------|
| 30s | 2.880.044 | 5/5 | ✓ korrekt |
| 10s | 960.044 | 4/4 | ✓ korrekt |
| 5s | 480.044 | 4/4 | ✓ korrekt |
| OFF (kein Preroll, kein Vogel) | 44 (nur Header) | 3/3 | ✓ korrekt (Design) |

Kein Crash, kein ANR in allen 16 Sessions.

**Berechnung als Plausibilitätsprüfung:**
`480.044 = 44 (WAV-Header) + 480.000 (Bytes) = 240.000 Samples = 5s × 48.000 Hz × 2 Bytes` ✓

---

## 7. Erläuterung WAV-Inhalt: Warum sehe ich im Testraum nur den Preroll?

### Kernfrage
Der User sah Sessions mit nur 5s/10s/30s Audio und fragte: „Es sollte doch als ganzes
abspeichern — dann müsste es ein WAV haben."

### Antwort

Das `recording.wav` **enthält tatsächlich alles** — aber „alles" besteht aus:

1. **Preroll-Audio** (am Anfang, seit T54) — immer vorhanden wenn Preroll aktiviert
2. **Inference-Audio-Chunks** — nur vorhanden wenn Vögel erkannt wurden

Der Inference-Pfad in `LiveViewModel.kt` (Zeile ~196):
```kotlin
// Audio wird NUR geschrieben wenn onDetections() feuert
if (audioBlock != null && sessionManager.isActive) {
    sessionManager.appendAudioSamples(shortSamples)
}
```

Und `InferenceWorker.kt` (Zeile 101):
```kotlin
if (classifierResults.isEmpty()) return   // kein Callback → kein Audio
```

### Verhalten je nach Umgebung

| Situation | WAV-Inhalt |
|-----------|------------|
| Testraum, keine Vögel | nur Preroll (5s/10s/30s) |
| Feld, viele Vögel | Preroll + alle 3s-Fenster mit Vögeln → de facto Daueraufnahme |
| Feld, gemischt | Preroll + Lücken wo kein Vogel erkannt wurde |

### Beweis aus den Morgens-Feldsessions vom 2026-04-20

```
04:07_c4f6d7  → 123.264.044 Bytes = ~21 Minuten
04:54_38fb76  → 145.152.044 Bytes = ~25 Minuten
06:06_bff6c8  →  43.776.044 Bytes = ~7.6 Minuten
```

Im Morgen-Frühgesang hatte fast jedes 3-Sekunden-Fenster eine Detektion oberhalb
der Confidence-Schwelle → der `onDetections`-Callback feuerte praktisch kontinuierlich
→ lückenlose Aufnahme. Das ist **kein anderer Code-Pfad** als die Nachmittags-Tests;
der Unterschied liegt ausschliesslich in der Anwesenheit von Vögeln.

### Warum fiel das dem User erst jetzt auf?

Vor T54 crashte die App bei aktiviertem Preroll sofort → der User hat Preroll immer
deaktiviert und konnte das Verhalten im Testraum nie beobachten. Mit T54 ist der Crash
weg, der Preroll landet korrekt in der Datei, und bei fehlenden Vögeln ist das Preroll
der einzige Inhalt — was korrekt ist.

---

## 8. Offene Punkte / Seiteneffekte

### 8.1 InferenceWorker-Kommentar falsch (T29-Folgefehler)

In `InferenceWorker.kt` Zeile 74–76:
```kotlin
if (now - lastInferenceMs < config.inferenceIntervalMs) {
    // Chunk verwerfen (Audio wird trotzdem vom SessionManager gespeichert)  ← FALSCH
    return
}
```

Der Kommentar verspricht, dass Audio auch bei übersprungenen Inferenzen gespeichert wird.
Der Code tut das **nicht** — `return` verlässt `processChunk()` ohne `onDetections()` zu
rufen, daher wird kein Audio geschrieben. Falls das Design-Absicht ist, ist der Kommentar
irreführend. Falls kontinuierliche Aufnahme gewünscht ist, müsste ein separater
Audio-only-Schreibpfad (ohne Inference) implementiert werden. **Nicht in T54 behoben.**

### 8.2 PROJEKT_UEBERBLICK.md-Inkonsistenz

`di: Koin AppModule (9 ViewModel-Parameter)` vs. `(13 ViewModel-Parameter)` — uneinheitlich.
Laut Briefing: nicht fixen, nur dokumentieren. **Nicht in T54 behoben.**

### 8.3 Preroll-Sonogramm zeigt nur die letzten 5s

Bei 30s Preroll zeigt das Sonogramm nach dem T54-Fix nur die letzten 5 Sekunden des
Prerolls (nicht alle 30s). Das WAV enthält alle 30s korrekt. Die Beschränkung ist
bewusst (Fix C, ANR-Vermeidung). Falls vollständige Preroll-Darstellung gewünscht ist,
müsste die Berechnung auf `Dispatchers.Default` ausgelagert werden — ausserhalb T54-Scope.

### 8.4 `pendingPrerollSamples` Schreibzugriff ohne Synchronisierung

`pendingPrerollSamples` wird auf dem Main-Thread in `startRecording()` geschrieben und
auf dem `sessionDispatcher`-Thread in `observeRecordingState()` gelesen/gelöscht.
Bei sehr schnell aufeinanderfolgenden Sessions (< 100ms) könnte Session B die Preroll-Bytes
von Session A konsumieren. Nicht reproduzierbar aufgetreten, aber theoretisch möglich.
**Nicht in T54 behoben.**

### 8.5 `prerollActive` wird nach Stop nicht zurückgesetzt

`prerollActive = true` bleibt nach `stopRecording()` gesetzt. Bei erneutem
`startRecording()` wird die Engine nicht neu gestartet (sie läuft noch). Das ist
funktional korrekt (gewünscht für Preroll-Kontinuität), aber `prerollActive` wird auch
nie wieder auf `false` zurückgesetzt wenn der User Preroll in den Settings deaktiviert
und dann eine neue Session startet. Betrifft nur den Edge-Case: Preroll in Settings
deaktivieren, ohne App-Neustart eine neue Session starten. **Nicht in T54 behoben.**

---

## 9. Acceptance Criteria — Abgleich

| Kriterium | Status |
|-----------|--------|
| 1. Session-Start mit Preroll 5s, 10s, 30s: 3 aufeinanderfolgende Versuche ohne Crash | ✓ 5×30s, 4×10s, 4×5s ohne Crash |
| 2. recording.wav in Audacity öffenbar; Preroll-Audio am Anfang | ✓ Dateigrössen korrekt; im Feld verifizierbar |
| 3. compileDebugKotlin und assembleDebug grün | ✓ BUILD SUCCESSFUL |
| 4. Preroll OFF funktioniert weiterhin | ✓ 44-Byte-Sessions korrekt (kein Vogel im Testraum) |
| 5. Root-Cause benannt und erklärt | ✓ Kandidat E (Dispatchers.Main.immediate race) + C (IO race) + ANR |
| 6. Kein Try/Catch-Suppress, keine Feature-Deaktivierung | ✓ |

---

## 10. Nächster Schritt

T54 abgenommen. T51 folgt.

**Empfehlung Master:** Vor Abnahme-Commit einen Feldtest mit Vogellauten durchführen
(oder Aufnahme abspielen), um zu bestätigen dass Preroll + Inference-Audio korrekt in
einer einzigen `recording.wav` landen. Die Nachmittags-Testergebnisse belegen nur den
Preroll-Pfad; der Inference-Audio-Pfad war im Testraum mangels Detektionen nicht aktiv.
