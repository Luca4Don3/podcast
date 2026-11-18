cmake_minimum_required(VERSION 3.16)
# This target uses only C headers and needs no C++ runtime. Set the default
# before the Android NDK toolchain is loaded; AGP can also pass it explicitly.
if(NOT DEFINED ANDROID_STL)
    set(ANDROID_STL none)
endif()
project(podcast_native LANGUAGES C CXX)

if(ANDROID)
    add_library(podcast_native SHARED source_line_scan.c native_source_lines.cpp)
    # Keep load segments 16 KiB aligned on all ABIs, including 32-bit ones.
    target_link_options(podcast_native PRIVATE "-Wl,-z,max-page-size=16384")
    set(scan_target podcast_native)
else()
    enable_testing()
    add_executable(source_line_scan_test source_line_scan.c tests/source_line_scan_test.cpp)
    target_include_directories(source_line_scan_test PRIVATE .)
    add_test(NAME source_line_scan COMMAND source_line_scan_test)
    set(scan_target source_line_scan_test)
endif()

set_target_properties(
    ${scan_target} PROPERTIES
    C_STANDARD 11
    C_STANDARD_REQUIRED YES
    C_EXTENSIONS NO
    C_VISIBILITY_PRESET hidden
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED YES
    CXX_EXTENSIONS NO
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN YES
)
target_compile_options(${scan_target} PRIVATE $<$<COMPILE_LANGUAGE:CXX>:-fno-exceptions;-fno-rtti>)
