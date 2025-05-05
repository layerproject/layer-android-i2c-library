package com.layer.i2c

import android.util.Log
import java.io.IOException

/**
 * Common base class for spectral sensors AS7341 and AS7343.
 * Defines the core functionality that both spectral sensor types must implement.
 */
abstract class AS73XXSensor(busPath: String) : I2CSensor(busPath) {
    companion object {
        protected const val TAG = "AS73XXSensor"
    }
    
    // Default address for AS73XX sensors
    override val sensorAddress: Int = 0x39
    
    // Abstract register and bit definitions that must be provided by child classes
    protected abstract val REG_ATIME: Int
    protected abstract val REG_ASTEP_L: Int
    protected abstract val REG_CONFIG0: Int      // Bank selection register
    protected abstract val BIT_REGBANK: Int      // Bank selection bit position
    protected abstract val REG_ENABLE: Int       // Enable register 
    protected abstract val BIT_POWER: Int        // Power bit position
    protected abstract val BIT_MEASUREMENT: Int  // Measurement enable bit position
    protected abstract val REG_CFG1: Int         // Gain configuration register

    /**
     * Reads all spectral channels from the sensor.
     * @return Map of channel names to values, or empty map if not connected
     */
    abstract fun readSpectralData(): Map<String, Int>

    /**
     * Closes the connection to the sensor and attempts to power it down.
     */
    override fun disconnect() {
        if (isBusOpen) {
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
            // Call parent's closeDevice() which handles all the cleanup
            super.disconnect()
        } else {
            Log.d(TAG, "Sensor on $busPath already disconnected.")
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
     * Sets the integration time for spectral measurements.
     */
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