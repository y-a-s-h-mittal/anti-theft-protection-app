package com.example.anti_theft_protection.state

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class VerifyResult(val ok: Boolean, val lockedMs: Long = 0L)

/** Salted PBKDF2 PIN hash with persisted retry lockout. The PIN itself is never stored or logged. */
object PinStore {
    private const val MIN_LEN = 4
    private const val MAX_LEN = 8
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val FREE_ATTEMPTS = 5
    private const val BASE_LOCK_MS = 30_000L
    private const val MAX_LOCK_MS = 15 * 60_000L

    private const val PREFS = "pin_store"
    private const val K_SALT = "salt"
    private const val K_HASH = "hash"
    private const val K_FAILS = "fails"
    private const val K_LOCK_START = "lock_start"   // SystemClock.elapsedRealtime() base
    private const val K_LOCK_DUR = "lock_dur"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isValidFormat(pin: String) =
        pin.length in MIN_LEN..MAX_LEN && pin.all { it in '0'..'9' }

    fun hasPin(ctx: Context) = prefs(ctx).contains(K_HASH)

    fun setPin(ctx: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs(ctx).edit()
            .putString(K_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(K_HASH, Base64.encodeToString(hash(pin, salt), Base64.NO_WRAP))
            .putInt(K_FAILS, 0)
            .remove(K_LOCK_START)
            .remove(K_LOCK_DUR)
            .apply()
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_BITS)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    /** Milliseconds of lockout left. After a reboot the clock restarts, so the full lock is applied again. */
    fun lockRemainingMs(ctx: Context): Long {
        val p = prefs(ctx)
        val dur = p.getLong(K_LOCK_DUR, 0L)
        if (dur == 0L) return 0L
        val start = p.getLong(K_LOCK_START, 0L)
        val now = SystemClock.elapsedRealtime()
        return if (now < start) dur else (start + dur - now).coerceAtLeast(0L)
    }

    fun verify(ctx: Context, pin: String): VerifyResult {
        val locked = lockRemainingMs(ctx)
        if (locked > 0) return VerifyResult(false, locked)   // attempts during a lock are not even checked

        val p = prefs(ctx)
        val salt = p.getString(K_SALT, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
        val stored = p.getString(K_HASH, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
        if (salt == null || stored == null) return VerifyResult(false)

        if (MessageDigest.isEqual(hash(pin, salt), stored)) {   // constant-time compare
            p.edit().putInt(K_FAILS, 0).remove(K_LOCK_START).remove(K_LOCK_DUR).apply()
            return VerifyResult(true)
        }

        val fails = p.getInt(K_FAILS, 0) + 1
        val e = p.edit().putInt(K_FAILS, fails)
        var lockMs = 0L
        if (fails >= FREE_ATTEMPTS) {
            lockMs = minOf(BASE_LOCK_MS shl (fails - FREE_ATTEMPTS).coerceAtMost(10), MAX_LOCK_MS)
            e.putLong(K_LOCK_START, SystemClock.elapsedRealtime()).putLong(K_LOCK_DUR, lockMs)
        }
        e.apply()
        return VerifyResult(false, lockMs)
    }
}