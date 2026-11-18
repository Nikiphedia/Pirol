package ch.etasystems.pirol.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import ch.etasystems.pirol.ml.ModelProfile

/**
 * T103a: Rueckfrage beim Import einer Modelldatei, deren Profil nicht an der Groesse erkennbar ist.
 * Falsche Wahl fuehrt zu "Score-Ausgang passt nicht" im Log und keinen Detektionen (nicht zu falschen Arten).
 */
@Composable
fun ModelProfileDialog(
    fileSizeMB: Int,
    onConfirm: (ModelProfile) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf(ModelProfile.CATALOG.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Welches Modell ist das?") },
        text = {
            Column(modifier = Modifier.selectableGroup()) {
                Text(
                    "Die Datei ($fileSizeMB MB) ist keinem bekannten Modell zuzuordnen. Bitte waehlen:",
                    style = MaterialTheme.typography.bodySmall
                )
                ModelProfile.CATALOG.forEach { profile ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selected == profile,
                                onClick = { selected = profile },
                                role = Role.RadioButton
                            )
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selected == profile, onClick = null)
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(profile.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${profile.speciesCount} Klassen — ${profile.licenseNote}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selected) }) { Text("Uebernehmen") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}
