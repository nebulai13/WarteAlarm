package com.example.wartealarm.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.wartealarm.alarm.AlarmActivity
import com.example.wartealarm.alarm.AlarmEngine
import com.example.wartealarm.core.WatchBus
import com.example.wartealarm.data.SettingsStore
import com.example.wartealarm.data.WnrRepository
import com.example.wartealarm.domain.MyStatus
import com.example.wartealarm.domain.RoomSnapshot
import com.example.wartealarm.domain.WatchParams
import com.example.wartealarm.socket.WnrSocketRepository
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * The background watcher: holds the live socket open with a wakelock for the whole wait and raises the
 * alarm the moment the user is called — reliably, even with the screen off.
 *
 * **Why this is the app's spine.** A silent failure here means the user misses their turn, which defeats
 * the entire product (see CLAUDE.md "hot zones"). It therefore runs as a foreground service so Android
 * won't kill it, holds a partial wakelock so the CPU keeps the socket alive screen-off, and fires alarms
 * off *status transitions* (not every emission) so the alarm fires exactly once per change.
 *
 * Data flow: [WnrSocketRepository] → [RoomSnapshot]s → [com.example.wartealarm.domain.QueueAnalyzer] →
 * [MyStatus] → published on [WatchBus] for the UI, and diffed against the previous status to drive
 * [AlarmEngine] + the full-screen [AlarmActivity].
 */
class WatchService : LifecycleService() {

    /** The live feed. Constructed here (no-arg) and torn down in [onDestroy]. */
    private val repo: WnrRepository = WnrSocketRepository()

    /** Partial wakelock keeping the CPU alive so the socket keeps delivering with the screen off. */
    private var wakeLock: PowerManager.WakeLock? = null

    /** What we're watching; set from the start intent's extras. */
    private var params: WatchParams? = null

    /**
     * The previous computed status, so we fire alarms only on *transitions into* an alarming state — not
     * on every snapshot (the feed re-emits the same status many times while you wait).
     */
    private var lastStatus: MyStatus? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // A redelivered start with no extras (e.g. after a process restart) can't reconstruct the watch;
        // stop cleanly rather than run blind.
        val parsed = intent?.let { readParams(it) }
        if (parsed == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // If already running, ignore duplicate starts so we don't reconnect / re-foreground.
        if (params != null) return START_STICKY
        params = parsed

        startInForeground(parsed)
        acquireWakeLock()
        WatchBus.setParams(parsed)

        repo.connect(parsed.room)
        observeFeed(parsed)

        // START_STICKY: if the system kills us under memory pressure, recreate — but with a null intent,
        // which onStartCommand handles by stopping (we can't watch without the room/queue/number).
        return START_STICKY
    }

    /**
     * Collects snapshots + connection state together and, for each non-null snapshot, computes the user's
     * status, publishes it to [WatchBus], updates the ongoing notification, and drives the alarm.
     */
    private fun observeFeed(params: WatchParams) {
        lifecycleScope.launch {
            combine(
                repo.snapshots.filterNotNull(),
                repo.connectionState,
            ) { snapshot, connection -> snapshot to connection }
                .collect { (snapshot, connection) ->
                    val status = com.example.wartealarm.domain.QueueAnalyzer.analyze(
                        snapshot, params.queue, params.myNumber,
                    )

                    // Publish for the UI. `coarseMode` is true when we have no per-ticket feed at all.
                    WatchBus.setStatus(status)
                    WatchBus.setConnection(connection)
                    WatchBus.setCoarseMode(snapshot.tickets == null)

                    updateOngoingNotification(status, snapshot)
                    maybeFireAlarm(status)
                    lastStatus = status
                }
        }
    }

    /**
     * Fires the alarm on a *transition into* an alarming state, reading the latest settings each time.
     *
     * - Entering [MyStatus.Called], or coarse "reached", is the real call → loud looping alarm + the
     *   full-screen [AlarmActivity].
     * - Entering [MyStatus.Waiting] with few enough people ahead → the gentler pre-alarm.
     *
     * Comparing against [lastStatus] is what makes each fire exactly once: re-emissions of the same
     * status are skipped because the transition already happened.
     */
    private suspend fun maybeFireAlarm(status: MyStatus) {
        val previous = lastStatus
        val settings = SettingsStore.current(this@WatchService)

        when (status) {
            is MyStatus.Called -> {
                if (previous !is MyStatus.Called) {
                    AlarmEngine.fireAlarm(this@WatchService, status.desk, settings)
                    launchFullScreenAlarm(status.desk)
                }
            }

            is MyStatus.Coarse -> {
                // Coarse "reached" is the best "it might be your turn" signal in fallback mode.
                val wasReached = (previous as? MyStatus.Coarse)?.reached == true
                if (status.reached && !wasReached) {
                    AlarmEngine.fireAlarm(this@WatchService, desk = null, settings = settings)
                    launchFullScreenAlarm(desk = null)
                }
            }

            is MyStatus.Waiting -> {
                val crossedThreshold = status.ahead <= settings.preAlarmThreshold &&
                    !wasAtOrBelowPreAlarm(previous, settings.preAlarmThreshold)
                if (crossedThreshold) {
                    AlarmEngine.preAlarm(this@WatchService, settings)
                }
            }

            MyStatus.Skipped, MyStatus.Unknown -> Unit
        }
    }

    /** True if the previous status was already a [MyStatus.Waiting] at/below the pre-alarm threshold. */
    private fun wasAtOrBelowPreAlarm(previous: MyStatus?, threshold: Int): Boolean =
        previous is MyStatus.Waiting && previous.ahead <= threshold

    // --- Foreground / notifications ------------------------------------------------------------------

    /** Posts the ongoing notification and enters the foreground with the SPECIAL_USE FGS type. */
    private fun startInForeground(params: WatchParams) {
        ensureChannels()
        val notification = buildOngoingNotification(statusLine = "Watching queue for #${params.myNumber}…")

        // Android 10+ (Q) accepts an FGS type on startForeground; Android 14+ requires one. The
        // SPECIAL_USE type matches our manifest declaration, and its constant only exists from API 34,
        // so 29–33 fall back to no specific type (0) and pre-29 uses the typeless overload.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
            startForeground(ONGOING_NOTIFICATION_ID, notification, type)
        } else {
            startForeground(ONGOING_NOTIFICATION_ID, notification)
        }
    }

    /** Rebuilds and re-posts the ongoing notification to reflect the current [status]. */
    private fun updateOngoingNotification(status: MyStatus, snapshot: RoomSnapshot) {
        val line = describe(status, snapshot)
        val notification = buildOngoingNotification(statusLine = line)
        NotificationManagerCompat.from(this).notify(ONGOING_NOTIFICATION_ID, notification)
    }

    /** A short human summary of the current status for the ongoing notification. */
    private fun describe(status: MyStatus, snapshot: RoomSnapshot): String = when (status) {
        is MyStatus.Waiting ->
            if (status.ahead == 0) "You're next" else "${status.ahead} ahead of you (position ${status.position})"
        is MyStatus.Called -> "It's your turn" + (status.desk?.let { " → $it" } ?: "")
        MyStatus.Skipped -> "Your number was skipped"
        is MyStatus.Coarse ->
            if (status.reached) "It may be your turn (coarse mode)"
            else "Coarse mode · last called: ${status.lastDrawn ?: "—"}"
        MyStatus.Unknown -> if (snapshot.currentlyOpen) "Watching queue…" else "Queue closed"
    }

    /** Builds the low-importance ongoing notification that keeps the service in the foreground. */
    private fun buildOngoingNotification(statusLine: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("WarteAlarm")
            .setContentText(statusLine)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentPendingIntent())
            .setSilent(true)
            .build()

    /**
     * Posts the high-importance full-screen notification and launches [AlarmActivity]. The full-screen
     * intent is the only reliable way to surface over a locked screen; we also call `startActivity` so
     * the screen comes up even when the OS chooses to show the notification as a heads-up instead.
     */
    private fun launchFullScreenAlarm(desk: String?) {
        val fullScreenIntent = Intent(this, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(AlarmActivity.EXTRA_DESK, desk)
        }
        val pending = PendingIntent.getActivity(
            this,
            /* requestCode = */ 0,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ALARM)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Es ist soweit!")
            .setContentText("It's your turn" + (desk?.let { " → $it" } ?: ""))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(pending, /* highPriority = */ true)
            .setContentIntent(pending)
            .build()

        NotificationManagerCompat.from(this).notify(AlarmActivity.ALARM_NOTIFICATION_ID, notification)

        // Best-effort direct launch in addition to the full-screen intent. Wrapped because background
        // activity-start restrictions can throw; the notification is the guaranteed surface.
        runCatching { startActivity(fullScreenIntent) }
    }

    /** Tapping the ongoing notification reopens the app's main screen. */
    private fun contentPendingIntent(): PendingIntent {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent().setClassName(packageName, "$packageName.MainActivity")
        return PendingIntent.getActivity(
            this,
            /* requestCode = */ 1,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Creates both notification channels (idempotent). Uses the Compat API so it's a no-op pre-O. */
    private fun ensureChannels() {
        val manager = NotificationManagerCompat.from(this)

        val ongoing = NotificationChannelCompat.Builder(CHANNEL_ONGOING, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName("Queue watch")
            .setDescription("Ongoing notification while WarteAlarm watches your queue.")
            .build()

        // High importance so the alarm channel can present full-screen + heads-up.
        val alarm = NotificationChannelCompat.Builder(CHANNEL_ALARM, NotificationManagerCompat.IMPORTANCE_HIGH)
            .setName("It's your turn")
            .setDescription("Fires when your number is called.")
            .build()

        manager.createNotificationChannel(ongoing)
        manager.createNotificationChannel(alarm)
    }

    // --- Wakelock ------------------------------------------------------------------------------------

    /** Acquires a partial wakelock so the CPU keeps servicing the socket while the screen is off. */
    private fun acquireWakeLock() {
        val power = ContextCompat.getSystemService(this, PowerManager::class.java) ?: return
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_TIMEOUT_MS)
        }
    }

    // --- Lifecycle / params ---------------------------------------------------------------------------

    private fun readParams(intent: Intent): WatchParams? {
        val room = intent.getStringExtra(EXTRA_ROOM) ?: return null
        val queue = intent.getStringExtra(EXTRA_QUEUE) ?: return null
        val number = intent.getIntExtra(EXTRA_NUMBER, Int.MIN_VALUE)
        if (number == Int.MIN_VALUE) return null
        return WatchParams(room = room, queue = queue, myNumber = number)
    }

    override fun onDestroy() {
        repo.disconnect()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        AlarmEngine.stop(this)
        WatchBus.clear()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ROOM = "com.example.wartealarm.service.EXTRA_ROOM"
        private const val EXTRA_QUEUE = "com.example.wartealarm.service.EXTRA_QUEUE"
        private const val EXTRA_NUMBER = "com.example.wartealarm.service.EXTRA_NUMBER"

        /** Ongoing foreground notification id (kept distinct from the alarm notification). */
        private const val ONGOING_NOTIFICATION_ID = 2001

        private const val CHANNEL_ONGOING = "watch_ongoing"
        private const val CHANNEL_ALARM = "watch_alarm"

        private const val WAKELOCK_TAG = "WarteAlarm:WatchService"

        /** Safety cap on the wakelock so a stuck service can't drain the battery forever (3 hours). */
        private const val WAKELOCK_TIMEOUT_MS = 3 * 60 * 60 * 1000L

        /** Starts the foreground watch for [params]. Safe to call when already running (it's idempotent). */
        fun start(context: Context, params: WatchParams) {
            val intent = Intent(context, WatchService::class.java).apply {
                putExtra(EXTRA_ROOM, params.room)
                putExtra(EXTRA_QUEUE, params.queue)
                putExtra(EXTRA_NUMBER, params.myNumber)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /** Stops the watch and tears down the socket + alarm. */
        fun stop(context: Context) {
            context.stopService(Intent(context, WatchService::class.java))
        }
    }
}
