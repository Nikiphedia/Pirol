package ch.etasystems.pirol.ui.components

import android.widget.Toast
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.etasystems.pirol.R
import ch.etasystems.pirol.location.MunicipalityLookup
import kotlinx.coroutines.launch

/**
 * T197: "Gemeinde einfuegen" fuer die Session-Bemerkung (Live und Analyse).
 * Verhalten wie T195: Gemeinde per [MunicipalityLookup] ermitteln, Text an die Bemerkung
 * anhaengen (mit Leerzeichen), auf [maxLength] kuerzen. Fehler als Toast.
 *
 * @param position liefert (lat, lon) beim Tippen, null = keine Position
 * @param iconOnly true = nur Icon (48 dp), false = Knopf mit Text ueber die ganze Breite
 */
@Composable
fun MunicipalityInsertButton(
    position: () -> Pair<Double, Double>?,
    note: String,
    maxLength: Int,
    onNoteChange: (String) -> Unit,
    iconOnly: Boolean = false,
    ensureFix: (suspend () -> Unit)? = null,  // T206: ohne Position einmal aktiv suchen
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lookup = remember { MunicipalityLookup(context) }
    var busy by remember { mutableStateOf(false) }

    val onClick = click@{
        val first = position()
        if (first == null && ensureFix == null) {
            Toast.makeText(context, context.getString(R.string.ui_gemeinde_keine_position), Toast.LENGTH_SHORT).show()
            return@click
        }
        busy = true
        scope.launch {
            var pos = first
            if (pos == null) {
                Toast.makeText(context, context.getString(R.string.ui_gemeinde_suche_position), Toast.LENGTH_SHORT).show()
                ensureFix?.invoke()
                pos = position()
            }
            if (pos == null) {
                busy = false
                Toast.makeText(context, context.getString(R.string.ui_gemeinde_keine_position), Toast.LENGTH_SHORT).show()
                return@launch
            }
            val label = lookup.lookup(pos.first, pos.second)
            busy = false
            if (label == null) {
                Toast.makeText(context, context.getString(R.string.ui_gemeinde_nicht_ermittelbar), Toast.LENGTH_SHORT).show()
            } else {
                val text = context.getString(R.string.ui_gemeinde_text, label)
                val joined = if (note.isEmpty()) text else "$note $text"
                onNoteChange(joined.take(maxLength))
            }
        }
        Unit
    }

    if (iconOnly) {
        IconButton(onClick = onClick, enabled = !busy, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.LocationCity, contentDescription = stringResource(R.string.ui_gemeinde_einfuegen))
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 4.dp)
        ) {
            Icon(Icons.Filled.LocationCity, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.ui_gemeinde_einfuegen))
        }
    }
}
