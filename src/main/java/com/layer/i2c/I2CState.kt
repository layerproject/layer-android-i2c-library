package com.layer.i2c


interface SensorFactory<T> {
    fun create(
        busPath: String,
    ): T
}

/** SensorState and derived interfaces represent the
 * a single reading from a particular I2C sensor.
 */
interface SensorState {
    val connected: Boolean
    val updateTS: Long
    val sensorId: String
}

/**
 * A Representation of a single reading from an
 * ambient light sensor like the AS7343
 */
interface ColorSensorState : SensorState {
    /**
     * Color channel data A map of color channel readings,
     * each indexed by an arbitrary channel label string.
     */
    val channelData: Map<String, Int>
}

/**
 * A representation of a single reading from a Temperature/Humidity sensor
 * Temperature and Humidity are represented as floating point numbers.
 */
interface TemperatureSensorState : SensorState {
    val temperature: Double
    val humidity: Double
}

