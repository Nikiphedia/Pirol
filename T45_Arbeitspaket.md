# T45 — Alternative ersetzt Hauptart + REPLACED-Status

## Voraussetzung

**T44 ist abgeschlossen und reviewed.** Dieser Task baut auf dem aktuellen `main`-Stand auf.

---

## Ziel

Wenn der Nutzer in der aufgeklappten Alternativenliste einer Detektion eine andere Art antippt, ersetzt diese die erkannte Hauptart:

- Die Alternative wird als **neue Detektion an Index 0** eingefügt (5s-Highlight)
- Die ursprüngliche Detektion bleibt als `REPLACED` in der Liste stehen — **ausgegraut** mit rotem Rand
- In `verifications.jsonl` wird ein `VerificationEvent` mit `status = REPLACED` und `correctedSpecies = <Alternative>` geschrieben

---

## Projekt-Kontext

**Package:** `ch.etasystems.pirol`
**Pfad:** `D:\80002\PIROL`
**Build-Befehl:** `./gradlew compileDebugKotlin` (kein Device nötig)
**Sprache:** Kotlin 2.1.0, Jetpack Compose, Material 3

**Relevante Dateien — alle vor Änderung vollständig lesen:**

- `app/src/main/kotlin/ch/etasystems/pirol/ml/DetectionResult.kt`
- `app/src/main/kotlin/ch/etasystems/pirol/ml/DetectionListState.kt`
- `app/src/main/kotlin/ch/etasystems/pirol/ui/components/SpeciesCard.kt`
- `app/src/main/kotlin/ch/etasystems/pirol/ui/components/DetectionList.kt`
- `app/src/main/kotlin/ch/etasystems/pirol/ui/live/LiveScreen.kt`
- `app/src/main/kotlin/ch/etasystems/pirol/ui/live/LiveViewModel.kt`
- `app/src/main/kotlin/ch/etasystems/pirol/data/repository/SessionManager.kt`

**Aktuelle `VerificationStatus`** (nach T44):
```kotlin
@Serializable
enum class VerificationStatus {
    UNVERIFIED, CONFIRMED, REJECTED, CORRECTED, UNCERTAIN
}
```

**`DetectionCandidate`** (bereits in `DetectionResult.kt`):
```kotlin
@Serializable
data class DetectionCandidate(
    val scientificName: String,
    val commonName: String,
    val confidence: Float
)
```

**Aktuelles Kandidaten-UI** (in `SpeciesCard.kt` ~Zeile 393 ff.):
Kandidaten werden in einer aufklappbaren `Column` angezeigt. Jede Zeile ist eine `Row` mit `commonName` links und `confidence` rechts. Die Zeilen sind aktuell **nicht klickbar** — das ist die Hauptänderung.

**`appendVerification()`** in `SessionManager.kt`:
Schreibt ein `VerificationEvent` in `verifications.jsonl`. Wird bereits für CONFIRMED/REJECTED/CORRECTED/UNCERTAIN genutzt.

**`verifyDetection()`** in `LiveViewModel.kt`:
Generische Funktion die `detectionListState.updateVerification()` + `sessionManager.appendVerification()` kombiniert. Für T45 wird eine **separate** Funktion `selectAlternative()` benötigt, da die Logik komplexer ist (neuer Detection-Eintrag, nicht nur Status-Update).

---

## Änderung 1 — `DetectionResult.kt`

`REPLACED` zu `VerificationStatus` hinzufügen:

```kotlin
@Serializable
enum class VerificationStatus {
    UNVERIFIED, CONFIRMED, REJECTED, CORRECTED, UNCERTAIN, REPLACED
}
```

`REPLACED` bedeutet: Diese Detektion wurde durch eine vom Nutzer gewählte Alternative ersetzt. Sie ist inhaltlich falsch.

---

## Änderung 2 — `DetectionListState.kt`

**Neue öffentliche Methode `selectAlternative()`:**

```kotlin
/**
 * Ersetzt eine Detektion durch eine vom Nutzer gewaehlte Alternative.
 *
 * - Original-Detektion wird als REPLACED markiert (bleibt an ihrer Position, ausgegraut)
 * - Alternative wird als neue Detektion an Index 0 eingefuegt (5s-Highlight)
 * - Falls die Alternative bereits in der Liste war, wird sie vorher entfernt (kein Duplikat)
 *
 * @param originalDetectionId UUID der zu ersetzenden Detektion
 * @param candidate Vom Nutzer gewaehlte Alternative
 * @return true wenn erfolgreich, false wenn originalDetectionId nicht gefunden
 */
@Synchronized
fun selectAlternative(
    originalDetectionId: String,
    candidate: DetectionCandidate
): Boolean {
    val originalIndex = detections.indexOfFirst { it.id == originalDetectionId }
    if (originalIndex < 0) return false

    val original = detections[originalIndex]
    val now = System.currentTimeMillis()

    // 1. Original als REPLACED markieren (bleibt an Position)
    detections[originalIndex] = original.copy(
        verificationStatus = VerificationStatus.REPLACED,
        correctedSpecies = candidate.scientificName,
        verifiedAtMs = now
    )

    // 2. Falls die Alternative-Art schon woanders in der Liste ist → entfernen
    detections.removeAll {
        it.id != originalDetectionId &&
        it.scientificName == candidate.scientificName
    }

    // 3. Alternative als neue Detektion an Index 0
    val alternativeDetection = DetectionResult(
        id = java.util.UUID.randomUUID().toString(),
        scientificName = candidate.scientificName,
        commonName = candidate.commonName,
        confidence = candidate.confidence,
        timestampMs = now,
        chunkStartSec = original.chunkStartSec,
        chunkEndSec = original.chunkEndSec,
        sampleRate = original.sampleRate,
        latitude = original.latitude,
        longitude = original.longitude,
        verificationStatus = VerificationStatus.UNVERIFIED,
        candidates = emptyList(),
        detectionCount = 1,
        lastDetectedMs = now
    )
    detections.add(0, alternativeDetection)

    // 4. Max-Limit einhalten
    while (detections.size > maxDetections) {
        detections.removeAt(detections.lastIndex)
    }

    _version.intValue++
    return true
}
```

Import falls nicht vorhanden: `java.util.UUID` (bereits in anderen Dateien genutzt, prüfen).

---

## Änderung 3 — `SpeciesCard.kt`

### 3a. Neuer Callback-Parameter

```kotlin
onSelectAlternative: ((DetectionCandidate) -> Unit)? = null,
```

### 3b. Kandidaten-Zeilen klickbar machen

Im bestehenden Kandidaten-`AnimatedVisibility`-Block: wenn `onSelectAlternative != null`, jede `Row` mit `.clickable { onSelectAlternative(candidate) }` versehen. Standard Compose-Ripple genügt, kein zusätzliches Icon.

**Import falls nötig:** `androidx.compose.foundation.clickable`

### 3c. Kandidaten-Header-Text

Wenn `onSelectAlternative != null`, Hinweis ergänzen dass die Einträge wählbar sind:
```kotlin
text = if (candidatesExpanded) "Alternativen ausblenden"
       else if (onSelectAlternative != null) "▸ Alternative waehlen (${detection.candidates.size})"
       else "Alternativen (${detection.candidates.size})"
```

### 3d. REPLACED-Visualisierung

**`verificationBorder` when-Block ergänzen:**
```kotlin
VerificationStatus.REPLACED -> BorderStroke(2.dp, MaterialTheme.colorScheme.error)
```

**Status-Badge when-Block ergänzen:**
```kotlin
VerificationStatus.REPLACED -> Text(
    text = "Ersetzt \u2192 ${detection.correctedSpecies ?: "?"}",
    style = MaterialTheme.typography.labelSmall,
    fontWeight = FontWeight.Bold,
    color = MaterialTheme.colorScheme.error
)
```

**Status-Text (Kompakt) when-Block ergänzen:**
```kotlin
VerificationStatus.REPLACED -> "\u2717"
```

**`cardAlpha`:**
```kotlin
val cardAlpha = if (detection.verificationStatus == VerificationStatus.REJECTED ||
                    detection.verificationStatus == VerificationStatus.REPLACED) 0.6f else 1f
```

---

## Änderung 4 — `DetectionList.kt`

Neuen Parameter hinzufügen und an `SpeciesCard` durchreichen (analog zu `onConfirm` etc.):

```kotlin
onSelectAlternative: ((String, DetectionCandidate) -> Unit)? = null,
```

```kotlin
onSelectAlternative = onSelectAlternative?.let { cb ->
    { candidate -> cb(detection.id, candidate) }
},
```

---

## Änderung 5 — `LiveViewModel.kt`

**Neue öffentliche Funktion:**

```kotlin
/**
 * Ersetzt eine Detektion durch eine vom Nutzer gewaehlte Alternative (T45).
 *
 * Markiert das Original als REPLACED, fuegt die Alternative als neue Detektion
 * an Index 0 ein und schreibt ein VerificationEvent in verifications.jsonl.
 */
fun selectAlternative(detectionId: String, candidate: DetectionCandidate) {
    val success = detectionListState.selectAlternative(detectionId, candidate)
    if (success) {
        viewModelScope.launch(Dispatchers.IO) {
            sessionManager.appendVerification(
                VerificationEvent(
                    detectionId = detectionId,
                    status = VerificationStatus.REPLACED,
                    correctedSpecies = candidate.scientificName,
                    verifiedAtMs = System.currentTimeMillis()
                )
            )
        }
    }
}
```

---

## Änderung 6 — `LiveScreen.kt`

An **allen** `DetectionList`-Aufrufen (Compact / Medium / Expanded Layout — analog zu T44) verdrahten:

```kotlin
onSelectAlternative = { id, candidate ->
    viewModel.selectAlternative(id, candidate)
}
```

---

## Scope-Ausschluss

- **Kein** UI im Analyse-Tab (nur Live-Screen)
- **Keine** Änderung an `KmlExporter` oder anderen Export-Formaten (JSONL-Feld `verificationStatus = "REPLACED"` genügt downstream)
- **Kein** Undo/Rückgängig-Mechanismus
- **Kein** Filter, der REPLACED-Einträge aus der Anzeige ausblendet (sie bleiben sichtbar, ausgegraut)
- **Kein** Runtime-Test auf Device/Emulator
- **Kein** Refactoring bestehender Buttons oder Callback-Signaturen

---

## Testanforderung

```bash
./gradlew compileDebugKotlin
```

→ **BUILD SUCCESSFUL**, null Compile-Errors.

Zusätzlich sicherstellen:
- `VerificationStatus.REPLACED` ist in **allen** `when`-Ausdrücken in `SpeciesCard.kt` abgedeckt (kein non-exhaustive when)
- `selectAlternative()` gibt `false` zurück wenn `detectionId` nicht gefunden → kein Crash
- Andere Dateien mit `when (verificationStatus)` prüfen (AnalysisScreen.kt, AnalysisViewModel.kt) — falls dort kein `else`-Branch ist, REPLACED ergänzen

---

## Acceptance Criteria

1. `VerificationStatus.REPLACED` existiert und ist `@Serializable`
2. Kandidaten-Zeilen in `SpeciesCard` sind klickbar wenn `onSelectAlternative != null` (mit Ripple-Effekt)
3. Klick auf Kandidat: Original → REPLACED (roter Rand, ausgegraut mit `alpha=0.6`, Badge "Ersetzt → <Art>"); Alternative an Index 0 mit 5s-Highlight
4. `VerificationEvent(status=REPLACED, correctedSpecies=<Alternative>)` wird in `verifications.jsonl` geschrieben
5. Wenn die alternative Art bereits in der Liste war, wird sie entfernt bevor sie an Index 0 eingefügt wird (kein Duplikat)
6. `selectAlternative()` mit ungültiger ID → kein Crash, `false` Rückgabe
7. `compileDebugKotlin` BUILD SUCCESSFUL

---

## Handover

`Handover_T45.md` im Projekt-Root (`D:\80002\PIROL\`) abliefern mit:
1. Zusammenfassung was geändert wurde
2. Vollständige Liste geänderter Dateien
3. Build-Status
4. Offene Punkte / Beobachtungen (insbesondere: weitere `when (verificationStatus)`-Stellen in anderen Dateien)
