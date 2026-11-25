package com.example.anti_theft_protection.sim

import android.content.Context
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.example.anti_theft_protection.state.SpikeLog

class SimMonitor(private val ctx: Context) {
    private val sm = ctx.getSystemService(SubscriptionManager::class.java)
    private val tm = ctx.getSystemService(TelephonyManager::class.java)
    private val executor = ContextCompat.getMainExecutor(ctx)

    private val listener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
        override fun onSubscriptionsChanged() {
            SpikeLog.add(ctx, "CALLBACK onSubscriptionsChanged -> ${describe()}")
        }
    }

    fun describe(): String = try {
        val subs = sm.activeSubscriptionInfoList.orEmpty()
        val ids = subs.joinToString { "id=${it.subscriptionId}/slot=${it.simSlotIndex}" }
        "activeSubs=${subs.size} [$ids] simState(slot0)=${tm.getSimState(0)}"
    } catch (e: SecurityException) {
        "NO READ_PHONE_STATE PERMISSION"
    }

    fun start() {
        SpikeLog.add(ctx, "START baseline: ${describe()}")
        sm.addOnSubscriptionsChangedListener(executor, listener)
    }

    fun stop() {
        sm.removeOnSubscriptionsChangedListener(listener)
        SpikeLog.add(ctx, "STOP")
    }
}