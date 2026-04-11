# Arbeitspaket T50: ReferenceDownloader (Xeno-Canto → Referenzbibliothek)

## Ziel

User kann im Referenz-Tab nach Vogelarten suchen, Xeno-Canto-Aufnahmen durchblaettern, anhoeren und als lokale Referenz herunterladen. Downloads landen in der bestehenden ReferenceRepository-Struktur.

## Kontext

- **Abhaengigkeit:** T49 (XenoCantoClient) — implementiert, uncommitted auf master
- **Abhaengigkeit:** T18 (ReferenceRepository) — implementiert und committed

### Bestehende Infrastruktur

**XenoCantoClient** (`data/api/XenoCantoClient.kt`):
- `searchBySpecies(scientificName, quality?, country?, page, perPage)` → `List<XenoCantoRecording>`
- `search(query, page, perPage)` → generische Suche
- API-Key aus `SecurePreferences`
- Ktor OkHttp, HTTPS-only

**XenoCantoRecording** (`data/api/XenoCantoModels.kt`):
- `id`, `gen`, `sp`, `en` (engl. Name), `cnt` (Land), `loc` (Ort), `lat`/`lon`
- `q` (Qualitaet A-E), `length` (Dauer), `type` (call/song)
- `audioUrl` — HTTPS-URL zum Audio-File
- `sono` — Sonogramm-URLs (small/med/large/full)
- `rec` — Name des Aufnehmenden

**ReferenceRepository** (`data/repository/ReferenceRepository.kt`):
- Speichert Referenzen in `filesDir/references/{scientificName}/`
- `index.json` mit `ReferenceIndex` (Liste von `ReferenceEntry`)
- `ReferenceEntry`: id, scientificName, commonName, confidence, wavFileName, sourceSessionId, sourceDetectionId, recordedAtMs, addedAtMs, lat/lon, verificationStatus
- `addFromDetection()` — aktuell nur aus lokalen Detektionen
- WAV-Format: 16-bit PCM Mono

**ReferenceScreen** (`ui/reference/ReferenceScreen.kt`):
- Zwei-Ebenen-Navigation: Artenliste → Aufnahmen pro Art
- Playback (AudioPlayer), Loeschen mit Bestaetigung
- 499 Zeilen, gut strukturiert

**ReferenceViewModel** (`ui/reference/ReferenceViewModel.kt`):
- State: `ReferenceUiState` (speciesList, recordings, selectedSpecies, isLoading)
- Methoden: `loadSpecies()`, `selectSpecies()`, `deleteReference()`, `getSpeciesCount()`

### Normung (aus CLAUDE.md)

- Packages: lowercase
- Klassen: PascalCase
- Funktionen: camelCase
- Kommentare: Deutsch
- UI-Strings: Deutsch
- API-Keys nur aus SecurePreferences, nie loggen
- HTTPS-only (Ktor OkHttp Default)
- Coroutines, kein GlobalScope

---

## Anforderungen

### 1. ReferenceDownloader Klasse

Neue Klasse: `data/repository/ReferenceDownloader.kt`

```kotlin
class ReferenceDownloader(
    private val xenoCantoClient: XenoCantoClient,
    private val referenceRepository: ReferenceRepository
)
```

**Methoden:**
- `suspend fun downloadRecording(recording: XenoCantoRecording): Result<ReferenceEntry>`
  - Audio von `recording.audioUrl` herunterladen (Ktor HttpClient)
  - Falls MP3: Konvertierung zu WAV via Android MediaCodec (oder als MP3 speichern — siehe Entscheidung unten)
  - In ReferenceRepository einfuegen (neuer `ReferenceEntry` mit Quelle "xeno-canto")

**Entscheidung Audio-Format:**
Xeno-Canto liefert MP3. Die ReferenceRepository erwartet WAV. Zwei Optionen:
- **Option A:** MP3 direkt speichern, `ReferenceEntry.wavFileName` in `audioFileName` umbenennen (Breaking Change an Bestandscode)
- **Option B:** MP3 → WAV konvertieren via Android `MediaExtractor` + `MediaCodec` (aufwaendiger, aber kompatibel)

**Empfehlung:** Option A — MP3 speichern, Feld-Rename. Begruendung: WAV aus Xeno-Canto waere 10-50x groesser, MP3-Playback funktioniert mit `MediaPlayer` identisch. Das Feld `wavFileName` wird nur intern genutzt (kein Export-Format).

**Falls Option A gewaehlt:**
- `ReferenceEntry.wavFileName` → `audioFileName` umbenennen (alle Referenzen anpassen)
- `ReferenceRepository` anpassen: `getWavFile()` → `getAudioFile()`
- AudioPlayer spielt bereits ueber `MediaPlayer.setDataSource()` — funktioniert mit MP3

### 2. ReferenceEntry erweitern

Neues optionales Feld fuer Xeno-Canto-Herkunft:
```kotlin
val source: String = "local",       // "local" oder "xeno-canto"
val xenoCantoId: String? = null,    // z.B. "XC123456"
val recordist: String? = null       // Name des Aufnehmenden
```

Bestehende Eintraege (source fehlt) → Default "local", rueckwaertskompatibel durch Default-Werte.

### 3. Xeno-Canto-Suche in ReferenceScreen

Neuer UI-Abschnitt im ReferenceScreen (oder als eigener Screen/Dialog):

**Einstiegspunkt:** Button "Xeno-Canto durchsuchen" (nur sichtbar wenn `xenoCantoClient.hasApiKey`)

**Such-UI:**
- Textfeld fuer Artname (wissenschaftlich, mit Autocomplete aus RegionalSpeciesFilter — T51 Pattern nutzen)
- Qualitaets-Filter (Chips: "Alle", "A", "A-B", Default: "A-B")
- Suchergebnis: `LazyColumn` mit Xeno-Canto-Aufnahmen:
  ```
  [Art] Turdus merula — Song
  [Ort] Zürich, Switzerland
  [Qualitaet] A | [Dauer] 0:45 | [Aufnehmer] J. Doe
  [▶ Anhoeren]  [⬇ Herunterladen]
  ```
- Paging: "Mehr laden" Button am Ende der Liste
- Online-Playback: Direkt von URL abspielen (MediaPlayer unterstuetzt HTTP-Streams)

**Download-Button:**
- Zeigt Fortschritt (CircularProgressIndicator waehrend Download)
- Nach Download: "Gespeichert" Text, Eintrag erscheint in lokaler Referenz-Liste
- Fehler: Toast mit Meldung

### 4. Koin-Registrierung

```kotlin
// ReferenceDownloader — Singleton (T50)
single { ReferenceDownloader(get(), get()) }
```

### 5. ReferenceViewModel erweitern

Neue State-Felder:
```kotlin
data class ReferenceUiState(
    // ... bestehende Felder ...
    val xenoCantoResults: List<XenoCantoRecording> = emptyList(),
    val isSearching: Boolean = false,
    val searchQuery: String = "",
    val downloadingIds: Set<String> = emptySet()  // XC-IDs die gerade laden
)
```

Neue Methoden:
- `fun searchXenoCanto(query: String, quality: String? = "A")` — Suche starten
- `fun loadMoreResults()` — Naechste Seite laden
- `fun downloadFromXenoCanto(recording: XenoCantoRecording)` — Download + lokale Speicherung
- `fun playXenoCantoPreview(url: String)` — Streaming-Playback

---

## Scope-Ausschluss

- **Kein Batch-Download** (nur einzelne Aufnahmen)
- **Kein Offline-Caching** der Xeno-Canto-Suchergebnisse
- **Kein Sonogramm-Preview** aus Xeno-Canto (nur Audio)
- **Keine Filter nach Land/Region** (kann spaeter ergaenzt werden)

## Testanforderung

- `./gradlew compileDebugKotlin` — BUILD SUCCESSFUL
- Suche liefert Ergebnisse (manueller Test mit API-Key auf Geraet)
- Download speichert Datei in `references/{species}/` (manueller Test)
- Bestehende lokale Referenzen werden nicht beeintraechtigt
- ReferenceEntry-Serialisierung rueckwaertskompatibel (Default-Werte)

## Acceptance Criteria

1. Xeno-Canto-Suche im ReferenceScreen (nur mit API-Key sichtbar)
2. Suchergebnisse mit Art, Qualitaet, Dauer, Ort angezeigt
3. Online-Playback funktioniert (Streaming)
4. Download speichert Audio + ReferenceEntry mit `source = "xeno-canto"`
5. Bestandscode (lokale Referenzen) funktioniert unveraendert
6. `ReferenceEntry` um `source`, `xenoCantoId`, `recordist` erweitert
7. Koin-Registrierung fuer ReferenceDownloader
8. Build kompiliert fehlerfrei
9. Handover_T50.md mit Dateiliste

## Offene Entscheidung fuer Worker

**Audio-Format:** Option A (MP3 speichern, Feld-Rename) oder Option B (MP3→WAV Konvertierung)?
→ **Nimm Option A**, sofern du keinen technischen Grund dagegen findest. Dokumentiere die Entscheidung im Handover.
