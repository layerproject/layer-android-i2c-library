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
    }
    
    // Reference to the bus manager
    private val busManager = I2CBusManager.getInstance()
    
    protected var fileDescriptor: Int = -1
    protected var isInitialized: Boolean = false
    protected var isBusOpen: Boolean = false
    
    // Each sensor type must specify its own I2C address
    protected abstract val sensorAddress: Int
    
    /**
     * Sensor-specific initialization after connection is established
     * @param fd File descriptor for the I2C connection
     * @return true if initialization was successful, false otherwise
     */
    protected abstract fun initializeSensor(fd: Int): Boolean
    
    /**
     * Checks if the sensor has the expected identity
     * Used to verify that the connected device is the expected sensor type
     * @return true if this is the expected sensor, false otherwise
     */
    abstract fun isCorrectSensor(): Boolean
    
    /**
     * Checks if the sensor is connected *and* initialized.
     * @return true if connected and initialized, false otherwise
     */
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
    private fun openDevice(): Int {
        val fd = busManager.openBus(busPath, sensorAddress)
        if (fd < 0) {
            Log.e(TAG, "Failed to open I2C bus $busPath for address 0x${sensorAddress.toString(16)}")
            isBusOpen = false
        } else {
            Log.d(TAG, "Successfully opened I2C bus $busPath for address 0x${sensorAddress.toString(16)}")
            isBusOpen = true
        }
        return fd
    }

    /**
     * Closes an I2C connection.
     */
    protected fun closeDevice() {
        if (isBusOpen) {
            busManager.closeBus(busPath, sensorAddress)
            isBusOpen = false
        }
    }

    /**
     * Opens a connection to the sensor and initializes it.
     * @return true if connection and initialization were successful, false otherwise
     */
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
        
        fileDescriptor = openDevice()
        if (fileDescriptor < 0) {
            Log.e(TAG, "Failed to open sensor on $busPath for address 0x${sensorAddress.toString(16)}.")
            isInitialized = false
            isBusOpen = false
            return false
        }

        Log.d(TAG, "Sensor opened (fd=$fileDescriptor) for address 0x${sensorAddress.toString(16)}. Initializing...")
        isInitialized = initializeSensor(fileDescriptor)

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
    open fun disconnect() {
        if (isBusOpen) {
            Log.d(TAG, "Disconnecting sensor on $busPath for address $sensorAddress (fd=$fileDescriptor)...")
            closeDevice()
            fileDescriptor = -1
            isBusOpen = false
            isInitialized = false
            Log.i(TAG, "Sensor on $busPath disconnected.")
        } else {
            Log.d(TAG, "Sensor on $busPath already disconnected.")
        }
    }

    // --- I2C Primitive Helpers ---
    
    @Synchronized
    protected fun readByteReg(fd: Int, register: Int): Int {
        if (fd < 0) {
            throw IOException("Invalid file descriptor")
        }
        
        val result = I2cNative.readWord(fd, register)
        if (result < 0) {
            throw IOException("I2C Read Error on fd=$fd, reg=0x${register.toString(16)}, code=$result")
        }
        return result and 0xFF
    }

    protected fun readByteReg(register: Int): Int {
        if (!isReady()) {
            throw IOException("Not connected to I2C device")
        }
        return readByteReg(fileDescriptor, register)
    }

    @Synchronized
    protected fun writeByteReg(fd: Int, register: Int, value: Int) {
        if (fd < 0) {
            throw IOException("Invalid file descriptor")
        }
        
        val result = I2cNative.writeByte(fd, register, value)
        if (result < 0) {
            throw IOException("I2C Write Error on fd=$fd, reg=0x${register.toString(16)}, value=0x${value.toString(16)}, code=$result")
        }
    }

    protected fun writeByteReg(register: Int, value: Int) {
        if (!isReady()) {
            throw IOException("Not connected to I2C device")
        }
        writeByteReg(fileDescriptor, register, value)
    }

    @Synchronized
    protected fun writeWordReg(fd: Int, lsbRegister: Int, value: Int) {
        val lsb = value and 0xFF
        val msb = (value shr 8) and 0xFF
        // Write LSB first, then MSB
        writeByteReg(fd, lsbRegister, lsb)
        writeByteReg(fd, lsbRegister + 1, msb)
    }

    protected fun writeWordReg(lsbRegister: Int, value: Int) {
        if (!isReady()) {
            throw IOException("Not connected to I2C device")
        }
        writeWordReg(fileDescriptor, lsbRegister, value)
    }
    
    @Synchronized
    protected fun readDataBlock(data: ByteArray, length: Int): Int {
        if (!isReady()) {
            throw IOException("Not connected to I2C device")
        }
        return I2cNative.readRawBytes(fileDescriptor, data, length)
    }
    
    protected fun enableBit(register: Int, bit: Int, on: Boolean) {
        if (!isReady()) {
            throw IOException("Not connected to I2C device")
        }
        enableBit(fileDescriptor, register, bit, on)
    }

    @Synchronized
    protected fun enableBit(fd: Int, register: Int, bit: Int, on: Boolean) {
        val regValue = readByteReg(fd, register)
        val bitMask = (1 shl bit)
        val newValue = if (on) regValue or bitMask else regValue and bitMask.inv()
        if (newValue != regValue) {
            writeByteReg(fd, register, newValue)
        }
    }

    @Synchronized
    protected fun setRegisterBits(fd: Int, register: Int, shift: Int, width: Int, value: Int) {
        var regValue = readByteReg(fd, register)
        val mask = ((1 shl width) - 1) shl shift
        regValue = (regValue and mask.inv()) or ((value shl shift) and mask)
        writeByteReg(fd, register, regValue)
    }
    
    protected fun setRegisterBits(register: Int, shift: Int, width: Int, value: Int) {
        if (!isReady()) {
            throw IOException("Not connected to I2C device")
        }
        setRegisterBits(fileDescriptor, register, shift, width, value)
    }
}