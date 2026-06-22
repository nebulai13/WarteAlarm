package com.example.wartealarm.domain

/**
 * User-configurable alarm behaviour.
 *
 * The modality flags are independent and combinable — the alarm engine ORs together whichever are
 * enabled at fire time (sound and/or vibrate and/or visual blink, etc.). This is why they're plain
 * booleans rather than a single enum: the user can, say, want vibrate + flashlight but no sound.
 */
data class AlarmSettings(
    /** Fire the quieter pre-alarm once this many people (or fewer) are ahead. */
    val preAlarmThreshold: Int = 2,

    /** Play the alarm sound. */
    val sound: Boolean = true,

    /**
     * Only play the sound when headphones are connected; with no headset the engine falls back to the
     * other enabled non-audio modalities (vibrate/blink) instead of blaring through the speaker.
     */
    val soundHeadphonesOnly: Boolean = false,

    /** Vibrate. */
    val vibrate: Boolean = true,

    /** Flash the screen. */
    val visualBlink: Boolean = false,

    /** Blink the camera flashlight (torch). */
    val flashlightBlink: Boolean = false,

    /** Force the alarm audio stream to max volume and override Do-Not-Disturb. */
    val fullSystemAlarm: Boolean = false,
)
