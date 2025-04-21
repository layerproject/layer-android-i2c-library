package com.layer.i2c

import android.util.Log
import java.io.IOException

/**
 * High-level interface for the AS7343 spectral sensor.
 * Provides convenient methods for sensor operations.
 */
class AS7343Sensor(private val busPath: String) {
    companion object {
        private const val TAG = "AS7343Sensor"
        // AS7343 sensor address - Confirmed 0x39
        private const val AS7343_ADDRESS = 0x39

        // --- AS7343 Register Addresses (Verified from Datasheet) ---
        private const val AS7343_AUXID = 0x58
        private const val AS7343_REVID = 0x59
        private const val AS7343_ID = 0x5A

        // Configuration Registers (Bank 0 unless noted)
        private const val AS7343_ENABLE_REG = 0x80      // Enable Register
        private const val AS7343_ATIME_REG = 0x81       // Integration Time ADC cycles LSB
        private const val AS7343_WTIME_REG = 0x83       // Wait Time cycles
        private const val AS7343_ASTEP_L_REG = 0xD4     // Integration Time Step Size LSB (16-bit)
        private const val AS7343_ASTEP_H_REG = 0xD5     // Integration Time Step Size MSB (16-bit)
        private const val AS7343_CFG0_REG = 0xBF        // Bank Select, Low Power Idle, WLONG
        private const val AS7343_CFG1_REG = 0xC6        // AGAIN (Spectral Gain)
        private const val AS7343_CFG20_REG = 0xD6       // auto_smux setting, FD FIFO 8b mode
        private const val AS7343_LED_REG = 0xCD         // LED Control (ACT, DRIVE) (Bank 1)

        // Status Registers (Bank 0)
        private const val AS7343_STATUS2_REG = 0x90     // Secondary Status (AVALID, Saturation flags)
        private const val AS7343_ASTATUS_REG = 0x94     // Latched Gain/Saturation for DATA read

        // Data Registers (Bank 0)
        private const val AS7343_DATA0_L_REG = 0x95      // Base address for DATA_0_L

        // Register Bits
        private const val AS7343_ENABLE_FDEN_BIT = 6      // Flicker Detect Enable
        private const val AS7343_ENABLE_WEN_BIT = 3       // Wait Enable
        private const val AS7343_ENABLE_SP_EN_BIT = 1     // Spectral Measurement Enable
        private const val AS7343_ENABLE_PON_BIT = 0       // Power ON
        private const val AS7343_STATUS2_AVALID_BIT = 6   // Spectral Data Valid
        private const val AS7343_CFG0_REG_BANK_BIT = 4    // Register Bank Select (0=0x80+, 1=0x20-0x7F)
        private const val AS7343_CFG20_AUTO_SMUX_SHIFT = 5
        private const val AS7343_AUTO_SMUX_MODE_18CH = 3 // Value for 18-channel read
        private const val AS7343_LED_LED_ACT_BIT = 7      // LED Activation
        private const val AS7343_LED_LED_DRIVE_MASK = 0x7F // Mask for LED_DRIVE[6:0] bits

        // Channel Count
        private const val AS7343_NUM_DATA_REGISTERS = 18

        // Channel names corresponding to DATA_0 through DATA_17 registers when auto_smux=3
        val dataRegisterNames = listOf(
            "FZ (Data 0)", "FY (Data 1)", "FXL (Data 2)", "NIR (Data 3)", "VIS_C1 (Data 4)", "FD_C1 (Data 5)",
            "F2 (Data 6)", "F3 (Data 7)", "F4 (Data 8)", "F6 (Data 9)", "VIS_C2 (Data 10)", "FD_C2 (Data 11)",
            "F1 (Data 12)", "F7 (Data 13)", "F8 (Data 14)", "F5 (Data 15)", "VIS (Data 16)", "FD (Data 17)"
        )
        // Define the primary channels most users will want (used by helper)
        val primaryChannelKeys = listOf(
            "F1 (Data 12)", "F2 (Data 6)", "F3 (Data 7)", "F4 (Data 8)", "F5 (Data 15)",
            "F6 (Data 9)", "F7 (Data 13)", "F8 (Data 14)", "FZ (Data 0)", "FY (Data 1)",
            "FXL (Data 2)", "NIR (Data 3)", "VIS (Data 16)", "FD (Data 17)"
        )
        // Simple names for the primary channels extracted by the helper
        val primaryChannelSimpleNames = listOf(
            "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "FZ", "FY", "FXL", "NIR", "VIS", "FD"
        )
    }

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

        readIDs()

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

    fun readIDs(): Map<String, String> {
        if (!connect()) {

        } else {
            val auxid = readByteReg(fileDescriptor, AS7343_AUXID)
            Log.d(TAG, "Reading sensor AUXID: $auxid")
            val revid = readByteReg(fileDescriptor, AS7343_ID)
            Log.d(TAG, "Reading sensor REVID: $revid")
            val id = readByteReg(fileDescriptor, AS7343_ID)
            Log.d(TAG, "Reading sensor ID: $id")
        }

        return emptyMap()
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
        val rawData = readAllChannels(fileDescriptor)
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

        val rawData = readAllChannels(fileDescriptor)

        if (rawData.isEmpty()) {
            Log.w(TAG, "Read failed or returned empty data for $busPath.")
            return emptyMap()
        } else {
            Log.d(TAG, "Raw data was read: $rawData")
        }

        return extractPrimaryChannels(rawData) // Return the filtered primary channels
    }

    /**
     * Helper to extract the primary 14 channels using the SensorManager's logic.
     * @param rawData The map containing all 18 register readings.
     * @return Map containing primary channels with simplified names ("F1", "NIR", etc.).
     */
    private fun extractPrimaryChannels(rawData: Map<String, Int>): Map<String, Int> {
        val primaryMap = mutableMapOf<String, Int>()
        primaryChannelKeys.forEachIndexed { index, key ->
            val simpleName = primaryChannelSimpleNames.getOrElse(index) { key } // Use simple name
            primaryMap[simpleName] = rawData[key] ?: 0 // Get value using the register-based key
        }
        return primaryMap
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
            setLEDCurrent(fileDescriptor, current)
        }
        // Use the correct function name from the updated SensorManager
        toggleLEDActivation(fileDescriptor, on)
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
        setGain(fileDescriptor, gainValue)
    }

    fun setIntegrationTime(atime: Int, astep: Int) {
        if (!isReady()) {
            Log.e(TAG, "Cannot set integration time: Sensor not initialized on $busPath.")
            return
        }
        setIntegrationTime(fileDescriptor, atime, astep)
    }

    /**
     * Opens an I2C connection to an AS7343 sensor on the specified bus.
     */
    fun openSensor(busPath: String): Int {
        val fd = I2cNative.openBus(busPath, AS7343_ADDRESS)
        if (fd < 0) {
            Log.e(TAG, "Failed to open I2C bus $busPath for address $AS7343_ADDRESS")
        }
        return fd
    }

    /**
     * Closes an I2C connection.
     */
    fun closeSensor(fd: Int) {
        if (fd >= 0) {
            I2cNative.closeBus(fd)
        }
    }

    /**
     * Initializes the AS7343 sensor with default settings for measurement.
     * MUST be called after opening the sensor.
     * @param fd File descriptor for the I2C connection
     * @return true if initialization succeeds, false otherwise.
     */
    fun initializeSensor(fd: Int): Boolean {
        if (fd < 0) return false
        Log.d(TAG, "Initializing sensor on fd=$fd...")
        try {
            setBank(fd, false) // Ensure Bank 0
            togglePower(fd, true)
            Thread.sleep(5) // Short delay after power on

            // Configure auto_smux for 18-channel readout
            setRegisterBits(fd, AS7343_CFG20_REG, AS7343_CFG20_AUTO_SMUX_SHIFT, 2, AS7343_AUTO_SMUX_MODE_18CH)
            Log.d(TAG, "Set auto_smux mode to 18-channel (3)")

            // --- Sensor Configuration Example ---
            // Set default integration time (~100ms)
            // Integration Time determines how long the sensor collects light for a measurement.
            // It is calculated as: tint = (ATIME + 1) * (ASTEP + 1) * 2.78 microseconds.
            //
            // For example:
            // - If you want approximately 100ms integration time:
            //   ASTEP = 999 → each step ≈ 2.78ms
            //   Then ATIME = (100ms / 2.78ms) - 1 ≈ 35
            //
            // So we use the following values to get ~100ms total exposure time:
            setIntegrationTime(fd, atime = 35, astep = 999)

            // Gain: AGAIN (0=0.5x, 9=256x(default), 12=2048x)
            setGain(fd, againValue = 9)

            Log.d(TAG, "Sensor fd=$fd initialized successfully.")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error during sensor initialization for fd=$fd: ${e.message}", e)
            // Attempt to power off on failure
            try { togglePower(fd, false) } catch (_: Exception) {}
            return false
        }
    }

    /**
     * Reads all 18 data registers from the sensor after triggering a measurement.
     * Assumes sensor is initialized.
     * @param fd File descriptor for the I2C connection
     * @return Map containing all 18 data register values (using names from dataRegisterNames), or empty map on error.
     */
    private fun readAllChannels(fd: Int): Map<String, Int> {
        if (fd < 0) return emptyMap()

        val channelData = mutableMapOf<String, Int>()
        try {
            setBank(fd, false) // Ensure Bank 0
            Log.d(TAG, "Starting spectral measurement on fd=$fd")

            // 1. Enable Spectral Measurement
            enableSpectralMeasurement(fd, true)

            // 2. Wait for Data Ready
            if (!waitForDataReady(fd, 2000)) { // Use helper with timeout
                Log.e(TAG,"Timeout waiting for data ready on fd=$fd")
                enableSpectralMeasurement(fd, false) // Ensure measurement is disabled
                return emptyMap() // Return empty on timeout
            }
            Log.d(TAG, "Data ready on fd=$fd")

            // 3. Read ASTATUS
            val aStatus = readByteReg(fd, AS7343_ASTATUS_REG)
            // Log.d(TAG, "fd=$fd ASTATUS = 0x${aStatus.toString(16)}") // Optional verbose log

            // 4. Read Data Registers
            for (i in 0 until AS7343_NUM_DATA_REGISTERS) {
                val value = readDataChannel(fd, i)
                val name = dataRegisterNames.getOrElse(i) { "Unknown_Data_$i" }
                channelData[name] = value
            }

            // 5. Disable Spectral Measurement
            enableSpectralMeasurement(fd, false)
            Log.d(TAG, "Spectral measurement finished on fd=$fd")

            return channelData

        } catch (e: Exception) {
            Log.e(TAG, "Error during readAllChannels for fd=$fd: ${e.message}", e)
            // Attempt to disable measurement on error
            try { enableSpectralMeasurement(fd, false) } catch (_: Exception) {}
            return emptyMap() // Return empty on error
        }
    }

    // --- Configuration Methods ---

    private fun setIntegrationTime(fd: Int, atime: Int, astep: Int) {
        if (fd < 0) return
        val safeAtime = atime.coerceIn(0, 255)
        val safeAstep = astep.coerceIn(0, 65534)
        if (safeAtime == 0 && safeAstep == 0) {
            Log.e(TAG, "ATIME and ASTEP cannot both be 0. Setting ASTEP=1.")
            _setIntegrationTimeInternal(fd, 0, 1)
            return
        }
        _setIntegrationTimeInternal(fd, safeAtime, safeAstep)
    }

    private fun _setIntegrationTimeInternal(fd: Int, atime: Int, astep: Int) {
        try {
            Log.d(TAG, "Setting ATIME=$atime, ASTEP=$astep on fd=$fd")
            setBank(fd, false) // Ensure Bank 0
            writeByteReg(fd, AS7343_ATIME_REG, atime)
            writeWordReg(fd, AS7343_ASTEP_L_REG, astep)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting integration time for fd=$fd: ${e.message}", e)
        }
    }

    fun setGain(fd: Int, againValue: Int) {
        if (fd < 0) return
        val safeAgain = againValue.coerceIn(0, 12)
        try {
            Log.d(TAG, "Setting Gain (AGAIN) on fd=$fd to $safeAgain")
            setBank(fd, false) // Ensure Bank 0
            setRegisterBits(fd, AS7343_CFG1_REG, 0, 5, safeAgain)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting gain for fd=$fd: ${e.message}", e)
        }
    }

    // --- LED Control Methods ---

    fun setLEDCurrent(fd: Int, driveValue: Int) {
        if (fd < 0) return
        val safeDriveValue = driveValue.coerceIn(0, 127)
        try {
            Log.d(TAG, "Setting LED Current on fd=$fd to setting $safeDriveValue")
            setBank(fd, true) // Bank 1 for LED register
            val ledReg = AS7343_LED_REG
            var ledWord = readByteReg(fd, ledReg)
            ledWord = (ledWord and (1 shl AS7343_LED_LED_ACT_BIT)) or (safeDriveValue and AS7343_LED_LED_DRIVE_MASK)
            writeByteReg(fd, ledReg, ledWord)
            setBank(fd, false) // Revert to Bank 0
        } catch (e: Exception) {
            Log.e(TAG, "Error setting LED current for fd=$fd: ${e.message}", e)
            try { setBank(fd, false) } catch (_: Exception) {} // Try to revert bank
        }
    }

    fun toggleLEDActivation(fd: Int, on: Boolean) {
        if (fd < 0) return
        try {
            Log.d(TAG, "Setting LED Activation ${if (on) "ON" else "OFF"} on fd=$fd")
            setBank(fd, true) // Bank 1 for LED register
            enableBit(fd, AS7343_LED_REG, AS7343_LED_LED_ACT_BIT, on)
            setBank(fd, false) // Revert to Bank 0
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling LED activation for fd=$fd: ${e.message}", e)
            try { setBank(fd, false) } catch (_: Exception) {} // Try to revert bank
        }
    }

    // --- Power Control ---
    fun togglePower(fd: Int, on: Boolean) {
        if (fd < 0) return
        try {
            // Power control is in Bank 0, ensure it's selected
            setBank(fd, false)
            Log.d(TAG, "Setting Power ${if (on) "ON" else "OFF"} on fd=$fd")
            enableBit(fd, AS7343_ENABLE_REG, AS7343_ENABLE_PON_BIT, on)
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling power for fd=$fd: ${e.message}", e)
        }
    }

    // --- Internal Helper Methods ---

    private fun enableSpectralMeasurement(fd: Int, enable: Boolean) {
        // Assumes Bank 0 is selected
        if (enable) {
            val enableReg = readByteReg(fd, AS7343_ENABLE_REG)
            if (enableReg and (1 shl AS7343_ENABLE_PON_BIT) == 0) {
                Log.w(TAG, "Warning: Enabling SP_EN while PON is OFF for fd=$fd. Enabling PON first.")
                togglePower(fd, true)
                Thread.sleep(1) // Small delay after PON
            }
        }
        enableBit(fd, AS7343_ENABLE_REG, AS7343_ENABLE_SP_EN_BIT, enable)
    }

    private fun getIsDataReady(fd: Int): Boolean {
        // Assumes Bank 0 is selected
        val status = readByteReg(fd, AS7343_STATUS2_REG)
        return status and (1 shl AS7343_STATUS2_AVALID_BIT) != 0
    }

    private fun waitForDataReady(fd: Int, timeoutMillis: Long): Boolean {
        val startTime = System.currentTimeMillis()
        while (!getIsDataReady(fd)) {
            if (System.currentTimeMillis() - startTime > timeoutMillis) {
                return false // Timeout
            }
            try {
                Thread.sleep(10) // Polling interval
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt() // Restore interrupt status
                Log.w(TAG, "Data wait interrupted for fd=$fd")
                return false
            }
        }
        return true // Data is ready
    }


    private fun readDataChannel(fd: Int, channelIndex: Int): Int {
        // Assumes Bank 0 is selected
        val dataLReg = AS7343_DATA0_L_REG + (channelIndex * 2)
        val dataHReg = dataLReg + 1
        val dataL = readByteReg(fd, dataLReg)
        val dataH = readByteReg(fd, dataHReg)
        return ((dataH and 0xFF) shl 8) or (dataL and 0xFF)
    }

    private fun setBank(fd: Int, accessBank1: Boolean) {
        // This function might be called when already in Bank 1, but needs Bank 0 access to modify CFG0
        // For simplicity, we assume we can always read CFG0 initially (might require temporary switch if robust needed)
        // Safest approach might be: always switch to bank 0, read CFG0, write if needed.
        // Current simpler approach: Read CFG0 (assuming Bank 0 access), write if needed.

        val targetBank = if (accessBank1) 1 else 0
        val cfg0Val = readByteReg(fd, AS7343_CFG0_REG) // Read current value (needs Bank 0 access)
        val currentBank = (cfg0Val shr AS7343_CFG0_REG_BANK_BIT) and 1

        if (currentBank != targetBank) {
            Log.d(TAG, "Setting Register Bank Access to $targetBank on fd=$fd")
            enableBit(fd, AS7343_CFG0_REG, AS7343_CFG0_REG_BANK_BIT, accessBank1)
            // Thread.sleep(1) // Optional short delay
        }
    }

    // --- I2C Primitive Helpers ---

    private fun enableBit(fd: Int, register: Int, bit: Int, on: Boolean) {
        val regValue = readByteReg(fd, register)
        val bitMask = (1 shl bit)
        val newValue = if (on) regValue or bitMask else regValue and bitMask.inv()
        if (newValue != regValue) {
            writeByteReg(fd, register, newValue)
        }
    }

    private fun setRegisterBits(fd: Int, register: Int, shift: Int, width: Int, value: Int) {
        var regValue = readByteReg(fd, register)
        val mask = ((1 shl width) - 1) shl shift
        regValue = (regValue and mask.inv()) or ((value shl shift) and mask)
        writeByteReg(fd, register, regValue)
    }

    private fun readByteReg(fd: Int, register: Int): Int {
        // Assuming I2cNative.readWord handles setting the register address implicitly
        val result = I2cNative.readWord(fd, register)
        if (result < 0) {
            throw IOException("I2C Read Error on fd=$fd, reg=0x${register.toString(16)}, code=$result")
        }
        return result and 0xFF
    }

    private fun writeByteReg(fd: Int, register: Int, value: Int) {
        val result = I2cNative.writeByte(fd, register, value)
        if (result < 0) {
            throw IOException("I2C Write Error on fd=$fd, reg=0x${register.toString(16)}, value=0x${value.toString(16)}, code=$result")
        }
    }

    private fun writeWordReg(fd: Int, lsbRegister: Int, value: Int) {
        val lsb = value and 0xFF
        val msb = (value shr 8) and 0xFF
        // Write LSB first, then MSB
        writeByteReg(fd, lsbRegister, lsb)
        writeByteReg(fd, lsbRegister + 1, msb)
    }
}