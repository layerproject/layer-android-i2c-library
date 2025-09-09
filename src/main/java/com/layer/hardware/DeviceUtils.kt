package com.layer.hardware

import android.util.Log

object DeviceUtils {
    private const val TAG = "DeviceUtils"
    
    fun getSerialNumber(): String {
        return try {
            val process = Runtime.getRuntime().exec("getprop ro.serialno")
            process.inputStream.bufferedReader().use { it.readText().trim() }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting serial number", e)
            "Unknown"
        }
    }
}