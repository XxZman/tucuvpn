package com.tucuvpn.tucuvpn

import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    companion object {
        private const val LAUNCHER_CHANNEL = "com.tucuvpn.tucuvpn/launcher"
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

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