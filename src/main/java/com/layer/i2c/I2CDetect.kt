package com.layer.i2c

import android.util.Log
import java.io.IOException

/**
 * I2C detection utility class that provides i2cdetect-style functionality.
 * This class can scan I2C buses directly (without multiplexers) to detect connected devices.
 * 
 * Based on the Linux i2cdetect command-line tool, this implementation uses
 * SMBus probing techniques to detect I2C devices without needing to know
 * their specific protocols or communication patterns.
 */
object I2CDetect {
    private const val TAG = "I2CDetect"
    
    // Standard I2C address ranges (same as i2cdetect)
    const val MIN_ADDRESS = 0x08
    const val MAX_ADDRESS = 0x77
    
    /**
     * Scan an I2C bus for devices using i2cdetect-style probing.
     * This method opens the I2C bus directly and scans for devices.
     * 
     * @param busPath The I2C bus device path (e.g., "/dev/i2c-0")
     * @param startAddress First address to scan (default: 0x08)
     * @param endAddress Last address to scan (default: 0x77)
     * @return List of detected I2C addresses
     */
    fun scanI2CBus(
        busPath: String, 
        startAddress: Int = MIN_ADDRESS, 
        endAddress: Int = MAX_ADDRESS
    ): List<Int> {
        require(startAddress >= MIN_ADDRESS) { "Start address must be >= 0x${MIN_ADDRESS.toString(16)}" }
        require(endAddress <= MAX_ADDRESS) { "End address must be <= 0x${MAX_ADDRESS.toString(16)}" }
        require(startAddress <= endAddress) { "Start address must be <= end address" }
        
        Log.i(TAG, "Scanning I2C bus $busPath from 0x${startAddress.toString(16)} to 0x${endAddress.toString(16)}")
        
        val detectedAddresses = mutableListOf<Int>()
        
        // Open the I2C bus with a dummy address (we'll switch addresses during scanning)
        val fd = I2cNative.openBus(busPath, 0x08)
        if (fd < 0) {
            throw IOException("Failed to open I2C bus: $busPath")
        }
        
        try {
            for (address in startAddress..endAddress) {
                try {
                    val result = I2cNative.scanAddress(fd, address)
                    if (result > 0) {
                        detectedAddresses.add(address)
                        Log.d(TAG, "Device detected at address 0x${address.toString(16)}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error scanning address 0x${address.toString(16)}: ${e.message}")
                }
            }
        } finally {
            I2cNative.closeBus(fd)
        }
        
        Log.i(TAG, "Bus scan complete: found ${detectedAddresses.size} devices at addresses: ${detectedAddresses.map { "0x${it.toString(16)}" }}")
        return detectedAddresses
    }
    
    /**
     * Scan an I2C bus and return detected devices with type information.
     * 
     * @param busPath The I2C bus device path (e.g., "/dev/i2c-0")
     * @param startAddress First address to scan (default: 0x08)
     * @param endAddress Last address to scan (default: 0x77)
     * @return List of DeviceInfo objects with address and type information
     */
    fun scanI2CBusWithDeviceInfo(
        busPath: String,
        startAddress: Int = MIN_ADDRESS,
        endAddress: Int = MAX_ADDRESS
    ): List<DeviceInfo> {
        val addresses = scanI2CBus(busPath, startAddress, endAddress)
        return addresses.map { address -> 
            DeviceInfo(
                address = address,
                channel = -1, // Direct bus connection (no multiplexer)
                deviceType = CommonI2CDevices.getDeviceType(address)
            )
        }
    }
    
    /**
     * Check if a specific device is present on an I2C bus.
     * 
     * @param busPath The I2C bus device path (e.g., "/dev/i2c-0")
     * @param address The I2C address to check
     * @return true if device is present, false otherwise
     */
    fun isDevicePresent(busPath: String, address: Int): Boolean {
        require(address >= MIN_ADDRESS && address <= MAX_ADDRESS) { 
            "Address must be in range 0x${MIN_ADDRESS.toString(16)}-0x${MAX_ADDRESS.toString(16)}" 
        }
        
        val fd = I2cNative.openBus(busPath, 0x08)
        if (fd < 0) {
            Log.e(TAG, "Failed to open I2C bus: $busPath")
            return false
        }
        
        return try {
            val result = I2cNative.scanAddress(fd, address)
            result > 0
        } catch (e: Exception) {
            Log.w(TAG, "Error checking device at 0x${address.toString(16)}: ${e.message}")
            false
        } finally {
            I2cNative.closeBus(fd)
        }
    }
    
    /**
     * Print an i2cdetect-style table showing detected devices.
     * This mimics the output format of the standard Linux i2cdetect command.
     * 
     * @param busPath The I2C bus device path
     * @param addresses List of detected addresses (from scanI2CBus)
     * @return Formatted string representing the detection table
     */
    fun formatI2CDetectTable(busPath: String, addresses: List<Int>): String {
        val output = StringBuilder()
        output.appendLine("I2C detect table for $busPath:")
        output.appendLine("     0  1  2  3  4  5  6  7  8  9  a  b  c  d  e  f")
        
        for (row in 0..7) {
            output.append("${row}0: ")
            
            for (col in 0..15) {
                val address = row * 16 + col
                
                when {
                    address < MIN_ADDRESS || address > MAX_ADDRESS -> {
                        output.append("   ") // Outside valid range
                    }
                    address in addresses -> {
                        output.append("${address.toString(16).padStart(2, '0')} ")
                    }
                    else -> {
                        output.append("-- ")
                    }
                }
            }
            output.appendLine()
        }
        
        return output.toString()
    }
    
    /**
     * Perform a complete i2cdetect scan and print results.
     * This is equivalent to running `i2cdetect -y <bus_number>` on the command line.
     * 
     * @param busPath The I2C bus device path (e.g., "/dev/i2c-0")
     * @param printResults Whether to print results to log (default: true)
     * @return I2CDetectResult containing addresses and formatted table
     */
    fun performI2CDetect(busPath: String, printResults: Boolean = true): I2CDetectResult {
        Log.i(TAG, "Performing i2cdetect scan on $busPath")
        
        val startTime = System.currentTimeMillis()
        val addresses = scanI2CBus(busPath)
        val scanDuration = System.currentTimeMillis() - startTime
        
        val devicesWithInfo = addresses.map { address ->
            DeviceInfo(
                address = address,
                channel = -1,
                deviceType = CommonI2CDevices.getDeviceType(address)
            )
        }
        
        val table = formatI2CDetectTable(busPath, addresses)
        
        val result = I2CDetectResult(
            busPath = busPath,
            detectedAddresses = addresses,
            detectedDevices = devicesWithInfo,
            formattedTable = table,
            scanDurationMs = scanDuration
        )
        
        if (printResults) {
            Log.i(TAG, "I2C detect scan results for $busPath:")
            Log.i(TAG, table)
            Log.i(TAG, "Found ${addresses.size} devices in ${scanDuration}ms")
            devicesWithInfo.forEach { device ->
                val typeInfo = device.deviceType?.let { " ($it)" } ?: ""
                Log.i(TAG, "Device at ${device.getAddressHex()}$typeInfo")
            }
        }
        
        return result
    }
}

/**
 * Result of an I2C detect scan operation.
 * 
 * @param busPath The I2C bus that was scanned
 * @param detectedAddresses List of addresses where devices were found
 * @param detectedDevices List of DeviceInfo objects with type information
 * @param formattedTable i2cdetect-style formatted table string
 * @param scanDurationMs Time taken to perform the scan
 */
data class I2CDetectResult(
    val busPath: String,
    val detectedAddresses: List<Int>,
    val detectedDevices: List<DeviceInfo>,
    val formattedTable: String,
    val scanDurationMs: Long
) {
    /**
     * Get devices of a specific type.
     * @param deviceType The device type to filter by
     * @return List of devices matching the type
     */
    fun getDevicesOfType(deviceType: String): List<DeviceInfo> {
        return detectedDevices.filter { it.deviceType == deviceType }
    }
    
    /**
     * Check if a specific device type was detected.
     * @param deviceType The device type to check for
     * @return true if at least one device of this type was found
     */
    fun hasDeviceType(deviceType: String): Boolean {
        return detectedDevices.any { it.deviceType == deviceType }
    }
    
    /**
     * Get summary statistics about the scan.
     * @return Human-readable summary string
     */
    fun getSummary(): String {
        val deviceCount = detectedAddresses.size
        val knownDevices = detectedDevices.count { it.deviceType != null }
        val unknownDevices = deviceCount - knownDevices
        
        return "Scanned $busPath in ${scanDurationMs}ms: $deviceCount devices found " +
               "($knownDevices known types, $unknownDevices unknown)"
    }
}