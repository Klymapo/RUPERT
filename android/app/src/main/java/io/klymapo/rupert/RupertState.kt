package io.klymapo.rupert

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Lo que Rupert está haciendo; cada modo tiene su cara en pantalla. */
enum class Mode { OFF, SLEEPING, LISTENING, THINKING, SPEAKING, EXCITED, ERROR }

data class UiState(
    val mode: Mode = Mode.OFF,
    val subtitle: String = "",
    val heard: String = "",
    val wakeWord: String = "",
    val lastScore: Float = 0f,
)

/** Estado compartido entre el servicio de micrófono y la pantalla. */
object RupertState {
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    fun mode(mode: Mode, subtitle: String? = null) = _ui.update {
        it.copy(mode = mode, subtitle = subtitle ?: it.subtitle)
    }

    fun heard(text: String) = _ui.update { it.copy(heard = text) }
    fun wakeWord(name: String) = _ui.update { it.copy(wakeWord = name) }
    fun score(value: Float) = _ui.update { it.copy(lastScore = value) }
}
