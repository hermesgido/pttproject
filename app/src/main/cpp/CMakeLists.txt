cmake_minimum_required(VERSION 3.18)
project(mediasoupbridge)

add_library(mediasoupbridge SHARED mediasoup_bridge.cpp)

find_library(log-lib log)

target_link_libraries(mediasoupbridge ${log-lib})

