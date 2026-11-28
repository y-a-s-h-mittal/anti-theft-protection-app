package com.example.anti_theft_protection.bridge

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.anti_theft_protection.service.MonitoringService
import com.example.anti_theft_protection.sim.SimMonitor
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
    }

    private var pendingPermResult: MethodChannel.Result? = null
    private val required = arrayOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.POST_NOTIFICATIONS
    )

    fun register(messenger: BinaryMessenger) {
        MethodChannel(messenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "requestPermissions" -> requestPermissions(result)
                "arm" -> arm(result)
                "disarm" -> disarm(result)
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
        "countdownSeconds" to ProtectionStore.getCountdownSeconds(activity)
    )

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

    private fun disarm(result: MethodChannel.Result) {
        // TEMP (Stage 1B): disarming from ALARMING needs no PIN yet. Stage 1C removes this.
        val before = ProtectionStore.getState(activity)
        ProtectionStore.disarm(activity)
        activity.stopService(Intent(activity, MonitoringService::class.java))
        SpikeLog.add(activity, "STATE $before -> DISARMED (owner)")
        result.success(status())
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