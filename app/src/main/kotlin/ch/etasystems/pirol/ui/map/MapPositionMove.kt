package ch.etasystems.pirol.ui.map

import ch.etasystems.pirol.ml.VerificationEvent

/**
 * T165: Reine Logik fuer das Verschieben von Detektionspunkten (testbar ohne Android).
 * Format unveraendert (AMSEL-kompatibel): POSITION_MOVED mit newValue = "lat,lon".
 */
object MapPositionMove {

    /** Neue Position aus einem POSITION_MOVED-Event; null bei anderem Typ oder ungueltigem Wert. */
    fun parse(event: VerificationEvent): Pair<Double, Double>? {
        if (event.actionType != VerificationEvent.ACTION_POSITION_MOVED) return null
        val parts = event.newValue?.split(',') ?: return null
        if (parts.size != 2) return null
        val lat = parts[0].trim().toDoubleOrNull() ?: return null
        val lon = parts[1].trim().toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return lat to lon
    }

    /** Marker mit neuer Position in der Liste ersetzen (nur der passende). */
    fun applyTo(
        list: List<MapViewModel.MapMarker>,
        detectionId: String,
        lat: Double,
        lon: Double
    ): List<MapViewModel.MapMarker> =
        list.map { if (it.detectionId == detectionId) it.copy(latitude = lat, longitude = lon) else it }
}
