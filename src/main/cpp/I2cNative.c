#include <fcntl.h>
#include <sys/ioctl.h>
#include <linux/i2c-dev.h>
#include <linux/i2c.h>
#include <unistd.h>
#include <sched.h>
#include <time.h>
#include <errno.h>

#include <syslog.h>
#include <jni.h>

#include "I2cNative.h"

// Minimum interval between I2C operations in nanoseconds (250 microseconds).
// Prevents interrupt clustering that causes rendering jank.
#define MIN_I2C_INTERVAL_NS 250000L

static struct timespec last_i2c_time = {0, 0};

static inline void i2c_rate_limit(void)
{
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);

    if (last_i2c_time.tv_sec != 0 || last_i2c_time.tv_nsec != 0) {
        long elapsed_ns = (now.tv_sec - last_i2c_time.tv_sec) * 1000000000L
                        + (now.tv_nsec - last_i2c_time.tv_nsec);
        if (elapsed_ns < MIN_I2C_INTERVAL_NS) {
            struct timespec sleep_time;
            long remaining = MIN_I2C_INTERVAL_NS - elapsed_ns;
            sleep_time.tv_sec = 0;
            sleep_time.tv_nsec = remaining;
            nanosleep(&sleep_time, NULL);
        }
    }
}

static inline void i2c_post_operation(void)
{
    clock_gettime(CLOCK_MONOTONIC, &last_i2c_time);
    sched_yield();
}

static inline __s32 i2c_smbus_access(int file, char read_write, __u8 command
        , int size, union i2c_smbus_data *data)
{
    struct i2c_smbus_ioctl_data args;

    i2c_rate_limit();

    args.read_write = read_write;
    args.command = command;
    args.size = size;
    args.data = data;
    __s32 result = ioctl(file, I2C_SMBUS, &args);

    i2c_post_operation();

    return result;
}

/**
 * Switches the I2C device on an already open file descriptor
 * This allows multiple devices to share the same I2C bus
 */
static inline int switch_i2c_device(int fd, __u8 deviceAddress)
{
    if (fd < 0) {
        return -1;
    }
    i2c_rate_limit();
    int result = ioctl(fd, I2C_SLAVE, deviceAddress);
    i2c_post_operation();
    return result;
}

static inline __s32 i2c_smbus_read_i2c_block_data(int file, __u8 command,
                                                  __u8 length, __u8 *values)
{
    union i2c_smbus_data data;

    // Cap at 31 bytes to always use I2C_SMBUS_I2C_BLOCK_DATA.
    // I2C_SMBUS_I2C_BLOCK_BROKEN (used for length==32) is unreliable
    // on some Qualcomm I2C controllers and can corrupt sensor state.
    if (length > 31) {
        length = 31;
    }

    data.block[0] = length;
    if (i2c_smbus_access(file, I2C_SMBUS_READ, command
            , I2C_SMBUS_I2C_BLOCK_DATA
            , &data))
    {
        return -1;
    } else {
        for (int i = 1; i <= data.block[0]; i++) {
            values[i - 1] = data.block[i];
        }

        return data.block[0];
    }
}

static inline __s32 i2c_smbus_write_byte_data(int file, __u8 command, __u8 value)
{
    union i2c_smbus_data data;
    data.byte = value;
    return i2c_smbus_access(file, I2C_SMBUS_WRITE, command, I2C_SMBUS_BYTE_DATA, &data);
}

static inline __s32 i2c_smbus_write_word_data(int file, __u8 command, __u16 value)
{
    union i2c_smbus_data data;
    data.word = value;
    return i2c_smbus_access(file, I2C_SMBUS_WRITE, command, I2C_SMBUS_WORD_DATA, &data);
}

//JNI part begins here

JNIEXPORT jint JNICALL Java_com_layer_i2c_I2cNative_openBus
        (JNIEnv *env, jclass jcl, jstring busName, jint deviceAddress)
{
    char fileName[256];
    __u8 devAddr = deviceAddress & 0xFF;
    int len = (*env)->GetStringLength(env, busName);
    (*env)->GetStringUTFRegion(env, busName, 0, len, fileName);

    openlog("I2cNative", LOG_PID | LOG_CONS, LOG_USER);
    syslog(LOG_INFO, "I2C Log: %s", fileName);

    int fd = open(fileName, O_RDWR);

    syslog(LOG_INFO, "I2C FD: %d", fd);
    closelog();
    if (fd < 0 || ioctl(fd, I2C_SLAVE, deviceAddress) < 0 ) {
        return -1;
    } else {
        return fd;
    }
}

/**
 * Switches the I2C device address for an already open file descriptor.
 * This allows multiple devices to share the same I2C bus.
 */
JNIEXPORT jint JNICALL Java_com_layer_i2c_I2cNative_switchDeviceAddress
        (JNIEnv *env, jclass jcl, jint fd, jint deviceAddress)
{
    __u8 devAddr = deviceAddress & 0xFF;
    
    openlog("I2cNative", LOG_PID | LOG_CONS, LOG_USER);
    syslog(LOG_DEBUG, "Switching I2C device address to 0x%02X on FD: %d", devAddr, fd);
    closelog();
    
    int result = switch_i2c_device(fd, devAddr);
    if (result < 0) {
        return -1;
    } else {
        return 0;
    }
}

JNIEXPORT jint JNICALL Java_com_layer_i2c_I2cNative_closeBus
        (JNIEnv *env, jclass jcl, jint fd)
{
    return close (fd);
}

JNIEXPORT jint JNICALL Java_com_layer_i2c_I2cNative_writeByte
        (JNIEnv *env, jclass jcl, jint fd, jint address, jint b)
{
    __u8 addr = address & 0xFF;
    __u8 byte = b       & 0xFF;
    return i2c_smbus_write_byte_data(fd, addr, byte);
}

JNIEXPORT jint JNICALL Java_com_layer_i2c_I2cNative_writeWord
        (JNIEnv *env, jclass jcl, jint fd, jint address, jint word)
{
    __u8  addr = address & 0xFF;
    __u16 value = word & 0xFFFF;
    return i2c_smbus_write_word_data(fd, addr, value);
}

JNIEXPORT jint JNICALL Java_com_layer_i2c_I2cNative_readWord
        (JNIEnv *env, jclass jcl, jint fd, jint address)
{
    union i2c_smbus_data data;
    __u8  addr = address & 0xFF;
    if (i2c_smbus_access(fd, I2C_SMBUS_READ, addr, I2C_SMBUS_WORD_DATA, &data)) {
        return -1;
    } else {
        return (jint)(0x0FFFF & data.word);
    }
}

JNIEXPORT jlong JNICALL Java_com_layer_i2c_I2cNative_readAllBytes
        (JNIEnv *env, jclass jcl, jint fd, jint address)
{
    openlog("I2cNative", LOG_PID | LOG_CONS, LOG_USER);
    syslog(LOG_DEBUG, "I2C readAllBytes");
    closelog();

    __u8 addr = address & 0xFF;
    __u8 buffer[4] = {0};
    i2c_smbus_read_i2c_block_data(fd, addr, 4, buffer);
    return (jlong)(buffer[3] << 24 | buffer[2] <<  16 | buffer[1] << 8 | buffer[0]);
}

JNIEXPORT jint JNICALL Java_com_layer_i2c_I2cNative_readRawBytes
        (JNIEnv *env, jclass jcl, jint fd, jbyteArray jbuffer, jint length)
{
    openlog("I2cNative", LOG_PID | LOG_CONS, LOG_USER);
    syslog(LOG_DEBUG, "I2C readRawBytes");
    closelog();

    if (length <= 0 || length > 32) {
        return -1; // Invalid length
    }
    
    // Create a local buffer for reading
    __u8 buffer[32] = {0};
    
    // Read data directly from the I2C device
    // This is for reading after a command has been sent
    i2c_rate_limit();
    int bytesRead = read(fd, buffer, length);
    i2c_post_operation();

    openlog("I2cNative", LOG_PID | LOG_CONS, LOG_USER);
    syslog(LOG_DEBUG, "I2C %d bytes read on FD: %d", bytesRead, fd);
    closelog();
    
    if (bytesRead <= 0) {
        return -1; // Error reading
    }
    
    // Copy data to the Java byte array
    (*env)->SetByteArrayRegion(env, jbuffer, 0, bytesRead, (jbyte*)buffer);
    
    return bytesRead;
}

/**
 * Reads a block of bytes starting from a register address using SMBus I2C block read.
 * Handles reads larger than 32 bytes by performing multiple sequential reads.
 *
 * @param fd       File descriptor for the I2C bus
 * @param reg      Starting register address
 * @param jbuffer  Java byte array to store the data
 * @param length   Number of bytes to read
 * @return Total number of bytes read, or -1 if error
 */
JNIEXPORT jint JNICALL Java_com_layer_i2c_I2cNative_readBlockData
        (JNIEnv *env, jclass jcl, jint fd, jint reg, jbyteArray jbuffer, jint length)
{
    if (length <= 0 || length > 256) {
        return -1;
    }

    __u8 buffer[256] = {0};
    int totalRead = 0;
    int remaining = length;
    __u8 currentReg = reg & 0xFF;

    while (remaining > 0) {
        __u8 chunkSize = remaining > 32 ? 32 : (__u8)remaining;
        __s32 bytesRead = i2c_smbus_read_i2c_block_data(fd, currentReg, chunkSize, buffer + totalRead);
        if (bytesRead <= 0) {
            if (totalRead == 0) {
                return -1; // First read failed
            }
            break; // Return what we have so far
        }
        totalRead += bytesRead;
        remaining -= bytesRead;
        currentReg += bytesRead;
    }

    (*env)->SetByteArrayRegion(env, jbuffer, 0, totalRead, (jbyte*)buffer);
    return totalRead;
}

JNIEXPORT jint JNICALL Java_com_layer_i2c_I2cNative_write
        (JNIEnv *env, jclass jcl, jint fd, jint value)
{
    __u8 byte = value & 0xFF;
    i2c_rate_limit();
    int result = write(fd, &byte, 1);
    i2c_post_operation();
    return result;
}

/**
 * SMBus Quick Write probe - used by i2cdetect for device detection
 * This is the most compatible method for detecting I2C devices
 */
static inline __s32 i2c_smbus_quick_write(int file, __u8 deviceAddress)
{
    // Switch to the device address first
    if (switch_i2c_device(file, deviceAddress) < 0) {
        return -1;
    }
    
    // Perform SMBus Quick Write - just sends address + write bit
    return i2c_smbus_access(file, I2C_SMBUS_WRITE, 0, I2C_SMBUS_QUICK, NULL);
}

/**
 * SMBus Read Byte probe - alternative method for device detection
 * Some devices respond better to read operations
 */
static inline __s32 i2c_smbus_read_byte_probe(int file, __u8 deviceAddress)
{
    union i2c_smbus_data data;
    
    // Switch to the device address first
    if (switch_i2c_device(file, deviceAddress) < 0) {
        return -1;
    }
    
    // Perform SMBus Read Byte
    return i2c_smbus_access(file, I2C_SMBUS_READ, 0, I2C_SMBUS_BYTE, &data);
}

/**
 * Scans for a device at a specific I2C address using i2cdetect-style probing.
 * Uses SMBus Quick Write first (most compatible), falls back to Read Byte if needed.
 * 
 * @param fd File descriptor for the I2C bus
 * @param deviceAddress 7-bit I2C address to probe (0x08-0x77)
 * @return 1 if device responds, 0 if no device found, -1 if error
 */
JNIEXPORT jint JNICALL Java_com_layer_i2c_I2cNative_scanAddress
        (JNIEnv *env, jclass jcl, jint fd, jint deviceAddress)
{
    __u8 devAddr = deviceAddress & 0x7F; // Ensure 7-bit address
    
    // Skip reserved addresses (like i2cdetect does)
    if (devAddr < 0x08 || devAddr > 0x77) {
        return 0; // Not a valid user device address
    }
    
    // Skip addresses that are typically reserved or problematic
    // 0x00-0x07: General call and reserved addresses
    // 0x78-0x7F: Reserved addresses
    if (devAddr <= 0x07 || devAddr >= 0x78) {
        return 0;
    }
    
    openlog("I2cNative", LOG_PID | LOG_CONS, LOG_USER);
    syslog(LOG_INFO, "Scanning I2C address 0x%02X on FD: %d", devAddr, fd);
    
    // Method 1: Try SMBus Quick Write (most compatible, used by i2cdetect -q)
    __s32 result = i2c_smbus_quick_write(fd, devAddr);
    if (result == 0) {
        syslog(LOG_INFO, "Device found at 0x%02X using Quick Write", devAddr);
        closelog();
        return 1; // Device responded to Quick Write
    }
    
    // Method 2: Try SMBus Read Byte (alternative method, used by i2cdetect -r) 
    result = i2c_smbus_read_byte_probe(fd, devAddr);
    if (result >= 0) {
        syslog(LOG_INFO, "Device found at 0x%02X using Read Byte", devAddr);
        closelog();
        return 1; // Device responded to Read Byte
    }
    
    // No response from either method
    syslog(LOG_DEBUG, "No device found at 0x%02X", devAddr);
    closelog();
    return 0;
}

/**
 * Attempts to recover a frozen I2C bus using Linux kernel recovery mechanisms.
 * This function tries multiple recovery approaches to restore bus functionality:
 * 1. I2C_RECOVER ioctl (if supported by kernel/driver)
 * 2. Bus reset through re-initialization
 * 3. Force device switch to try clearing stuck transactions
 * 
 * @param fd File descriptor for the I2C bus
 * @return 0 if recovery successful, -1 if recovery failed
 */
JNIEXPORT jint JNICALL Java_com_layer_i2c_I2cNative_recoverBus
        (JNIEnv *env, jclass jcl, jint fd)
{
    if (fd < 0) {
        return -1;
    }
    
    openlog("I2cNative", LOG_PID | LOG_CONS, LOG_USER);
    syslog(LOG_INFO, "Attempting I2C bus recovery on FD: %d", fd);
    
    // Method 1: Try I2C_RECOVER ioctl if supported by the kernel driver
    // This leverages kernel-level recovery mechanisms that may include:
    // - Clock pulse generation to unstick SDA line
    // - Bus state machine reset
    // - Hardware-specific recovery procedures
#ifdef I2C_RECOVER
    syslog(LOG_DEBUG, "Attempting I2C_RECOVER ioctl on FD: %d", fd);
    if (ioctl(fd, I2C_RECOVER) == 0) {
        syslog(LOG_INFO, "I2C bus recovery successful using I2C_RECOVER ioctl on FD: %d", fd);
        closelog();
        return 0;
    }
    syslog(LOG_DEBUG, "I2C_RECOVER ioctl failed or not supported on FD: %d", fd);
#endif
    
    // Method 2: Try to clear any stuck transaction by switching to general call address
    // The general call address (0x00) can sometimes help clear stuck transactions
    syslog(LOG_DEBUG, "Attempting general call address switch for recovery on FD: %d", fd);
    if (ioctl(fd, I2C_SLAVE, 0x00) == 0) {
        // Try a quick write to general call address - this may help unstick the bus
        union i2c_smbus_data data;
        int result = i2c_smbus_access(fd, I2C_SMBUS_WRITE, 0, I2C_SMBUS_QUICK, NULL);
        if (result == 0) {
            syslog(LOG_INFO, "I2C bus recovery successful using general call on FD: %d", fd);
            closelog();
            return 0;
        }
    }
    
    // Method 3: Try force-clearing any pending transactions
    // This attempts to send a STOP condition by doing a quick read/write cycle
    syslog(LOG_DEBUG, "Attempting transaction force-clear for recovery on FD: %d", fd);
    
    // Try switching to a safe address and doing minimal operations
    for (int addr = 0x08; addr <= 0x77; addr += 8) {
        if (ioctl(fd, I2C_SLAVE, addr) == 0) {
            // Try a quick operation that might help clear the bus
            union i2c_smbus_data data;
            if (i2c_smbus_access(fd, I2C_SMBUS_READ, 0, I2C_SMBUS_QUICK, NULL) == 0) {
                syslog(LOG_INFO, "I2C bus recovery successful using address probe method on FD: %d", fd);
                closelog();
                return 0;
            }
        }
    }
    
    // Method 4: Try resetting I2C functionality flags
    // Some drivers support resetting specific I2C functionality
    syslog(LOG_DEBUG, "Attempting I2C functionality reset on FD: %d", fd);
    
    // Query current functionality to ensure the bus is still operational
    unsigned long funcs;
    if (ioctl(fd, I2C_FUNCS, &funcs) == 0) {
        // If we can query functionality, the low-level driver is responsive
        // Try one more general call attempt
        if (ioctl(fd, I2C_SLAVE, 0x00) == 0) {
            syslog(LOG_INFO, "I2C bus recovery: driver responsive, attempting final general call on FD: %d", fd);
            
            // Give the bus some time to settle
            usleep(1000); // 1ms delay
            
            // Try a final quick write
            union i2c_smbus_data data;
            if (i2c_smbus_access(fd, I2C_SMBUS_WRITE, 0, I2C_SMBUS_QUICK, NULL) == 0) {
                syslog(LOG_INFO, "I2C bus recovery successful using delayed general call on FD: %d", fd);
                closelog();
                return 0;
            }
        }
    }
    
    // All recovery methods failed
    syslog(LOG_ERR, "All I2C bus recovery methods failed on FD: %d", fd);
    closelog();
    return -1;
}

/**
 * Sets the calling thread to SCHED_IDLE scheduling policy.
 * SCHED_IDLE is the absolute lowest scheduling priority in Linux —
 * the thread only runs when no other thread on the system wants CPU time.
 * Perfect for background I2C sensor polling that must never interfere with rendering.
 *
 * @return 0 if successful, -1 if error (errno will contain the reason)
 */
JNIEXPORT jint JNICALL Java_com_layer_i2c_I2cNative_setSchedIdle
        (JNIEnv *env, jclass jcl)
{
    struct sched_param param;
    param.sched_priority = 0; // SCHED_IDLE requires priority 0
    int result = sched_setscheduler(0, SCHED_IDLE, &param);
    if (result < 0) {
        openlog("I2cNative", LOG_PID | LOG_CONS, LOG_USER);
        syslog(LOG_WARNING, "Failed to set SCHED_IDLE: errno=%d", errno);
        closelog();
    }
    return result;
}