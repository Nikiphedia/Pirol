package ch.etasystems.pirol.ml

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Priority-Stufen fuer Watchlist-Arten.
 * - high: Vibration + Sound + persistente Notification
 * - normal: Vibration + Notification (Standard)
 * - low: Nur visuelles Badge auf SpeciesCard, keine Notification
 */
@Serializable
enum class WatchlistPriority {
    @SerialName("high") HIGH,
    @SerialName("normal") NORMAL,
    @SerialName("low") LOW
}

/**
 * Einzelne Art auf der Watchlist.
 * Artennamen im Unterstrich-Format (Luscinia_megarhynchos).
 */
@Serializable
data class WatchlistEntry(
    val scientificName: String,
    val commonName: String = "",
    val priority: WatchlistPriority = WatchlistPriority.NORMAL,
    val notes: String? = null
)

/**
 * Watchlist-Container. Wird als JSON geladen/gespeichert.
 * Quellen: Downloads/PIROL/watchlist.json (extern) oder filesDir/watchlist.json (intern).
 */
@Serializable
data class Watchlist(
    val version: Int = 1,
    val name: String = "Watchlist",
    val updatedAt: String = "",
    val species: List<WatchlistEntry> = emptyList()
)
