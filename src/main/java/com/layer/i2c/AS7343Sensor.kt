package com.layer.i2c

import android.util.Log

/**
 * High-level interface for the AS7343 spectral sensor.
 * Provides convenient methods for sensor operations.
 */
class AS7343Sensor(private val busPath: String) {
    companion object {
        private const val TAG = "AS7343Sensor"
    }
    // Uses the updated AS7343 SensorManager
    private val sensorManager = SensorManager() 
    private var fileDescriptor: Int = -1

    // Track initialization status
    private var isInitialized: Boolean = false 

    /**
     * Opens a connection to the sensor and initializes it.
     * @return true if connection and initialization were successful, false otherwise
     */
    fun connect(): Boolean {
        if (isInitialized) {
            Log.d(TAG, "Sensor on $busPath already connected and initialized.")
            return true
        }

        // Already open but not initialized? Close first.
        if (fileDescriptor >= 0) { 
             Log.w(TAG, "Sensor fd already open but not initialized. Re-opening.")
             sensorManager.closeSensor(fileDescriptor)
             fileDescriptor = -1
        }

        Log.d(TAG, "Connecting to sensor on $busPath...")
        fileDescriptor = sensorManager.openSensor(busPath)
        if (fileDescriptor < 0) {
            Log.e(TAG, "Failed to open sensor on $busPath.")
            isInitialized = false
            return false
        }

        Log.d(TAG, "Sensor opened (fd=$fileDescriptor). Initializing...")
        // *** Crucial step: Initialize the sensor after opening ***
        isInitialized = sensorManager.initializeSensor(fileDescriptor)

        if (!isInitialized) {
            Log.e(TAG, "Failed to initialize sensor on $busPath (fd=$fileDescriptor). Closing.")
            sensorManager.closeSensor(fileDescriptor)
            fileDescriptor = -1
            return false
        }

        Log.i(TAG, "Sensor on $busPath connected and initialized successfully.")
        return true
    }

    /**
     * Closes the connection to the sensor and attempts to power it down.
     */
    fun disconnect() {
        if (fileDescriptor >= 0) {
            Log.d(TAG, "Disconnecting sensor on $busPath (fd=$fileDescriptor)...")
            // Attempt to power down before closing
            if (isInitialized) { // Only try if we think it was initialized
                try {
                     sensorManager.togglePower(fileDescriptor, false)
                     Log.d(TAG,"Sensor powered down.")
                } catch (e: Exception){
                    Log.w(TAG,"Error powering down sensor during disconnect: ${e.message}")
                }
            }
            sensorManager.closeSensor(fileDescriptor)
            fileDescriptor = -1
            isInitialized = false
             Log.i(TAG, "Sensor on $busPath disconnected.")
        } else {
             Log.d(TAG, "Sensor on $busPath already disconnected.")
        }
    }

    /**
     * Reads all spectral channels from the sensor.
     * Handles connect/disconnect internally for a single read operation.
     * Use this if you only need infrequent reads. For frequent reads, manage
     * connect/disconnect externally.
     * @return Map of primary channel names to values, or empty map if read fails.
     */
    fun readSpectralDataOnce(): Map<String, Int> {
        // Ensure connected and initialized
         if (!connect()) { 
             return emptyMap()
         }
         val rawData = sensorManager.readAllChannels(fileDescriptor)
         // Do not disconnect here if caller wants to manage connection externally
         // disconnect()
         if (rawData.isEmpty()) {
             Log.w(TAG, "Read failed or returned empty data for $busPath.")
             return emptyMap()
         }
         return extractPrimaryChannels(rawData) // Return the filtered primary channels
    }

     /**
     * Reads all spectral channels from the sensor (assuming already connected).
     * Use this method if you are managing the connection state externally.
     * Call connect() before using this, and disconnect() when done.
     * @return Map of primary channel names to values, or empty map if not initialized or read fails.
     */
    fun readSpectralData(): Map<String, Int> {
        if (!isInitialized) {
            Log.e(TAG, "Sensor not initialized. Call connect() first.")
            return emptyMap()
        }
         val rawData = sensorManager.readAllChannels(fileDescriptor)
         if (rawData.isEmpty()) {
             Log.w(TAG, "Read failed or returned empty data for $busPath.")
             return emptyMap()
         }
         return extractPrimaryChannels(rawData) // Return the filtered primary channels
    }

     /**
     * Helper to extract the primary 14 channels using the SensorManager's logic.
     * @param rawData The map containing all 18 register readings.
     * @return Map containing primary channels with simplified names ("F1", "NIR", etc.).
     */
     fun extractPrimaryChannels(rawData: Map<String, Int>): Map<String, Int> {
         return sensorManager.extractPrimaryChannels(rawData)
     }


    /**
     * Controls the sensor's built-in LED.
     * Requires the sensor to be connected and initialized.
     * @param on True to turn the LED on, false to turn it off
     * @param current LED drive strength (0-127, see datasheet for mA mapping) when turning on
     */
    fun controlLED(on: Boolean, current: Int = 4) { // Default to 12mA (value 4)
        if (!isInitialized) {
             Log.e(TAG, "Cannot control LED: Sensor not initialized on $busPath.")
            return
        }

        if (on) {
            // Set current only when turning on, to avoid changing it when turning off
            sensorManager.setLEDCurrent(fileDescriptor, current)
        }
        // Use the correct function name from the updated SensorManager
        sensorManager.toggleLEDActivation(fileDescriptor, on)
    }

    /**
     * Checks if the sensor is connected *and* initialized.
     * @return true if connected and initialized, false otherwise
     */
    fun isReady(): Boolean {
        return isInitialized && fileDescriptor >= 0
    }

    /**
     * Returns the file descriptor of the I2C connection.
     * Useful for direct access via the SensorManager if needed, but check isReady() first.
     * @return I2C file descriptor, or -1 if not connected/initialized
     */
    fun getFileDescriptor(): Int {
        return if (isReady()) fileDescriptor else -1
    }

     // Optional: Expose configuration methods from SensorManager if needed by the app
     fun setGain(gainValue: Int) {
         if (!isReady()) {
             Log.e(TAG, "Cannot set gain: Sensor not initialized on $busPath.")
             return
         }
         sensorManager.setGain(fileDescriptor, gainValue)
     }

     fun setIntegrationTime(atime: Int, astep: Int) {
          if (!isReady()) {
             Log.e(TAG, "Cannot set integration time: Sensor not initialized on $busPath.")
             return
         }
         sensorManager.setIntegrationTime(fileDescriptor, atime, astep)
     }
}