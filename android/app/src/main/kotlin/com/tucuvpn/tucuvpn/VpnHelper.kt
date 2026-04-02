package com.tucuvpn.tucuvpn

import android.app.Activity
import android.net.VpnService
import de.blinkt.openvpn.OnVPNStatusChangeListener
import de.blinkt.openvpn.VPNHelper
import io.flutter.plugin.common.EventChannel

/**
 * Thin wrapper around nizwar's VPNHelper that bridges ics-openvpn to Flutter.
 *
 * Call [connect] to start a VPN session. If the system VPN permission dialog
 * needs to be shown, [connect] fires startActivityForResult(VPN_REQUEST_CODE)
 * and returns; call [onPermissionResult] from MainActivity.onActivityResult
 * to resume. Stage updates are emitted on [eventSink] as lowercase strings
 * (e.g. "connected", "connecting", "disconnected", "auth_failed", "error").
 *
 * All risky calls are wrapped in try/catch so exceptions are surfaced as
 * "error" stage events to Dart rather than crashes or silent no-ops. This
 * ensures the Dart failover timer (not the immediate catch path) handles
 * server-switching at the correct 20-second cadence.
 */
class VpnHelper(private val activity: Activity) {

    companion object {
        const val VPN_REQUEST_CODE = 24
    }

    private val helper = VPNHelper(activity)

    // Held while waiting for the user's VPN-permission dialog response.
    private var pendingConfig: String? = null
    private var pendingName: String? = null

    /** Set by MainActivity when the EventChannel listener is attached. */
    var eventSink: EventChannel.EventSink? = null

    init {
        helper.setOnVPNStatusChangeListener(object : OnVPNStatusChangeListener {
            override fun onVPNStatusChanged(status: String) {
                activity.runOnUiThread {
                    eventSink?.success(status.lowercase())
                }
            }

            override fun onConnectionStatusChanged(
                duration: String,
                lastPacketReceive: String,
                byteIn: String,
                byteOut: String,
            ) { /* byte-count updates — not needed by TucuVPN */ }
        })
    }

    /**
     * Starts the VPN. If the system needs to show the permission dialog first,
     * the actual connect is deferred until [onPermissionResult] is called.
     * Any exception is caught and emitted as an "error" stage event so Dart
     * can failover via the normal EventChannel path (not via PlatformException).
     */
    fun connect(config: String, name: String) {
        try {
            val permissionIntent = VpnService.prepare(activity)
            if (permissionIntent != null) {
                pendingConfig = config
                pendingName = name
                activity.startActivityForResult(permissionIntent, VPN_REQUEST_CODE)
            } else {
                startVpn(config, name)
            }
        } catch (e: Exception) {
            activity.runOnUiThread { eventSink?.success("error") }
        }
    }

    /**
     * Forward the result of the VPN-permission dialog from onActivityResult.
     * Fully guarded: null pending state and startVpn exceptions both emit
     * "error" so Dart is never left waiting with no stage update.
     */
    fun onPermissionResult(granted: Boolean) {
        if (granted) {
            val config = pendingConfig
            val name   = pendingName
            if (config != null && name != null) {
                startVpn(config, name)
            } else {
                // Pending data lost between grant and resume — signal Dart to failover.
                activity.runOnUiThread { eventSink?.success("error") }
            }
        } else {
            activity.runOnUiThread { eventSink?.success("denied") }
        }
        pendingConfig = null
        pendingName   = null
    }

    /**
     * Calls nizwar's VPNHelper.startVPN(). Any exception (config parse error,
     * service not found, etc.) is caught and emitted as "error" so the Dart
     * failover timer — already armed at this point — fires at the normal
     * 20-second interval instead of triggering an immediate PlatformException.
     */
    private fun startVpn(config: String, name: String) {
        try {
            // VPN Gate / SoftEther servers use username "vpn" / password "vpn".
            helper.startVPN(config, "vpn", "vpn", name, null)
        } catch (e: Exception) {
            activity.runOnUiThread { eventSink?.success("error") }
        }
    }

    fun disconnect() {
        try { helper.stopVPN() } catch (_: Exception) {}
        activity.runOnUiThread { eventSink?.success("disconnected") }
    }
}
