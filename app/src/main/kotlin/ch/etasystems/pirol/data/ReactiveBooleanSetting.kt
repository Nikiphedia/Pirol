package ch.etasystems.pirol.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** T229: Boolean-Einstellung mit beobachtbarem Wert; jede Aenderung wird sofort persistiert. */
class ReactiveBooleanSetting(initial: Boolean, private val persist: (Boolean) -> Unit) {
    private val _flow = MutableStateFlow(initial)
    val flow: StateFlow<Boolean> = _flow
    val value: Boolean get() = _flow.value

    fun set(newValue: Boolean) {
        _flow.value = newValue
        persist(newValue)
    }

    fun toggle() = set(!_flow.value)
}
