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
     * Tries three strategies in order to launch [packageName]:
     *   1. CATEGORY_LEANBACK_LAUNCHER  — primary for Android TV apps
     *   2. CATEGORY_LAUNCHER           — fallback for hybrid / phone apps
     *   3. Package name only           — last resort, no category
     * Returns true on success, false if nothing worked.
     */
    private fun launchApp(packageName: String): Boolean {
        val pm: PackageManager = packageManager

        // ── Strategy 1: LEANBACK_LAUNCHER (Android TV / Fire TV) ─────────────
        pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                setPackage(packageName)
            }, 0
        ).firstOrNull()?.activityInfo?.let { info ->
            return try {
                startActivity(Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    component = ComponentName(info.packageName, info.name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                true
            } catch (e: Exception) {
                false
            }
        }

        // ── Strategy 2: CATEGORY_LAUNCHER (standard / hybrid apps) ───────────
        pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(packageName)
            }, 0
        ).firstOrNull()?.activityInfo?.let { info ->
            return try {
                startActivity(Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    component = ComponentName(info.packageName, info.name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                true
            } catch (e: Exception) {
                false
            }
        }

        // ── Strategy 3: package name only, no category ────────────────────────
        return try {
            startActivity(Intent(Intent.ACTION_MAIN).apply {
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            true
        } catch (e: Exception) {
            false
        }
    }
}
