# T54 — Preroll-Crash fixen

## Ziel

Die App stürzt bei aktivem Preroll (5 s / 10 s / 30 s) reproduzierbar beim Session-Start ab. Nach diesem Task startet die Aufnahme mit aktiviertem Preroll in allen drei Längen ohne Absturz, und die resultierende `recording.wav` enthält den Preroll-Audio am Dateianfang (direkt vor dem Start-FAB-Tap-Zeitpunkt).

**Dies ist ein Hotfix-Task** — keine UX-Änderungen, keine Refaktorierung, nur Root-Cause-Fix.

---

## Projekt-Kontext

**Package:** `ch.etasystems.pirol`
**Pfad:** `D:\80002\PIROL`
**Branch-Basis:** `master` (aktuell auf Tag `v0.0.5`, Commit `6f48c49`)
**Build-Befehl:** `.\gradlew.bat compileDebugKotlin` (schnell, kein Device) / `.\gradlew.bat assembleDebug` (voller Build)
**Sprache:** Kotlin 2.1.0, Jetpack Compose, Material 3, NDK (C++17) für Oboe

**Feldtest-Befund (2026-04-20):** "Tragisch ist der Bug mit dem Preroll, bei aktivierter Preroll stürzt die App immer noch ab." Der User hat einen längeren Testtag hinter sich; der Crash ist zuverlässig reproduzierbar bei **jedem** Session-Start mit aktivem Preroll.

**Preroll-Architektur (Kontext):** Bei aktivem Preroll werden vor dem eigentlichen Session-Start bereits N Sekunden Audio in einen Ring-Buffer mitgeschnitten. Beim FAB-Tap wird der Ring-Buffer-Inhalt als erstes in die `recording.wav` geschrieben (via `StreamingWavWriter` / `RandomAccessFile`-Header-Finalisierung beim Stop). Die Engine läuft also schon **vor** dem Service-Start.

---

## Tablet-Debug-Freigabe

**Der Worker darf und soll das über USB angeschlossene Tablet für Live-Debugging nutzen.**

```cmd
set PATH=%PATH%;C:\Users\nleis\AppData\Local\Android\Sdk\platform-tools
adb devices                                          # Tablet muss als "device" erscheinen
adb logcat -c                                        # Log clearen vor Repro
adb logcat *:E AndroidRuntime:E PirolApp:V > crash.log
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell run-as ch.etasystems.pirol ls files/sessions/
```

Falls `adb` nicht im PATH ist, liegt es hier:
`C:\Users\nleis\AppData\Local\Android\Sdk\platform-tools\adb.exe`

---

## Schritt 1 — Reproduktion und Stacktrace

1. Debug-APK auf Tablet installieren (`adb install -r`).
2. In der App: Settings → Erkennung → Preroll auf **5 s** stellen.
3. Parallel `adb logcat *:E AndroidRuntime:*` laufen lassen (eigenes Fenster).
4. Live-Tab → grünen FAB drücken → Crash provozieren.
5. Stacktrace + umliegende Log-Zeilen sichern (vollständig, inkl. JNI-Abort-Messages falls vorhanden).
6. Gleiches mit **10 s** und **30 s** Preroll — unterschiedliches Crash-Verhalten? Selber Stacktrace?
7. Stacktrace in Handover dokumentieren.

**Falls kein Crash reproduzierbar:** Mit anderen Konfigurationen testen (mit/ohne GPS-Permission, mit/ohne Hochpassfilter, 48 kHz vs. 96 kHz). Erst wenn Repro steht, weiter zu Schritt 2.

---

## Schritt 2 — Root-Cause-Analyse

**Vermutliche Fehlerquellen** (Worker priorisiert nach Stacktrace, nicht nach dieser Liste):

### Kandidat A — RingBuffer-Überlauf / Lese-Race
`app/src/main/cpp/RingBuffer.h` — SPSC-RingBuffer, 30 s Kapazität laut Briefing.
- Wenn Preroll = 30 s eingestellt ist, könnte der Lese-Index den Schreib-Index überholen oder umgekehrt.
- Prüfen: Capacity ausreichend? Atomics korrekt? `std::memory_order`-Semantik passt?

### Kandidat B — JNI-Bridge `chunk_000.wav`-Legacy
`app/src/main/cpp/jni_bridge.cpp`, `app/src/main/cpp/AudioEngine.cpp` / `.h`.
- JNI-Referenzen (jobject, jbyteArray) korrekt released? `DeleteLocalRef`?
- Pointer-Lifetime gegenüber Kotlin-Seite?

### Kandidat C — StreamingWavWriter Header-Init mit Preroll-Daten
`app/src/main/kotlin/ch/etasystems/pirol/data/repository/WavWriter.kt` — StreamingWavWriter schreibt via `RandomAccessFile`.
- Header wird am **Stop** finalisiert. Was passiert, wenn Preroll-Bytes am Anfang geschrieben werden, bevor der Header-Platzhalter steht?
- Reihenfolge: `openFile()` → Header-Platzhalter schreiben → Preroll-Bytes appenden → live-Samples appenden → `close()` seekt zurück auf Header-Offset und finalisiert?
- Falls `openFile()` erst beim ersten Live-Sample passiert, gehen Preroll-Bytes ins Leere oder krachen in eine `FileNotFoundException`.

### Kandidat D — RecordingService-Start vs. Foreground-Notification
`app/src/main/kotlin/ch/etasystems/pirol/audio/RecordingService.kt`.
- Android 14+ erzwingt `startForeground()` innerhalb 5 s mit `foregroundServiceType`. Wenn Preroll-Flush die Startup-Latenz zieht, kann `ForegroundServiceDidNotStartInTimeException` fliegen.
- `AndroidManifest.xml` prüfen: `foregroundServiceType="microphone"` korrekt deklariert? Permission `FOREGROUND_SERVICE_MICROPHONE`?

### Kandidat E — Oboe-Stream wird vor Service-Start gestartet
`app/src/main/kotlin/ch/etasystems/pirol/audio/OboeAudioEngine.kt`.
- Wenn Preroll läuft, muss `OboeAudioEngine.start()` **vor** Session-Start gecallt worden sein. Wer ruft das? `PirolApp`? ViewModel-Init?
- Bei `onDestroy` der Activity ohne `stop()` → Engine weiterhin an → zweiter Start beim FAB-Tap crashed mit `OBOE_ERROR_ALREADY_STARTED` oder ähnlich.

---

## Schritt 3 — Fix

Root-Cause beseitigen. **Keine symptomatischen Workarounds** (z. B. try/catch um den Crash und Preroll deaktivieren). Der Fix muss Preroll in allen drei Längen funktional lassen.

**Wenn der Fix eine Interface-Änderung erfordert** (z. B. `WavWriter`-API anders), Master informieren bevor gemergt wird — kann Auswirkungen auf T46-Architektur haben.

**Keine Änderungen an:**
- WAV-Format (16-bit PCM Mono 48 kHz bleibt bit-identisch)
- `session.json`-Schema
- UI-Flow oder Settings-Layout
- Anderen Modulen (DSP, ML, GPS, Map)

---

## Schritt 4 — Regression-Tests

1. `.\gradlew.bat compileDebugKotlin` — muss grün bleiben.
2. `.\gradlew.bat assembleDebug` — muss APK produzieren.
3. APK auf Tablet installieren.
4. **3× pro Preroll-Länge** (5 s, 10 s, 30 s) = 9 Start/Stop-Zyklen. Kein Crash, jede `recording.wav` öffnet in Audacity / Cornell Raven fehlerfrei.
5. **Preroll OFF** → weiterhin funktional (Regression-Check gegen V0.0.5-Baseline).
6. Verifizieren in Audacity: Die ersten N Sekunden der WAV sind tatsächlich Preroll-Audio (vor dem FAB-Tap-Zeitpunkt erkennbar).
7. 5-Min-Session mit 10 s Preroll durchlaufen lassen → `session.json` + `detections.jsonl` + `recording.wav` konsistent, keine Warnings in Logcat.

---

## Acceptance Criteria

1. Session-Start mit Preroll 5 s, 10 s, 30 s: jeweils 3 aufeinanderfolgende Versuche **ohne Crash**.
2. Resultierende `recording.wav` in Audacity öffenbar; Waveform zeigt Preroll-Audio vor dem Start-Marker.
3. `compileDebugKotlin` und `assembleDebug` grün.
4. Preroll OFF funktioniert weiterhin (keine Regression).
5. Root-Cause ist im Handover benannt und erklärt (welcher Kandidat A–E oder neuer Fund, warum der Fix ihn löst).
6. Kein Try/Catch-Suppress, keine Feature-Deaktivierung als "Fix".

---

## Scope-Ausschluss

- Kein Refactor der Preroll-Architektur.
- Keine UX-Änderung.
- Kein Hotfix an anderer Stelle (auch wenn beim Debuggen andere Bugs auffallen — nur dokumentieren, nicht fixen).
- Keine neuen Abhängigkeiten.
- Keine Änderung an `master-briefing.md` / `PROJEKT_UEBERBLICK.md` / `CLAUDE.md` (macht Master nach Abnahme).

---

## Normung (aus CLAUDE.md)

- Kotlin 2.1, Jetpack Compose / Material 3, Koin DI.
- Kommentare auf Deutsch, Bezeichner auf Englisch.
- Keine `Thread.sleep`, kein `GlobalScope`, keine hardcodierten Pfade.
- Coroutines via `viewModelScope` / Service-Scope / strukturiert.
- Commit-Format: `T54: Kurzbeschreibung` + Bullet-Details.

---

## Relevante Dateien — alle vor Änderung vollständig lesen

- `app/src/main/AndroidManifest.xml` (Foreground-Service-Type, Permissions)
- `app/src/main/cpp/RingBuffer.h`
- `app/src/main/cpp/AudioEngine.h`, `AudioEngine.cpp`
- `app/src/main/cpp/jni_bridge.cpp`
- `app/src/main/cpp/CMakeLists.txt` (nur falls relevant)
- `app/src/main/kotlin/ch/etasystems/pirol/audio/OboeAudioEngine.kt`
- `app/src/main/kotlin/ch/etasystems/pirol/audio/RecordingService.kt`
- `app/src/main/kotlin/ch/etasystems/pirol/data/repository/WavWriter.kt`
- `app/src/main/kotlin/ch/etasystems/pirol/ui/live/LiveViewModel.kt`
- `app/src/main/kotlin/ch/etasystems/pirol/ui/live/LiveScreen.kt` (nur FAB-Handler)
- `app/src/main/kotlin/ch/etasystems/pirol/data/AppPreferences.kt` (Preroll-Keys)

---

## Handover

Nach Abschluss `Handover_T54.md` im Projekt-Root mit:

1. **Zusammenfassung:** Was war die Root-Cause? Welcher Fix?
2. **Stacktrace vor dem Fix** (vollständig, aus Schritt 1).
3. **Dateiliste** (neu / geändert / gelöscht).
4. **Verifizierung:** Build grün, 9/9 Start-Stop-Zyklen ohne Crash, Audacity-Check bestätigt Preroll-Audio.
5. **Offene Punkte / Seiteneffekte:** alles was auffiel, aber laut Scope nicht gefixt wurde.
6. **Nächster Schritt:** "T54 abgenommen, T51 folgt."

---

## Briefing-Inkonsistenzen, falls sie auffallen

- `PROJEKT_UEBERBLICK.md` erwähnt `di: Koin AppModule (9 ViewModel-Parameter)` vs. `(13 ViewModel-Parameter)` uneinheitlich. Nicht fixen, nur im Handover erwähnen.
- CLAUDE.md-Verzeichnisstruktur zeigt `chunk_000.wav` — Legacy aus Pre-T46, nicht mehr aktuell (seit T46 nur `recording.wav`). Master aktualisiert separat.
