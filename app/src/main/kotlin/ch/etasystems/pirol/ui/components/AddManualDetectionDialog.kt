package ch.etasystems.pirol.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.etasystems.pirol.ml.AtlasCode

private const val MAX_MANUAL_COUNT = 999_999
private const val MAX_MANUAL_NOTE_LENGTH = 500

/**
 * Dialog zum manuellen Hinzufuegen einer Art zur Detektions-Liste (T72).
 *
 * - Art-Autocomplete: Tippen → Dropdown mit Vorschlaegen aus SpeciesNameResolver
 * - Stueckzahl-Spinner
 * - Atlascode-Dropdown
 * - Bemerkung-Freitext
 * - GPS-Felder (pre-filled aus aktueller Position, optional editierbar)
 *
 * @param onSearchSpecies Suche nach Arten (Query → List<Pair<scientificName, commonName>>)
 * @param atlasCodes Verfuegbare Atlas-Codes
 * @param currentLat Aktuelle GPS-Breite (pre-fill, nullable)
 * @param currentLon Aktuelle GPS-Laenge (pre-fill, nullable)
 * @param onAdd Callback: (speciesKey, count, atlasCode, note, lat, lon)
 * @param onDismiss Dialog schliessen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddManualDetectionDialog(
    onSearchSpecies: (String) -> List<Pair<String, String>>,
    atlasCodes: List<AtlasCode>,
    currentLat: Double?,
    currentLon: Double?,
    onAdd: (speciesKey: String, count: Int, atlasCode: String?, note: String?, lat: Double?, lon: Double?) -> Unit,
    onDismiss: () -> Unit
) {
    // Art-Auswahl
    var speciesQuery by remember { mutableStateOf("") }
    var selectedSpecies by remember { mutableStateOf<Pair<String, String>?>(null) }  // (scientificName, commonName)
    var speciesSuggestions by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var speciesDropdownExpanded by remember { mutableStateOf(false) }

    // Stueckzahl
    var count by remember { mutableIntStateOf(1) }

    // Atlascode
    var selectedCode by remember { mutableStateOf<String?>(null) }
    var atlasDropdownExpanded by remember { mutableStateOf(false) }

    // Bemerkung
    var note by remember { mutableStateOf("") }

    // GPS
    var latText by remember { mutableStateOf(currentLat?.toString() ?: "") }
    var lonText by remember { mutableStateOf(currentLon?.toString() ?: "") }

    val canSave = selectedSpecies != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Art manuell hinzufuegen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

                // 1. Art-Autocomplete
                Column {
                    Text("Art", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Box {
                        OutlinedTextField(
                            value = if (selectedSpecies != null)
                                "${selectedSpecies!!.second} (${selectedSpecies!!.first})"
                            else speciesQuery,
                            onValueChange = { input ->
                                selectedSpecies = null
                                speciesQuery = input
                                speciesSuggestions = if (input.length >= 2) onSearchSpecies(input) else emptyList()
                                speciesDropdownExpanded = speciesSuggestions.isNotEmpty()
                            },
                            label = { Text("Art suchen (DE oder wissenschaftlich)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        DropdownMenu(
                            expanded = speciesDropdownExpanded,
                            onDismissRequest = { speciesDropdownExpanded = false },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                        ) {
                            speciesSuggestions.forEach { (sciName, commonName) ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                text = commonName,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            Text(
                                                text = sciName,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    onClick = {
                                        selectedSpecies = sciName to commonName
                                        speciesQuery = ""
                                        speciesDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                // 2. Stueckzahl-Spinner
                Column {
                    Text("Stueckzahl", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start
                    ) {
                        IconButton(
                            onClick = { if (count > 1) count-- },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(Icons.Filled.Remove, contentDescription = "Weniger")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = count.toString(),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = { if (count < MAX_MANUAL_COUNT) count++ },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "Mehr")
                        }
                    }
                }

                // 3. Atlascode-Dropdown
                Column {
                    Text("Atlascode", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    ExposedDropdownMenuBox(
                        expanded = atlasDropdownExpanded,
                        onExpandedChange = { atlasDropdownExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = selectedCode?.let { code ->
                                atlasCodes.find { it.code == code }
                                    ?.let { "${it.code} — ${it.label_de}" }
                                    ?: code
                            } ?: "(keiner)",
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(atlasDropdownExpanded) },
                            modifier = Modifier
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = atlasDropdownExpanded,
                            onDismissRequest = { atlasDropdownExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("(keiner)") },
                                onClick = {
                                    selectedCode = null
                                    atlasDropdownExpanded = false
                                }
                            )
                            atlasCodes.forEach { atlasCode ->
                                DropdownMenuItem(
                                    text = { Text("${atlasCode.code} — ${atlasCode.label_de}") },
                                    onClick = {
                                        selectedCode = atlasCode.code
                                        atlasDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                // 4. Bemerkung
                OutlinedTextField(
                    value = note,
                    onValueChange = { if (it.length <= MAX_MANUAL_NOTE_LENGTH) note = it },
                    label = { Text("Bemerkung") },
                    maxLines = 3,
                    supportingText = { Text("${note.length}/$MAX_MANUAL_NOTE_LENGTH") },
                    modifier = Modifier.fillMaxWidth()
                )

                // 5. GPS-Felder (optional)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = latText,
                        onValueChange = { latText = it },
                        label = { Text("Lat") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = lonText,
                        onValueChange = { lonText = it },
                        label = { Text("Lon") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val sp = selectedSpecies ?: return@TextButton
                    val lat = latText.toDoubleOrNull()
                    val lon = lonText.toDoubleOrNull()
                    onAdd(sp.first, count, selectedCode, note.ifEmpty { null }, lat, lon)
                },
                enabled = canSave
            ) { Text("Hinzufuegen") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        }
    )
}
