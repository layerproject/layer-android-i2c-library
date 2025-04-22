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
    
    // Abstract register and bit definitions that must be provided by child classes
    protected abstract val REG_ATIME: Int
    protected abstract val REG_ASTEP_L: Int
    protected abstract val REG_CONFIG0: Int      // Bank selection register
    protected abstract val BIT_REGBANK: Int      // Bank selection bit position
    protected abstract val REG_ENABLE: Int       // Enable register 
    protected abstract val BIT_POWER: Int        // Power bit position
    protected abstract val BIT_MEASUREMENT: Int  // Measurement enable bit position
    protected abstract val REG_CFG1: Int         // Gain configuration register

    protected var fileDescriptor: Int = -1
    protected var isInitialized: Boolean = false

    /**
     * Reads all spectral channels from the sensor.
     * @return Map of channel names to values, or empty map if not connected
     */
    abstract fun readSpectralData(): Map<String, Int>

    abstract fun initializeSensor(fd: Int): Boolean

    /**
     * Checks if the sensor is connected *and* initialized.
     * @return true if connected and initialized, false otherwise
     */
    fun isReady(): Boolean {
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

    protected fun setIntegrationTime(fd: Int, atime: Int, astep: Int) {
        if (fd < 0) return
        val safeAtime = atime.coerceIn(0, 255)
        val safeAstep = astep.coerceIn(0, 65534)
        if (safeAtime == 0 && safeAstep == 0) {
            Log.e(TAG, "ATIME and ASTEP cannot both be 0. Setting ASTEP=1.")
            setIntegrationTimeInternal(fd, 0, 1)
            return
        }
        setIntegrationTimeInternal(fd, safeAtime, safeAstep)
    }

    private fun setIntegrationTimeInternal(fd: Int, atime: Int, astep: Int) {
        try {
            Log.d(TAG, "Setting ATIME=$atime, ASTEP=$astep on fd=$fd")
            setBank(fd, false) // Ensure Bank 0
            writeByteReg(fd, REG_ATIME, atime)
            writeWordReg(fd, REG_ASTEP_L, astep)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting integration time for fd=$fd: ${e.message}", e)
        }
    }
    
    /**
     * Sets the register bank.
     * Some registers are accessible only when the register bank bit is set.
     * 
     * @param fd File descriptor for the I2C connection
     * @param useBank1 True to select Bank 1, false to select Bank 0
     */
    protected fun setBank(fd: Int, useBank1: Boolean) {
        try {
            val configWord = readByteReg(fd, REG_CONFIG0)
            val currentBank = (configWord shr BIT_REGBANK) and 1
            val targetBank = if (useBank1) 1 else 0
            
            if (currentBank != targetBank) {
                Log.d(TAG, "Setting Register Bank Access to $targetBank on fd=$fd")
                enableBit(fd, REG_CONFIG0, BIT_REGBANK, useBank1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting bank for fd=$fd: ${e.message}", e)
        }
    }
    
    /**
     * Toggles the power state of the sensor.
     *
     * @param fd File descriptor for the I2C connection
     * @param on True to power on, false to power off
     */
    protected fun togglePower(fd: Int, on: Boolean) {
        if (fd < 0) return
        try {
            // Power control is in Bank 0, ensure it's selected
            setBank(fd, false)
            Log.d(TAG, "Setting Power ${if (on) "ON" else "OFF"} on fd=$fd")
            enableBit(fd, REG_ENABLE, BIT_POWER, on)
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling power for fd=$fd: ${e.message}", e)
        }
    }
    
    /**
     * Enables or disables spectral measurement.
     */
    protected fun enableSpectralMeasurement(fd: Int, enableMeasurement: Boolean) {
        try {
            setBank(fd, false) // Ensure Bank 0
            if (enableMeasurement) {
                // Make sure power is on before enabling measurement
                val enableReg = readByteReg(fd, REG_ENABLE)
                if (enableReg and (1 shl BIT_POWER) == 0) {
                    Log.w(TAG, "Warning: Enabling measurement while power is OFF. Enabling power first.")
                    togglePower(fd, true)
                    Thread.sleep(1) // Small delay after power on
                }
            }
            enableBit(fd, REG_ENABLE, BIT_MEASUREMENT, enableMeasurement)
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling measurement for fd=$fd: ${e.message}", e)
        }
    }
    
    /**
     * Sets the sensor gain.
     * @param fd File descriptor for the I2C connection
     * @param againValue Gain value (typically 0-12, where higher values mean higher sensitivity)
     *   0=0.5x, 4=16x, 8=128x, 9=256x(default), 10=512x, 12=2048x
     */
    protected fun setGain(fd: Int, againValue: Int) {
        if (fd < 0) return
        val safeAgain = againValue.coerceIn(0, 12)
        try {
            Log.d(TAG, "Setting Gain (AGAIN) on fd=$fd to $safeAgain")
            setBank(fd, false) // Ensure Bank 0
            setRegisterBits(fd, REG_CFG1, 0, 5, safeAgain)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting gain for fd=$fd: ${e.message}", e)
        }
    }
}
