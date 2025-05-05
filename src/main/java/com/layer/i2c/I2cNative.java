package com.layer.i2c;

/**
 * Native interface for I2C bus communication.
 */
public class I2cNative {

    private I2cNative() {
        // we do not allow constructing I2cNative objects
    }

    static {
        System.loadLibrary("I2cNative");
    }

    /**
     * Opens linux file for reading/writing, returning a file handle.
     *
     * @param busName file name of device. For example, "/dev/i2c-0" or "/dev/i2c-1".
     * @param deviceAddress address of the I2C device on the bus.
     * @return file descriptor or i2c bus, or -1 if opening failed.
     */
    public static native int openBus(String busName, int deviceAddress);

    /**
     * Closes linux file, associated with i2c bus.
     *
     * @param fd file descriptor
     * @return 0 if successful, -1 if error
     */
    public static native int closeBus(int fd);

    /**
     * Writes one byte to a specified address inside the i2c bus.
     *
     * @param fd            file descriptor of i2c bus
     * @param address       address in the i2c bus
     * @param value         byte to be written to the device
     * @return result of operation. Zero is OK, everything less than a zero means there was an error.
     */
    public static native int writeByte(int fd, int address, int value);

    /**
     * Writes number of bytes to a specified address inside the i2c bus.
     *
     * @param fd            file descriptor of i2c bus
     * @param address       address in the device
     * @param word         two bytes to be written
     * @return result of operation. Zero is OK, everything less than a zero means there was an error.
     */
    public static native int writeWord(int fd, int address, int word);

    /**
     * Reads one byte from a specified address inside the i2c bus.
     *
     * @param fd            file descriptor of i2c bus
     * @param address       address in the device
     * @return number from 0 to 65535 if reading was successful (2 bytes). Negative number if reading failed.
     */
    public static native int readWord(int fd, int address);

    /**
     * Reads number of bytes from a specified address inside the i2c bus.
     *
     * @param fd            file descriptor of i2c bus
     * @param address       address in the device
     * @return number that represents 4 bytes read. Negative number if reading failed.
     */
    public static native long readAllBytes(int fd, int address);
    
    /**
     * Reads a specific number of bytes from the current I2C device into the provided buffer.
     * Note: This method reads from the current device (set by openBus) without specifying
     * a register address. It's useful for reading data after a command has been sent.
     *
     * @param fd         file descriptor of i2c bus
     * @param buffer     buffer to store the data
     * @param length     number of bytes to read
     * @return number of bytes read, or negative value if error
     */
    public static native int readRawBytes(int fd, byte[] buffer, int length);

    /**
     * Writes one byte inside the i2c bus.
     *
     * @param fd            file descriptor of i2c bus
     * @param value         byte to be written to the device
     * @return result of operation. Zero is OK, everything less than a zero means there was an error.
     */
    public static native int write(int fd, int value);
    
    /**
     * Switches the I2C device address for an already open file descriptor.
     * This allows multiple devices to share the same I2C bus.
     *
     * @param fd            file descriptor of i2c bus
     * @param deviceAddress address of the I2C device to switch to
     * @return 0 if successful, -1 if error
     */
    public static native int switchDeviceAddress(int fd, int deviceAddress);
}