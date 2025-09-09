# Layer Android I2C Library

An Android library for I2C communication and AS73XX spectral sensor interactions. This library
provides a clean API for communicating with I2C devices, with specific support for the AS7341 and
AS7343 spectral sensors.

## Features

- Native C code for I2C communication
- JNI interface for Android
- High-level Kotlin API for sensor interaction
- Support for AS7341 and AS7343 spectral sensors
- Support for SHT40 temperature/humidity sensor
- TCA9548 family I2C multiplexer support (TCA9548, PCA9548, PCA9546, PCA9545, PCA9543)
- Common interface for all sensor types
- Transaction-level locking for multi-sensor applications

## Installation

### Gradle

Add the GitHub Packages repository to your project's `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/layerproject/layer-android-i2c-library")
            credentials {
                username =
                    project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_USER")
                password =
                    project.findProperty("gpr.key") as String? ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
```

Then add the dependency to your module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.layer:i2c:1.0.2")
}
```

## Usage

### Basic Usage

```kotlin
// Create a sensor instance with bus path
val sensor = AS7343Sensor("/dev/i2c-0")

// Connect to the sensor
if (sensor.connect()) {
    // Read spectral data
    val channelData = sensor.readSpectralData()
    
    // Disconnect when done
    sensor.disconnect()
}
```

### Simplified One-time Reading (AS7343)

```kotlin
// Create a sensor instance
val sensor = AS7343Sensor("/dev/i2c-0")

// Connect, read, and automatically handle initialization
val data = sensor.readSpectralDataOnce()

// Access specific channels
val nirValue = data["NIR"]
val f1Value = data["F1"]
```

### Reading Multiple Sensors

```kotlin
// Create sensors for different I2C buses
val sensor1 = AS7343Sensor("/dev/i2c-0")
val sensor2 = AS7343Sensor("/dev/i2c-1")

// Connect both sensors
val sensor1Ready = sensor1.connect()
val sensor2Ready = sensor2.connect()

// Read data from both sensors
if (sensor1Ready) {
    val data1 = sensor1.readSpectralData()
}

if (sensor2Ready) {
    val data2 = sensor2.readSpectralData()
}

// Don't forget to disconnect when done
sensor1.disconnect()
sensor2.disconnect()
```

### Using I2C Multiplexers

The library supports the TCA9548 family of I2C multiplexers, allowing you to connect multiple
sensors with the same I2C address.

#### Basic Multiplexer Usage

```kotlin
// Create a multiplexer instance
val multiplexer = TCA9548Multiplexer("/dev/i2c-0", 0x70)

// Connect to the multiplexer
if (multiplexer.connect()) {
    // Enable specific channels
    multiplexer.enableChannel(0)
    multiplexer.enableChannel(1)
    
    // Or select a single channel exclusively
    multiplexer.selectChannel(2)
    
    // Disable all channels when done
    multiplexer.disableAllChannels()
    
    multiplexer.disconnect()
}
```

#### Using Sensors with Multiplexers

```kotlin
// Create a multiplexer
val multiplexer = TCA9548Multiplexer("/dev/i2c-0", 0x70)

// Create sensors on different multiplexer channels
val sensor1 = AS7343Sensor("/dev/i2c-0", multiplexer, 0)  // Channel 0
val sensor2 = AS7343Sensor("/dev/i2c-0", multiplexer, 1)  // Channel 1
val sensor3 = SHT40Sensor("/dev/i2c-0", multiplexer, 2)   // Channel 2

// Connect and read from sensors
if (sensor1.connect() && sensor2.connect() && sensor3.connect()) {
    // Read data - multiplexer channels are automatically managed
    val data1 = sensor1.readSpectralData()
    val data2 = sensor2.readSpectralData()
    val tempHumidity = sensor3.readTemperatureAndHumidity()
    
    // Disconnect sensors
    sensor1.disconnect()
    sensor2.disconnect()
    sensor3.disconnect()
}

// Disconnect multiplexer
multiplexer.disconnect()
```

#### Advanced Multiplexer Operations

```kotlin
val multiplexer = PCA9546Multiplexer("/dev/i2c-0", 0x71)  // 4-channel variant

if (multiplexer.connect()) {
    // Execute operation on specific channel
    val result = multiplexer.executeOnChannel(3) {
        // This code runs with only channel 3 enabled
        // Perform operations that require exclusive channel access
        "Operation completed on channel 3"
    }
    
    // Execute operation with multiple channels
    val multiResult = multiplexer.executeWithChannels(listOf(0, 2)) {
        // This code runs with channels 0 and 2 enabled
        // The previous channel configuration is restored afterward
        "Operation completed on channels 0 and 2"
    }
    
    // Check multiplexer status
    val enabledChannels = multiplexer.getEnabledChannels()
    println("Currently enabled channels: $enabledChannels")
    
    multiplexer.disconnect()
}
```

#### Device Detection and Scanning

The library provides comprehensive device detection capabilities using `i2cdetect`-style probing.
This allows you to discover I2C devices without needing to know their specific protocols - just like
the Linux `i2cdetect` command.

##### Direct I2C Bus Scanning (like i2cdetect)

```kotlin
// Direct bus scanning - equivalent to running "i2cdetect -y 0"
val detectResult = I2CDetect.performI2CDetect("/dev/i2c-0")

// Print the familiar i2cdetect table format
println(detectResult.formattedTable)
/*
Output:
     0  1  2  3  4  5  6  7  8  9  a  b  c  d  e  f
00:          -- -- -- -- -- -- -- -- -- -- -- -- --
10: -- -- -- -- -- -- -- -- -- -- -- -- -- -- -- --
20: -- -- -- -- -- -- -- -- -- -- -- -- -- -- -- --
30: -- -- -- -- -- -- -- -- -- 39 -- -- -- -- -- --
40: -- -- -- -- 44 -- -- -- -- -- -- -- -- -- -- --
50: -- -- -- -- -- -- -- -- -- -- -- -- -- -- -- --
60: -- -- -- -- -- -- -- -- -- -- -- -- -- -- -- --
70: 70 -- -- -- -- -- -- --
*/

// Get detected devices with type information
println("Found devices:")
detectResult.detectedDevices.forEach { device ->
    println("${device.getAddressHex()}: ${device.deviceType ?: "Unknown device"}")
}
// Output:
// 0x39: AS7343 Spectral Sensor
// 0x44: SHT40 Temperature/Humidity Sensor
// 0x70: TCA9548 I2C Multiplexer

// Quick check if specific device is present
if (I2CDetect.isDevicePresent("/dev/i2c-0", 0x39)) {
    println("AS7343 sensor detected on bus!")
}
```

##### Multiplexer Channel Scanning

```kotlin
val multiplexer = TCA9548Multiplexer("/dev/i2c-0", 0x70)

if (multiplexer.connect()) {
    // Scan all channels for devices
    val scanResults = multiplexer.scanAllChannels()
    
    println("Found ${scanResults.getTotalDeviceCount()} devices:")
    for (channel in scanResults.getActiveChannels()) {
        val devices = scanResults.getDevicesOnChannel(channel)
        println("Channel $channel: ${devices.joinToString { it.getAddressHex() }}")
    }
    
    // Find a specific device across all channels
    val as7343Channels = multiplexer.findDevice(0x39) // AS7343 sensor
    println("AS7343 sensors found on channels: $as7343Channels")
    
    // Check if a specific device is on a specific channel
    val hasDevice = multiplexer.isDeviceOnChannel(0x44, 2) // SHT40 on channel 2
    println("SHT40 on channel 2: $hasDevice")
    
    // Scan a single channel
    val channel0Devices = multiplexer.scanChannel(0)
    channel0Devices.forEach { device ->
        println("Found: $device") // Includes device type if recognized
    }
    
    multiplexer.disconnect()
}
```

##### Comprehensive Scanning (Direct Bus + Multiplexer Channels)

```kotlin
val multiplexer = TCA9548Multiplexer("/dev/i2c-0", 0x70)

if (multiplexer.connect()) {
    // Scan both direct bus and all multiplexer channels
    val comprehensiveResult = multiplexer.scanComprehensive()
    
    println(comprehensiveResult.getSummary())
    // Output: "Comprehensive scan: 1 direct bus devices, 3 devices across 2 multiplexer channels"
    
    // Show what's on the direct bus (like i2cdetect would show)
    println("Direct bus devices:")
    comprehensiveResult.directBusDevices.forEach { device ->
        println("  ${device.getAddressHex()}: ${device.deviceType ?: "Unknown"}")
    }
    
    // Show what's on multiplexer channels
    println("Multiplexer channel devices:")
    for (channel in comprehensiveResult.channelDeviceMap.getActiveChannels()) {
        val devices = comprehensiveResult.channelDeviceMap.getDevicesOnChannel(channel)
        println("  Channel $channel: ${devices.map { "${it.getAddressHex()} (${it.deviceType ?: "Unknown"})" }}")
    }
    
    // Get i2cdetect-style table for just the direct bus
    println("Direct bus table:")
    println(multiplexer.getDirectBusTable())
    
    multiplexer.disconnect()
}
```

#### Advanced Scanning Options

```kotlin
val multiplexer = TCA9548Multiplexer("/dev/i2c-0", 0x70)

if (multiplexer.connect()) {
    // Custom scan configuration
    val config = ScanConfig(
        startAddress = 0x10,        // Start scanning from 0x10
        endAddress = 0x60,          // End scanning at 0x60
        skipAddresses = listOf(0x50, 0x51), // Skip EEPROM addresses
        timeoutMs = 50              // 50ms timeout per address
    )
    
    // Scan specific channels with custom config
    val results = multiplexer.scanChannels(
        channels = listOf(0, 2, 4),
        config = config
    )
    
    // Quick check if any devices are present
    if (multiplexer.hasAnyDevices()) {
        println("At least one device detected")
    }
    
    multiplexer.disconnect()
}
```

#### Automatic Device Configuration

```kotlin
val multiplexer = TCA9548Multiplexer("/dev/i2c-0", 0x70)

if (multiplexer.connect()) {
    val scanResults = multiplexer.scanAllChannels()
    val sensors = mutableListOf<I2CSensor>()
    
    // Automatically create sensors based on detected devices
    for (device in scanResults.getAllDevices()) {
        when (device.address) {
            0x39 -> {
                // Create AS7343 sensor on detected channel
                val sensor = AS7343Sensor("/dev/i2c-0", multiplexer, device.channel)
                sensors.add(sensor)
                println("Created AS7343 sensor on channel ${device.channel}")
            }
            0x44 -> {
                // Create SHT40 sensor on detected channel
                val sensor = SHT40Sensor("/dev/i2c-0", multiplexer, device.channel)
                sensors.add(sensor)
                println("Created SHT40 sensor on channel ${device.channel}")
            }
        }
    }
    
    // Use the automatically configured sensors
    sensors.forEach { sensor ->
        if (sensor.connect()) {
            // Read data from sensor
            sensor.disconnect()
        }
    }
    
    multiplexer.disconnect()
}
```

### I2CSensorBus provides a reusable I/O Loop

The [I2CSensorBus class](src/main/java/com/layer/i2c/I2CSensorBus.kt) provides an easy interface for
getting
sensor readings without implementing all of the device detection, multiplexer management and thread
safety concerns. This class implements a reusable IO loop that runs in a dedicated IO thread and
coroutine context.

To use the sensor bus you simply create an instance by calling the
static constructor:

```kotlin
/*        open /dev/i2c-0               */
val port0 = I2CSensorBus.getInstance(0)
/*  start the IO loop coroutine */
port0.start()
```

I2CSensorBus takes care of reading from all of the sensors on a dedicated IO thread. The latest
reading from each sensor is made available as an immutable data structure which you can access by
calling `I2CSensorBus.getAllSensorState()` which returns a `Map<String, SensorState>` where
`sensorId` Strings are mapped to [SensorState](src/main/java/com/layer/i2c/I2CState.kt) objects.

Each device driver provides `SensorState` objects via the `getSensorState()` method
implementation in the respective `I2CSensor` sub-classes. For
example, [SHT40Sensor.kt](src/main/java/com/layer/i2c/SHT40Sensor.kt) provides this simple
implementation:

```kotlin
override fun getSensorState() = object : TemperatureSensorState {
    override val errorMessage = lastError()
    override val connected = this@SHT40Sensor.isConnected()
    override val updateTS = System.currentTimeMillis()
    override val sensorId = this@SHT40Sensor.toString()
    override val temperature = this@SHT40Sensor.temperature
    override val humidity = this@SHT40Sensor.humidity
}
```

Here is a detailed example of of using I2CSensorBus with multiple i2c ports and multiple sensors
attached:

```kotlin
private fun startWatchingSensors() {
    sensorMonitoringJob = CoroutineScope(Dispatchers.IO).launch {
        /*
        * Instantiate an instance for every i2c bus that
        * you need to read from:
        */
        val ports = listOf(
            I2CSensorBus.getInstance(0),  // /dev/i2c-0
            I2CSensorBus.getInstance(1),  // /dev/i2c-1
        )
        
        /*
         * Here we provide a list of device driver classes
         * that we will use. A class can be repeated in order
         * to instantiate multiple instances of the same device,
         * separated by using a multiplexer or placing them each
         * on on a separate i2c bus.
         *
         * I2CSensorBus will scan the bus looking for these
         * specific devices and the scan will be repeated periodically
         * until it finds a matching i2c device for every class
         * referenced in this list:
         */
        val expectedDeviceClasses = listOf(
            // 1 temperature sensor:
            SHT40Sensor,
            // 2 ambient light sensors:
            AS7343Sensor,
            AS7343Sensor
        )
        
        // Pass the list to `.expect()` so that I2CSensorBus knows
        // which device addresses to scan for.
        //
        // Notice that you do not specify which port each sensor is
        // attached to. The I2CSensorBus takes care of detecting them
        // wherever they are attached, directly or via an i2c multiplexer.
        I2CSensorBus.expect(expectedDeviceClasses)
        
        
        try {
            // Start the background jobs, this will create 1 coroutine per port and a single dedicated IO thread for all I2C operations.
            for (port in ports) {
                port.start()
            }
            
            while (isActive) {
                // Here's an example of repeatedly reading the sensor state:
                
                // sensorData is immutable, each time you call
                // getAllSensorState it returns a structure with
                // the newest reading from each active sensor:
                val sensorData = I2CSensorBus.getAllSensorState()
                        .values
                        .toList()
                // the data can be filtered and iterated conveniently:
                sensorData.filter { it.connected }.forEach {
                    // do something with sensor readings:
                    when (it) {
                        is TemperatureSensorState -> {
                            // do something with SHT40Sensor readings
                            val temperature = it.temperature.toFloat()
                            val humidity = it.humidity.toFloat()
                        }
                        is ColorSensorState       -> {
                            // do something with AS7343Sensor readings...
                        }
                    }
                }
            
            }
        } finally {
            // clean up in case of a CancellationException or other
            // unexpected / uncaught exception.
            
            for (port in ports) {
                // cancel the coroutines when they are no longer needed:
                port.cancel()
            }
        }
    }
}
```

Note that the coroutine and `while(isActive)` loop in the above example are not actually necessary
in order to utilize I2CSensorBus. You can just call `.start()` on one or more ports and then call
`I2CSensorBus.getAllSensorState()` as needed. After you call `.start()` the I2CSensorBus has already
launched a background thread that is constantly polling the sensors for new data so an additional
polling loop is somewhat supurfulous.

## API Documentation

### AS7343Sensor

High-level interface for the AS7343 spectral sensor.

- `connect()`: Opens a connection to the sensor and initializes it
- `disconnect()`: Closes the connection and powers down the sensor
- `readSpectralData()`: Abstract method to read spectral channels
- `isReady()`: Checks if the sensor is connected and initialized

### AS7341Sensor

High-level interface for the AS7341 spectral sensor.

- `readSpectralData()`: Reads all spectral channels (F1-F8, Clear, NIR)
- `readAllChannels()`: Reads all spectral channels with given file descriptor

### AS7343Sensor

High-level interface for the AS7343 spectral sensor.

- `readSpectralData()`: Reads all spectral channels
- `readSpectralDataOnce()`: Connects, reads data, and filters to primary channels
- `readAllChannels()`: Reads all 18 spectral channels internally

### TCA9548Multiplexer

I2C multiplexer interface for managing multiple sensors on the same bus.

- `connect()`: Connect to the multiplexer
- `disconnect()`: Disconnect from the multiplexer
- `enableChannel(channel: Int)`: Enable a specific channel
- `disableChannel(channel: Int)`: Disable a specific channel
- `selectChannel(channel: Int)`: Select a channel exclusively (disable others)
- `setChannelMask(mask: Int)`: Set channel enable mask directly
- `getChannelMask()`: Get current channel enable mask
- `isChannelEnabled(channel: Int)`: Check if a channel is enabled
- `getEnabledChannels()`: Get list of enabled channels
- `enableAllChannels()`: Enable all channels
- `disableAllChannels()`: Disable all channels
- `executeOnChannel(channel, operation)`: Execute operation on specific channel
- `executeWithChannels(channels, operation)`: Execute operation with multiple channels
- `isConnected()`: Check multiplexer connectivity
- `reset()`: Reset multiplexer to known state
- `scanChannel(channel, config?)`: Scan single channel for devices
- `scanAllChannels(config?)`: Scan all channels for devices
- `findDevice(address)`: Find which channels contain specific device
- `isDeviceOnChannel(address, channel)`: Check if device is on specific channel
- `scanChannels(channels?, config?)`: Scan specific channels with options
- `hasAnyDevices()`: Quick check if any devices are present

### I2cNative

JNI interface to native I2C functions.

- `openBus(busName: String, deviceAddress: Int)`: Opens I2C bus
- `closeBus(fd: Int)`: Closes I2C bus
- `writeByte(fd: Int, address: Int, value: Int)`: Writes a byte
- `writeWord(fd: Int, address: Int, word: Int)`: Writes a word
- `readWord(fd: Int, address: Int)`: Reads a word
- `readAllBytes(fd: Int, address: Int)`: Reads multiple bytes
- `switchDeviceAddress(fd: Int, address: Int)`: Switch to different device on same bus
- `scanAddress(fd: Int, address: Int)`: Scan for device at specific I2C address

## Requirements

- Android API level 26+ (Android 8.0+)
- Device with I2C buses

## Publishing

To publish a new version to GitHub Packages:

```bash
# Set GitHub credentials
export GITHUB_USER=your-github-username
export GITHUB_TOKEN=your-github-token

# Publish
./gradlew publish
```

## License

This project is licensed under the Apache License 2.0 - see the LICENSE file for details.
