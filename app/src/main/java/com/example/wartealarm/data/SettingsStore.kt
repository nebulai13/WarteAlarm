package com.example.wartealarm.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.wartealarm.domain.AlarmSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore("wartealarm_settings")

/**
 * Persists [AlarmSettings] via Jetpack DataStore.
 *
 * Lives in the foundation (rather than being owned by the UI) because both the settings screen and the
 * alarm engine read it, and a single source avoids divergent copies. Methods take a [Context] instead
 * of holding global state, so there's nothing to initialise.
 */
object SettingsStore {

    private val PRE_ALARM_THRESHOLD = intPreferencesKey("pre_alarm_threshold")
    private val SOUND = booleanPreferencesKey("sound")
    private val SOUND_HEADPHONES_ONLY = booleanPreferencesKey("sound_headphones_only")
    private val VIBRATE = booleanPreferencesKey("vibrate")
    private val VISUAL_BLINK = booleanPreferencesKey("visual_blink")
    private val FLASHLIGHT_BLINK = booleanPreferencesKey("flashlight_blink")
    private val FULL_SYSTEM_ALARM = booleanPreferencesKey("full_system_alarm")

    /** Observes the stored settings, emitting defaults until the user changes anything. */
    fun flow(context: Context): Flow<AlarmSettings> =
        context.applicationContext.settingsDataStore.data.map { it.toAlarmSettings() }

    /** Reads the current settings once. */
    suspend fun current(context: Context): AlarmSettings = flow(context).first()

    /** Atomically updates the settings via [transform]. */
    suspend fun update(context: Context, transform: (AlarmSettings) -> AlarmSettings) {
        context.applicationContext.settingsDataStore.edit { prefs ->
            transform(prefs.toAlarmSettings()).writeInto(prefs)
        }
    }

    private fun Preferences.toAlarmSettings(): AlarmSettings {
        val defaults = AlarmSettings()
        return AlarmSettings(
            preAlarmThreshold = this[PRE_ALARM_THRESHOLD] ?: defaults.preAlarmThreshold,
            sound = this[SOUND] ?: defaults.sound,
            soundHeadphonesOnly = this[SOUND_HEADPHONES_ONLY] ?: defaults.soundHeadphonesOnly,
            vibrate = this[VIBRATE] ?: defaults.vibrate,
            visualBlink = this[VISUAL_BLINK] ?: defaults.visualBlink,
            flashlightBlink = this[FLASHLIGHT_BLINK] ?: defaults.flashlightBlink,
            fullSystemAlarm = this[FULL_SYSTEM_ALARM] ?: defaults.fullSystemAlarm,
        )
    }

    private fun AlarmSettings.writeInto(prefs: androidx.datastore.preferences.core.MutablePreferences) {
        prefs[PRE_ALARM_THRESHOLD] = preAlarmThreshold
        prefs[SOUND] = sound
        prefs[SOUND_HEADPHONES_ONLY] = soundHeadphonesOnly
        prefs[VIBRATE] = vibrate
        prefs[VISUAL_BLINK] = visualBlink
        prefs[FLASHLIGHT_BLINK] = flashlightBlink
        prefs[FULL_SYSTEM_ALARM] = fullSystemAlarm
    }
}
