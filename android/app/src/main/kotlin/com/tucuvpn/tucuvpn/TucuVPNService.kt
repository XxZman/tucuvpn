package com.tucuvpn.tucuvpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import de.blinkt.openvpn.core.VpnStatus
import java.io.File
import java.io.FileOutputStream
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
        
        logNativeDebug()
        loadNativeLibs()
        
        Log.d(TAG, "TucuVPNService created")
    }

    private fun logNativeDebug() {
        Log.d(TAG, "=== NATIVE DEBUG ===")
        Log.d(TAG, "nativeLibraryDir: ${applicationInfo.nativeLibraryDir}")
        Log.d(TAG, "SUPPORTED_ABIS: ${Build.SUPPORTED_ABIS.joinToString()}")
        Log.d(TAG, "SDK_INT: ${Build.VERSION.SDK_INT}")
        
        val nativeDir = File(applicationInfo.nativeLibraryDir ?: "")
        if (nativeDir.exists() && nativeDir.isDirectory) {
            nativeDir.listFiles()?.forEach { file ->
                Log.d(TAG, "Found: ${file.name} (${file.length()} bytes)")
            }
        }
    }

    private fun loadNativeLibs() {
        Log.d(TAG, "=== Loading Native Libs ===")
        try {
            System.loadLibrary("ovpnexec")
            Log.d(TAG, "Successfully loaded libovpnexec.so")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load libovpnexec.so: ${e.message}")
        }
        
        try {
            System.loadLibrary("openvpn")
            Log.d(TAG, "Successfully loaded libopenvpn.so")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load libopenvpn.so: ${e.message}")
        }
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
            
            VpnStatus.addStateListener(stateListener)

            Log.d(TAG, "=== Starting OpenVPN ===")
            Log.d(TAG, "Using VPNLaunchHelper approach...")
            
            // Use VPNLaunchHelper.buildOpenvpnArgv to get the command
            try {
                val argv = VPNLaunchHelperWrapper.buildOpenvpnArgv(this)
                if (argv != null) {
                    Log.d(TAG, "OpenVPN argv: ${argv.joinToString(" ")}")
                    startOpenVPNProcess(argv)
                } else {
                    Log.e(TAG, "buildOpenvpnArgv returned null")
                    VpnStatus.logError("Failed to build OpenVPN arguments")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error using VPNLaunchHelper: ${e.message}", e)
                // Fallback: try direct approach
                tryDirectOpenVPN()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error connecting: ${e.message}", e)
            VpnStatus.logError("Error: ${e.message}")
            stopSelf()
        }
    }

    private fun tryDirectOpenVPN() {
        Log.d(TAG, "=== Direct OpenVPN Start ===")
        
        val nativeLibDir = applicationInfo.nativeLibraryDir
        if (nativeLibDir == null) {
            Log.e(TAG, "nativeLibraryDir is null!")
            return
        }
        
        val libovpnexec = File(nativeLibDir, "libovpnexec.so")
        Log.d(TAG, "libovpnexec path: ${libovpnexec.absolutePath}")
        Log.d(TAG, "libovpnexec exists: ${libovpnexec.exists()}")
        Log.d(TAG, "libovpnexec size: ${libovpnexec.length()} bytes")
        
        // Try to load and call native functions
        try {
            // These would be native methods if they exist
            // For now, just log that we're trying
            Log.d(TAG, "Would call native openvpn_main if available")
        } catch (e: Exception) {
            Log.e(TAG, "Direct approach failed: ${e.message}")
        }
    }

    private fun startOpenVPNProcess(argv: Array<String>) {
        Log.d(TAG, "=== Starting OpenVPN Process ===")
        Log.d(TAG, "Args: ${argv.joinToString(" ")}")
        
        try {
            val processBuilder = ProcessBuilder(*argv)
            processBuilder.directory(File(filesDir, "openvpn"))
            
            val env = processBuilder.environment()
            env["LD_LIBRARY_PATH"] = "${applicationInfo.nativeLibraryDir}"
            
            Log.d(TAG, "Starting process...")
            val process = processBuilder.start()
            
            Thread({
                try {
                    val reader = process.inputStream.bufferedReader()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        Log.d(TAG, "[ovpn] $line")
                        VpnStatus.logInfo(line!!)
                    }
                } catch (e: Exception) {}
            }, "OVPN-stdout").start()
            
            Thread({
                try {
                    val reader = process.errorStream.bufferedReader()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        Log.e(TAG, "[ovpn-err] $line")
                    }
                } catch (e: Exception) {}
            }, "OVPN-stderr").start()
            
            Thread({
                val exitCode = process.waitFor()
                Log.d(TAG, "OpenVPN exited with code: $exitCode")
                VpnStatus.updateStateString("NOPROCESS", "Exited", 0, 
                    de.blinkt.openvpn.core.ConnectionStatus.LEVEL_NOTCONNECTED, null)
            }, "OVPN-waiter").start()
            
            Log.d(TAG, "OpenVPN process started")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start OpenVPN: ${e.message}", e)
            VpnStatus.logError("Failed to start: ${e.message}")
        }
    }

    private fun writeOpenVPNConfig(config: String) {
        val configFile = File(filesDir, "openvpn/android.conf")
        configFile.parentFile?.mkdirs()
        
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
        Log.d(TAG, "Disconnect called")
        
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

object VPNLaunchHelperWrapper {
    private const val TAG = "VPNLaunchHelper"
    
    fun buildOpenvpnArgv(context: android.content.Context): Array<String>? {
        try {
            // Get the method via reflection since we can't import it directly
            val clazz = Class.forName("de.blinkt.openvpn.core.VPNLaunchHelper")
            val method = clazz.getMethod("buildOpenvpnArgv", android.content.Context::class.java)
            @Suppress("UNCHECKED_CAST")
            val result = method.invoke(null, context) as? Array<String>
            return result
        } catch (e: Exception) {
            Log.e(TAG, "buildOpenvpnArgv failed: ${e.message}")
            return null
        }
    }
}
