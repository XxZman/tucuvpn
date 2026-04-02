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
     * Finds the launcher Activity for [packageName] via PackageManager and
     * starts it with FLAG_ACTIVITY_NEW_TASK — identical to how a TV launcher
     * would open an app.  Returns true on success, false if the app is not
     * found or cannot be started.
     */
    private fun launchApp(packageName: String): Boolean {
        return try {
            val pm: PackageManager = packageManager

            // Build an intent that matches what the launcher broadcasts
            val queryIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(packageName)
            }

            val activities = pm.queryIntentActivities(queryIntent, 0)

            if (activities.isEmpty()) {
                // Also try LEANBACK_LAUNCHER for Android TV apps
                val tvQueryIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    setPackage(packageName)
                }
                val tvActivities = pm.queryIntentActivities(tvQueryIntent, 0)

                if (tvActivities.isEmpty()) return false

                val activityInfo = tvActivities[0].activityInfo
                val launchIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    component = ComponentName(activityInfo.packageName, activityInfo.name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(launchIntent)
            } else {
                val activityInfo = activities[0].activityInfo
                val launchIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    component = ComponentName(activityInfo.packageName, activityInfo.name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(launchIntent)
            }

            true
        } catch (e: Exception) {
            false
        }
    }
}
