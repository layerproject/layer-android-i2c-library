package com.layer.i2c

import android.util.Log

/**
 * High-level interface for the AS7341 spectral sensor.
 * Provides convenient methods for sensor operations.
 */
class AS7341Sensor(busPath: String) : AS73XXSensor(busPath) {
    // Implement abstract register properties
    override val REG_ATIME: Int = 0x81        // Integration Time ADC cycles LSB 
    override val REG_ASTEP_L: Int = 0xCA      // Integration Time Step Size LSB (16-bit)
    override val REG_CONFIG0: Int = 0xa9      // Bank selection register
    override val BIT_REGBANK: Int = 4         // Bank selection bit position
    override val REG_ENABLE: Int = 0x80       // Enable register 
    override val BIT_POWER: Int = 0           // Power bit position
    override val BIT_MEASUREMENT: Int = 1     // Measurement enable bit position
    override val REG_CFG1: Int = 0xaa         // Gain configuration register
    
    companion object {
        private const val TAG = "AS7341Sensor"

        private const val AS7341_ID = 0x92

        // AS7341 registers
        private const val REG_STATUS2 = 0xa3
        private const val REG_LED_CONFIG = 0x74
        private const val REG_CONFIG = 0x70
        private const val REG_CFG6 = 0xaf

        // Register data start addresses
        private const val REG_CH0_LOW = 0x95
        private const val REG_CH0_HIGH = 0x96

        // Bit positions
        private const val BIT_SMUXEN = 4
        private const val BIT_LED_SEL = 3
        private const val BIT_LED_ACT = 4
        private const val BIT_AVALID = 6
    }

    override fun isCorrectSensor(): Boolean {
        return (readID() == 36)
    }
    
    /**
     * Reads all spectral channels from the sensor.
     * @return Map of channel names to values, or empty map if not connected
     */
    override fun readSpectralData(): Map<String, Int> {
        return if (isInitialized) {
            val data = readAllChannels(fileDescriptor)
            Log.d(TAG, "Sensor read spectral data: $data")
            data
        } else {
            Log.e(TAG, "Sensor not connected, cannot read data")
            emptyMap()
        }
    }

    /**
     * Reads all spectral channels from the sensor.
     *
     * @param fd File descriptor for the I2C connection
     * @return Map containing the spectral channel values
     */
    fun readAllChannels(fd: Int): Map<String, Int> {
        // Enable power
        togglePower(fd, true)

        // Read low channels (F1-F4)
        setSMUXLowChannels(fd, true)
        enableSpectralMeasurement(fd, true)

        while (!getIsDataReady(fd)) {
            Log.d(TAG, "Awaiting I2C data on low channels")
            Thread.sleep(10)
        }

        val channelDataOne = readSpectralDataOne(fd)

        // Read high channels (F5-F8 plus Clear and NIR)
        setSMUXLowChannels(fd, false)
        enableSpectralMeasurement(fd, true)

        while (!getIsDataReady(fd)) {
            Log.d(TAG, "Awaiting I2C data on high channels")
            Thread.sleep(10)
        }

        val channelDataTwo = readSpectralDataTwo(fd)

        // Combine results and return
        return channelDataOne + channelDataTwo
    }

    override fun initializeSensor(fd: Int): Boolean {
        if (fd < 0) return false
        
        try {
            // Power up the device
            togglePower(fd, true)
            Thread.sleep(5) // Short delay after power on
            
            // Set default gain
            val gainValue = 10 // Higher gain for better low-light sensitivity
            Log.d(TAG, "Setting gain to $gainValue")
            setGain(fd, gainValue)
            
            // Set integration time
            setIntegrationTime(fd, 0, 65534)
            
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error during sensor initialization for fd=$fd: ${e.message}", e)
            try { togglePower(fd, false) } catch (_: Exception) {}
            return false
        }
    }

    /**
     * Checks if sensor data is ready to be read.
     */
    private fun getIsDataReady(fd: Int): Boolean {
        val status = I2cNative.readWord(fd, REG_STATUS2)
        Log.d(TAG, "Status read: $status")
        return status and (1 shl BIT_AVALID) != 0
    }

    /**
     * Reads the first set of spectral data channels (F1-F4).
     */
    private fun readSpectralDataOne(fd: Int): MutableMap<String, Int> {
        val channelData = mutableMapOf<String, Int>()
        channelData["F1"] = readChannel(fd, 0)
        channelData["F2"] = readChannel(fd, 1)
        channelData["F3"] = readChannel(fd, 2)
        channelData["F4"] = readChannel(fd, 3)
        return channelData
    }

    /**
     * Reads the second set of spectral data channels (F5-F8, Clear, NIR).
     */
    private fun readSpectralDataTwo(fd: Int): MutableMap<String, Int> {
        val channelData = mutableMapOf<String, Int>()
        channelData["F5"] = readChannel(fd, 0)
        channelData["F6"] = readChannel(fd, 1)
        channelData["F7"] = readChannel(fd, 2)
        channelData["F8"] = readChannel(fd, 3)
        channelData["Clear"] = readChannel(fd, 4)
        channelData["NIR"] = readChannel(fd, 5)
        return channelData
    }

    /**
     * Reads a single channel from the sensor.
     *
     * @param fd File descriptor for the I2C connection
     * @param channel Channel number (0-5)
     * @return Channel value as a 16-bit integer
     */
    private fun readChannel(fd: Int, channel: Int): Int {
        val dataLReg = REG_CH0_LOW + (channel * 2)
        val dataHReg = dataLReg + 1
        val dataL = readByteReg(fd, dataLReg)
        val dataH = readByteReg(fd, dataHReg)

        return ((dataH and 0xFF) shl 8) or (dataL and 0xFF)
    }

    /**
     * Enables or disables the SMUX (Sensor Mux) functionality.
     */
    private fun enableSMUX(fd: Int, on: Boolean) {
        enableBit(fd, REG_ENABLE, BIT_SMUXEN, on)
    }

    /**
     * Configures the sensor to read either low channels (F1-F4) or high channels (F5-F8).
     *
     * @param fd File descriptor for the I2C connection
     * @param f1Tof4 True to read F1-F4, false to read F5-F8
     */
    private fun setSMUXLowChannels(fd: Int, f1Tof4: Boolean) {
        enableSpectralMeasurement(fd, false)
        setSMUXCommand(fd, 0x10) // Write SMUX configuration from RAM to SMUX chain

        if (f1Tof4) {
            setupF1F4ClearNIR(fd)
        } else {
            setupF5F8ClearNIR(fd)
        }

        enableSMUX(fd, true)
    }

    /**
     * Sets the SMUX command.
     */
    private fun setSMUXCommand(fd: Int, command: Int) {
        val smuxCommand = I2cNative.writeByte(fd, REG_CFG6, command)
        Log.d(TAG, "SMUX command result: $smuxCommand")
    }

    /**
     * Sets up sensor configuration for F1-F4 channels.
     */
    private fun setupF1F4ClearNIR(fd: Int) {
        val registerValues = mapOf(
            0x00 to 0x30, 0x01 to 0x01, 0x02 to 0x00, 0x03 to 0x00,
            0x04 to 0x00, 0x05 to 0x42, 0x06 to 0x00, 0x07 to 0x00,
            0x08 to 0x50, 0x09 to 0x00, 0x0a to 0x00, 0x0b to 0x00,
            0x0c to 0x20, 0x0d to 0x04, 0x0e to 0x00, 0x0f to 0x30,
            0x10 to 0x01, 0x11 to 0x50, 0x12 to 0x00, 0x13 to 0x06
        )

        registerValues.forEach { (register, value) ->
            I2cNative.writeByte(fd, register, value)
        }
    }

    /**
     * Sets up sensor configuration for F5-F8 channels plus Clear and NIR.
     */
    private fun setupF5F8ClearNIR(fd: Int) {
        val registerValues = mapOf(
            0x00 to 0x00, 0x01 to 0x00, 0x02 to 0x00, 0x03 to 0x40,
            0x04 to 0x02, 0x05 to 0x00, 0x06 to 0x10, 0x07 to 0x03,
            0x08 to 0x50, 0x09 to 0x10, 0x0a to 0x03, 0x0b to 0x00,
            0x0c to 0x00, 0x0d to 0x00, 0x0e to 0x24, 0x0f to 0x00,
            0x10 to 0x00, 0x11 to 0x50, 0x12 to 0x00, 0x13 to 0x06
        )

        registerValues.forEach { (register, value) ->
            I2cNative.writeByte(fd, register, value)
        }
    }

    fun readID(): Int {
        if (!isInitialized) {
            return -1
        }

        val id = readByteReg(fileDescriptor, AS7341_ID)
        Log.d(TAG, "Reading sensor ID: $id")

        return id
    }
}