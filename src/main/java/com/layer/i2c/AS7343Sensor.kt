package com.layer.i2c

import android.util.Log
import kotlin.math.min

/**
 * High-level interface for the AS7343 spectral sensor.
 * Provides convenient methods for sensor operations.
 */
open class AS7343Sensor : I2CSensor {

    /**
     * Constructor for direct I2C connection (no multiplexer).
     * @param busPath The I2C bus path (e.g., "/dev/i2c-0")
     */
    constructor(busPath: String) : super(busPath)
    
    /**
     * Constructor for multiplexed I2C connection.
     * @param busPath The I2C bus path (e.g., "/dev/i2c-0")
     * @param multiplexer The TCA9548 multiplexer instance
     * @param multiplexerChannel The channel on the multiplexer (0-7)
     */
    constructor(
        busPath: String,
        multiplexer: TCA9548Multiplexer,
        multiplexerChannel: Int
    ) : super(busPath, multiplexer, multiplexerChannel)
    
    // Default address for AS7343 sensor
    override val sensorAddress: Int = 0x39
    
    // Register and bit definitions
    private val REG_ATIME: Int = 0x81        // Integration Time ADC cycles LSB
    private val REG_ASTEP_L: Int = 0xD4      // Integration Time Step Size LSB (16-bit)
    private val REG_CONFIG0: Int = 0xBF      // Bank selection register
    private val BIT_REGBANK: Int = 4         // Bank selection bit position
    private val REG_ENABLE: Int = 0x80       // Enable register 
    private val BIT_POWER: Int = 0           // Power bit position
    private val BIT_MEASUREMENT: Int = 1     // Measurement enable bit position
    private val REG_CFG1: Int = 0xC6         // Gain configuration register
    
    @Volatile
    open var primaryChannelData: MutableMap<String, Int>? = mutableMapOf()
    
    companion object : SensorFactory<I2CSensor> {
        
        override fun create(busPath:String): AS7343Sensor = AS7343Sensor(busPath)
        
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
        private const val AS7343_ID_VALUE = 0x81  // Correct ID value for AS7343
        
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
     * Closes the connection to the sensor and attempts to power it down.
     */
    override fun disconnect() {
        if (isBusOpen) {
            val locationInfo = if (isMultiplexed()) {
                "multiplexer channel ${getSensorMultiplexerChannel()}"
            } else {
                "direct connection"
            }
            Log.d(TAG, "Disconnecting sensor on $busPath ($locationInfo) (fd=$fileDescriptor)...")
            
            // Attempt to power down before closing
            if (isInitialized) { // Only try if we think it was initialized
                try {
                    togglePower(false)
                    Log.d(TAG, "Sensor powered down.")
                } catch (e: Exception) {
                    Log.w(TAG, "Error powering down sensor during disconnect: ${e.message}")
                }
            }
            // Call parent's closeDevice() which handles all the cleanup
            super.disconnect()
        } else {
            val locationInfo = if (isMultiplexed()) {
                "multiplexer channel ${getSensorMultiplexerChannel()}"
            } else {
                "direct connection"
            }
            Log.d(TAG, "Sensor on $busPath ($locationInfo) already disconnected.")
        }
    }
    
    /**
     * Sets the register bank.
     * Direct bank setting that bypasses recovery mechanisms.
     * Use this method within recovery functions to avoid infinite loops.
     *
     * @param useBank1 True to select Bank 1, false to select Bank 0
     */
    @Synchronized
    private fun setBankDirect(useBank1: Boolean) {
        if (fileDescriptor < 0) return

        try {
            val configWord = readByteRegDirect(REG_CONFIG0)
            val currentBank = (configWord shr BIT_REGBANK) and 1
            val targetBank = if (useBank1) 1 else 0
            
            if (currentBank != targetBank) {
                Log.d(TAG, "Setting Register Bank Access to $targetBank on fd=$fileDescriptor")
                enableBitDirect(REG_CONFIG0, BIT_REGBANK, useBank1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting bank for fd=$fileDescriptor: ${e.message}", e)
        }
    }
    
    private fun setBank(useBank1: Boolean) {
        if (fileDescriptor < 0) return

        try {
            val configWord = readByteReg(REG_CONFIG0)
            val currentBank = (configWord shr BIT_REGBANK) and 1
            val targetBank = if (useBank1) 1 else 0
            
            if (currentBank != targetBank) {
                Log.d(TAG, "Setting Register Bank Access to $targetBank on fd=$fileDescriptor")
                enableBit(REG_CONFIG0, BIT_REGBANK, useBank1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting bank for fd=$fileDescriptor: ${e.message}", e)
        }
    }
    
    /**
     * Toggles the power state of the sensor.
     *
     * @param on True to power on, false to power off
     */
    @Synchronized
    private fun togglePower(on: Boolean) {
        if (fileDescriptor < 0) return
        try {
            // Power control is in Bank 0, ensure it's selected
            setBank(false)
            Log.d(TAG, "Setting Power ${if (on) "ON" else "OFF"} on fd=$fileDescriptor")
            enableBit(REG_ENABLE, BIT_POWER, on)
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling power for fd=$fileDescriptor: ${e.message}", e)
        }
    }
    
    /**
     * Enables or disables spectral measurement.
     */
    @Synchronized
    private fun enableSpectralMeasurement(enableMeasurement: Boolean) {
        try {
            setBank(false) // Ensure Bank 0
            if (enableMeasurement) {
                // Make sure power is on before enabling measurement
                val enableReg = readByteReg(REG_ENABLE)
                if (enableReg and (1 shl BIT_POWER) == 0) {
                    Log.w(TAG, "Warning: Enabling measurement while power is OFF. Enabling power first.")
                    togglePower(true)
                    Thread.sleep(1) // Small delay after power on
                }
            }
            enableBit(REG_ENABLE, BIT_MEASUREMENT, enableMeasurement)
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling measurement for fd=$fileDescriptor: ${e.message}", e)
        }
    }
    
    /**
     * Sets the integration time for spectral measurements.
     */
    @Synchronized
    private fun setIntegrationTime(atime: Int, astep: Int) {
        if (fileDescriptor < 0) return
        val safeAtime = atime.coerceIn(0, 255)
        val safeAstep = astep.coerceIn(0, 65534)
        if (safeAtime == 0 && safeAstep == 0) {
            Log.e(TAG, "ATIME and ASTEP cannot both be 0. Setting ASTEP=1.")
            setIntegrationTimeInternal(0, 1)
            return
        }
        setIntegrationTimeInternal(safeAtime, safeAstep)
    }

    @Synchronized
    private fun setIntegrationTimeInternal(atime: Int, astep: Int) {
        try {
            Log.d(TAG, "Setting ATIME=$atime, ASTEP=$astep on fd=$fileDescriptor")
            setBank(false) // Ensure Bank 0
            writeByteReg(REG_ATIME, atime)
            writeWordReg(REG_ASTEP_L, astep)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting integration time for fd=$fileDescriptor: ${e.message}", e)
        }
    }
    
    /**
     * Sets the sensor gain.
     * @param againValue Gain value (typically 0-12, where higher values mean higher sensitivity)
     *   0=0.5x, 4=16x, 8=128x, 9=256x(default), 10=512x, 12=2048x
     */
    @Synchronized
    private fun setGain(againValue: Int) {
        if (fileDescriptor < 0) return
        val safeAgain = againValue.coerceIn(0, 12)
        try {
            Log.d(TAG, "Setting Gain (AGAIN) on fd=$fileDescriptor to $safeAgain")
            setBank(false) // Ensure Bank 0
            setRegisterBits(REG_CFG1, 0, 5, safeAgain)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting gain for fd=$fileDescriptor: ${e.message}", e)
        }
    }
    
    override fun getSensorState() = object : ColorSensorState {
        override val errorMessage = lastError()
        override val connected = this@AS7343Sensor.isConnected()
        override val updateTS = System.currentTimeMillis()
        override val sensorId = this@AS7343Sensor.toString()
        override val channelData :  Map<String, Int> = getLatestChannelData()?.toMap()
            ?: mapOf(
                "F1" to 0, "F2" to 0, "F3" to 0, "F4" to 0,
                "F5" to 0, "F6" to 0, "F7" to 0, "F8" to 0
            )
    }
    
    override fun readDataImpl(): Map<String, Int> {
        val read = readSpectralDataOnce()
        if (read.isEmpty()) {
            return read
        } else {
            return this.primaryChannelData!!
        }
    }
    
    fun getLatestChannelData(): Map<String, Int>? {
        return this.primaryChannelData
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
    fun readSpectralData(): Map<String, Int> {
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
            primaryChannelData?.set(simpleName,
                rawData[key] ?: (primaryChannelData?.get(simpleName) ?: 0)
            )
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
            Log.e(TAG, "EXCEPTION during sensor initialization for fd=$fileDescriptor", e)
            Log.e(TAG, "Initialization exception type: ${e.javaClass.simpleName}")
            Log.e(TAG, "Initialization exception message: ${e.message}")
            if (e.cause != null) {
                Log.e(TAG, "Initialization exception cause: ${e.cause?.javaClass?.simpleName} - ${e.cause?.message}")
            }
            // Attempt to power off on failure
            try {
                Log.d(TAG, "Attempting to power off sensor after initialization failure on fd=$fileDescriptor")
                togglePower(false)
            } catch (powerOffException: Exception) {
                Log.w(TAG, "Failed to power off sensor after initialization failure: ${powerOffException.message}")
            }
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
            Log.d(TAG, "Step 1: Setting Bank 0 and powering off sensor on fd=$fileDescriptor")
            setBank(false) // Ensure Bank 0
            togglePower(false)
            Thread.sleep(5) // Wait for power down
            Log.d(TAG, "Step 1 completed: Sensor powered off on fd=$fileDescriptor")
            
            // Step 2: Power on and wait for stabilization
            // Datasheet specifies 200μs initialization time after power-on
            Log.d(TAG, "Step 2: Powering on sensor on fd=$fileDescriptor")
            togglePower(true)
            Thread.sleep(1) // Wait for internal initialization (>200μs)
            Log.d(TAG, "Step 2 completed: Sensor powered on, waiting for stabilization on fd=$fileDescriptor")
            
            // Step 3: Verify sensor is responsive after power-on
            Log.d(TAG, "Step 3: Checking sensor responsiveness after power-on on fd=$fileDescriptor")
            if (!isSensorResponsive()) {
                Log.e(TAG, "Step 3 FAILED: Sensor not responsive after power-on on fd=$fileDescriptor")
                return false
            }
            Log.d(TAG, "Step 3 completed: Sensor responsive after power-on on fd=$fileDescriptor")
            
            // Step 4: Clear any residual state from previous operations
            Log.d(TAG, "Step 4: Clearing SAI active state on fd=$fileDescriptor")
            clearSAIActive()
            Log.d(TAG, "Step 4 completed: SAI active state cleared on fd=$fileDescriptor")
            
            // Step 5: Ensure proper bank selection for configuration
            Log.d(TAG, "Step 5: Setting Bank 0 for configuration on fd=$fileDescriptor")
            setBank(false) // Ensure Bank 0 for main configuration
            Log.d(TAG, "Step 5 completed: Bank 0 selected for configuration on fd=$fileDescriptor")
            
            // Step 6: Configure auto_smux for 18-channel readout
            Log.d(TAG, "Step 6: Configuring auto_smux for 18-channel readout on fd=$fileDescriptor")
            setRegisterBits(AS7343_CFG20_REG, AS7343_CFG20_AUTO_SMUX_SHIFT, 2, AS7343_AUTO_SMUX_MODE_18CH)
            Log.d(TAG, "Step 6 completed: Set auto_smux mode to 18-channel on fd=$fileDescriptor")

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

            Log.d(TAG, "Step 7: Setting integration time on fd=$fileDescriptor")
            setIntegrationTime(0, 65534)
            Log.d(TAG, "Step 7 completed: Integration time set on fd=$fileDescriptor")

            // Step 8: Set gain: AGAIN (0=0.5x, 9=256x(default), 12=2048x)
            Log.d(TAG, "Step 8: Setting gain to 10 on fd=$fileDescriptor")
            setGain(10)
            Log.d(TAG, "Step 8 completed: Gain set to 10 on fd=$fileDescriptor")
            
            // Step 9: Final verification that sensor is still responsive
            Log.d(TAG, "Step 9: Final responsiveness check on fd=$fileDescriptor")
            if (!isSensorResponsive()) {
                Log.e(TAG, "Step 9 FAILED: Sensor became unresponsive during configuration on fd=$fileDescriptor")
                return false
            }
            Log.d(TAG, "Step 9 completed: Final responsiveness check passed on fd=$fileDescriptor")

            Log.d(TAG, "Proper Power-On Reset sequence completed successfully on fd=$fileDescriptor")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "EXCEPTION during Power-On Reset sequence for fd=$fileDescriptor", e)
            Log.e(TAG, "Exception type: ${e.javaClass.simpleName}")
            Log.e(TAG, "Exception message: ${e.message}")
            if (e.cause != null) {
                Log.e(TAG, "Exception cause: ${e.cause?.javaClass?.simpleName} - ${e.cause?.message}")
            }
            return false
        }
    }

    /**
     * Reads all 18 data registers from the sensor after triggering a measurement.
     * Uses transaction-level locking to ensure atomic operation across all I2C calls.
     * Assumes sensor is initialized.
     * @return Map containing all 18 data register values (using names from dataRegisterNames), or empty map on error.
     */
    @Synchronized
    private fun readAllChannels(): Map<String, Int> {
        if (fileDescriptor < 0) return emptyMap()

        return try {
            executeTransaction {
                val channelData = mutableMapOf<String, Int>()
                
                setBankTransaction(false) // Ensure Bank 0
                Log.d(TAG, "Starting spectral measurement on fd=$fileDescriptor")

                // 1. Enable Spectral Measurement
                enableSpectralMeasurementTransaction(true)

                // 2. Wait for Data Ready
                if (!waitForDataReadyTransaction(2000)) { // Use helper with timeout
                    Log.e(TAG,"Timeout waiting for data ready on fd=$fileDescriptor")
                    enableSpectralMeasurementTransaction(false) // Ensure measurement is disabled
                    return@executeTransaction emptyMap<String, Int>() // Return empty on timeout
                }
                Log.d(TAG, "Data ready on fd=$fileDescriptor")

                // 3. Read ASTATUS (contains saturation info, read to clear it)
                readByteRegTransaction(AS7343_ASTATUS_REG)
                // We don't use the value, but reading it clears latched status bits

                // 4. Read Data Registers atomically
                for (i in 0 until AS7343_NUM_DATA_REGISTERS) {
                    val value = readDataChannelTransaction(i)
                    val name = dataRegisterNames.getOrElse(i) { "Unknown_Data_$i" }
                    channelData[name] = value
                }

                // 5. Disable Spectral Measurement
                enableSpectralMeasurementTransaction(false)
                Log.d(TAG, "Spectral measurement finished on fd=$fileDescriptor")

                channelData
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during readAllChannels transaction for fd=$fileDescriptor: ${e.message}", e)
            
            // If we get an I2C error, try recovery (but don't retry automatically)
            if (e.message?.contains("I2C") == true) {
                Log.w(TAG, "I2C error detected, attempting sensor recovery on fd=$fileDescriptor")
                try {
                    if (recoverSensor()) {
                        Log.i(TAG, "Sensor recovery successful after I2C error on fd=$fileDescriptor")
                    }
                } catch (recoveryException: Exception) {
                    Log.e(TAG, "Error during recovery: ${recoveryException.message}")
                }
            }
            
            emptyMap() // Return empty on error
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
            Log.w(TAG, "isSensorResponsive: invalid file descriptor")
            return false
        }

        try {
            // Just verify we can communicate with the sensor by reading a register
            val id = readByteRegDirect(AS7343_ID_REG)
            Log.d(TAG, "Sensor communication test on fd=$fileDescriptor: read ID=0x${id.toString(16).uppercase()}")
            return true  // If we can read without exception, sensor is responsive
        } catch (e: Exception) {
            Log.w(TAG, "Sensor unresponsive on fd=$fileDescriptor: ${e.message}", e)
            return false
        }
    }

    /**
     * Attempts to recover a non-responsive sensor using progressive recovery methods.
     * Tries increasingly aggressive recovery methods until the sensor responds.
     * @return true if recovery was successful, false if all methods failed
     */
    @Synchronized
    fun recoverSensor(): Boolean {
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

    // --- Transaction Helper Methods ---
    
    /**
     * Transaction version of setBank - sets the register bank without device switching
     */
    private fun setBankTransaction(bank0: Boolean) {
        val bankBit = if (bank0) 0 else 1
        val regValue = readByteRegTransaction(REG_CONFIG0)
        val newValue = (regValue and 0xEF) or (bankBit shl 4)
        writeByteRegTransaction(REG_CONFIG0, newValue)
    }

    /**
     * Transaction version of enableSpectralMeasurement
     */
    private fun enableSpectralMeasurementTransaction(enable: Boolean) {
        setBankTransaction(false) // Ensure Bank 0
        
        if (enable) {
            // Make sure power is on before enabling measurement
            val enableReg = readByteRegTransaction(REG_ENABLE)
            if (enableReg and (1 shl BIT_POWER) == 0) {
                Log.w(TAG, "Warning: Enabling measurement while power is OFF. Enabling power first.")
                enableBitTransaction(REG_ENABLE, BIT_POWER, true)
                Thread.sleep(1) // Small delay after power on
            }
        }
        enableBitTransaction(REG_ENABLE, BIT_MEASUREMENT, enable)
    }

    /**
     * Transaction version of waitForDataReady
     */
    private fun waitForDataReadyTransaction(timeoutMs: Long): Boolean {
        val startTime = System.currentTimeMillis()
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val statusReg = readByteRegTransaction(AS7343_STATUS2_REG)
            val avalid = (statusReg shr AS7343_STATUS2_AVALID_BIT) and 1 == 1
            
            if (avalid) {
                return true
            }
            
            Thread.sleep(10) // Wait 10ms before next check
        }
        
        return false // Timeout
    }

    /**
     * Transaction version of readDataChannel
     */
    private fun readDataChannelTransaction(channelIndex: Int): Int {
        // Read data registers directly without additional device switching
        val dataLReg = AS7343_DATA0_L_REG + (channelIndex * 2)
        val dataHReg = dataLReg + 1
        val dataL = readByteRegTransaction(dataLReg)
        val dataH = readByteRegTransaction(dataHReg)
        return ((dataH and 0xFF) shl 8) or (dataL and 0xFF)
    }


}