cmake_minimum_required(VERSION 3.4.1)

project("com.layer.i2c")

add_library(
        I2cNative
        SHARED
        I2cNative.c)

find_library(
        log-lib
        log)

target_link_libraries(
        I2cNative
        ${log-lib})