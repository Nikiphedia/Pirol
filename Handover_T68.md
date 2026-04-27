# Handover T68 — EditDetectionDialog (count + atlasCode + note)

**Branch:** `T68-detection-edit`  
**Commit:** `ded0311`  
**Datum:** 2026-04-27

---

## Erledigt

### Phase 1 — atlas_codes.json
- `app/src/main/assets/atlas_codes.json` angelegt
- CH-Set: 20 Einträge (AC1–AC19 + AC99), Version 1
- Fehlende Kommas aus dem AP-Listing (AC16/AC17/AC18/AC19) korrigiert
- Quelle: ornitho.ch, laut AP verifiziert

### Phase 2–3 — Datenklassen
- **`ml/AtlasCode.kt`** — `@Serializable data class AtlasCode(code, label_de)`
- **`data/repository/AtlasCodeRepository.kt`** — Singleton, lazy cached, lädt aus `assets/atlas_codes.json`; lenienter Parser, leere Liste bei Fehler (graceful degradation)

### Phase 4 — EditDetectionDialog.kt
- **`ui/components/EditDetectionDialog.kt`** neu (≈150 Zeilen)
- Stückzahl: Row mit −/+ IconButtons, Min=1, Max=999 999
- Atlascode: `ExposedDropdownMenuBox`, erste Option "(keiner)" → null; Einträge als "CODE — label_de"
- Bemerkung: `OutlinedTextField`, multiline, max 500 Zeichen mit Counter
- Speichern-Button disabled wenn keine Änderung gegenüber `detection`

ASCII-Skizze:
```
┌─ Detail-Eingaben ────────────────────────┐
│ Stückzahl:  [−]  3  [+]                  │
│ Atlascode:  AC5 — Revierverhalten ...  ▾ │
│ Bemerkung:                               │
│ │ Singt in Buche                         │
│                                  14/500  │
│              [Abbrechen]  [Speichern]    │
└──────────────────────────────────────────┘
```

### Phase 5 — SpeciesCard.kt
- Neue Parameter: `onEditDetection: ((Int, String?, String?) -> Unit)?` + `atlasCodes: List<AtlasCode>`
- `hasCustomData = count>1 || atlasCode!=null || note!=null`
- `Icons.AutoMirrored.Filled.List`-Button (48dp) rechts neben Correct-Button
- Icon färbt sich `MaterialTheme.colorScheme.primary` wenn `hasCustomData` (AC5)
- `EditDetectionDialog` beim Tap geöffnet; beim Save: Callback aufgerufen + Dialog geschlossen

### Phase 6 — DetectionList.kt
- `onEditDetection` + `atlasCodes` Parameter ergänzt
- Lambda-Wrapping mit `detection.id` analog zu bestehenden Callbacks

### Phase 7–8 — LiveScreen + AnalysisScreen
- LiveScreen: alle drei DetectionList-Aufrufe (Compact/Medium/Expanded) erweitert
- AnalysisScreen: SpeciesCard direkt mit `onEditDetection` + `atlasCodes` verdrahtet

### Phase 9 — SessionManager.replaceDetection()
- File-Modus: atomar via `detections.jsonl.tmp` + `renameTo`
- SAF-Modus: `"wt"`-Rewrite via ContentResolver (analog `removeLastVerification`)
- Silent no-op wenn Session-ID nicht gefunden

### Phase 10 — LiveViewModel.editDetection()
- Optimistisches `detectionListState.updateDetection(updated)`
- `sessionManager.isActive`-Guard (nur wenn Session läuft)
- `verificationMutex.withLock { sessionManager.replaceDetection(...) + appendVerification(...) }`
- Granulare Events: COUNT_CHANGED / ATLAS_SET / NOTE_ADDED (nur wenn tatsächlich geändert)
- Rollback via `detectionListState.updateDetection(current)` + Snackbar bei IO-Fehler
- `val atlasCodes: List<AtlasCode> by lazy { atlasCodeRepository.loadCodes() }`

### Phase 11 — AnalysisViewModel.editDetection()
- Gleiche Logik, direktes Datei-Schreiben (wie `verifyDetection()`)
- `detections.jsonl`: read + replace + tmp + renameTo (per File-API)
- `verifications.jsonl`: `appendText(...)` granular
- Rollback via `_uiState.update { it.copy(detections = previousDetections, saveErrorMessage = ...) }`
- `val atlasCodes: List<AtlasCode> by lazy { atlasCodeRepository.loadCodes() }`

### Phase 12 — DetectionListState.updateDetection()
- Neue `@Synchronized`-Methode analog `updateVerification()`
- Ersetzt Detektion an gleicher Position (kein Positionswechsel), triggert Recomposition

### AppModule.kt
- `single { AtlasCodeRepository(androidContext()) }` eingetragen
- LiveViewModel: 13 → 14 `get()` (AtlasCodeRepository als letztes)
- AnalysisViewModel: 4 → 5 `get()` (AtlasCodeRepository als letztes)

### Phase 13 — EditDetectionTest.kt
- 9 Tests: ac7a/b/c/d, ac8a/b, ac9, Konstanten-Check
- Reine JVM-Tests, kein Android-Context nötig

---

## Getestet

```
./gradlew compileDebugKotlin  → BUILD SUCCESSFUL in 7s
./gradlew testDebugUnitTest   → BUILD SUCCESSFUL (48 tests, 0 failures)
./gradlew assembleDebug       → BUILD SUCCESSFUL in 52s
```

Unit-Tests:
| Test | Status |
|------|--------|
| ac7a: JSONL-Zeile wird korrekt ersetzt | ✓ |
| ac7b: Andere Detektionen unberührt | ✓ |
| ac7c: 3 granulare VerificationEvents bei allen Feldern | ✓ |
| ac7d: Nur geänderte Felder erzeugen Events | ✓ |
| ac8a: updateDetection() ersetzt korrekt | ✓ |
| ac8b: updateDetection() gibt false für unbekannte ID | ✓ |
| ac9: Sequentielle Edits, finaler Wert korrekt | ✓ |
| Konstanten COUNT_CHANGED/ATLAS_SET/NOTE_ADDED definiert | ✓ |

Manuell (Gerät): Noch nicht deployed (pirol-deploy-worktree.ps1 liegt beim Master).

---

## Bewusst nicht gemacht

- Voice-Memo-Aufnahme (→ T69)
- Settings-Dropdown für Atlascode-Set CH/FR/DE (Folge-AP)
- Manuelles Hinzufügen einer Detection (Cluster B)
- Atlascode-Suche/Filter (bei 20 Einträgen unnötig)
- Bulk-Edit mehrerer Detektionen
- `onMarkUncertain`/`onCompare`/`onJumpToChunk` in DetectionList-Aufrufen fehlen noch in LiveScreen (waren schon vorher nicht verdrahtet) — **nicht im Scope**

---

## Beobachtungen

1. **`SessionManager.removeLastVerification()` nutzt direkten "wt"-Rewrite** ohne tmp+rename (beide JSONL-Methoden sollten konsistent sein → Empfehlung: removeLastVerification auf tmp+rename umstellen in Folge-AP).

2. **AnalysisViewModel hat kein snackbarChannel** — Fehler landen in `saveErrorMessage` in UiState. editDetection() folgt dem gleichen Muster. AnalysisScreen muss `saveErrorMessage` anzeigen (Snackbar) — dies ist bereits in T-prev implementiert via `consumeSaveErrorMessage()`.

3. **LiveViewModel: `sessionManager.isActive`-Guard** schreibt bei inaktiver Session nur ins Memory, nicht auf Disk. Kein Problem für AC6 (Analyse-Tab), da AnalysisViewModel direkt schreibt.

4. **atlas_codes.json Codes:** Das AP nennt "AC1"–"AC19"/"AC99" (neu), aber DetectionResult-Kommentar bei `atlasCode` zeigt noch Beispiele "A2"/"B4" (alt). → Kommentar in DetectionResult.kt sollte auf AC1-AC99 aktualisiert werden (Beobachtung, nicht gemacht).

---

## Abhängigkeiten für T69 (Voice-Memo)

1. **`EditDetectionDialog.kt`** ist der richtige Ort für die Voice-Memo-UI: Button zum Aufnehmen/Löschen unterhalb der Bemerkung einfügen. Die Callback-Signatur `onSave(count, atlasCode, note)` muss auf `onSave(count, atlasCode, note, voiceMemoFile?)` erweitert werden.
2. **`editDetection()` in beiden ViewModels** muss `voiceMemoFile`-Parameter erhalten und ACTION_MEMO_RECORDED / ACTION_MEMO_DELETED verarbeiten.
3. **`AtlasCodeRepository.kt`** bleibt unverändert.
4. **`AppModule.kt`**: Kein neuer Koin-Eintrag für T69 nötig (AtlasCodeRepository bereits da).
5. **Branch T68-detection-edit** kann als Basis für T69-Worktree verwendet werden (nach Master-Merge).
