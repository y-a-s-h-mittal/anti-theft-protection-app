package com.example.anti_theft_protection.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.example.anti_theft_protection.state.SpikeLog

/**
 * Loops the alarm on the ALARM stream and keeps that stream at maximum volume.
 * Pass autoStopMs for the short owner-triggered test.
 */
class AlarmPlayer(private val ctx: Context) {
    companion object {
        private const val TICK_MS = 250L
        private const val RAW_NAME = "atp_alarm"   // res/raw/atp_alarm.mp3
    }

    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val wakeLock = ctx.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "antitheft:alarm")
        .apply { setReferenceCounted(false) }

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(attrs)
        .setOnAudioFocusChangeListener { change ->
            // An alarm deliberately ignores focus loss; we only record it.
            SpikeLog.add(ctx, "ALARM: audio focus change=$change (ignored)")
        }
        .build()

    private var player: MediaPlayer? = null
    private var savedVolume: Int? = null
    private var active = false
    private var volumeWarned = false

    val isPlaying: Boolean get() = active

    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            forceMaxVolume()
            wakeLock.acquire(30_000L)   // refreshed every tick; expires by itself if this loop ever dies
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun start(autoStopMs: Long? = null) {
        if (active) return
        active = true
        volumeWarned = false
        wakeLock.acquire(30_000L)
        savedVolume = runCatching { audio.getStreamVolume(AudioManager.STREAM_ALARM) }.getOrNull()
        val focus = runCatching { audio.requestAudioFocus(focusRequest) }.getOrElse { -1 }
        SpikeLog.add(ctx, "ALARM: audio focus result=$focus (1=granted, 0=failed)")
        forceMaxVolume()
        startPlayer()
        handler.post(tick)
        if (autoStopMs != null) handler.postDelayed({ stop() }, autoStopMs)
    }

    fun stop() {
        if (!active) return
        active = false
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
        runCatching { audio.abandonAudioFocusRequest(focusRequest) }
        savedVolume?.let { v -> runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, v, 0) } }
        savedVolume = null
        if (wakeLock.isHeld) wakeLock.release()
        SpikeLog.add(ctx, "ALARM: stopped")
    }

    private fun forceMaxVolume() {
        try {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            if (audio.getStreamVolume(AudioManager.STREAM_ALARM) != max) {
                audio.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
                if (audio.getStreamVolume(AudioManager.STREAM_ALARM) != max) warnVolume("change had no effect")
            }
        } catch (e: Exception) {
            warnVolume("API failed: ${e.javaClass.simpleName}")
        }
    }

    private fun warnVolume(why: String) {
        if (volumeWarned) return
        volumeWarned = true
        SpikeLog.add(ctx, "ALARM: WARNING volume control restricted ($why)")
    }

    private fun defaultUri(): Uri? =
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

    private fun startPlayer() {
        releasePlayer()
        var mp: MediaPlayer? = null
        try {
            mp = MediaPlayer()
            mp.setAudioAttributes(attrs)
            val resId = ctx.resources.getIdentifier(RAW_NAME, "raw", ctx.packageName)
            val source: String
            if (resId != 0) {
                ctx.resources.openRawResourceFd(resId).use {
                    mp.setDataSource(it.fileDescriptor, it.startOffset, it.length)
                }
                source = "bundled $RAW_NAME"
            } else {
                val uri = defaultUri() ?: throw IllegalStateException("no alarm tone available")
                mp.setDataSource(ctx, uri)
                source = "system alarm tone"
            }
            mp.isLooping = true
            mp.setOnErrorListener { _, what, extra ->
                SpikeLog.add(ctx, "ALARM: player error what=$what extra=$extra, restarting")
                handler.postDelayed({ if (active) startPlayer() }, 500L)
                true
            }
            mp.prepare()
            mp.start()
            player = mp
            val cur = runCatching { audio.getStreamVolume(AudioManager.STREAM_ALARM) }.getOrDefault(-1)
            val max = runCatching { audio.getStreamMaxVolume(AudioManager.STREAM_ALARM) }.getOrDefault(-1)
            SpikeLog.add(ctx, "ALARM: playing ($source), alarm volume $cur/$max")
        } catch (e: Exception) {
            runCatching { mp?.release() }
            SpikeLog.add(ctx, "ALARM: failed to start player: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun releasePlayer() {
        player?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        player = null
    }
}