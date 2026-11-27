package com.example.anti_theft_protection.service

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.anti_theft_protection.MainActivity
import com.example.anti_theft_protection.sim.SimMonitor
import com.example.anti_theft_protection.state.ProtectionState
import com.example.anti_theft_protection.state.ProtectionStore
import com.example.anti_theft_protection.state.SpikeLog

class MonitoringService : Service() {
    companion object {
        @Volatile var running = false
        private const val CHANNEL_ID = "monitoring"
        private const val NOTIF_ID = 1
    }

    private var monitor: SimMonitor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val state = ProtectionStore.getState(this)
        // Android requires startForeground() soon after startForegroundService(),
        // even if we stop right afterwards.
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(state),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )

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
        }
        running = true
        return START_STICKY
    }

    private fun onRemoval() {
        if (ProtectionStore.getState(this) == ProtectionState.ARMED) {
            ProtectionStore.setState(this, ProtectionState.COUNTDOWN)
            SpikeLog.add(this, "STATE ARMED -> COUNTDOWN (removal confirmed)")
            refreshNotification()
        }
    }

    private fun onRestored() {
        if (ProtectionStore.getState(this) == ProtectionState.COUNTDOWN) {
            ProtectionStore.setState(this, ProtectionState.ARMED)
            SpikeLog.add(this, "STATE COUNTDOWN -> ARMED (original SIM restored)")
            refreshNotification()
        }
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
            ProtectionState.COUNTDOWN -> "SIM missing: countdown"
            ProtectionState.ALARMING -> "ALARM ACTIVE"
            ProtectionState.DISARMED -> "Disarmed"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Anti-theft protection")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(open)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(ProtectionStore.getState(this)))
    }

    override fun onDestroy() {
        monitor?.stop()
        monitor = null
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}