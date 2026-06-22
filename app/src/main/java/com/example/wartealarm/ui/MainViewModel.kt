package com.example.wartealarm.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.wartealarm.core.WatchBus
import com.example.wartealarm.data.ConnectionState
import com.example.wartealarm.data.SettingsStore
import com.example.wartealarm.domain.AlarmSettings
import com.example.wartealarm.domain.MyStatus
import com.example.wartealarm.domain.WatchParams
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Shared state for the two UI screens.
 *
 * It keeps the fragments thin by:
 * - holding the entry-screen text fields ([linkInput] / [numberInput]) across configuration changes, and
 * - re-exposing the foundation flows the status screen renders ([WatchBus] for live state, [SettingsStore]
 *   for persisted [AlarmSettings]) so the fragment never touches the singletons directly.
 *
 * An [AndroidViewModel] because [SettingsStore] is `Context`-driven; only the application `Context` is
 * held, so there's no activity/fragment leak.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    // --- Entry screen field state (survives rotation) -------------------------------------------

    /** Current text of the link field, retained across configuration changes. */
    var linkInput: String = ""

    /** Current text of the number field, retained across configuration changes. */
    var numberInput: String = ""

    // --- Live status (from the WatchBus) --------------------------------------------------------

    /** What is currently being watched, or `null` when idle. */
    val params: StateFlow<WatchParams?> = WatchBus.params

    /** The user's latest computed status. */
    val status: StateFlow<MyStatus> = WatchBus.status

    /** Live socket connection state. */
    val connection: StateFlow<ConnectionState> = WatchBus.connection

    /** True when running on the coarse `last_drawn` fallback rather than the precise ticket feed. */
    val coarseMode: StateFlow<Boolean> = WatchBus.coarseMode

    // --- Persisted alarm settings ---------------------------------------------------------------

    /** The persisted [AlarmSettings], observed for the settings controls. */
    val settings: StateFlow<AlarmSettings> =
        SettingsStore.flow(getApplication()).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = AlarmSettings(),
        )

    /** Persists a settings change made on the settings screen. */
    fun updateSettings(transform: (AlarmSettings) -> AlarmSettings) {
        viewModelScope.launch {
            SettingsStore.update(getApplication(), transform)
        }
    }

    private companion object {
        /** Keep the settings flow warm briefly after the screen leaves, to survive quick rotations. */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
