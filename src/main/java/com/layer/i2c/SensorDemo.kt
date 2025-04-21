package com.layer.i2c

import android.util.Log

/**
 * Demonstration class showing how to use the I2C library with AS7343.
 */
class SensorDemo {
    companion object {
        private const val TAG = "AS7343-SensorDemo"
    }

    /**
     * Run a complete demo of sensor functionality.
     * Connects, initializes, reads data, controls LEDs, and disconnects.
     */
    fun runDemo() {
        Log.i(TAG, "Starting AS7343 Demo...")
        // Create sensors for both I2C buses using the new class
        val sensor1 = AS7343Sensor("/dev/i2c-0")
        val sensor2 = AS7343Sensor("/dev/i2c-1")

        // Connect and initialize sensors
        val sensor1Ready = sensor1.connect() // connect() now also initializes
        val sensor2Ready = sensor2.connect()

        if (sensor1Ready) {
            Log.d(TAG, "Sensor 1 ready (Connected & Initialized)")

            // Optional: Set custom gain/time if needed after connecting
            // sensor1.setGain(10) // e.g., 512x gain

            // Turn on LED (example: 12mA)
            Log.d(TAG,"Turning Sensor 1 LED ON")
            sensor1.controlLED(true, 4) // Use value 4 for 12mA
            Thread.sleep(100) // Give LED time to turn on

            // Read sensor data (using the method for already connected sensors)
            val data1 = sensor1.readSpectralData() // Returns primary channels map
            if (data1.isNotEmpty()) {
                 Log.d(TAG, "Sensor 1 Primary Data: $data1")
                 // Access specific channels if needed
                 Log.d(TAG, "Sensor 1 F1 value: ${data1["F1"]}")
                 Log.d(TAG, "Sensor 1 NIR value: ${data1["NIR"]}")
            } else {
                 Log.w(TAG, "Sensor 1 read returned empty data.")
            }


            // Turn off LED
            Log.d(TAG,"Turning Sensor 1 LED OFF")
            sensor1.controlLED(false)
        } else {
            Log.e(TAG, "Failed to connect/initialize Sensor 1 on /dev/i2c-0")
        }

        Thread.sleep(500) // Pause between sensor operations

        if (sensor2Ready) {
            Log.d(TAG, "Sensor 2 ready (Connected & Initialized)")

             // Turn on LED with different current (example: 20mA, value 8)
            Log.d(TAG,"Turning Sensor 2 LED ON")
            sensor2.controlLED(true, 8) // Use value 8 for 20mA
            Thread.sleep(100)

            // Read sensor data
            val data2 = sensor2.readSpectralData()
             if (data2.isNotEmpty()) {
                Log.d(TAG, "Sensor 2 Primary Data: $data2")
            } else {
                 Log.w(TAG, "Sensor 2 read returned empty data.")
            }

            // Turn off LED
            Log.d(TAG,"Turning Sensor 2 LED OFF")
            sensor2.controlLED(false)
        } else {
            Log.e(TAG, "Failed to connect/initialize Sensor 2 on /dev/i2c-1")
        }

        // Clean up: Disconnect sensors (powers down and closes fd)
        Log.d(TAG,"Disconnecting sensors...")
        sensor1.disconnect()
        sensor2.disconnect()

        Log.i(TAG, "Demo completed.")
    }

    /**
     * Measure light on both sensors and return aggregated primary channel results.
     * Handles connect/disconnect internally.
     * @return Map of all primary channel readings from both sensors, prefixed. Empty if fails.
     */
    fun measureAllPrimaryChannels(): Map<String, Int> {
        Log.d(TAG, "Measuring all primary channels...")
        val results = mutableMapOf<String, Int>()
        val sensor1 = AS7343Sensor("/dev/i2c-0")
        val sensor2 = AS7343Sensor("/dev/i2c-1")

        // Use readSpectralDataOnce which handles connect/init/read/disconnect
        val data1 = sensor1.readSpectralDataOnce()
        if (data1.isNotEmpty()) {
            Log.d(TAG,"Sensor 1 read OK.")
            results.putAll(data1.mapKeys { "sensor1_${it.key}" })
        } else {
            Log.w(TAG,"Sensor 1 read failed.")
        }
        // Important: readSpectralDataOnce leaves the sensor disconnected.

        val data2 = sensor2.readSpectralDataOnce()
         if (data2.isNotEmpty()) {
             Log.d(TAG,"Sensor 2 read OK.")
            results.putAll(data2.mapKeys { "sensor2_${it.key}" })
        } else {
             Log.w(TAG,"Sensor 2 read failed.")
        }

        Log.d(TAG, "Finished measuring all channels. Result count: ${results.size}")
        return results
    }
}