## Task 11: Confidence-Tuning + Species-Filter (CH/DACH)

### Kontext
PIROL Android-Projekt unter `D:\80002\PIROL`, Package `ch.etasystems.pirol`.
Lies `D:\80002\PIROL\Handover_T9_T10.md` fuer den aktuellen Stand.
Lies `D:\80002\PIROL\PIROL_Projektbeschrieb.md` fuer das Gesamtkonzept.

**Bereits vorhanden:**
- Dual-Pipeline: DSP (Sonogramm) + ML (BirdNET V3.0 ONNX) laufen parallel im LiveViewModel
- `BirdNetV3Classifier.classify(samples, topK=5, threshold=0.1f)` → `List<ClassifierResult>`
- `DetectionListState` mit Deduplizierung (10s Fenster), max 100 Detektionen
- `DetectionList` + `SpeciesCard` UI in LazyColumn
- `LiveUiState` mit `detectionListState`, `isModelAvailable`
- `SpectrogramConfig`-Umschaltung per FilterChips (BIRDS/BATS/WIDEBAND)
- Labels-Platzhalter: 25 DACH-Arten in `assets/models/birdnet_v3_labels.txt`

### Aufgabe
Implementiere konfigurierbaren Confidence-Threshold, regionale Artenfilterung (CH/DACH) und Top-N-Begrenzung. Sowohl Backend-Logik als auch minimale UI-Steuerung.

### Anforderungen

**1. RegionalSpeciesFilter (`app/src/main/kotlin/ch/etasystems/pirol/ml/RegionalSpeciesFilter.kt`)**

JSON-basierte regionale Artenliste die unplausible Detektionen herausfiltert.

```kotlin
class RegionalSpeciesFilter(private val context: Context) {
    // Laedt die regionale Artenliste aus assets/
    fun loadRegion(regionId: String = "dach")

    // Prueft ob eine Art in der Region vorkommt
    fun isPlausible(scientificName: String): Boolean

    // Alle Arten der Region
    fun getSpeciesList(): Set<String>

    // Verfuegbare Regionen
    fun getAvailableRegions(): List<RegionInfo>
}

data class RegionInfo(
    val id: String,           // z.B. "dach", "ch", "europe"
    val name: String,         // z.B. "DACH (DE/AT/CH)"
    val speciesCount: Int
)
```

**2. Regionale Artenliste (`app/src/main/assets/regions/`)**

Erstelle `app/src/main/assets/regions/dach.json`:
```json
{
  "id": "dach",
  "name": "DACH (Deutschland, Oesterreich, Schweiz)",
  "description": "Brutvögel und regelmaessige Gastvögel der DACH-Region",
  "species": [
    "Turdus merula",
    "Fringilla coelebs",
    "Parus major",
    ... (mindestens 150 haeufige DACH-Arten + 20 Fledermausarten)
  ]
}
```

Erstelle eine realistische Liste mit:
- ~150 haeufige Brutvoegel (die man realistisch im Feld hoert)
- ~20 Fledermausarten (DACH-relevant)
- Sortiert taxonomisch oder alphabetisch nach scientificName
- Nur scientificName (Mapping auf commonName kommt aus den BirdNET-Labels)

**3. InferenceConfig (`app/src/main/kotlin/ch/etasystems/pirol/ml/InferenceConfig.kt`)**

```kotlin
data class InferenceConfig(
    val confidenceThreshold: Float = 0.5f,   // Minimum-Confidence (0.0 - 1.0)
    val topK: Int = 5,                        // Max Ergebnisse pro 3s-Chunk
    val regionFilter: String? = "dach",       // null = kein Filter, "dach" = DACH-Filter
    val showOnlyFiltered: Boolean = true       // true = nur regionale Arten anzeigen
) {
    companion object {
        val DEFAULT = InferenceConfig()
        val SENSITIVE = InferenceConfig(confidenceThreshold = 0.3f, topK = 10)
        val STRICT = InferenceConfig(confidenceThreshold = 0.7f, topK = 3)
    }
}
```

**4. InferenceWorker erweitern**

`InferenceWorker` soll `InferenceConfig` + `RegionalSpeciesFilter` akzeptieren:
- Threshold wird an `classifier.classify(threshold = config.confidenceThreshold)` weitergegeben
- TopK wird an `classifier.classify(topK = config.topK)` weitergegeben
- Nach Klassifizierung: `RegionalSpeciesFilter.isPlausible()` auf jedes Ergebnis anwenden
- Nicht-plausible Arten werden entfernt (oder optional mit Markierung "ungewoehnlich" behalten)

**5. LiveViewModel + LiveUiState erweitern**

```kotlin
// LiveUiState
data class LiveUiState(
    // ... bestehend ...
    val inferenceConfig: InferenceConfig = InferenceConfig.DEFAULT
)

// LiveViewModel
fun setConfidenceThreshold(threshold: Float)
fun setRegionFilter(regionId: String?)
fun setInferenceConfig(config: InferenceConfig)
```

**6. UI: Confidence-Slider + Region-Chip**

Im LiveScreen, unterhalb der SpectrogramConfig-Chips oder als ausklappbares Panel:

- **Confidence-Slider**: `Slider` von 0.1 bis 0.9, Schritte 0.1, Label "Min. Confidence: 60%"
  - Kompakt: nur ein kleiner Bereich, nicht dominant
  - Aendert `viewModel.setConfidenceThreshold()`
- **Region-Chip**: `FilterChip` "DACH" (aktiv/inaktiv toggle)
  - Aktiv: nur DACH-Arten anzeigen
  - Inaktiv: alle Arten anzeigen
  - Spaeter erweiterbar auf Dropdown mit mehreren Regionen
- **Preset-Chips** (optional, wenn Platz): "Standard" | "Sensitiv" | "Strikt"
  - Setzt InferenceConfig.DEFAULT / SENSITIVE / STRICT

Platzierung: In einer zweiten Zeile unter den bestehenden Config-Chips, oder als Bottom-Sheet wenn der User auf ein Settings-Icon tippt. Waehle die pragmatischere Variante — KISS.

**7. Koin-Registrierung**

```kotlin
single { RegionalSpeciesFilter(get()) }
```

InferenceWorker-Erstellung im ViewModel anpassen: `InferenceWorker(classifier, regionalFilter, config) { ... }`

### Arbeitsregeln
- Vollstaendige Dateien ausgeben (kein "rest bleibt gleich")
- Deutsch in Kommentaren, Englisch fuer API/Code-Bezeichner
- Am Ende: `./gradlew assembleDebug` muss GRUEN sein
- Gib am Ende ein **Handover-Dokument** als `D:\80002\PIROL\Handover_T11.md` zurueck mit:
  1. Was wurde erstellt (Zusammenfassung)
  2. Vollstaendige Dateiliste mit Pfaden
  3. Aenderungen an bestehenden Dateien
  4. Regionale Artenliste: Anzahl Arten, Zusammensetzung
  5. Offene Punkte / bekannte TODOs
  6. Naechster Schritt: "Task 12 — Embedding-Engine (EfficientNet ONNX)"
