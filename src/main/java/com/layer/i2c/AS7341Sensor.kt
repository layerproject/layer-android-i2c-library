package com.layer.i2c

import android.util.Log
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min

/**
 * High-level interface for the AS7341 spectral sensor.
 * The AS7341 has 10 output channels (F1-F8, Clear, NIR) vs the AS7343's 14.
 * It requires manual SMUX configuration with two read cycles to get all 8 spectral channels.
 *
 * Used on legacy devices with serials: 3cc0ef17, 6aceb0a1, 8b11ea02
 */
open class AS7341Sensor : I2CSensor {

    constructor(busPath: String) : super(busPath)

    constructor(
        busPath: String,
        multiplexer: TCA9548Multiplexer,
        multiplexerChannel: Int
    ) : super(busPath, multiplexer, multiplexerChannel)

    override val sensorAddress: Int = 0x39

    override val minReadIntervalMs: Long = 2_000L

    // AS7341 Register Addresses
    private val REG_ENABLE: Int = 0x80
    private val BIT_POWER: Int = 0
    private val BIT_MEASUREMENT: Int = 1
    private val BIT_SMUXEN: Int = 4

    private val REG_ATIME: Int = 0x81
    private val REG_ASTEP_L: Int = 0xCA       // AS7341 ASTEP is at 0xCA (vs AS7343's 0xD4)
    private val REG_CFG0: Int = 0xA9          // Bank select for AS7341 (vs AS7343's 0xBF)
    private val BIT_REGBANK: Int = 4
    private val REG_CFG1: Int = 0xAA          // Gain register (vs AS7343's 0xC6)
    private val REG_CFG6: Int = 0xAF          // SMUX command register
    private val REG_STATUS2: Int = 0xA3       // Status register (vs AS7343's 0x90)
    private val BIT_AVALID: Int = 6

    private val REG_DATA0_L: Int = 0x95       // Data register base (same as AS7343)

    private var primaryChannelData: MutableMap<String, Int> = mutableMapOf(
        "F1" to 0, "F2" to 0, "F3" to 0, "F4" to 0,
        "F5" to 0, "F6" to 0, "F7" to 0, "F8" to 0
    )

    private var updateTS: Long = 0

    companion object : SensorFactory<I2CSensor> {

        override fun create(busPath: String): AS7341Sensor = AS7341Sensor(busPath)

        private const val TAG = "AS7341Sensor"

        // AS7341 Control/Reset register
        private const val AS7341_CONTROL_REG = 0xEF
        private const val AS7341_SW_RESET_BIT = 3

        // AS7341 ID register
        private const val AS7341_ID_REG = 0x92

        // Number of data channels per SMUX cycle (6 channels x 2 bytes = 12 bytes)
        private const val CHANNELS_PER_SMUX = 6
        private const val BYTES_PER_SMUX = CHANNELS_PER_SMUX * 2

        // Channel names for output
        val primaryChannelSimpleNames = listOf(
            "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "Clear", "NIR"
        )

        // SMUX configuration for F1-F4 + Clear + NIR (from AMS application note)
        val SMUX_F1_F4 = intArrayOf(
            0x30, 0x01, 0x00, 0x00, 0x00, 0x42,
            0x00, 0x00, 0x50, 0x00, 0x00, 0x00,
            0x20, 0x04, 0x00, 0x30, 0x01, 0x50,
            0x00, 0x06
        )

        // SMUX configuration for F5-F8 + Clear + NIR (from AMS application note)
        val SMUX_F5_F8 = intArrayOf(
            0x00, 0x00, 0x00, 0x40, 0x02, 0x00,
            0x10, 0x03, 0x50, 0x10, 0x03, 0x00,
            0x00, 0x00, 0x24, 0x00, 0x00, 0x50,
            0x00, 0x06
        )
    }

    override fun disconnect() {
        if (isBusOpen) {
            val locationInfo = if (isMultiplexed()) {
                "multiplexer channel ${getSensorMultiplexerChannel()}"
            } else {
                "direct connection"
            }
            Log.d(TAG, "Disconnecting sensor on $busPath ($locationInfo) (fd=$fileDescriptor)...")

            if (isInitialized) {
                try {
                    togglePower(false)
                    Log.d(TAG, "Sensor powered down.")
                } catch (e: Exception) {
                    Log.w(TAG, "Error powering down sensor during disconnect: ${e.message}")
                }
            }
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

    // --- Bank management ---

    private fun setBankDirect(useBank1: Boolean) {
        if (fileDescriptor < 0) return
        try {
            val configWord = readByteRegDirect(REG_CFG0)
            val currentBank = (configWord shr BIT_REGBANK) and 1
            val targetBank = if (useBank1) 1 else 0
            if (currentBank != targetBank) {
                Log.d(TAG, "Setting Register Bank Access to $targetBank on fd=$fileDescriptor")
                enableBitDirect(REG_CFG0, BIT_REGBANK, useBank1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting bank for fd=$fileDescriptor: ${e.message}", e)
        }
    }

    private fun setBank(useBank1: Boolean) {
        if (fileDescriptor < 0) return
        try {
            val configWord = readByteReg(REG_CFG0)
            val currentBank = (configWord shr BIT_REGBANK) and 1
            val targetBank = if (useBank1) 1 else 0
            if (currentBank != targetBank) {
                Log.d(TAG, "Setting Register Bank Access to $targetBank on fd=$fileDescriptor")
                enableBit(REG_CFG0, BIT_REGBANK, useBank1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting bank for fd=$fileDescriptor: ${e.message}", e)
        }
    }

    // --- Power control ---

    private fun togglePower(on: Boolean) {
        if (fileDescriptor < 0) return
        try {
            setBank(false)
            Log.d(TAG, "Setting Power ${if (on) "ON" else "OFF"} on fd=$fileDescriptor")
            enableBit(REG_ENABLE, BIT_POWER, on)
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling power for fd=$fileDescriptor: ${e.message}", e)
        }
    }

    // --- Measurement control ---

    private suspend fun enableSpectralMeasurement(enable: Boolean) {
        try {
            setBank(false)
            if (enable) {
                val enableReg = readByteReg(REG_ENABLE)
                if (enableReg and (1 shl BIT_POWER) == 0) {
                    Log.w(TAG, "Warning: Enabling measurement while power is OFF. Enabling power first.")
                    togglePower(true)
                    delay(1)
                }
            }
            enableBit(REG_ENABLE, BIT_MEASUREMENT, enable)
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling measurement for fd=$fileDescriptor: ${e.message}", e)
        }
    }

    // --- Integration time and gain ---

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

    private fun setIntegrationTimeInternal(atime: Int, astep: Int) {
        try {
            Log.d(TAG, "Setting ATIME=$atime, ASTEP=$astep on fd=$fileDescriptor")
            setBank(false)
            writeByteReg(REG_ATIME, atime)
            writeWordReg(REG_ASTEP_L, astep)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting integration time for fd=$fileDescriptor: ${e.message}", e)
        }
    }

    private fun setGain(againValue: Int) {
        if (fileDescriptor < 0) return
        val safeAgain = againValue.coerceIn(0, 12)
        try {
            Log.d(TAG, "Setting Gain (AGAIN) on fd=$fileDescriptor to $safeAgain")
            setBank(false)
            setRegisterBits(REG_CFG1, 0, 5, safeAgain)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting gain for fd=$fileDescriptor: ${e.message}", e)
        }
    }

    // --- State ---

    override fun getSensorState() = object : ColorSensorState {
        override val errorMessage = lastError()
        override val connected = this@AS7341Sensor.isConnected()
        override val updateTS = this@AS7341Sensor.updateTS
        override val sensorId = this@AS7341Sensor.toString()
        override val channelData: Map<String, Int> = getLatestChannelData().toMap()
    }

    override fun shouldUpdateState(): Boolean {
        val timeDiff = System.currentTimeMillis() - updateTS
        val lightDiff: Double = abs(primaryChannelData["total_change"] ?: 0).toDouble()
        val timeStep = 1000 + (5000 * (1.0 / ln(lightDiff)))
        return (timeDiff > timeStep)
    }

    override suspend fun readDataImpl(): Map<String, Int> {
        val read = readSpectralDataOnce()
        return if (read.isEmpty()) {
            read
        } else {
            this.primaryChannelData
        }
    }

    fun getLatestChannelData(): Map<String, Int> {
        return this.primaryChannelData
    }

    // --- Spectral data reading ---

    suspend fun readSpectralDataOnce(): Map<String, Int> {
        if (!connect()) {
            return emptyMap()
        }
        val rawData = readAllChannels()
        if (rawData.isEmpty()) {
            Log.w(TAG, "Read failed or returned empty data for $busPath.")
            return emptyMap()
        }
        return extractPrimaryChannels(rawData)
    }

    suspend fun readSpectralData(): Map<String, Int> {
        if (!isInitialized) {
            Log.e(TAG, "Sensor not initialized. Call connect() first.")
            return emptyMap()
        }
        return readSpectralDataWithRetry(maxRetries = 3)
    }

    private suspend fun readSpectralDataWithRetry(maxRetries: Int): Map<String, Int> {
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
            if (attempt <= maxRetries) {
                val backoffTime = min((50 * Math.pow(2.0, (attempt - 1).toDouble())).toLong(), 500L)
                Log.d(TAG, "Waiting ${backoffTime}ms before retry attempt $attempt on fd=$fileDescriptor")
                delay(backoffTime)
            }
        }

        Log.e(TAG, "All retry attempts failed for spectral data read on fd=$fileDescriptor. Last error: ${lastException?.message}")
        return emptyMap()
    }

    /**
     * Extract primary channels from raw SMUX data and track changes.
     */
    private fun extractPrimaryChannels(rawData: Map<String, Int>): Map<String, Int> {
        val primaryMap = mutableMapOf<String, Int>()
        var totalChange = 0
        for (name in primaryChannelSimpleNames) {
            val newVal = rawData[name] ?: 0
            primaryMap[name] = newVal
            val previousVal = primaryChannelData[name] ?: 0
            val diff = newVal - previousVal
            primaryChannelData[name] = newVal
            primaryChannelData[name + "_change"] = diff
            totalChange += diff
        }
        updateTS = System.currentTimeMillis()
        primaryMap["total_change"] = totalChange
        return primaryMap
    }

    // --- Initialization ---

    override fun initializeSensor(): Boolean {
        if (fileDescriptor < 0) return false
        Log.d(TAG, "Initializing AS7341 sensor on fd=$fileDescriptor...")
        try {
            return kotlinx.coroutines.runBlocking {
                performProperPowerOnReset()
            }
        } catch (e: Exception) {
            Log.e(TAG, "EXCEPTION during sensor initialization for fd=$fileDescriptor", e)
            try {
                togglePower(false)
            } catch (powerOffException: Exception) {
                Log.w(TAG, "Failed to power off sensor after initialization failure: ${powerOffException.message}")
            }
            return false
        }
    }

    private suspend fun performProperPowerOnReset(): Boolean {
        Log.d(TAG, "Performing Power-On Reset sequence on fd=$fileDescriptor")

        try {
            // Step 1: Power off
            Log.d(TAG, "Step 1: Powering off sensor on fd=$fileDescriptor")
            setBank(false)
            togglePower(false)
            delay(5)

            // Step 2: Power on
            Log.d(TAG, "Step 2: Powering on sensor on fd=$fileDescriptor")
            togglePower(true)
            delay(1)

            // Step 3: Verify sensor is responsive
            Log.d(TAG, "Step 3: Checking sensor responsiveness on fd=$fileDescriptor")
            if (!isSensorResponsive()) {
                Log.e(TAG, "Step 3 FAILED: Sensor not responsive after power-on on fd=$fileDescriptor")
                return false
            }

            // Step 4: Set integration time (ATIME=0, ASTEP=65534 — same as AS7343)
            Log.d(TAG, "Step 4: Setting integration time on fd=$fileDescriptor")
            setIntegrationTime(0, 65534)

            // Step 5: Set gain to 10 (512x — same as AS7343)
            Log.d(TAG, "Step 5: Setting gain to 10 on fd=$fileDescriptor")
            setGain(10)

            // Step 6: Final responsiveness check
            Log.d(TAG, "Step 6: Final responsiveness check on fd=$fileDescriptor")
            if (!isSensorResponsive()) {
                Log.e(TAG, "Step 6 FAILED: Sensor became unresponsive during configuration on fd=$fileDescriptor")
                return false
            }

            Log.d(TAG, "Power-On Reset sequence completed successfully on fd=$fileDescriptor")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "EXCEPTION during Power-On Reset for fd=$fileDescriptor", e)
            return false
        }
    }

    // --- Two-phase SMUX read ---

    /**
     * Reads all channels using two SMUX cycles within a single transaction.
     * Phase 1: F1, F2, F3, F4, Clear, NIR
     * Phase 2: F5, F6, F7, F8, (Clear2, NIR2 discarded)
     */
    private suspend fun readAllChannels(): Map<String, Int> {
        if (fileDescriptor < 0) return emptyMap()

        return try {
            executeTransaction {
                val channelData = mutableMapOf<String, Int>()

                // Phase 1: Read F1-F4 + Clear + NIR
                Log.d(TAG, "SMUX Phase 1: F1-F4 + Clear + NIR on fd=$fileDescriptor")
                writeSmuxConfigTransaction(SMUX_F1_F4)
                triggerSmuxLoadTransaction()
                enableSpectralMeasurementTransaction(true)

                if (!waitForDataReadyTransaction(2000)) {
                    Log.e(TAG, "Timeout waiting for Phase 1 data on fd=$fileDescriptor")
                    enableSpectralMeasurementTransaction(false)
                    return@executeTransaction emptyMap<String, Int>()
                }

                val phase1 = readDataRegistersTransaction()
                enableSpectralMeasurementTransaction(false)

                if (phase1.size < CHANNELS_PER_SMUX) {
                    Log.e(TAG, "Phase 1 returned ${phase1.size} channels (expected $CHANNELS_PER_SMUX)")
                    return@executeTransaction emptyMap<String, Int>()
                }

                channelData["F1"] = phase1[0]
                channelData["F2"] = phase1[1]
                channelData["F3"] = phase1[2]
                channelData["F4"] = phase1[3]
                channelData["Clear"] = phase1[4]
                channelData["NIR"] = phase1[5]

                // Phase 2: Read F5-F8 + Clear + NIR (discard duplicates)
                Log.d(TAG, "SMUX Phase 2: F5-F8 on fd=$fileDescriptor")
                writeSmuxConfigTransaction(SMUX_F5_F8)
                triggerSmuxLoadTransaction()
                enableSpectralMeasurementTransaction(true)

                if (!waitForDataReadyTransaction(2000)) {
                    Log.e(TAG, "Timeout waiting for Phase 2 data on fd=$fileDescriptor")
                    enableSpectralMeasurementTransaction(false)
                    return@executeTransaction emptyMap<String, Int>()
                }

                val phase2 = readDataRegistersTransaction()
                enableSpectralMeasurementTransaction(false)

                if (phase2.size < CHANNELS_PER_SMUX) {
                    Log.e(TAG, "Phase 2 returned ${phase2.size} channels (expected $CHANNELS_PER_SMUX)")
                    return@executeTransaction emptyMap<String, Int>()
                }

                channelData["F5"] = phase2[0]
                channelData["F6"] = phase2[1]
                channelData["F7"] = phase2[2]
                channelData["F8"] = phase2[3]
                // phase2[4] = Clear2 (discarded)
                // phase2[5] = NIR2 (discarded)

                Log.d(TAG, "All channels read successfully on fd=$fileDescriptor")
                channelData
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during readAllChannels transaction for fd=$fileDescriptor: ${e.message}", e)

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

            emptyMap()
        }
    }

    // --- Transaction helper methods ---

    /**
     * Writes the SMUX configuration bytes to registers 0x00-0x13 within a transaction.
     */
    private fun writeSmuxConfigTransaction(config: IntArray) {
        for (i in config.indices) {
            writeByteRegTransaction(i, config[i])
        }
    }

    /**
     * Triggers SMUX load: write 0x10 to CFG6, set SMUXEN, wait for SMUXEN to self-clear.
     */
    private suspend fun triggerSmuxLoadTransaction() {
        // Write SMUX command: 0x10 means "write SMUX config from RAM to SMUX chain"
        writeByteRegTransaction(REG_CFG6, 0x10)

        // Set SMUXEN bit in ENABLE register to trigger the load
        enableBitTransaction(REG_ENABLE, BIT_SMUXEN, true)

        // Wait for SMUXEN to self-clear (indicates load completed)
        val startTime = System.currentTimeMillis()
        val timeout = 100L
        while (System.currentTimeMillis() - startTime < timeout) {
            val enableReg = readByteRegTransaction(REG_ENABLE)
            val smuxActive = (enableReg shr BIT_SMUXEN) and 1 == 1
            if (!smuxActive) {
                return
            }
            delay(1)
        }
        Log.w(TAG, "SMUX load timed out on fd=$fileDescriptor")
    }

    private suspend fun enableSpectralMeasurementTransaction(enable: Boolean) {
        if (enable) {
            val enableReg = readByteRegTransaction(REG_ENABLE)
            if (enableReg and (1 shl BIT_POWER) == 0) {
                Log.w(TAG, "Enabling measurement while power is OFF. Enabling power first.")
                enableBitTransaction(REG_ENABLE, BIT_POWER, true)
                delay(1)
            }
        }
        enableBitTransaction(REG_ENABLE, BIT_MEASUREMENT, enable)
    }

    private suspend fun waitForDataReadyTransaction(timeoutMs: Long): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val statusReg = readByteRegTransaction(REG_STATUS2)
            val avalid = (statusReg shr BIT_AVALID) and 1 == 1
            if (avalid) {
                return true
            }
            delay(10)
        }
        return false
    }

    /**
     * Reads 6 channels (12 bytes) from data registers 0x95-0xA0 using block read.
     */
    private fun readDataRegistersTransaction(): List<Int> {
        val dataBytes = ByteArray(BYTES_PER_SMUX)
        val bytesRead = I2cNative.readBlockData(fileDescriptor, REG_DATA0_L, dataBytes, dataBytes.size)

        if (bytesRead == dataBytes.size) {
            val values = mutableListOf<Int>()
            for (i in 0 until CHANNELS_PER_SMUX) {
                val lo = dataBytes[i * 2].toInt() and 0xFF
                val hi = dataBytes[i * 2 + 1].toInt() and 0xFF
                values.add((hi shl 8) or lo)
            }
            return values
        } else {
            // Fallback to individual register reads
            Log.w(TAG, "Block read returned $bytesRead bytes (expected ${dataBytes.size}), falling back to individual reads on fd=$fileDescriptor")
            val values = mutableListOf<Int>()
            for (i in 0 until CHANNELS_PER_SMUX) {
                val dataLReg = REG_DATA0_L + (i * 2)
                val dataHReg = dataLReg + 1
                val dataL = readByteRegTransaction(dataLReg)
                val dataH = readByteRegTransaction(dataHReg)
                values.add(((dataH and 0xFF) shl 8) or (dataL and 0xFF))
            }
            return values
        }
    }

    // --- Reset and Recovery ---

    suspend fun performSoftwareReset(): Boolean {
        if (fileDescriptor < 0) {
            Log.e(TAG, "Cannot perform software reset: invalid file descriptor")
            return false
        }

        try {
            Log.d(TAG, "Performing software reset on fd=$fileDescriptor")
            setBankDirect(false)

            // AS7341 reset bit is bit 3 in CONTROL register 0xEF
            enableBitDirect(AS7341_CONTROL_REG, AS7341_SW_RESET_BIT, true)
            delay(1)

            val controlReg = readByteRegDirect(AS7341_CONTROL_REG)
            val resetInProgress = (controlReg shr AS7341_SW_RESET_BIT) and 1 == 1

            if (resetInProgress) {
                Log.w(TAG, "Software reset still in progress on fd=$fileDescriptor")
                delay(5)
                val controlReg2 = readByteRegDirect(AS7341_CONTROL_REG)
                val stillInProgress = (controlReg2 shr AS7341_SW_RESET_BIT) and 1 == 1
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

    fun isSensorResponsive(): Boolean {
        if (fileDescriptor < 0) {
            Log.w(TAG, "isSensorResponsive: invalid file descriptor")
            return false
        }

        try {
            val id = readByteRegDirect(AS7341_ID_REG)
            Log.d(TAG, "Sensor communication test on fd=$fileDescriptor: read ID=0x${id.toString(16).uppercase()}")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Sensor unresponsive on fd=$fileDescriptor: ${e.message}", e)
            return false
        }
    }

    /**
     * Progressive recovery: software reset → power cycle → re-initialize.
     * AS7341 doesn't have SAI or auto-SMUX, so recovery is simpler than AS7343.
     */
    suspend fun recoverSensor(): Boolean {
        if (fileDescriptor < 0) {
            Log.e(TAG, "Cannot recover sensor: invalid file descriptor")
            return false
        }

        Log.w(TAG, "Attempting sensor recovery on fd=$fileDescriptor")

        // Step 1: Try software reset
        if (performSoftwareReset() && isSensorResponsive()) {
            Log.i(TAG, "Sensor recovered using software reset on fd=$fileDescriptor")
            if (initializeSensor()) {
                Log.i(TAG, "Sensor re-initialized successfully after software reset on fd=$fileDescriptor")
                return true
            } else {
                Log.e(TAG, "Failed to re-initialize sensor after software reset on fd=$fileDescriptor")
                return false
            }
        }

        // Step 2: Try full power cycle
        try {
            Log.d(TAG, "Attempting full power cycle on fd=$fileDescriptor")
            togglePower(false)
            delay(10)
            togglePower(true)
            delay(5)

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
