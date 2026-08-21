cmake_minimum_required(VERSION 3.22)
project(tv_reader)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

if(NOT CMAKE_SYSTEM_NAME MATCHES "Android")
    # Fallback build for host testing (won't work for game reading)
    message(STATUS "Building for host (not Android) - reader will not work with game")
endif()

add_executable(tv_reader tv_reader.c)

if(NOT CMAKE_SYSTEM_NAME MATCHES "Android")
    target_compile_definitions(tv_reader PRIVATE HOST_BUILD=1)
    target_link_libraries(tv_reader PRIVATE m pthread)
endif()
