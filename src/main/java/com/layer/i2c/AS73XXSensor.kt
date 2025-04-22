package com.layer.i2c

import android.util.Log
import java.io.IOException

/**
 * Common for spectral sensors AS7341 and AS7343.
 * Defines the core functionality that both sensor types must implement.
 */
abstract class AS73XXSensor(protected val busPath: String) {
    companion object {
        protected const val TAG = "AS73XXSensor"
        private const val SENSOR_ADDRESS = 0x39
    }

    protected var fileDescriptor: Int = -1
    protected var isInitialized: Boolean = false

    /**
     * Reads all spectral channels from the sensor.
     * @return Map of channel names to values, or empty map if not connected
     */
    abstract fun readSpectralData(): Map<String, Int>

    abstract fun togglePower(fd: Int, on: Boolean)

    abstract fun initializeSensor(fd: Int): Boolean

    /**
     * Checks if the sensor is connected *and* initialized.
     * @return true if connected and initialized, false otherwise
     */
    protected fun isReady(): Boolean {
        return isInitialized && fileDescriptor >= 0
    }

    /**
     * Opens an I2C connection to an AS7343 sensor on the specified bus.
     */
    private fun openSensor(busPath: String): Int {
        val fd = I2cNative.openBus(busPath, SENSOR_ADDRESS)
        if (fd < 0) {
            Log.e(TAG, "Failed to open I2C bus $busPath for address $SENSOR_ADDRESS")
        }
        return fd
    }

    /**
     * Closes an I2C connection.
     */
    private fun closeSensor(fd: Int) {
        if (fd >= 0) {
            I2cNative.closeBus(fd)
        }
    }

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
            closeSensor(fileDescriptor)
            fileDescriptor = -1
        }

        Log.d(TAG, "Connecting to sensor on $busPath...")
        fileDescriptor = openSensor(busPath)
        if (fileDescriptor < 0) {
            Log.e(TAG, "Failed to open sensor on $busPath.")
            isInitialized = false
            return false
        }

        Log.d(TAG, "Sensor opened (fd=$fileDescriptor). Initializing...")
        // *** Crucial step: Initialize the sensor after opening ***
        isInitialized = initializeSensor(fileDescriptor)

        if (!isInitialized) {
            Log.e(TAG, "Failed to initialize sensor on $busPath (fd=$fileDescriptor). Closing.")
            closeSensor(fileDescriptor)
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
                    togglePower(fileDescriptor, false)
                    Log.d(TAG, "Sensor powered down.")
                } catch (e: Exception) {
                    Log.w(TAG, "Error powering down sensor during disconnect: ${e.message}")
                }
            }
            closeSensor(fileDescriptor)
            fileDescriptor = -1
            isInitialized = false
            Log.i(TAG, "Sensor on $busPath disconnected.")
        } else {
            Log.d(TAG, "Sensor on $busPath already disconnected.")
        }
    }

    // --- I2C Primitive Helpers ---

    protected fun enableBit(fd: Int, register: Int, bit: Int, on: Boolean) {
        val regValue = readByteReg(fd, register)
        val bitMask = (1 shl bit)
        val newValue = if (on) regValue or bitMask else regValue and bitMask.inv()
        if (newValue != regValue) {
            writeByteReg(fd, register, newValue)
        }
    }

    protected fun setRegisterBits(fd: Int, register: Int, shift: Int, width: Int, value: Int) {
        var regValue = readByteReg(fd, register)
        val mask = ((1 shl width) - 1) shl shift
        regValue = (regValue and mask.inv()) or ((value shl shift) and mask)
        writeByteReg(fd, register, regValue)
    }

    protected fun readByteReg(fd: Int, register: Int): Int {
        // Assuming I2cNative.readWord handles setting the register address implicitly
        val result = I2cNative.readWord(fd, register)
        if (result < 0) {
            throw IOException("I2C Read Error on fd=$fd, reg=0x${register.toString(16)}, code=$result")
        }
        return result and 0xFF
    }

    protected fun writeByteReg(fd: Int, register: Int, value: Int) {
        val result = I2cNative.writeByte(fd, register, value)
        if (result < 0) {
            throw IOException("I2C Write Error on fd=$fd, reg=0x${register.toString(16)}, value=0x${value.toString(16)}, code=$result")
        }
    }

    protected fun writeWordReg(fd: Int, lsbRegister: Int, value: Int) {
        val lsb = value and 0xFF
        val msb = (value shr 8) and 0xFF
        // Write LSB first, then MSB
        writeByteReg(fd, lsbRegister, lsb)
        writeByteReg(fd, lsbRegister + 1, msb)
    }
}
