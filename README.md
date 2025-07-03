# Layer Android I2C Library

An Android library for I2C communication and AS73XX spectral sensor interactions. This library provides a clean API for communicating with I2C devices, with specific support for the AS7341 and AS7343 spectral sensors.

## Features

- Native C code for I2C communication
- JNI interface for Android
- High-level Kotlin API for sensor interaction
- Support for AS7341 and AS7343 spectral sensors
- Common interface for all sensor types

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