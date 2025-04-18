package com.layer.i2c

import android.util.Log

/**
 * Demonstration class showing how to use the I2C library.
 * This is a reference implementation and can be used as a starting point
 * for your own implementations.
 */
class SensorDemo {
    companion object {
        private const val TAG = "SensorDemo"
    }
    
    /**
     * Run a complete demo of sensor functionality.
     * Connects to sensors, reads data, controls LEDs, and disconnects.
     */
    fun runDemo() {
        // Create sensors for both I2C buses
        val sensor1 = AS7341Sensor("/dev/i2c-0")
        val sensor2 = AS7341Sensor("/dev/i2c-1")
        
        // Connect to sensors
        val sensor1Connected = sensor1.connect()
        val sensor2Connected = sensor2.connect()
        
        if (sensor1Connected) {
            Log.d(TAG, "Sensor 1 connected successfully")
            
            // Turn on LED
            sensor1.controlLED(true, 30)
            
            // Read sensor data
            val data1 = sensor1.readSpectralData()
            Log.d(TAG, "Sensor 1 data: $data1")
            
            // Turn off LED
            sensor1.controlLED(false)
        } else {
            Log.e(TAG, "Failed to connect to Sensor 1")
        }
        
        if (sensor2Connected) {
            Log.d(TAG, "Sensor 2 connected successfully")
            
            // Turn on LED with different current
            sensor2.controlLED(true, 50)
            
            // Read sensor data
            val data2 = sensor2.readSpectralData()
            Log.d(TAG, "Sensor 2 data: $data2")
            
            // Turn off LED
            sensor2.controlLED(false)
        } else {
            Log.e(TAG, "Failed to connect to Sensor 2")
        }
        
        // Clean up
        sensor1.disconnect()
        sensor2.disconnect()
        
        Log.d(TAG, "Demo completed")
    }
    
    /**
     * Measure light on both sensors and return aggregated results.
     * @return Map of all channel readings from both sensors
     */
    fun measureAllChannels(): Map<String, Int> {
        val results = mutableMapOf<String, Int>()
        val sensor1 = AS7341Sensor("/dev/i2c-0")
        val sensor2 = AS7341Sensor("/dev/i2c-1")
        
        if (sensor1.connect()) {
            results.putAll(sensor1.readSpectralData().mapKeys { "sensor1_${it.key}" })
            sensor1.disconnect()
        }
        
        if (sensor2.connect()) {
            results.putAll(sensor2.readSpectralData().mapKeys { "sensor2_${it.key}" })
            sensor2.disconnect()
        }
        
        return results
    }
}