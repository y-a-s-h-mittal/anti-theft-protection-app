package com.example.anti_theft_protection.alarm

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.example.anti_theft_protection.state.SpikeLog

/**
 * Runs one countdown toward an absolute deadline (SystemClock.elapsedRealtime() base).
 * A partial wake lock keeps the CPU awake so the timer cannot stall while the screen is off.
 */
class CountdownManager(
    private val ctx: Context,
    private val onExpired: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val wakeLock = ctx.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "antitheft:countdown")
        .apply { setReferenceCounted(false) }

    private var expiry: Runnable? = null

    fun start(deadlineElapsed: Long) {
        cancel()
        val remaining = (deadlineElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        // The timeout is a safety net: a bug can never hold the CPU awake forever.
        wakeLock.acquire(remaining + 5_000L)
        val r = Runnable {
            expiry = null
            releaseWake()
            SpikeLog.add(ctx, "COUNTDOWN: expired")
            onExpired()
        }
        expiry = r
        handler.postDelayed(r, remaining)
        SpikeLog.add(ctx, "COUNTDOWN: started, ${remaining}ms remaining")
    }

    fun cancel() {
        expiry?.let { handler.removeCallbacks(it) }
        if (expiry != null) SpikeLog.add(ctx, "COUNTDOWN: cancelled")
        expiry = null
        releaseWake()
    }

    private fun releaseWake() {
        if (wakeLock.isHeld) wakeLock.release()
    }
}