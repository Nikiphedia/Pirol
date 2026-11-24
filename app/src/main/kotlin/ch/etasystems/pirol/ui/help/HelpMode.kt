package ch.etasystems.pirol.ui.help

import androidx.annotation.StringRes
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import ch.etasystems.pirol.R

/** T266: Ein Hilfetext (Ressource) plus optional das passende Kapitel der Anleitung (z.B. "6"). */
data class HelpTopic(@StringRes val textRes: Int, val chapter: String? = null)

/**
 * T266: App-weiter Zustand des Hilfemodus und der Anleitung (eine Instanz in MainActivity).
 */
@Stable
class HelpModeState {
    /** Hilfemodus an: Bedienelemente mit [helpTopic] loesen nicht aus, sondern zeigen ihre Erklaerung */
    var active by mutableStateOf(false)

    /** Aktuell angezeigte Erklaerungsblase (null = keine) */
    var bubble by mutableStateOf<HelpTopic?>(null)

    /** Anleitung offen; Wert = Kapitel zum Hinspringen ("" = Anfang), null = geschlossen */
    var manualChapter by mutableStateOf<String?>(null)

    private val tapDecider = HelpTapDecider()

    /** Antippen des "?"-Knopfs: sofort umschalten, Doppeltipp oeffnet die Anleitung */
    fun onHelpButtonTap(nowMs: Long = android.os.SystemClock.uptimeMillis()) {
        val r = tapDecider.onTap(nowMs, active)
        active = r.active
        if (!active) bubble = null
        if (r.openManual) manualChapter = ""
    }

    fun show(topic: HelpTopic) { bubble = topic }

    fun openManual(chapter: String?) {
        bubble = null
        manualChapter = chapter ?: ""
    }
}

/** Ausserhalb von MainActivity (Previews, Dialog-Tests) gibt es einen inaktiven Zustand. */
val LocalHelpMode = staticCompositionLocalOf { HelpModeState() }

/**
 * T266: Hilfetext fuer ein Bedienelement.
 *
 * Entscheid: Pointer-Abfang in der Initial-Pass statt Umbau der Knoepfe. Der Modifier sitzt vor
 * (ausserhalb) dem clickable/toggleable/slider des Elements und sieht die Beruehrung darum zuerst;
 * im Hilfemodus wird Down/Move/Up konsumiert, das Element loest nicht aus (auch kein Langdruck).
 * Ausserhalb des Hilfemodus wird nichts konsumiert und die Semantik bleibt unveraendert
 * (TalkBack wie bisher). Im Hilfemodus ersetzt eine Semantik-Aktion den Klick, damit auch
 * TalkBack-Nutzer die Erklaerung statt der Aktion bekommen.
 */
fun Modifier.helpTopic(@StringRes textRes: Int, chapter: String? = null): Modifier = composed {
    val state = LocalHelpMode.current
    val active = state.active
    val topic = HelpTopic(textRes, chapter)
    val currentTopic by rememberUpdatedState(topic)
    val label = if (active) stringResource(R.string.help_semantics_label) else null
    this
        .then(
            if (active) Modifier.semantics {
                onClick(label = label) { state.show(currentTopic); true }
            } else Modifier
        )
        .pointerInput(state, active) {
            if (!HelpIntercept.shouldIntercept(active, hasTopic = true)) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                down.consume()
                val slop = viewConfiguration.touchSlop
                var moved = false
                var released = false
                while (!released) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach {
                        if ((it.position - down.position).getDistance() > slop) moved = true
                        it.consume()
                    }
                    released = event.changes.all { !it.pressed }
                }
                // Gewischt statt getippt: keine Blase (die Ausloesung bleibt trotzdem gesperrt)
                if (!moved) state.show(currentTopic)
            }
        }
}

/** Kurzform fuer Composables, die selbst eine Hilfe-Ressource brauchen */
@Composable
fun isHelpModeActive(): Boolean = LocalHelpMode.current.active
