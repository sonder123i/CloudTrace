package com.zcshou.gogogo.run.service

import com.zcshou.gogogo.run.engine.RunPhase
import com.zcshou.gogogo.run.engine.RunRuntimeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 进程内状态桥：RunSessionService 发布引擎状态，首页/胶囊订阅。
 */
object RunSessionHolder {

    private val _state = MutableStateFlow(RunRuntimeState())
    val state: StateFlow<RunRuntimeState> = _state.asStateFlow()

    @Volatile
    var sessionActive: Boolean = false
        private set

    internal fun publish(state: RunRuntimeState) {
        _state.value = state
        sessionActive = state.phase != RunPhase.IDLE && state.phase != RunPhase.FINISHED
    }

    internal fun reset() {
        _state.value = RunRuntimeState()
        sessionActive = false
    }
}