package ch.etasystems.pirol.ml

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Unit-Tests fuer den Cluster-Algorithmus (T70).
 *
 * Prueft: Radius-Match, Zeit-Match, fehlender GPS-Fix, Toggle-Aus, Backward-Kompat,
 * Confidence-Max, ClusterResult-Felder, verschiedene Arten bleiben getrennt.
 */
class DetectionClusterTest {

    // ---- Hilfsmethoden -------------------------------------------------------

    private fun makeDetection(
        id: String = UUID.randomUUID().toString(),
        species: String = "Turdus merula",
        confidence: Float = 0.8f,
        timestampMs: Long = System.currentTimeMillis(),
        lat: Double? = null,
        lon: Double? = null
    ) = DetectionResult(
        id = id,
        scientificName = species,
        commonName = "Amsel",
        confidence = confidence,
        timestampMs = timestampMs,
        chunkStartSec = 0f,
        chunkEndSec = 3f,
        sampleRate = 48000,
        latitude = lat,
        longitude = lon
    )

    /** Zuerich HB (47.3769 N, 8.5417 E) als Ankerpunkt */
    private val LAT_BASE = 47.3769
    private val LON_BASE = 8.5417

    /**
     * Berechnet Laengengrad-Offset fuer eine gegebene Distanz in Metern (Zuerich-Breite).
     * Ca. 71 500 m pro Laengengrad auf 47 Grad Breite.
     */
    private fun lonOffset(meters: Double) = meters / 71_500.0

    // ---- Tests ---------------------------------------------------------------

    @Test
    fun `cluster match — 5m distanz, 2min auseinander ergibt 1 Eintrag mit count=2`() {
        val state = DetectionListState()
        val t0 = System.currentTimeMillis() - 2 * 60_000L  // vor 2 Minuten

        val det1 = makeDetection(id = "a", timestampMs = t0, lat = LAT_BASE, lon = LON_BASE)
        val det2 = makeDetection(id = "b", timestampMs = System.currentTimeMillis(),
            lat = LAT_BASE, lon = LON_BASE + lonOffset(5.0))  // ~5m entfernt

        val r1 = state.addOrCluster(det1, clusterEnabled = true, radiusM = 10, timeWindowMin = 5)
        val r2 = state.addOrCluster(det2, clusterEnabled = true, radiusM = 10, timeWindowMin = 5)

        assertTrue("Erste Detection ist New", r1 is ClusterResult.New)
        assertTrue("Zweite Detection ist Merged", r2 is ClusterResult.Merged)
        assertEquals("Nur 1 Eintrag in der Liste", 1, state.size)

        val merged = r2 as ClusterResult.Merged
        assertEquals("detectionCount = 2", 2, merged.anchor.detectionCount)
        assertEquals("lastSeenAtMs = Timestamp der zweiten Detection",
            det2.timestampMs, merged.anchor.lastSeenAtMs)
        assertEquals("absorbedId = id der zweiten Detection", "b", merged.absorbedId)
    }

    @Test
    fun `kein match wegen distanz 50m — 2 eintraege`() {
        val state = DetectionListState()
        val now = System.currentTimeMillis()

        val det1 = makeDetection(id = "a", timestampMs = now - 60_000L,
            lat = LAT_BASE, lon = LON_BASE)
        val det2 = makeDetection(id = "b", timestampMs = now,
            lat = LAT_BASE, lon = LON_BASE + lonOffset(50.0))  // ~50m entfernt

        state.addOrCluster(det1, clusterEnabled = true, radiusM = 10, timeWindowMin = 5)
        val r2 = state.addOrCluster(det2, clusterEnabled = true, radiusM = 10, timeWindowMin = 5)

        assertTrue("Zweite Detection ist New (zu weit weg)", r2 is ClusterResult.New)
        assertEquals("2 Eintraege in der Liste", 2, state.size)
    }

    @Test
    fun `kein match wegen zeitfenster — 10min auseinander ergibt 2 eintraege`() {
        val state = DetectionListState()
        val now = System.currentTimeMillis()

        val det1 = makeDetection(id = "a", timestampMs = now - 10 * 60_000L,
            lat = LAT_BASE, lon = LON_BASE)
        val det2 = makeDetection(id = "b", timestampMs = now,
            lat = LAT_BASE, lon = LON_BASE + lonOffset(5.0))

        state.addOrCluster(det1, clusterEnabled = true, radiusM = 10, timeWindowMin = 5)
        val r2 = state.addOrCluster(det2, clusterEnabled = true, radiusM = 10, timeWindowMin = 5)

        assertTrue("Zweite Detection ist New (zu alt)", r2 is ClusterResult.New)
        assertEquals("2 Eintraege in der Liste", 2, state.size)
    }

    @Test
    fun `kein gps auf einer seite — zeit-match-only pfad ergibt cluster`() {
        val state = DetectionListState()
        val now = System.currentTimeMillis()

        // Erste Detection MIT GPS
        val det1 = makeDetection(id = "a", timestampMs = now - 60_000L,
            lat = LAT_BASE, lon = LON_BASE)
        // Zweite Detection OHNE GPS
        val det2 = makeDetection(id = "b", timestampMs = now, lat = null, lon = null)

        state.addOrCluster(det1, clusterEnabled = true, radiusM = 10, timeWindowMin = 5)
        val r2 = state.addOrCluster(det2, clusterEnabled = true, radiusM = 10, timeWindowMin = 5)

        assertTrue("Merged via Zeit-Match-only (kein GPS)", r2 is ClusterResult.Merged)
        assertEquals("1 Eintrag (Cluster)", 1, state.size)
    }

    @Test
    fun `toggle aus — klassisches t44-verhalten, 2x selbe art = 2 karten moeglich`() {
        val state = DetectionListState()
        // Toggle aus: addDetections-Logik (Art bereits vorhanden → detectionCount++)
        // Bei Toggle aus gibt addOrCluster immer New zurueck, aber in-memory wird deduped.
        val det1 = makeDetection(id = "a", lat = LAT_BASE, lon = LON_BASE)
        val det2 = makeDetection(id = "b", lat = LAT_BASE, lon = LON_BASE + lonOffset(50.0))

        val r1 = state.addOrCluster(det1, clusterEnabled = false, radiusM = 10, timeWindowMin = 5)
        val r2 = state.addOrCluster(det2, clusterEnabled = false, radiusM = 10, timeWindowMin = 5)

        // Toggle aus → immer New zurueck (kein Cluster-Merge)
        assertTrue("r1 ist New", r1 is ClusterResult.New)
        assertTrue("r2 ist New (kein Cluster-Merge bei Toggle aus)", r2 is ClusterResult.New)
        // In-memory: T44 deduplication (gleiche Art → 1 Eintrag mit count++)
        assertEquals("T44-Dedup: 1 Eintrag (selbe Art)", 1, state.size)
        assertEquals("detectionCount = 2 (T44-Zaehler)", 2, state.getDetections().first().detectionCount)
    }

    @Test
    fun `backward kompatibilitaet — altes jsonl ohne firstSeenAtMs parst mit default=timestampMs`() {
        val jsonWithoutNewFields = """
            {
                "id": "test-id-123",
                "scientificName": "Turdus merula",
                "commonName": "Amsel",
                "confidence": 0.9,
                "timestampMs": 1700000000000,
                "chunkStartSec": 0.0,
                "chunkEndSec": 3.0,
                "sampleRate": 48000
            }
        """.trimIndent()

        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val det = json.decodeFromString<DetectionResult>(jsonWithoutNewFields)

        assertEquals("firstSeenAtMs = timestampMs als Default", det.timestampMs, det.firstSeenAtMs)
        assertEquals("lastSeenAtMs = timestampMs als Default", det.timestampMs, det.lastSeenAtMs)
        assertEquals("timestampMs korrekt deserialisiert", 1700000000000L, det.timestampMs)
    }

    @Test
    fun `confidence max — 0_6 dann 0_85 ergibt 0_85 behalten`() {
        val state = DetectionListState()
        val now = System.currentTimeMillis()

        val det1 = makeDetection(id = "a", confidence = 0.6f, timestampMs = now - 60_000L,
            lat = LAT_BASE, lon = LON_BASE)
        val det2 = makeDetection(id = "b", confidence = 0.85f, timestampMs = now,
            lat = LAT_BASE, lon = LON_BASE + lonOffset(5.0))

        state.addOrCluster(det1, clusterEnabled = true, radiusM = 10, timeWindowMin = 5)
        val r2 = state.addOrCluster(det2, clusterEnabled = true, radiusM = 10, timeWindowMin = 5)

        assertTrue("Merged", r2 is ClusterResult.Merged)
        val anchor = (r2 as ClusterResult.Merged).anchor
        assertEquals("Confidence = Max(0.6, 0.85) = 0.85", 0.85f, anchor.confidence, 0.001f)
    }

    @Test
    fun `cluster merged felder korrekt — absorbedId und lastSeenAtMs`() {
        val state = DetectionListState()
        val t0 = System.currentTimeMillis() - 90_000L  // vor 1.5 min
        val t1 = System.currentTimeMillis()

        val det1 = makeDetection(id = "anchor-001", timestampMs = t0,
            lat = LAT_BASE, lon = LON_BASE)
        val det2 = makeDetection(id = "absorbed-002", timestampMs = t1,
            lat = LAT_BASE, lon = LON_BASE + lonOffset(3.0))

        state.addOrCluster(det1, clusterEnabled = true, radiusM = 10, timeWindowMin = 5)
        val result = state.addOrCluster(det2, clusterEnabled = true, radiusM = 10, timeWindowMin = 5)

        assertTrue("Merged", result is ClusterResult.Merged)
        val merged = result as ClusterResult.Merged

        assertEquals("absorbedId = id der absorbierten Detection", "absorbed-002", merged.absorbedId)
        assertEquals("anchor.id bleibt unveraendert (anchor-001)", "anchor-001", merged.anchor.id)
        assertEquals("lastSeenAtMs = Timestamp der absorbierten Detection",
            t1, merged.anchor.lastSeenAtMs)
        assertEquals("firstSeenAtMs unveraendert = t0", t0, merged.anchor.firstSeenAtMs)
    }

    @Test
    fun `verschiedene arten bleiben immer getrennt — kein cross-species merge`() {
        val state = DetectionListState()
        val now = System.currentTimeMillis()

        val det1 = makeDetection(id = "a", species = "Turdus merula",
            timestampMs = now - 60_000L, lat = LAT_BASE, lon = LON_BASE)
        val det2 = makeDetection(id = "b", species = "Parus major",
            timestampMs = now, lat = LAT_BASE, lon = LON_BASE + lonOffset(2.0))

        val r1 = state.addOrCluster(det1, clusterEnabled = true, radiusM = 10, timeWindowMin = 5)
        val r2 = state.addOrCluster(det2, clusterEnabled = true, radiusM = 10, timeWindowMin = 5)

        assertTrue("Art 1 ist New", r1 is ClusterResult.New)
        assertTrue("Art 2 ist New (andere Art = kein Merge)", r2 is ClusterResult.New)
        assertEquals("2 Eintraege (verschiedene Arten)", 2, state.size)
    }
}
