package com.tucuvpn.tucuvpn

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.IBinder
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.VpnStatus
import io.flutter.plugin.common.EventChannel

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
            val n = pendingName
            if (cfg != null && n != null) {
                vpnService?.connect(cfg, n)
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
                state == "CONNECTED"                         -> "connected"
                state == "DISCONNECTED"                      -> "disconnected"
                state == "EXITING"                           -> "disconnected"
                state == "NOPROCESS"                         -> "disconnected"
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
            val n = pendingName
            if (cfg != null && n != null) startVpn(cfg, n)
            else activity.runOnUiThread { eventSink?.success("error:lost pending config") }
        } else {
            activity.runOnUiThread { eventSink?.success("denied") }
        }
        pendingConfig = null
        pendingName = null
    }

    fun disconnect() {
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
        android.util.Log.d("VpnHelper", "=== startVpn called ===")
        android.util.Log.d("VpnHelper", "name=$name")
        android.util.Log.d("VpnHelper", "config length=${config.length}")
        android.util.Log.d("VpnHelper", NativeLibHelper.getLibsInfo(activity))
        
        activity.runOnUiThread { eventSink?.success("log:connecting ($name)") }

        pendingConfig = config
        pendingName = name

        android.util.Log.d("VpnHelper", "Starting TucuVPNService...")
        
        val intent = Intent(activity, TucuVPNService::class.java).apply {
            action = TucuVPNService.ACTION_CONNECT
            putExtra("config", config)
            putExtra("name", name)
        }
        activity.startService(intent)
        
        if (!serviceBound) {
            android.util.Log.d("VpnHelper", "Binding to TucuVPNService...")
            val bindIntent = Intent(activity, TucuVPNService::class.java)
            activity.bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun emitError(method: String, e: Exception) {
        val msg = "error:${e.javaClass.simpleName}: ${e.message}"
        android.util.Log.e("VpnHelper", "$method threw: $msg", e)
        activity.runOnUiThread { eventSink?.success(msg) }
    }
}
