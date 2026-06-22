package com.example.wartealarm.alarm

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import com.example.wartealarm.data.SettingsStore
import com.example.wartealarm.databinding.ActivityAlarmBinding
import kotlinx.coroutines.launch

/**
 * The full-screen "it's your turn" screen, surfaced over the lock screen by the [WatchService]'s
 * full-screen-intent notification.
 *
 * **Why a dedicated activity.** A full-screen intent can only reliably wake the device and show over the
 * keyguard if it launches a real activity. This screen shows the call, names the Zimmer, and gives one
 * unmissable STOP button. It owns the *screen-flash* (`visualBlink`) modality, because flashing a window
 * needs a foreground window — the rest of the modalities live in [AlarmEngine].
 *
 * The activity does **not** start the sound/vibration itself: [WatchService] already called
 * [AlarmEngine.fireAlarm] the instant the status flipped, so the alert is audible even if the system is
 * slow to bring this window up. Tapping STOP is what silences everything.
 */
class AlarmActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlarmBinding

    /** Background-flash animator, kept so we can cancel it in [onDestroy]. */
    private var blinkAnimator: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show over the lock screen and wake the screen. The manifest declares these too, but the
        // programmatic calls are the documented, reliable path on API 27+. Below 27 we fall back to the
        // window flags (deprecated but the only option there).
        showOverLockScreen()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityAlarmBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bindDesk()
        binding.alarmStop.setOnClickListener { dismissAlarm() }

        startVisualBlinkIfEnabled()
    }

    /**
     * Re-bind when the running instance is re-delivered an intent (the activity is `singleTask`), so a
     * second call/notification updates the shown Zimmer rather than stacking a new screen.
     */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        bindDesk()
    }

    /** Shows the desk/Zimmer passed via [EXTRA_DESK]; hides the line entirely when unknown. */
    private fun bindDesk() {
        val desk = intent.getStringExtra(EXTRA_DESK)
        binding.alarmDesk.text = if (desk.isNullOrBlank()) "" else "→ $desk"
    }

    /**
     * Stops every alarm modality, clears the alarm notification, and closes the screen. This is the
     * single user-facing off-switch, so it must reliably tear everything down — order matters less than
     * making sure all three happen.
     */
    private fun dismissAlarm() {
        AlarmEngine.stop(this)
        NotificationManagerCompat.from(this).cancel(ALARM_NOTIFICATION_ID)
        finish()
    }

    /** Calls the API 27+ "show over lock screen / turn screen on" APIs, guarded by version. */
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
    }

    /**
     * Animates the background between two attention-grabbing reds when the user enabled `visualBlink`.
     * Settings are read off the main thread via the lifecycle scope; we cancel any prior animator first
     * so re-entry (onNewIntent) doesn't stack animations.
     */
    private fun startVisualBlinkIfEnabled() {
        lifecycleScope.launch {
            val settings = SettingsStore.current(this@AlarmActivity)
            if (!settings.visualBlink) return@launch

            blinkAnimator?.cancel()
            blinkAnimator = ValueAnimator.ofObject(ArgbEvaluator(), BLINK_COLOR_A, BLINK_COLOR_B).apply {
                duration = BLINK_PERIOD_MS
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { binding.alarmRoot.setBackgroundColor(it.animatedValue as Int) }
                start()
            }
        }
    }

    override fun onDestroy() {
        blinkAnimator?.cancel()
        blinkAnimator = null
        super.onDestroy()
    }

    companion object {
        /** Intent extra carrying the desk/"Zimmer" string to display. */
        const val EXTRA_DESK = "com.example.wartealarm.alarm.EXTRA_DESK"

        /**
         * Notification id of the full-screen alarm notification. Shared with [WatchService] (which posts
         * it) so the STOP button can cancel the exact same notification.
         */
        const val ALARM_NOTIFICATION_ID = 1001

        private const val BLINK_PERIOD_MS = 450L
        private const val BLINK_COLOR_A = 0xFFB71C1C.toInt()
        private const val BLINK_COLOR_B = 0xFFFF5252.toInt()
    }
}
