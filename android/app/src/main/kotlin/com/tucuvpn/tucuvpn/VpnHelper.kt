package com.tucuvpn.tucuvpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VPNLaunchHelper
import de.blinkt.openvpn.core.VpnStatus
import io.flutter.plugin.common.EventChannel
import java.io.StringReader

/**
 * Direct ics-openvpn integration (de.blinkt.openvpn.core.*).
 *
 * Lifecycle:
 *   1. [connect] parses the .ovpn string, builds a VpnProfile, requests VPN
 *      permission if needed, then calls [VPNLaunchHelper.startOpenVpn].
 *   2. If the system shows the permission dialog, [onPermissionResult] resumes
 *      the connect flow once the user responds.
 *   3. Stage events from [VpnStatus.StateListener] are forwarded to Flutter via
 *      [eventSink] as lowercase strings ("connected", "connecting", etc.).
 *   4. [disconnect] sends the DISCONNECT_VPN action to [OpenVPNService] and
 *      emits "disconnected" immediately so the Dart failover timer can reset.
 *   5. Call [cleanup] from onDestroy to remove the state listener.
 */
class VpnHelper(private val activity: Activity) {

    companion object {
        const val VPN_REQUEST_CODE = 24
    }

    /** Set by MainActivity when the EventChannel listener attaches. */
    var eventSink: EventChannel.EventSink? = null

    private var pendingConfig: String? = null
    private var pendingName: String? = null

    // ics-openvpn state listener — registered in init, removed in cleanup().
    private val stateListener = object : VpnStatus.StateListener {
        override fun updateState(
            state: String,
            logmessage: String,
            localizedResId: Int,
            level: VpnStatus.ConnectionStatus,
            intent: Intent?,
        ) {
            val mapped = when (state) {
                "CONNECTED"               -> "connected"
                "DISCONNECTED", "EXITING" -> "disconnected"
                "AUTH_FAILED"             -> "auth_failed"
                "NONETWORK"               -> "nonetwork"
                else                      -> "connecting"
            }
            activity.runOnUiThread { eventSink?.success(mapped) }
        }

        override fun setConnectedVPN(uuid: String) { /* no-op */ }
    }

    init {
        VpnStatus.addStateListener(stateListener)
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Starts the VPN. Shows the system permission dialog if needed.
     * All errors are emitted as "error:..." strings so Dart's failover timer
     * handles retry at the normal 20-second cadence.
     */
    fun connect(config: String, name: String) {
        try {
            val permissionIntent = VpnService.prepare(activity)
            if (permissionIntent != null) {
                pendingConfig = config
                pendingName   = name
                activity.startActivityForResult(permissionIntent, VPN_REQUEST_CODE)
            } else {
                startVpn(config, name)
            }
        } catch (e: Exception) {
            emitError("connect", e)
        }
    }

    /** Called from MainActivity.onActivityResult after the VPN permission dialog. */
    fun onPermissionResult(granted: Boolean) {
        if (granted) {
            val cfg  = pendingConfig
            val name = pendingName
            if (cfg != null && name != null) startVpn(cfg, name)
            else activity.runOnUiThread { eventSink?.success("error:lost pending config") }
        } else {
            activity.runOnUiThread { eventSink?.success("denied") }
        }
        pendingConfig = null
        pendingName   = null
    }

    /** Stops any active VPN tunnel. */
    fun disconnect() {
        try {
            ProfileManager.setConntectedVpnProfileDisconnected(activity)
        } catch (_: Exception) {}
        try {
            val intent = Intent(activity, OpenVPNService::class.java)
            intent.action = OpenVPNService.DISCONNECT_VPN
            activity.startService(intent)
        } catch (_: Exception) {}
        activity.runOnUiThread { eventSink?.success("disconnected") }
    }

    /** Remove the VpnStatus listener — call this from Activity.onDestroy. */
    fun cleanup() {
        VpnStatus.removeStateListener(stateListener)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun startVpn(config: String, name: String) {
        // Strip comments and blank lines — some VPN Gate / SoftEther configs
        // start with many ### header lines that confuse the parser.
        val clean = config.lines()
            .filter { line -> val t = line.trim(); t.isNotEmpty() && !t.startsWith("#") && !t.startsWith(";") }
            .joinToString("\n")

        android.util.Log.d("VpnHelper", "startVpn name=$name lines=${clean.lines().size}")
        activity.runOnUiThread { eventSink?.success("log:parsing config ($name)") }

        try {
            // 1. Parse the .ovpn config string.
            val cp = ConfigParser()
            cp.parseConfig(StringReader(clean))

            // 2. Convert to a VpnProfile (ics-openvpn's internal model).
            val profile: VpnProfile = cp.convertProfile()
            profile.mName = name

            // 3. Register the profile so ics-openvpn can look it up by UUID.
            val pm = ProfileManager.getInstance(activity)
            pm.addProfile(profile)
            // v0.7.33: setConnectedVpnProfile(Context, VpnProfile) — note lowercase 'v'
            ProfileManager.setConnectedVpnProfile(activity, profile)

            // 4. Hand off to VPNLaunchHelper — it requests tun, binds the service,
            //    and starts the OpenVPN process.
            // v0.7.33: startOpenVpn(VpnProfile, Context) — 2-param signature
            VPNLaunchHelper.startOpenVpn(profile, activity.applicationContext)
        } catch (e: ConfigParser.ConfigParseError) {
            emitError("startVpn/parse", e)
        } catch (e: Exception) {
            emitError("startVpn", e)
        }
    }

    private fun emitError(method: String, e: Exception) {
        val msg = "error:${e.javaClass.simpleName}: ${e.message}"
        android.util.Log.e("VpnHelper", "$method threw: $msg", e)
        activity.runOnUiThread { eventSink?.success(msg) }
    }
}
