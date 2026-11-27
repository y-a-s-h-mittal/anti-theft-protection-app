package com.example.anti_theft_protection.state

import android.content.Context

enum class ProtectionState { DISARMED, ARMED, COUNTDOWN, ALARMING }

/** Persists the state machine so it survives process death. Only the minimum is stored. */
object ProtectionStore {
    private const val PREFS = "protection_state"
    private const val K_STATE = "state"
    private const val K_BASELINE = "baseline_sub_id"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getState(ctx: Context): ProtectionState =
        runCatching { ProtectionState.valueOf(prefs(ctx).getString(K_STATE, null) ?: "") }
            .getOrDefault(ProtectionState.DISARMED)

    fun setState(ctx: Context, s: ProtectionState) {
        prefs(ctx).edit().putString(K_STATE, s.name).apply()
    }

    fun getBaselineId(ctx: Context): Int = prefs(ctx).getInt(K_BASELINE, -1)

    fun arm(ctx: Context, baselineId: Int) {
        prefs(ctx).edit()
            .putString(K_STATE, ProtectionState.ARMED.name)
            .putInt(K_BASELINE, baselineId)
            .apply()
    }

    fun disarm(ctx: Context) {
        prefs(ctx).edit()
            .putString(K_STATE, ProtectionState.DISARMED.name)
            .remove(K_BASELINE)
            .apply()
    }
}