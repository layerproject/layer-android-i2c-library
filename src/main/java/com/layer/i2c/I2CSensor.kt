package com.layer.i2c

import android.util.Log
import java.io.IOException

/**
 * Base class for all I2C sensors.
 * Provides common I2C communication methods and management.
 */
abstract class I2CSensor(protected val busPath: String) {
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
    
    /**
     * Sensor-specific initialization after connection is established
     * @return true if initialization was successful, false otherwise
     */
    protected abstract fun initializeSensor(): Boolean
    
    
    /**
     * Checks if the sensor is connected *and* initialized.
     * @return true if connected and initialized, false otherwise
     */
    @Synchronized
    fun isReady(): Boolean {
        // First, check if our bus is still open (could have been closed by another sensor)
        if (isBusOpen && fileDescriptor >= 0) {
            val currentFd = busManager.getBusFd(busPath)
            if (currentFd != fileDescriptor) {
                // Bus was closed/reopened by another component
                isBusOpen = false
                fileDescriptor = -1
                isInitialized = false
            }
        }
        
        return isInitialized && isBusOpen && fileDescriptor >= 0
    }

    /**
     * Opens an I2C connection to the sensor on the specified bus.
     * @return File descriptor if successful, -1 if failed
     */
    private fun openDevice(): Boolean {
        val fd = busManager.openBus(busPath, sensorAddress)
        if (fd < 0) {
            Log.e(TAG, "Failed to open I2C bus $busPath for address 0x${sensorAddress.toString(16)}")
            isBusOpen = false
            return false
        } else {
            Log.d(TAG, "Successfully opened I2C bus $busPath for address 0x${sensorAddress.toString(16)}")
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
            
            // Clean up any locks before closing
            cleanUpFdResources(fd)
            
            busManager.closeBus(busPath, sensorAddress)
            isBusOpen = false
        }
    }

    /**
     * Opens a connection to the sensor and initializes it.
     * @return true if connection and initialization were successful, false otherwise
     */
    @Synchronized
    open fun connect(): Boolean {
        if (isInitialized && isBusOpen) {
            Log.d(TAG, "Sensor on $busPath for address 0x${sensorAddress.toString(16)} already connected and initialized.")
            return true
        }

        // Already open but not initialized? Close first.
        if (isBusOpen) {
            Log.w(TAG, "Sensor fd already open but not initialized. Re-opening.")
            closeDevice()
            fileDescriptor = -1
        }

        Log.d(TAG, "Connecting to sensor on $busPath for address 0x${sensorAddress.toString(16)}...")
        
        // Check if this address is already in use on this bus
        if (busManager.isAddressInUse(busPath, sensorAddress)) {
            Log.e(TAG, "Address 0x${sensorAddress.toString(16)} already in use on bus $busPath")
            return false
        }
        
        if (!openDevice()) {
            Log.e(TAG, "Failed to open sensor on $busPath for address 0x${sensorAddress.toString(16)}.")
            isInitialized = false
            isBusOpen = false
            return false
        }

        Log.d(TAG, "Sensor opened (fd=$fileDescriptor) for address 0x${sensorAddress.toString(16)}. Initializing...")
        isInitialized = initializeSensor()

        if (!isInitialized) {
            Log.e(TAG, "Failed to initialize sensor on $busPath for address 0x${sensorAddress.toString(16)} (fd=$fileDescriptor). Closing.")
            closeDevice()
            fileDescriptor = -1
            isBusOpen = false
            return false
        }

        Log.i(TAG, "Sensor on $busPath connected and initialized successfully.")
        return true
    }

    /**
     * Closes the connection to the sensor.
     */
    @Synchronized
    open fun disconnect() {
        if (isBusOpen) {
            Log.d(TAG, "Disconnecting sensor on $busPath for address $sensorAddress (fd=$fileDescriptor)...")
            closeDevice()
            fileDescriptor = -1
            isBusOpen = false
            isInitialized = false
            fdLock = null
            Log.i(TAG, "Sensor on $busPath disconnected.")
        } else {
            Log.d(TAG, "Sensor on $busPath already disconnected.")
        }
    }

    /**
     * Switches the I2C bus to this device's address only if needed.
     * This must be called before any I/O operations when multiple devices
     * share the same I2C bus/file descriptor.
     * 
     * @param fd File descriptor to use
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
            // Check if we're already addressing the correct device
            val currentDevice = getCurrentDevice(fileDescriptor)
            if (currentDevice == sensorAddress) {
                // Already switched to this device, no need to switch again
                return true
            }
            
            // We need to switch device
            val result = I2cNative.switchDeviceAddress(fileDescriptor, sensorAddress)
            if (result < 0) {
                Log.e(TAG, "Failed to switch to device address 0x${sensorAddress.toString(16)} on fd=$fileDescriptor")
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
                val errorMessage = "I2C Read Error on fd=$fileDescriptor, reg=0x${register.toString(16)}, code=$result"
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
                val errorMessage = "I2C Read Error on fd=$fileDescriptor, reg=0x${register.toString(16)}, code=$result"
                Log.e(TAG, errorMessage)
                
                // If this sensor supports recovery (spectral sensors) and we're not already in recovery, attempt it
                if (this is AS7343Sensor && !isRecovering) {
                    Log.w(TAG, "Attempting sensor recovery due to I2C read error on fd=$fileDescriptor")
                    try {
                        isRecovering = true
                        val recovered = (this as AS7343Sensor).recoverSensor()
                        if (recovered) {
                            Log.i(TAG, "Sensor recovery successful, retrying read operation on fd=$fileDescriptor")
                            // Retry the read operation once after successful recovery
                            if (!switchToDevice()) {
                                throw IOException("Failed to switch to device after recovery 0x${sensorAddress.toString(16)}")
                            }
                            val retryResult = I2cNative.readWord(fileDescriptor, register)
                            if (retryResult >= 0) {
                                Log.i(TAG, "Read operation successful after recovery on fd=$fileDescriptor")
                                return retryResult and 0xFF
                            } else {
                                Log.e(TAG, "Read operation still failed after recovery on fd=$fileDescriptor")
                            }
                        } else {
                            Log.e(TAG, "Sensor recovery failed on fd=$fileDescriptor")
                        }
                    } catch (recoveryException: Exception) {
                        Log.e(TAG, "Exception during sensor recovery on fd=$fileDescriptor: ${recoveryException.message}")
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
                throw IOException("I2C Write Error on fd=$fileDescriptor, reg=0x${register.toString(16)}, value=0x${value.toString(16)}, code=$result")
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
                throw IOException("I2C Write LSB Error on fd=$fileDescriptor, reg=0x${lsbRegister.toString(16)}, value=0x${lsb.toString(16)}, code=$result1")
            }
            
            val result2 = I2cNative.writeByte(fileDescriptor, lsbRegister + 1, msb)
            if (result2 < 0) {
                throw IOException("I2C Write MSB Error on fd=$fileDescriptor, reg=0x${(lsbRegister + 1).toString(16)}, value=0x${msb.toString(16)}, code=$result2")
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
            Log.d(TAG, "enableBitDirect called during initialization (fd=$fileDescriptor, reg=0x${register.toString(16)}, bit=$bit)")
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
                    val errorMessage = "I2C Write Error on fd=$fileDescriptor, reg=0x${register.toString(16)}, value=0x${newValue.toString(16)}, code=$result"
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
            Log.d(TAG, "enableBit called during initialization (fd=$fileDescriptor, reg=0x${register.toString(16)}, bit=$bit)")
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
                    throw IOException("I2C Write Error on fd=$fileDescriptor, reg=0x${register.toString(16)}, value=0x${newValue.toString(16)}, code=$result")
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
                throw IOException("I2C Write Error on fd=$fileDescriptor, reg=0x${register.toString(16)}, value=0x${newValue.toString(16)}, code=$result")
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
            val errorMessage = "I2C Read Error on fd=$fileDescriptor, reg=0x${register.toString(16)}, code=$result"
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
            val errorMessage = "I2C Write Error on fd=$fileDescriptor, reg=0x${register.toString(16)}, value=0x${value.toString(16)}, code=$result"
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
            throw IOException("I2C Write LSB Error on fd=$fileDescriptor, reg=0x${lsbRegister.toString(16)}, value=0x${lsb.toString(16)}, code=$result1")
        }
        
        val result2 = I2cNative.writeByte(fileDescriptor, lsbRegister + 1, msb)
        if (result2 < 0) {
            throw IOException("I2C Write MSB Error on fd=$fileDescriptor, reg=0x${(lsbRegister + 1).toString(16)}, value=0x${msb.toString(16)}, code=$result2")
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
                val errorMessage = "I2C Write Error on fd=$fileDescriptor, reg=0x${register.toString(16)}, value=0x${newValue.toString(16)}, code=$result"
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
            throw IOException("I2C Write Error on fd=$fileDescriptor, reg=0x${register.toString(16)}, value=0x${newValue.toString(16)}, code=$result")
        }
    }
}