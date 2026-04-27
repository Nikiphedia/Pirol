package ch.etasystems.pirol.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
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
import ch.etasystems.pirol.ml.DetectionResult

private const val MAX_COUNT = 999_999
private const val MAX_NOTE_LENGTH = 500

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditDetectionDialog(
    detection: DetectionResult,
    atlasCodes: List<AtlasCode>,
    onDismiss: () -> Unit,
    onSave: (count: Int, atlasCode: String?, note: String?) -> Unit
) {
    var count by remember { mutableIntStateOf(detection.count) }
    var selectedCode by remember { mutableStateOf(detection.atlasCode) }
    var note by remember { mutableStateOf(detection.note ?: "") }
    var dropdownExpanded by remember { mutableStateOf(false) }

    val hasChanged = count != detection.count ||
        selectedCode != detection.atlasCode ||
        note.ifEmpty { null } != detection.note

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Detail-Eingaben") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // 1. Stückzahl-Spinner
                Column {
                    Text("Stückzahl", style = MaterialTheme.typography.labelMedium)
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
                            onClick = { if (count < MAX_COUNT) count++ },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "Mehr")
                        }
                    }
                }

                // 2. Atlascode-Dropdown
                Column {
                    Text("Atlascode", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    ExposedDropdownMenuBox(
                        expanded = dropdownExpanded,
                        onExpandedChange = { dropdownExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = selectedCode?.let { code ->
                                atlasCodes.find { it.code == code }
                                    ?.let { "${it.code} — ${it.label_de}" }
                                    ?: code
                            } ?: "(keiner)",
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(dropdownExpanded) },
                            modifier = Modifier
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = dropdownExpanded,
                            onDismissRequest = { dropdownExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("(keiner)") },
                                onClick = {
                                    selectedCode = null
                                    dropdownExpanded = false
                                }
                            )
                            atlasCodes.forEach { atlasCode ->
                                DropdownMenuItem(
                                    text = { Text("${atlasCode.code} — ${atlasCode.label_de}") },
                                    onClick = {
                                        selectedCode = atlasCode.code
                                        dropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                // 3. Bemerkung
                Column {
                    OutlinedTextField(
                        value = note,
                        onValueChange = { if (it.length <= MAX_NOTE_LENGTH) note = it },
                        label = { Text("Bemerkung") },
                        maxLines = 5,
                        supportingText = { Text("${note.length}/$MAX_NOTE_LENGTH") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(count, selectedCode, note.ifEmpty { null })
                },
                enabled = hasChanged
            ) { Text("Speichern") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        }
    )
}
