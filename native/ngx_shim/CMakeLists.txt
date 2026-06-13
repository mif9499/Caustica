cmake_minimum_required(VERSION 3.20)
project(ngxshim CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Path to the cloned NVIDIA/DLSS SDK (override with -DDLSS_SDK=...).
if(NOT DEFINED DLSS_SDK)
    set(DLSS_SDK "${CMAKE_CURRENT_SOURCE_DIR}/../../../dlss-sdk")
endif()

if(NOT DEFINED ENV{VULKAN_SDK})
    message(FATAL_ERROR "VULKAN_SDK environment variable is not set")
endif()

add_library(ngxshim SHARED ngx_shim.cpp)

target_include_directories(ngxshim PRIVATE
    "${DLSS_SDK}/include"
    "$ENV{VULKAN_SDK}/Include")

target_link_libraries(ngxshim PRIVATE
    "${DLSS_SDK}/lib/Windows_x86_64/x64/nvsdk_ngx_d.lib")

# nvsdk_ngx_d.lib is built against the dynamic CRT, so match it (the UCRT ships
# with Windows 10+ and vcruntime140 is redistributable / near-universal).
set_property(TARGET ngxshim PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreadedDLL")

set_target_properties(ngxshim PROPERTIES
    OUTPUT_NAME "ngxshim"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/out")
