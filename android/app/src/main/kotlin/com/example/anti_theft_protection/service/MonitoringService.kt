package com.example.anti_theft_protection.service

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.anti_theft_protection.MainActivity
import com.example.anti_theft_protection.alarm.AlarmPlayer
import com.example.anti_theft_protection.alarm.CountdownManager
import com.example.anti_theft_protection.sim.SimMonitor
import com.example.anti_theft_protection.state.ProtectionState
import com.example.anti_theft_protection.state.ProtectionStore
import com.example.anti_theft_protection.state.SpikeLog

class MonitoringService : Service() {
    companion object {
        @Volatile var running = false
        private const val CHANNEL_ID = "monitoring"
        private const val NOTIF_ID = 1
        private const val FGS_TYPES =
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
    }

    private var monitor: SimMonitor? = null
    private lateinit var countdown: CountdownManager   // need a real Context, so built in onCreate
    private lateinit var alarm: AlarmPlayer

    override fun onCreate() {
        super.onCreate()
        countdown = CountdownManager(this) { onCountdownExpired() }
        alarm = AlarmPlayer(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val state = ProtectionStore.getState(this)
        // Android requires startForeground() soon after startForegroundService(),
        // even if we stop right afterwards. The mediaPlayback type is what lets us play audio
        // from the background on Android 17.
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(state), FGS_TYPES)

        if (state == ProtectionState.DISARMED) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (monitor == null) {
            monitor = SimMonitor(
                ctx = this,
                baselineId = ProtectionStore.getBaselineId(this),
                // ARMED: SIM assumed present. COUNTDOWN/ALARMING: assumed missing.
                initiallyPresent = state == ProtectionState.ARMED,
                onRemovalConfirmed = { onRemoval() },
                onRestoreConfirmed = { onRestored() },
            ).also { it.start() }

            if (state == ProtectionState.COUNTDOWN) {   // recreated mid-countdown
                val latest = SystemClock.elapsedRealtime() + ProtectionStore.getCountdownSeconds(this) * 1000L
                countdown.start(minOf(ProtectionStore.getDeadline(this), latest))
                SpikeLog.add(this, "COUNTDOWN resumed after service restart")
            }
            if (state == ProtectionState.ALARMING) {    // recreated mid-alarm
                SpikeLog.add(this, "ALARM resumed after service restart")
                alarm.start()
            }
        }
        running = true
        return START_STICKY
    }

    private fun onRemoval() {
        if (ProtectionStore.getState(this) != ProtectionState.ARMED) return
        val secs = ProtectionStore.getCountdownSeconds(this)
        // Measure from when the SIM first went missing, not from debounce confirmation.
        val deadline = SystemClock.elapsedRealtime() + secs * 1000L - SimMonitor.REMOVAL_DEBOUNCE_MS
        ProtectionStore.startCountdown(this, deadline)
        SpikeLog.add(this, "STATE ARMED -> COUNTDOWN (removal confirmed, setting ${secs}s)")
        countdown.start(deadline)
        refreshNotification()
    }

    private fun onRestored() {
        if (ProtectionStore.getState(this) != ProtectionState.COUNTDOWN) return  // ALARMING stays latched
        countdown.cancel()
        ProtectionStore.setState(this, ProtectionState.ARMED)
        SpikeLog.add(this, "STATE COUNTDOWN -> ARMED (original SIM restored)")
        refreshNotification()
    }

    private fun onCountdownExpired() {
        if (ProtectionStore.getState(this) != ProtectionState.COUNTDOWN) return
        ProtectionStore.setState(this, ProtectionState.ALARMING)
        SpikeLog.add(this, "STATE COUNTDOWN -> ALARMING (countdown expired)")
        refreshNotification()
        alarm.start()
    }

    private fun buildNotification(state: ProtectionState): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Protection status", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val text = when (state) {
            ProtectionState.ARMED -> "Armed: watching the SIM"
            ProtectionState.COUNTDOWN -> "SIM missing: alarm in"
            ProtectionState.ALARMING -> "ALARM ACTIVE: open the app and enter your PIN"
            ProtectionState.DISARMED -> "Disarmed"
        }
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SIM Sentinel")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(open)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (state == ProtectionState.COUNTDOWN) {
            // Built-in chronometer ticks by itself, so it costs no wake-ups.
            b.setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis() + ProtectionStore.remainingMs(this))
        }
        return b.build()
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(ProtectionStore.getState(this)))
    }

    override fun onDestroy() {
        alarm.stop()          // also restores the owner's alarm volume
        countdown.cancel()
        monitor?.stop()
        monitor = null
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}