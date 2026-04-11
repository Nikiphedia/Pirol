# Arbeitspaket T53: Code-Scan Fixes (alle 14 Funde aus Scan_T52)

## Ziel

Alle 14 Funde aus `Scan_T52.md` beheben. Kein Fund wird akzeptiert — alles wird gefixt.

## Kontext

- Scan: `Scan_T52.md` im Projekt-Root (76 Dateien, 13 Konventions-Funde, 1 Dependency-Fund)
- Konventionen: `CLAUDE.md` im Projekt-Root
- Projekt-Root: `C:\eta_Data\80002\PIROL`
- Quellcode: `app/src/main/kotlin/ch/etasystems/pirol/`

---

## Fix-Liste

### Fix 1 — WatchlistPriority Enum-Werte (Scan #1, minor)

**Datei:** `ml/WatchlistEntry.kt:12-13`

**Ist:**
```kotlin
enum class WatchlistPriority {
    high, normal, low
}
```

**Soll:**
```kotlin
enum class WatchlistPriority {
    HIGH, NORMAL, LOW
}
```

**Achtung:** Enum-Werte werden in JSON serialisiert (`watchlist.json`) und in der UI referenziert. Alle Stellen aktualisieren:
- `WatchlistEntry.kt:24` — Default `WatchlistPriority.NORMAL`
- `SettingsScreen.kt` — Alle Referenzen auf `.high`, `.normal`, `.low`
- `WatchlistManager.kt` — falls Vergleiche
- `AlarmService.kt` — falls Priority-Checks

**Serialisierung:** Da `@Serializable` genutzt wird, `@SerialName("high")` etc. hinzufuegen, damit bestehende `watchlist.json`-Dateien weiterhin gelesen werden:
```kotlin
enum class WatchlistPriority {
    @SerialName("high") HIGH,
    @SerialName("normal") NORMAL,
    @SerialName("low") LOW
}
```

---

### Fix 2 — Tab-Label "Settings" (Scan #2, minor)

**Datei:** `ui/navigation/Screen.kt:21`

**Ist:**
```kotlin
Settings("settings", "Settings", Icons.Filled.Settings)
```

**Soll:**
```kotlin
Settings("settings", "Einstellungen", Icons.Filled.Settings)
```

Nur das Label aendern. Route `"settings"` bleibt.

---

### Fix 3 — getSpeciesCount() Dead Code (Scan #3, minor)

**Datei:** `ui/reference/ReferenceScreen.kt:136-138`

```kotlin
private fun getSpeciesCount(scientificName: String, viewModel: ReferenceViewModel): Int {
    return viewModel.getSpeciesCount(scientificName)
}
```

Loeschen. Aufrufer direkt auf `viewModel.getSpeciesCount()` umstellen.
Suche alle Aufrufe von `getSpeciesCount(` in `ReferenceScreen.kt` und ersetze durch `viewModel.getSpeciesCount(...)`.

---

### Fix 4 — overlapMs @Suppress entfernen (Scan #4, minor)

**Datei:** `ml/ChunkAccumulator.kt:45`

`overlapMs` ist `@Suppress("unused")`. Wenn der Parameter wirklich nie gelesen wird:
- Parameter aus dem Konstruktor entfernen
- Alle Aufrufstellen pruefen (InferenceWorker, LiveViewModel) und den Parameter dort ebenfalls entfernen

Falls der Parameter an Aufrufstellen uebergeben wird, dort ebenfalls entfernen.

---

### Fix 5 — AudioPlayer.release() Dead Code (Scan #5, minor)

**Datei:** `audio/AudioPlayer.kt:54-57`

```kotlin
fun release() {
    stop()
}
```

Loeschen. Alle Aufrufer nutzen bereits direkt `stop()`.
Pruefen, dass `release()` nirgends aufgerufen wird (Grep ueber gesamten Quellcode).

---

### Fix 6+7 — scientificName Normalisierung (Scan #6+#7, minor)

**Problem:** `DetectionResult.scientificName` und `XenoCantoRecording.scientificName` nutzen Leerzeichen, interne Konvention verlangt Unterstriche.

**Strategie:** Normalisierung an den Grenzen — dort wo Werte erzeugt werden:

1. `DetectionResult` (ml/DetectionResult.kt:38): BirdNET liefert "Turdus merula" mit Leerzeichen. Im InferenceWorker bei Erzeugung normalisieren: `scientificName = rawName.replace(' ', '_')`.

2. `XenoCantoRecording.scientificName` (data/api/XenoCantoModels.kt:38): Property aendern zu `"$gen $sp".trim().replace(' ', '_')`.

3. **Vorsicht:** Alle Stellen pruefen, die `scientificName` mit Leerzeichen erwarten:
   - `SpeciesNameResolver` — Keys in `namesMap` pruefen (Format in `species_master.json`)
   - `RegionalSpeciesFilter.isPlausible()` — normalisiert bereits
   - `searchBySpecies()` in XenoCantoClient — wandelt `_` in Leerzeichen um, funktioniert weiterhin

---

### Fix 8 — highpassFilter Default-Parameter (Scan #8, minor)

**Datei:** `audio/dsp/AudioDspUtils.kt:43`

**Ist:**
```kotlin
fun highpassFilter(samples: FloatArray, sampleRate: Int = 48000, cutoffHz: Float = 200f)
```

**Soll:** Default-Wert entfernen, damit Aufrufer explizit die Rate angeben muessen:
```kotlin
fun highpassFilter(samples: FloatArray, sampleRate: Int, cutoffHz: Float = 200f)
```

Alle Aufrufstellen pruefen und expliziten `sampleRate` uebergeben. Es gibt zwei Kontexte:
- Oboe-Pfad: 48000
- Inference-Pfad: 32000

---

### Fix 9 — OboeAudioEngine Koin Dead-Singleton (Scan #9, kritisch)

**Datei:** `di/AppModule.kt:33`

**Problem:** `single { OboeAudioEngine() }` ist registriert, aber `RecordingService` erstellt eigene Instanz (`private val engine = OboeAudioEngine()` in RecordingService.kt:57). Zwei Engine-Instanzen moeglich.

**Fix:** Koin-Registrierung von `OboeAudioEngine` entfernen (Zeile 32-33 in AppModule.kt). RecordingService verwaltet seinen eigenen Lifecycle — das ist korrekt fuer einen Foreground Service.

Pruefen: Wird `OboeAudioEngine` irgendwo per `get()` oder `koinInject()` geholt? Falls ja, dort ebenfalls aendern. Falls nein: einfach loeschen.

---

### Fix 10 — InferenceWorker Koin-Registrierung (Scan #10, minor)

**Problem:** InferenceWorker wird manuell instanziiert, Dependencies werden vom ViewModel durchgereicht.

**Fix:** InferenceWorker in Koin registrieren als `factory` (nicht Singleton — kurzlebig):
```kotlin
factory { InferenceWorker(get(), get()) }
```

Im LiveViewModel dann per Koin injizieren statt manuell instanziieren.
Pruefen welche Dependencies der InferenceWorker-Konstruktor braucht und ob alle in Koin verfuegbar sind.

---

### Fix 11 — StorageManager Injection-Konsistenz (Scan #11, minor)

**Datei:** `di/AppModule.kt:57`

**Problem:** StorageManager als Singleton registriert, wird nur via `koinInject()` aus UI verwendet, nie per Konstruktor-Injection.

**Fix:** Pruefen ob StorageManager in einem ViewModel-Konstruktor sinnvoll waere (z.B. SettingsViewModel, falls vorhanden). Falls nicht: Koin-Registrierung belassen (fuer koinInject() benoetigt), aber Kommentar aktualisieren:
```kotlin
// StorageManager — Singleton, via koinInject() in UI-Composables (T38)
single { StorageManager(get()) }
```

---

### Fix 12 — runBlocking im ViewModel.init (Scan #12, kritisch, Variante B)

**Datei:** `ui/live/LiveViewModel.kt:228`

**Ist:**
```kotlin
runBlocking(Dispatchers.IO) { speciesNameResolver.load() }
```

**Soll (Variante B):** `SpeciesNameResolver.load()` wird beim App-Start vorab aufgerufen, nicht im ViewModel.

**Umsetzung:**
1. In `PirolApp.kt` (Application-Klasse): Im `onCreate()` nach Koin-Init den Resolver laden:
   ```kotlin
   val resolver: SpeciesNameResolver = get()
   CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
       resolver.load()
   }
   ```
   Da `load()` intern `if (loaded) return` prueft, ist Mehrfachaufruf sicher.

2. In `LiveViewModel.kt:228`: Die Zeile `runBlocking(Dispatchers.IO) { speciesNameResolver.load() }` ersetzen durch:
   ```kotlin
   // SpeciesNameResolver wird in PirolApp.onCreate() vorab geladen (T53)
   // Falls noch nicht fertig: Fallback auf lateinischen Namen, kein ANR
   ```
   (Kein Aufruf mehr — Resolver ist bereits geladen oder laedt noch im Hintergrund.)

3. `PirolApp.kt` lesen und die genaue Stelle finden (nach `startKoin {}` Block).

---

### Fix 13 — onCleared Timeout (Scan #13, minor)

**Datei:** `ui/live/LiveViewModel.kt:799-815`

**Ist:**
```kotlin
val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
cleanupScope.launch {
    try { ... } finally { cleanupScope.cancel() }
}
```

**Soll:** `withTimeout` hinzufuegen, damit Cleanup nicht ewig blockiert:
```kotlin
val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
cleanupScope.launch {
    try {
        withTimeout(5_000L) {
            sessionManager.endSession()
            val file = getEmbeddingDbFile() ?: return@withTimeout
            file.parentFile?.mkdirs()
            embeddingDatabase.save(file)
            Log.d(TAG, "Embedding-DB gespeichert: ${embeddingDatabase.size} Eintraege")
        }
    } catch (e: Exception) {
        Log.e(TAG, "Cleanup fehlgeschlagen oder Timeout", e)
    } finally {
        cleanupScope.cancel()
    }
}
```

---

### Fix 14 — kotlinx-serialization-json doppelt (Scan Dep-1, minor)

**Datei:** `gradle/libs.versions.toml`

**Problem:** `kotlinx-serialization-json` direkt deklariert UND transitiv durch Ktor eingebracht.

**Fix:** Pruefen ob die direkte Dependency (`implementation(libs.kotlinx.serialization.json)` in `app/build.gradle.kts`) entfernt werden kann, da Ktor sie transitiv mitbringt. Falls andere Module sie direkt brauchen (z.B. `@Serializable` ohne Ktor), belassen und Kommentar ergaenzen.

Wahrscheinlich wird sie direkt gebraucht (fuer `@Serializable` Data Classes die nicht ueber Ktor gehen). In dem Fall: Belassen, aber in `libs.versions.toml` einen Kommentar ergaenzen:
```toml
# Direkt benoetigt fuer @Serializable (nicht nur transitiv via Ktor)
kotlinx-serialization-json = { ... }
```

---

## Scope-Ausschluss

- Keine neuen Features
- Keine Architektur-Aenderungen ausser den hier beschriebenen
- findCommonName() in ReferenceScreen.kt NICHT anfassen (bekannt akzeptiert)

## Reihenfolge

Empfohlen: Kritische zuerst (Fix 9, Fix 12), dann Rest.

## Testanforderung

- `./gradlew compileDebugKotlin` muss BUILD SUCCESSFUL sein
- Grep nach `runBlocking` — darf nur noch in Test-Code vorkommen
- Grep nach `OboeAudioEngine()` — darf nur noch in RecordingService vorkommen

## Acceptance Criteria

1. Alle 14 Fixes umgesetzt (oder begruendet, warum ein spezifischer Fix anders geloest wurde)
2. Build kompiliert fehlerfrei
3. Kein `runBlocking` mehr im ViewModel-Code
4. Kein toter Koin-Singleton (OboeAudioEngine)
5. Enum-Werte in SCREAMING_SNAKE mit @SerialName fuer Rueckwaertskompatibilitaet
6. Handover_T53.md mit vollstaendiger Dateiliste
