package com.layer.i2c

import android.util.Log
import com.layer.hardware.DeviceUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min

open class Expectation(
    open var expected: Any,
    open var instance: I2CSensor? = null
)
/** high level management of a single I2C bus
 * including the IO coroutine to repeatedly read from the bus.
 * Each sensor's latest readings are stored and exposed to the
 * rest of the application.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class I2CSensorBus(val busPath: String) {
    var i2cBusDescriptor : Int = -1
    var multiplexer : TCA9548Multiplexer? = null
    var lastRescanTime = 0L
    var errorCounter = 0
    var rescanInterval = 15000L
    var maxRescanInterval = 150000L
    var updateInterval = 5000L
    var staleStateTimeoutMS = updateInterval * 3
    
    private var reconnectList = mutableListOf<I2CSensor>()
    private var ioJob : Job? = null
    
    companion object {
        var allSensors : MutableSet<I2CSensor> = mutableSetOf()
        private const val TAG = "I2CBusManager"
        private val context = newSingleThreadContext("I2CBusThread")
        // Breathing room between consecutive sensor reads (in milliseconds)
        private const val SENSOR_READ_DELAY_MS = 100L
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
        
        fun initPorts():MutableList<I2CSensorBus> {
            val ports = mutableListOf(
                getInstance(0)
            )
            val serialNumber = DeviceUtils.getSerialNumber()
            // only use port 1 on a select list of early device serial numbers:
            val usesPort1 = listOf("3cc0ef17", "6aceb0a1", "6b52867a", "8b11ea02", "92cd665f", "122ff29e", "715d8296", "b89d02a6", "c1e07642", "e593b0da", "fe5064cd" )
            if (usesPort1.contains(serialNumber)) {
                Log.i(TAG, "Connecting i2c port /dev/i2c-1 because serialnumber ${serialNumber} is in the allow list.")
                ports.add(getInstance(1))
            }
            // these sensors are expected to be present on all layer devices, usually connected to port 0:
            expect(listOf(SHT40Sensor, AS7343Sensor, AS7343Sensor))
            for (port in ports) {
                port.start()
            }
            return ports
        }
        
    }
    
    fun logException(e: Throwable){
        logException(e.message, e)
    }
    fun logException(msg: String?, e: Throwable) {
        Log.e(TAG, msg ?: e.toString(), e)
    }
    
    
    
    suspend fun initSensors(devices : MutableList<DeviceInfo>) : MutableSet<I2CSensor> {
        val sensors : MutableSet<I2CSensor> = mutableSetOf()
        
        withContext(context) {
            devices.forEach { device ->
                val devClass = CommonI2CDevices.getDeviceClass(device.address)
                if (devClass != null && devClass !== TCA9548Multiplexer) {
                    val sensor = devClass.create(busPath)
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
                        logException(e)
                    }
                }
            }

        }
        return sensors
    }
    
    /**
     * Scan a specific I2C port and log comprehensive results
     */
    suspend fun scanI2CPort() : MutableList<DeviceInfo> {
        val allDevices : MutableList<DeviceInfo> = mutableListOf()
        val uniqueDeviceIds : MutableSet<String> = mutableSetOf()
        withContext(context) {
            try {
                // Perform i2cdetect-style scan
                val scanResult = I2CDetect.performI2CDetect(busPath)
                
                if (scanResult.detectedDevices.isNotEmpty()) {
                    for (dev in scanResult.detectedDevices) {
                        // Record and deduplicate the detected devices
                        // use * for devices no on any multiplexer channel
                        val devId = "$busPath:*:${dev.address}"
                        if (!uniqueDeviceIds.contains(devId)) {
                            allDevices.add(dev)
                            uniqueDeviceIds.add(devId)
                        }
                    }
                    
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
                        val muxDevices = allChannels.channelDevices
                        for (channel in muxDevices.keys) {
                            muxDevices[channel]?.let {
                                for (dev in it) {
                                    // The multiplexer allows us to turn off certain devices while scanning, howver,
                                    // devices that are not on a multiplexer channel will still show up in the
                                    // multiplexer scan, so we need to deduplicate.  We only allow one device per
                                    // address and per channel. Devices with * as their channel will block
                                    // the same device address with any channel. In order to have more than one
                                    // device of the same address, all of them must be on a multiplexer channel.
                                    if (!uniqueDeviceIds.contains("$busPath:*:${dev.address}")){
                                        val channelDevId = "$busPath:${dev.channel}:${dev.address}"
                                        if (!uniqueDeviceIds.contains(channelDevId)) {
                                            allDevices.add(dev)
                                            uniqueDeviceIds.add(channelDevId)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e : Exception) {
                logException( "Failed to scan $busPath: ${e.message}", e)
            }
        }
        return allDevices
    }
    
    private suspend fun scanForSensors() {
        withContext(context) {
            val devices = scanI2CPort()
            if (devices.isEmpty()) {
                rescanInterval =  min(maxRescanInterval, (rescanInterval * 1.1).toLong())
            }
            initSensors(devices)
            lastRescanTime = System.currentTimeMillis()

        }
    }
    
    private fun tryDisconnectSafely(sensor : I2CSensor?) {
        try {
            sensor?.disconnect()
        } catch (e : Exception) {
            logException("Error disconnecting sensor: ${e.message}", e)
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
            // Set absolute lowest scheduling priority — SCHED_IDLE threads only run
            // when no other thread on the system wants CPU time
            val schedResult = I2cNative.setSchedIdle()
            if (schedResult < 0) {
                // Fallback to nice-based deprioritization if SCHED_IDLE not available
                Log.w(TAG, "SCHED_IDLE not available, falling back to THREAD_PRIORITY_LOWEST")
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LOWEST)
            } else {
                Log.i(TAG, "I2C thread set to SCHED_IDLE scheduling policy")
            }
            scanForSensors()
            // update interval is divided between the delay at the end of the for loop and
            // another delay at the end of the while loop
            
            var currentTime: Long
            try {
                while (isActive) {
                    val waitTime =  if (allSensors.isEmpty())
                        updateInterval * 2
                    else
                        updateInterval / (allSensors.size+2)
                    
                    currentTime = System.currentTimeMillis()
                    val it = latestSensorState.iterator()
                    for (state in it) {
                        if (currentTime - state.value.updateTS > staleStateTimeoutMS) {
                            it.remove()
                        }
                    }
                    
                    for (sensor in allSensors.toList()) {
                        try {
                            if (!sensor.isReady()) {
                                try {
                                    val connected = sensor.connect()
                                    if (!connected) {
                                        Log.e(TAG, String.format("Sensor at %s is not connected", sensor.busPath))
                                        reconnectList.add(sensor)
                                    }
                                } catch (e : Exception) {
                                    logException("Error connecting sensor: ${e.message}", e)
                                    reconnectList.add(sensor)
                                    errorCounter++
                                }
                            }
                            if (sensor.isReady()) {
                                // Skip this sensor if its minimum read interval hasn't elapsed
                                if (sensor.minReadIntervalMs > 0) {
                                    val elapsed = currentTime - sensor.lastReadTime
                                    if (sensor.lastReadTime > 0 && elapsed < sensor.minReadIntervalMs) {
                                        continue
                                    }
                                }
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
                                    Log.d(TAG, "Sensor $sensor returned data: $data")
                                    val sensorId = sensor.deviceUniqueId()
                                    latestSensorState[sensorId] = sensor.getSensorState()
                                }
                                delay(SENSOR_READ_DELAY_MS)  // Delay after successful sensor read, before any other I2C operations
                            }
                        } catch (e : IOException) {
                            Log.e(TAG, "Error reading from sensor $sensor: ${e.message}")
                            // Disconnect to ensure clean reconnection later
                            errorCounter++
                            reconnectList.add(sensor)
                            delay(SENSOR_READ_DELAY_MS)  // Delay after I/O error
                        } catch(e: CancellationException) {
                            Log.i(TAG, "Coroutine canceled.", e)
                            throw e
                        } catch (e : Exception) {
                            logException("Unexpected error reading from sensor0: ${e.message}", e)
                            errorCounter++
                            reconnectList.add(sensor)
                            delay(SENSOR_READ_DELAY_MS)  // Delay after unexpected error
                        }
                        delay(waitTime)
                    }
                    
                    // reconnect any disconnected sensors
                    val iterator = reconnectList.iterator()
                    iterator.forEach { sensor ->
                        try {
                            tryDisconnectSafely(sensor)
                            if (sensor.connect()) {
                                Log.i(TAG, "Reconnected: $sensor.")
                                iterator.remove()
                            } else {
                                Log.e(TAG, "Reconnect failed. Will continue trying.")
                                errorCounter++
                            }
                        } catch (e : IOException) {
                            logException("Reconnect failed $sensor due to ${e.message}", e)
                            errorCounter++
                            tryDisconnectSafely(sensor)
                            Log.i(TAG, "Removing $sensor from active polling.")
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
                        lastRescanTime = currentTime
                        cleanupSensors()
                        scanForSensors()
                    }
                    delay(SENSOR_READ_DELAY_MS)
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
        try {
            errorCounter = 0
            // Clean up attached sensors before multiplexers.
            for (sensor in allSensors.toList()) {
                if (sensor !is TCA9548Multiplexer) {
                    tryDisconnectSafely(sensor)
                }
            }
            val multiplexer = this.multiplexer
            if (multiplexer != null) {
                try {
                    // now clean up multiplexers (if any)
                    multiplexer.connect()
                    multiplexer.disableAllChannels()
                } catch(e:Exception) {
                    logException(e)
                }
                tryDisconnectSafely(multiplexer)
            }
        } finally {
            this.multiplexer = null
            mappedSensors.clear()
            allSensors.clear()
            reconnectList.clear()
            //make sure the bus is closed:
            I2CBusManager.getInstance().closeBus(busPath, 112)
        }
    }
}
