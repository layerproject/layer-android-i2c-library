package com.layer.i2c

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import java.io.IOException

/** high level management of a single I2C bus
 * including the IO coroutine to repeatedly read from the bus.
 * Each sensor's latest readings are stored and exposed to the
 * rest of the application.
 */
class I2CSensorBus(val busPath: String) {
    public var allSensors : MutableList<I2CSensor> = mutableListOf()
    var devicePathMap : MutableMap<String, I2CSensor> = mutableMapOf()
    
    var multiplexer : TCA9548Multiplexer? = null
    var lastReconnectTime = 0L
    var lastRescanTime = 0L
    var errorCounter = 0
    var rescanInterval = 10000L
    var updateInterval = 1000L
    
    var busThreadContext: ExecutorCoroutineDispatcher? = null
    
    init {
        busThreadContext = newSingleThreadContext("I2C Bus $busPath")
    }
    
    /** the number of sensors we expect to see, not counting multiplexers.
     * If we find less than this number of sensors then we periodically
     * perform a full bus scan looking for missing sensors.
     */
    var expectedSensorCount = 3
    
    private var reconnectList = mutableListOf<I2CSensor>()
    
    private var ioJob : Job? = null
    
    companion object {
        private const val TAG = "I2CBusManager"
        
        // Singleton instance
        private val port0 = I2CSensorBus("/dev/i2c-0")
        private val port1 = I2CSensorBus("/dev/i2c-1")
        
        // Get the singleton instance
        fun getInstance(port : Int = 0) : I2CSensorBus {
            return if (port == 0) {
                port0
            } else {
                port1
            }
        }
    }
    
    public fun initSensors(devices : MutableList<DeviceInfo>) : MutableList<I2CSensor> {
        val sensors : MutableList<I2CSensor> = mutableListOf()
        devices.forEach { device ->
            val devClass = CommonI2CDevices.getDeviceClass(device.address)
            if (devClass !== TCA9548Multiplexer) {
                val sensor = devClass!!.create(busPath)
                if (sensor !is TCA9548Multiplexer) {
                    if (this.multiplexer != null && device.channel >= 0) {
                        sensor.setMultiplexer(this.multiplexer!!, device.channel)
                    }
                    sensors.add(sensor)
                }
            }
        }
        sensors.forEach { sensor ->
            if (!allSensors.contains(sensor)) {
                try {
                    if (sensor.connect()) {
                        allSensors.add(sensor)
                    }
                } catch (e : Exception) {
                    Log.e(
                        TAG,
                        "Error connecting to ${sensor.getAddress()} on ${this.busPath}: ${e.message}"
                    )
                }
            }
        }
        return sensors
    }
    
    /**
     * Scan a specific I2C port and log comprehensive results
     */
    public fun scanI2CPort() : MutableList<DeviceInfo> {
        val allDevices : MutableList<DeviceInfo> = mutableListOf()
        try {
            // Perform i2cdetect-style scan
            val scanResult = I2CDetect.performI2CDetect(busPath)
            
            if (scanResult.detectedDevices.isNotEmpty()) {
                allDevices.addAll( scanResult.detectedDevices )
                
                // Check for multiplexers and scan their channels
                val multiplexers = scanResult.getDevicesOfType("TCA9548")
                
                multiplexers.forEach { muxDevice ->
                    if (multiplexer != null && multiplexer!!.getAddress() != muxDevice.address) {
                        val oldAddress = multiplexer?.getAddress()
                        val newAddress = muxDevice.address
                        Log.w(TAG, "Multiple multiplexers on $busPath, replacing old address: $oldAddress with new address: $newAddress")
                        multiplexer?.disableAllChannels()
                        tryDisconnectSafely(multiplexer)
                        multiplexer = null
                    }
                    if (multiplexer == null) {
                        multiplexer = TCA9548Multiplexer(busPath, muxDevice.address)
                    }
                }
                if (multiplexer == null) {
                    return allDevices
                }
                if (multiplexer!!.isReady() || multiplexer!!.connect()) {
                    val allChannels = multiplexer!!.scanAllChannels()
                    val muxDevices = allChannels.channelDevices;
                    for (channel in muxDevices.keys) {
                        muxDevices[channel]?.let {
                            allDevices.addAll(it)
                        }
                    }
                }
            }
        } catch (e : Exception) {
            Log.e(TAG, "Failed to scan $busPath: ${e.message}")
        }
        return allDevices
    }
    
    private fun scanForSensors() {

        val devices = this.scanI2CPort()
        allSensors = this.initSensors(devices)
        lastRescanTime = System.currentTimeMillis()
    }
    
    private fun tryDisconnectSafely(sensor : I2CSensor?) {
        try {
            sensor?.disconnect()
        } catch (e : Exception) {
            Log.e(TAG, "Error disconnecting sensor: ${e.message}")
        }
    }
    
    /**
     *  start reading from the i²c bus in a coroutine job.
     */
    fun start() {
        if (ioJob != null) {
            if (ioJob?.isActive == true) {
                throw Exception("I2C Bus ${this.busPath} already has a running I/O job.")
            }
        }
        
        ioJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                
                for (sensor in allSensors) {
                    try {
                        if (!sensor.isReady()) {
                            try {
                                val connected = sensor.connect()
                                if (!connected) {
                                    Log.e(TAG, String.format("Sensor at %s is not connected", sensor.busPath))
                                    reconnectList.add(sensor)
                                }
                            } catch (e : Exception) {
                                reconnectList.add(sensor)
                                errorCounter++
                                Log.e(TAG, "Error reading from sensor: ${e.message}")
                            }
                        }
                        if (sensor.isReady()) {
                            val data = sensor.readData()
                            if (data.isEmpty()) {
                                Log.e(
                                    TAG,
                                    String.format(
                                        "Sensor at %s returned empty data, marking as disconnected",
                                        sensor.busPath
                                    )
                                )
                                errorCounter++
                                reconnectList.add(sensor)
                                tryDisconnectSafely(sensor)
                            } else if (data.containsKey("ERROR")) {
                                Log.e(
                                    TAG,
                                    String.format(
                                        "Sensor at %s returned error, marking as disconnected",
                                        sensor.busPath
                                    )
                                )
                                errorCounter++
                                reconnectList.add(sensor)
                                tryDisconnectSafely(sensor)
                            }
                            
                        }
                    } catch (e : IOException) {
                        Log.e(TAG, "Error reading from sensor0: ${e.message}")
                        // Disconnect to ensure clean reconnection later
                        errorCounter++
                        tryDisconnectSafely(sensor)
                        reconnectList.add(sensor)
                    } catch (e : Exception) {
                        Log.e(TAG, "Unexpected error reading from sensor0: ${e.message}")
                        errorCounter++
                        reconnectList.add(sensor)
                        tryDisconnectSafely(sensor)
                    }
                }
                
                // reconnect any disconnected sensors
                
                
                Log.w(TAG, "Reconnect interval elapsed, attempt to reconnect sensors.")
                val iterator = reconnectList.iterator()
                iterator.forEach { sensor ->
                    try {
                        if (sensor.connect()) {
                            iterator.remove()
                        }
                    } catch (e : Exception) {
                        errorCounter++
                        tryDisconnectSafely(sensor)
                        allSensors.remove(sensor)
                    }
                }
                
                val currentTime = System.currentTimeMillis()
                // rescan the bus for sensors
                if ((currentTime - lastRescanTime > rescanInterval)
                    && (reconnectList.isNotEmpty() || allSensors.size < expectedSensorCount)
                ) {
                    // Attempt to rescan all sensors
                    Log.w(
                        TAG,
                        "Max reconnection attempts exceeded. Resetting all sensors to re-detect and re-connect."
                    )
                    reconnectList.clear()
                    lastRescanTime = currentTime
                    cleanupSensors()
                    scanForSensors()
                }
                
                delay(updateInterval)
            }
        }
    }
    
    /** cancel the running coroutine */
    fun cancel() {
        this.ioJob?.cancel()
        cleanupSensors()
    }
    
    /** Disconnect all devices on this bus and free up resources */
    fun cleanupSensors() {
        errorCounter = 0
        // Clean up attached sensors before multiplexers.
        for (sensor in allSensors) {
            if (sensor !is TCA9548Multiplexer) {
                tryDisconnectSafely(sensor)
            }
        }
        val multiplexer = this.multiplexer
        if (multiplexer != null) {
            // now clean up multiplexers (if any)
            if (multiplexer.connected) {
                multiplexer.connect()
            }
            multiplexer.disableAllChannels()
            tryDisconnectSafely(multiplexer)
            this.multiplexer = null
        }
        
        allSensors.clear()
    }
}
