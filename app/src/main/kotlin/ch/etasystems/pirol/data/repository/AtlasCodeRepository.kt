package ch.etasystems.pirol.data.repository

import android.content.Context
import ch.etasystems.pirol.ml.AtlasCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class AtlasCodeRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private val cache: List<AtlasCode> by lazy { load() }

    fun loadCodes(): List<AtlasCode> = cache

    private fun load(): List<AtlasCode> = try {
        val raw = context.assets.open("atlas_codes.json").bufferedReader().readText()
        json.decodeFromString<AtlasCodeFile>(raw).codes
    } catch (_: Exception) {
        emptyList()
    }

    @Serializable
    private data class AtlasCodeFile(
        val version: Int = 1,
        val set: String = "CH",
        val codes: List<AtlasCode> = emptyList()
    )
}
