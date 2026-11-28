package com.example.anti_theft_protection.bridge

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.anti_theft_protection.alarm.AlarmPlayer
import com.example.anti_theft_protection.service.MonitoringService
import com.example.anti_theft_protection.sim.SimMonitor
import com.example.anti_theft_protection.state.PinStore
import com.example.anti_theft_protection.state.ProtectionState
import com.example.anti_theft_protection.state.ProtectionStore
import com.example.anti_theft_protection.state.SpikeLog
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

class ChannelHandler(private val activity: Activity) {
    companion object {
        const val CHANNEL = "anti_theft/native"
        private const val REQ_PERMS = 100
        private const val TEST_ALARM_MS = 5_000L
    }

    private var pendingPermResult: MethodChannel.Result? = null
    private val required = arrayOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.POST_NOTIFICATIONS
    )
    private val testPlayer by lazy { AlarmPlayer(activity.applicationContext) }

    fun register(messenger: BinaryMessenger) {
        MethodChannel(messenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "requestPermissions" -> requestPermissions(result)
                "arm" -> arm(result)
                "disarm", "stopAlarm" -> disarm(call, result)   // same operation: PIN, then DISARMED
                "setPin" -> setPin(call, result)
                "pinStatus" -> result.success(
                    mapOf(
                        "lockedMs" to PinStore.lockRemainingMs(activity),
                        "attemptsLeft" to PinStore.attemptsLeft(activity)
                    )
                )
                "testAlarm" -> testAlarm(result)
                "setCountdownSeconds" -> setCountdownSeconds(call, result)
                "getStatus" -> result.success(status())
                "getLog" -> result.success(SpikeLog.read(activity))
                "clearLog" -> { SpikeLog.clear(activity); result.success(null) }
                else -> result.notImplemented()
            }
        }
    }

    private fun status(): Map<String, Any> = mapOf(
        "state" to ProtectionStore.getState(activity).name,
        "serviceRunning" to MonitoringService.running,
        "remainingMs" to ProtectionStore.remainingMs(activity),
        "countdownSeconds" to ProtectionStore.getCountdownSeconds(activity),
        "hasPin" to PinStore.hasPin(activity)
    )

    private fun auth(ok: Boolean, lockedMs: Long = 0L, attemptsLeft: Int = 0): Map<String, Any> =
        mapOf("ok" to ok, "lockedMs" to lockedMs, "attemptsLeft" to attemptsLeft)

    private fun missingPermissions() = required.filter {
        ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions(result: MethodChannel.Result) {
        val missing = missingPermissions()
        if (missing.isEmpty()) {
            result.success(true)
        } else {
            pendingPermResult = result
            ActivityCompat.requestPermissions(activity, missing.toTypedArray(), REQ_PERMS)
        }
    }

    fun onPermissionsResult(requestCode: Int, grantResults: IntArray) {
        if (requestCode == REQ_PERMS) {
            pendingPermResult?.success(grantResults.all { it == PackageManager.PERMISSION_GRANTED })
            pendingPermResult = null
        }
    }

    private fun arm(result: MethodChannel.Result) {
        if (ProtectionStore.getState(activity) != ProtectionState.DISARMED) {
            result.error("ALREADY_ACTIVE", "Protection is already active", null); return
        }
        if (!PinStore.hasPin(activity)) {
            result.error("NO_PIN", "Set a PIN first", null); return
        }
        if (missingPermissions().isNotEmpty()) {
            result.error("PERMISSION", "Phone and notification permissions are required", null); return
        }
        val ids = SimMonitor.readActiveIds(activity)
        if (ids == null) { result.error("NO_ACCESS", "Cannot read SIM state", null); return }
        if (ids.isEmpty()) { result.error("NO_SIM", "No active SIM found to monitor", null); return }
        if (ids.size > 1) { result.error("MULTI_SIM", "Only single-SIM phones are supported so far", null); return }

        val baseline = ids.first()
        ProtectionStore.arm(activity, baseline)
        SpikeLog.add(activity, "STATE DISARMED -> ARMED (baseline id=$baseline)")
        ContextCompat.startForegroundService(activity, Intent(activity, MonitoringService::class.java))
        result.success(status())
    }

    private fun disarm(call: MethodCall, result: MethodChannel.Result) {
        val before = ProtectionStore.getState(activity)
        if (before == ProtectionState.DISARMED) { result.success(auth(true)); return }

        val v = PinStore.verify(activity, call.argument<String>("pin") ?: "")
        if (!v.ok) {
            // Never reveal how close a wrong PIN was.
            SpikeLog.add(activity, if (v.lockedMs > 0) "AUTH: rejected, locked ${v.lockedMs / 1000}s" else "AUTH: wrong PIN")
            result.success(auth(false, v.lockedMs, v.attemptsLeft)); return
        }
        ProtectionStore.disarm(activity)
        activity.stopService(Intent(activity, MonitoringService::class.java))   // service onDestroy stops the alarm
        SpikeLog.add(activity, "STATE $before -> DISARMED (PIN accepted)")
        result.success(auth(true))
    }

    private fun setPin(call: MethodCall, result: MethodChannel.Result) {
        if (ProtectionStore.getState(activity) != ProtectionState.DISARMED) {
            result.error("NOT_DISARMED", "Disarm first to change the PIN", null); return
        }
        val pin = call.argument<String>("pin") ?: ""
        if (!PinStore.isValidFormat(pin)) {
            result.error("BAD_PIN", "PIN must be 4-8 digits", null); return
        }
        if (PinStore.hasPin(activity)) {   // changing an existing PIN needs the old one
            val v = PinStore.verify(activity, call.argument<String>("oldPin") ?: "")
            if (!v.ok) {
                SpikeLog.add(activity, if (v.lockedMs > 0) "AUTH: PIN change rejected, locked ${v.lockedMs / 1000}s" else "AUTH: PIN change, wrong current PIN")
                result.success(auth(false, v.lockedMs, v.attemptsLeft)); return
            }
        }
        PinStore.setPin(activity, pin)
        SpikeLog.add(activity, "PIN saved")
        result.success(auth(true))
    }

    private fun testAlarm(result: MethodChannel.Result) {
        if (ProtectionStore.getState(activity) != ProtectionState.DISARMED) {
            result.error("NOT_DISARMED", "Test alarm only works while disarmed", null); return
        }
        testPlayer.start(autoStopMs = TEST_ALARM_MS)
        result.success(null)
    }

    private fun setCountdownSeconds(call: MethodCall, result: MethodChannel.Result) {
        if (ProtectionStore.getState(activity) != ProtectionState.DISARMED) {
            result.error("NOT_DISARMED", "Disarm first to change the countdown", null); return
        }
        val seconds = call.argument<Int>("seconds")
        if (seconds == null) { result.error("BAD_ARGS", "seconds is required", null); return }
        ProtectionStore.setCountdownSeconds(activity, seconds)
        result.success(status())
    }
}