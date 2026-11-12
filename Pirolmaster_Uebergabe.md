# Pirolmaster-Uebergabe — Master 8 → Master 9

**Datum:** 2026-05-04
**Abgebender Master:** Pirolmaster 8
**Status V0.0.7:** Cluster A+C+B+D komplett, Backlog T86-T89 erledigt, Cluster E+F+G offen.

---

## 1. Pflichtlektüre (in dieser Reihenfolge)

1. **`master-briefing.md`** — Source of Truth, vollständiger Rotations-Log aller APs
2. **`CLAUDE.md`** — Projektkonventionen, Worker-Regeln, Datenmodell-Tabellen, ActionType-Liste
3. **`Pirol_Changerequest_V0.0.7.md`** — Original-Wunschliste mit Status pro Punkt (✅/⏳/🔄/📅/⬜)
4. Letzte Handovers: `Handover_T87.md`, `T88_Handover.md`, `Handover.md` (=T89)

---

## 2. Was in dieser Master-Session gemacht wurde (Pirolmaster 8)

### Cluster D — Karte V2 (T79–T85, alle ✅)
| Tag | Inhalt |
|-----|--------|
| V0.0.6.T79 | D1: Session-Filter (Alle/Nur Live/Letzte 3), Live-Marker blau, Position-Move via verifications.jsonl |
| V0.0.6.T80 | D2: SpeciesColorRepository (species_colors.json), Kreis-Marker 64px mit Art-Kürzel |
| V0.0.6.T81 | D3a: Greedy O(n²) Clustering (radiusDeg=0.003), +N-Cluster-Icon, AlertDialog |
| V0.0.6.T82 | D3b: EditDetection auf Karte, Lock-Badge, erweiterte Marker-Info |
| V0.0.6.T83 | D4: Long-Press-Dialog (Bearbeiten/Verschieben/Abbrechen), SwisstopoFarbeTileSource, map_manifest.json, Home-FAB |
| V0.0.6.T84 | D5a: Simultanbeobachtung Focus-Modus + Polylinien (grün/rot/blau) + recordSimultaneousLink() |
| V0.0.6.T85 | D5b: Raven-Export Simultan/Moeglich/Gleich (SimultaneousLinkSummary, 3 neue TSV-Spalten) |

### Backlog + Feldtest-Bugfixes (T86–T89, alle ✅)
| Tag | Inhalt |
|-----|--------|
| V0.0.6.T86 | Memo-Button Platzhalter-Dialog, WavWriter Int-Overflow-Cap, removeLastVerification tmp+rename |
| V0.0.6.T87 | Swisstopo X/Y getauscht ({z}/{y}/{x} statt {z}/{x}/{y}), osmdroid Cache-Config |
| V0.0.6.T88 | Long-press: GestureDetectorCompat via setOnTouchListener (Compose PointerInteropFilter-Problem), Tap-Target 120px |
| V0.0.6.T89 | osmdroid-Init in PirolApp.onCreate() (echte Root-Cause Tile-Download), Position-Move Center-Pin UX |

---

## 3. Aktueller Code-Stand (master HEAD, Commit a99d8d0)

### Schlüsseldateien Karte

| Datei | Inhalt |
|-------|--------|
| `ui/map/MapScreen.kt` | Komplettes Karten-Composable. GestureDetectorCompat für Long-press (T88). Center-Pin Move-Modus (T89). inMoveMode-Guard für alle UI-Elemente. |
| `ui/map/MapViewModel.kt` | FilterMode, LinkType, MapMarker, SimultaneousLink, MarkerCluster, MapUiState (15 Felder), alle VM-Methoden |
| `ui/map/SwisstopoTileSource.kt` | OnlineTileSourceBase, URL {z}/{y}/{x}.jpeg (nach T87-Fix) |
| `ui/map/SwisstopoFarbeTileSource` | object, minZoom=13, maxZoom=18 |
| `app/src/main/assets/map_manifest.json` | OSM 1-12, Swisstopo 13-18 (als Konfig-Doku, tileSourceForZoom() ist hartcodiert) |
| `PirolApp.kt` | osmdroid Configuration.getInstance() VOR startKoin() (T89) |

### MapUiState-Felder (aktuell)
```kotlin
markers: List<MapMarker>          // historische Detektionen
liveMarkers: List<MapMarker>      // Live-Feed (snapshotFlow)
clusters: List<MarkerCluster>     // computed via computeClusters()
selectedCluster: MarkerCluster?   // Cluster-Dialog
centerLat/centerLon: Double       // Startzentrum Schweiz (47.38, 8.54)
isLoading: Boolean
filterMode: FilterMode            // ALL / LIVE_ONLY / RECENT_3
longPressTarget: MapMarker?       // Long-Press-Menü
positionMoveTarget: MapMarker?    // Center-Pin Move-Modus
editTarget/editDetection          // Edit-Dialog
lockedSpecies: Set<String>
focusMode: Boolean                // Simultanbeobachtungs-Modus
focusSpecies: String?
linkAnchor/linkTarget: MapMarker? // Simultanlink-Auswahl
simultaneousLinks: List<SimultaneousLink>
```

### Long-press-Implementierung (wichtig für Nachfolger)
**NICHT** osmdroid-Overlay! `GestureDetectorCompat` via `setOnTouchListener` auf dem `MapView` selbst (T88). Compose's `PointerInteropFilter` sendet `ACTION_CANCEL` an eingebettete Views bei Scroll-Gesten — das bricht osmdroid-Overlay-onLongPress ab. `setOnTouchListener` empfängt vor Compose.

```kotlin
mapView.setOnTouchListener { _, event ->
    gestureDetector.onTouchEvent(event)
    false  // false = MapView erhält Event weiterhin
}
```

### osmdroid-Konfiguration (wichtig)
In `PirolApp.onCreate()`, VOR `startKoin`. Nie im Composable — der `MapView(ctx)`-Konstruktor initialisiert den `MapTileProvider` sofort.

---

## 4. Offene Feldtests (User muss verifizieren)

| AC | Was | Stand |
|----|-----|-------|
| T88 AC1 | Long-press öffnet Menü zuverlässig | ⏳ offen |
| T89 AC1 | Swisstopo-Kacheln laden bei Zoom ≥ 13 | ⏳ offen |
| T89 AC2 | OSM-Kacheln laden normal | ⏳ offen |
| T89 AC3–6 | Center-Pin Move-Modus komplett (Banner, Drag, Save, Cancel) | ⏳ offen |

Falls T89 die Tile-Probleme nicht löst: Logcat auf `osmdroid` filtern. Häufige Ursachen: falscher Cache-Pfad, Permission-Fehler, User-Agent leer.

---

## 5. Was als nächstes kommt

### Cluster E — Player + Chunk-Workflow (alle 📅)
- Playbar mit Drag-Positionierung + einstellbarem Preroll-Event
- Chunk abhören mit Kopfhörer während Aufnahme (ohne Unterbrechung)
- Vergleichsmodus + Analysemodus Referenzaufnahmen
- Memo-Export als m4a aus AnalysisPlayer

**Hinweis:** Cluster E ist die komplexeste Baustelle. Empfehlung: Disk-Pipeline-Spike vorab (separates Konzept-AP), dann E in 3–4 Unter-APs.

### Cluster F — Listen / Default-JSON (alle 📅)
- Listen-Editor + JSON-Import aus `/pirol/`-Ordner (Downloads/PIROL/)
- Default-JSON (species_colors.json, watchedSpecies.json) im Nutzerordner

### Cluster G — Settings-UX (alle 📅)
- Kapitel in Einstellungen ausklappbar
- Einzelne Funktionen abwählbar (UI-Komplexität reduzieren)
- Touchsperre + Bildschirmdrehsperre

### Bugreport (offen)
- 📅 BirdNet/Modell-Manifest via JSON (eigener AP, T78-Nummer vergeben für Raven-UX)

---

## 6. Pirolmaster-Workflow (unverändert vom Vorgänger)

- **Worker-Spawning:** User startet eigene Claude-Code-Sessions als Worker. **Nicht** via `Agent`-Tool.
- **Worktree-Disziplin (HART):** jeder Worker startet in `git worktree add ../<branch> master -b <branch>`. **Nicht auf bestehenden Branches stapeln.** In dieser Session wurde die Disziplin bei T82, T83, T84, T86, T87, T88, T89 gebrochen (alle uncommitted oder direkt auf master). Master musste jeweils selbst committen. Im AP explizit "Kein Self-Merge" + Branch-Name vorschreiben.
- **Quality-Gates:** (1) AP vor Dispatch freigegeben, (2) Handover vor Master-Schluss freigegeben.
- **Token-Budget:** 70k pro Worker-AP. Bei Überschreitung aufteilen.
- **Merge-Regel:** Worker committed in Branch, kein self-merge. Master merged nach Review.
- **Tag-Schema:** `V0.0.6.T{N}` — Tag wird nach Master-Merge gesetzt.

---

## 7. Bekannte technische Entscheidungen (nicht neu erfragen)

| Entscheidung | Details |
|---|---|
| osmdroid Clustering | Kein RadiusMarkerClusterer (bonus-pack nicht im Classpath). Eigenes O(n²) greedy mit radiusDeg=0.003. |
| Swisstopo | BGDI WMTS via wmts.geo.admin.ch, EPSG:3857, URL {z}/{y}/{x}.jpeg, Zoom 13-18 |
| Map-Manifest | JSON-Asset (Option A), hartcodiert in tileSourceForZoom() — Manifest ist Konfig-Doku |
| Simultanbeobachtung | Long-Press-Menü 4. Option (nicht 2s-Gesture), verifications.jsonl bidirektional (2 Events pro Link) |
| VerificationEvent.newValue | Trägt Gegenstück-ID bei Simultanlinks |
| RavenExporter | 6 Parameter, 6. linkMap mit Default emptyMap() (backward-compatible) |
| DetectionResult | count, atlasCode, note, voiceMemoFile, manualEntry, lockSpecies, firstSeenAtMs, lastSeenAtMs |
| Atlascode-Set | CH initial, AC1–AC99, atlas_codes.json in assets |
| Sprach-Memo | WAV 16kHz Mono 16-bit, <sessionDir>/memos/<id>.wav |
| GestureDetectorCompat | Deprecated, funktioniert korrekt. Cleanup separater Task. |

---

## 8. Tag-Liste ab T76 (diese Master-Session und Vorgänger)

```
V0.0.6.T76  Lifecycle+Preroll+LongPress-Fix
V0.0.6.T77  Button-Layout+Player-Symbol
V0.0.6.T78  Raven-UX+Felder-Toggle
V0.0.6.T79  Karte D1: Filter+Live+Position-Move
V0.0.6.T80  Karte D2: SpeciesColor+Kreis-Marker
V0.0.6.T81  Karte D3a: Clustering+Dialog
V0.0.6.T82  Karte D3b: EditDetection+Lock-Badge
V0.0.6.T83  Karte D4: LongPress-Dialog+Swisstopo+HomeFAB
V0.0.6.T84  Karte D5a: Simultanbeobachtung Focus+Linien
V0.0.6.T85  Karte D5b: Raven-Export Simultanfelder
V0.0.6.T86  Backlog: Memo-Button+WavWriter+tmp+rename
V0.0.6.T87  Bugfix: Swisstopo X/Y+osmdroid-Cache
V0.0.6.T88  Bugfix: GestureDetectorCompat+Config-Timing
V0.0.6.T89  Bugfix: PirolApp osmdroid-Init+Center-Pin-Move
```

---

## 9. Tooling

- **Build + Deploy:** `C:\Users\nleis\Documents\WindowsPowerShell\pirol-deploy-worktree.ps1 -Branch <branch>`
- **Push:** nur auf User-Anweisung. Aktuell weit ahead von origin/master.
- **Worktrees:** `D:\80002\PIROL\.claude-worktrees\<branch>` — alte können aufgeräumt werden
- **Handover-Files:** `Handover_T{N}.md` oder `T{N}_Handover.md` oder `Handover.md` (Worker inkonsistent beim Naming)

---

Ende Uebergabe. Naechster Master liest zuerst `master-briefing.md`, dann dieses Dokument.
