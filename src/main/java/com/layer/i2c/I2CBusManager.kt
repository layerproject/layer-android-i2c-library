package com.layer.i2c

import android.util.Log
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Manager for I2C bus access that handles sharing buses between multiple sensors.
 * This singleton ensures proper management of I2C file descriptors when
 * multiple sensors are connected to the same physical I2C bus.
 */
class I2CBusManager private constructor() {
    companion object {
        private const val TAG = "I2CBusManager"
        
        // Singleton instance
        private val instance = I2CBusManager()
        
        // Get the singleton instance
        fun getInstance(): I2CBusManager = instance
    }
    
    // Map of bus paths to their file descriptors
    private val busMap = ConcurrentHashMap<String, Int>()
    
    // Map to track which sensor addresses are active on each bus
    private val addressMap = ConcurrentHashMap<String, MutableSet<Int>>()
    
    // Map to track reference count for each bus
    private val referenceCountMap = ConcurrentHashMap<String, Int>()
    
    // Map of file descriptors to locks for fd-level synchronization
    private val fdLockMap = ConcurrentHashMap<Int, Any>()
    
    /**
     * Opens an I2C bus for a specific address if not already open.
     * If the bus is already open, it increments the reference count.
     * 
     * @param busPath The path to the I2C bus (e.g., "/dev/i2c-0")
     * @param address The I2C address of the device on the bus
     * @return File descriptor if successful, -1 if failed
     */
    @Synchronized
    fun openBus(busPath: String, address: Int): Int {
        // First check if we already have this bus open
        var fd = busMap[busPath]
        
        // If not open, open it
        if (fd == null || fd < 0) {
            fd = I2cNative.openBus(busPath, address)
            if (fd < 0) {
                Log.e(TAG, "Failed to open I2C bus $busPath for address 0x${address.toString(16)}")
                return -1
            }
            busMap[busPath] = fd
            referenceCountMap[busPath] = 1
            
            // Initialize address set for this bus
            addressMap[busPath] = mutableSetOf(address)
            
            // Create a lock object for this file descriptor
            fdLockMap[fd] = Any()
            
            // Register this device as the current device on this fd
            I2CSensor.setCurrentDevice(fd, address)
            
            Log.d(TAG, "Opened I2C bus $busPath for address 0x${address.toString(16)}, fd=$fd")
        } else {
            // Bus already open, increment reference count
            referenceCountMap[busPath] = (referenceCountMap[busPath] ?: 0) + 1
            
            // Add address to the set of active addresses for this bus
            addressMap[busPath]?.add(address)
            
            Log.d(TAG, "Reusing I2C bus $busPath for address 0x${address.toString(16)}, fd=$fd, refCount=${referenceCountMap[busPath]}")
        }
        
        return fd
    }
    
    /**
     * Closes an I2C bus when a sensor is done with it.
     * Only actually closes the file descriptor when the reference count drops to zero.
     * 
     * @param busPath The path to the I2C bus
     * @param address The I2C address of the device being closed
     */
    @Synchronized
    fun closeBus(busPath: String, address: Int) {
        val fd = busMap[busPath] ?: return
        
        // Get the current reference count
        val refCount = referenceCountMap[busPath] ?: 0
        
        // Remove this sensor's address from the set of active addresses
        addressMap[busPath]?.remove(address)
        
        if (refCount <= 1) {
            // Last reference, actually close the bus
            I2cNative.closeBus(fd)
            busMap.remove(busPath)
            referenceCountMap.remove(busPath)
            addressMap.remove(busPath)
            
            // Clean up the current device tracking in I2CSensor
            I2CSensor.clearDeviceMapping(fd)
            
            // Remove the lock object for this file descriptor
            fdLockMap.remove(fd)
            
            Log.d(TAG, "Closed I2C bus $busPath (fd=$fd), no more references")
        } else {
            // Decrement reference count
            referenceCountMap[busPath] = refCount - 1
            Log.d(TAG, "Released I2C bus $busPath for address 0x${address.toString(16)}, " +
                   "refCount=${refCount - 1}")
        }
    }
    
    /**
     * Check if a specific address is already in use on a bus
     */
    @Synchronized
    fun isAddressInUse(busPath: String, address: Int): Boolean {
        return addressMap[busPath]?.contains(address) == true
    }
    
    /**
     * Get the file descriptor for a bus if it's open
     */
    fun getBusFd(busPath: String): Int {
        return busMap[busPath] ?: -1
    }
    
    /**
     * Get the lock object for a file descriptor.
     * This allows multiple sensors sharing the same file descriptor
     * to synchronize I/O operations across different instances.
     * 
     * @param fd The file descriptor to get the lock for
     * @return The lock object, or null if the file descriptor is not managed
     */
    fun getFdLock(fd: Int): Any? {
        return fdLockMap[fd]
    }
}