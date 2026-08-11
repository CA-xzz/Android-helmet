cmake_minimum_required(VERSION 3.22.1)
project(helmet_serial LANGUAGES CXX)

add_library(helmet_serial SHARED serial_port.cpp)

find_library(log-lib log)
target_link_libraries(helmet_serial PRIVATE ${log-lib})
