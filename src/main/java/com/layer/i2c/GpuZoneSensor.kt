package com.layer.i2c

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

class GPUZoneSensor(context : CoroutineDispatcher = Dispatchers.IO) : ThermalZoneSensor("", 63 .. 69, context ) {
    companion object
    {
        const val TAG = "ThermalZoneSensor"
    }
}