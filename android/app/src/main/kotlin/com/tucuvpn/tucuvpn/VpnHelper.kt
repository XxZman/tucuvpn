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
 * (e.g. "connected", "connecting", "disconnected", "auth_failed").
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
     */
    fun connect(config: String, name: String) {
        val permissionIntent = VpnService.prepare(activity)
        if (permissionIntent != null) {
            pendingConfig = config
            pendingName = name
            activity.startActivityForResult(permissionIntent, VPN_REQUEST_CODE)
        } else {
            startVpn(config, name)
        }
    }

    /** Forward the result of the VPN-permission dialog from onActivityResult. */
    fun onPermissionResult(granted: Boolean) {
        if (granted) {
            val config = pendingConfig
            val name = pendingName
            if (config != null && name != null) startVpn(config, name)
        } else {
            activity.runOnUiThread { eventSink?.success("denied") }
        }
        pendingConfig = null
        pendingName = null
    }

    private fun startVpn(config: String, name: String) {
        // VPN Gate / SoftEther servers use username "vpn" / password "vpn".
        helper.startVPN(config, "vpn", "vpn", name, null)
    }

    fun disconnect() {
        try { helper.stopVPN() } catch (_: Exception) {}
        activity.runOnUiThread { eventSink?.success("disconnected") }
    }
}
