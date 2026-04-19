# T44 — UNCERTAIN-Status + 10s Re-detection Rule

## Ziel

Nach diesem Task hat die App einen vierten Verifikations-Status (`UNCERTAIN`) mit Fragezeichen-Button in der Detektionsliste, und erneut erkannte Arten rücken nur dann an die erste Stelle, wenn sie seit mehr als 10 Sekunden nicht mehr gesehen wurden (ruhigere Liste).

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
- `app/src/main/kotlin/ch/etasystems/pirol/ui/live/LiveViewModel.kt`

**Bestehende `VerificationStatus`-Werte** (in `DetectionResult.kt`):
```kotlin
@Serializable
enum class VerificationStatus {
    UNVERIFIED, CONFIRMED, REJECTED, CORRECTED
}
```

**Bestehende Re-detection-Logik** (in `DetectionListState.addDetections()`):
Wenn eine Art bereits in der Liste ist → Confidence und Count aktualisieren, **Position bleibt unverändert**.

**Bestehende Buttons in `SpeciesCard`:** ✓ Bestätigen · ✗ Ablehnen · ✎ Korrigieren

---

## Änderung 1 — `DetectionResult.kt`

`UNCERTAIN` zu `VerificationStatus` hinzufügen:

```kotlin
@Serializable
enum class VerificationStatus {
    UNVERIFIED, CONFIRMED, REJECTED, CORRECTED, UNCERTAIN
}
```

`UNCERTAIN` bedeutet: Nutzer hat die Art als unsicher markiert. Die Detektion bleibt in der Liste, wird aber orange markiert.

---

## Änderung 2 — `DetectionListState.kt`

**Neue Logik in `addDetections()`** für den Fall, dass die Art bereits in der Liste ist:

```
val now = System.currentTimeMillis()
val timeSinceLastDetection = now - existing.lastDetectedMs

if (timeSinceLastDetection >= 10_000L) {
    // Art zuletzt vor >= 10s gesehen → an Index 0 verschieben
    detections.removeAt(existingIndex)
    detections.add(0, updatedDetection)
} else {
    // Art zuletzt vor < 10s gesehen → Position beibehalten
    detections[existingIndex] = updatedDetection
}
```

**Wichtig:** `verificationStatus` einer bereits verifizierten Detektion beim Update **nicht überschreiben**. Nur `confidence` (max), `detectionCount` (inkrementieren) und `lastDetectedMs` aktualisieren.

---

## Änderung 3 — `SpeciesCard.kt`

**Neuer Callback-Parameter:**
```kotlin
onMarkUncertain: (() -> Unit)? = null,
```

**Button:** "?" zwischen Bestätigen (✓) und Ablehnen (✗) einfügen. Gleiches Format wie bestehende Buttons (`IconButton`, 32dp, Icon 18dp). Icon: `Icons.Filled.Help` oder `Icons.Filled.QuestionMark`.

**UNCERTAIN-Visualisierung — ergänzen in den bestehenden when-Blöcken:**

`verificationBorder`:
```kotlin
VerificationStatus.UNCERTAIN -> BorderStroke(2.dp, Color(0xFFFF9800))
```

Status-Badge:
```kotlin
VerificationStatus.UNCERTAIN -> Text(
    text = "Unsicher",
    style = MaterialTheme.typography.labelSmall,
    fontWeight = FontWeight.Bold,
    color = Color(0xFFFF9800)
)
```

Button-Icon tint (aktiver Zustand):
```kotlin
tint = if (detection.verificationStatus == VerificationStatus.UNCERTAIN)
    Color(0xFFFF9800) else MaterialTheme.colorScheme.onSurfaceVariant
```

`cardAlpha`: UNCERTAIN bleibt `1f` (voll sichtbar, nur Rand ist orange).

---

## Änderung 4 — `DetectionList.kt`

Neuen Parameter hinzufügen und an `SpeciesCard` durchreichen (analog zu `onConfirm`, `onReject`):

```kotlin
onMarkUncertain: ((String) -> Unit)? = null,
```

```kotlin
onMarkUncertain = onMarkUncertain?.let { callback -> { callback(detection.id) } },
```

---

## Änderung 5 — `LiveViewModel.kt`

Suche nach dem Aufruf von `DetectionList` in `LiveViewModel` oder `LiveScreen`. Dort `onMarkUncertain` verdrahten:

```kotlin
onMarkUncertain = { detectionId ->
    detectionListState.updateVerification(detectionId, VerificationStatus.UNCERTAIN)
    viewModelScope.launch(Dispatchers.IO) {
        sessionManager.appendVerification(
            VerificationEvent(
                detectionId = detectionId,
                status = VerificationStatus.UNCERTAIN,
                verifiedAtMs = System.currentTimeMillis()
            )
        )
    }
}
```

---

## Scope-Ausschluss

- Keine Änderungen an anderen `VerificationStatus`-Werten
- Kein Refactoring bestehender Buttons
- Kein neues Screen oder ViewModel
- Keine Änderungen im Analyse-Tab
- Kein Runtime-Test auf Device/Emulator

---

## Testanforderung

```bash
./gradlew compileDebugKotlin
```

→ **BUILD SUCCESSFUL**, null Compile-Errors.

Zusätzlich sicherstellen: `VerificationStatus.UNCERTAIN` ist in **allen** `when`-Blöcken in `SpeciesCard.kt` abgedeckt (kein non-exhaustive when).

---

## Acceptance Criteria

1. `VerificationStatus.UNCERTAIN` existiert und ist `@Serializable`
2. `SpeciesCard` hat "?" Button — sichtbar wenn `onMarkUncertain != null`
3. Bei UNCERTAIN: orangefarbener Rand + "Unsicher"-Badge, `cardAlpha = 1f`
4. `DetectionListState.addDetections()`: Art mit `lastDetectedMs >= 10s` rückt an Index 0; Art mit `< 10s` bleibt an Position
5. `verificationStatus` einer bereits verifizierten Detektion wird durch Re-detection nicht überschrieben
6. `compileDebugKotlin` BUILD SUCCESSFUL

---

## Handover

`Handover_T44.md` im Projekt-Root (`D:\80002\PIROL\`) abliefern mit:
1. Zusammenfassung was geändert wurde
2. Vollständige Liste geänderter Dateien
3. Build-Status
4. Offene Punkte / Beobachtungen
