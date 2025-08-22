package com.layer.i2c

import android.annotation.SuppressLint
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min


open class ThermalZoneSensor(initialValue: String = "", val zoneIds: IntRange, context : CoroutineDispatcher = Dispatchers.IO)  : DeviceNodeSensor<String>(initialValue, context)  {
    companion object {
        const val TAG = "ThermalZoneSensor"
    }
    
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
                delay(updateFrequencyMS) // Update every 5 seconds
            }
        }
    }
}

