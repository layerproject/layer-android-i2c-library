package com.layer.i2c

import android.util.Log
import kotlinx.coroutines.delay

/**
 * SHT40 Temperature and Humidity Sensor implementation.
 * This sensor provides accurate temperature and humidity readings.
 */
open class SHT40Sensor : I2CSensor {
    
    /**
     * Constructor for direct I2C connection (no multiplexer).
     * @param devicePath The I2C bus path (e.g., "/dev/i2c-0")
     */
    constructor(devicePath: String) : super(devicePath)
    
    /**
     * Constructor for multiplexed I2C connection.
     * @param devicePath The I2C bus path (e.g., "/dev/i2c-0")
     * @param multiplexer The TCA9548 multiplexer instance
     * @param multiplexerChannel The channel on the multiplexer (0-7)
     */
    constructor(
        devicePath: String,
        multiplexer: TCA9548Multiplexer,
        multiplexerChannel: Int,
    ) : super(devicePath, multiplexer, multiplexerChannel)
    
    companion object : SensorFactory<I2CSensor> {
        private const val TAG = "SHT40Sensor"
        override fun create(busPath: String): SHT40Sensor = SHT40Sensor(busPath)
        
        // SHT40 commands
        private const val CMD_MEASURE_HIGH_PRECISION = 0xFD
        
        // Default values when reading fails
        public const val DEFAULT_TEMPERATURE = -9999.0
        public const val DEFAULT_HUMIDITY = -9999.0
        
        // Temperature and humidity scaling (from raw to actual values)
        private const val TEMPERATURE_SCALE = 175.0
        private const val TEMPERATURE_OFFSET = -45.0
        private const val HUMIDITY_SCALE = 125.0
        private const val HUMIDITY_OFFSET = -6.0
    }
    
    // SHT40 I2C address (0x44 is the default address)
    override val sensorAddress: Int = 0x44

    // Temperature/humidity is low priority — read at most every 10 seconds
    override val minReadIntervalMs: Long = 10_000L
    
    // Temperature and humidity values
    var temperature: Double = DEFAULT_TEMPERATURE
    
    var humidity: Double = DEFAULT_HUMIDITY
    
    fun getTemp(): Double {
        return this.temperature
    }
    
    fun getHumid(): Double {
        return this.humidity
    }
    
    /**
     * Initialize the SHT40 sensor
     */
    override fun initializeSensor(): Boolean {
        if (fileDescriptor < 0) return false
        
        return try {
            Log.d(TAG, "Initializing SHT40 sensor on fd=$fileDescriptor")
            
            if (!kotlinx.coroutines.runBlocking { softReset() }) {
                logError(TAG, "SHT40 sensor soft reset command failed")
                return false
            }
            
            return true
        } catch (e: Exception) {
            logError(TAG, "Error initializing SHT40 sensor: ${e.message}")
            false
        }
    }
    
    private suspend fun softReset(): Boolean {
        val writeResult: Int
        val lock = fdLock ?: this

        synchronized(lock) {
            if (!switchToDeviceBlocking()) {
                logError(TAG, "Failed switching to device ahead of sending command")
                return false
            }

            writeResult = I2cNative.write(fileDescriptor, 0x94)
            Log.d(TAG, "Soft reset command result on SHT40: $writeResult")
        }

        // SHT40 needs about 100ms to soft reset - delay outside lock
        delay(100)

        return writeResult == 1
    }
    
    private fun calculateCRC8(data: ByteArray, offset: Int, length: Int): Int {
        var crc = 0xFF // Initialization value
        val polynomial = 0x31 // x^8 + x^5 + x^4 + 1
        
        for (i in offset until offset + length) {
            crc = crc xor (data[i].toInt() and 0xFF)
            
            for (j in 0 until 8) {
                if ((crc and 0x80) != 0) {
                    crc = ((crc shl 1) and 0xFF) xor polynomial
                } else {
                    crc = (crc shl 1) and 0xFF
                }
            }
        }
        
        return crc
    }
    
    override fun getSensorState() = object : TemperatureSensorState {
        override val errorMessage = lastError()
        override val connected = this@SHT40Sensor.isConnected()
        override val updateTS = System.currentTimeMillis()
        override val sensorId = this@SHT40Sensor.toString()
        override val temperature = this@SHT40Sensor.temperature
        override val humidity = this@SHT40Sensor.humidity
    }
    
    /**
     * Read data from the sensor, including temperature and humidity
     * Uses transaction-level locking to ensure atomic operation across all I2C calls.
     * @return Map containing temperature and humidity data
     */
    override suspend fun readDataImpl(): Map<String, Int> {
        if (!isReady()) {
            return mapOf("ERROR" to 65535)
        }

        return executeTransaction {
            try {

                // Entire SHT40 operation is now atomic
                val writeResult = I2cNative.write(fileDescriptor, CMD_MEASURE_HIGH_PRECISION)
                Log.d(TAG, "Measure temperature and humidity with high precision on SHT40: $writeResult")

                if (writeResult != 1) {
                    logError(TAG, "Failed to send measurement command to SHT40")
                    mapOf("ERROR" to 65535)
                } else {
                    // SHT40 high-precision measurement completes in 8.2ms max per datasheet
                    delay(15)
                }
                // Read 6 bytes: 2 for temperature, 1 CRC, 2 for humidity, 1 CRC
                val buffer = ByteArray(6)
                val bytesRead = I2cNative.readRawBytes(fileDescriptor, buffer, 6)
                
                if (bytesRead == 6) {
                    // Extract temperature (first 2 bytes)
                    val tempRaw =
                        ((buffer[0].toInt() and 0xFF) shl 8) or (buffer[1].toInt() and 0xFF)
                    val tempCrc = buffer[2].toInt() and 0xFF
                    
                    // Extract humidity (next 2 bytes)
                    val humRaw =
                        ((buffer[3].toInt() and 0xFF) shl 8) or (buffer[4].toInt() and 0xFF)
                    val humCrc = buffer[5].toInt() and 0xFF
                    
                    // Verify CRC
                    if (calculateCRC8(buffer, 0, 2) == tempCrc &&
                        calculateCRC8(buffer, 3, 2) == humCrc
                    ) {
                        
                        // Convert raw values to actual temperature and humidity
                        val tempValue = TEMPERATURE_OFFSET + TEMPERATURE_SCALE * tempRaw / 65535.0
                        val humidityValue = HUMIDITY_OFFSET + HUMIDITY_SCALE * humRaw / 65535.0
                        
                        Log.d(TAG, "SHT40 Temperature: $tempValue °C, Humidity: $humidityValue %RH")
                        
                        // Update instance variables
                        temperature = tempValue
                        humidity = humidityValue
                        
                        // Scale values to integers for easier handling
                        val tempScaled = (tempValue * 100).toInt()
                        val humidityScaled = (humidityValue * 100).toInt()
                        
                        mapOf(
                            "TEMP" to tempScaled,
                            "HUMIDITY" to humidityScaled
                        )
                    } else {
                        logError(TAG, "SHT40 CRC check failed")
                        temperature = DEFAULT_TEMPERATURE
                        humidity = DEFAULT_HUMIDITY
                        mapOf("ERROR" to 65535)
                    }
                } else {
                    logError(TAG, "Failed to read data block from SHT40: $bytesRead")
                    temperature = DEFAULT_TEMPERATURE
                    humidity = DEFAULT_HUMIDITY
                    mapOf("ERROR" to 65535)
                }
                
            } catch (e: Exception) {
                logError(TAG, "Error during SHT40 transaction: ${e.message}", e)
                temperature = DEFAULT_TEMPERATURE
                humidity = DEFAULT_HUMIDITY
                mapOf("ERROR" to 65535)
            }
        }
    }
}