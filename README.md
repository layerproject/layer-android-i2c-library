# Layer Android I2C Library

An Android library for I2C communication and AS7341 spectral sensor interactions. This library provides a clean API for communicating with I2C devices, with specific support for the AS7341 spectral sensor.

## Features

- Native C code for I2C communication
- JNI interface for Android
- High-level Kotlin API for sensor interaction
- Support for AS7341 spectral sensors

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
                username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_USER")
                password = project.findProperty("gpr.key") as String? ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
```

Then add the dependency to your module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.layer:i2c:1.0.0")
}
```

## Usage

### Basic Usage

```kotlin
// Create a sensor instance with bus path
val sensor = AS7341Sensor("/dev/i2c-0")

// Connect to the sensor
if (sensor.connect()) {
    // Read spectral data
    val channelData = sensor.readSpectralData()
    
    // Control the LED
    sensor.controlLED(on = true, current = 30)
    
    // Disconnect when done
    sensor.disconnect()
}
```

### Advanced Usage

For more advanced usage, you can directly use the `SensorManager` class:

```kotlin
val sensorManager = SensorManager()
val fd = sensorManager.openSensor("/dev/i2c-0")

// Enable power
sensorManager.togglePower(fd, true)

// Read spectral data
val channelData = sensorManager.readAllChannels(fd)

// Close connection
sensorManager.closeSensor(fd)
```

## API Documentation

### AS7341Sensor

High-level interface for the AS7341 spectral sensor.

- `connect()`: Opens a connection to the sensor
- `disconnect()`: Closes the connection
- `readSpectralData()`: Reads all spectral channels
- `controlLED(on: Boolean, current: Int)`: Controls the sensor's built-in LED
- `isConnected()`: Checks if the sensor is connected
- `getFileDescriptor()`: Gets the I2C file descriptor

### SensorManager

Low-level manager for AS7341 sensor communication.

- `openSensor(busPath: String)`: Opens an I2C connection
- `closeSensor(fd: Int)`: Closes the connection
- `readAllChannels(fd: Int)`: Reads all spectral channels
- `togglePower(fd: Int, on: Boolean)`: Controls sensor power
- `setLEDCurrent(fd: Int, current: Int)`: Sets LED current
- `toggleLED(fd: Int, on: Boolean)`: Controls the LED

### I2cNative

JNI interface to native I2C functions.

- `openBus(busName: String, deviceAddress: Int)`: Opens I2C bus
- `closeBus(fd: Int)`: Closes I2C bus
- `writeByte(fd: Int, address: Int, value: Int)`: Writes a byte
- `writeWord(fd: Int, address: Int, word: Int)`: Writes a word
- `readWord(fd: Int, address: Int)`: Reads a word
- `readAllBytes(fd: Int, address: Int)`: Reads multiple bytes

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