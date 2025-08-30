package com.layer.i2c

import androidx.compose.runtime.MutableState
import java.sql.Date
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

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
    
    val errorMessage: String?
    
    
    open fun hasError() : Boolean {
        return errorMessage != null
    }
    
    open fun error() : String {
        return errorMessage ?: "N/A"
    }
    
    public fun timeOfUpdate(updateTS:Long) : String {
        val date = Date(updateTS)
        return DateTimeFormatter.ofPattern("HH:mm:ss").format( date.toInstant().atZone(java.time.ZoneId.systemDefault()) )
    }
    
    public fun ageOfUpdate() : String {
        val duration = System.currentTimeMillis() - updateTS
        return duration.milliseconds.toString( DurationUnit.SECONDS )
    }
}

interface GenericSensorState<T> : SensorState {
    val stateFields: Map<String, MutableState<T>>
    
    override fun hasError() : Boolean {
        return super.hasError() || stateFields.keys.contains("ERROR")
    }
    
    override fun error() : String {
        return if (stateFields.keys.contains("ERROR")) {
            stateFields["ERROR"]?.value.toString() ?: "N/A"
        } else {
            super.error()
        }
    }
}

interface StringSensorState : GenericSensorState<String> {
}

/**
 * Creates an anonymous object that implemenmts the GenericSensorState interface. This is used
 * to hold sensor readings at a given point in time. This version of the interface is generic in
 * the sense that it uses a map to store arbitrary key -> value pairs.
 */
fun newSensorState(isConnected : Boolean, newSensorId : String, fields: Map<String, String>?) = object : GenericSensorState<String> {
    override val connected = isConnected
    override val errorMessage = if (fields?.contains("ERROR") == true) {
        fields["ERROR"]
    } else {
        null
    }
    override val updateTS = System.currentTimeMillis()
    override val sensorId = newSensorId
    override val stateFields = fields?.map { (key, value) -> key to mutableStateOf(value) }!!.toMap()
}

fun newSensorState(sensor: I2CSensor) : SensorState {
    return sensor.getSensorState()
}



interface MultiplexerState : SensorState {
    val deviceSummary : String
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

