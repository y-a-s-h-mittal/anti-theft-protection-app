package com.example.anti_theft_protection.state

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*

object SpikeLog {
    private const val PREFS = "spike_log"
    private const val KEY = "lines"

    fun add(ctx: Context, msg: String) {
        Log.d("SimSpike", msg)
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val lines = (p.getString(KEY, "")!!.lines().filter { it.isNotBlank() } + "$ts $msg").takeLast(100)
        p.edit().putString(KEY, lines.joinToString("\n")).apply()
    }

    fun read(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""

    fun clear(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
}