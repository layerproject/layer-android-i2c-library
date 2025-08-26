package com.layer.i2c

import android.R.attr.delay
import android.annotation.SuppressLint
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.layer.i2c.RunnableSensor.Companion.DEFAULT_UPDATE_FREQUENCY
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

interface RunnableSensor {
    var job : Job?
    fun start() : Job
    fun isConnected() : Boolean {
        return job != null && job!!.isActive
    }
    
    companion object {
        const val DEFAULT_UPDATE_FREQUENCY = 5000L
    }
}

/**
 * Read a sensor's value from a device node in sysfs
 */
abstract class DeviceNodeSensor<T>(val initialValue:T, val context : CoroutineDispatcher = Dispatchers.IO) : RunnableSensor {
    override var job : Job? = null
    protected open val state : MutableState<T> = mutableStateOf<T>(initialValue)
    open var valueLabel:String = "Value"
    protected open val fields = mutableMapOf(valueLabel to state)
    companion object {
        val TAG = "DeviceNodeSensors"
    }
    
    val updateFrequencyMS: Long = DEFAULT_UPDATE_FREQUENCY
    
    fun setUpdateFrequency(freq:Long) : DeviceNodeSensor<T> {
        this.updateFrequencyMS
        return this
    }
    
    fun getSensorState() = object : GenericSensorState<T> {
        override val connected = isConnected()
        override val updateTS = System.currentTimeMillis()
        override val sensorId = this.toString()
        override val stateFields = mapOf(valueLabel to state)
    }

   
}
