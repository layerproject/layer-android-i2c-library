package com.layer.i2c

import android.util.Log
import kotlin.math.min

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
        
        // Reset and Recovery Registers
        private const val AS7343_CONTROL_REG = 0xFA        // Control register with reset functionality
        private const val AS7343_SW_RESET_BIT = 4          // Software reset bit in CONTROL register
        private const val AS7343_CLEAR_SAI_ACT_BIT = 0     // Clear SAI_ACTIVE bit in CONTROL register
        private const val AS7343_CFG3_REG = 0xC7           // Configuration register 3
        private const val AS7343_SAI_BIT = 4               // Sleep after interrupt bit in CFG3
        private const val AS7343_STATUS4_REG = 0xBC        // Status register 4 (has SAI_ACTIVE)
        private const val AS7343_SAI_ACTIVE_BIT = 6        // SAI_ACTIVE bit in STATUS4 register
    }
    
    /**
     * Checks if this is the correct sensor by reading the ID register
     */
    override fun isCorrectSensor(): Boolean {
        if (!isReady()) {
            return false
        }
        
        try {
            val id = readByteRegDirect(AS7343_ID_REG)
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

        return readSpectralDataWithRetry(maxRetries = 3)
    }

    /**
     * Reads spectral data with retry logic and exponential backoff.
     * @param maxRetries Maximum number of retry attempts
     * @return Map of primary channel names to values, or empty map if all retries fail
     */
    @Synchronized
    private fun readSpectralDataWithRetry(maxRetries: Int): Map<String, Int> {
        var attempt = 0
        var lastException: Exception? = null

        while (attempt <= maxRetries) {
            try {
                Log.d(TAG, "Attempting spectral data read (attempt ${attempt + 1}/${maxRetries + 1}) on fd=$fileDescriptor")
                
                val rawData = readAllChannels()

                if (rawData.isNotEmpty()) {
                    Log.d(TAG, "Spectral data read successful on attempt ${attempt + 1} for fd=$fileDescriptor")
                    return extractPrimaryChannels(rawData)
                } else {
                    Log.w(TAG, "Read returned empty data on attempt ${attempt + 1} for fd=$fileDescriptor")
                }

            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Read attempt ${attempt + 1} failed for fd=$fileDescriptor: ${e.message}")
                
                // If it's an I2C error and we have retries left, try recovery
                if (e.message?.contains("I2C") == true && attempt < maxRetries) {
                    Log.w(TAG, "I2C error on attempt ${attempt + 1}, attempting recovery on fd=$fileDescriptor")
                    try {
                        if (recoverSensor()) {
                            Log.i(TAG, "Sensor recovery successful after attempt ${attempt + 1} on fd=$fileDescriptor")
                        } else {
                            Log.e(TAG, "Sensor recovery failed after attempt ${attempt + 1} on fd=$fileDescriptor")
                        }
                    } catch (recoveryException: Exception) {
                        Log.e(TAG, "Exception during recovery after attempt ${attempt + 1} on fd=$fileDescriptor: ${recoveryException.message}")
                    }
                }
            }

            attempt++
            
            // Exponential backoff before retry (if we have more attempts)
            if (attempt <= maxRetries) {
                val backoffTime = min((50 * Math.pow(2.0, (attempt - 1).toDouble())).toLong(), 500L)
                Log.d(TAG, "Waiting ${backoffTime}ms before retry attempt $attempt on fd=$fileDescriptor")
                try {
                    Thread.sleep(backoffTime)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    Log.w(TAG, "Retry backoff interrupted for fd=$fileDescriptor")
                    break
                }
            }
        }

        Log.e(TAG, "All retry attempts failed for spectral data read on fd=$fileDescriptor. Last error: ${lastException?.message}")
        return emptyMap()
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
            // Perform proper Power-On Reset sequence as per datasheet
            return performProperPowerOnReset()
        } catch (e: Exception) {
            Log.e(TAG, "Error during sensor initialization for fd=$fileDescriptor: ${e.message}", e)
            // Attempt to power off on failure
            try { togglePower(false) } catch (_: Exception) {}
            return false
        }
    }

    /**
     * Performs proper Power-On Reset sequence according to AS7343 datasheet.
     * This ensures the sensor is in a known good state before configuration.
     * @return true if initialization succeeds, false otherwise.
     */
    @Synchronized
    private fun performProperPowerOnReset(): Boolean {
        Log.d(TAG, "Performing proper Power-On Reset sequence on fd=$fileDescriptor")
        
        try {
            // Step 1: Ensure we start from a clean state - power off first
            setBank(false) // Ensure Bank 0
            togglePower(false)
            Thread.sleep(5) // Wait for power down
            
            // Step 2: Power on and wait for stabilization
            // Datasheet specifies 200μs initialization time after power-on
            togglePower(true)
            Thread.sleep(1) // Wait for internal initialization (>200μs)
            
            // Step 3: Verify sensor is responsive after power-on
            if (!isSensorResponsive()) {
                Log.e(TAG, "Sensor not responsive after power-on on fd=$fileDescriptor")
                return false
            }
            
            // Step 4: Clear any residual state from previous operations
            clearSAIActive()
            
            // Step 5: Ensure proper bank selection for configuration
            setBank(false) // Ensure Bank 0 for main configuration
            
            // Step 6: Configure auto_smux for 18-channel readout
            setRegisterBits(AS7343_CFG20_REG, AS7343_CFG20_AUTO_SMUX_SHIFT, 2, AS7343_AUTO_SMUX_MODE_18CH)
            Log.d(TAG, "Set auto_smux mode to 18-channel (3)")

            // Step 7: Set default integration time (~100ms)
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

            // Step 8: Set gain: AGAIN (0=0.5x, 9=256x(default), 12=2048x)
            setGain(10)
            
            // Step 9: Final verification that sensor is still responsive
            if (!isSensorResponsive()) {
                Log.e(TAG, "Sensor became unresponsive during configuration on fd=$fileDescriptor")
                return false
            }

            Log.d(TAG, "Proper Power-On Reset sequence completed successfully on fd=$fileDescriptor")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during Power-On Reset sequence for fd=$fileDescriptor: ${e.message}", e)
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

        // Perform sensor health check before attempting to read
        if (!performSensorHealthCheck()) {
            Log.w(TAG, "Sensor health check failed on fd=$fileDescriptor, attempting recovery")
            if (!recoverSensor()) {
                Log.e(TAG, "Sensor recovery failed on fd=$fileDescriptor")
                return emptyMap()
            }
            Log.i(TAG, "Sensor recovery successful on fd=$fileDescriptor")
        }

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
            
            // If we get an I2C error, try recovery
            if (e.message?.contains("I2C") == true) {
                Log.w(TAG, "I2C error detected, attempting sensor recovery on fd=$fileDescriptor")
                if (recoverSensor()) {
                    Log.i(TAG, "Sensor recovery successful after I2C error on fd=$fileDescriptor")
                    // Don't retry automatically here - let the caller decide
                }
            }
            
            return emptyMap() // Return empty on error
        }
    }

    /**
     * Performs a health check on the sensor to verify it's responsive and in a good state.
     * @return true if sensor is healthy, false if recovery is needed
     */
    @Synchronized
    private fun performSensorHealthCheck(): Boolean {
        if (fileDescriptor < 0) {
            return false
        }

        try {
            // Check 1: Verify sensor is responsive (can read ID register)
            if (!isSensorResponsive()) {
                Log.w(TAG, "Sensor health check failed: not responsive on fd=$fileDescriptor")
                return false
            }

            // Check 2: Verify we can read basic status registers
            val enableReg = readByteRegDirect(REG_ENABLE)
            val powerOn = (enableReg and (1 shl BIT_POWER)) != 0
            
            if (!powerOn) {
                Log.w(TAG, "Sensor health check failed: power not on fd=$fileDescriptor")
                return false
            }

            // Check 3: Verify bank switching works properly
            setBankDirect(false)
            val configReg = readByteRegDirect(REG_CONFIG0)
            val bankBit = (configReg shr BIT_REGBANK) and 1
            
            if (bankBit != 0) {
                Log.w(TAG, "Sensor health check failed: bank switching issue on fd=$fileDescriptor")
                return false
            }

            Log.d(TAG, "Sensor health check passed on fd=$fileDescriptor")
            return true

        } catch (e: Exception) {
            Log.w(TAG, "Sensor health check failed with exception on fd=$fileDescriptor: ${e.message}")
            return false
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
            val status = readByteRegDirect(AS7343_STATUS2_REG)
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

    // --- Reset and Recovery Methods ---

    /**
     * Performs a software reset of the AS7343 sensor.
     * This resets the sensor's internal state machine and registers to default values.
     * @return true if the reset was successful, false otherwise
     */
    @Synchronized
    fun performSoftwareReset(): Boolean {
        if (fileDescriptor < 0) {
            Log.e(TAG, "Cannot perform software reset: invalid file descriptor")
            return false
        }

        try {
            Log.d(TAG, "Performing software reset on fd=$fileDescriptor")
            
            // Ensure Bank 0 is selected to access CONTROL register
            setBankDirect(false)
            
            // Trigger software reset by setting SW_RESET bit in CONTROL register
            enableBitDirect(AS7343_CONTROL_REG, AS7343_SW_RESET_BIT, true)
            
            // Wait for reset to complete (datasheet recommends minimum 200μs)
            Thread.sleep(1)
            
            // Reset is self-clearing, verify it completed
            val controlReg = readByteRegDirect(AS7343_CONTROL_REG)
            val resetInProgress = (controlReg shr AS7343_SW_RESET_BIT) and 1 == 1
            
            if (resetInProgress) {
                Log.w(TAG, "Software reset still in progress on fd=$fileDescriptor")
                // Wait a bit more and check again
                Thread.sleep(5)
                val controlReg2 = readByteRegDirect(AS7343_CONTROL_REG)
                val stillInProgress = (controlReg2 shr AS7343_SW_RESET_BIT) and 1 == 1
                if (stillInProgress) {
                    Log.e(TAG, "Software reset failed to complete on fd=$fileDescriptor")
                    return false
                }
            }
            
            Log.d(TAG, "Software reset completed successfully on fd=$fileDescriptor")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during software reset on fd=$fileDescriptor: ${e.message}", e)
            return false
        }
    }

    /**
     * Performs a SMUX (Sensor Multiplexer) reset.
     * This resets the sensor's internal multiplexer that routes photodiode signals to ADCs.
     * @return true if the SMUX reset was successful, false otherwise
     */
    @Synchronized
    fun performSMUXReset(): Boolean {
        if (fileDescriptor < 0) {
            Log.e(TAG, "Cannot perform SMUX reset: invalid file descriptor")
            return false
        }

        try {
            Log.d(TAG, "Performing SMUX reset on fd=$fileDescriptor")
            
            // Ensure Bank 0 is selected
            setBankDirect(false)
            
            // Trigger SMUX reset by setting SMUXEN bit in ENABLE register
            // This bit is self-clearing when the operation completes
            enableBitDirect(REG_ENABLE, 4, true) // SMUXEN is bit 4
            
            // Wait for SMUX operation to complete (typically a few milliseconds)
            val startTime = System.currentTimeMillis()
            val timeout = 100 // 100ms timeout
            
            while (System.currentTimeMillis() - startTime < timeout) {
                val enableReg = readByteRegDirect(REG_ENABLE)
                val smuxActive = (enableReg shr 4) and 1 == 1
                
                if (!smuxActive) {
                    Log.d(TAG, "SMUX reset completed successfully on fd=$fileDescriptor")
                    return true
                }
                
                Thread.sleep(1)
            }
            
            Log.e(TAG, "SMUX reset timed out on fd=$fileDescriptor")
            return false
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during SMUX reset on fd=$fileDescriptor: ${e.message}", e)
            return false
        }
    }

    /**
     * Clears the SAI (Sleep after Interrupt) active state.
     * This is needed if the sensor gets stuck in sleep mode after an interrupt.
     * @return true if clearing was successful, false otherwise
     */
    @Synchronized
    fun clearSAIActive(): Boolean {
        if (fileDescriptor < 0) {
            Log.e(TAG, "Cannot clear SAI active: invalid file descriptor")
            return false
        }

        try {
            Log.d(TAG, "Clearing SAI active state on fd=$fileDescriptor")
            
            // Ensure Bank 0 is selected
            setBankDirect(false)
            
            // Set CLEAR_SAI_ACT bit in CONTROL register
            enableBitDirect(AS7343_CONTROL_REG, AS7343_CLEAR_SAI_ACT_BIT, true)
            
            // Verify the SAI_ACTIVE bit is cleared in STATUS4 register
            Thread.sleep(1)
            val status4 = readByteRegDirect(AS7343_STATUS4_REG)
            val saiActive = (status4 shr AS7343_SAI_ACTIVE_BIT) and 1 == 1
            
            if (saiActive) {
                Log.w(TAG, "SAI active state not cleared on fd=$fileDescriptor")
                return false
            }
            
            Log.d(TAG, "SAI active state cleared successfully on fd=$fileDescriptor")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing SAI active on fd=$fileDescriptor: ${e.message}", e)
            return false
        }
    }

    /**
     * Checks if the sensor is responsive by attempting to read a known register.
     * @return true if sensor responds correctly, false if unresponsive
     */
    @Synchronized
    fun isSensorResponsive(): Boolean {
        if (fileDescriptor < 0) {
            return false
        }

        try {
            // Try to read the ID register - this should always return the expected value
            val id = readByteRegDirect(AS7343_ID_REG)
            return id == AS7343_ID_VALUE
        } catch (e: Exception) {
            Log.w(TAG, "Sensor unresponsive on fd=$fileDescriptor: ${e.message}")
            return false
        }
    }

    /**
     * Attempts to recover a non-responsive sensor using progressive recovery methods.
     * Tries increasingly aggressive recovery methods until the sensor responds.
     * @return true if recovery was successful, false if all methods failed
     */
    @Synchronized
    override fun recoverSensor(): Boolean {
        if (fileDescriptor < 0) {
            Log.e(TAG, "Cannot recover sensor: invalid file descriptor")
            return false
        }

        Log.w(TAG, "Attempting sensor recovery on fd=$fileDescriptor")

        // Step 1: Try clearing SAI active state (least invasive)
        if (clearSAIActive() && isSensorResponsive()) {
            Log.i(TAG, "Sensor recovered using SAI clear on fd=$fileDescriptor")
            return true
        }

        // Step 2: Try SMUX reset (moderate)
        if (performSMUXReset() && isSensorResponsive()) {
            Log.i(TAG, "Sensor recovered using SMUX reset on fd=$fileDescriptor")
            return true
        }

        // Step 3: Try software reset (more aggressive)
        if (performSoftwareReset() && isSensorResponsive()) {
            Log.i(TAG, "Sensor recovered using software reset on fd=$fileDescriptor")
            // After software reset, need to re-initialize
            if (initializeSensor()) {
                Log.i(TAG, "Sensor re-initialized successfully after software reset on fd=$fileDescriptor")
                return true
            } else {
                Log.e(TAG, "Failed to re-initialize sensor after software reset on fd=$fileDescriptor")
                return false
            }
        }

        // Step 4: Try full power cycle (most aggressive)
        try {
            Log.d(TAG, "Attempting full power cycle on fd=$fileDescriptor")
            togglePower(false)
            Thread.sleep(10) // Wait for power down
            togglePower(true)
            Thread.sleep(5)  // Wait for power up
            
            if (isSensorResponsive()) {
                Log.i(TAG, "Sensor recovered using power cycle on fd=$fileDescriptor")
                if (initializeSensor()) {
                    Log.i(TAG, "Sensor re-initialized successfully after power cycle on fd=$fileDescriptor")
                    return true
                } else {
                    Log.e(TAG, "Failed to re-initialize sensor after power cycle on fd=$fileDescriptor")
                    return false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during power cycle recovery on fd=$fileDescriptor: ${e.message}", e)
        }

        Log.e(TAG, "All recovery methods failed for sensor on fd=$fileDescriptor")
        return false
    }
}