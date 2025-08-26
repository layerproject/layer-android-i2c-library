package com.layer.i2c

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

public open class Expectation(
    public open var expected: Any,
    public open var instance: I2CSensor? = null
) {}
/** high level management of a single I2C bus
 * including the IO coroutine to repeatedly read from the bus.
 * Each sensor's latest readings are stored and exposed to the
 * rest of the application.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class I2CSensorBus(val busPath: String) {
    var allSensors : MutableSet<I2CSensor> = mutableSetOf()
    var devicePathMap : MutableMap<String, I2CSensor> = mutableMapOf()
    
    var multiplexer : TCA9548Multiplexer? = null
    var lastReconnectTime = 0L
    var lastRescanTime = 0L
    var errorCounter = 0
    var rescanInterval = 10000L
    var staleStateTimeoutMS = rescanInterval * 1.5
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
        private val context = newSingleThreadContext("I2CBusThread")
        // Singleton instance
        private val port0 = I2CSensorBus("/dev/i2c-0")
        private val port1 = I2CSensorBus("/dev/i2c-1")
        
        private var expectedSensors :  List<Expectation> = listOf()
        private var mappedSensors : MutableMap<I2CSensor,  Expectation> = mutableMapOf()
        private var latestSensorState = HashMap<String, SensorState>()
        
        fun expect(sensors : List<Any>) {
            expectedSensors = sensors.map { sensor ->
                val name = sensor.javaClass.name.split("$")[0]
                Expectation(name) }
        }
        
        // Get the singleton instance
        fun getInstance(port : Int = 0) : I2CSensorBus {
            return if (port == 0) {
                port0
            } else {
                port1
            }
        }
        
        fun getAllSensorState() : Map<String, SensorState> {
            return latestSensorState.toMap()
        }
        
        fun getSensorState(sensorId : String) : SensorState? {
            return latestSensorState[sensorId]
        }
    }
    
    public suspend fun initSensors(devices : MutableList<DeviceInfo>) : MutableSet<I2CSensor> {
        val sensors : MutableSet<I2CSensor> = mutableSetOf()
        
        withContext(context) {
            devices.forEach { device ->
                val devClass = CommonI2CDevices.getDeviceClass(device.address)
                if (devClass !== TCA9548Multiplexer) {
                    val sensor = devClass!!.create(busPath)
                    if (sensor !is TCA9548Multiplexer) {
                        if (multiplexer != null && device.channel >= 0) {
                            sensor.setMultiplexer(multiplexer!!, device.channel)
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
                            for (expected in expectedSensors) {
                                if (expected.expected == sensor.javaClass.name && expected.instance == null) {
                                    expected.instance = sensor
                                    mappedSensors[sensor] = expected
                                    break
                                }
                            }
                        }
                    } catch (e : Exception) {
                        Log.e(
                            TAG,
                            "Error connecting to ${sensor.getAddress()} on ${busPath}: ${e.message}"
                        )
                    }
                }
            }

        }
        return sensors
    }
    
    /**
     * Scan a specific I2C port and log comprehensive results
     */
    public suspend fun scanI2CPort() : MutableList<DeviceInfo> {
        val allDevices : MutableList<DeviceInfo> = mutableListOf()
        
        withContext(context) {
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
                    if ( multiplexer!!.isReady() || multiplexer!!.connect()) {
                        val allChannels = multiplexer!!.scanAllChannels()
                        val muxDevices = allChannels.channelDevices;
                        for (channel in muxDevices.keys) {
                            muxDevices[channel]?.let {
                                allDevices.addAll(it)
                            }
                        }
                        allDevices
                    } else {
                        allDevices
                    }
                } else {
                    allDevices
                }
            } catch (e : Exception) {
                Log.e(TAG, "Failed to scan $busPath: ${e.message}")
            }
        }
        return allDevices
    }
    
    private suspend fun scanForSensors() {
        withContext(context) {
            val devices = scanI2CPort()
            initSensors(devices)
            lastRescanTime = System.currentTimeMillis()
        }
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

        ioJob = CoroutineScope(context).launch {
            scanForSensors()
            // update interval is divided between the delay at the end of the for loop and
            // another delay at the end of the while loop
            val waitTime =  updateInterval / (allSensors.size+2)
            var currentTime: Long
            try {
                while (isActive) {
                    currentTime = System.currentTimeMillis()
                    val it = latestSensorState.iterator()
                    for (state in it) {
                        if (currentTime - state.value.updateTS > staleStateTimeoutMS) {
                            it.remove()
                        }
                    }
                    
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
                                    Log.e(TAG, "Error connecting sensor: ${e.message}")
                                }
                            }
                            if (sensor.isReady()) {
                                val data = sensor.readData()
                                if (data.isEmpty()) {
                                    Log.e(TAG, "Sensor $sensor returned empty data. Marking sensor as disconnected")
                                    errorCounter++
                                    reconnectList.add(sensor)
                                    
                                } else if (data.containsKey("ERROR")) {
                                    Log.e(
                                        TAG,
                                        "Sensor $sensor returned error: ${data["ERROR"]}. Marking sensor as disconnected"
                                    )
                                    errorCounter++
                                    reconnectList.add(sensor)
                                    
                                } else {
                                    val sensorId = sensor.deviceUniqueId()
                                    latestSensorState[sensorId] = sensor.getSensorState()
                                    Log.d(TAG, "Sensor $sensor returned data: $data")
                                }
                                
                            }
                        } catch (e : IOException) {
                            Log.e(TAG, "Error reading from sensor $sensor: ${e.message}")
                            // Disconnect to ensure clean reconnection later
                            errorCounter++
                            reconnectList.add(sensor)
                        } catch(e: CancellationException) {
                            Log.e(TAG, "Coroutine canceled.", e)
                            throw e
                        } catch (e : Exception) {
                            Log.e(TAG, "Unexpected error reading from sensor0: ${e.message}")
                            errorCounter++
                            reconnectList.add(sensor)
                        }
                        delay(waitTime)
                    }
                    
                    // reconnect any disconnected sensors
                    
                    val iterator = reconnectList.iterator()
                    iterator.forEach { sensor ->
                        try {
                            tryDisconnectSafely(sensor)
                            if (sensor.connect()) {
                                Log.w(TAG, "Reconnected: $sensor.")
                                iterator.remove()
                            }
                        } catch (e : IOException) {
                            Log.w(TAG, "Reconnect failed $sensor due to ${e.message}")
                            errorCounter++
                            tryDisconnectSafely(sensor)
                            Log.w(TAG, "Removing $sensor from active polling.")
                            // Remove the sensor from active polling but keep it in the reconnect
                            allSensors.remove(sensor)
                            latestSensorState.remove(sensor.deviceUniqueId())
                        }
                    }
                    
                    val currentTime = System.currentTimeMillis()
                    // rescan the bus for sensors only after rescanInterval
                    // but only when some sensors are disconnected or
                    // less than the expected number of sensors are found.
                    if ((currentTime - lastRescanTime > rescanInterval)
                        && (reconnectList.isNotEmpty() || allSensors.size < expectedSensors.size)
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
                    delay(waitTime)
                }
            } finally {
                cleanupSensors()
            }
        }
    }
    
    /** cancel the running coroutine */
    fun cancel() {
        this.ioJob?.cancel()
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
