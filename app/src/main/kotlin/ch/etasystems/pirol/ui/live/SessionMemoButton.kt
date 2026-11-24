package ch.etasystems.pirol.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.etasystems.pirol.R
import kotlinx.coroutines.delay

/** T264: Aufnahmefarbe des gedrueckten Memo-Knopfs */
private val MEMO_RECORDING_RED = Color(0xFFD32F2F)

/**
 * T264: Session-Memo-Knopf (Walkie-Talkie): Druecken startet, Loslassen stoppt die Aufnahme.
 * Waehrend der Aufnahme rot mit Laufzeit; kurze Vibration bei Start und Stopp. 48 dp Tap-Target.
 */
@Composable
fun SessionMemoButton(viewModel: LiveViewModel, modifier: Modifier = Modifier) {
    val startedAt by viewModel.sessionMemoStartedAtMs.collectAsState()
    val recording = startedAt != null
    val haptic = LocalHapticFeedback.current
    val description = stringResource(R.string.live_memo_halten)

    var elapsedSec by remember { mutableLongStateOf(0L) }
    LaunchedEffect(startedAt) {
        val start = startedAt ?: return@LaunchedEffect
        while (true) {
            elapsedSec = ((System.currentTimeMillis() - start) / 1000L).coerceAtLeast(0L)
            delay(250)
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (recording) MEMO_RECORDING_RED else Color.Transparent)
                .semantics {
                    contentDescription = description
                    role = Role.Button
                }
                .pointerInput(viewModel) {
                    detectTapGestures(onPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.startSessionMemo()
                        try {
                            tryAwaitRelease()
                        } finally {
                            // auch wenn der Knopf verschwindet (Aufnahme beendet) oder die Geste abbricht
                            viewModel.stopSessionMemo()
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    })
                }
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = null,
                tint = if (recording) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
        if (recording) {
            Text(
                text = "%d:%02d".format(elapsedSec / 60, elapsedSec % 60),
                style = MaterialTheme.typography.labelMedium,
                color = MEMO_RECORDING_RED,
                modifier = Modifier.padding(start = 2.dp, end = 4.dp)
            )
        }
    }
}
