package com.layer.i2c

import android.util.Log
import java.io.IOException


interface OnDataReceivedListener {
    fun onDataReceived(sensor: I2CSensor, channelData:  Map<String, Any>)
}


/**
 * Base class for all I2C sensors.
 * Provides common I2C communication methods and management.
 */
abstract class I2CSensor(
    val busPath: String,
    private var multiplexer: TCA9548Multiplexer? = null,
    private var multiplexerChannel: Int? = null,
) {
    companion object {
        protected const val TAG = "I2CSensor"
        
        // Map to track the current device address for each file descriptor
        private val currentDeviceMap = HashMap<Int, Int>()
        
        // Set the current device for a file descriptor
        @Synchronized
        fun setCurrentDevice(fd: Int, deviceAddress: Int) {
            currentDeviceMap[fd] = deviceAddress
        }
        
        // Get the current device for a file descriptor
        @Synchronized
        fun getCurrentDevice(fd: Int): Int {
            return currentDeviceMap[fd] ?: -1
        }
        
        // Clear the device mapping for a file descriptor
        @Synchronized
        fun clearDeviceMapping(fd: Int) {
            currentDeviceMap.remove(fd)
        }
    }
    
    // Reference to the bus manager
    private val busManager = I2CBusManager.getInstance()
    
    protected var fileDescriptor: Int = -1
    protected var isInitialized: Boolean = false
    protected var isBusOpen: Boolean = false
    
    // Lock object for file descriptor-level synchronization
    protected var fdLock: Any? = null
    
    // Flag to prevent recursive recovery attempts
    @Volatile
    private var isRecovering = false
    
    // Each sensor type must specify its own I2C address
    protected abstract val sensorAddress: Int
    
    private var listeners: MutableList<OnDataReceivedListener> = mutableListOf()
    
    public fun addListener(listener: OnDataReceivedListener): Boolean {
        return listeners.add(listener)
    }
    
    public fun removeListener(listener: OnDataReceivedListener): Boolean {
        return listeners.remove(listener)
    }
    
    public fun clearListeners() {
        listeners.clear()
    }
    
    protected fun notifyListeners(data: Map<String, Any>) : Map<String, Any> {
        for (l in listeners) {
            l.onDataReceived(this, data)
        }
        return data
    }
    
    /**
     * A string that uniquely identifies an i2c devices by it's busPath, multiplexer channel and i2c saddress.
     */
    open fun deviceUniqueId(): String {
        val address = getAddress()
        val deviceId = if (isMultiplexed()) {
            val channel = getSensorMultiplexerChannel()
            "$busPath:$channel:$address"
        } else {
            "$busPath:*:$address"
        }
        return deviceId;
    }
    
    fun setMultiplexer(multiplexer: TCA9548Multiplexer, channel: Int) {
        this.multiplexer = multiplexer
        this.multiplexerChannel = channel
    }
    
    override fun toString(): String {
        val type = this.javaClass.simpleName
        val connected = if (this.connected) "Connected" else "Disconnected"
        val sensorInfo = if (isMultiplexed()) {
            val channel = getSensorMultiplexerChannel()
            "$type ($busPath Multiplexed Ch$channel) $connected"
        } else {
            "$type ($busPath) - $connected"
        }
        return sensorInfo
    }
    
    
    public fun getAddress(): Int = sensorAddress
    
    /**
     * Get the multiplexer channel this sensor is connected to.
     * @return The multiplexer channel (0-7) or null if directly connected
     */
    fun getSensorMultiplexerChannel(): Int? = multiplexerChannel
    
    /**
     * Get the multiplexer this sensor is connected through.
     * @return The TCA9548Multiplexer instance or null if directly connected
     */
    fun getSensorMultiplexer(): TCA9548Multiplexer? = multiplexer
    
    /**
     * Check if this sensor is connected through a multiplexer.
     * @return true if using a multiplexer, false if directly connected
     */
    fun isMultiplexed(): Boolean = multiplexer != null && multiplexerChannel != null
    
    // Validate multiplexer configuration
    init {
        if (multiplexer != null && multiplexerChannel == null) {
            throw IllegalArgumentException("Multiplexer channel must be specified when using a multiplexer")
        }
        if (multiplexer == null && multiplexerChannel != null) {
            throw IllegalArgumentException("Multiplexer must be specified when using a multiplexer channel")
        }
        if (multiplexer != null && multiplexerChannel != null) {
            if (multiplexerChannel!! < 0 || multiplexerChannel!! >= multiplexer!!.maxChannels) {
                throw IllegalArgumentException("Multiplexer channel $multiplexerChannel is out of range (0-${multiplexer!!.maxChannels - 1})")
            }
        }
    }
    
    /**
     * Sensor-specific initialization after connection is established
     * @return true if initialization was successful, false otherwise
     */
    protected abstract fun initializeSensor(): Boolean
    
    public abstract fun readDataImpl(): Map<String, Any>
    public fun readData(): Map<String, Any> {
        return notifyListeners(readDataImpl())
    }
    
    public open fun getSensorState() = object : SensorState {
        override val connected = isConnected()
        override val updateTS = System.currentTimeMillis()
        override val sensorId = this@I2CSensor.toString()
    }
    
    /**
     * Checks if the sensor is connected *and* initialized.
     * @return true if connected and initialized, false otherwise
     */
    @Synchronized
    fun isReady(): Boolean {
        // First, check if our bus is still open (could have been closed by another sensor)
        if (isBusOpen && fileDescriptor >= 0) {
            val effectiveBusPath = getEffectiveBusPath()
            val currentFd = busManager.getBusFd(effectiveBusPath)
            if (currentFd != fileDescriptor) {
                // Bus was closed/reopened by another component
                isBusOpen = false
                fileDescriptor = -1
                isInitialized = false
            }
        }
        
        connected = isInitialized && isBusOpen && fileDescriptor >= 0
        return connected
    }
    
    /**
     * Gets the effective bus path for this sensor, including multiplexer channel if applicable.
     * This ensures multiplexed sensors have unique identities.
     * @return Bus path with channel info for multiplexed sensors, plain path for direct connections
     */
    public fun getEffectiveBusPath(): String {
        return if (multiplexer != null && multiplexerChannel != null) {
            "${busPath}:ch${multiplexerChannel}"
        } else {
            busPath
        }
    }
    
    /**
     * Opens an I2C connection to the sensor on the specified bus.
     * @return File descriptor if successful, -1 if failed
     */
    private fun openDevice(): Boolean {
        val effectiveBusPath = getEffectiveBusPath()
        val fd = busManager.openBus(effectiveBusPath, sensorAddress)
        if (fd < 0) {
            Log.e(
                TAG,
                "Failed to open I2C bus $effectiveBusPath for address 0x${sensorAddress.toString(16)}"
            )
            isBusOpen = false
            return false
        } else {
            Log.d(
                TAG,
                "Successfully opened I2C bus $effectiveBusPath for address 0x${
                    sensorAddress.toString(16)
                }"
            )
            isBusOpen = true
            fileDescriptor = fd
            
            // Get the shared lock object for this file descriptor
            fdLock = busManager.getFdLock(fd)
            
            return true
        }
    }
    
    /**
     * Closes an I2C connection.
     */
    private fun closeDevice() {
        if (isBusOpen) {
            val fd = fileDescriptor
            val effectiveBusPath = getEffectiveBusPath()
            
            // Clean up any locks before closing
            cleanUpFdResources(fd)
            
            busManager.closeBus(effectiveBusPath, sensorAddress)
            isBusOpen = false
        }
        connected = false
        clearListeners()
    }
    
    
    open var connected: Boolean = false
    open fun isConnected(): Boolean {
        if (multiplexer != null && multiplexer?.isConnected() == false) {
            connected = false
        }
        if (isInitialized && isBusOpen) {
            connected = true
        }
        return connected
    }
    
    /**
     * Opens a connection to the sensor and initializes it.
     * @return true if connection and initialization were successful, false otherwise
     */
    @Synchronized
    open fun connect(): Boolean {
        if (isInitialized && isBusOpen) {
            Log.d(
                TAG,
                "Sensor on $busPath for address 0x${sensorAddress.toString(16)} already connected and initialized."
            )
            connected = true
            return true
        }
        
        // Already open but not initialized? Close first.
        if (isBusOpen) {
            Log.w(TAG, "Sensor fd already open but not initialized. Re-opening.")
            closeDevice()
            fileDescriptor = -1
        }
        
        val effectiveBusPath = getEffectiveBusPath()
        Log.d(
            TAG,
            "Connecting to sensor on $effectiveBusPath for address 0x${sensorAddress.toString(16)}..."
        )
        
        // If using a multiplexer, connect to it first
        if (multiplexer != null) {
            if (multiplexer?.isReady() == false) {
                Log.d(TAG, "Multiplexer not ready, attempting to connect...")
                if (multiplexer?.connect() == false) {
                    Log.e(TAG,"Failed to connect to multiplexer for sensor 0x${sensorAddress.toString(16)}")
                    connected = false
                    return false
                }
            }
            Log.d(TAG, "Multiplexer ready for sensor 0x${sensorAddress.toString(16)}")
        }
        
        // Check if this address is already in use on this bus
        if (busManager.isAddressInUse(effectiveBusPath, sensorAddress)) {
            Log.e(TAG, "Address 0x${sensorAddress.toString(16)} already in use on bus $effectiveBusPath")
            connected = false
            return false
        }
        
        if (!openDevice()) {
            Log.e(
                TAG,
                "Failed to open sensor on $effectiveBusPath for address 0x${
                    sensorAddress.toString(16)
                }."
            )
            isInitialized = false
            isBusOpen = false
            connected = false
            return false
        }
        
        Log.d(
            TAG,
            "Sensor opened (fd=$fileDescriptor) for address 0x${sensorAddress.toString(16)}. Initializing..."
        )
        isInitialized = initializeSensor()
        
        if (!isInitialized) {
            Log.e(
                TAG,
                "Failed to initialize sensor on $effectiveBusPath for address 0x${
                    sensorAddress.toString(16)
                } (fd=$fileDescriptor). Closing."
            )
            closeDevice()
            fileDescriptor = -1
            isBusOpen = false
            connected = false
            return false
        }
        
        Log.i(TAG, "Sensor on $effectiveBusPath connected and initialized successfully.")
        connected = true
        return true
    }
    
    /**
     * Closes the connection to the sensor.
     */
    @Synchronized
    open fun disconnect() {
        if (isBusOpen) {
            val effectiveBusPath = getEffectiveBusPath()
            Log.d(
                TAG,
                "Disconnecting sensor on $effectiveBusPath for address $sensorAddress (fd=$fileDescriptor)..."
            )
            closeDevice()
            fileDescriptor = -1
            isBusOpen = false
            isInitialized = false
            fdLock = null
            Log.i(TAG, "Sensor on $effectiveBusPath disconnected.")
        } else {
            val effectiveBusPath = getEffectiveBusPath()
            Log.d(TAG, "Sensor on $effectiveBusPath already disconnected.")
        }
        connected = false
    }
    
    /**
     * Switches the I2C bus to this device's address only if needed.
     * This must be called before any I/O operations when multiple devices
     * share the same I2C bus/file descriptor.
     * If a multiplexer is configured, it also switches to the correct channel.
     *
     * @return true if the switch was successful, false otherwise
     */
    protected fun switchToDevice(): Boolean {
        if (fileDescriptor < 0) {
            Log.e(TAG, "Invalid file descriptor: $fileDescriptor")
            return false
        }
        
        // Use the shared lock for file descriptor level synchronization
        val lock = fdLock ?: this
        synchronized(lock) {
            // If using a multiplexer, ensure the correct channel is selected first
            if (multiplexer != null && multiplexerChannel != null) {
                if (!multiplexer!!.isReady()) {
                    Log.e(
                        TAG,
                        "Multiplexer not ready for sensor at address 0x${sensorAddress.toString(16)}"
                    )
                    return false
                }
                
                // Switch to the multiplexer and select our channel
                if (!multiplexer!!.isChannelEnabled(multiplexerChannel!!)) {
                    try {
                        multiplexer!!.selectChannel(multiplexerChannel!!)
                        Log.d(
                            TAG,
                            "Selected multiplexer channel $multiplexerChannel for sensor 0x${
                                sensorAddress.toString(16)
                            }"
                        )
                    } catch (e: Exception) {
                        Log.e(
                            TAG,
                            "Failed to select multiplexer channel $multiplexerChannel: ${e.message}"
                        )
                        return false
                    }
                }
            }
            
            // Check if we're already addressing the correct device
            val currentDevice = getCurrentDevice(fileDescriptor)
            if (currentDevice == sensorAddress) {
                // Already switched to this device, no need to switch again
                return true
            }
            
            // We need to switch device
            val result = I2cNative.switchDeviceAddress(fileDescriptor, sensorAddress)
            if (result < 0) {
                Log.e(
                    TAG,
                    "Failed to switch to device address 0x${sensorAddress.toString(16)} on fd=$fileDescriptor"
                )
                return false
            }
            
            // Update our tracking of the current device
            setCurrentDevice(fileDescriptor, sensorAddress)
            return true
        }
    }
    
    // --- I2C Primitive Helpers ---
    
    /**
     * Direct I2C read that bypasses recovery mechanisms.
     * Use this method within recovery functions to avoid infinite loops.
     */
    protected fun readByteRegDirect(register: Int): Int {
        if (fileDescriptor < 0) {
            throw IOException("Invalid file descriptor")
        }
        
        // Use the shared lock for file descriptor level synchronization
        val lock = fdLock ?: this
        synchronized(lock) {
            // Switch to this device before performing I/O
            if (!switchToDevice()) {
                throw IOException("Failed to switch to device 0x${sensorAddress.toString(16)}")
            }
            
            val result = I2cNative.readWord(fileDescriptor, register)
            if (result < 0) {
                val errorMessage =
                    "I2C Read Error on fd=$fileDescriptor, reg=0x${register.toString(16)}, code=$result"
                Log.e(TAG, errorMessage)
                throw IOException(errorMessage)
            }
            return result and 0xFF
        }
    }
    
    protected fun readByteReg(register: Int): Int {
        if (fileDescriptor < 0) {
            throw IOException("Invalid file descriptor")
        }
        
        // Use the shared lock for file descriptor level synchronization
        val lock = fdLock ?: this
        synchronized(lock) {
            // Switch to this device before performing I/O
            if (!switchToDevice()) {
                throw IOException("Failed to switch to device 0x${sensorAddress.toString(16)}")
            }
            
            val result = I2cNative.readWord(fileDescriptor, register)
            if (result < 0) {
                val errorMessage =
                    "I2C Read Error on fd=$fileDescriptor, reg=0x${register.toString(16)}, code=$result"
                Log.e(TAG, errorMessage)
                
                // If this sensor supports recovery (spectral sensors) and we're not already in recovery, attempt it
                if (this is AS7343Sensor && !isRecovering) {
                    Log.w(
                        TAG,
                        "Attempting sensor recovery due to I2C read error on fd=$fileDescriptor"
                    )
                    try {
                        isRecovering = true
                        val recovered = this.recoverSensor()
                        if (recovered) {
                            Log.i(
                                TAG,
                                "Sensor recovery successful, retrying read operation on fd=$fileDescriptor"
                            )
                            // Retry the read operation once after successful recovery
                            if (!switchToDevice()) {
                                throw IOException(
                                    "Failed to switch to device after recovery 0x${
                                        sensorAddress.toString(
                                            16
                                        )
                                    }"
                                )
                            }
                            val retryResult = I2cNative.readWord(fileDescriptor, register)
                            if (retryResult >= 0) {
                                Log.i(
                                    TAG,
                                    "Read operation successful after recovery on fd=$fileDescriptor"
                                )
                                return retryResult and 0xFF
                            } else {
                                Log.e(
                                    TAG,
                                    "Read operation still failed after recovery on fd=$fileDescriptor"
                                )
                            }
                        } else {
                            Log.e(TAG, "Sensor recovery failed on fd=$fileDescriptor")
                        }
                    } catch (recoveryException: Exception) {
                        Log.e(
                            TAG,
                            "Exception during sensor recovery on fd=$fileDescriptor: ${recoveryException.message}"
                        )
                    } finally {
                        isRecovering = false
                    }
                }
                
                throw IOException(errorMessage)
            }
            return result and 0xFF
        }
    }
    
    protected fun writeByteReg(register: Int, value: Int) {
        if (fileDescriptor < 0) {
            throw IOException("Invalid file descriptor")
        }
        
        // Use the shared lock for file descriptor level synchronization
        val lock = fdLock ?: this
        synchronized(lock) {
            // Switch to this device before performing I/O
            if (!switchToDevice()) {
                throw IOException("Failed to switch to device 0x${sensorAddress.toString(16)}")
            }
            
            val result = I2cNative.writeByte(fileDescriptor, register, value)
            if (result < 0) {
                throw IOException(
                    "I2C Write Error on fd=$fileDescriptor, reg=0x${
                        register.toString(
                            16
                        )
                    }, value=0x${value.toString(16)}, code=$result"
                )
            }
        }
    }
    
    protected fun writeWordReg(lsbRegister: Int, value: Int) {
        if (fileDescriptor < 0) {
            throw IOException("Invalid file descriptor")
        }
        
        // Use the shared lock for file descriptor level synchronization
        val lock = fdLock ?: this
        synchronized(lock) {
            // Switch to this device before performing I/O
            if (!switchToDevice()) {
                throw IOException("Failed to switch to device 0x${sensorAddress.toString(16)}")
            }
            
            val lsb = value and 0xFF
            val msb = (value shr 8) and 0xFF
            
            // Write LSB first, then MSB - no need to switch device again
            val result1 = I2cNative.writeByte(fileDescriptor, lsbRegister, lsb)
            if (result1 < 0) {
                throw IOException(
                    "I2C Write LSB Error on fd=$fileDescriptor, reg=0x${
                        lsbRegister.toString(
                            16
                        )
                    }, value=0x${lsb.toString(16)}, code=$result1"
                )
            }
            
            val result2 = I2cNative.writeByte(fileDescriptor, lsbRegister + 1, msb)
            if (result2 < 0) {
                throw IOException(
                    "I2C Write MSB Error on fd=$fileDescriptor, reg=0x${
                        (lsbRegister + 1).toString(
                            16
                        )
                    }, value=0x${msb.toString(16)}, code=$result2"
                )
            }
        }
    }
    
    protected fun readDataBlock(data: ByteArray, length: Int): Int {
        if (!isReady()) {
            throw IOException("Not connected to I2C device")
        }
        
        // Use the shared lock for file descriptor level synchronization
        val lock = fdLock ?: this
        synchronized(lock) {
            // Switch to this device before performing I/O
            if (!switchToDevice()) {
                throw IOException("Failed to switch to device 0x${sensorAddress.toString(16)}")
            }
            
            return I2cNative.readRawBytes(fileDescriptor, data, length)
        }
    }
    
    /**
     * Direct bit enable that bypasses recovery mechanisms.
     * Use this method within recovery functions to avoid infinite loops.
     */
    protected fun enableBitDirect(register: Int, bit: Int, on: Boolean) {
        if (fileDescriptor < 0) {
            throw IOException("I2C device not connected: invalid file descriptor")
        }
        
        if (!isBusOpen) {
            throw IOException("I2C device not connected: bus not open")
        }
        
        if (!isInitialized) {
            Log.d(
                TAG,
                "enableBitDirect called during initialization (fd=$fileDescriptor, reg=0x${
                    register.toString(16)
                }, bit=$bit)"
            )
        }
        
        // Use the shared lock for file descriptor level synchronization
        val lock = fdLock ?: this
        synchronized(lock) {
            // Switch to this device before performing I/O
            if (!switchToDevice()) {
                throw IOException("Failed to switch to device 0x${sensorAddress.toString(16)}")
            }
            
            // Read current value using direct method (already performs device switching)
            val regValue = readByteRegDirect(register)
            val bitMask = (1 shl bit)
            val newValue = if (on) regValue or bitMask else regValue and bitMask.inv()
            
            if (newValue != regValue) {
                val result = I2cNative.writeByte(fileDescriptor, register, newValue)
                if (result < 0) {
                    val errorMessage =
                        "I2C Write Error on fd=$fileDescriptor, reg=0x${register.toString(16)}, value=0x${
                            newValue.toString(16)
                        }, code=$result"
                    Log.e(TAG, errorMessage)
                    throw IOException(errorMessage)
                }
            }
        }
    }
    
    protected fun enableBit(register: Int, bit: Int, on: Boolean) {
        if (fileDescriptor < 0) {
            throw IOException("I2C device not connected: invalid file descriptor")
        }
        
        if (!isBusOpen) {
            throw IOException("I2C device not connected: bus not open")
        }
        
        if (!isInitialized) {
            Log.d(
                TAG,
                "enableBit called during initialization (fd=$fileDescriptor, reg=0x${
                    register.toString(16)
                }, bit=$bit)"
            )
        }
        
        // Use the shared lock for file descriptor level synchronization
        val lock = fdLock ?: this
        synchronized(lock) {
            // Switch to this device before performing I/O
            if (!switchToDevice()) {
                throw IOException("Failed to switch to device 0x${sensorAddress.toString(16)}")
            }
            
            // Read current value (already performs device switching)
            val regValue = readByteReg(register)
            val bitMask = (1 shl bit)
            val newValue = if (on) regValue or bitMask else regValue and bitMask.inv()
            
            if (newValue != regValue) {
                val result = I2cNative.writeByte(fileDescriptor, register, newValue)
                if (result < 0) {
                    throw IOException(
                        "I2C Write Error on fd=$fileDescriptor, reg=0x${
                            register.toString(
                                16
                            )
                        }, value=0x${newValue.toString(16)}, code=$result"
                    )
                }
            }
        }
    }
    
    protected fun setRegisterBits(register: Int, shift: Int, width: Int, value: Int) {
        if (fileDescriptor < 0) {
            throw IOException("Invalid file descriptor")
        }
        
        // Use the shared lock for file descriptor level synchronization
        val lock = fdLock ?: this
        synchronized(lock) {
            // Switch to this device before performing I/O
            if (!switchToDevice()) {
                throw IOException("Failed to switch to device 0x${sensorAddress.toString(16)}")
            }
            
            // Read current value
            val regValue = readByteReg(register)
            val mask = ((1 shl width) - 1) shl shift
            val newValue = (regValue and mask.inv()) or ((value shl shift) and mask)
            
            val result = I2cNative.writeByte(fileDescriptor, register, newValue)
            if (result < 0) {
                throw IOException(
                    "I2C Write Error on fd=$fileDescriptor, reg=0x${
                        register.toString(
                            16
                        )
                    }, value=0x${newValue.toString(16)}, code=$result"
                )
            }
        }
    }
    
    /**
     * Called when disconnecting or closing the device.
     * Clean up by removing any device mappings for this file descriptor.
     */
    protected fun cleanUpFdResources(fd: Int) {
        if (fd >= 0) {
            clearDeviceMapping(fd)
        }
    }
    
    // --- Transaction Support for Atomic Multi-Step Operations ---
    
    /**
     * Executes a block of I2C operations as an atomic transaction.
     * Holds the file descriptor lock for the entire operation to prevent
     * race conditions when multiple sensors share the same I2C bus.
     *
     * @param operation The block of I2C operations to execute atomically
     * @return The result of the operation block
     */
    protected fun <T> executeTransaction(operation: () -> T): T {
        val lock = fdLock ?: this
        synchronized(lock) {
            // Ensure we're addressing the correct device at start of transaction
            if (!switchToDevice()) {
                throw IOException("Failed to switch to device 0x${sensorAddress.toString(16)}")
            }
            
            // Execute the entire operation while holding the lock
            return operation()
        }
    }
    
    /**
     * Internal method for I2C byte register reads within a transaction.
     * Skips device switching since it's already done at transaction start.
     *
     * @param register The register address to read from
     * @return The byte value read from the register
     */
    protected fun readByteRegTransaction(register: Int): Int {
        if (fileDescriptor < 0) {
            throw IOException("Invalid file descriptor")
        }
        
        // NO switchToDevice() call - transaction already switched
        val result = I2cNative.readWord(fileDescriptor, register)
        if (result < 0) {
            val errorMessage =
                "I2C Read Error on fd=$fileDescriptor, reg=0x${register.toString(16)}, code=$result"
            Log.e(TAG, errorMessage)
            throw IOException(errorMessage)
        }
        return result and 0xFF
    }
    
    /**
     * Internal method for I2C byte register writes within a transaction.
     * Skips device switching since it's already done at transaction start.
     *
     * @param register The register address to write to
     * @param value The byte value to write
     */
    protected fun writeByteRegTransaction(register: Int, value: Int) {
        if (fileDescriptor < 0) {
            throw IOException("Invalid file descriptor")
        }
        
        // NO switchToDevice() call - transaction already switched  
        val result = I2cNative.writeByte(fileDescriptor, register, value)
        if (result < 0) {
            val errorMessage =
                "I2C Write Error on fd=$fileDescriptor, reg=0x${register.toString(16)}, value=0x${
                    value.toString(16)
                }, code=$result"
            Log.e(TAG, errorMessage)
            throw IOException(errorMessage)
        }
    }
    
    /**
     * Internal method for I2C word register writes within a transaction.
     * Skips device switching since it's already done at transaction start.
     *
     * @param lsbRegister The LSB register address to write to
     * @param value The 16-bit value to write (LSB first, then MSB)
     */
    protected fun writeWordRegTransaction(lsbRegister: Int, value: Int) {
        if (fileDescriptor < 0) {
            throw IOException("Invalid file descriptor")
        }
        
        val lsb = value and 0xFF
        val msb = (value shr 8) and 0xFF
        
        // Write LSB first, then MSB - no need to switch device again within transaction
        val result1 = I2cNative.writeByte(fileDescriptor, lsbRegister, lsb)
        if (result1 < 0) {
            throw IOException(
                "I2C Write LSB Error on fd=$fileDescriptor, reg=0x${
                    lsbRegister.toString(
                        16
                    )
                }, value=0x${lsb.toString(16)}, code=$result1"
            )
        }
        
        val result2 = I2cNative.writeByte(fileDescriptor, lsbRegister + 1, msb)
        if (result2 < 0) {
            throw IOException(
                "I2C Write MSB Error on fd=$fileDescriptor, reg=0x${
                    (lsbRegister + 1).toString(
                        16
                    )
                }, value=0x${msb.toString(16)}, code=$result2"
            )
        }
    }
    
    /**
     * Internal method for bit manipulation within a transaction.
     * Skips device switching since it's already done at transaction start.
     *
     * @param register The register address
     * @param bit The bit position (0-7)
     * @param on Whether to set (true) or clear (false) the bit
     */
    protected fun enableBitTransaction(register: Int, bit: Int, on: Boolean) {
        if (fileDescriptor < 0) {
            throw IOException("Invalid file descriptor")
        }
        
        // Read current value using transaction method
        val regValue = readByteRegTransaction(register)
        val bitMask = (1 shl bit)
        val newValue = if (on) regValue or bitMask else regValue and bitMask.inv()
        
        if (newValue != regValue) {
            val result = I2cNative.writeByte(fileDescriptor, register, newValue)
            if (result < 0) {
                val errorMessage =
                    "I2C Write Error on fd=$fileDescriptor, reg=0x${register.toString(16)}, value=0x${
                        newValue.toString(16)
                    }, code=$result"
                Log.e(TAG, errorMessage)
                throw IOException(errorMessage)
            }
        }
    }
    
    /**
     * Internal method for setting specific bits in a register within a transaction.
     * Skips device switching since it's already done at transaction start.
     *
     * @param register The register address
     * @param shift The bit position to start at
     * @param width The number of bits to set
     * @param value The value to set in those bits
     */
    protected fun setRegisterBitsTransaction(register: Int, shift: Int, width: Int, value: Int) {
        if (fileDescriptor < 0) {
            throw IOException("Invalid file descriptor")
        }
        
        // Read current value using transaction method
        val regValue = readByteRegTransaction(register)
        val mask = ((1 shl width) - 1) shl shift
        val newValue = (regValue and mask.inv()) or ((value shl shift) and mask)
        
        val result = I2cNative.writeByte(fileDescriptor, register, newValue)
        if (result < 0) {
            throw IOException(
                "I2C Write Error on fd=$fileDescriptor, reg=0x${register.toString(16)}, value=0x${
                    newValue.toString(
                        16
                    )
                }, code=$result"
            )
        }
    }
    
    /**
     * Check if this sensor is using a multiplexer.
     * @return true if using a multiplexer, false otherwise
     */
    fun isUsingMultiplexer(): Boolean {
        return multiplexer != null
    }
}