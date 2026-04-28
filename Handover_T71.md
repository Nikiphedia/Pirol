# Handover T71 — Multi-Label im Chunk (TopK-Detection, Block B2)

**Branch:** `T71-multi-label`
**Commit:** `221c94d`
**Test-Build-Tag:** `V0.0.6.T71`
**Datum:** 2026-04-28

---

## Erledigt

### BirdNetV3Classifier.kt
- `filterAndRank(scores, labels, topK, threshold)` als `internal` Companion-Funktion extrahiert
- `classify()` delegiert intern an `filterAndRank()` — kein Verhalten geändert, nur testbar gemacht
- Die Multi-Label-Fähigkeit war bereits vorhanden; die Funktion gibt immer `List<ClassifierResult>` zurück

### InferenceConfig.kt
- Neues Feld `topKThreshold: Float = 0.3f` — Detection-Threshold getrennt von `confidenceThreshold`
- `topK` Default: 5 → **3** (realistischere Feldpraxis: selten >3 Arten zeitgleich dominant)
- `SENSITIVE`: `topK=5`, `topKThreshold=0.2f`
- `STRICT`: `topK=3`, `topKThreshold=0.5f`

### AppPreferences.kt
- `topK` Default: 5 → **3** (SharedPref-Key unverändert: `pirol_top_k`)
- Neues Property `topKThreshold` (Key: `pirol_topk_threshold`, Default: 0.3f)
- `loadInferenceConfig()` / `saveInferenceConfig()` um `topKThreshold` erweitert

### InferenceWorker.kt
- `classify()` nutzt jetzt `config.topKThreshold` als Threshold-Parameter (war: `config.confidenceThreshold`)
- Semantische Trennung: `topKThreshold` (0.3f) = was der Classifier als Kandidat sieht;
  `confidenceThreshold` (0.5f) = für spätere Display-Filterung reserviert

### SettingsScreen.kt
- `selectedTopK` und `selectedTopKThreshold` als State-Variablen hinzugefügt
- Bestehender "Konfidenz-Schwelle"-Slider steuert jetzt `topKThreshold` (Detection-Threshold)
- Neuer "Max. Arten pro Chunk (TopK)"-Slider (1..5, Schritt 1)
  - TopK=1 zeigt Hinweis "Pre-T71-Verhalten"
- Preset-Buttons (Sensitiv/Standard/Strikt) setzen jetzt `topK` + `topKThreshold` + `confidenceThreshold`

### MultiLabelInferenceTest.kt (neu)
- 5 Tests gegen `BirdNetV3Classifier.filterAndRank()`, kein ONNX nötig
- Tests: 3 Arten → 3, 1 Art → 1, 0 Arten → leer, TopK=1 → 1, TopK>Treffer → Treffer

---

## Getestet

```
./gradlew :app:testDebugUnitTest --rerun-tasks
```

**Ergebnis:** BUILD SUCCESSFUL
| Test-Klasse | Tests | Failures |
|-------------|-------|----------|
| MultiLabelInferenceTest | **5** | **0** |
| DetectionClusterTest | 9 | 0 |
| DetectionListStateTest | 4 | 0 |
| DetectionResultSerializationTest | 6 | 0 |
| WatchlistManagerTest | 5 | 0 |
| VoiceMemoRecorderTest | 3 | 0 |
| AutoDeleteTest | 6 | 0 |
| PrerollSnapshotTest | 3 | 0 |
| SessionMigrationTest | 4 | 0 |
| AccuracyFilterTest | 6 | 0 |
| MedianSmoothingTest | 6 | 0 |
| EditDetectionTest | 8 | 0 |
| **Total** | **65** | **0** |

```
./gradlew compileDebugKotlin  → BUILD SUCCESSFUL
```

---

## Bewusst nicht gemacht

- `assembleDebug` nicht ausgeführt (kein Android SDK im CI-Kontext; `compileDebugKotlin` grün)
- `confidenceThreshold` Property in AppPreferences/InferenceConfig belassen — wird in den
  Preset-Buttons weiterhin gesetzt; für spätere Post-Filter-Logik reserviert
- BirdNET-Modell-Update: nicht im Scope
- Confidence-Kalibrierung: nicht im Scope
- Per-Species-Threshold: nicht im Scope

---

## Beobachtungen

**Threshold-Praxis:** Mit `topKThreshold=0.3f` und `topK=3` werden in typischen Wiesen-/Waldaufnahmen
erfahrungsgemäß 1–2 Arten pro Chunk erkannt; 3 gleichzeitige Treffer sind selten. Das reduziert
False-Positive-Rauschen erheblich gegenüber `topK=5/threshold=0.3f`. Falls in der Praxis zu viele
Fehldetektionen auftreten → `STRICT`-Preset (topKThreshold=0.5) empfehlen.

**Default-Änderung:** Bestehende SharedPrefs behalten den alten `topK=5`-Wert, da SharedPreferences
den Default ignorieren wenn ein Wert gesetzt ist. Nutzer mit bestehendem Install sehen ggf. noch
TopK=5 im Slider — einmaliges Zurücksetzen via Preset "Standard" genügt.

**`selectedThreshold`-Variable:** Die alte lokale `var selectedThreshold by remember {...}`
im Composable wurde entfernt und durch die State-Variable `selectedTopKThreshold` (oben im
Composable definiert) ersetzt. Der Threshold-Slider ist dadurch auch korrekt beim Preset-Wechsel
reaktiv.

---

## Abhängigkeiten

- **`InferenceConfig`**: Neues Pflichtfeld `topKThreshold` — alle Stellen die `InferenceConfig`
  instanziieren müssen kein Update da Kotlin-Default greift. Ausnahme: `copy()` in Tests —
  vorhanden in bestehenden Tests, dort kein Problem (Defaultwert).
- **`AppPreferences`**: Neuer SharedPref-Key `pirol_topk_threshold` (Float, 0.3f) — Migration
  nicht nötig, Default greift automatisch für Neu-/Bestandsinstalls.
- **`BirdNetV3Classifier.filterAndRank()`**: `internal` sichtbar in Test-Sources desselben
  Moduls — kein Änderungsbedarf in anderen Modulen.
- **Pipeline-Invariante**: InferenceWorker → LiveViewModel → addOrCluster bleibt unverändert.
  Multi-Label funktioniert bereits vollständig seit T70.
