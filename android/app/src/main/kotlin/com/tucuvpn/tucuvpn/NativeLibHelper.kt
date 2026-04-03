package com.tucuvpn.tucuvpn

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

object NativeLibHelper {
    private const val TAG = "NativeLibHelper"
    
    private val NATIVE_LIBS = listOf(
        "libopenvpn.so",
        "libovpnexec.so",
        "libovpn3.so",
        "libovpnutil.so",
        "libosslutil.so",
        "libosslspeedtest.so",
        "libssl.so",
        "libcrypto.so"
    )
    
    private var libsLoaded = false
    
    fun ensureLibsLoaded(context: Context) {
        if (libsLoaded) {
            Log.d(TAG, "Native libs already loaded")
            return
        }
        
        Log.d(TAG, "=== Loading Native Libs ===")
        
        val nativeLibDir = getNativeLibDir(context)
        Log.d(TAG, "Native lib dir: $nativeLibDir")
        
        if (nativeLibDir == null) {
            Log.e(TAG, "nativeLibraryDir is null!")
            return
        }
        
        val dir = File(nativeLibDir)
        if (!dir.exists()) {
            Log.e(TAG, "Native lib dir does not exist: $nativeLibDir")
            return
        }
        
        val files = dir.listFiles() ?: emptyArray()
        Log.d(TAG, "Files in native lib dir: ${files.map { it.name }.joinToString()}")
        
        for (libName in NATIVE_LIBS) {
            val libFile = File(dir, libName)
            if (libFile.exists()) {
                try {
                    Log.d(TAG, "Loading $libName from ${libFile.absolutePath}")
                    System.load(libFile.absolutePath)
                    Log.d(TAG, "Successfully loaded $libName")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load $libName: ${e.message}")
                }
            } else {
                Log.w(TAG, "Lib not found: $libName")
            }
        }
        
        libsLoaded = true
        Log.d(TAG, "=== Native libs loading complete ===")
    }
    
    private fun getNativeLibDir(context: Context): String? {
        return context.applicationInfo.nativeLibraryDir
    }
    
    fun getLibsInfo(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine("=== Native Libs Info ===")
        sb.appendLine("SDK_INT: ${Build.VERSION.SDK_INT}")
        sb.appendLine("SUPPORTED_ABIS: ${Build.SUPPORTED_ABIS.joinToString()}")
        sb.appendLine("CPU_ABI: ${Build.CPU_ABI}")
        
        val nativeLibDir = getNativeLibDir(context)
        sb.appendLine("nativeLibraryDir: $nativeLibDir")
        
        if (nativeLibDir != null) {
            val dir = File(nativeLibDir)
            if (dir.exists()) {
                sb.appendLine("Files in nativeLibraryDir:")
                dir.listFiles()?.forEach { file ->
                    sb.appendLine("  - ${file.name} (${file.length()} bytes)")
                }
            }
        }
        
        return sb.toString()
    }
}
