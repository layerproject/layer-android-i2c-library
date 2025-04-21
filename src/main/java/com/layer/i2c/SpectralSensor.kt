package com.layer.i2c

/**
 * Common interface for spectral sensors like AS7341 and AS7343.
 * Defines the core functionality that both sensor types must implement.
 */
interface SpectralSensor {
    /**
     * Opens a connection to the sensor.
     * @return true if connection was successful, false otherwise
     */
    fun connect(): Boolean

    /**
     * Closes the connection to the sensor.
     */
    fun disconnect()

    /**
     * Reads all spectral channels from the sensor.
     * @return Map of channel names to values, or empty map if not connected
     */
    fun readSpectralData(): Map<String, Int>

    /**
     * Checks if the sensor is ready.
     * @return true if ready, false otherwise
     */
    fun isReady(): Boolean

    /**
     * Returns the file descriptor of the I2C connection.
     * @return I2C file descriptor, or -1 if not connected
     */
    fun getFileDescriptor(): Int
}
