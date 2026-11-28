package com.example.anti_theft_protection.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.example.anti_theft_protection.state.SpikeLog

/**
 * Loops the alarm on the ALARM stream and keeps that stream at maximum volume.
 * Pass autoStopMs for the short owner-triggered test.
 */
class AlarmPlayer(private val ctx: Context) {
    companion object {
        private const val TICK_MS = 100L
        private const val WAKE_REFRESH_TICKS = 100   // renew the wake lock every ~10 s
        private const val RAW_NAME = "atp_alarm"     // res/raw/atp_alarm.<any extension>
        // Not a public SDK constant, but a long-standing system broadcast sent on every volume change.
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
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
    private var ticks = 0
    private var volumeErrorLogged = false
    private var receiverRegistered = false

    val isPlaying: Boolean get() = active

    /** Reacts the instant the system reports an alarm-stream volume change. */
    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (active && intent?.getIntExtra(EXTRA_STREAM_TYPE, -1) == AudioManager.STREAM_ALARM) {
                forceMaxVolume()
            }
        }
    }

    /** Backup in case the broadcast is ever not delivered. */
    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            forceMaxVolume()
            if (++ticks % WAKE_REFRESH_TICKS == 0) wakeLock.acquire(30_000L)
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun start(autoStopMs: Long? = null) {
        if (active) return
        active = true
        ticks = 0
        volumeErrorLogged = false
        wakeLock.acquire(30_000L)
        savedVolume = runCatching { audio.getStreamVolume(AudioManager.STREAM_ALARM) }.getOrNull()
        val focus = runCatching { audio.requestAudioFocus(focusRequest) }.getOrElse { -1 }
        SpikeLog.add(ctx, "ALARM: audio focus result=$focus (1=granted, 0=failed)")

        // The only place a "restricted" warning is judged: nobody is touching the keys yet.
        if (!forceMaxVolume()) {
            SpikeLog.add(ctx, "ALARM: WARNING could not raise alarm volume to max at start (restricted?)")
        }
        registerVolumeReceiver()
        startPlayer()
        handler.post(tick)
        if (autoStopMs != null) handler.postDelayed({ stop() }, autoStopMs)
    }

    fun stop() {
        if (!active) return
        active = false
        handler.removeCallbacksAndMessages(null)
        unregisterVolumeReceiver()
        releasePlayer()
        runCatching { audio.abandonAudioFocusRequest(focusRequest) }
        savedVolume?.let { v -> runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, v, 0) } }
        savedVolume = null
        if (wakeLock.isHeld) wakeLock.release()
        SpikeLog.add(ctx, "ALARM: stopped")
    }

    /** Returns true if the alarm stream is at maximum afterwards. */
    private fun forceMaxVolume(): Boolean = try {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        if (audio.getStreamVolume(AudioManager.STREAM_ALARM) != max) {
            audio.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
        }
        audio.getStreamVolume(AudioManager.STREAM_ALARM) == max
    } catch (e: Exception) {
        if (!volumeErrorLogged) {
            volumeErrorLogged = true
            SpikeLog.add(ctx, "ALARM: volume API failed: ${e.javaClass.simpleName}")
        }
        false
    }

    private fun registerVolumeReceiver() {
        if (receiverRegistered) return
        ContextCompat.registerReceiver(
            ctx, volumeReceiver, IntentFilter(VOLUME_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    private fun unregisterVolumeReceiver() {
        if (!receiverRegistered) return
        runCatching { ctx.unregisterReceiver(volumeReceiver) }
        receiverRegistered = false
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