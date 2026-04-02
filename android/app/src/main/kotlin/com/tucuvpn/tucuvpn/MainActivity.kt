package com.tucuvpn.tucuvpn

import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    companion object {
        private const val LAUNCHER_CHANNEL = "com.tucuvpn.tucuvpn/launcher"
        private const val VPN_CHANNEL     = "com.tucuvpn.tucuvpn/vpn"
        private const val VPN_STAGE_CHANNEL = "com.tucuvpn.tucuvpn/vpnstage"
    }

    private lateinit var vpnHelper: VpnHelper

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VpnHelper.VPN_REQUEST_CODE) {
            vpnHelper.onPermissionResult(resultCode == RESULT_OK)
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        vpnHelper = VpnHelper(this)

        // ── VPN stage event stream (ics-openvpn → Flutter) ─────────────────────
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, VPN_STAGE_CHANNEL)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                    vpnHelper.eventSink = events
                }
                override fun onCancel(arguments: Any?) {
                    vpnHelper.eventSink = null
                }
            })

        // ── VPN control (Flutter → ics-openvpn) ───────────────────────────────
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, VPN_CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "connect" -> {
                        val config = call.argument<String>("config")
                        val name   = call.argument<String>("name") ?: "TucuVPN"
                        if (config == null) {
                            result.error("INVALID_ARG", "config is required", null)
                            return@setMethodCallHandler
                        }
                        vpnHelper.connect(config, name)
                        result.success(null)
                    }
                    "disconnect" -> {
                        vpnHelper.disconnect()
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            }

        // ── App launcher + toast ───────────────────────────────────────────────
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, LAUNCHER_CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "launchApp" -> {
                        val packageName = call.argument<String>("packageName")
                        if (packageName == null) {
                            result.error("INVALID_ARG", "packageName is required", null)
                            return@setMethodCallHandler
                        }
                        result.success(launchApp(packageName))
                    }
                    "showToast" -> {
                        val message = call.argument<String>("message") ?: ""
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
                        }
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            }
    }

    /**
     * Mirrors AutoVPN's launch approach — tries three strategies in order:
     *   1. getLeanbackLaunchIntentForPackage  (TV launcher resolution)
     *   2. queryIntentActivities / LEANBACK_LAUNCHER  (explicit component)
     *   3. getLaunchIntentForPackage  (phone/tablet fallback)
     */
    private fun launchApp(packageName: String): Boolean {
        return try {
            val intent = packageManager.getLeanbackLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return true
            }

            val leanbackIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory("android.intent.category.LEANBACK_LAUNCHER")
                setPackage(packageName)
            }
            val activities = packageManager.queryIntentActivities(leanbackIntent, 0)
            if (activities.isNotEmpty()) {
                val ai = activities[0].activityInfo
                val launchIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory("android.intent.category.LEANBACK_LAUNCHER")
                    component = ComponentName(ai.packageName, ai.name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(launchIntent)
                return true
            }

            val regularIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (regularIntent != null) {
                regularIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(regularIntent)
                return true
            }

            false
        } catch (e: Exception) {
            false
        }
    }
}
