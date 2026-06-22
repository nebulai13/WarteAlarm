package com.example.wartealarm.core

import com.example.wartealarm.data.ConnectionState
import com.example.wartealarm.domain.MyStatus
import com.example.wartealarm.domain.WatchParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A tiny process-wide bridge between the [WatchService][com.example.wartealarm.service.WatchService]
 * (the source of truth that owns the socket and runs the analysis) and the UI (which just renders).
 *
 * Using a shared observable here keeps the two decoupled — the UI never binds to the service — at the
 * cost of being a singleton. That's an acceptable trade for a single-user, single-watch prototype; if
 * this grew to multiple concurrent watches it would move into a bound service or a DI-scoped holder.
 */
object WatchBus {

    private val _params = MutableStateFlow<WatchParams?>(null)
    private val _status = MutableStateFlow<MyStatus>(MyStatus.Unknown)
    private val _connection = MutableStateFlow(ConnectionState.DISCONNECTED)
    private val _coarseMode = MutableStateFlow(false)

    /** What is currently being watched, or `null` when idle. */
    val params: StateFlow<WatchParams?> = _params.asStateFlow()

    /** The user's latest computed status. */
    val status: StateFlow<MyStatus> = _status.asStateFlow()

    /** Live socket connection state. */
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    /** True when running on the coarse `last_drawn` fallback rather than the precise ticket feed. */
    val coarseMode: StateFlow<Boolean> = _coarseMode.asStateFlow()

    fun setParams(value: WatchParams?) { _params.value = value }
    fun setStatus(value: MyStatus) { _status.value = value }
    fun setConnection(value: ConnectionState) { _connection.value = value }
    fun setCoarseMode(value: Boolean) { _coarseMode.value = value }

    /** Reset to the idle state when a watch stops. */
    fun clear() {
        _params.value = null
        _status.value = MyStatus.Unknown
        _connection.value = ConnectionState.DISCONNECTED
        _coarseMode.value = false
    }
}
