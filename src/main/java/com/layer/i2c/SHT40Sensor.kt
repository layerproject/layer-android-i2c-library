package com.layer.i2c

import android.util.Log
import java.io.IOException

/**
 * SHT40 Temperature and Humidity Sensor implementation.
 * This sensor provides accurate temperature and humidity readings.
 */
class SHT40Sensor(devicePath: String) : I2CSensor(devicePath) {
    companion object {
        private const val TAG = "SHT40Sensor"
        
        // SHT40 commands
        private const val CMD_MEASURE_HIGH_PRECISION = 0xFD
        
        // Default values when reading fails
        private const val DEFAULT_TEMPERATURE = -9999.0
        private const val DEFAULT_HUMIDITY = -9999.0
        
        // Temperature and humidity scaling (from raw to actual values)
        private const val TEMPERATURE_SCALE = 175.0
        private const val TEMPERATURE_OFFSET = -45.0
        private const val HUMIDITY_SCALE = 125.0
        private const val HUMIDITY_OFFSET = -6.0
    }
    
    // SHT40 I2C address (0x44 is the default address)
    override val sensorAddress: Int = 0x44
    
    // Temperature and humidity values
    private var temperature: Double = DEFAULT_TEMPERATURE
    private var humidity: Double = DEFAULT_HUMIDITY
    
    
    /**
     * Initialize the SHT40 sensor
     */
    override fun initializeSensor(): Boolean {
        if (fileDescriptor < 0) return false
        
        return try {
            Log.d(TAG, "Initializing SHT40 sensor on fd=$fileDescriptor")

            if (!softReset()) {
                Log.e(TAG, "SHT40 sensor soft reset command failed")
                return false
            }
            
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing SHT40 sensor: ${e.message}")
            false
        }
    }

    private fun softReset(): Boolean {
        val lock = fdLock ?: this
        
        synchronized(lock) {
            if (!switchToDevice()) {
                Log.e(TAG, "Failed switching to device ahead of sending command")
                return false
            }

            val writeResult = I2cNative.write(fileDescriptor, 0x94)
            Log.d(TAG, "Soft reset command result on SHT40: $writeResult")

            Thread.sleep(100)
            // SHT40 needs about 100ms to soft reset

            return writeResult == 1
        }
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
    
    /**
     * Read data from the sensor, including temperature and humidity
     * Uses transaction-level locking to ensure atomic operation across all I2C calls.
     * @return Map containing temperature and humidity data
     */
    fun readData(): Map<String, Int> {
        if (!isReady()) {
            return mapOf("ERROR" to 65535)
        }

        return try {
            executeTransaction {
                // Entire SHT40 operation is now atomic
                val writeResult = I2cNative.write(fileDescriptor, CMD_MEASURE_HIGH_PRECISION)
                Log.d(TAG, "Measure temperature and humidity with high precision on SHT40: $writeResult")
                
                if (writeResult != 1) {
                    Log.e(TAG, "Failed to send measurement command to SHT40")
                    return@executeTransaction mapOf("ERROR" to 65535)
                }
                
                // SHT40 needs about 100ms to complete the measurement
                Thread.sleep(100)
                
                // Read 6 bytes: 2 for temperature, 1 CRC, 2 for humidity, 1 CRC
                val buffer = ByteArray(6)
                val bytesRead = I2cNative.readRawBytes(fileDescriptor, buffer, 6)

                if (bytesRead == 6) {
                    // Extract temperature (first 2 bytes)
                    val tempRaw = ((buffer[0].toInt() and 0xFF) shl 8) or (buffer[1].toInt() and 0xFF)
                    val tempCrc = buffer[2].toInt() and 0xFF

                    // Extract humidity (next 2 bytes)
                    val humRaw = ((buffer[3].toInt() and 0xFF) shl 8) or (buffer[4].toInt() and 0xFF)
                    val humCrc = buffer[5].toInt() and 0xFF

                    // Verify CRC
                    if (calculateCRC8(buffer, 0, 2) == tempCrc &&
                        calculateCRC8(buffer, 3, 2) == humCrc) {

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
                        Log.e(TAG, "SHT40 CRC check failed")
                        temperature = DEFAULT_TEMPERATURE
                        humidity = DEFAULT_HUMIDITY
                        mapOf("ERROR" to 65535)
                    }
                } else {
                    Log.e(TAG, "Failed to read data block from SHT40: $bytesRead")
                    temperature = DEFAULT_TEMPERATURE
                    humidity = DEFAULT_HUMIDITY
                    mapOf("ERROR" to 65535)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during SHT40 transaction: ${e.message}", e)
            temperature = DEFAULT_TEMPERATURE
            humidity = DEFAULT_HUMIDITY
            mapOf("ERROR" to 65535)
        }
    }
}