package com.tucuvpn.tucuvpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VpnStatus
import java.io.StringReader

class TucuVPNService : VpnService() {

    companion object {
        const val TAG = "TucuVPNService"
        const val ACTION_CONNECT = "com.tucuvpn.tucuvpn.CONNECT"
        const val ACTION_DISCONNECT = "com.tucuvpn.tucuvpn.DISCONNECT"
        
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "tucuvpn_channel"
    }

    private var notificationManager: NotificationManager? = null
    private var currentProfile: VpnProfile? = null

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): TucuVPNService = this@TucuVPNService
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        Log.d(TAG, "TucuVPNService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                disconnect()
                return START_NOT_STICKY
            }
            ACTION_CONNECT -> {
                val config = intent.getStringExtra("config")
                val name = intent.getStringExtra("name") ?: "TucuVPN"
                if (config != null) {
                    connect(config, name)
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return binder
    }

    override fun onDestroy() {
        disconnect()
        VpnStatus.removeStateListener(stateListener)
        super.onDestroy()
    }

    fun connect(config: String, name: String) {
        Log.d(TAG, "connect called with name=$name")
        
        startForeground(NOTIFICATION_ID, createNotification("Conectando..."))
        
        try {
            val cleanConfig = config.lines()
                .map { it.trim() }
                .filter { line -> 
                    line.isNotEmpty() && !line.startsWith("#") && !line.startsWith(";")
                }
                .joinToString("\n")

            Log.d(TAG, "Config cleaned, length: ${cleanConfig.length}")
            
            val cp = ConfigParser()
            cp.parseConfig(StringReader(cleanConfig))

            val profile: VpnProfile = cp.convertProfile()
            profile.mName = name
            
            Log.d(TAG, "VpnProfile created, authentication type: ${profile.mAuthenticationType}")
            Log.d(TAG, "Server: ${profile.mServerName}:${profile.mServerPort}")
            
            currentProfile = profile
            
            val pm = ProfileManager.getInstance(this)
            pm.addProfile(profile)
            ProfileManager.setConnectedVpnProfile(this, profile)
            pm.saveProfile(this, profile)
            
            VpnStatus.setConnectedVPNProfile(profile.getUUIDString())
            VpnStatus.addStateListener(stateListener)

            Log.d(TAG, "Profile saved with UUID: ${profile.getUUIDString()}")
            Log.d(TAG, "Profile UUID string: ${profile.getUUIDString()}")
            Log.d(TAG, "Profile version: ${profile.mVersion}")
            
            // Start OpenVPNService with profile - NO action set, just extras
            val startIntent = Intent(this, de.blinkt.openvpn.core.OpenVPNService::class.java).apply {
                putExtra(de.blinkt.openvpn.VpnProfile.EXTRA_PROFILEUUID, profile.getUUIDString())
                putExtra(de.blinkt.openvpn.VpnProfile.EXTRA_PROFILE_VERSION, profile.mVersion)
                putExtra(de.blinkt.openvpn.core.OpenVPNService.EXTRA_DO_NOT_REPLACE_RUNNING_VPN, false)
                putExtra(de.blinkt.openvpn.core.OpenVPNService.EXTRA_START_REASON, "TucuVPN")
            }
            Log.d(TAG, "Starting OpenVPNService with direct intent (NO ACTION), UUID: ${profile.getUUIDString()}")
            startService(startIntent)
            Log.d(TAG, "OpenVPNService start called")

        } catch (e: Exception) {
            Log.e(TAG, "Error connecting: ${e.message}", e)
            VpnStatus.logError("Error: ${e.message}")
            stopSelf()
        }
    }

    fun disconnect() {
        Log.d(TAG, "Disconnect called")
        
        try {
            val intent = Intent(this, OpenVPNService::class.java).apply {
                action = OpenVPNService.DISCONNECT_VPN
            }
            startService(intent)
        } catch (_: Exception) {}
        
        try {
            ProfileManager.setConntectedVpnProfileDisconnected(this)
        } catch (_: Exception) {}
        
        VpnStatus.removeStateListener(stateListener)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private val stateListener = object : VpnStatus.StateListener {
        override fun updateState(
            state: String?,
            logmessage: String?,
            localizedResId: Int,
            level: de.blinkt.openvpn.core.ConnectionStatus?,
            intent: Intent?
        ) {
            Log.d(TAG, ">>> State: state=$state level=$level msg=$logmessage")
            
            val statusText = when {
                level == de.blinkt.openvpn.core.ConnectionStatus.LEVEL_CONNECTED -> "Conectado"
                level == de.blinkt.openvpn.core.ConnectionStatus.LEVEL_NOTCONNECTED -> "Desconectado"
                level == de.blinkt.openvpn.core.ConnectionStatus.LEVEL_AUTH_FAILED -> "Auth fallida"
                state == "CONNECTED" -> "Conectado"
                state == "NOPROCESS" -> "Sin proceso"
                state != null -> state
                else -> logmessage ?: "..."
            }
            
            updateNotification(statusText)
        }

        override fun setConnectedVPN(uuid: String?) {
            Log.d(TAG, "setConnectedVPN: $uuid")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TucuVPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN connection status"
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(status: String): Notification {
        val disconnectIntent = Intent(this, TucuVPNService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this, 0, disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TucuVPN")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Desconectar", disconnectPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(status: String) {
        notificationManager?.notify(NOTIFICATION_ID, createNotification(status))
    }
}
