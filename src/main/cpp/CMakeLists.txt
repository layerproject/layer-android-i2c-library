cmake_minimum_required(VERSION 3.4.1)

project("com.layer.i2c")

add_library(
        I2cNative
        SHARED
        I2cNative.c)

find_library(
        log-lib
        log)

# Set linker flags for 16KB page alignment (required for Android 15+)
target_link_options(I2cNative
        PRIVATE
        "-Wl,-z,max-page-size=16384")

target_link_libraries(
        I2cNative
        ${log-lib})