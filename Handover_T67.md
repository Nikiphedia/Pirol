# Handover T67 — Datenmodell-Erweiterung + JSONL-Migration

**Branch:** `T67-data-model`
**Commit:** `852e5ec`
**Datum:** 2026-04-27

---

## Erledigt

### AC1 — 6 neue Felder in DetectionResult
`app/src/main/kotlin/ch/etasystems/pirol/ml/DetectionResult.kt`, Zeilen 53–59:

| Feld | Typ | Default |
|------|-----|---------|
| count | Int | 1 |
| atlasCode | String? | null |
| note | String? | null |
| voiceMemoFile | String? | null |
| manualEntry | Boolean | false |
| lockSpecies | Boolean | false |

Kommentar `// Cluster-C-Felder (V0.0.7+)` markiert den Block fuer spaetere Orientierung.

### AC5 — actionType + 15 Konstanten in VerificationEvent
`DetectionResult.kt`, Zeilen 76–97.

Feld `actionType: String? = null` in `VerificationEvent` (null = Legacy-Eintrag V0.0.6).
15 Konstanten in `companion object` (Prafix `ACTION_`):

| Gruppe | Konstanten |
|--------|-----------|
| Bestehend (6) | CONFIRMED, REJECTED, UNCERTAIN, CORRECTED, REPLACED, UNDO |
| Cluster C (5) | COUNT_CHANGED, ATLAS_SET, NOTE_ADDED, MEMO_RECORDED, MEMO_DELETED |
| Cluster B/D (4) | MANUAL_ADD, SPECIES_LOCKED, ALTERNATIVE_CONFIRMED, POSITION_MOVED |

**Modellierung-Entscheidung:** String-Konstanten in companion object statt sealed class.
Begruendung: JSONL-direkt-lesbar, keine Exhaustive-Behandlung in bestehenden when-Ausdruecken noetig, einfach erweiterbar ohne Migrationsrisiko. Verwendung im Code kommt erst in T68/T69.

### AC6 — CLAUDE.md aktualisiert
`CLAUDE.md`: Zwei neue Abschnitte nach "Datenformate"-Tabelle eingefuegt:
- `DetectionResult-Felder (V0.0.7+)` — Tabelle aller 6 neuen Felder
- `verifications.jsonl actionType-Werte (V0.0.7+)` — Tabelle nach Gruppe

### AC7 — Unit-Tests (6 Tests, alle gruen)
Neue Datei: `app/src/test/kotlin/ch/etasystems/pirol/ml/DetectionResultSerializationTest.kt`

| Test | Prueft |
|------|--------|
| old v006 jsonl without cluster c fields deserializes with correct defaults | AC2: Backward-Kompat |
| new v007 jsonl with all cluster c fields deserializes correctly | AC3: Forward |
| round trip serialize deserialize is identity | AC4: Round-trip |
| old verification event without actionType defaults to null | AC5: Legacy null |
| new verification event with actionType deserializes correctly | AC5: Forward actionType |
| all 15 actionType constants are defined and unique | AC5: Vollstaendigkeit |

---

## Getestet

```
./gradlew compileDebugKotlin   → BUILD SUCCESSFUL (22s)
./gradlew test                 → BUILD SUCCESSFUL (14s)
./gradlew assembleDebug        → BUILD SUCCESSFUL (56s)
```

Test-Ergebnis Debug-Suite (`testDebugUnitTest`):
- **DetectionResultSerializationTest:** 6 tests, 0 failures, 0 errors ✓
- **DetectionListStateTest:** 4 tests, 0 failures, 0 errors ✓ (bestehend, unveraendert)
- **WatchlistManagerTest:** 5 tests, 0 failures, 0 errors ✓ (bestehend)
- **AutoDeleteTest:** 6 tests, 0 failures, 0 errors ✓ (bestehend)
- **PrerollSnapshotTest:** 3 tests, 0 failures, 0 errors ✓ (bestehend)
- **SessionMigrationTest:** 4 tests, 0 failures, 0 errors ✓ (bestehend)
- **AccuracyFilterTest:** 6 tests, 0 failures, 0 errors ✓ (bestehend)
- **MedianSmoothingTest:** 6 tests, 0 failures, 0 errors ✓ (bestehend)

**Gesamt: 40 Tests, 0 failures, 0 errors**

---

## Bewusst nicht gemacht

- UI fuer count/atlasCode/note (kommt T68)
- Voice-Memo-Recorder (kommt T69)
- Verwendung der Konstanten MANUAL_ADD / SPECIES_LOCKED / ALTERNATIVE_CONFIRMED / POSITION_MOVED (Cluster B/D)
- Atlascode-JSON-Asset (Folge-AP nach Cluster C)
- Aktive Migration alter Sessions (Backward-Kompat reicht dank kotlinx.serialization-Defaults)
- Aenderungen in SessionManager, LiveViewModel, AnalysisViewModel (kein Bedarf: neue Felder mit Defaults, bestehende JSONL-Lese/Schreib-Pfade unveraendert)

---

## Beobachtungen

1. **AP-Tippfehler "12 insgesamt":** AC5 nennt "12 insgesamt: 6 alt + 5 Cluster-C + 4 Cluster-B/D" — die explizite Liste ergibt aber 6+5+4=15. Ich habe 15 Konstanten implementiert (die explizite Liste ist authoritative). Test und Doku verwenden 15. Master sollte AP-Tippfehler notieren.

2. **AP-Namensdiskrepanz `VerificationEntry` vs. `VerificationEvent`:** AP nennt die Klasse "VerificationEntry", tatsaechlicher Klassenname ist `VerificationEvent`. Implementierung verwendet korrekt `VerificationEvent`. Fuer T68-Worker: Klasse heisst `VerificationEvent`.

3. **`detectionCount` vs. `count`:** `detectionCount` (Int, Default 1) ist der Dedup-Zaehler (inkrementiert bei Wiedererkennung). Das neue `count` (Int, Default 1) ist die User-Stueckzahl. Beide Felder koexistieren ohne Konflikt. T68-UI-Worker muss darauf achten, nur `count` anzuzeigen/zu editieren, nicht `detectionCount`.

4. **`selectAlternative()` in DetectionListState:** Erstellt neue `DetectionResult`-Instanzen direkt mit Named Arguments (Zeile 177ff). Neue Felder mit Defaults erscheinen dort automatisch — kein Handlungsbedarf fuer T67, T68 sollte pruefen ob count=1/atlasCode=null fuer Alternative korrekt ist.

---

## Abhaengigkeiten

- Nachfolgende Tasks (T68 UI) muessen lesen:
  - `DetectionResult.kt` (neue Felder inkl. Kommentare)
  - `DetectionListState.kt` (selectAlternative(), copy()-Aufrufe)
  - `SessionManager.kt` L335 `appendDetections()`, L747 `loadDetections()` — keine Aenderung noetig, Defaults reichen
- `VerificationEvent.ACTION_*` Konstanten koennen ab sofort im ViewModel-Code referenziert werden

---

## Naechster Schritt

**T68 (UI Cluster C):** DetectionCard um count-Stepper, atlasCode-Dropdown, note-Textfeld erweitern.
Empfohlene Lektueere fuer T68-Worker: `DetectionResult.kt`, `DetectionListState.kt`, `ui/components/SpeciesCard.kt` oder entsprechende DetectionCard-Komponente, `ui/live/LiveViewModel.kt` (verifyDetection).
