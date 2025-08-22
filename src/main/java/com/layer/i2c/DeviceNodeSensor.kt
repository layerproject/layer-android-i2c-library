package com.layer.i2c

import android.R.attr.delay
import android.annotation.SuppressLint
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
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
    fun start() : Unit
    fun isConnected() : Boolean {
        return job != null && job!!.isActive
    }
}

/**
 * Read a sensor's value from a device node in sysfs
 */
open class DeviceNodeSensor<T>(val initialValue:T, val context : CoroutineDispatcher = Dispatchers.IO) : RunnableSensor {
    override var job : Job? = null
    protected open val state : MutableState<T> = mutableStateOf<T>(initialValue)
    var valueLabel:String = "Value"
    protected open val fields = mutableMapOf(valueLabel to state)
    companion object {
        val TAG = "DeviceNodeSensors"
    }
    

    
    fun getSensorState() = object : GenericSensorState<T> {
        override val connected = isConnected()
        override val updateTS = System.currentTimeMillis()
        override val sensorId = this.toString()
        override val stateFields = mapOf(valueLabel to state)
    }
    
    override fun start() {
    
    }
   
}

open class ThermalZoneSensor(initialValue: String = "", val zoneIds: IntRange, context : CoroutineDispatcher = Dispatchers.IO)  : DeviceNodeSensor<String>(initialValue, context)  {
    val zones = zoneIds.map { i -> "/sys/class/thermal/thermal_zone$i/temp" }
    /**
     * Starts monitoring device temperature
     */
    @SuppressLint("DefaultLocale")
    override fun start() {
        this.job = CoroutineScope(context).launch {
            // GPU thermal zones, determined by probing /sys/class/thermal/thermal_zone*/type
            

            
            fun err(error : String) {
                if (fields.containsKey("error")){
                    fields["error"]?.value = error
                } else {
                    fields["error"] = mutableStateOf(error)
                }
            }
            
            while (isActive) {
                var index:Int = 0;
                val readings : MutableList<Float> = mutableListOf()
                var sumOfTemps = 0F
                for (zone in zones) {
                    
                    try {
                        val file = File(zone)
                        if (file.canRead()) {
                            val temp = file
                                    .readText()
                                    .trim()
                                    .toInt()
                            val tempFloat = temp / 1000.0F;
                            sumOfTemps += tempFloat
                            readings.add(tempFloat);
                        }
                    } catch (e : SecurityException) {
                        Log.d(TAG, "Cannot access thermal zone: $zone")
                        state.value = "N/A";
                    } catch (e : Exception) {
                        state.value = "Error"
                    }
                    index = (index + 1) % zones.size
                }
                val tMean = sumOfTemps / readings.size
                var tVar=0F
                var tMin=0F
                var tMax=0F
                for (temp in readings) {
                    var variance = abs(temp - tMean)
                    variance = variance * variance // Variance²
                    
                    tVar += variance
                    tMin = min(tMin, temp)
                    tMax = max(tMax, temp)
                }
                val stdDev = tVar / readings.size
                state.value = String.format("%.1f°C", tMean)
                if (tMin < tMean - (stdDev * 3))
                {
                    err("tMin greater than 3 standard deviations from the mean.")
                } else if (tMax > tMean +  (stdDev * 3)) {
                    err("tMin greater than 3 standard deviations from the mean.")
                }
                delay(5000) // Update every 5 seconds
            }
        }
    }
}

class GPUZoneSensor(context : CoroutineDispatcher = Dispatchers.IO) : ThermalZoneSensor("", 63 .. 69, context ) {

}