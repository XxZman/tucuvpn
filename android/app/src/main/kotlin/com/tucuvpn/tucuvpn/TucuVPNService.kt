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
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VPNLaunchHelper
import de.blinkt.openvpn.core.VpnStatus
import java.io.File
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
        Log.d(TAG, "Native library dir: ${applicationInfo.nativeLibraryDir}")
        Log.d(TAG, "Supported ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
        
        startForeground(NOTIFICATION_ID, createNotification("Conectando..."))
        
        try {
            val cleanConfig = config.lines()
                .filter { line -> 
                    val t = line.trim()
                    t.isNotEmpty() && !t.startsWith("#") && !t.startsWith(";")
                }
                .joinToString("\n")

            val cp = ConfigParser()
            cp.parseConfig(StringReader(cleanConfig))

            val profile: VpnProfile = cp.convertProfile()
            profile.mName = name

            val pm = ProfileManager.getInstance(this)
            pm.addProfile(profile)
            ProfileManager.setConnectedVpnProfile(this, profile)
            
            currentProfile = profile

            writeOpenVPNConfig(cleanConfig)
            
            VPNLaunchHelper.startOpenVpn(profile, this)

            VpnStatus.addStateListener(stateListener)

        } catch (e: Exception) {
            Log.e(TAG, "Error connecting: ${e.message}", e)
            VpnStatus.logError("Error: ${e.message}")
            stopSelf()
        }
    }

    private fun writeOpenVPNConfig(config: String) {
        val configFile = File(cacheDir, "android.conf")
        val builder = StringBuilder(config)
        
        if (!config.contains("dev tun")) {
            builder.append("\ndev tun\n")
        }
        if (!config.contains("persist-tun")) {
            builder.append("persist-tun\n")
        }
        if (!config.contains("persist-key")) {
            builder.append("persist-key\n")
        }
        
        configFile.writeText(builder.toString())
        Log.d(TAG, "Wrote config to ${configFile.absolutePath}")
    }

    fun disconnect() {
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
            Log.d(TAG, "State: $state, Level: $level, Message: $logmessage")
            
            val statusText = when (state) {
                "CONNECTED" -> "Conectado"
                "DISCONNECTED", "EXITING" -> "Desconectado"
                "NEGOTIATING" -> "Negociando..."
                "AUTH" -> "Autenticando..."
                "WAIT" -> "Esperando servidor..."
                "RECONNECTING" -> "Reconectando..."
                "GET_CONFIG" -> "Obteniendo config..."
                "ASSIGN_IP" -> "Asignando IP..."
                "ADD_ROUTES" -> "Configurando rutas..."
                "CONNECTING" -> "Conectando..."
                "NOPROCESS" -> "Sin proceso"
                "VPN_GENERATE_CONFIG" -> "Generando config..."
                else -> state ?: "Desconocido"
            }
            
            updateNotification(statusText)
        }

        override fun setConnectedVPN(uuid: String?) {}
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
