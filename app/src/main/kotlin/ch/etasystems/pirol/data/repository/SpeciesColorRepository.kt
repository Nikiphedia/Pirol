package ch.etasystems.pirol.data.repository

import android.content.Context
import java.io.File

class SpeciesColorRepository(private val context: Context) {

    // JSON: { "Amsel": "#FF5722", "Buchfink": "#4CAF50" }
    private val colorMap: Map<String, Int> by lazy { loadOrEmpty() }

    private fun loadOrEmpty(): Map<String, Int> {
        val file = File(context.filesDir, "species_colors.json")
        if (!file.exists()) return emptyMap()
        return runCatching {
            val json = file.readText()
            val map = mutableMapOf<String, Int>()
            val regex = Regex(""""([^"]+)"\s*:\s*"(#[0-9A-Fa-f]{6})"""")
            for (match in regex.findAll(json)) {
                val species = match.groupValues[1]
                val hex = match.groupValues[2]
                runCatching { map[species] = android.graphics.Color.parseColor(hex) }
            }
            map
        }.getOrDefault(emptyMap())
    }

    /** Liefert die konfigurierte Farbe oder einen deterministischen Fallback. */
    fun getColor(species: String): Int {
        colorMap[species]?.let { return it }
        val palette = intArrayOf(
            0xFFE53935.toInt(),
            0xFF8E24AA.toInt(),
            0xFF1E88E5.toInt(),
            0xFF00897B.toInt(),
            0xFF43A047.toInt(),
            0xFFF4511E.toInt(),
            0xFF6D4C41.toInt(),
            0xFF039BE5.toInt(),
            0xFFD81B60.toInt(),
            0xFF3949AB.toInt(),
            0xFF00ACC1.toInt(),
            0xFF7CB342.toInt(),
        )
        return palette[Math.abs(species.hashCode()) % palette.size]
    }
}
