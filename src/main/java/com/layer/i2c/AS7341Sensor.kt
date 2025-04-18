package com.layer.i2c

/**
 * High-level interface for the AS7341 spectral sensor.
 * Provides convenient methods for sensor operations.
 */
class AS7341Sensor(private val busPath: String) {
    private val sensorManager = SensorManager()
    private var fileDescriptor: Int = -1
    private var isConnected: Boolean = false
    
    /**
     * Opens a connection to the sensor.
     * @return true if connection was successful, false otherwise
     */
    fun connect(): Boolean {
        if (isConnected) {
            return true
        }
        
        fileDescriptor = sensorManager.openSensor(busPath)
        isConnected = fileDescriptor >= 0
        return isConnected
    }
    
    /**
     * Closes the connection to the sensor.
     */
    fun disconnect() {
        if (isConnected) {
            sensorManager.closeSensor(fileDescriptor)
            fileDescriptor = -1
            isConnected = false
        }
    }
    
    /**
     * Reads all spectral channels from the sensor.
     * @return Map of channel names to values, or empty map if not connected
     */
    fun readSpectralData(): Map<String, Int> {
        return if (isConnected) {
            sensorManager.readAllChannels(fileDescriptor)
        } else {
            emptyMap()
        }
    }
    
    /**
     * Controls the sensor's built-in LED.
     * @param on True to turn the LED on, false to turn it off
     * @param current LED current (0-127) when turning on
     */
    fun controlLED(on: Boolean, current: Int = 30) {
        if (!isConnected) {
            return
        }
        
        if (on) {
            sensorManager.setLEDCurrent(fileDescriptor, current)
        }
        sensorManager.toggleLED(fileDescriptor, on)
    }
    
    /**
     * Checks if the sensor is connected.
     * @return true if connected, false otherwise
     */
    fun isConnected(): Boolean {
        return isConnected
    }
    
    /**
     * Returns the file descriptor of the I2C connection.
     * Useful for direct access via the SensorManager if needed.
     * @return I2C file descriptor, or -1 if not connected
     */
    fun getFileDescriptor(): Int {
        return fileDescriptor
    }
}