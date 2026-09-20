package com.trevor.assistant

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TrevorOrbState { IDLE, LISTENING, THINKING, ANALYSING, RESEARCHING, PROCESSING_FILE, EXECUTING, SUCCESS, ERROR }
enum class TrevorRequestState { IDLE, PROCESSING, SUCCESS, ERROR }
enum class TrevorFileState { NONE, SELECTED, VALIDATING, EXTRACTING, READY, ERROR }
enum class TrevorAiState { DISABLED, READY, PROCESSING, ERROR }

data class TrevorState(
    val currentMode: TrevorMode? = TrevorMode.NORMAL,
    val orbState: TrevorOrbState = TrevorOrbState.IDLE,
    val requestState: TrevorRequestState = TrevorRequestState.IDLE,
    val fileState: TrevorFileState = TrevorFileState.NONE,
    val aiState: TrevorAiState = TrevorAiState.DISABLED,
    val lastOutput: String? = null,
    val lastError: String? = null,
    val terminalOutput: String = ""
)

object TrevorStateStore {
    private val mutableState = MutableStateFlow(TrevorState())
    val state: StateFlow<TrevorState> = mutableState.asStateFlow()
    internal fun update(transform: (TrevorState) -> TrevorState) { mutableState.value = transform(mutableState.value) }
    fun reset() { mutableState.value = TrevorState() }
}
