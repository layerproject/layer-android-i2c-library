package com.layer.i2c

import android.annotation.SuppressLint
import android.util.Log
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


open class ThermalZoneSensor(initialValue: String = "", val zoneIds: IntRange, context : CoroutineDispatcher = Dispatchers.IO)  : DeviceNodeSensor<String>(initialValue, context)  {
    companion object {
        const val TAG = "ThermalZoneSensor"
    }
    override var valueLabel = "temperature"
    val zones = zoneIds.map { i -> "/sys/class/thermal/thermal_zone$i/temp" }
    /**
     * Starts monitoring device temperature
     */
    @SuppressLint("DefaultLocale")
    override fun start() : Job {
        val job = CoroutineScope(context).launch {
            fun err(error : String) {
                logError("$valueLabel sensor error: $error")
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
                        err("Cannot access thermal zone: $zone")
                        state.value = "N/A";
                    } catch (e : Exception) {
                        state.value = "Error"
                        Log.e(TAG, "Unexpected Error:", e)
                    }
                    index = (index + 1) % zones.size
                }
                val tMean = sumOfTemps / readings.size
                var tVar=0F
                var tMin=tMean
                var tMax=tMean
                for (temp in readings) {
                    var variance = abs(temp - tMean)
                    variance = variance * variance // Variance²
                    
                    tVar += variance
                    tMin = min(tMin, temp)
                    tMax = max(tMax, temp)
                }
                val stdDev = tVar / readings.size
                state.value = String.format("%.1f°C", tMean)
                if (tMin <= 0 ) {
                    err("Sensor out of range: tMin is <= 0c")
                } else if (tMax > 99) {
                    err("Sensor out of range: tMax is > 99c")
                } else if (stdDev > 0.5 && tMin < tMean - (stdDev * 5))  {
                    Log.w( valueLabel, "tMin($tMin) more than 5 standard deviations(stdDev=$stdDev) below the mean($tMean).")
                } else if (stdDev > 0.5 && tMax > tMean + (stdDev * 5)) {
                    Log.w(valueLabel, "tMax($tMax) more than 5 standard deviations(stdDev=$stdDev) above the mean($tMean).")
                }
                delay(updateFrequencyMS) // Update every 5 seconds
            }
        }
        this.job = job
        return job
    }
}

