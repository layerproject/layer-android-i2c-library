package com.layer.i2c

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

class GPUZoneSensor(context : CoroutineDispatcher = Dispatchers.IO) : ThermalZoneSensor(0.0f, 63 .. 69, context ) {
    override var valueLabel = "GPU Temperature"
    
    companion object
    {
        const val TAG = "ThermalZoneSensor"
    }
}