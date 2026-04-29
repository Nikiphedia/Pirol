# Handover T75 — "Aehnliche Arten" Refactor

**Branch:** T75-similar-species  
**Dispatch:** 2026-04-27  
**Build-Tag:** V0.0.6.T75

---

## Audit-Befund (Phase 1 — vor dem Fix)

### Was zeigt SimilarityPanel aktuell?

- **Format:** Vertikale LazyColumn mit bis zu 5 Karten (hardcoded `take(5)`)
- **Inhalt je Karte:** wissenschaftlicher Artname (`match.species`, z.B. "Turdus merula"), Prozentwert, horizontaler Balken, Recording-ID
- **Header:** "Aehnliche Aufnahmen" (nicht "Aehnliche Arten")
- **Namen:** ausschliesslich wissenschaftlich — kein SpeciesNameResolver involviert
- **Sichtbarkeit:** immer sichtbar wenn `isEmbeddingAvailable=true` (= Modell geladen), unabhaengig vom Session-Zustand
- **Keine Einstellungen:** null Preference-Keys fuer diese Funktion

### Wann / wo wird sie angezeigt?

Alle drei Layout-Varianten (Compact/Medium/Expanded) in `LiveScreen.kt` zeigen das Panel, wenn:
```kotlin
uiState.similarMatches.isNotEmpty() || uiState.isEmbeddingAvailable
```
Das bedeutet: sobald das BirdNET-Modell geladen ist, ist das Panel permanent sichtbar — auch ohne jede Detektion. Ausgeloest wird `findSimilar()` nach jeder neuen Detektion mit Confidence >= Threshold (Callback in LiveViewModel ~Zeile 299–316).

### Woher kommen Mehrfach-Nennungen?

**Ursache:** `EmbeddingDatabase` speichert Eintraege per `recordingId` als Key (nicht per Art). Jede Detektion erzeugt einen eigenen Eintrag:
```
rec_001 → "Turdus merula", embedding_A
rec_002 → "Turdus merula", embedding_B   ← gleiche Art, andere recordingId
rec_003 → "Turdus merula", embedding_C
```
`EmbeddingDatabase.search()` gibt alle Eintraege innerhalb von `topN` ungefiltert zurueck — kein Dedup. Ergebnis: "Turdus merula" erscheint 3× in der Liste, jeweils mit leicht unterschiedlichem Score.

**Sinn-Klage des Users:** Das Panel nennt sich "Aehnliche Aufnahmen" und zeigt Embedding-Nachbarn aus der eigenen Session — kein Vergleich mit einer Referenzdatenbank. Die Mehrfach-Nennungen machen die Funktion unlesbar.

### Zweck-Vermutung (korrekt)

`findSimilar()` sucht in der laufenden Session-DB nach Aufnahmen, die dem aktuellen Embedding aehnlich sind. Da die gleiche Art pro Session viele Detektionen produziert (jede 3-Sekunden-Chunk), hat sie viele Embeddings → dominiert Top-5. Panel zeigt also faktisch "welche Art wurde in dieser Session am aehnlichsten detektiert" — nuetzlich als Aehnlichkeits-Spiegel, aber mit Dedup und DE-Namen erst verstaendlich.

---

## Implementierte Aenderungen (T75-Fix)

| Datei | Aenderung |
|-------|-----------|
| `data/AppPreferences.kt` | +2 Props: `similarEnabled` (Bool, default true), `similarLines` (Float 0–10, default 3.0) |
| `ui/live/LiveUiState.kt` | +3 Felder: `similarEnabled`, `similarLines`, `recentSpecies` |
| `ui/live/LiveViewModel.kt` | Init der neuen State-Felder; `findSimilar()` mit Dedup + similarLines-Logik; `recentSpecies`-Update nach Detektionen |
| `ui/components/SimilarityPanel.kt` | Neue Signatur; DE-Namen via `koinInject<SpeciesNameResolver>()`; Marquee-Mode (0.5); Listen-Mode (1+); Header "Aehnliche Arten" |
| `ui/live/LiveScreen.kt` | 3× neue Params an SimilarityPanel |
| `ui/settings/SettingsScreen.kt` | Neue Sektion "Aehnliche Arten": Toggle + Slider 0–10 (Schritt 0.5) |
| `app/src/test/.../ml/SimilarityDedupTest.kt` | 3 JUnit-Tests: Dedup, Lines-Mode, Language |

## Acceptance Criteria — Status

| AC | Beschreibung | Status |
|----|-------------|--------|
| AC1 | Audit-Befund in Handover_T75.md | ✅ |
| AC2 | Toggle aus → Panel unsichtbar | ✅ |
| AC3 | Toggle an + Slider 0 → Panel unsichtbar | ✅ |
| AC4 | Slider 0.5 → Laufschrift letzte 5 Detektionen | ✅ |
| AC5 | Slider 1–10 → Liste mit n Eintraegen | ✅ |
| AC6 | Dedup pro Art | ✅ |
| AC7 | Anzeige DE-Namen | ✅ |
| AC8 | Mind. 3 Tests gruen | ✅ |
| AC9 | Build gruen | ✅ |
