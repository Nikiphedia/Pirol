# T47 — Raven Selection Table Exporter

## Voraussetzung

**T46 ist abgeschlossen und reviewed.** Jede neue Session enthält `audio/recording.wav`.

---

## Ziel

Pro Session wird neben `recording.wav` eine **Raven Selection Table** (`recording.selections.txt`) generiert, die Cornell Raven, Audacity (Label-Track-Import) und Sonic Visualiser direkt öffnen können.

Format ist Tab-getrennt, eine Header-Zeile, eine Zeile pro Detektion. Quelle: `detections.jsonl` + `verifications.jsonl` (über `loadDetectionsWithVerifications`). REPLACED-/REJECTED-Einträge werden **mit-exportiert** (Status-Spalte zeigt es) — die Selection-Table ist Audit-Trail, kein gefilterter Final-Output.

---

## Projekt-Kontext

- **Package:** `ch.etasystems.pirol`
- **Pfad:** `D:\80002\PIROL`
- **Build-Befehl:** `./gradlew compileDebugKotlin`

**Bestehender Referenz-Code:**

- `app/src/main/kotlin/ch/etasystems/pirol/data/export/KmlExporter.kt` (Stilvorlage — analoge Struktur)
- `app/src/main/kotlin/ch/etasystems/pirol/data/repository/SessionManager.kt` (`loadDetectionsWithVerifications()`, `loadMetadata()`)
- `app/src/main/kotlin/ch/etasystems/pirol/ml/DetectionResult.kt` (Felder)
- `app/src/main/kotlin/ch/etasystems/pirol/ui/analysis/AnalysisScreen.kt` (UI-Anker für Export-Button)

**Relevante Detection-Felder für die Tabelle:**
- `chunkStartSec` / `chunkEndSec` → **Begin Time** / **End Time** in Sekunden seit Aufnahme-Start
- `scientificName`, `commonName`, `confidence`, `verificationStatus`, `correctedSpecies`

---

## Raven Selection Table Format

Tab-getrennt (`\t` als Trenner), `\n` als Zeilenende, UTF-8.

**Header-Zeile (exakt):**
```
Selection	View	Channel	Begin Time (s)	End Time (s)	Low Freq (Hz)	High Freq (Hz)	Species	Common Name	Confidence	Status	Corrected Species
```

**Datenzeile (Beispiel):**
```
1	Spectrogram 1	1	12.345	15.345	0	12000	Parus major	Kohlmeise	0.873	CONFIRMED	
2	Spectrogram 1	1	23.100	26.100	0	12000	Turdus merula	Amsel	0.721	UNCERTAIN	
3	Spectrogram 1	1	45.900	48.900	0	12000	Erithacus rubecula	Rotkehlchen	0.910	REPLACED	Sylvia atricapilla
```

**Konventionen:**
- `Selection`: 1-basiert, fortlaufend
- `View`: konstant `"Spectrogram 1"`
- `Channel`: konstant `1` (Mono)
- Frequenz-Spalten: konstant `0` und `12000` (BirdNET-Default — kein Per-Detection-Bandpass verfügbar)
- `Begin Time` / `End Time`: 3 Nachkommastellen, Punkt als Dezimaltrenner (Locale.US)
- `Confidence`: 3 Nachkommastellen, Locale.US
- `Corrected Species`: leer wenn `null`

---

## Änderung 1 — Neue Datei `data/export/RavenExporter.kt`

```kotlin
package ch.etasystems.pirol.data.export

import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.ml.VerificationStatus
import java.io.File
import java.util.Locale

/**
 * Exportiert Detektionen einer Session als Raven Selection Table (.txt).
 *
 * Format: Tab-getrennt, eine Header-Zeile + eine Zeile pro Detektion.
 * Kompatibel mit Cornell Raven, Audacity (Label-Track), Sonic Visualiser.
 */
object RavenExporter {

    private const val LOW_FREQ_HZ = 0
    private const val HIGH_FREQ_HZ = 12000
    private const val VIEW = "Spectrogram 1"
    private const val CHANNEL = 1

    private val HEADER = listOf(
        "Selection", "View", "Channel",
        "Begin Time (s)", "End Time (s)",
        "Low Freq (Hz)", "High Freq (Hz)",
        "Species", "Common Name", "Confidence",
        "Status", "Corrected Species"
    ).joinToString("\t")

    /**
     * Schreibt die Selection-Table.
     *
     * @param outputFile Zieldatei (z.B. recording.selections.txt)
     * @param detections Detektionen mit angewandten Verifikationen
     * @return Anzahl geschriebener Zeilen
     */
    fun export(outputFile: File, detections: List<DetectionResult>): Int {
        outputFile.bufferedWriter(Charsets.UTF_8).use { w ->
            w.appendLine(HEADER)
            detections.forEachIndexed { index, d ->
                val row = listOf(
                    (index + 1).toString(),
                    VIEW,
                    CHANNEL.toString(),
                    String.format(Locale.US, "%.3f", d.chunkStartSec),
                    String.format(Locale.US, "%.3f", d.chunkEndSec),
                    LOW_FREQ_HZ.toString(),
                    HIGH_FREQ_HZ.toString(),
                    sanitize(d.scientificName),
                    sanitize(d.commonName),
                    String.format(Locale.US, "%.3f", d.confidence),
                    d.verificationStatus.name,
                    sanitize(d.correctedSpecies ?: "")
                ).joinToString("\t")
                w.appendLine(row)
            }
        }
        return detections.size
    }

    /** Tabs/Newlines aus Strings entfernen, damit das TSV-Format nicht bricht. */
    private fun sanitize(s: String): String =
        s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()
}
```

---

## Änderung 2 — `SessionManager.kt`: Convenience-Methode

```kotlin
/**
 * Schreibt eine Raven Selection Table neben recording.wav.
 * @return Pfad zur erzeugten Datei oder null wenn keine recording.wav existiert
 */
suspend fun exportRavenSelectionTable(sessionDir: File): File? = withContext(Dispatchers.IO) {
    val recording = getRecordingFile(sessionDir) ?: return@withContext null
    val outputFile = File(sessionDir, "audio/recording.selections.txt")
    val detections = loadDetectionsWithVerifications(sessionDir)
    RavenExporter.export(outputFile, detections)
    outputFile
}
```

Import: `ch.etasystems.pirol.data.export.RavenExporter`.

---

## Änderung 3 — `AnalysisScreen.kt` / `AnalysisViewModel.kt`: Export-Button

Im Bereich wo bereits der **KML-Export-Button** sitzt: einen zweiten Button `"Raven (.txt)"` daneben einfügen.

**ViewModel:**
```kotlin
fun exportRavenTable() {
    val dir = currentSessionDir ?: return
    viewModelScope.launch(Dispatchers.IO) {
        val file = sessionManager.exportRavenSelectionTable(dir)
        // Snackbar / Toast wie beim KML-Export
    }
}
```

**Screen:**
```kotlin
Button(onClick = { viewModel.exportRavenTable() }) {
    Text("Raven (.txt)")
}
```

Stil exakt analog zum bestehenden KML-Button (gleicher Container, gleiche Reihenfolge, kein neues Layout).

---

## Scope-Ausschluss

- **Kein** Filter (REPLACED/REJECTED bleiben in der Tabelle, Status-Spalte zeigt es)
- **Kein** Per-Detection-Bandpass (Low/High-Freq sind konstant 0/12000)
- **Kein** automatischer Export beim Session-Ende — nur on-demand via Button
- **Kein** Share-Intent (User öffnet Datei manuell aus dem Sessions-Ordner)
- **Keine** Unterstützung für alte Chunk-Sessions (die haben keine `recording.wav` — Methode gibt `null` zurück, Button no-op + Log)
- **Kein** Runtime-Test

---

## Testanforderung

```bash
./gradlew compileDebugKotlin
```

→ **BUILD SUCCESSFUL**, null Compile-Errors.

---

## Acceptance Criteria

1. `RavenExporter.export()` schreibt eine Datei mit korrektem Tab-getrenntem Format
2. Dezimalzahlen mit Punkt (Locale.US), 3 Nachkommastellen
3. Header-Zeile exakt wie spezifiziert (Reihenfolge der Spalten zählt)
4. Eine Zeile pro Detektion in der Reihenfolge wie sie in `detections.jsonl` stehen
5. Status-Spalte enthält den `VerificationStatus.name` (z.B. `CONFIRMED`, `REPLACED`)
6. `correctedSpecies` ist leer für Detektionen ohne Korrektur (kein `null`-Literal in der Datei)
7. Tabs/Newlines in `commonName`/`scientificName` werden zu Spaces ersetzt (kein zerschossenes TSV)
8. Button im Analyse-Tab löst Export aus, erzeugt `recording.selections.txt` neben `recording.wav`
9. `compileDebugKotlin` BUILD SUCCESSFUL

---

## Handover

`Handover_T47.md` im Projekt-Root mit:
1. Zusammenfassung
2. Geänderte/neue Dateien
3. Build-Status
4. Beispiel-Output: ersten 3 Zeilen einer generierten Selection-Table im Markdown-Codeblock
5. Beobachtungen — insbesondere falls `chunkStartSec`/`chunkEndSec` Werte enthalten die nicht plausibel sind (T46 hat das Feld evtl. nicht angepasst — dann in T48 nachziehen)
