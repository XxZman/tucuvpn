package com.tucuvpn.tucuvpn

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    companion object {
        private const val CHANNEL = "com.tucuvpn.tucuvpn/launcher"
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                if (call.method == "launchApp") {
                    val packageName = call.argument<String>("packageName")
                    if (packageName == null) {
                        result.error("INVALID_ARG", "packageName is required", null)
                        return@setMethodCallHandler
                    }
                    result.success(launchApp(packageName))
                } else {
                    result.notImplemented()
                }
            }
    }

    /**
     * Mirrors AutoVPN's launch approach. Tries three strategies in order:
     *   1. getLeanbackLaunchIntentForPackage — built-in TV launcher resolution
     *   2. queryIntentActivities with LEANBACK_LAUNCHER — explicit component
     *   3. getLaunchIntentForPackage — regular LAUNCHER fallback
     * Returns true on success, false if nothing worked.
     */
    private fun launchApp(packageName: String): Boolean {
        return try {
            // ── Strategy 1: getLeanbackLaunchIntentForPackage (TV apps) ──────────
            val intent = packageManager.getLeanbackLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return true
            }

            // ── Strategy 2: queryIntentActivities with LEANBACK_LAUNCHER ─────────
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

            // ── Strategy 3: regular LAUNCHER fallback ─────────────────────────────
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
