package com.example.anti_theft_protection.state

import android.content.Context
import android.os.SystemClock

enum class ProtectionState { DISARMED, ARMED, COUNTDOWN, ALARMING }

/** Persists the state machine so it survives process death. Only the minimum is stored. */
object ProtectionStore {
    const val MIN_COUNTDOWN_S = 5
    const val MAX_COUNTDOWN_S = 60
    const val DEFAULT_COUNTDOWN_S = 10

    private const val PREFS = "protection_state"
    private const val K_STATE = "state"
    private const val K_BASELINE = "baseline_sub_id"
    private const val K_DURATION = "countdown_seconds"
    private const val K_DEADLINE = "countdown_deadline"   // SystemClock.elapsedRealtime() base

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getState(ctx: Context): ProtectionState =
        runCatching { ProtectionState.valueOf(prefs(ctx).getString(K_STATE, null) ?: "") }
            .getOrDefault(ProtectionState.DISARMED)

    /** Invariant: a deadline is stored only while the state is COUNTDOWN. */
    fun setState(ctx: Context, s: ProtectionState) {
        val e = prefs(ctx).edit().putString(K_STATE, s.name)
        if (s != ProtectionState.COUNTDOWN) e.remove(K_DEADLINE)
        e.apply()
    }

    fun startCountdown(ctx: Context, deadlineElapsed: Long) {
        prefs(ctx).edit()
            .putString(K_STATE, ProtectionState.COUNTDOWN.name)
            .putLong(K_DEADLINE, deadlineElapsed)
            .apply()
    }

    fun getDeadline(ctx: Context): Long = prefs(ctx).getLong(K_DEADLINE, 0L)

    fun getCountdownSeconds(ctx: Context): Int =
        prefs(ctx).getInt(K_DURATION, DEFAULT_COUNTDOWN_S).coerceIn(MIN_COUNTDOWN_S, MAX_COUNTDOWN_S)

    fun setCountdownSeconds(ctx: Context, seconds: Int) {
        prefs(ctx).edit()
            .putInt(K_DURATION, seconds.coerceIn(MIN_COUNTDOWN_S, MAX_COUNTDOWN_S))
            .apply()
    }

    /** Milliseconds left in the countdown (0 if not counting down). */
    fun remainingMs(ctx: Context): Long {
        if (getState(ctx) != ProtectionState.COUNTDOWN) return 0L
        val maxMs = getCountdownSeconds(ctx) * 1000L
        return (getDeadline(ctx) - SystemClock.elapsedRealtime()).coerceIn(0L, maxMs)
    }

    fun getBaselineId(ctx: Context): Int = prefs(ctx).getInt(K_BASELINE, -1)

    fun arm(ctx: Context, baselineId: Int) {
        prefs(ctx).edit()
            .putString(K_STATE, ProtectionState.ARMED.name)
            .putInt(K_BASELINE, baselineId)
            .remove(K_DEADLINE)
            .apply()
    }

    fun disarm(ctx: Context) {
        prefs(ctx).edit()
            .putString(K_STATE, ProtectionState.DISARMED.name)
            .remove(K_BASELINE)
            .remove(K_DEADLINE)
            .apply()
    }
}