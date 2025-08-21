package com.layer.i2c

import android.util.Log
import java.io.IOException

typealias I2CMultiplexer = TCA9548Multiplexer


/**
 * Base class for TCA9548 I2C multiplexer family.
 *
 * The TCA9548 is an 8-channel I2C multiplexer that allows multiple I2C devices
 * with the same address to be connected to a single I2C bus. Each channel can be
 * individually enabled or disabled.
 *
 * Supported devices:
 * - TCA9548: 8-channel switch
 * - PCA9548: 8-channel switch (compatible)
 * - PCA9546: 4-channel switch
 * - PCA9545: 4-channel switch with interrupt
 * - PCA9543: 2-channel switch with interrupt
 *
 * @param busPath The I2C bus path (e.g., "/dev/i2c-0")
 * @param multiplexerAddress The I2C address of the multiplexer (0x70-0x77)
 */
open class TCA9548Multiplexer(
    busPath: String,
    private val multiplexerAddress: Int = 0x70
) : I2CSensor(busPath) {
    
    companion object : SensorFactory<I2CSensor> {
        private const val TAG = "TCA9548Multiplexer"
        override fun create(busPath:String): TCA9548Multiplexer = TCA9548Multiplexer(busPath)
        // TCA9548 constants
        const val DEFAULT_ADDRESS = 0x70
        const val MIN_ADDRESS = 0x70
        const val MAX_ADDRESS = 0x77
        const val MAX_CHANNELS = 8
        
        // Error codes
        const val ERROR_OK = 0
        const val ERROR_I2C = -1
        const val ERROR_CHANNEL = -2
        const val ERROR_NOT_CONNECTED = -3
    }
    
    override val sensorAddress: Int = multiplexerAddress
    
    // Current channel mask (cached for performance)
    private var currentChannelMask: Int = 0
    
    // Maximum number of channels for this multiplexer variant
    open val maxChannels: Int = MAX_CHANNELS
    
    var deviceMap : ChannelDeviceMap? = null
    
    /**
     * Initialize the multiplexer. Since device presence was already verified through I2CDetect,
     * we simply reset it to a known state and read the initial channel mask.
     */
    override fun initializeSensor(): Boolean {
        if (fileDescriptor < 0) return false
        
        return try {
            // Reset to known state (all channels disabled) - direct operation during init
            currentChannelMask = 0
            val validMask = 0 and ((1 shl maxChannels) - 1)
            
            synchronized(fdLock ?: this) {
                if (!switchToDevice()) {
                    connected = false
                    throw IOException("Failed to switch to multiplexer 0x${multiplexerAddress.toString(16)}")
                }
                
                // Write reset mask directly
                val result = I2cNative.write(fileDescriptor, validMask)
                if (result < 0) {
                    connected = false
                    throw IOException("Failed to write channel mask to multiplexer: I2C error $result")
                }
                currentChannelMask = validMask
            }
            
            // Read back the channel mask to confirm reset
            val mask = readChannelMaskDirect()
            currentChannelMask = mask
            
            Log.d(TAG, "TCA9548 multiplexer initialized at address 0x${multiplexerAddress.toString(16)}, reset to mask: 0x${mask.toString(16)}")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to initialize TCA9548 multiplexer at address 0x${multiplexerAddress.toString(16)}: ${e.message}")
            false
        }
    }
    
    override fun getSensorState() = object : MultiplexerState {
        override val connected = this@TCA9548Multiplexer.isConnected()
        override val updateTS = System.currentTimeMillis()
        override val sensorId = this@TCA9548Multiplexer.toString()
        override val deviceSummary = "Multiplexer ${busPath} address 0x${multiplexerAddress.toString(16)}: Devices: ${deviceMap.toString()}"
    }
    
    override fun readDataImpl(): Map<String, Int> {
        if (!isReady()) {
            return mapOf("ERROR" to 65535)
        }
        return mapOf("channelMask" to currentChannelMask)
    }
    
    /**
     * Check if the multiplexer is connected and responding.
     * @return true if the multiplexer is accessible, false otherwise
     */
    override fun isConnected(): Boolean {
        try {
            readChannelMask()
            connected = true
        } catch (e: IOException) {
            connected = false
        }
        return connected
    }
    
    /**
     * Read the current channel mask from the multiplexer.
     * @return The current channel mask (bit 0 = channel 0, bit 1 = channel 1, etc.)
     */
    private fun readChannelMask(): Int {
        if (!isReady()) {
            throw IOException("Multiplexer not connected")
        }
        
        // For TCA9548, reading from the device returns the current channel mask
        // Use raw I2C read since TCA9548 doesn't use registers
        synchronized(fdLock ?: this) {
            if (!switchToDevice()) {
                throw IOException("Failed to switch to multiplexer 0x${multiplexerAddress.toString(16)}")
            }
            
            val result = I2cNative.readWord(fileDescriptor, 0)
            if (result < 0) {
                throw IOException("Failed to read channel mask from multiplexer: I2C error $result")
            }
            return result and 0xFF
        }
    }
    
    /**
     * Read the current channel mask from the multiplexer during initialization.
     * This version doesn't check isReady() since it's called during initializeSensor().
     * @return The current channel mask (bit 0 = channel 0, bit 1 = channel 1, etc.)
     */
    private fun readChannelMaskDirect(): Int {
        if (fileDescriptor < 0) {
            throw IOException("Multiplexer file descriptor not valid")
        }
        
        // For TCA9548, reading from the device returns the current channel mask
        // Use raw I2C read since TCA9548 doesn't use registers
        synchronized(fdLock ?: this) {
            if (!switchToDevice()) {
                throw IOException("Failed to switch to multiplexer 0x${multiplexerAddress.toString(16)}")
            }
            
            val result = I2cNative.readWord(fileDescriptor, 0)
            if (result < 0) {
                throw IOException("Failed to read channel mask from multiplexer: I2C error $result")
            }
            return result and 0xFF
        }
    }
    
    /**
     * Write a channel mask to the multiplexer.
     * Simple, direct write operation following reference library patterns.
     * @param mask The channel mask to write (bit 0 = channel 0, bit 1 = channel 1, etc.)
     */
    private fun writeChannelMask(mask: Int) {
        if (!isReady()) {
            throw IOException("Multiplexer not connected")
        }
        
        val validMask = mask and ((1 shl maxChannels) - 1)
        
        synchronized(fdLock ?: this) {
            if (!switchToDevice()) {
                throw IOException("Failed to switch to multiplexer 0x${multiplexerAddress.toString(16)}")
            }
            
            // Simple single-byte write to TCA9548 - just like reference libraries
            val result = I2cNative.write(fileDescriptor, validMask)
            if (result < 0) {
                throw IOException("Failed to write channel mask to multiplexer: I2C error $result")
            }
            
            // Update cached value and log success
            currentChannelMask = validMask
            Log.d(TAG, "Set multiplexer channel mask to 0x${validMask.toString(16)}")
        }
    }
    
    /**
     * Get the current channel mask.
     * @return The current channel mask (cached value)
     */
    fun getChannelMask(): Int {
        return currentChannelMask
    }
    
    /**
     * Set the channel mask directly.
     * @param mask The channel mask (bit 0 = channel 0, bit 1 = channel 1, etc.)
     */
    fun setChannelMask(mask: Int) {
        if (mask != currentChannelMask) {
            writeChannelMask(mask)
        }
    }
    
    /**
     * Enable a specific channel.
     * @param channel The channel number (0-7 for TCA9548)
     */
    fun enableChannel(channel: Int) {
        validateChannel(channel)
        val newMask = currentChannelMask or (1 shl channel)
        setChannelMask(newMask)
        Log.d(TAG, "Enabled channel $channel")
    }
    
    /**
     * Disable a specific channel.
     * @param channel The channel number (0-7 for TCA9548)
     */
    fun disableChannel(channel: Int) {
        validateChannel(channel)
        val newMask = currentChannelMask and (1 shl channel).inv()
        setChannelMask(newMask)
        Log.d(TAG, "Disabled channel $channel")
    }
    
    /**
     * Select a specific channel exclusively (disable all others).
     * @param channel The channel number (0-7 for TCA9548)
     */
    fun selectChannel(channel: Int) {
        validateChannel(channel)
        val newMask = 1 shl channel
        setChannelMask(newMask)
        Log.d(TAG, "Selected channel $channel exclusively")
    }
    
    /**
     * Check if a specific channel is enabled.
     * @param channel The channel number (0-7 for TCA9548)
     * @return true if the channel is enabled, false otherwise
     */
    fun isChannelEnabled(channel: Int): Boolean {
        validateChannel(channel)
        return (currentChannelMask and (1 shl channel)) != 0
    }
    
    /**
     * Disable all channels.
     */
    fun disableAllChannels() {
        setChannelMask(0)
        Log.d(TAG, "Disabled all channels")
    }
    
    /**
     * Enable all channels.
     */
    fun enableAllChannels() {
        val allChannelsMask = (1 shl maxChannels) - 1
        setChannelMask(allChannelsMask)
        Log.d(TAG, "Enabled all channels")
    }
    
    /**
     * Get a list of currently enabled channels.
     * @return List of enabled channel numbers
     */
    fun getEnabledChannels(): List<Int> {
        val enabledChannels = mutableListOf<Int>()
        for (i in 0 until maxChannels) {
            if (isChannelEnabled(i)) {
                enabledChannels.add(i)
            }
        }
        return enabledChannels
    }
    
    /**
     * Execute an operation on a specific channel.
     * This method temporarily switches to the specified channel, executes the operation,
     * and then restores the previous channel configuration.
     *
     * @param channel The channel to execute the operation on
     * @param operation The operation to execute
     * @return The result of the operation
     */
    fun <T> executeOnChannel(channel: Int, operation: () -> T): T {
        validateChannel(channel)
        
        val previousMask = currentChannelMask
        try {
            selectChannel(channel)
            return operation()
        } finally {
            setChannelMask(previousMask)
        }
    }
    
    /**
     * Execute an operation with specific channels enabled.
     * This method temporarily sets the channel mask, executes the operation,
     * and then restores the previous channel configuration.
     *
     * @param channels List of channels to enable during the operation
     * @param operation The operation to execute
     * @return The result of the operation
     */
    fun <T> executeWithChannels(channels: List<Int>, operation: () -> T): T {
        channels.forEach { validateChannel(it) }
        
        val previousMask = currentChannelMask
        try {
            var newMask = 0
            channels.forEach { newMask = newMask or (1 shl it) }
            setChannelMask(newMask)
            return operation()
        } finally {
            setChannelMask(previousMask)
        }
    }
    
    /**
     * Validate that a channel number is within the valid range.
     * @param channel The channel number to validate
     * @throws IllegalArgumentException if the channel is invalid
     */
    private fun validateChannel(channel: Int) {
        if (channel < 0 || channel >= maxChannels) {
            throw IllegalArgumentException("Channel $channel is out of range (0-${maxChannels - 1})")
        }
    }
    
    /**
     * Reset the multiplexer to a known state (all channels disabled).
     * This is useful for initialization or error recovery.
     */
    fun reset() {
        if (isReady()) {
            disableAllChannels()
            Log.i(TAG, "Multiplexer reset - all channels disabled")
        }
    }
    
    /**
     * Get the multiplexer address.
     * @return The I2C address of the multiplexer
     */
    fun getMultiplexerAddress(): Int {
        return multiplexerAddress
    }
    
    // --- Device Detection and Scanning Methods ---
    
    /**
     * Scan a specific channel for I2C devices.
     * This method temporarily switches to the specified channel and scans for devices.
     *
     * @param channel The channel to scan (0 to maxChannels-1)
     * @param config Scan configuration options
     * @return List of detected devices on the channel
     */
    fun scanChannel(channel: Int, config: ScanConfig = ScanConfig()): List<DeviceInfo> {
        validateChannel(channel)
        
        if (!isReady()) {
            throw IOException("Multiplexer not connected")
        }
        
        // Save current state
        val originalMask = currentChannelMask
        
        try {
            Log.d(TAG, "Scanning channel $channel using I2CDetect approach")
            
            // Switch to the target channel exclusively
            selectChannel(channel)
            Log.d(TAG, "Enabled channel $channel, now scanning bus with I2CDetect")
            
            // Use I2CDetect to scan whatever is now visible on the bus
            val scanResult = I2CDetect.performI2CDetect(busPath)
            val detectedDevices = scanResult.detectedDevices
            
            // Filter out the multiplexer itself from results and assign correct channel
            val channelDevices = detectedDevices
                .filter { it.address != multiplexerAddress }
                .map { device ->
                    DeviceInfo(device.address, channel, device.deviceType)
                }
            
            Log.i(TAG, "Channel $channel scan found ${channelDevices.size} devices: ${channelDevices.map { it.getAddressHex() }.joinToString(", ")}")
            
            return channelDevices
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to scan channel $channel: ${e.message}")
            return emptyList()
        } finally {
            // Restore original channel mask
            try {
                setChannelMask(originalMask)
            } catch (restoreException: Exception) {
                Log.w(TAG, "Failed to restore channel mask after scanning channel $channel: ${restoreException.message}")
            }
        }
    }
    
    /**
     * Scan all channels for I2C devices.
     * This method scans each channel sequentially and returns a comprehensive map.
     * Implements robust error handling to ensure all discoverable devices are found.
     *
     * @param config Scan configuration options
     * @return ChannelDeviceMap containing all detected devices organized by channel
     */
    fun scanAllChannels(config: ScanConfig = ScanConfig()): ChannelDeviceMap {
        if (!isReady()) {
            throw IOException("Multiplexer not connected")
        }
        
        Log.i(TAG, "Starting full scan of all ${maxChannels} channels on multiplexer 0x${multiplexerAddress.toString(16)}")
        val channelDevices = mutableMapOf<Int, List<DeviceInfo>>()
        var totalDevicesFound = 0
        var successfulChannels = 0
        var failedChannels = 0
        
        // Store initial state for recovery
        val initialMask = currentChannelMask
        Log.d(TAG, "Initial multiplexer state: mask=0x${initialMask.toString(16)}")
        
        for (channel in 0 until maxChannels) {
            try {
                Log.d(TAG, "Scanning channel $channel...")
                val devices = scanChannel(channel, config)
                channelDevices[channel] = devices
                
                if (devices.isNotEmpty()) {
                    totalDevicesFound += devices.size
                    successfulChannels++
                    Log.i(TAG, "Channel $channel: found ${devices.size} devices - ${devices.map { it.getAddressHex() }.joinToString(", ")}")
                } else {
                    Log.d(TAG, "Channel $channel: no devices found")
                }
                
            } catch (e: Exception) {
                failedChannels++
                Log.e(TAG, "Failed to scan channel $channel: ${e.message}")
                channelDevices[channel] = emptyList()
                
                // Simple delay before continuing - avoid complex recovery that might worsen things
                Thread.sleep(10)
            }
        }
        
        // Attempt to restore initial state
        try {
            if (currentChannelMask != initialMask) {
                Log.d(TAG, "Restoring initial multiplexer state: 0x${initialMask.toString(16)}")
                setChannelMask(initialMask)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore initial multiplexer state: ${e.message}")
        }
        
        val result = ChannelDeviceMap(multiplexerAddress, channelDevices)
        
        Log.i(TAG, "Full scan complete: $totalDevicesFound devices found across $successfulChannels channels")
        if (failedChannels > 0) {
            Log.w(TAG, "Scan had $failedChannels failed channels, but discovered devices are preserved")
        }
        
        // Log detailed results for debugging
        if (totalDevicesFound > 0) {
            Log.i(TAG, "Device summary:")
            for ((channel, devices) in channelDevices) {
                if (devices.isNotEmpty()) {
                    Log.i(TAG, "  Channel $channel: ${devices.map { "${it.getAddressHex()}${it.deviceType?.let { " ($it)" } ?: ""}" }.joinToString(", ")}")
                }
            }
        }
        deviceMap = result
        return result
    }
    
    /**
     * Find which channels contain a specific device address.
     * This method scans all channels looking for the specified address.
     *
     * @param address The I2C address to search for
     * @return List of channel numbers where the device was found
     */
    fun findDevice(address: Int): List<Int> {
        if (address < 0x08 || address > 0x77) {
            throw IllegalArgumentException("Invalid I2C address: 0x${address.toString(16)}")
        }
        
        if (!isReady()) {
            throw IOException("Multiplexer not connected")
        }
        
        Log.d(TAG, "Searching for device at 0x${address.toString(16)} across all channels")
        val foundChannels = mutableListOf<Int>()
        val originalMask = currentChannelMask
        
        try {
            for (channel in 0 until maxChannels) {
                try {
                    selectChannel(channel)
                    
                    // Use I2CDetect to scan current channel
                    val scanResult = I2CDetect.performI2CDetect(busPath)
                    val deviceFound = scanResult.detectedDevices.any { it.address == address }
                    
                    if (deviceFound) {
                        foundChannels.add(channel)
                        Log.d(TAG, "Found device 0x${address.toString(16)} on channel $channel")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error scanning address 0x${address.toString(16)} on channel $channel: ${e.message}")
                }
            }
        } finally {
            try {
                setChannelMask(originalMask)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to restore mask after device search: ${e.message}")
            }
        }
        
        Log.i(TAG, "Device 0x${address.toString(16)} found on channels: $foundChannels")
        return foundChannels
    }
    
    /**
     * Check if a specific device is present on a specific channel.
     *
     * @param address The I2C address to check
     * @param channel The channel to check
     * @return true if the device responds on the specified channel, false otherwise
     */
    fun isDeviceOnChannel(address: Int, channel: Int): Boolean {
        if (address < 0x08 || address > 0x77) {
            throw IllegalArgumentException("Invalid I2C address: 0x${address.toString(16)}")
        }
        validateChannel(channel)
        
        if (!isReady()) {
            throw IOException("Multiplexer not connected")
        }
        
        val originalMask = currentChannelMask
        
        try {
            selectChannel(channel)
            
            // Use I2CDetect to scan current channel
            val scanResult = I2CDetect.performI2CDetect(busPath)
            return scanResult.detectedDevices.any { it.address == address }
            
        } catch (e: Exception) {
            Log.w(TAG, "Error checking device 0x${address.toString(16)} on channel $channel: ${e.message}")
            return false
        } finally {
            try {
                setChannelMask(originalMask)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to restore mask after device check: ${e.message}")
            }
        }
    }
    
    /**
     * Scan for devices using a more comprehensive approach.
     * This method provides additional options for advanced scanning scenarios.
     *
     * @param channels List of specific channels to scan (if empty, scans all channels)
     * @param config Scan configuration options
     * @return ChannelDeviceMap containing detected devices
     */
    fun scanChannels(channels: List<Int> = emptyList(), config: ScanConfig = ScanConfig()): ChannelDeviceMap {
        if (!isReady()) {
            throw IOException("Multiplexer not connected")
        }
        
        val channelsToScan = if (channels.isEmpty()) {
            (0 until maxChannels).toList()
        } else {
            channels.also { it.forEach { channel -> validateChannel(channel) } }
        }
        
        Log.i(TAG, "Scanning specific channels: $channelsToScan")
        val channelDevices = mutableMapOf<Int, List<DeviceInfo>>()
        
        for (channel in channelsToScan) {
            try {
                val devices = scanChannel(channel, config)
                channelDevices[channel] = devices
            } catch (e: Exception) {
                Log.e(TAG, "Failed to scan channel $channel: ${e.message}")
                channelDevices[channel] = emptyList()
            }
        }
        
        return ChannelDeviceMap(multiplexerAddress, channelDevices)
    }
    
    /**
     * Perform a quick scan to check if any devices are present.
     * This method stops scanning as soon as it finds the first device.
     *
     * @return true if at least one device was found, false if no devices found
     */
    fun hasAnyDevices(): Boolean {
        if (!isReady()) {
            return false
        }
        
        val originalMask = currentChannelMask
        
        try {
            for (channel in 0 until maxChannels) {
                selectChannel(channel)
                
                // Use I2CDetect to scan current channel
                val scanResult = I2CDetect.performI2CDetect(busPath)
                val hasDevices = scanResult.detectedDevices.any { it.address != multiplexerAddress }
                
                if (hasDevices) {
                    return true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error during quick device scan: ${e.message}")
        } finally {
            try {
                setChannelMask(originalMask)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to restore mask after quick scan: ${e.message}")
            }
        }
        
        return false
    }
    
    /**
     * Scan the I2C bus directly (bypassing multiplexer channels).
     * This method scans for devices that are connected directly to the I2C bus,
     * not through multiplexer channels. This is useful for detecting the multiplexer
     * itself and any other devices on the main bus.
     *
     * @param config Scan configuration options
     * @return List of DeviceInfo objects found on the direct bus
     */
    fun scanDirectBus(config: ScanConfig = ScanConfig()): List<DeviceInfo> {
        Log.d(TAG, "Scanning direct I2C bus (bypassing multiplexer channels)")
        
        try {
            // Use the I2CDetect utility to scan the bus directly
            val devices = I2CDetect.scanI2CBusWithDeviceInfo(
                busPath = busPath,
                startAddress = config.startAddress,
                endAddress = config.endAddress
            )
            
            // Filter out addresses that should be skipped
            val filteredDevices = devices.filter { device ->
                !config.skipAddresses.contains(device.address) &&
                (config.includeMultiplexer || device.address != multiplexerAddress)
            }
            
            Log.i(TAG, "Direct bus scan found ${filteredDevices.size} devices: ${filteredDevices.map { it.getAddressHex() }}")
            return filteredDevices
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to scan direct I2C bus: ${e.message}")
            return emptyList()
        }
    }
    
    /**
     * Perform a comprehensive scan that includes both direct bus devices
     * and devices connected through multiplexer channels.
     *
     * @param config Scan configuration options
     * @return ComprehensiveScanResult containing both direct and multiplexed devices
     */
    fun scanComprehensive(config: ScanConfig = ScanConfig()): ComprehensiveScanResult {
        Log.i(TAG, "Starting comprehensive scan (direct bus + all multiplexer channels)")
        
        // Scan direct bus first
        val directDevices = scanDirectBus(config)
        
        // Scan all multiplexer channels
        val channelScan = scanAllChannels(config)
        
        return ComprehensiveScanResult(
            multiplexerAddress = multiplexerAddress,
            directBusDevices = directDevices,
            channelDeviceMap = channelScan,
            scanTime = System.currentTimeMillis()
        )
    }
    
    /**
     * Get an i2cdetect-style formatted table for the direct bus.
     * This shows what would be visible with a standard i2cdetect command.
     *
     * @return Formatted table string
     */
    fun getDirectBusTable(): String {
        return try {
            val addresses = I2CDetect.scanI2CBus(busPath)
            I2CDetect.formatI2CDetectTable(busPath, addresses)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate direct bus table: ${e.message}")
            "Error scanning direct bus: ${e.message}"
        }
    }
    
}

/**
 * Result of a comprehensive scan that includes both direct bus and multiplexed devices.
 *
 * @param multiplexerAddress The I2C address of the multiplexer
 * @param directBusDevices Devices found on the direct I2C bus (not through multiplexer)
 * @param channelDeviceMap Devices found on multiplexer channels
 * @param scanTime Timestamp when the scan was performed
 */
data class ComprehensiveScanResult(
    val multiplexerAddress: Int,
    val directBusDevices: List<DeviceInfo>,
    val channelDeviceMap: ChannelDeviceMap,
    val scanTime: Long
) {
    /**
     * Get all detected devices (direct bus + all channels).
     * @return List of all detected devices
     */
    fun getAllDevices(): List<DeviceInfo> {
        return directBusDevices + channelDeviceMap.getAllDevices()
    }
    
    /**
     * Get the total number of detected devices.
     * @return Total device count across direct bus and all channels
     */
    fun getTotalDeviceCount(): Int {
        return directBusDevices.size + channelDeviceMap.getTotalDeviceCount()
    }
    
    /**
     * Check if any devices were found.
     * @return true if at least one device was detected anywhere
     */
    fun hasDevices(): Boolean {
        return directBusDevices.isNotEmpty() || channelDeviceMap.hasDevices()
    }
    
    /**
     * Get a summary of the comprehensive scan.
     * @return Human-readable summary string
     */
    fun getSummary(): String {
        val directCount = directBusDevices.size
        val channelCount = channelDeviceMap.getTotalDeviceCount()
        val activeChannels = channelDeviceMap.getActiveChannels().size
        
        return "Comprehensive scan: $directCount direct bus devices, " +
               "$channelCount devices across $activeChannels multiplexer channels"
    }
    
    override fun toString(): String {
        return getSummary()
    }
}