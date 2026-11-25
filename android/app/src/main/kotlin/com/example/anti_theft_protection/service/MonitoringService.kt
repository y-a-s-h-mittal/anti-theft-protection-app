package com.example.anti_theft_protection.service

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.anti_theft_protection.sim.SimMonitor

class MonitoringService : Service() {
    companion object {
        @Volatile var running = false
        private const val CHANNEL_ID = "monitoring"
    }

    private lateinit var monitor: SimMonitor

    override fun onCreate() {
        super.onCreate()
        monitor = SimMonitor(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Protection status", NotificationManager.IMPORTANCE_LOW)
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Protection monitoring (spike)")
            .setContentText("Watching SIM state")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(this, 1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)

        if (!running) { monitor.start(); running = true }
        return START_STICKY
    }

    override fun onDestroy() {
        monitor.stop()
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}