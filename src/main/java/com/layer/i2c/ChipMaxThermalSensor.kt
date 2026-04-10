package com.layer.i2c

import android.annotation.SuppressLint
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.max

/**
 * Reads all CPU and GPU thermal zones and stores the MAX temperature.
 * Used to detect chip-level thermal throttling for fan control.
 *
 * Zones: cpuss (31-34), cpu cores (35-45, 47-49), gpuss (63-70)
 */
class ChipMaxThermalSensor(context: CoroutineDispatcher = Dispatchers.IO) : DeviceNodeSensor<Float>(0.0f, context) {

    override var valueLabel = "Chip Max Temperature"

    companion object {
        const val TAG = "ChipMaxThermalSensor"

        // CPU subsystem + CPU cores + GPU subsystem zone IDs
        val ZONE_IDS: List<Int> = (31..45).toList() + (47..49).toList() + (63..70).toList()
    }

    private val zones = ZONE_IDS.map { "/sys/class/thermal/thermal_zone$it/temp" }

    @SuppressLint("DefaultLocale")
    override fun start(): Job {
        val job = CoroutineScope(context).launch {
            while (isActive) {
                var maxTemp = Float.NEGATIVE_INFINITY
                var readCount = 0

                for (zone in zones) {
                    try {
                        val file = File(zone)
                        if (file.canRead()) {
                            val temp = file.readText().trim().toInt()
                            val tempCelsius = temp / 1000.0f
                            if (tempCelsius > 0 && tempCelsius <= 150) {
                                maxTemp = max(maxTemp, tempCelsius)
                                readCount++
                            }
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Cannot access thermal zone: $zone")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading thermal zone $zone: ${e.message}")
                    }
                }

                if (readCount > 0) {
                    state.value = maxTemp
                    if (maxTemp > 99) {
                        lastErrorMessage = "Chip max temperature out of range: ${maxTemp}°C"
                        Log.w(TAG, "Chip max temperature: ${maxTemp}°C (from $readCount zones)")
                    }
                } else {
                    state.value = 0.0f
                    lastErrorMessage = "No thermal zones readable"
                    Log.e(TAG, "No thermal zones readable")
                }

                delay(updateFrequencyMS)
            }
        }
        this.job = job
        return job
    }
}
