package com.example.anti_theft_protection

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.anti_theft_protection.service.MonitoringService
import com.example.anti_theft_protection.state.SpikeLog
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private var pendingResult: MethodChannel.Result? = null
    private val required = arrayOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.POST_NOTIFICATIONS
    )

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "anti_theft/native")
            .setMethodCallHandler { call, result ->
                val svc = Intent(this, MonitoringService::class.java)
                when (call.method) {
                    "requestPermissions" -> {
                        val missing = required.filter {
                            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
                        }
                        if (missing.isEmpty()) {
                            result.success(true)
                        } else {
                            pendingResult = result
                            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
                        }
                    }
                    "startService" -> { ContextCompat.startForegroundService(this, svc); result.success(true) }
                    "stopService" -> { stopService(svc); result.success(true) }
                    "isRunning" -> result.success(MonitoringService.running)
                    "getLog" -> result.success(SpikeLog.read(this))
                    "clearLog" -> { SpikeLog.clear(this); result.success(null) }
                    else -> result.notImplemented()
                }
            }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            pendingResult?.success(grantResults.all { it == PackageManager.PERMISSION_GRANTED })
            pendingResult = null
        }
    }
}