package ch.etasystems.pirol.ml

import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Lokale Embedding-Datenbank mit AMSEL-kompatiblem Binary-Format "BSED".
 * Brute-Force Cosine Similarity auf L2-normalisierten Vektoren (= Dot Product).
 *
 * Speicherformat:
 * Header (16 Bytes):
 *   [4] Magic: "BSED"
 *   [4] Version: 1 (int32 LE)
 *   [4] Embedding Dimension (int32 LE)
 *   [4] Number of Entries (int32 LE)
 * Per Entry:
 *   [4] Recording ID length (int32 LE)
 *   [N] Recording ID (UTF-8)
 *   [4] Species length (int32 LE)
 *   [N] Species (UTF-8)
 *   [D*4] Embedding vector (Float32 LE)
 *
 * Pure Kotlin — keine Android-Context-Abhaengigkeit.
 */
class EmbeddingDatabase {

    companion object {
        private const val TAG = "EmbeddingDB"
        private const val MAGIC = "BSED"
        private const val VERSION = 1
    }

    /** Einzelner Eintrag in der Datenbank */
    data class EmbeddingEntry(
        val recordingId: String,
        val species: String,
        val embedding: FloatArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is EmbeddingEntry) return false
            return recordingId == other.recordingId &&
                    species == other.species &&
                    embedding.contentEquals(other.embedding)
        }

        override fun hashCode(): Int {
            var result = recordingId.hashCode()
            result = 31 * result + species.hashCode()
            result = 31 * result + embedding.contentHashCode()
            return result
        }
    }

    /** Suchergebnis mit Aehnlichkeits-Score */
    data class EmbeddingMatch(
        val recordingId: String,
        val species: String,
        val similarity: Float, // 0..1 (normalisierte Cosine Similarity)
        val rank: Int
    )

    // Interner Speicher: recordingId → Entry (schnelles Ersetzen)
    private val entries = LinkedHashMap<String, EmbeddingEntry>()

    /** Anzahl Eintraege in der Datenbank */
    val size: Int get() = entries.size

    /**
     * Fuegt ein Embedding hinzu oder ersetzt ein bestehendes mit gleicher recordingId.
     *
     * @param recordingId Eindeutige ID der Aufnahme
     * @param species Artname (wissenschaftlich oder common)
     * @param embedding L2-normalisierter Embedding-Vektor
     */
    fun add(recordingId: String, species: String, embedding: FloatArray) {
        entries[recordingId] = EmbeddingEntry(recordingId, species, embedding.copyOf())
    }

    /**
     * Sucht die aehnlichsten Embeddings per Cosine Similarity.
     * Brute-Force Dot Product auf L2-normalisierten Vektoren.
     *
     * @param query L2-normalisierter Query-Vektor
     * @param topN Maximale Anzahl Ergebnisse
     * @param speciesFilter Optional: Nur Eintraege dieser Arten beruecksichtigen
     * @return Sortierte Liste der besten Treffer (hoechste Similarity zuerst)
     */
    fun search(
        query: FloatArray,
        topN: Int = 5,
        speciesFilter: Set<String>? = null
    ): List<EmbeddingMatch> {
        if (entries.isEmpty()) return emptyList()

        val candidates = if (speciesFilter != null) {
            entries.values.filter { it.species in speciesFilter }
        } else {
            entries.values.toList()
        }

        if (candidates.isEmpty()) return emptyList()

        // Brute-Force: Cosine Similarity berechnen fuer alle Kandidaten
        val scored = candidates.map { entry ->
            val sim = dotProduct(query, entry.embedding)
            // Cosine-Normalisierung: Dot Product auf L2-normalisierten Vektoren ist [-1, 1]
            // Umrechnung auf [0, 1]: (sim + 1) / 2
            val normalizedSim = ((sim + 1f) / 2f).coerceIn(0f, 1f)
            entry to normalizedSim
        }

        return scored
            .sortedByDescending { it.second }
            .take(topN)
            .mapIndexed { index, (entry, sim) ->
                EmbeddingMatch(
                    recordingId = entry.recordingId,
                    species = entry.species,
                    similarity = sim,
                    rank = index + 1
                )
            }
    }

    /**
     * Speichert die Datenbank im BSED-Binary-Format.
     *
     * @param file Zieldatei (wird ueberschrieben falls vorhanden)
     */
    fun save(file: File) {
        val allEntries = entries.values.toList()
        if (allEntries.isEmpty()) {
            Log.w(TAG, "Leere Datenbank — ueberspringe Speichern")
            return
        }

        // Embedding-Dimension aus erstem Eintrag
        val dim = allEntries.first().embedding.size

        FileOutputStream(file).use { fos ->
            DataOutputStream(fos).use { dos ->
                // Header schreiben
                writeMagic(dos)
                writeIntLE(dos, VERSION)
                writeIntLE(dos, dim)
                writeIntLE(dos, allEntries.size)

                // Eintraege schreiben
                for (entry in allEntries) {
                    // Recording ID
                    val idBytes = entry.recordingId.toByteArray(Charsets.UTF_8)
                    writeIntLE(dos, idBytes.size)
                    dos.write(idBytes)

                    // Species
                    val speciesBytes = entry.species.toByteArray(Charsets.UTF_8)
                    writeIntLE(dos, speciesBytes.size)
                    dos.write(speciesBytes)

                    // Embedding-Vektor (Float32 LE)
                    val buf = ByteBuffer.allocate(entry.embedding.size * 4)
                    buf.order(ByteOrder.LITTLE_ENDIAN)
                    for (v in entry.embedding) {
                        buf.putFloat(v)
                    }
                    dos.write(buf.array())
                }
            }
        }

        Log.i(TAG, "Datenbank gespeichert: ${allEntries.size} Eintraege, dim=$dim → ${file.name}")
    }

    /**
     * Laedt die Datenbank aus einer BSED-Binary-Datei.
     * Bestehende Eintraege werden NICHT geloescht — neue werden hinzugefuegt/ersetzt.
     *
     * @param file Quelldatei im BSED-Format
     * @throws IllegalArgumentException bei ungueltigem Format
     */
    fun load(file: File) {
        if (!file.exists()) {
            Log.w(TAG, "Datei nicht gefunden: ${file.absolutePath}")
            return
        }

        FileInputStream(file).use { fis ->
            DataInputStream(fis).use { dis ->
                // Header lesen und validieren
                val magic = readMagic(dis)
                require(magic == MAGIC) {
                    "Ungueltiges Format: Magic='$magic', erwartet='$MAGIC'"
                }

                val version = readIntLE(dis)
                require(version == VERSION) {
                    "Nicht unterstuetzte Version: $version, erwartet=$VERSION"
                }

                val dim = readIntLE(dis)
                require(dim > 0) { "Ungueltige Embedding-Dimension: $dim" }

                val numEntries = readIntLE(dis)
                require(numEntries >= 0) { "Ungueltige Eintragsanzahl: $numEntries" }

                // Eintraege lesen
                for (i in 0 until numEntries) {
                    // Recording ID
                    val idLen = readIntLE(dis)
                    val idBytes = ByteArray(idLen)
                    dis.readFully(idBytes)
                    val recordingId = String(idBytes, Charsets.UTF_8)

                    // Species
                    val speciesLen = readIntLE(dis)
                    val speciesBytes = ByteArray(speciesLen)
                    dis.readFully(speciesBytes)
                    val species = String(speciesBytes, Charsets.UTF_8)

                    // Embedding-Vektor
                    val embBytes = ByteArray(dim * 4)
                    dis.readFully(embBytes)
                    val buf = ByteBuffer.wrap(embBytes)
                    buf.order(ByteOrder.LITTLE_ENDIAN)
                    val embedding = FloatArray(dim) { buf.float }

                    entries[recordingId] = EmbeddingEntry(recordingId, species, embedding)
                }

                Log.i(TAG, "Datenbank geladen: $numEntries Eintraege, dim=$dim ← ${file.name}")
            }
        }
    }

    /** Alle Eintraege loeschen */
    fun clear() {
        entries.clear()
        Log.i(TAG, "Datenbank geleert")
    }

    /** Gibt alle in der Datenbank vorhandenen Artnamen zurueck */
    fun getSpecies(): Set<String> {
        return entries.values.map { it.species }.toSet()
    }

    // --- Binary I/O Hilfsfunktionen (Little Endian) ---

    /** Schreibt den 4-Byte Magic String */
    private fun writeMagic(dos: DataOutputStream) {
        dos.write(MAGIC.toByteArray(Charsets.US_ASCII))
    }

    /** Liest den 4-Byte Magic String */
    private fun readMagic(dis: DataInputStream): String {
        val bytes = ByteArray(4)
        dis.readFully(bytes)
        return String(bytes, Charsets.US_ASCII)
    }

    /** Schreibt einen Int32 in Little Endian */
    private fun writeIntLE(dos: DataOutputStream, value: Int) {
        val buf = ByteBuffer.allocate(4)
        buf.order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(value)
        dos.write(buf.array())
    }

    /** Liest einen Int32 in Little Endian */
    private fun readIntLE(dis: DataInputStream): Int {
        val bytes = ByteArray(4)
        dis.readFully(bytes)
        val buf = ByteBuffer.wrap(bytes)
        buf.order(ByteOrder.LITTLE_ENDIAN)
        return buf.int
    }

    /** Dot Product zweier Vektoren (fuer Cosine Similarity auf L2-normalisierten Vektoren) */
    private fun dotProduct(a: FloatArray, b: FloatArray): Float {
        val len = minOf(a.size, b.size)
        var sum = 0f
        for (i in 0 until len) {
            sum += a[i] * b[i]
        }
        return sum
    }
}
