# Handover T70 — 10m-Radius-Cluster + Multi-Chunk-Aggregation + Zeit von-bis

**Branch:** `T70-detection-cluster`  
**Commit:** `2a9368d`  
**Test-Build-Tag (nach Master-Merge):** `V0.0.6.T70`  
**Worker:** Claude Sonnet 4.6  
**Datum:** 2026-04-28

---

## Erledigt

### AC1 — `firstSeenAtMs` / `lastSeenAtMs` in DetectionResult
- **Datei:** `app/src/main/kotlin/ch/etasystems/pirol/ml/DetectionResult.kt` (Z. 53–55)
- Zwei neue Felder `val firstSeenAtMs: Long = timestampMs` und `val lastSeenAtMs: Long = timestampMs`
- Typ: `Long` (konsistent mit allen anderen Zeitstempel-Feldern im Projekt)
- Backward-kompatibel: kotlinx.serialization evaluiert `= timestampMs` beim Deserialisieren alter JSONL ohne diese Felder → beide Felder erhalten automatisch den korrekten `timestampMs`-Wert
- `detectionCount: Int = 1` war bereits aus T44 vorhanden — wird bei Cluster-Merge inkrementiert ✓

### AC2 — AppPreferences 3 neue Keys
- **Datei:** `app/src/main/kotlin/ch/etasystems/pirol/data/AppPreferences.kt` (Z. 156–170)
- `clusterEnabled: Boolean` (SharedPrefs-Key `pirol_cluster_enabled`, Default `true`)
- `clusterRadiusM: Int` (Key `pirol_cluster_radius_m`, Default `10`, Range 0–50)
- `clusterTimeWindowMin: Int` (Key `pirol_cluster_timewindow_min`, Default `5`, Range 0–30)

### AC3 — Settings-UI "Detektions-Cluster"-Sektion
- **Datei:** `app/src/main/kotlin/ch/etasystems/pirol/ui/settings/SettingsScreen.kt`
- State-Deklaration: Z. 111–113 (`clusterEnabled`, `clusterRadiusM`, `clusterTimeWindowMin`)
- UI-Block: eingefügt nach Cooldown-Slider, vor Watchlist-Einträge-Liste
- Toggle "Detektionen raeumlich/zeitlich zusammenfassen" mit GPS-Hinweistext ✓
- Slider "Cluster-Radius (m)" 0–50, `0 = Aus` Anzeige, disabled wenn Toggle aus ✓
- Slider "Cluster-Zeitfenster (min)" 0–30, `0 = Aus` Anzeige, disabled wenn Toggle aus ✓

### AC4 — Toggle aus → identisches T44-Verhalten
- **Datei:** `DetectionListState.kt` (Z. 247–250 in addOrCluster)
- Bei `clusterEnabled = false`: Delegiert an `addDetections(listOf(detection))` → exakt T44-Logik
- `ClusterResult.New` wird zurückgegeben (kein Cluster-Merge)

### AC5 — GPS-Fix + Haversine ≤ Radius + Zeit ≤ Window → Cluster-Merge
- **Datei:** `DetectionListState.kt` — `addOrCluster()` + `distanceM()`
- Haversine-Implementierung als private top-level Funktion (Z. 26–34)
- Match-Bedingung: `same species && time within window && haversine <= radiusM`
- Bei Match: `copy(lastSeenAtMs, detectionCount+1, confidence=max)` → in-place Replace ✓

### AC6 — Ohne GPS-Fix: nur Zeit-Match
- **Datei:** `DetectionListState.kt` (Z. 270–275 im run-Block)
- Wenn `lat1 == null || lon1 == null || lat2 == null || lon2 == null` ODER `radiusM == 0` → `true` (Zeit-Match reicht) ✓

### AC7 — CLUSTER_MERGED in verifications.jsonl + replaceDetection
- **Datei:** `app/src/main/kotlin/ch/etasystems/pirol/ui/live/LiveViewModel.kt` (Z. 238–268)
- `addDetections()` ersetzt durch `addOrCluster()`-Loop
- Bei `ClusterResult.Merged`: `sessionManager.replaceDetection(anchor)` + `sessionManager.appendVerification(ACTION_CLUSTER_MERGED)` im `Dispatchers.IO`-Scope
- Payload in `correctedSpecies`: `"merged:{absorbedId}|count:{count}|lastSeenAt:{ms}"`
- Neue Detektionen via `sessionManager.appendDetections(newDetections)` (nur echte Neue, nicht Merges) ✓

### AC8 — 9 Unit-Tests grün
- **Datei:** `app/src/test/kotlin/ch/etasystems/pirol/ml/DetectionClusterTest.kt`
- **Test-Ergebnis: 9/9 passed, 0 failures, 0 errors** (100%, 0.025s)

### AC9 — compileDebugKotlin + test + assembleDebug grün
```
./gradlew compileDebugKotlin  → BUILD SUCCESSFUL in 19s
./gradlew test                 → BUILD SUCCESSFUL in 12s
./gradlew assembleDebug        → BUILD SUCCESSFUL in 53s
```
Alle bestehenden Tests bleiben grün.

### AC10 — CLAUDE.md aktualisiert
- **Datei:** `CLAUDE.md`
- `firstSeenAtMs: Long = timestampMs` und `lastSeenAtMs: Long = timestampMs` in DetectionResult-Felder-Tabelle ✓
- `CLUSTER_MERGED` in actionType-Tabelle (neue Zeile "Cluster A — aktiv (1)") ✓

---

## Getestet

**Automatisch: 9 neue Unit-Tests in `DetectionClusterTest.kt`**

| Test | Ergebnis |
|------|---------|
| `cluster match — 5m distanz, 2min auseinander ergibt 1 Eintrag mit count=2` | ✅ PASSED |
| `kein match wegen distanz 50m — 2 eintraege` | ✅ PASSED |
| `kein match wegen zeitfenster — 10min auseinander ergibt 2 eintraege` | ✅ PASSED |
| `kein gps auf einer seite — zeit-match-only pfad ergibt cluster` | ✅ PASSED |
| `toggle aus — klassisches t44-verhalten, 2x selbe art = 2 karten moeglich` | ✅ PASSED |
| `backward kompatibilitaet — altes jsonl ohne firstSeenAtMs parst mit default=timestampMs` | ✅ PASSED |
| `confidence max — 0_6 dann 0_85 ergibt 0_85 behalten` | ✅ PASSED |
| `cluster merged felder korrekt — absorbedId und lastSeenAtMs` | ✅ PASSED |
| `verschiedene arten bleiben immer getrennt — kein cross-species merge` | ✅ PASSED |

**Bestehende Tests:** alle weiterhin grün (45 Tasks executed in `./gradlew test`).

**Manuell:** Noch nicht getestet — erfordert Gerät mit APK-Deployment (`pirol-deploy-worktree.ps1 -Branch T70-detection-cluster`).

---

## Bewusst nicht gemacht

- UI-Anzeige der Zeit-von-bis-Range auf SpeciesCard (Task-Briefing: optionaler Bonus falls Token-Budget — wurde bewusst weggelassen, ist klar als Folge-AP abgrenzbar)
- Radius-Wert > 50m (ausserhalb Scope)
- Multi-Label TopK Cluster B2 (T71)
- Alle weiteren T71–T75 Cluster-Features

---

## Beobachtungen

1. **Cross-species Edge-Case korrekt:** Zwei nahe Detektionen verschiedener Arten (z.B. Amsel + Kohlmeise, 2m Abstand, 1 min auseinander) → bleiben getrennt — `scientificName`-Gleichheit ist Pflichtbedingung ✓ (Test 9 deckt das ab)

2. **`timeWindowMin = 0` Edge-Case:** `windowMs = 0` → `(nowMs - lastSeenAtMs) <= 0` ist fast nie `true` (nur wenn identischer Millisekunden-Zeitstempel). Effektiv kein Zeit-Match → jede Detection ist neu. Verhält sich wie "Clustering aus". Kein Sonderfall nötig.

3. **`radiusM = 0` Edge-Case:** Guard `radiusM > 0` im run-Block → fällt in GPS-fehlt-Ast → Zeit-Match reicht. Radius-Slider auf 0 = reine Zeit-Dedup ohne Radius-Check, wie im Briefing spezifiziert ✓

4. **`lastSeenAtMs` beim Toggle-aus-Pfad:** Bei `clusterEnabled = false` wird `addDetections` aufgerufen. Die T44-Logik aktualisiert `timestampMs` und `lastDetectedMs`, aber NICHT `lastSeenAtMs`. Das ist korrekt — `lastSeenAtMs` ist ein Cluster-A-Feld und nur im Cluster-Pfad semantisch relevant.

5. **Watchlist-Alarm bei Cluster-Merge:** Cluster-Merges triggern keinen Watchlist-Alarm mehr (nur `newDetections`). Der bestehende `alarmCooldownMinutes`-Mechanismus wäre als Fallback ausreichend gewesen, aber die neue Logik ist semantisch sauberer.

6. **Persistence-Reihenfolge:** `replaceDetection` + `appendVerification` laufen in `Dispatchers.IO` parallel zu weiterem Inference-Verarbeiten — keine Mutex-Probleme, da `SessionManager` seine eigenen atomaren Writes (tmp+rename / SAF "wt") verwendet.

---

## Abhängigkeiten

**Neue Dateien:**
- `app/src/test/kotlin/ch/etasystems/pirol/ml/DetectionClusterTest.kt`

**Geänderte Interfaces/Klassen:**
- `DetectionResult`: 2 neue serialisierbare Felder `firstSeenAtMs`, `lastSeenAtMs`
- `DetectionListState`: neues `addOrCluster()` public API + `ClusterResult` sealed class (top-level im `ml`-Package)
- `AppPreferences`: 3 neue Properties `clusterEnabled`, `clusterRadiusM`, `clusterTimeWindowMin`
- `LiveViewModel`: ruft nicht mehr `addDetections()` direkt auf — `addOrCluster()` für jeden Detection-Eintrag

**Keine neuen Dependencies** (nur `kotlin.math.*` — bereits in stdlib).

**JSONL-Format:** `detections.jsonl` enthält neu `firstSeenAtMs` und `lastSeenAtMs`. Alte Sessions ohne diese Felder parsen via Default korrekt. `verifications.jsonl` enthält neue `CLUSTER_MERGED`-Einträge mit `actionType="CLUSTER_MERGED"` und `correctedSpecies="merged:{id}|count:{n}|lastSeenAt:{ms}"`.

---

## Empfehlung für T71 (Multi-Label TopK)

**Zuerst lesen:**
1. `ml/DetectionResult.kt` — verstehe `candidates: List<DetectionCandidate>` (bereits vorhanden aus T45) und die neuen `firstSeenAtMs`/`lastSeenAtMs`
2. `ml/DetectionListState.kt` — verstehe `addOrCluster()` und `ClusterResult` (T71 könnte eigene Cluster-Logik pro Kandidat brauchen)
3. `ml/InferenceWorker.kt` — verstehe `topK`-Config und wie Candidates befüllt werden
4. `LiveViewModel.kt` — Z. 238–274 (neuer addOrCluster-Loop) — T71 muss hier ebenfalls eingreifen

**Wichtig für T71:** `ClusterResult.New` vs `ClusterResult.Merged` — falls T71 auch Cluster-Logik für Kandidaten einführt, muss `addOrCluster` erweitert werden (oder neue parallele Methode). Nicht in `addDetections` schreiben — diese bleibt T44-Verhalten.
