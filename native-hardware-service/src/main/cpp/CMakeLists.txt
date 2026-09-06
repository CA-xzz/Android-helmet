cmake_minimum_required(VERSION 3.22.1)
project(helmet_serial LANGUAGES CXX)

option(HELMET_ENABLE_TEST_PTY "Enable the androidTest-only PTY fixture JNI" OFF)

add_library(helmet_serial SHARED serial_port.cpp)

if(HELMET_ENABLE_TEST_PTY)
    target_compile_definitions(helmet_serial PRIVATE HELMET_ENABLE_TEST_PTY=1)
endif()

find_library(log-lib log)
target_link_libraries(helmet_serial PRIVATE ${log-lib})
