package com.layer.i2c

import android.util.Log
import java.io.IOException

/**
 * High-level interface for the AS7341 spectral sensor.
 * Provides convenient methods for sensor operations.
 */
class AS7341Sensor(private val busPath: String) {
    companion object {
        private const val TAG = "AS7341Sensor"

        // AS7341 sensor address - typically this is fixed at 0x39
        private const val AS7341_ADDRESS = 0x39

        // AS7341 registers
        private const val REG_ENABLE = 0x80
        private const val REG_STATUS2 = 0xa3
        private const val REG_CONFIG0 = 0xa9
        private const val REG_LED_CONFIG = 0x74
        private const val REG_CONFIG = 0x70
        private const val REG_CFG6 = 0xaf

        // Register data start addresses
        private const val REG_CH0_LOW = 0x95
        private const val REG_CH0_HIGH = 0x96

        // Bit positions
        private const val BIT_POWER = 0
        private const val BIT_MEASUREMENT = 1
        private const val BIT_SMUXEN = 4
        private const val BIT_REGBANK = 4
        private const val BIT_LED_SEL = 3
        private const val BIT_LED_ACT = 4
        private const val BIT_AVALID = 6
    }

    private var fileDescriptor: Int = -1
    private var isConnected: Boolean = false
    
    /**
     * Opens a connection to the sensor.
     * @return true if connection was successful, false otherwise
     */
    fun connect(): Boolean {
        Log.d(TAG, "Connecting sensor")
        if (isConnected) {
            return true
        }
        
        fileDescriptor = openSensor(busPath)
        isConnected = fileDescriptor >= 0
        return isConnected
    }
    
    /**
     * Closes the connection to the sensor.
     */
    fun disconnect() {
        if (isConnected) {
            closeSensor(fileDescriptor)
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
            val data = readAllChannels(fileDescriptor)
            Log.d(TAG, "Sensor read spectral data: $data")
            data
        } else {
            Log.e(TAG, "Sensor not connected, cannot read data")
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
            setLEDCurrent(fileDescriptor, current)
        }
        toggleLED(fileDescriptor, on)
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

    /**
     * Opens an I2C connection to an AS7341 sensor on the specified bus.
     *
     * @param busPath The path to the I2C bus (e.g. "/dev/i2c-0")
     * @return File descriptor for the opened bus, or -1 if failed
     */
    fun openSensor(busPath: String): Int {
        return I2cNative.openBus(busPath, AS7341_ADDRESS)
    }

    /**
     * Closes an I2C connection.
     *
     * @param fd File descriptor of the I2C connection to close
     */
    fun closeSensor(fd: Int) {
        I2cNative.closeBus(fd)
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

    /**
     * Toggles the power state of the sensor.
     *
     * @param fd File descriptor for the I2C connection
     * @param on True to power on, false to power off
     */
    fun togglePower(fd: Int, on: Boolean) {
        enable(fd, BIT_POWER, on)
    }

    /**
     * Sets the LED current for the sensor.
     *
     * @param fd File descriptor for the I2C connection
     * @param current Current value (0-127)
     */
    fun setLEDCurrent(fd: Int, current: Int) {
        setBank(fd, true)

        val ledRegister = REG_LED_CONFIG
        val ledWord = I2cNative.readWord(fd, ledRegister)
        Log.d(TAG, "LED current read: $ledWord")

        var data = 0
        data = data or (1 shl 7)
        data = data or (current and 0x7f)

        val ledRet = I2cNative.writeByte(fd, ledRegister, data)
        Log.d(TAG, "LED current write result: $ledRet")

        setBank(fd, false)
    }

    /**
     * Toggles the LED state.
     *
     * @param fd File descriptor for the I2C connection
     * @param on True to turn LED on, false to turn off
     */
    fun toggleLED(fd: Int, on: Boolean) {
        val configRegister = REG_CONFIG
        val ledRegister = REG_LED_CONFIG

        setBank(fd, true)

        val configWord = I2cNative.readWord(fd, configRegister)
        val configWordWrite = if (on) {
            configWord or (1 shl BIT_LED_SEL)
        } else {
            configWord and (1 shl BIT_LED_SEL).inv()
        }

        Log.d(TAG, "Config read: $configWord")
        Log.d(TAG, "Config write: $configWordWrite")

        val configRet = I2cNative.writeByte(fd, configRegister, configWordWrite)
        Log.d(TAG, "Config write result: $configRet")

        val ledWord = I2cNative.readWord(fd, ledRegister)
        val ledWordWrite = if (on) {
            ledWord or (1 shl BIT_LED_ACT)
        } else {
            ledWord and (1 shl BIT_LED_ACT).inv()
        }

        Log.d(TAG, "LED read: $ledWord")
        Log.d(TAG, "LED write: $ledWordWrite")

        val ledRet = I2cNative.writeByte(fd, ledRegister, ledWordWrite)
        Log.d(TAG, "LED write result: $ledRet")

        setBank(fd, false)
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

        Log.d(TAG, "readChannel: $dataLReg $dataHReg $dataL $dataH")

        return ((dataH and 0xFF) shl 8) or (dataL and 0xFF)
    }

    private fun readByteReg(fd: Int, register: Int): Int {
        // Assuming I2cNative.readWord handles setting the register address implicitly
        val result = I2cNative.readWord(fd, register)
        if (result < 0) {
            throw IOException("I2C Read Error on fd=$fd, reg=0x${register.toString(16)}, code=$result")
        }
        return result and 0xFF
    }

    /**
     * Sets the register bank.
     * Some registers are accessible only when the register bank bit is set.
     */
    private fun setBank(fd: Int, access0x60to0x74: Boolean) {
        val configWord = I2cNative.readWord(fd, REG_CONFIG0)
        val configWordWrite = if (access0x60to0x74) {
            configWord or (1 shl BIT_REGBANK)
        } else {
            configWord and (1 shl BIT_REGBANK).inv()
        }

        Log.d(TAG, "Bank config read: $configWord")
        Log.d(TAG, "Bank config write: $configWordWrite")

        val configRet = I2cNative.writeByte(fd, REG_CONFIG0, configWordWrite)
        Log.d(TAG, "Bank config write result: $configRet")
    }

    /**
     * Enables or disables the SMUX (Sensor Mux) functionality.
     */
    private fun enableSMUX(fd: Int, on: Boolean) {
        enable(fd, BIT_SMUXEN, on)
    }

    /**
     * Helper function to enable or disable a specific bit in the enable register.
     */
    private fun enable(fd: Int, bit: Int, on: Boolean) {
        val enableWord = I2cNative.readWord(fd, REG_ENABLE)
        Log.d(TAG, "Enable read: $enableWord")

        val enableWordWrite = if (on) {
            enableWord or (1 shl bit)
        } else {
            enableWord and (1 shl bit).inv()
        }

        Log.d(TAG, "Enable write: $enableWordWrite")

        val enableRet = I2cNative.writeByte(fd, REG_ENABLE, enableWordWrite)
        Log.d(TAG, "Enable write result: $enableRet")
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

    /**
     * Enables or disables spectral measurement.
     */
    private fun enableSpectralMeasurement(fd: Int, enableMeasurement: Boolean) {
        enable(fd, BIT_MEASUREMENT, enableMeasurement)
    }
}