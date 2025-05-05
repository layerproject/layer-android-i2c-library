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
     * Check if this is the correct SHT40 sensor by attempting to communicate with it.
     * SHT40 sensors don't have an ID register, so we try to reset and read a measurement.
     */
    override fun isCorrectSensor(): Boolean {
        if (!isReady()) {
            return false
        }
        
        return try {
            // Then attempt to read a measurement
            readMeasurement()
            
            // If we got valid temperature/humidity readings, it's the correct sensor
            val validTemp = temperature != DEFAULT_TEMPERATURE
            val validHumidity = humidity != DEFAULT_HUMIDITY
            
            if (validTemp && validHumidity) {
                Log.d(TAG, "SHT40 sensor identified successfully: Temp=$temperature°C, Humidity=$humidity%")
                true
            } else {
                Log.d(TAG, "Failed to identify SHT40 sensor, got invalid readings")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if SHT40 sensor is present: ${e.message}")
            false
        }
    }
    
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

    @Synchronized
    private fun softReset(): Boolean {
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
    /**
     * Read measurement from SHT40 sensor
     */
    @Synchronized
    private fun readMeasurement() {
        if (!switchToDevice()) {
            Log.e(TAG, "Failed switching to device ahead of writing command and reading result")
            temperature = DEFAULT_TEMPERATURE
            humidity = DEFAULT_HUMIDITY
        }

        try {
            // Send measurement command (single shot, high precision)
            val writeResult = I2cNative.write(fileDescriptor, CMD_MEASURE_HIGH_PRECISION)
            Log.d(TAG, "Measure temperature and humidity with high precision on SHT40: $writeResult")
            
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

                // Verify CRC (you'll need to implement the CRC-8 algorithm)
                if (calculateCRC8(buffer, 0, 2) == tempCrc &&
                    calculateCRC8(buffer, 3, 2) == humCrc) {

                    // Convert raw values to actual temperature and humidity
                    // Formula should be in the datasheet, but typically:
                    temperature = TEMPERATURE_OFFSET + TEMPERATURE_SCALE * tempRaw / 65535.0
                    humidity = HUMIDITY_OFFSET + HUMIDITY_SCALE * humRaw / 65535.0

                    Log.d(TAG, "SHT40 Temperature: $temperature °C, Humidity: $humidity %RH")
                } else {
                    Log.e(TAG, "SHT40 CRC check failed")
                }
            } else {
                Log.e(TAG, "Failed to read data block from SHT40: $bytesRead")
                temperature = DEFAULT_TEMPERATURE
                humidity = DEFAULT_HUMIDITY
                return
            }
        } catch (e: IOException) {
            Log.e(TAG, "I/O error reading from SHT40: ${e.message}")
            temperature = DEFAULT_TEMPERATURE
            humidity = DEFAULT_HUMIDITY
        } catch (e: Exception) {
            Log.e(TAG, "Error reading from SHT40: ${e.message}")
            temperature = DEFAULT_TEMPERATURE
            humidity = DEFAULT_HUMIDITY
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
     * @return Map containing temperature and humidity data
     */
    @Synchronized
    fun readData(): Map<String, Int> {
        try {
            readMeasurement()
            
            if (temperature == DEFAULT_TEMPERATURE || humidity == DEFAULT_HUMIDITY) {
                // If reading failed, return error values
                return mapOf("ERROR" to 65535)
            }
            
            // Scale values to integers for easier handling
            // Temperature * 100 to preserve 2 decimal places
            // Humidity * 100 to preserve 2 decimal places
            val tempScaled = (temperature * 100).toInt()
            val humidityScaled = (humidity * 100).toInt()
            
            // Create a map with our temperature/humidity data
            return mapOf(
                "TEMP" to tempScaled,
                "HUMIDITY" to humidityScaled
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error reading temperature/humidity data: ${e.message}")
            return mapOf("ERROR" to 65535)
        }
    }
}