package com.layer.i2c

/**
 * High-level interface for the TCS3448 spectral sensor.
 *
 * The TCS3448 is protocol-identical to the AS7343: it shares the same register map and the same
 * ID-register value (0x81), so the entire [AS7343Sensor] driver logic applies unchanged. The only
 * difference is the I2C slave address — 0x59 instead of 0x39 (TCS3448 datasheet, Table 8) — so this
 * class overrides nothing but the address and the factory.
 */
open class TCS3448Sensor : AS7343Sensor {

    /** Constructor for direct I2C connection (no multiplexer). */
    constructor(busPath: String) : super(busPath)

    /** Constructor for multiplexed I2C connection. */
    constructor(
        busPath: String,
        multiplexer: TCA9548Multiplexer,
        multiplexerChannel: Int
    ) : super(busPath, multiplexer, multiplexerChannel)

    // TCS3448 lives at 0x59 (AS7343 is 0x39).
    override val sensorAddress: Int = 0x59

    companion object : SensorFactory<I2CSensor> {
        override fun create(busPath: String): TCS3448Sensor = TCS3448Sensor(busPath)
    }
}
