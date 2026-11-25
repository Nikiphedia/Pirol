package ch.etasystems.pirol.ui.analysis

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.etasystems.pirol.R
import ch.etasystems.pirol.data.importer.ImportTimeParser
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** T274: Datum aus dem DatePicker (UTC-Mitternacht in ms) + Uhrzeit zu einer Ortszeit; Sekunden aus [base]. */
object DateTimePick {
    fun combine(dateUtcMillis: Long, hour: Int, minute: Int, base: ZonedDateTime?, zone: ZoneId): ZonedDateTime {
        val date = Instant.ofEpochMilli(dateUtcMillis).atZone(ZoneOffset.UTC).toLocalDate()
        val sec = base?.second ?: 0
        return LocalDateTime.of(date, LocalTime.of(hour, minute, sec)).atZone(zone)
    }

    /** Datum als UTC-Mitternacht in ms (Eingabe des DatePickers). */
    fun dateUtcMillis(t: ZonedDateTime): Long =
        t.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    fun today(zone: ZoneId): LocalDate = LocalDate.now(zone)
}

/**
 * T274: Feld "Startzeit" mit Material-3 DatePicker und anschliessendem TimePicker (24 h).
 * Sekunden bleiben aus dem bisherigen Wert erhalten. [value] = null: leer (muss gewaehlt werden).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimePickerField(
    value: ZonedDateTime?,
    zone: ZoneId,
    onValueChange: (ZonedDateTime) -> Unit,
    isError: Boolean,
    supportingText: String,
    modifier: Modifier = Modifier
) {
    var step by remember { mutableStateOf(0) }  // 0 zu, 1 Datum, 2 Zeit
    var pickedDate by remember { mutableStateOf(0L) }
    val shown = value?.let { ImportTimeParser.formatInput(it, zone) } ?: ""

    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = shown,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            isError = isError,
            label = { Text(stringResource(R.string.import_startzeit)) },
            supportingText = { Text(supportingText) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
        )
        // Ueberlagerung faengt den Tipp ab (ein readOnly-Feld reicht Klicks nicht zuverlaessig weiter)
        Box(modifier = Modifier.matchParentSize().clickable { step = 1 })
    }

    if (step == 1) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = DateTimePick.dateUtcMillis(value ?: ZonedDateTime.now(zone))
        )
        DatePickerDialog(
            onDismissRequest = { step = 0 },
            confirmButton = {
                TextButton(
                    enabled = state.selectedDateMillis != null,
                    onClick = { pickedDate = state.selectedDateMillis ?: return@TextButton; step = 2 },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text(stringResource(R.string.import_zeit_weiter)) }
            },
            dismissButton = {
                TextButton(onClick = { step = 0 }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        ) { DatePicker(state = state) }
    }
    if (step == 2) {
        val base = value ?: ZonedDateTime.now(zone)
        val state = rememberTimePickerState(initialHour = base.hour, initialMinute = base.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { step = 0 },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onValueChange(DateTimePick.combine(pickedDate, state.hour, state.minute, value, zone))
                        step = 0
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text(stringResource(R.string.import_zeit_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { step = 0 }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}
