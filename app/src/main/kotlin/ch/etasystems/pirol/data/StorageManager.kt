package ch.etasystems.pirol.data

import android.content.Context
import java.io.File

/**
 * Ermittelt verfuegbare Speicherorte (intern + SD-Karte).
 * Nutzt getExternalFilesDirs() — kein MANAGE_EXTERNAL_STORAGE noetig.
 */
class StorageManager(private val context: Context) {

    /** Verfuegbare Speicherorte ermitteln */
    fun getAvailableStorageLocations(): List<StorageLocation> {
        val locations = mutableListOf<StorageLocation>()

        // Intern (immer verfuegbar)
        locations.add(StorageLocation(
            name = "Interner Speicher",
            path = context.filesDir,
            isInternal = true,
            freeSpaceMB = context.filesDir.freeSpace / 1_000_000
        ))

        // Externe Speicher (SD-Karte etc.)
        val externalDirs = context.getExternalFilesDirs(null)
        for (dir in externalDirs) {
            if (dir != null && dir != context.getExternalFilesDir(null)) {
                locations.add(StorageLocation(
                    name = "SD-Karte",
                    path = dir,
                    isInternal = false,
                    freeSpaceMB = dir.freeSpace / 1_000_000
                ))
            }
        }
        return locations
    }
}

data class StorageLocation(
    val name: String,
    val path: File,
    val isInternal: Boolean,
    val freeSpaceMB: Long
)
