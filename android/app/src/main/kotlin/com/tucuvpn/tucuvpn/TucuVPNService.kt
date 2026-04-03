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
        Log.d(TAG, "TucuVPNService created")
    }

    private fun logNativeDebug() {
        Log.d(TAG, "=== NATIVE DEBUG ===")
        Log.d(TAG, "nativeLibraryDir: ${applicationInfo.nativeLibraryDir}")
        Log.d(TAG, "filesDir: ${filesDir.absolutePath}")
        Log.d(TAG, "SUPPORTED_ABIS: ${Build.SUPPORTED_ABIS.joinToString()}")
        Log.d(TAG, "SDK_INT: ${Build.VERSION.SDK_INT}")
        
        val nativeDir = File(applicationInfo.nativeLibraryDir ?: "")
        if (nativeDir.exists() && nativeDir.isDirectory) {
            nativeDir.listFiles()?.forEach { file ->
                Log.d(TAG, "Found: ${file.name} (${file.length()} bytes)")
            }
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

            startOpenVPNProcess()

        } catch (e: Exception) {
            Log.e(TAG, "Error connecting: ${e.message}", e)
            VpnStatus.logError("Error: ${e.message}")
            stopSelf()
        }
    }

    private fun writeOpenVPNConfig(config: String) {
        val configFile = File(filesDir, "android.conf")
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

    private fun startOpenVPNProcess() {
        Log.d(TAG, "=== Starting OpenVPN ===")
        
        val abi = getPreferredAbi()
        val nativeLibDir = applicationInfo.nativeLibraryDir
        
        if (nativeLibDir == null) {
            Log.e(TAG, "nativeLibraryDir is null!")
            VpnStatus.logError("Native library directory not found")
            return
        }
        
        Log.d(TAG, "Using ABI: $abi")
        Log.d(TAG, "Native lib dir: $nativeLibDir")
        
        val binaryPath = getOpenVPNBinary(abi)
        if (binaryPath == null) {
            Log.e(TAG, "Failed to get OpenVPN binary path")
            VpnStatus.logError("Failed to get OpenVPN binary")
            return
        }
        
        Log.d(TAG, "OpenVPN binary: $binaryPath")
        
        val configPath = File(filesDir, "android.conf").absolutePath
        Log.d(TAG, "Config path: $configPath")
        
        try {
            val pb = ProcessBuilder(binaryPath, "--config", configPath)
            pb.environment()["LD_LIBRARY_PATH"] = nativeLibDir
            
            Log.d(TAG, "Starting OpenVPN process...")
            val process = pb.start()
            
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
                        VpnStatus.logError(line!!)
                    }
                } catch (e: Exception) {}
            }, "OVPN-stderr").start()
            
            Thread({
                val exitCode = process.waitFor()
                Log.d(TAG, "OpenVPN exited with code: $exitCode")
                VpnStatus.updateStateString("NOPROCESS", "Exited", 0, 
                    de.blinkt.openvpn.core.ConnectionStatus.LEVEL_NOTCONNECTED, null)
            }, "OVPN-waiter").start()
            
            Log.d(TAG, "OpenVPN process started successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start OpenVPN: ${e.message}", e)
            VpnStatus.logError("Failed to start OpenVPN: ${e.message}")
        }
    }

    private fun getPreferredAbi(): String {
        val supportedAbis = Build.SUPPORTED_ABIS.toList()
        val abiOrder = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        
        for (preferred in abiOrder) {
            for (abi in supportedAbis) {
                if (abi.contains(preferred)) {
                    return preferred
                }
            }
        }
        
        return supportedAbis.firstOrNull() ?: "armeabi-v7a"
    }

    private fun getOpenVPNBinary(abi: String): String? {
        val nativeLibDir = File(applicationInfo.nativeLibraryDir ?: "")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val libovpnexec = File(nativeLibDir, "libovpnexec.so")
            if (libovpnexec.exists() && libovpnexec.length() > 10000) {
                Log.d(TAG, "Using libovpnexec.so as executable")
                return libovpnexec.absolutePath
            }
        }
        
        val assetName = "pie_openvpn.$abi"
        val binaryFile = File(filesDir, "openvpn_$abi")
        binaryFile.parentFile?.mkdirs()
        
        if (binaryFile.exists()) {
            Log.d(TAG, "Using cached binary: ${binaryFile.absolutePath}")
            return binaryFile.absolutePath
        }
        
        try {
            assets.open(assetName).use { input ->
                FileOutputStream(binaryFile).use { output ->
                    input.copyTo(output)
                }
            }
            binaryFile.setExecutable(true, false)
            Log.d(TAG, "Copied binary from assets: $assetName")
            return binaryFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy binary from assets: ${e.message}")
            
            for (fallbackAbi in listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")) {
                try {
                    val fallbackName = "pie_openvpn.$fallbackAbi"
                    assets.open(fallbackName).use { input ->
                        FileOutputStream(binaryFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    binaryFile.setExecutable(true, false)
                    Log.d(TAG, "Copied fallback binary: $fallbackName")
                    return binaryFile.absolutePath
                } catch (_: Exception) {}
            }
        }
        
        return null
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
