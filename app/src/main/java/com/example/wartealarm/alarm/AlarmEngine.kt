package com.example.wartealarm.alarm

import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.ContextCompat

/**
 * The alerting engine: drives the configured [com.example.wartealarm.domain.AlarmSettings] modalities
 * (sound, vibration, flashlight) when it's the user's turn.
 *
 * **Why a singleton `object`.** The alarm is the app's entire reason to exist, and it must be stoppable
 * from two unrelated places — the foreground [com.example.wartealarm.service.WatchService] that *starts*
 * it and the full-screen [AlarmActivity] whose STOP button the user taps. A process-wide singleton lets
 * [AlarmActivity] call [stop] directly without holding a reference to the service. The trade-off (global
 * mutable state) is acceptable for a single-user, single-alarm prototype, and we guard the players so a
 * double-start or double-stop is harmless.
 *
 * Screen-flash (`visualBlink`) is intentionally *not* here — it needs a foreground window, so
 * [AlarmActivity] owns it.
 *
 * Threading: all public methods are expected to be called from the main thread (the service collects on
 * the main dispatcher, the activity on its UI thread). The torch blink runs on a main-thread [Handler].
 */
object AlarmEngine {

    /** Looping alarm-stream ringtone, held so [stop] can silence it. `null` when not sounding. */
    private var ringtone: Ringtone? = null

    /** The vibrator currently buzzing, held so [stop] can cancel the waveform. */
    private var vibrator: Vibrator? = null

    /** Camera id whose torch we toggled, held so [stop] can switch it off. `null` when not blinking. */
    private var torchCameraId: String? = null

    /** Drives the torch on/off toggles; on the main looper so it can be cleanly removed in [stop]. */
    private val torchHandler = Handler(Looper.getMainLooper())
    private var torchOn = false

    /** Vibration waveform for the full alarm: a steady, insistent buzz (off/on/off/on…), looping. */
    private val ALARM_VIBRATION_PATTERN = longArrayOf(0, 600, 400)

    /** A single gentle double-tap for the pre-alarm — noticeable but not alarming. */
    private val PRE_ALARM_VIBRATION_PATTERN = longArrayOf(0, 200, 150, 200)

    /** Torch blink half-period in milliseconds (on for this long, then off for this long). */
    private const val TORCH_BLINK_INTERVAL_MS = 350L

    /**
     * Fires the full "it's your turn" alarm, combining every enabled modality.
     *
     * The combinable flags are ORed together at fire time, mirroring the user's intent: e.g. vibrate +
     * flashlight with no sound. [desk] is unused here (the visible "go to Zimmer X" lives in the
     * notification / [AlarmActivity]); it's accepted to keep the call site self-documenting.
     */
    fun fireAlarm(context: Context, desk: String?, settings: com.example.wartealarm.domain.AlarmSettings) {
        // Always start from a clean slate so a re-fire doesn't stack two ringtones / waveforms.
        stop(context)

        if (settings.fullSystemAlarm) {
            raiseAlarmVolume(context)
        }
        if (shouldPlaySound(context, settings)) {
            startSound(context, looping = true)
        }
        if (settings.vibrate) {
            startVibration(context, ALARM_VIBRATION_PATTERN, looping = true)
        }
        if (settings.flashlightBlink) {
            startTorchBlink(context)
        }
    }

    /**
     * Fires the quieter pre-alarm (a single short buzz/tone), respecting the same modality toggles.
     *
     * This is a heads-up that the user is near the front, not the final call, so nothing loops: the tone
     * plays once and the vibration is a brief double-tap. We deliberately skip the flashlight and the
     * volume override here — those are reserved for the real "your turn" moment.
     */
    fun preAlarm(context: Context, settings: com.example.wartealarm.domain.AlarmSettings) {
        stop(context)

        if (shouldPlaySound(context, settings)) {
            startSound(context, looping = false)
        }
        if (settings.vibrate) {
            startVibration(context, PRE_ALARM_VIBRATION_PATTERN, looping = false)
        }
    }

    /**
     * Stops every modality and clears all references. Safe to call repeatedly and when nothing is active —
     * this is the safety valve both the STOP button and the service's teardown rely on.
     */
    fun stop(context: Context) {
        ringtone?.let { if (it.isPlaying) it.stop() }
        ringtone = null

        vibrator?.cancel()
        vibrator = null

        stopTorchBlink(context)
    }

    // --- Sound ---------------------------------------------------------------------------------------

    /**
     * Decides whether sound should play. Honours the *headphones-only* preference: if it's set and no
     * wired/Bluetooth output route is connected, we stay silent on the speaker and let the other enabled
     * modalities (vibrate/flash) carry the alarm instead — never blaring through the speaker by surprise.
     */
    private fun shouldPlaySound(context: Context, settings: com.example.wartealarm.domain.AlarmSettings): Boolean {
        if (!settings.sound) return false
        if (settings.soundHeadphonesOnly && !isHeadsetConnected(context)) return false
        return true
    }

    /**
     * Plays the default alarm ringtone on [AudioManager.STREAM_ALARM]. USAGE_ALARM is what makes this
     * audible over Do-Not-Disturb on the alarm stream, so we route through it explicitly.
     */
    private fun startSound(context: Context, looping: Boolean) {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: return

        val tone = RingtoneManager.getRingtone(context.applicationContext, uri) ?: return
        tone.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        // Looping on Ringtone is API 28+; on older devices a single play is the best we can do here.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            tone.isLooping = looping
        }
        tone.play()
        ringtone = tone
    }

    /**
     * True if any wired or Bluetooth-A2DP output route is connected — the gate for *headphones-only*.
     * Uses the modern device query on API 23+ (always available here, minSdk 24).
     */
    private fun isHeadsetConnected(context: Context): Boolean {
        val audio = ContextCompat.getSystemService(context, AudioManager::class.java) ?: return false
        val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return outputs.any { it.type in HEADSET_OUTPUT_TYPES }
    }

    /**
     * Output-device types that count as "headphones" for the *headphones-only* gate: wired, USB, and
     * Bluetooth/BLE audio. A plain `val` (not `const`) because the platform fields aren't Kotlin
     * compile-time constants. `TYPE_BLE_HEADSET` (26) is hardcoded since the constant is API 31+; its
     * integer value is stable, so it simply never matches on older devices.
     */
    private val HEADSET_OUTPUT_TYPES: Set<Int> = setOf(
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
        26, // AudioDeviceInfo.TYPE_BLE_HEADSET (API 31)
    )

    /**
     * Pushes the alarm stream toward maximum volume for the "full system alarm" mode.
     *
     * Note: USAGE_ALARM already bypasses Do-Not-Disturb on the alarm stream, so we deliberately do **not**
     * request `ACCESS_NOTIFICATION_POLICY` / DND-policy access in this prototype — raising the alarm
     * stream volume is sufficient to make the call impossible to miss.
     */
    private fun raiseAlarmVolume(context: Context) {
        val audio = ContextCompat.getSystemService(context, AudioManager::class.java) ?: return
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, max, /* flags = */ 0)
    }

    // --- Vibration -----------------------------------------------------------------------------------

    /**
     * Starts the given waveform [pattern]. When [looping] it repeats from index 0 (the final-alarm case);
     * otherwise it runs once (the pre-alarm). [VibrationEffect] is API 26+; we fall back to the deprecated
     * pattern API on older devices so minSdk-24 phones still buzz.
     */
    @Suppress("DEPRECATION")
    private fun startVibration(context: Context, pattern: LongArray, looping: Boolean) {
        val vib = resolveVibrator(context) ?: return
        if (!vib.hasVibrator()) return

        val repeatIndex = if (looping) 0 else -1
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createWaveform(pattern, repeatIndex))
        } else {
            vib.vibrate(pattern, repeatIndex)
        }
        vibrator = vib
    }

    /** Resolves a [Vibrator], using [VibratorManager] on API 31+ and the legacy service below it. */
    @Suppress("DEPRECATION")
    private fun resolveVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = ContextCompat.getSystemService(context, VibratorManager::class.java)
            manager?.defaultVibrator
        } else {
            ContextCompat.getSystemService(context, Vibrator::class.java)
        }
    }

    // --- Flashlight ----------------------------------------------------------------------------------

    /**
     * Blinks the camera torch on a main-thread timer via [CameraManager.setTorchMode] — no CAMERA
     * permission needed. We pick the first camera that has a flash unit and toggle it every
     * [TORCH_BLINK_INTERVAL_MS]. Failures (no flash, camera in use) are swallowed: a missing torch must
     * never take down the rest of the alarm.
     */
    private fun startTorchBlink(context: Context) {
        val cameraManager = ContextCompat.getSystemService(context, CameraManager::class.java) ?: return
        val cameraId = runCatching {
            cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull() ?: return

        torchCameraId = cameraId
        torchOn = false

        val blink = object : Runnable {
            override fun run() {
                val id = torchCameraId ?: return
                torchOn = !torchOn
                runCatching { cameraManager.setTorchMode(id, torchOn) }
                torchHandler.postDelayed(this, TORCH_BLINK_INTERVAL_MS)
            }
        }
        torchHandler.post(blink)
    }

    /** Stops the torch timer and forces the light off. Idempotent. */
    private fun stopTorchBlink(context: Context) {
        torchHandler.removeCallbacksAndMessages(null)
        val cameraManager = ContextCompat.getSystemService(context, CameraManager::class.java)
        torchCameraId?.let { id ->
            runCatching { cameraManager?.setTorchMode(id, false) }
        }
        torchCameraId = null
        torchOn = false
    }
}
