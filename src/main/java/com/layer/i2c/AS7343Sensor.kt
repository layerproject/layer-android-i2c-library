package com.layer.i2c

import android.util.Log

/**
 * High-level interface for the AS7343 spectral sensor.
 * Provides convenient methods for sensor operations.
 */
class AS7343Sensor(busPath: String) : AS73XXSensor(busPath) {
    // Implement abstract register properties
    override val REG_ATIME: Int = 0x81        // Integration Time ADC cycles LSB
    override val REG_ASTEP_L: Int = 0xD4      // Integration Time Step Size LSB (16-bit)
    override val REG_CONFIG0: Int = 0xBF      // Bank selection register
    override val BIT_REGBANK: Int = 4         // Bank selection bit position
    override val REG_ENABLE: Int = 0x80       // Enable register 
    override val BIT_POWER: Int = 0           // Power bit position
    override val BIT_MEASUREMENT: Int = 1     // Measurement enable bit position
    override val REG_CFG1: Int = 0xC6         // Gain configuration register
    
    companion object {
        private const val TAG = "AS7343Sensor"

        // --- AS7343 Register Addresses (Verified from Datasheet) ---

        // Configuration Registers (Bank 0 unless noted)
        private const val AS7343_WTIME_REG = 0x83       // Wait Time cycles
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
        private const val AS7343_STATUS2_AVALID_BIT = 6   // Spectral Data Valid
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

        // AS7343 ID register and expected value
        private const val AS7343_ID_REG = 0x5a
        private const val AS7343_ID_VALUE = 0
    }
    
    /**
     * Checks if this is the correct sensor by reading the ID register
     */
    override fun isCorrectSensor(): Boolean {
        if (!isReady()) {
            return false
        }
        
        try {
            val id = readByteReg(AS7343_ID_REG)
            Log.d(TAG, "Reading sensor ID: $id (expected: ${AS7343_ID_VALUE})")
            return id == AS7343_ID_VALUE
        } catch (e: Exception) {
            Log.e(TAG, "Error reading sensor ID: ${e.message}")
            return false
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
        val rawData = readAllChannels()
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
    override fun readSpectralData(): Map<String, Int> {
        if (!isInitialized) {
            Log.e(TAG, "Sensor not initialized. Call connect() first.")
            return emptyMap()
        }

        val rawData = readAllChannels()

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
     * Initializes the AS7343 sensor with default settings for measurement.
     * MUST be called after opening the sensor.
     * @return true if initialization succeeds, false otherwise.
     */
    override fun initializeSensor(): Boolean {
        if (fileDescriptor < 0) return false
        Log.d(TAG, "Initializing sensor on fd=$fileDescriptor...")
        try {
            setBank(false) // Ensure Bank 0
            togglePower(true)
            Thread.sleep(5) // Short delay after power on

            // Configure auto_smux for 18-channel readout
            setRegisterBits(AS7343_CFG20_REG, AS7343_CFG20_AUTO_SMUX_SHIFT, 2, AS7343_AUTO_SMUX_MODE_18CH)
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
            //setIntegrationTime(fd, atime = 35, astep = 999)

            setIntegrationTime(0, 65534)

            // Gain: AGAIN (0=0.5x, 9=256x(default), 12=2048x)
            setGain(9)

            Log.d(TAG, "Sensor fd=$fileDescriptor initialized successfully.")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error during sensor initialization for fd=$fileDescriptor: ${e.message}", e)
            // Attempt to power off on failure
            try { togglePower(false) } catch (_: Exception) {}
            return false
        }
    }

    /**
     * Reads all 18 data registers from the sensor after triggering a measurement.
     * Assumes sensor is initialized.
     * @param fd File descriptor for the I2C connection
     * @return Map containing all 18 data register values (using names from dataRegisterNames), or empty map on error.
     */
    @Synchronized
    private fun readAllChannels(): Map<String, Int> {
        if (fileDescriptor < 0) return emptyMap()

        val channelData = mutableMapOf<String, Int>()
        try {
            setBank(false) // Ensure Bank 0
            Log.d(TAG, "Starting spectral measurement on fd=$fileDescriptor")

            // 1. Enable Spectral Measurement
            enableSpectralMeasurement(true)

            // 2. Wait for Data Ready
            if (!waitForDataReady(2000)) { // Use helper with timeout
                Log.e(TAG,"Timeout waiting for data ready on fd=$fileDescriptor")
                enableSpectralMeasurement(false) // Ensure measurement is disabled
                return emptyMap() // Return empty on timeout
            }
            Log.d(TAG, "Data ready on fd=$fileDescriptor")

            // 3. Read ASTATUS (contains saturation info, read to clear it)
            readByteReg(AS7343_ASTATUS_REG)
            // We don't use the value, but reading it clears latched status bits

            // 4. Read Data Registers
            for (i in 0 until AS7343_NUM_DATA_REGISTERS) {
                val value = readDataChannel(i)
                val name = dataRegisterNames.getOrElse(i) { "Unknown_Data_$i" }
                channelData[name] = value
            }

            // 5. Disable Spectral Measurement
            enableSpectralMeasurement(false)
            Log.d(TAG, "Spectral measurement finished on fd=$fileDescriptor")

            return channelData

        } catch (e: Exception) {
            Log.e(TAG, "Error during readAllChannels for fd=$fileDescriptor: ${e.message}", e)
            // Attempt to disable measurement on error
            try { enableSpectralMeasurement(false) } catch (_: Exception) {}
            return emptyMap() // Return empty on error
        }
    }

    @Synchronized
    private fun getIsDataReady(): Boolean {
        // Use the shared file descriptor lock
        val lock = fdLock ?: this
        
        synchronized(lock) {
            // Ensure we're talking to the right device
            if (!switchToDevice()) {
                Log.e(TAG, "Failed to switch device before getIsDataReady")
                return false
            }
            
            // Assumes Bank 0 is selected
            val status = readByteReg(AS7343_STATUS2_REG)
            return status and (1 shl AS7343_STATUS2_AVALID_BIT) != 0
        }
    }

    private fun waitForDataReady(timeoutMillis: Long): Boolean {
        val startTime = System.currentTimeMillis()
        while (!getIsDataReady()) {
            if (System.currentTimeMillis() - startTime > timeoutMillis) {
                return false // Timeout
            }
            try {
                Thread.sleep(10) // Polling interval
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt() // Restore interrupt status
                Log.w(TAG, "Data wait interrupted for fd=$fileDescriptor")
                return false
            }
        }
        return true // Data is ready
    }

    private fun readDataChannel(channelIndex: Int): Int {
        // Use the shared file descriptor lock
        val lock = fdLock ?: this
        
        synchronized(lock) {
            // Ensure we're talking to the right device
            if (!switchToDevice()) {
                Log.e(TAG, "Failed to switch device before readDataChannel")
                return -1
            }
            
            // Assumes Bank 0 is selected
            val dataLReg = AS7343_DATA0_L_REG + (channelIndex * 2)
            val dataHReg = dataLReg + 1
            val dataL = readByteReg(dataLReg)
            val dataH = readByteReg(dataHReg)
            return ((dataH and 0xFF) shl 8) or (dataL and 0xFF)
        }
    }
}