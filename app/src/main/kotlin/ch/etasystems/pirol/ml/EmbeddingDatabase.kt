package ch.etasystems.pirol.ml

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
        private const val HEADER_BYTES = 16L
        /** T196: Obergrenzen beim Laden — groessere Werte bedeuten eine kaputte Datei */
        private const val MAX_DIM = 65_536
        private const val MAX_STRING_BYTES = 4_096
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

    // T61: add()/search()/save() laufen im LiveViewModel aus parallelen Coroutines
    // (Dispatchers.Default) — LinkedHashMap ist nicht thread-safe. Alle Zugriffe ueber diesen Lock;
    // Rechen- und I/O-Arbeit passiert auf Snapshots ausserhalb des Locks.
    private val lock = Any()

    /** Anzahl Eintraege in der Datenbank */
    val size: Int get() = synchronized(lock) { entries.size }

    /**
     * Fuegt ein Embedding hinzu oder ersetzt ein bestehendes mit gleicher recordingId.
     *
     * @param recordingId Eindeutige ID der Aufnahme
     * @param species Artname (wissenschaftlich oder common)
     * @param embedding L2-normalisierter Embedding-Vektor
     */
    fun add(recordingId: String, species: String, embedding: FloatArray) {
        val entry = EmbeddingEntry(recordingId, species, embedding.copyOf())
        synchronized(lock) {
            // T196: eine DB enthaelt nur Vektoren einer Laenge (BSED-Header kennt nur eine Dimension)
            val dim = entries.values.firstOrNull()?.embedding?.size
            if (dim != null && dim != embedding.size) {
                Log.w(TAG, "Embedding verworfen: Laenge ${embedding.size}, DB hat $dim ($recordingId)")
                return
            }
            entries[recordingId] = entry
            trimLocked()
        }
    }

    /**
     * T97: Obergrenze der Eintraege. Wird sie ueberschritten, fallen die aeltesten heraus
     * (LinkedHashMap haelt die Einfuege-Reihenfolge). Beim Setzen wird sofort gekuerzt.
     */
    var maxEntries: Int = Int.MAX_VALUE
        set(value) {
            synchronized(lock) {
                field = value.coerceAtLeast(1)
                trimLocked()
            }
        }

    /** Muss unter [lock] aufgerufen werden. */
    private fun trimLocked() {
        val excess = entries.size - maxEntries
        if (excess <= 0) return
        val oldest = entries.entries.iterator()
        repeat(excess) { oldest.next(); oldest.remove() }
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
        val candidates = synchronized(lock) {
            if (speciesFilter != null) {
                entries.values.filter { it.species in speciesFilter }
            } else {
                entries.values.toList()
            }
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
        val allEntries = synchronized(lock) { entries.values.toList() }
        if (allEntries.isEmpty()) {
            Log.w(TAG, "Leere Datenbank — ueberspringe Speichern")
            return
        }

        // Embedding-Dimension aus erstem Eintrag; T196: nur Eintraege dieser Laenge schreiben
        val dim = allEntries.first().embedding.size
        val consistent = allEntries.filter { it.embedding.size == dim }
        if (consistent.size < allEntries.size) {
            Log.w(TAG, "${allEntries.size - consistent.size} Eintraege mit abweichender Laenge nicht gespeichert")
        }

        // T196: erst in <name>.tmp schreiben, dann ersetzen — ein Kill hinterlaesst keine halbe Datei
        val tmp = File(file.parentFile, file.name + ".tmp")
        // Gepuffert: ohne BufferedOutputStream erzeugt jeder kleine write() einen Syscall
        FileOutputStream(tmp).use { fos ->
            DataOutputStream(BufferedOutputStream(fos)).use { dos ->
                // Header schreiben
                writeMagic(dos)
                writeIntLE(dos, VERSION)
                writeIntLE(dos, dim)
                writeIntLE(dos, consistent.size)

                // Eintraege schreiben
                for (entry in consistent) {
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
                    buf.asFloatBuffer().put(entry.embedding)
                    dos.write(buf.array())
                }
            }
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)

        Log.i(TAG, "Datenbank gespeichert: ${consistent.size} Eintraege, dim=$dim → ${file.name}")
    }

    /**
     * Laedt die Datenbank aus einer BSED-Binary-Datei.
     * Bestehende Eintraege werden NICHT geloescht — neue werden hinzugefuegt/ersetzt.
     *
     * @param file Quelldatei im BSED-Format
     * @throws IllegalArgumentException bei ungueltigem Format (T196: auch unsinnige Laengenfelder,
     *   Eintragszahl passt nicht zur Dateigroesse) — nie OutOfMemoryError
     * @throws java.io.EOFException bei abgeschnittener Datei
     */
    fun load(file: File) {
        if (!file.exists()) {
            Log.w(TAG, "Datei nicht gefunden: ${file.absolutePath}")
            return
        }

        val fileSize = file.length()
        FileInputStream(file).use { fis ->
            DataInputStream(BufferedInputStream(fis)).use { dis ->
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
                require(dim in 1..MAX_DIM) { "Ungueltige Embedding-Dimension: $dim" }

                val numEntries = readIntLE(dis)
                require(numEntries >= 0) { "Ungueltige Eintragsanzahl: $numEntries" }

                // T196: jeder Eintrag braucht mindestens 2 Laengenfelder + Vektor
                var remaining = fileSize - HEADER_BYTES
                val minEntryBytes = 8L + dim * 4L
                require(numEntries.toLong() * minEntryBytes <= remaining) {
                    "Eintragsanzahl $numEntries (dim=$dim) passt nicht zur Dateigroesse $fileSize"
                }

                // T196: Laengenfeld pruefen, bevor ein Array angelegt wird
                fun readLen(what: String): Int {
                    val len = readIntLE(dis)
                    remaining -= 4
                    require(len in 0..MAX_STRING_BYTES && len <= remaining) {
                        "Ungueltige Laenge $what: $len (Rest $remaining Bytes)"
                    }
                    remaining -= len
                    return len
                }

                // Eintraege lesen (erst lokal sammeln, dann unter Lock uebernehmen)
                val loaded = LinkedHashMap<String, EmbeddingEntry>(numEntries)
                for (i in 0 until numEntries) {
                    // Recording ID
                    val idLen = readLen("recordingId")
                    val idBytes = ByteArray(idLen)
                    dis.readFully(idBytes)
                    val recordingId = String(idBytes, Charsets.UTF_8)

                    // Species
                    val speciesLen = readLen("species")
                    val speciesBytes = ByteArray(speciesLen)
                    dis.readFully(speciesBytes)
                    val species = String(speciesBytes, Charsets.UTF_8)

                    // Embedding-Vektor
                    require(dim * 4L <= remaining) { "Vektor $i abgeschnitten (Rest $remaining Bytes)" }
                    remaining -= dim * 4L
                    val embBytes = ByteArray(dim * 4)
                    dis.readFully(embBytes)
                    val buf = ByteBuffer.wrap(embBytes)
                    buf.order(ByteOrder.LITTLE_ENDIAN)
                    val embedding = FloatArray(dim)
                    buf.asFloatBuffer().get(embedding)

                    loaded[recordingId] = EmbeddingEntry(recordingId, species, embedding)
                }
                require(remaining == 0L) { "$remaining Bytes nach dem letzten Eintrag — Datei inkonsistent" }
                synchronized(lock) {
                    // T196: nie Vektoren zweier Laengen mischen
                    val existingDim = entries.values.firstOrNull()?.embedding?.size
                    if (existingDim != null && existingDim != dim) {
                        Log.w(TAG, "Datei ${file.name} (dim=$dim) nicht uebernommen, DB hat dim=$existingDim")
                        return
                    }
                    entries.putAll(loaded)
                    trimLocked()  // T97
                }

                Log.i(TAG, "Datenbank geladen: $numEntries Eintraege, dim=$dim ← ${file.name}")
            }
        }
    }

    /** Alle Eintraege loeschen */
    fun clear() {
        synchronized(lock) { entries.clear() }
        Log.i(TAG, "Datenbank geleert")
    }

    /** Gibt alle in der Datenbank vorhandenen Artnamen zurueck */
    fun getSpecies(): Set<String> {
        return synchronized(lock) { entries.values.map { it.species }.toSet() }
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
