# T51 — Storage-Layout, Auto-Raven, Daueraufnahme, KML weg

## Ziel

Session-Daten landen in einem **oeffentlichen, tages-strukturierten Ordner** (`Downloads/PIROL/YYYY-MM-DD/{sessionId}/`), sofort per USB/MTP/Dateimanager sichtbar. Raven Selection Table wird **automatisch** beim Session-Stop neben der WAV geschrieben. Aufnahme laeuft **kontinuierlich** — nicht nur waehrend BirdNET Detektionen findet. KML-Export wird vollstaendig entfernt. Alle Timestamps bekommen **Zonen-Offset** (`+02:00`). Analyse-Tab refresht die Session-Liste beim Tab-Wechsel statt nur im `init {}`.

Nach diesem Task:
- Aufnahme startet → Datei-Manager zeigt sofort `Downloads/PIROL/2026-MM-DD/{id}/recording.wav` (waechst live).
- Session-Stop erzeugt im gleichen Ordner `recording.selections.txt` (Raven-TSV) automatisch, ohne Button-Klick.
- `recording.wav` ist eine lueckenlose Daueraufnahme (Preroll + alle folgenden Chunks).
- Analyse-Tab listet neu aufgenommene Sessions **sofort** beim Tab-Wechsel, ohne App-Neustart.
- Kein KML-Code, kein KML-Button, kein KML-Verweis mehr im Projekt.

---

## Projekt-Kontext

**Package:** `ch.etasystems.pirol`
**Pfad:** `D:\80002\PIROL`
**Branch-Basis:** `master` (aktueller Stand nach T54-Merge, Commit `70c932d`+)
**Build:** `.\gradlew.bat compileDebugKotlin` (schnell) / `.\gradlew.bat assembleDebug` (voller Build).
**Sprache:** Kotlin 2.1.0, Jetpack Compose, Material 3, Koin DI.

**Feldtest-Befund (2026-04-20):** User hat V0.0.5 einen Tag lang im Feld genutzt. Kernprobleme, die dieses AP adressiert:

1. Speicherort ist nur per `adb run-as` zugaenglich → Feldtest-Daten muehsam zu bergen.
2. Raven-Export nur ueber Share-Intent → unbequem, unzuverlaessig.
3. Aufnahme hat Luecken, wenn BirdNET in einem 3-Sekunden-Fenster nichts erkennt (siehe Handover T54, Punkt 8.1 — `InferenceWorker` schreibt Audio nur wenn Detektionen vorliegen). **Design-Entscheidung des Users: Daueraufnahme (Variante A, Merlin-Modell).**
4. Timestamps ohne Offset → Raven-TSV und `session.json` nicht eindeutig ohne Nachschlagen der Geraete-Zeitzone.
5. KML-Export wird nicht genutzt, macht Code-Ballast.
6. Analyse-Tab zeigt neu aufgenommene Sessions erst nach App-Neustart — User-Bug vom 2026-04-20: *"Ich muss SW neu starten, dann sehe ich erst die neu aufgenommenen Files, auch die mit 0 Detection."*

---

## Tablet-Debug-Freigabe

**Der Worker darf und soll das ueber USB angeschlossene Samsung Galaxy Tab A11 fuer Live-Debugging nutzen.**

```cmd
set PATH=%PATH%;C:\Users\nleis\AppData\Local\Android\Sdk\platform-tools
adb devices                                                      # muss "device" zeigen
adb logcat -c                                                    # Log clearen
adb logcat *:E PirolApp:V SessionManager:V AnalysisVM:V > run.log
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell ls /sdcard/Download/PIROL/                             # neuer Default-Pfad
adb shell run-as ch.etasystems.pirol ls files/sessions/          # alter Pfad (Migration)
adb pull /sdcard/Download/PIROL/2026-04-20/ .                    # Feldtest-Pull
```

Falls `adb` nicht im PATH: `C:\Users\nleis\AppData\Local\Android\Sdk\platform-tools\adb.exe`.

---

## Scope

### 1. Default-Speicherort umstellen

- **Neuer Default-Basispfad:** `Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS) / "PIROL"` (also `/sdcard/Download/PIROL/`).
- **Tages-Unterordner:** `YYYY-MM-DD` (ISO-Kurzform, **Lokalzeit** — nicht UTC; Ornithologe liest lokal).
- **Session-Ordner:** `{sessionId}` = `{iso-date}_{uuid6}` unveraendert (CLAUDE.md).
- **Flache Struktur** innerhalb des Session-Ordners: `session.json`, `detections.jsonl`, `verifications.jsonl`, `recording.wav`, `recording.selections.txt` — **kein `audio/`-Unterordner mehr**.
- Alt-Pfad `context.filesDir/sessions/` bleibt als **Fallback** (wenn SAF-URI nicht mehr zugreifbar oder Permission fehlt).

**Permission:** Auf Android 10+ (SDK 29) ist `DIRECTORY_DOWNLOADS` fuer die eigene App ohne `WRITE_EXTERNAL_STORAGE` beschreibbar via MediaStore-API oder direkten File-API bei `requestLegacyExternalStorage`. Saubere Loesung: **SAF (`ACTION_OPEN_DOCUMENT_TREE`) mit persistenter URI-Permission** fuer User-konfigurierten Pfad, Default auf `getExternalFilesDir(null)` → `/sdcard/Android/data/ch.etasystems.pirol/files/` wenn kein SAF-Pick vorliegt. **Diskussion noetig:** Feldornithologe will Dateien per USB sofort sehen; `getExternalFilesDir` ist zwar per USB sichtbar, aber im App-Sandbox-Unterordner. **Default-Entscheidung (User-Wunsch):** `Downloads/PIROL/` via SAF-Pick im Onboarding **einmal** anfragen und persistent speichern. Wenn User ablehnt → Fallback auf `getExternalFilesDir(null)/PIROL/` mit Banner-Hinweis.

### 2. Raven-Auto-Export

- `RavenExporter` wird am Ende von `SessionManager.endSession()` **automatisch** aufgerufen, schreibt `recording.selections.txt` neben `recording.wav` im Session-Ordner.
- Format 1:1 wie aktuell (TSV, Cornell-Raven-kompatibel).
- Bisherige manuelle `exportRavenTable()`-Methode im `AnalysisViewModel` bleibt fuer **Re-Export** nach nachtraeglichen Verifikationen (Verifikations-Updates, nicht fuer Erst-Export).
- Kein Share-Intent mehr beim Stop — Datei liegt einfach da.

### 3. KML-Export entfernen

- `app/src/main/kotlin/ch/etasystems/pirol/data/repository/KmlExporter.kt` loeschen.
- Alle Aufrufer (vermutlich `AnalysisViewModel`, `SettingsScreen`, `ShareHelper`) entkoppeln.
- Alle UI-Buttons mit KML-Bezug entfernen.
- String-Ressourcen mit `kml`/`KML`-Bezug bereinigen.
- Kein Ersatz, kein Deprecation-Warning, keine Migration — harter Cut.

### 4. Zeitzone: ISO 8601 mit Offset

- Alle Timestamps in `session.json`, `detections.jsonl`, `verifications.jsonl`, Raven-TSV (falls betroffen) bekommen **Zonen-Offset**: `2026-04-20T14:30:00+02:00`.
- Nicht mehr: `...Z` (UTC) oder nacktes `...T14:30:00`.
- Implementierung: `DateTimeFormatter.ISO_OFFSET_DATE_TIME` mit `ZonedDateTime.now(ZoneId.systemDefault())`.
- **Rueckwaertskompatibilitaet beim Lesen:** Parser in `SessionManager.loadMetadata()` und an anderen Lesestellen muss sowohl `...+02:00` als auch `...Z` als auch nackt akzeptieren (alte Sessions vor T51 haben Z/nackt). Use `OffsetDateTime.parse()` mit Fallback-Logik oder `DateTimeFormatter.ISO_DATE_TIME` der beides kann.

### 5. Daueraufnahme — Variante A (Merlin-Modell)

**Root-Ursache** (aus Handover T54, Punkt 8.1): `InferenceWorker.processChunk()` ruft `onDetections(detections, audioBlock)` nur, wenn `filteredResults.isNotEmpty()`. Dadurch schreibt `SessionManager.appendAudioSamples()` nur bei Detektionen — `recording.wav` hat Luecken.

**Fix:** `InferenceWorker` liefert den Audio-Block **immer**, sobald `AudioAccumulator.feed()` einen vollstaendigen 3-Sekunden-Block zurueckgibt. BirdNET-Inference laeuft weiter conditional (Intervall-Check, Schwelle, Regionalfilter) — das hat nichts mit der Audio-Persistenz zu tun.

**Architektur-Empfehlung** (Worker darf begruenden + umsetzen):
- Entweder **zwei Callbacks**: `onAudio(block)` (immer) und `onDetections(detections, block)` (nur bei Treffern).
- Oder **ein Callback** mit optionalem Detections-Parameter: `onChunkProcessed(block: AudioBlock, detections: List<DetectionResult>?)`.

Der Consumer (`LiveViewModel.kt`) leitet Audio **unabhaengig von Detektionen** an `sessionManager.appendAudioSamples()` weiter.

**Inference-Interval-Skip:** Der aktuelle Early-Return bei `now - lastInferenceMs < config.inferenceIntervalMs` (Zeile 73–77) darf **nicht mehr das Audio-Schreiben blockieren**. Entweder Skip nur fuer den Inference-Pfad, oder Skip komplett entfernen falls nicht mehr noetig.

**Chunk-Duplikate vermeiden:** Der neu geschriebene Daueraufnahme-Pfad muss sicherstellen, dass ein 3-Sekunden-Block nur **einmal** ins WAV geht — nicht einmal fuer Audio, einmal nochmal bei Detection. Pruefe Call-Sites sorgfaeltig.

**WAV-Header-Konsistenz:** `StreamingWavWriter` finalisiert den Header beim `close()`. Fuer kontinuierliche Aufnahme muss das weiterhin funktionieren — insbesondere, wenn Sessions jetzt deutlich groesser werden (10 MB/min statt nur Detections-Bytes). `RandomAccessFile` unterstuetzt grosse Dateien, aber Flush-Strategie pruefen: bei laengeren Sessions (1 h+) sollte periodisch `sync()` gerufen werden, damit nach App-Kill die Datei grossteils intakt ist.

**Kommentar korrigieren:** Der irrefuehrende Kommentar in `InferenceWorker.kt` Zeile 74 (`Audio wird trotzdem vom SessionManager gespeichert`) wird obsolet und muss durch eine korrekte Beschreibung ersetzt werden.

### 6. Settings: Speicherort-Sektion

Neue Sektion `"Speicherort"` im `SettingsScreen`:

- **Aktueller Basis-Pfad** als lesbare Darstellung (z. B. `Downloads/PIROL/`).
- **Button "Anderen Ordner waehlen"** → `ACTION_OPEN_DOCUMENT_TREE`-Intent, persistente URI-Permission speichern.
- **Toggle "Tages-Unterordner"** (default AN). Wenn aus: alle Sessions liegen direkt unter Basis-Pfad.
- **Preview-Zeile:** zeigt resultierenden Pfad fuer heutige Session, z. B. `Downloads/PIROL/2026-04-20/2026-04-20T08:30:00+02:00_a1b2c3/`.
- **Migration-Button "Bestehende Sessions verschieben"** → oeffnet Dialog (siehe Punkt 7).

Storage-Keys in `AppPreferences` (neu):
- `storageBaseUri: String?` (SAF-URI als String oder `null` fuer Fallback)
- `storageDailySubfolder: Boolean` (default `true`)

Bestehender Key `storagePath` wird nicht mehr direkt als `File(...)`-Pfad benutzt, sondern ersetzt durch URI-basierten Pfad. Migrations-Strategie in Code: Wenn `storagePath` gesetzt ist und `storageBaseUri` nicht, → alten Pfad als Basis nehmen (abwaertskompatibel, ein Release lang).

### 7. Migration bestehender Sessions

- Wenn User den Basis-Pfad aendert **oder** Tages-Unterordner umschaltet, bieten Dialog an: **"Bestehende N Sessions (M MB) an neuen Ort verschieben?"** mit Optionen **Ja / Nein / Spaeter**.
- **"Ja"** startet einen `CoroutineWorker` (WorkManager) mit Progress-Notification. Copy-dann-Delete (nicht move, wegen evtl. Filesystem-Grenzen zwischen internal/external). Bei Fehler: Source bleibt, Target wird bereinigt.
- Migration ist **idempotent**: bereits migrierte Sessions werden uebersprungen (Pruefung via Existenz der Ziel-Session-`.json`).
- Fortschritt: Notification mit `N / total`, kein modal dialog.

### 8. Fallback & Banner

- Wenn beim Session-Start `storageBaseUri` nicht mehr zugreifbar (User hat Permission in Android-Settings widerrufen, oder SD-Karte entfernt): Fallback auf `getExternalFilesDir(null)/PIROL/` + persistenter Banner im Live-Tab *"Speicherort nicht erreichbar — schreibe nach Fallback. Neu waehlen?"*.
- Kein stilles Schreiben in unerwartete Ordner.

### 9. Analyse-Tab Auto-Refresh

**Bug:** `AnalysisViewModel.loadSessions()` wird nur in `init {}` aufgerufen. Neu aufgenommene Sessions erscheinen erst nach App-Neustart.

**Fix:** Lifecycle-gebundener Refresh.
- In `AnalysisScreen.kt` via `LifecycleEventObserver` oder `LifecycleStartEffect` / `LaunchedEffect(Unit)` mit Composition-Aware-Key, sodass beim Tab-Wechsel (Bottom-Nav) `loadSessions()` erneut aufgerufen wird.
- Zusaetzlich: Nach `SessionManager.endSession()` koennte ein Flow-basiertes Signal in `AnalysisViewModel` ankommen. Minimal-Fix ist der Lifecycle-Refresh — das reicht fuer den gemeldeten Bug.
- **Acceptance:** Session starten → stoppen → Live-Tab → Analyse-Tab: neue Session steht sofort in der Liste (auch wenn 0 Detektionen).

---

## Scope-Ausschluss

- **Kein Cloud-Upload, kein Mehrgeraete-Sync.** (ZIP-Export via `SessionUploadWorker` bleibt unveraendert, nur der Ziel-Pfad innerhalb `Downloads/PIROL/` ist schon konsistent.)
- **Keine Aenderung am WAV-Format** (16-bit PCM Mono 48 kHz bleibt bit-identisch).
- **Keine Aenderung an `detections.jsonl`-Schema**, nur das Timestamp-Format-Feld (Offset).
- **Keine neue Session-ID-Logik** (bleibt `{iso-date}_{uuid6}`).
- **Keine UX-Aenderungen in Live-Tab oder Analyse-Tab ausser dem Auto-Refresh-Bug** (UX kommt in T52).
- **Keine Session-Rotation** (das ist T57 in V0.0.7).

---

## Acceptance Criteria

1. **Default-Install** + erste Session: `Downloads/PIROL/2026-04-20/{sessionId}/` enthaelt `session.json`, `detections.jsonl`, `verifications.jsonl`, `recording.wav`, **und** `recording.selections.txt` — alles **im Wurzel-Session-Ordner**, kein `audio/`-Unterordner.
2. **Raven-TSV automatisch** beim Session-Stop geschrieben. Datei oeffnet in Cornell Raven (oder Audacity als Label-Track) fehlerfrei.
3. **Daueraufnahme:** 5-Minuten-Session in ruhigem Raum (keine Vogelrufe) erzeugt `recording.wav` mit **erwarteter Dateigroesse = 44 Byte Header + 5 × 60 × 48.000 × 2 Byte = 28.800.044 Byte ± wenige Samples**. Nicht: 44 Byte (nur Header). Nicht: nur Preroll-Laenge.
4. **Zeitzone:** `session.json.startedAt` enthaelt `+02:00` (CEST bei Test in Europa). `detections.jsonl` ebenso. Alte Sessions mit `...Z`-Format bleiben **lesbar**.
5. **Settings:** Sektion "Speicherort" zeigt aktuellen Pfad, SAF-Picker funktioniert, Tages-Unterordner-Toggle wirkt, Preview-Zeile aktualisiert sich.
6. **Migration:** Mit 3 alten Sessions in `filesDir/sessions/` → nach SAF-Pick + Migration sind alle 3 in `Downloads/PIROL/YYYY-MM-DD/{id}/` und in `filesDir` geloescht. Progress-Notification sichtbar.
7. **Fallback:** Permission in Android-Settings manuell widerrufen → neuer Session-Start schreibt nach `getExternalFilesDir/PIROL/`, Banner im Live-Tab sichtbar.
8. **Analyse-Tab-Refresh:** Session aufnehmen (auch mit 0 Detektionen) → Tab-Wechsel Live → Analyse → neue Session in Liste. Kein App-Neustart noetig.
9. **KML vollstaendig weg:** `grep -ri "kml" app/src/main/` (case-insensitive) findet nichts mehr ausser evtl. harmlose Kommentare/History. Keine Klasse, kein Button, keine String-Resource, kein Import.
10. **Keine Chunk-Duplikate:** Jeder 3-Sekunden-Block landet exakt einmal im WAV. Verifizierbar: WAV-Dauer (aus Header) ≈ Session-Dauer (wall-clock) ± 1 Sekunde.
11. **Build:** `compileDebugKotlin` + `assembleDebug` gruen.
12. **Alt-Sessions (Pre-V0.0.3)** mit Chunk-Struktur bleiben im Analyse-Tab sichtbar mit Banner (wie bisher, keine Regression).

---

## Regression-Test-Plan

1. **3× neue Session** im Default-Pfad aufnehmen (je 2 Min, mit/ohne Vogelsound via Lautsprecher). Verifizieren: Dateigroesse waechst linear mit Zeit.
2. **3× Session mit Preroll aktiv** (5s/10s/30s) — keine Regression zu T54; WAV enthaelt Preroll + Daueraufnahme.
3. **Raven-TSV** in Cornell Raven oeffnen — eine Testsession mit bekannten Rufen, Label-Zeiten matchen WAV-Offsets.
4. **Analyse-Tab**: nach Session-Stop direkt Tab wechseln → Session da. Alte Sessions mit Chunk-Struktur laden noch (Banner, kein Play).
5. **SAF-Widerruf**: User widerruft Permission in Android-Settings → naechste Session schreibt nach Fallback, Banner sichtbar.
6. **Migration**: 2 alte Sessions anlegen (entweder durch Testlauf auf Alt-Pfad oder manuell kopieren), neuen Pfad setzen, migrieren, alle Sessions erscheinen am neuen Ort.
7. **Zeitzone**: `session.json.startedAt` per `adb pull` ansehen — enthaelt `+XX:XX`.
8. **KML-Check**: `grep -ri "kml" app/src/main/` liefert leer (case-insensitive).
9. **Keine Regression Live-Tab UX** (T52-Scope noch nicht drin, aber darf nicht kaputt sein): FAB startet/stoppt, Detektionen erscheinen, Verifikationen funktionieren wie in V0.0.5.

---

## Normung

- Kotlin 2.1.0, Jetpack Compose, Material 3, Koin 4.0.2.
- **Kommentare Deutsch, Bezeichner Englisch.**
- Keine `Thread.sleep`, kein `GlobalScope`, keine hardcodierten Pfade.
- Coroutines via `viewModelScope` / Service-Scope / `CoroutineWorker`.
- SAF-URI-Persistenz: `contentResolver.takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION)`.
- Migration als `CoroutineWorker`, nicht blockierend.
- Commit-Format: `T51: Kurzbeschreibung` + Bullet-Details. Gerne **mehrere Commits** in logischen Einheiten (z. B. 1. KML-Entfernung, 2. Daueraufnahme, 3. SAF+Settings, 4. Migration, 5. Analyse-Refresh).

---

## Relevante Dateien — vor Aenderung vollstaendig lesen

**Storage:**
- `app/src/main/kotlin/ch/etasystems/pirol/data/repository/SessionManager.kt` (Pfadlogik, `getBaseDir()`, `startSession()`, `endSession()`, `listSessions()`, `loadMetadata()`)
- `app/src/main/kotlin/ch/etasystems/pirol/data/repository/SessionMetadata.kt` (Timestamp-Felder)
- `app/src/main/kotlin/ch/etasystems/pirol/data/repository/WavWriter.kt` (`StreamingWavWriter`)
- `app/src/main/kotlin/ch/etasystems/pirol/data/AppPreferences.kt` (neue Keys)

**Auto-Raven:**
- `app/src/main/kotlin/ch/etasystems/pirol/data/export/RavenExporter.kt`
- `app/src/main/kotlin/ch/etasystems/pirol/data/repository/SessionManager.kt` (`endSession()` ruft Exporter)

**KML-Entfernung:**
- `app/src/main/kotlin/ch/etasystems/pirol/data/repository/KmlExporter.kt` (loeschen)
- `app/src/main/kotlin/ch/etasystems/pirol/data/repository/ShareHelper.kt` (KML-Aufrufer pruefen)
- `app/src/main/kotlin/ch/etasystems/pirol/ui/analysis/AnalysisViewModel.kt` (KML-Button-Logik)
- `app/src/main/kotlin/ch/etasystems/pirol/ui/analysis/AnalysisScreen.kt` (UI)
- `app/src/main/kotlin/ch/etasystems/pirol/ui/settings/SettingsScreen.kt` (falls dort KML-Bezug)
- `app/src/main/res/values/strings.xml` (String-Resources)
- `app/src/main/kotlin/ch/etasystems/pirol/di/AppModule.kt` (Koin-Binding falls vorhanden)

**Daueraufnahme:**
- `app/src/main/kotlin/ch/etasystems/pirol/ml/InferenceWorker.kt` (Audio-immer-liefern)
- `app/src/main/kotlin/ch/etasystems/pirol/ui/live/LiveViewModel.kt` (Consumer, `appendAudioSamples`-Call-Site)

**Zeitzone:**
- `app/src/main/kotlin/ch/etasystems/pirol/data/repository/SessionManager.kt` (Formatter)
- `app/src/main/kotlin/ch/etasystems/pirol/ml/DetectionResult.kt` (Timestamp-Serialisierung, falls relevant)
- alle weiteren Stellen mit `Instant.now()` / `DateTimeFormatter.ISO_INSTANT` — mit `grep -r "ISO_INSTANT\|Instant.now\|DateTimeFormatter" app/src/main/` auditieren

**Settings:**
- `app/src/main/kotlin/ch/etasystems/pirol/ui/settings/SettingsScreen.kt` (neue Sektion)
- `app/src/main/kotlin/ch/etasystems/pirol/ui/settings/SettingsViewModel.kt` (falls vorhanden)

**Migration:**
- neue Datei z. B. `app/src/main/kotlin/ch/etasystems/pirol/data/repository/SessionMigrationWorker.kt`
- `app/src/main/AndroidManifest.xml` (WorkManager-Init bereits drin, nur pruefen)

**Analyse-Tab-Refresh:**
- `app/src/main/kotlin/ch/etasystems/pirol/ui/analysis/AnalysisViewModel.kt` (loadSessions-Logik)
- `app/src/main/kotlin/ch/etasystems/pirol/ui/analysis/AnalysisScreen.kt` (Lifecycle-Observer)

---

## Handover

Nach Abschluss `Handover_T51.md` im Projekt-Root mit:

1. **Zusammenfassung:** Was wurde geaendert, in welcher Reihenfolge?
2. **Dateiliste** (neu / geaendert / geloescht) — vollstaendig.
3. **SAF-Verhalten** dokumentieren: Was passiert beim ersten Start (Permission-Flow), was bei Widerruf?
4. **Migration-Verhalten** dokumentieren: wie viele Sessions migriert, Fehlerfaelle, wie Abbruch behandelt?
5. **Daueraufnahme-Verifizierung:** Dateigroessen-Tabelle fuer Test-Sessions (z. B. 1 Min Stille = X Byte, 5 Min Feld = Y Byte), Dauer aus WAV-Header vs. Wall-Clock.
6. **Verifizierung:** `compileDebugKotlin`, `assembleDebug`, alle 12 Acceptance Criteria explizit abgehakt.
7. **Offene Punkte / Seiteneffekte:** alles was auffiel, aber laut Scope nicht gefixt wurde.
8. **Nächster Schritt:** "T51 abgenommen, T56 folgt."

---

## Briefing-Inkonsistenzen, falls sie auffallen

- `PROJEKT_UEBERBLICK.md` 9 vs 13 ViewModel-Parameter — nicht fixen, nur im Handover erwaehnen.
- CLAUDE.md bereits auf V0.0.6-Konventionen aktualisiert (Zeitzone, Speicherort, Raven-kanonisch, KML obsolet — siehe Commit `a729770`). Falls weitere Stellen im Projekt noch alte Formate dokumentieren (BDA, README, Kotlin-KDoc-Kommentare), nur im Handover notieren — Briefing-Update macht Master nach Abnahme.

---

## Scope-Bestaetigung

**Dieses AP bundled:**
- V0.0.6-Task "T51 Storage-Layout"
- Folge-Fix aus T54-Handover Punkt 8.1 (Daueraufnahme, Variante A — User-Entscheidung 2026-04-20)
- Feldtest-Bug "Analyse-Tab zeigt neue Sessions erst nach Neustart"

**Das ist der groesste Task der V0.0.6-Welle.** Worker darf den Scope in mehreren logischen Commits abliefern (siehe Normung). Build muss nach jedem Commit gruen bleiben.
