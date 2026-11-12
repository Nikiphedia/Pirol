# Befund Codereview PIROL — 2026-11-12

**Basis:** lokaler `master` @ `06165d8` (Stand T89, 2026-05-05) plus Abgleich mit GitHub (`origin`, gefetcht am 2026-11-12).
**Umfang:** Audio-Engine (C++/JNI), DSP, ML-Pipeline, Session-Speicher, Live-ViewModel, Sonogramm-Darstellung, Build. UI-Screens (Settings, Analyse, Karte, Onboarding) nur überflogen.
**Methode:** Code gelesen, nichts gebaut, nichts auf dem Gerät gemessen. Jeder Befund ist als *belegt (Code)* oder *zu verifizieren (Gerät)* markiert.
**Geändert wurde nichts** ausser dieser Datei.

Leitlinien für alle Arbeitspakete (aus den beiden Skills):
- *karpathy-guidelines:* Annahmen offenlegen, minimaler Code, chirurgische Änderungen, pro AP ein prüfbares Erfolgskriterium (zuerst Test/Messung, dann Fix).
- *ponytail* (nur `plugin.json` lag vor, der Skill-Text nicht): einfachste funktionierende Lösung, stdlib zuerst, keine ungefragten Abstraktionen (YAGNI).

---

## 0. Wichtigster Befund: GitHub ist NICHT der aktuelle Stand

| Linie | Stand | Inhalt |
|-------|-------|--------|
| lokal `master` | T89, 2026-05-05 | **74 Commits (T58–T89) sind nicht auf GitHub** |
| `origin/master` | V0.0.6 / T57, 2026-04-22 | alter Stand |
| `origin/main` | 3 Commits | nur README (GitHub-Default-Branch!) |
| `origin/claude/exciting-faraday-sa64ox` | 2026-10-20 | "T61 Effizienz-Review" + CI, **auf Basis T57** |
| `origin/claude/t62-dependency-updates` | 2026-10 | "T62 Dependency-Updates" (AGP 9.4, Kotlin 2.4.21, ONNX 1.31), Basis T57 |
| `origin/claude/ci-workflow` | 2026-10 | "T63 GitHub-Actions-CI", Basis T57 |

Folgen:
1. Die Cloud-Arbeit vom Oktober (T61–T63) baut auf dem alten Stand auf und kennt T58–T89 nicht (Cluster, Voice-Memo, Karte V2, Lock, Phenology ...).
2. **T-Nummern kollidieren:** lokal sind T60–T63 = Autodelete/Instant-Verify/Player/Fairphone-Preroll, auf GitHub sind T61–T63 = Effizienz/Dependencies/CI.
3. Probe-Merge von `exciting-faraday` in lokalen `master`: 4 Konfliktdateien (`WavWriter.kt`, `BirdNetV3Classifier.kt`, `InferenceWorker.kt`, `AnalysisViewModel.kt`), Rest mergt automatisch.
4. T58–T89 existieren nur auf diesem Rechner (kein Off-Site-Backup der letzten 74 Commits).
5. Im Arbeitsverzeichnis liegen **595 uncommittete Zeilen** (Referenz-Tab mit Xeno-Canto-Download) plus untracked Quelldateien (`data/api/`, `ReferenceDownloader.kt`, `SecurePreferences.kt`, `KmlExporter.kt`, `ShareHelper.kt`, `TileDownloadManager.kt`, `TileSourceConfig.kt`). Die geänderten Dateien referenzieren die untracked Dateien — dieser Stand ist nirgends gesichert.
6. 36 Worktrees sind registriert, zeigen aber auf nicht mehr existierende Pfade (`D:/80002/PIROL/...`, `C:/eta_Data/...`) → alle "prunable". Die Worker-Branches existieren noch.

**Bevor irgendein Worker startet, muss die Basis geklärt sein** (siehe Interview-Frage 1).

---

## 1. Befunde nach Schweregrad

### Kritisch — Datenintegrität im Feld

**K1. Audio wird lückenhaft aus dem Ring-Buffer gelesen** — *belegt (Code), Ausmass zu verifizieren (Gerät)*
`RecordingService.startChunkEmitter()` (`audio/RecordingService.kt:297`) holt alle `delay(100)` ms die **jüngsten** 100 ms (`RingBuffer::readLatest`). Es gibt keinen Lese-Cursor. Jede Schleifenrunde dauert 100 ms + Verarbeitungszeit + Scheduler-Jitter; die Samples dazwischen gehen verloren (bei zu früher Runde werden sie doppelt gelesen). Zusätzlich: `emit()` suspendiert, wenn ein Collector langsam ist (Inference läuft inline im Collector, Puffer 64 Chunks = 6.4 s) → dann fehlen ganze Abschnitte.
Wirkung: `recording.wav` ist kürzer als die Realzeit und hat alle ~100 ms eine Unstetigkeit; BirdNET bekommt dasselbe gestückelte Signal. Detection-Offsets bleiben zur WAV konsistent, aber nicht zur Uhrzeit.
Schnelltest ohne Code: bei einer vorhandenen langen Session `totalRecordedSamples / sampleRate` mit `endedAt − startedAt` (minus Preroll) vergleichen.

**K2. Neue Detektionen können nach dem ersten Cluster-Merge/Edit verloren gehen** — *belegt (Code), zu verifizieren (Gerät)*
`SessionManager` hält `detections.jsonl` die ganze Session über mit einem `FileWriter` offen (`SessionManager.kt:272`). `replaceDetection()` schreibt im File-Modus eine tmp-Datei und macht `renameTo()` über die Originaldatei (`:475-478`). Unter Linux/Android zeigt der offene Writer danach auf die gelöschte alte Datei — alle späteren `appendDetections()` landen im Nichts. `replaceDetection()` läuft bei **jedem Cluster-Merge** (Cluster ist per Default an) und bei jedem Edit/Lock.
Dazu kommt: `replaceDetection`, `appendDetections` und `appendVerification` laufen unsynchronisiert auf `Dispatchers.IO` (nur die UI-Aktionen nutzen `verificationMutex`, der Merge-Pfad in `LiveViewModel.kt:275` nicht) → Read-Modify-Write-Races auch im SAF-Modus.
Achtung beim Testen: unter Windows verhält sich `renameTo` anders (schlägt fehl statt zu ersetzen) — ein Test muss auf Linux/CI oder dem Gerät laufen.
Schnelltest: Session mit Cluster-Merges aufnehmen, danach Zeilen in `detections.jsonl` gegen `totalDetections` in `session.json` zählen.

**K3. Die Aufnahme-Pipeline hängt am ViewModel, nicht am Foreground-Service** — *belegt (Code)*
Der Service liefert nur Chunks. Akkumulieren, Inference, WAV-Schreiben und Session-Verwaltung laufen im `viewModelScope` des `LiveViewModel` (`LiveViewModel.kt:188-333`, `:1123-1263`). `onCleared()` beendet die Session (`:1319`), während der Service samt Notification "Aufnahme läuft" weiterläuft. Wird die Activity beendet (Zurück-Geste, Task wegwischen), nimmt niemand mehr auf. Bildschirm aus allein ist unkritisch, solange die Activity lebt.

### Hoch

**H1. 10-MB-JSON wird auf dem Main-Thread geparst** — *belegt (Code)*
`LiveViewModel.init` ruft `runBlocking(Dispatchers.IO) { speciesNameResolver.load() }` (`:355`). `species_master.json` ist 9.98 MB und wird mit `org.json` komplett in den Speicher gelesen (String + Objektbaum). Der Kommentar nennt ~200 ms; auf einem Mittelklasse-Gerät ist eher mit Sekunden und hohem Heap-Peak zu rechnen → Start-Ruckler bis ANR-Risiko. Genutzt werden 11 von 23 Sprachen.

**H2. Lokaler `master` enthält die Effizienz-Fixes vom Oktober nicht** — *belegt (Code)*
Unabhängig von meinem Review bestätigt: Im lokalen Stand sind weiterhin vorhanden
- natives Speicherleck: `OrtSession.Result` wird in `runInference()` nie geschlossen (`BirdNetV3Classifier.kt:343`), ~50 KB pro Inference, ~60 MB pro Stunde;
- doppelte BirdNET-Inference pro Treffer-Block (`classify()` + `extractEmbedding()`, `LiveViewModel.kt:311`);
- `cos`/`sin` pro Butterfly in der FFT (`FFT.kt:45-46`, ~11'000 Trig-Aufrufe pro Frame);
- Vollsortierung von 60'000 Floats alle 250 ms im `DynamicRangeMapper`;
- `EmbeddingDatabase` ohne Synchronisation bei parallelem `add`/`save`;
- Float→Short-Rückwandlung des ganzen Blocks fürs WAV (zusätzlich ±1 LSB Rundungsfehler durch `/32767` und Abschneiden).
Alle sechs sind auf `origin/claude/exciting-faraday-sa64ox` gelöst und getestet (dort 20/20 Unit-Tests grün). **Nicht neu schreiben, sondern portieren.**

**H3. GPS im Hintergrund vermutlich eingeschränkt** — *zu verifizieren (Gerät)*
Der Service deklariert nur `foregroundServiceType="microphone"`. Ohne Typ `location` darf ein Foreground-Service ab Android 11 den Standort nicht zuverlässig weiter beziehen, sobald die App nicht mehr sichtbar ist. Prüfbar über `gpsStats.fixCount` einer Session mit ausgeschaltetem Bildschirm.

**H4. Embedding-DB wächst unbegrenzt und wird jedes Mal komplett neu geschrieben** — *belegt (Code)*
Pro Treffer-Block ein Eintrag (4 KB), alle 10 Einträge Vollschreiben der ganzen Datei (`LiveViewModel.kt:328`). Bei 10'000 Einträgen sind das 40 MB alle ~30 s Vogelgesang. Zweck und gewünschte Grösse der DB sind unklar (Interview-Frage).

### Mittel

- **M1. Resampler ohne Anti-Alias-Filter** (`AudioResampler.kt`): 48→32 kHz per linearer Interpolation spiegelt 16–24 kHz in den Bereich 8–16 kHz (Insekten, Rauschen des Unprocessed-Mikrofons). Kann Fehl-Detektionen begünstigen. Aber: AMSEL nutzt denselben Resampler — eine Änderung verschiebt die Vergleichbarkeit. Nur mit A/B-Test an echten Aufnahmen entscheiden.
- **M2. Fehler-Neustart der Audio-Engine** (`AudioEngine.cpp:122`): `onErrorAfterClose` ruft `start()` aus dem Fehler-Thread, `start()` macht `ringBuffer_.resize()` (Preroll weg) und ist nicht gegen gleichzeitiges `stop()` geschützt. Selten (USB-Mikrofon ab/an), aber dann undefiniert.
- **M3. Preroll-Sonogramm wird synchron auf dem Main-Thread gerechnet** (`LiveViewModel.kt:526`, begrenzt auf 5 s). Auf GitHub (T61 2.17) bereits in den Hintergrund verlegt.
- **M4. Tests/CI:** 16 Unit-Test-Dateien, alle reine JVM-Logik; keine Tests für Session-Persistenz (K2) und Audio-Kontinuität (K1). CI existiert nur auf dem GitHub-Branch.
- **M5. `versionName = "0.0.5"`, `versionCode = 5`** in `app/build.gradle.kts` bei Tags bis `V0.0.6.T89`.
- **M6. `species_phenology.json` ist 59 Bytes** — das Saisonalitäts-Feature (T74) hat praktisch keine Daten.

### Niedrig / bewusst NICHT anfassen (YAGNI)

- `LiveViewModel` (1351 Zeilen, 15 Konstruktor-Parameter) und `SettingsScreen` (1698 Zeilen) sind gross. Kein eigener Refactor — K3 zieht die Pipeline ohnehin heraus, der Rest lohnt den Umbau derzeit nicht.
- Sonogramm-Canvas zeichnet pro Update das ganze Bitmap neu (~0.3 Mio. Pixel bei 10 Hz). Messbar, aber unkritisch.
- Real-FFT-Trick, JNI-Doppelkopie (`samples.copyOf()` in `RecordingService.kt:305`), doppeltes Lesen von `region_sets.json`: zu kleiner Gewinn.
- `CLAUDE.md` ist an mehreren Stellen veraltet (Modell-Tabelle nennt Haiku 4.5/Sonnet 4.6/Opus 4.7; "9 ViewModel-Parameter"; Verzeichnisbaum) → reines Doku-AP.

---

## 2. GPU-Auslagerung — Bewertung

**Empfehlung: für PIROL (Handy) jetzt nicht. Zuerst messen, dann höchstens einen CPU-Beschleuniger zuschalten.**

| Kandidat | Bewertung |
|----------|-----------|
| **BirdNET-Inference** | Einziger echter Rechenblock: 1 Fenster à 3 s alle 3 s. ONNX Runtime Android hat keinen allgemeinen GPU-Provider. Optionen: NNAPI (Android-seitig abgekündigt, schlechte Abdeckung für Audio-Frontends, oft langsamer wegen Aufteilung CPU/Beschleuniger), XNNPACK (bleibt CPU, optimierte Kerne), QNN (nur Qualcomm, eigene Bibliothek, quantisiertes Modell). Echte GPU-Nutzung hiesse Wechsel auf LiteRT/TFLite-GPU-Delegate → Modellkonvertierung und Bruch mit dem gemeinsamen AMSEL-Format `birdnet_v3.onnx`. |
| **DSP (FFT/Mel)** | Nein. ~94 FFTs à 2048 Punkte pro Sekunde sind nach dem Twiddle-Fix weit unter 1 % eines Kerns; der Datentransfer zur GPU wäre teurer als die Rechnung. |
| **Sonogramm-Darstellung** | Bitmap wird bereits GPU-komponiert. Farb-Mapping per Shader (AGSL) ginge erst ab API 33, Min-SDK ist 26. Gewinn minimal. |
| **AMSEL Desktop** | Hier lohnt GPU: Stapel-Analyse stundenlanger Aufnahmen mit ONNX Runtime + DirectML/CUDA. Das ist ein AMSEL-Thema, nicht PIROL. |

Was stattdessen wirkt, in dieser Reihenfolge: (1) doppelte Inference entfernen = halbe ML-Last bei Treffern, (2) Thread-Zahl und XNNPACK auf dem Zielgerät benchmarken, (3) FP16 vs. FP32 messen, (4) optional Stille-Erkennung, die Inference bei Ruhe überspringt. Die Aussagen zu den Execution-Providern stammen aus meinem Wissen und nicht aus einer Messung — vor einer Entscheidung die aktuelle ONNX-Runtime-Doku und einen Geräte-Benchmark heranziehen.

---

## 3. Handlungsanleitung für die Agenten

Nummern ab **T90** (T60–T63 sind doppelt belegt, siehe 0). Modellwahl: günstigstes Modell, das die Aufgabe sicher löst.

| Stufe | Modell-ID | Einsatz |
|-------|-----------|---------|
| klein | `claude-haiku-5-5` | Indexer, Doku, Strings, mechanische Umbenennungen |
| Standard | `claude-sonnet-5-5` | Bugfixes und Umbauten mit klarer Spezifikation |
| gross | `claude-opus-5-5` | Architektur-Entwurf, nebenläufiger C++/Service-Umbau |
| Master | `claude-fable-5-2` | Planung, Review gegen AC, Merge — keine Worker-Arbeit |

Für jeden Worker gilt (in jedes Arbeitspaket kopieren):
1. Annahmen zuerst hinschreiben; bei Unklarheit stoppen und fragen, nicht raten.
2. Erst das Erfolgskriterium als Test oder Messung herstellen, dann der Fix.
3. Nur Zeilen ändern, die sich auf das AP zurückführen lassen. Fremde Auffälligkeiten unter "Beobachtungen" ins Handover.
4. Keine neuen Abstraktionen, Interfaces oder Einstellungen, die das AP nicht verlangt.
5. Frischer Worktree von aktuellem `master`, kein Selbst-Merge (CLAUDE.md, Worktree-Disziplin).

### Block 0 — Basis herstellen (Master, mit User-Freigabe)

**T90 Repo-Konsolidierung** — Master selbst, kein Worker
- Lokalen `master` + Tags nach GitHub pushen (Freigabe nötig, nach aussen sichtbar). Default-Branch auf GitHub von `main` auf `master` stellen oder `main` löschen.
- Xeno-Canto-WIP als Branch `wip/xeno-canto-referenzen` sichern (nicht in `master`).
- `git worktree prune`; Binärdateien (`*.apk`, `*.zip`, `*.xlsx`) in `.gitignore`.
- Verifikation: `git status` sauber, `git rev-list --count origin/master..master` = 0, `./gradlew assembleDebug` grün auf sauberem `master`.

**T91 Effizienz-Port (GitHub-T61 + CI auf aktuellen `master`)** — `claude-sonnet-5-5`
- Scope: Commits von `origin/claude/exciting-faraday-sa64ox` auf `master` bringen; 4 Konfliktdateien von Hand auflösen. `WavWriter.open()`: lokale Variante behalten (Header-Bug ist lokal bereits behoben), nur Bulk-Schreiben und `repairHeaderIfNeeded()` übernehmen. Cluster-/Multi-Label-Logik (T70/T71) in `InferenceWorker`/`LiveViewModel` muss erhalten bleiben.
- Nicht-Ziele: Dependency-Updates (T62), neue Optimierungen.
- Erfolgskriterium: `DspEffizienzTest` 8/8 plus alle bestehenden Tests grün, `assembleDebug` grün, CI-Workflow läuft auf GitHub. Gerät: 10 min Aufnahme, RAM-Verlauf flach (Leck weg), WAV öffnet in Raven.
- Datei-Index vom Indexer (`claude-haiku-5-5`) vorab, da ~20 Dateien betroffen.

### Block A — Datenintegrität (vor jedem neuen Feature)

**T92 Lückenlose Audio-Entnahme (K1)** — `claude-opus-5-5`
- Schritt 1 (Messung, kein Code): Dauer-Vergleich an bestehender Session, Ergebnis ins Handover. Zeigt die Messung < 0.1 % Abweichung, AP stoppen und zurückmelden.
- Schritt 2: `RingBuffer` um sequentielles Lesen ergänzen (Cursor auf Basis `totalWritten_`; `readSince(cursor, out, max)` liefert alles Neue und meldet Überlauf). Emitter liest pro Runde alles Verfügbare. `readLatest` bleibt für Preroll.
- Schritt 3: `emit` darf den Leser nicht blockieren — Überlauf zählen und loggen statt still verlieren.
- Nicht-Ziele: Pipeline-Umzug (T94), Chunk-Grösse ändern.
- Erfolgskriterium: 10 min Aufnahme → WAV-Dauer = Wanduhr ±0.1 %; Überlauf-Zähler 0; Testton 1 kHz zeigt im Spektrogramm keine periodischen Störlinien.

**T93 Detektions-Persistenz absichern (K2)** — `claude-sonnet-5-5`
- Zuerst Test, der den Verlust reproduziert (JVM-Test auf Linux/CI: Session starten → append → replace → append → Datei lesen, erwartet 2 Zeilen).
- Einfachster Fix: alle Schreibzugriffe auf Session-Dateien über **einen** `Mutex` im `SessionManager`; in `replaceDetection` Writer schliessen, umschreiben, im Append-Modus neu öffnen. Kein Event-Sourcing-Umbau.
- Erfolgskriterium: neuer Test grün; Gerät: Session mit ≥ 20 Merges, Zeilenzahl `detections.jsonl` = `totalDetections`.

**T94 Aufnahme unabhängig vom ViewModel (K3)** — nur nach Interview-Frage 3
- T94a Entwurf — `claude-opus-5-5`: 1 Seite, welche Teile (Accumulator, InferenceWorker, WAV-Append, Session-Start/-Ende) in den Service-Scope wandern und was das ViewModel nur noch beobachtet. Keine Umsetzung.
- T94b Umsetzung — `claude-sonnet-5-5` nach freigegebenem Entwurf.
- Erfolgskriterium: Aufnahme starten, Activity per Zurück-Geste schliessen, 5 min warten, App öffnen → Session läuft, WAV lückenlos, Detektionen vorhanden.

### Block B — Effizienz und Start

**T95 Artnamen-Laden vom Main-Thread nehmen (H1)** — `claude-sonnet-5-5`
- Messung zuerst (Ladezeit + Heap auf dem Gerät loggen).
- Schritt 1: `runBlocking` entfernen; Laden im App-Scope starten, Inference-Start wartet per `suspend` darauf.
- Schritt 2 nur falls weiterhin > 500 ms: Asset beim Build auf die 11 genutzten Sprachen kürzen oder streamend parsen (`android.util.JsonReader`).
- Erfolgskriterium: kein `runBlocking` mehr im UI-Pfad; erster Frame des Live-Tabs ohne Wartezeit; Artnamen ab erster Detektion in gewählter Sprache.

**T96 ONNX-Benchmark auf dem Zielgerät** — `claude-sonnet-5-5`
- Kleiner Debug-Eintrag in den Settings: 20 Inferenzen, Median/Maximum in ms für die Varianten {Threads 1/2/4} × {CPU-Standard, XNNPACK} × {FP32, FP16}. Ergebnis als Tabelle ins Handover.
- Nicht-Ziele: Provider produktiv umschalten, NNAPI/QNN/GPU.
- Erfolgskriterium: Messwerte von jedem Testgerät; Master schlägt danach eine feste Konfiguration vor.

**T97 Embedding-DB begrenzen (H4)** — `claude-sonnet-5-5`, nach Interview-Frage
- Einfachste Variante: Obergrenze (älteste Einträge verwerfen) + Speichern nur bei Session-Ende.

**T98 GPS im Hintergrund (H3)** — `claude-sonnet-5-5`
- Erst messen (`gpsStats` bei Bildschirm aus). Nur bei bestätigtem Problem: Service-Typ `microphone|location`, Manifest-Permission `FOREGROUND_SERVICE_LOCATION`.

**T99 Resampler A/B (M1)** — zurückstellen, bis AMSEL-Abstimmung geklärt ist.

### Block C — Hygiene

**T100 Doku nachführen** — `claude-haiku-5-5`: `CLAUDE.md` (Modell-Tabelle, Tech-Stack, Verzeichnisbaum, Koin-Parameter), `Index.md`, `versionName`/`versionCode`.
**T101 Dependency-Updates** — zuletzt, eigener Feldtest. GitHub-T62 als Vorlage, aber AGP 9 / compileSdk 37 / APK 131 MB sind grosse Schritte; ONNX Runtime ggf. vorziehen und mit T96 koppeln.

Reihenfolge und Abhängigkeiten: T90 → T91 → (T92, T93 parallel, verschiedene Dateien) → T94 → Block B → Block C. T92 und T94 berühren beide `RecordingService.kt`, deshalb nacheinander.

---

## 4. Weitere Ideen (nicht beauftragt, zur Auswahl)

1. **Selbsttest nach Session-Stop:** WAV-Dauer gegen Wanduhr und Zeilenzahl `detections.jsonl` gegen Zähler prüfen, Abweichung in `session.json` vermerken und als Hinweis zeigen. Hätte K1 und K2 im ersten Feldtest sichtbar gemacht. Klein.
2. **Stille-Gate:** Inference überspringen, wenn der Pegel im 3-s-Block unter einer Schwelle liegt. Spart Akku in ruhigen Phasen, ändert aber das Verhalten (leise ferne Rufe) → als Option im Energieprofil.
3. **Überlappende Fenster (1.5 s Versatz):** bessere Trefferquote bei kurzen Rufen an Fenstergrenzen, kostet doppelte Inference. Erst nach T96 beurteilbar.
4. **Release-APK ohne `x86_64`** (nur für Emulator nötig): spart je nach ONNX-Version 20–40 MB.
5. **Phenology-Daten** tatsächlich befüllen (M6) oder das Feature in den Settings ausblenden.
6. **Raven-Export periodisch statt nur bei Stop**, damit nach einem Absturz mehr als die JSONL bleibt (Crash-Recovery T65 deckt nur die WAV).

---

## 5. Offene Fragen

Interview vom 2026-11-12, Antworten des Users:

| Frage | Antwort | Konsequenz |
|-------|---------|------------|
| Welche Basis gilt? | Lokaler `master` (T89) | T90 pusht `master` nach GitHub, T91 portiert die Oktober-Arbeit darauf. |
| Uncommittete Xeno-Canto-Arbeit? | Als Branch sichern | Teil von T90: `wip/xeno-canto-referenzen`, `master` bleibt sauber. |
| Feldnutzung? | App bleibt während der Aufnahme offen | K3 trifft die heutige Nutzung nicht. **T94 wird zurückgestellt** (hinter Block B). Knackser/Lücken wurden nicht gemeldet → bei T92 entscheidet die Messung in Schritt 1. |
| Priorität? | Datenintegrität | Reihenfolge: T90 → T91 → T92 + T93 → Block B → T94 → Block C. |

Noch offen (für die jeweiligen APs, nicht blockierend für T90–T93):
- Zweck und gewünschte Grösse der Embedding-DB (T97).
- Welche Testgeräte für den Benchmark zur Verfügung stehen (T96).
- Ob der Resampler mit AMSEL identisch bleiben muss (T99).
