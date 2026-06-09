# TCS3448 spectral sensor support — design

**Date:** 2026-06-09
**Author:** Gilles Dubuc (with Claude)
**Repos affected:** `layer-android-i2c-library` (changes), `layer-android-offline` (one change), `layer-android-diagnostics` (no change)

## Goal

Support a new spectral chipset, the **ams OSRAM TCS3448**, on hardware variants that ship
`SHT40 + 2×TCS3448` in place of the legacy `SHT40 + 2×AS7343`. The TCS3448 is
protocol-identical to the AS7343 but lives at a different I²C address.

## Datasheet verification (TCS3448_DS001121_1-01.pdf)

Confirmed directly against the datasheet text stream:

- **I²C slave address `0x59`** — Table 8 "TCS3448 I²C slave address", 7-bit addressing.
  (AS7343 is `0x39`, so there is **no address collision** with the existing part.)
- **Register map matches AS7343** — ATIME `0x81`, ASTEP `0xD4/0xD5`, CFG1 `0xC6`,
  CONTROL `0xFA`, GPIO `0x6B`, ENABLE `0x80`, CONFIG0 bank-select, STATUS banks, ID `0x5A`.
- **ID register (`0x5A`) default value `0b10000001` = `0x81`** — identical to the driver's
  `AS7343_ID_VALUE`. Moreover `isSensorResponsive()` only checks that the register can be
  *read* (it does not compare the value), and `AS7343_ID_VALUE` is not used in any comparison
  anywhere. So nothing in the driver rejects a TCS3448.

**Conclusion:** the existing `AS7343Sensor` driver logic is byte-for-byte applicable to the
TCS3448. The only thing that differs is the I²C address.

## Architecture context (how detection works today)

- `CommonI2CDevices` (`DeviceDetection.kt`) maps an I²C address → a `SensorFactory`.
  Detection is **purely address-based**. AS7341 needed a serial-number override only because it
  *collides* with AS7343 at `0x39`; TCS3448 at `0x59` does not collide, so **no override and no
  serial allowlist are needed**.
- `I2CSensorBus.expect(...)` drives only one behaviour: the rescan trigger
  `allSensors.size < expectedSensors.size`. `mappedSensors` is written but never read, and the
  expectation *types* are never matched functionally. A new device is `SHT40 + 2×TCS3448` = **3
  devices**, the same count as the legacy `SHT40 + 2×AS7343`. The existing default
  `expect(listOf(SHT40Sensor, AS7343Sensor, AS7343Sensor))` therefore already produces the correct
  count → **`I2CSensorBus.kt` needs no change**.
- Sensor identity and state already key off `sensorAddress`/class:
  `deviceUniqueId()` → `busPath:channel:0x59`; `toString()` → `javaClass.simpleName`;
  `ColorSensorState.sensorId` is the `toString()`. So two TCS3448s on different mux channels get
  unique ids, and the diagnostics UI heading (`SensorHistogram` renders `sensorId`) will read
  `"TCS3448Sensor (…)"` automatically.

## Design

### 1. `layer-android-i2c-library` (the substantive change)

**a. New file `src/main/java/com/layer/i2c/TCS3448Sensor.kt`**

`open class TCS3448Sensor` extends `AS7343Sensor`, overriding only the address and the factory:

```kotlin
package com.layer.i2c

/**
 * High-level interface for the TCS3448 spectral sensor.
 * Protocol-identical to the AS7343 (same register map and ID value); differs only in
 * I²C address (0x59 vs 0x39). See TCS3448 datasheet Table 8.
 */
open class TCS3448Sensor : AS7343Sensor {
    constructor(busPath: String) : super(busPath)
    constructor(busPath: String, multiplexer: TCA9548Multiplexer, multiplexerChannel: Int)
        : super(busPath, multiplexer, multiplexerChannel)

    override val sensorAddress: Int = 0x59

    companion object : SensorFactory<I2CSensor> {
        override fun create(busPath: String): TCS3448Sensor = TCS3448Sensor(busPath)
    }
}
```

**b. `DeviceDetection.kt` — register `0x59`**

- Add to `knownDevices`: `0x59 to "TCS3448 Spectral Sensor"`.
- Add to `knownDeviceClasses`: `0x59 to TCS3448Sensor`.

**c. Expose chip model on `ColorSensorState` (for the offline DB label)**

- `I2CState.kt` — add to the `ColorSensorState` interface:
  `val sensorType: String`.
- `I2CSensor.kt` — add a derived, overridable property on the base class:
  `open val sensorTypeName: String get() = javaClass.simpleName.removeSuffix("Sensor")`
  (yields `"AS7343"`, `"TCS3448"`, `"AS7341"`).
- `AS7343Sensor.kt` `getSensorState()` — add `override val sensorType = sensorTypeName`.
  Because `TCS3448Sensor` inherits `getSensorState()`, a TCS3448 instance reports `"TCS3448"`.
- `AS7341Sensor.kt` `getSensorState()` — also implements `ColorSensorState`, so it must add
  `override val sensorType = sensorTypeName` to satisfy the new interface member (yields `"AS7341"`).

These are the only two `ColorSensorState` implementers in the codebase (verified by grep across all
three repos). Consumers that don't use the field (diagnostics) are unaffected.

### 2. `layer-android-offline` (one change)

`app/.../AndroidSensorHardwarePorts.kt:160` currently hardcodes the chip model that gets persisted
to the DB `sensorType` column:

```kotlin
fun getSensorType(sensorIndex: Int): String =
    if (sensorIndex < colorSensorStates.size) "AS7343" else "Unknown"
```

Change it to read the real model from the library-exposed field:

```kotlin
fun getSensorType(sensorIndex: Int): String =
    colorSensorStates.getOrNull(sensorIndex)?.sensorType ?: "Unknown"
```

This is the only offline change. Everything else in offline (auto-detect, sensor count,
auto-brightness, failure monitoring, channel-data processing) is sensor-type-agnostic and works
unchanged. The misnamed helper `getWorkingAS7343SensorCount()` is left as-is (out of scope; it is
generic and renaming it would churn unrelated call sites).

### 3. `layer-android-diagnostics` (no change)

Verified: all sensor rendering is polymorphic on `ColorSensorState`, the histogram heading already
displays the sensor `toString()` (which begins with the class name), and detection/count are
delegated to the library. New TCS3448 sensors will appear automatically with the correct
`"TCS3448Sensor (…)"` heading. No code change.

## Explicitly out of scope (YAGNI)

- No `I2CSensorBus.kt` change (no serial allowlist, no `expect()` variant) — address disambiguates
  and the device count is unchanged.
- No new device serial-number allowlists. (New devices use port 0 by default and are not in any
  legacy list.)
- No rename of `getWorkingAS7343SensorCount()` / related comments.

## Testing / verification

- **i2c-library:** `./gradlew :layer-android-i2c-library:assemble` (or the module's unit test task)
  compiles; confirm the new `ColorSensorState.sensorType` member is satisfied by both
  `AS7343Sensor` and `AS7341Sensor`.
- **offline:** `./gradlew :app:assembleDebug` compiles against the updated library; `getSensorType`
  returns `"TCS3448"` / `"AS7343"` appropriately.
- **diagnostics:** `./gradlew :app:assembleDebug` compiles unchanged against the updated library.
- On-device (when hardware is available): a TCS3448 device enumerates two color sensors at `0x59`
  on the mux channels, the diagnostics histogram heading reads `TCS3448Sensor`, and the offline DB
  records `sensorType = "TCS3448"`.

## Git / PR plan

All three repos default to `main` (not `master`). Each PR branches from up-to-date `main`.

1. **i2c-library PR** — `feat: add TCS3448 spectral sensor (AS7343-compatible, addr 0x59)`.
   Branch `feat/tcs3448-support` off `main`. Contains the new class, registry entries, and the
   `ColorSensorState.sensorType` exposure. **Merge this first.**
2. **offline PR** — `feat: record real spectral chip model (TCS3448 support)`.
   Branch `feat/tcs3448-sensor-type` off `main`. One-line change in `getSensorType`. **Depends on
   the i2c-library PR** (uses the new `sensorType` field); the PR body must call out the ordering.
   Because offline includes the library as a local composite build (`projectDir = file("../layer-android-i2c-library")`),
   local builds resolve against the sibling working tree — note this for CI/reviewers.
3. **diagnostics** — no PR.

The stray self-referential symlink `layer-android-i2c-library/layer-android-i2c-library -> .` in the
library working tree is untracked and unrelated; it will not be added to any commit.
