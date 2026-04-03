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
            "libovpnexec.so", 
            "libovpn3.so",
            "libovpnutil.so",
            "libosslutil.so",
            "libosslspeedtest.so"
        )
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
            
            startOpenVPNProcess(profile, cleanConfig)

            VpnStatus.addStateListener(stateListener)

        } catch (e: Exception) {
            Log.e(TAG, "Error connecting: ${e.message}", e)
            VpnStatus.logError("Error: ${e.message}")
            stopSelf()
        }
    }

    private fun startOpenVPNProcess(profile: VpnProfile, config: String) {
        val abi = getPreferredAbi()
        val cacheDir = File(cacheDir, "openvpn_$abi")
        cacheDir.mkdirs()

        Log.d(TAG, "Using ABI: $abi, cache dir: ${cacheDir.absolutePath}")

        copyNativeLibs(cacheDir, abi)

        val execFile = File(cacheDir, "openvpn")
        if (!execFile.exists()) {
            copyBinaryFromAssets(execFile, abi)
        }
        execFile.setExecutable(true)

        val libPath = cacheDir.absolutePath
        val configFile = File(cacheDir, "android.conf")
        configFile.writeText(buildOpenVPNConfig(config))

        val processBuilder = ProcessBuilder(
            execFile.absolutePath,
            "--config", configFile.absolutePath
        )
        processBuilder.environment()["LD_LIBRARY_PATH"] = libPath
        processBuilder.directory(cacheDir)

        try {
            val process = processBuilder.start()
            
            Thread({
                try {
                    val reader = process.inputStream.bufferedReader()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        Log.d(TAG, "[openvpn] $line")
                        VpnStatus.logInfo(line!!)
                    }
                } catch (e: Exception) {}
            }, "OpenVPN-stdout").start()
            
            Thread({
                try {
                    val reader = process.errorStream.bufferedReader()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        Log.e(TAG, "[openvpn-err] $line")
                    }
                } catch (e: Exception) {}
            }, "OpenVPN-stderr").start()

            Thread({
                val exitCode = process.waitFor()
                Log.d(TAG, "OpenVPN process exited with code: $exitCode")
                VpnStatus.updateStateString("NOPROCESS", "", 0, de.blinkt.openvpn.core.ConnectionStatus.LEVEL_NOTCONNECTED, null)
            }, "OpenVPN-waiter").start()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start OpenVPN: ${e.message}", e)
            VpnStatus.logError("Failed to start OpenVPN: ${e.message}")
        }
    }

    private fun getPreferredAbi(): String {
        val supportedAbis = Build.SUPPORTED_ABIS
        val abiOrder = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        
        for (preferred in abiOrder) {
            if (supportedAbis.any { it.contains(preferred) }) {
                return preferred
            }
        }
        
        return supportedAbis.firstOrNull() ?: "arm64-v8a"
    }

    private fun copyNativeLibs(targetDir: File, abi: String) {
        val nativeLibDir = File(applicationInfo.nativeLibraryDir)
        
        for (libName in NATIVE_LIBS) {
            val targetFile = File(targetDir, libName)
            if (targetFile.exists()) continue
            
            val sourceFile = File(nativeLibDir, libName)
            if (sourceFile.exists()) {
                sourceFile.copyTo(targetFile)
                targetFile.setReadable(true, false)
                targetFile.setExecutable(false)
                Log.d(TAG, "Copied native lib: $libName from nativeLibraryDir")
            } else {
                Log.w(TAG, "Native lib not found in nativeLibraryDir: $libName")
                copyFromAssetsOrJni(targetFile, libName, abi)
            }
        }
    }

    private fun copyFromAssetsOrJni(targetFile: File, libName: String, abi: String) {
        try {
            val assetStream = assets.open("jniLibs/$abi/$libName")
            copyStreamToFile(assetStream, targetFile)
            targetFile.setReadable(true, false)
            Log.d(TAG, "Copied $libName from assets jniLibs/$abi/")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to copy $libName from assets: ${e.message}")
            val jniLibsFile = File("src/main/jniLibs/$abi/$libName")
            if (jniLibsFile.exists()) {
                jniLibsFile.copyTo(targetFile)
                targetFile.setReadable(true, false)
                Log.d(TAG, "Copied $libName from jniLibs")
            }
        }
    }

    private fun copyBinaryFromAssets(targetFile: File, abi: String) {
        try {
            val binaryName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) "pie_openvpn" else "nopie_openvpn"
            val assetName = "$binaryName.$abi"
            
            val assetStream = assets.open(assetName)
            copyStreamToFile(assetStream, targetFile)
            targetFile.setExecutable(true)
            Log.d(TAG, "Copied binary from assets: $assetName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy binary from assets: ${e.message}")
            throw e
        }
    }

    private fun copyStreamToFile(input: InputStream, output: File) {
        FileOutputStream(output).use { fos ->
            input.copyTo(fos)
        }
    }

    private fun buildOpenVPNConfig(config: String): String {
        val builder = StringBuilder()
        builder.append(config)
        
        if (!config.contains("dev tun")) {
            builder.append("\ndev tun\n")
        }
        if (!config.contains("persist-tun")) {
            builder.append("persist-tun\n")
        }
        if (!config.contains("persist-key")) {
            builder.append("persist-key\n")
        }
        
        builder.append("\n# TucuVPN Android wrapper\n")
        return builder.toString()
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
