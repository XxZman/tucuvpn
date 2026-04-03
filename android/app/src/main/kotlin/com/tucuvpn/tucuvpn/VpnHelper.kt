package com.tucuvpn.tucuvpn

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.IBinder
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VPNLaunchHelper
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.VpnStatus
import io.flutter.plugin.common.EventChannel
import java.io.StringReader

/**
 * VPN helper that uses TucuVPNService (our own VPN service in com.tucuvpn.tucuvpn).
 *
 * Lifecycle:
 *   1. [connect] requests VPN permission if needed, then starts TucuVPNService.
 *   2. If the system shows the permission dialog, [onPermissionResult] resumes
 *      the connect flow once the user responds.
 *   3. Stage events from [VpnStatus.StateListener] are forwarded to Flutter via
 *      [eventSink] as lowercase strings ("connected", "connecting", etc.).
 *   4. [disconnect] stops TucuVPNService and emits "disconnected".
 *   5. Call [cleanup] from onDestroy to remove the state listener.
 */
class VpnHelper(private val activity: Activity) {

    companion object {
        const val VPN_REQUEST_CODE = 24
    }

    var eventSink: EventChannel.EventSink? = null

    private var pendingConfig: String? = null
    private var pendingName: String? = null
    private var vpnService: TucuVPNService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TucuVPNService.LocalBinder
            vpnService = binder.getService()
            serviceBound = true
            android.util.Log.d("VpnHelper", "TucuVPNService bound")
            
            val cfg = pendingConfig
            val name = pendingName
            if (cfg != null && name != null) {
                vpnService?.connect(cfg, name)
            }
            pendingConfig = null
            pendingName = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            vpnService = null
            serviceBound = false
        }
    }

    private val stateListener = object : VpnStatus.StateListener {
        override fun updateState(
            state: String?,
            logmessage: String?,
            localizedResId: Int,
            level: ConnectionStatus?,
            intent: Intent?,
        ) {
            val mapped = when {
                level == ConnectionStatus.LEVEL_CONNECTED    -> "connected"
                level == ConnectionStatus.LEVEL_NOTCONNECTED -> "disconnected"
                level == ConnectionStatus.LEVEL_AUTH_FAILED  -> "auth_failed"
                level == ConnectionStatus.LEVEL_NONETWORK    -> "nonetwork"
                state == "RECONNECTING"                      -> "connecting"
                state == "CONNECTING"                        -> "connecting"
                state == "WAIT"                              -> "connecting"
                state == "AUTH"                              -> "connecting"
                state == "GET_CONFIG"                        -> "connecting"
                state == "ASSIGN_IP"                         -> "connecting"
                state == "ADD_ROUTES"                        -> "connecting"
                state == "CONNECTED"                         -> "connected"
                state == "DISCONNECTED"                      -> "disconnected"
                state == "EXITING"                           -> "disconnected"
                else                                         -> state?.lowercase() ?: "connecting"
            }
            activity.runOnUiThread { eventSink?.success(mapped) }
        }

        override fun setConnectedVPN(uuid: String?) { }
    }

    init {
        VpnStatus.addStateListener(stateListener)
    }

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
            emitError("connect", e)
        }
    }

    fun onPermissionResult(granted: Boolean) {
        if (granted) {
            val cfg = pendingConfig
            val name = pendingName
            if (cfg != null && name != null) startVpn(cfg, name)
            else activity.runOnUiThread { eventSink?.success("error:lost pending config") }
        } else {
            activity.runOnUiThread { eventSink?.success("denied") }
        }
        pendingConfig = null
        pendingName = null
    }

    fun disconnect() {
        try {
            ProfileManager.setConntectedVpnProfileDisconnected(activity)
        } catch (_: Exception) {}
        
        try {
            if (serviceBound) {
                vpnService?.disconnect()
                activity.unbindService(serviceConnection)
                serviceBound = false
            }
        } catch (_: Exception) {}
        
        val intent = Intent(activity, TucuVPNService::class.java)
        intent.action = TucuVPNService.ACTION_DISCONNECT
        activity.startService(intent)
        
        activity.runOnUiThread { eventSink?.success("disconnected") }
    }

    fun cleanup() {
        VpnStatus.removeStateListener(stateListener)
        if (serviceBound) {
            try {
                activity.unbindService(serviceConnection)
            } catch (_: Exception) {}
            serviceBound = false
        }
    }

    private fun startVpn(config: String, name: String) {
        val clean = config.lines()
            .filter { line -> val t = line.trim(); t.isNotEmpty() && !t.startsWith("#") && !t.startsWith(";") }
            .joinToString("\n")

        android.util.Log.d("VpnHelper", "startVpn name=$name lines=${clean.lines().size}")
        activity.runOnUiThread { eventSink?.success("log:parsing config ($name)") }

        try {
            val cp = ConfigParser()
            cp.parseConfig(StringReader(clean))

            val profile: VpnProfile = cp.convertProfile()
            profile.mName = name

            val pm = ProfileManager.getInstance(activity)
            pm.addProfile(profile)
            ProfileManager.setConnectedVpnProfile(activity, profile)

            pendingConfig = clean
            pendingName = name

            val intent = Intent(activity, TucuVPNService::class.java).apply {
                action = TucuVPNService.ACTION_CONNECT
                putExtra("config", clean)
                putExtra("name", name)
            }
            activity.startService(intent)
            
            if (!serviceBound) {
                val bindIntent = Intent(activity, TucuVPNService::class.java)
                activity.bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE)
            }

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
