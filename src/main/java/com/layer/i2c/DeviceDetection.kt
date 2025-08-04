package com.layer.i2c

import android.util.Log

/**
 * Represents information about a detected I2C device.
 * 
 * @param address The 7-bit I2C address of the device (0x08-0x77)
 * @param channel The multiplexer channel where the device was found (0-7 for TCA9548)
 * @param deviceType Optional device type identification (if detectable)
 */
data class DeviceInfo(
    val address: Int,
    val channel: Int,
    val deviceType: String? = null
) {
    companion object {
        private const val TAG = "DeviceInfo"
    }
    /**
     * Get the address as a formatted hex string.
     * @return Address formatted as "0x39"
     */
    fun getAddressHex(): String = "0x${address.toString(16).padStart(2, '0')}"
    
    override fun toString(): String {
        val deviceTypeStr = deviceType?.let { " ($it)" } ?: ""
        return "Device at ${getAddressHex()} on channel $channel$deviceTypeStr"
    }
}

/**
 * Represents the results of scanning I2C devices across multiplexer channels.
 * 
 * @param multiplexerAddress The I2C address of the multiplexer that was scanned
 * @param channelDevices Map of channel number to list of detected devices
 * @param scanTime Timestamp when the scan was performed
 */
data class ChannelDeviceMap(
    val multiplexerAddress: Int,
    val channelDevices: Map<Int, List<DeviceInfo>>,
    val scanTime: Long = System.currentTimeMillis()
) {
    companion object {
        private const val TAG = "ChannelDeviceMap"
    }
    
    init {
        Log.d(TAG, "Created ChannelDeviceMap for multiplexer 0x${multiplexerAddress.toString(16)} with ${getTotalDeviceCount()} devices")
        for ((channel, devices) in channelDevices) {
            if (devices.isNotEmpty()) {
                Log.d(TAG, "Channel $channel: ${devices.map { it.getAddressHex() }.joinToString(", ")}")
            }
        }
    }
    /**
     * Get all detected devices across all channels.
     * @return List of all detected devices
     */
    fun getAllDevices(): List<DeviceInfo> {
        return channelDevices.values.flatten()
    }
    
    /**
     * Get devices on a specific channel.
     * @param channel The channel number
     * @return List of devices on the specified channel, or empty list if none found
     */
    fun getDevicesOnChannel(channel: Int): List<DeviceInfo> {
        return channelDevices[channel] ?: emptyList()
    }
    
    /**
     * Find which channels contain a specific device address.
     * @param address The I2C address to search for
     * @return List of channel numbers containing the device
     */
    fun findChannelsWithDevice(address: Int): List<Int> {
        return channelDevices.entries
            .filter { (_, devices) -> devices.any { it.address == address } }
            .map { it.key }
    }
    
    /**
     * Check if any devices were found.
     * @return true if at least one device was detected, false otherwise
     */
    fun hasDevices(): Boolean {
        return channelDevices.values.any { it.isNotEmpty() }
    }
    
    /**
     * Get the total number of detected devices.
     * @return Total device count
     */
    fun getTotalDeviceCount(): Int {
        return channelDevices.values.sumOf { it.size }
    }
    
    /**
     * Get channels that have devices.
     * @return List of channel numbers that contain at least one device
     */
    fun getActiveChannels(): List<Int> {
        return channelDevices.entries
            .filter { it.value.isNotEmpty() }
            .map { it.key }
            .sorted()
    }
    
    /**
     * Get the multiplexer address as a formatted hex string.
     * @return Address formatted as "0x70"
     */
    fun getMultiplexerAddressHex(): String = "0x${multiplexerAddress.toString(16).padStart(2, '0')}"
    
    override fun toString(): String {
        val deviceCount = getTotalDeviceCount()
        val activeChannels = getActiveChannels()
        return "Scan of multiplexer ${getMultiplexerAddressHex()}: $deviceCount devices on channels $activeChannels"
    }
}

/**
 * Configuration options for I2C device scanning.
 * 
 * @param startAddress First address to scan (inclusive, default 0x08)
 * @param endAddress Last address to scan (inclusive, default 0x77)
 * @param skipAddresses List of addresses to skip during scanning
 * @param timeoutMs Timeout for each address scan in milliseconds
 * @param includeMultiplexer Whether to include the multiplexer's own address in results
 */
data class ScanConfig(
    val startAddress: Int = 0x08,
    val endAddress: Int = 0x77,
    val skipAddresses: List<Int> = listOf(),
    val timeoutMs: Int = 100,
    val includeMultiplexer: Boolean = false
) {
    companion object {
        private const val TAG = "ScanConfig"
    }
    
    init {
        require(startAddress >= 0x08) { "Start address must be >= 0x08" }
        require(endAddress <= 0x77) { "End address must be <= 0x77" }
        require(startAddress <= endAddress) { "Start address must be <= end address" }
        require(timeoutMs > 0) { "Timeout must be positive" }
        
        Log.d(TAG, "Created ScanConfig: addresses 0x${startAddress.toString(16)}-0x${endAddress.toString(16)}, " +
                  "skip ${skipAddresses.map { "0x${it.toString(16)}" }}, " +
                  "timeout ${timeoutMs}ms, includeMultiplexer=$includeMultiplexer")
    }
    
    /**
     * Get the range of addresses to scan, excluding skipped addresses.
     * @param multiplexerAddress The multiplexer's address (to optionally exclude)
     * @return List of addresses to scan
     */
    fun getAddressesToScan(multiplexerAddress: Int? = null): List<Int> {
        val addresses = (startAddress..endAddress).toMutableList()
        
        // Remove explicitly skipped addresses
        addresses.removeAll(skipAddresses.toSet())
        
        // Remove multiplexer address if not included
        if (!includeMultiplexer && multiplexerAddress != null) {
            addresses.remove(multiplexerAddress)
        }
        
        return addresses
    }
}

/**
 * Well-known I2C device addresses and their common device types.
 * This helps with automatic device type identification during scanning.
 */
object CommonI2CDevices {
    private const val TAG = "CommonI2CDevices"
    
    private val knownDevices = mapOf(
        0x39 to "AS7343 Spectral Sensor",
        0x44 to "SHT40 Temperature/Humidity Sensor",
        0x48 to "ADS1115 ADC / TMP102 Temperature Sensor",
        0x49 to "ADS1115 ADC / AS7341 Spectral Sensor",
        0x4A to "ADS1115 ADC",
        0x4B to "ADS1115 ADC",
        0x68 to "DS3231 RTC / MPU6050 IMU",
        0x69 to "MPU6050 IMU",
        0x70 to "TCA9548 I2C Multiplexer",
        0x71 to "TCA9548 I2C Multiplexer",
        0x72 to "TCA9548 I2C Multiplexer",
        0x73 to "TCA9548 I2C Multiplexer",
        0x74 to "TCA9548 I2C Multiplexer",
        0x75 to "TCA9548 I2C Multiplexer",
        0x76 to "TCA9548 I2C Multiplexer / BMP280 Pressure Sensor",
        0x77 to "TCA9548 I2C Multiplexer / BMP280 Pressure Sensor"
    )
    
    /**
     * Get the device type for a known I2C address.
     * @param address The I2C address
     * @return Device type string, or null if not recognized
     */
    fun getDeviceType(address: Int): String? {
        val deviceType = knownDevices[address]
        if (deviceType != null) {
            Log.d(TAG, "Identified device at 0x${address.toString(16)}: $deviceType")
        }
        return deviceType
    }
    
    /**
     * Check if an address corresponds to a known device.
     * @param address The I2C address
     * @return true if the device is recognized, false otherwise
     */
    fun isKnownDevice(address: Int): Boolean {
        return knownDevices.containsKey(address)
    }
    
    /**
     * Get all known device addresses.
     * @return Set of known I2C addresses
     */
    fun getKnownAddresses(): Set<Int> {
        return knownDevices.keys
    }
}