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
import java.io.InputStream
import java.io.StringReader

class TucuVPNService : VpnService() {

    companion object {
        const val TAG = "TucuVPNService"
        const val ACTION_CONNECT = "com.tucuvpn.tucuvpn.CONNECT"
        const val ACTION_DISCONNECT = "com.tucuvpn.tucuvpn.DISCONNECT"
        
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "tucuvpn_channel"
        
        private val NATIVE_LIBS = listOf(
            "libopenvpn.so",
            "libovpn3.so",
            "libovpnutil.so",
            "libosslutil.so",
            "libosslspeedtest.so"
        )
    }

    private var notificationManager: NotificationManager? = null
    private var currentProfile: VpnProfile? = null
    private var openVPNProcess: Process? = null

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
            
            VpnStatus.addStateListener(stateListener)

            writeOpenVPNConfig(cleanConfig)
            startOpenVPNProcess()

        } catch (e: Exception) {
            Log.e(TAG, "Error connecting: ${e.message}", e)
            e.printStackTrace()
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

    private fun startOpenVPNProcess() {
        Log.d(TAG, "=== Starting OpenVPN Process ===")
        
        val abi = getPreferredAbi()
        Log.d(TAG, "Using ABI: $abi")
        
        val workDir = File(cacheDir, "openvpn_$abi")
        workDir.mkdirs()
        
        val nativeLibDir = File(applicationInfo.nativeLibraryDir ?: "")
        Log.d(TAG, "Native lib dir: ${nativeLibDir.absolutePath}")
        
        for (libName in NATIVE_LIBS) {
            val targetLib = File(workDir, libName)
            if (!targetLib.exists()) {
                val sourceLib = File(nativeLibDir, libName)
                if (sourceLib.exists()) {
                    sourceLib.copyTo(targetLib)
                    targetLib.setReadable(true, false)
                    Log.d(TAG, "Copied $libName to work dir")
                } else {
                    Log.w(TAG, "Source lib not found: $libName")
                }
            }
        }
        
        val openVPNBinary = File(workDir, "openvpn")
        if (!openVPNBinary.exists()) {
            copyOpenVPNBinary(openVPNBinary, abi)
        }
        openVPNBinary.setExecutable(true, false)
        
        val configPath = File(cacheDir, "android.conf").absolutePath
        Log.d(TAG, "OpenVPN binary: ${openVPNBinary.absolutePath}")
        Log.d(TAG, "Config path: $configPath")
        Log.d(TAG, "Work dir: ${workDir.absolutePath}")
        
        val libPath = "${workDir.absolutePath}:${nativeLibDir.absolutePath}"
        Log.d(TAG, "LD_LIBRARY_PATH: $libPath")
        
        try {
            val pb = ProcessBuilder(
                openVPNBinary.absolutePath,
                "--config", configPath
            )
            pb.environment()["LD_LIBRARY_PATH"] = libPath
            pb.directory(workDir)
            
            Log.d(TAG, "Starting OpenVPN process...")
            openVPNProcess = pb.start()
            
            Thread({
                try {
                    val reader = openVPNProcess!!.inputStream.bufferedReader()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        Log.d(TAG, "[openvpn] $line")
                        VpnStatus.logInfo(line!!)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading stdout: ${e.message}")
                }
            }, "OpenVPN-stdout").start()
            
            Thread({
                try {
                    val reader = openVPNProcess!!.errorStream.bufferedReader()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        Log.e(TAG, "[openvpn-err] $line")
                        VpnStatus.logError(line!!)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading stderr: ${e.message}")
                }
            }, "OpenVPN-stderr").start()
            
            Thread({
                try {
                    val exitCode = openVPNProcess!!.waitFor()
                    Log.d(TAG, "OpenVPN process exited with code: $exitCode")
                    VpnStatus.updateStateString("NOPROCESS", "OpenVPN exited", 0, 
                        de.blinkt.openvpn.core.ConnectionStatus.LEVEL_NOTCONNECTED, null)
                } catch (e: Exception) {
                    Log.e(TAG, "Error waiting for process: ${e.message}")
                }
            }, "OpenVPN-waiter").start()
            
            Log.d(TAG, "OpenVPN process started successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start OpenVPN: ${e.message}", e)
            e.printStackTrace()
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

    private fun copyOpenVPNBinary(targetFile: File, abi: String) {
        val binaryName = "pie_openvpn.$abi"
        Log.d(TAG, "Looking for binary: $binaryName in assets")
        
        try {
            assets.open(binaryName).use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "Copied $binaryName from assets")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy $binaryName from assets: ${e.message}")
            
            val fallbackName = "pie_openvpn.armeabi-v7a"
            try {
                assets.open(fallbackName).use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d(TAG, "Copied fallback $fallbackName from assets")
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to copy fallback binary: ${e2.message}")
            }
        }
    }

    fun disconnect() {
        Log.d(TAG, "Disconnect called")
        
        openVPNProcess?.let { process ->
            try {
                process.destroy()
                Log.d(TAG, "Process destroyed")
            } catch (_: Exception) {}
        }
        openVPNProcess = null
        
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
