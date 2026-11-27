package com.example.anti_theft_protection.sim

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.example.anti_theft_protection.state.SpikeLog

/**
 * Watches whether the baseline subscription is still active.
 * Callbacks are only prompts to re-read state; a change must survive a debounce
 * window before it is reported.
 */
class SimMonitor(
    private val ctx: Context,
    private val baselineId: Int,
    initiallyPresent: Boolean,
    private val onRemovalConfirmed: () -> Unit,
    private val onRestoreConfirmed: () -> Unit,
) {
    companion object {
        const val REMOVAL_DEBOUNCE_MS = 1500L
        const val RESTORE_DEBOUNCE_MS = 2000L

        /** IDs of active subscriptions, or null if they cannot be read. */
        fun readActiveIds(ctx: Context): Set<Int>? = try {
            val sm = ctx.getSystemService(SubscriptionManager::class.java)
            sm.activeSubscriptionInfoList.orEmpty().map { it.subscriptionId }.toSet()
        } catch (e: SecurityException) {
            null
        }
    }

    private val sm = ctx.getSystemService(SubscriptionManager::class.java)
    private val tm = ctx.getSystemService(TelephonyManager::class.java)
    private val executor = ContextCompat.getMainExecutor(ctx)
    private val handler = Handler(Looper.getMainLooper())
    private val wakeLock = ctx.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "antitheft:simdebounce")
        .apply { setReferenceCounted(false) }

    private var confirmedPresent = initiallyPresent
    private var pendingCheck: Runnable? = null

    private val listener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
        override fun onSubscriptionsChanged() = evaluate("callback")
    }

    private fun isBaselinePresent(): Boolean? = readActiveIds(ctx)?.contains(baselineId)

    private fun describe(): String =
        "activeIds=${readActiveIds(ctx) ?: "unreadable"} simState(slot0)=${tm.getSimState(0)}"

    private fun evaluate(source: String) {
        val present = isBaselinePresent()
        if (present == null) {
            SpikeLog.add(ctx, "SIM: cannot read subscriptions (permission?)")
            return
        }
        if (present == confirmedPresent) {   // nothing changed (or it flapped back)
            cancelPending()
            return
        }
        if (pendingCheck != null) return     // already waiting to confirm this change

        val delay = if (present) RESTORE_DEBOUNCE_MS else REMOVAL_DEBOUNCE_MS
        SpikeLog.add(
            ctx,
            "SIM: baseline id=$baselineId ${if (present) "back" else "missing"} " +
                    "(via $source), confirming in ${delay}ms [${describe()}]"
        )
        wakeLock.acquire(10_000L)            // CPU must stay awake until the check fires
        val r = Runnable { pendingCheck = null; confirm() }
        pendingCheck = r
        handler.postDelayed(r, delay)
    }

    private fun confirm() {
        val present = isBaselinePresent()
        if (present != null && present != confirmedPresent) {
            confirmedPresent = present
            if (present) onRestoreConfirmed() else onRemovalConfirmed()
        }
        releaseWake()
    }

    private fun cancelPending() {
        pendingCheck?.let { handler.removeCallbacks(it) }
        pendingCheck = null
        releaseWake()
    }

    private fun releaseWake() {
        if (wakeLock.isHeld) wakeLock.release()
    }

    fun start() {
        SpikeLog.add(ctx, "SIM: monitoring baseline id=$baselineId; now ${describe()}")
        sm.addOnSubscriptionsChangedListener(executor, listener)
        evaluate("start")   // covers a SIM already missing after a service restart
    }

    fun stop() {
        cancelPending()
        sm.removeOnSubscriptionsChangedListener(listener)
        SpikeLog.add(ctx, "SIM: monitoring stopped")
    }
}