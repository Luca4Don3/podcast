cmake_minimum_required(VERSION 3.16)
# This target uses only C headers and needs no C++ runtime. Set the default
# before the Android NDK toolchain is loaded; AGP can also pass it explicitly.
if(NOT DEFINED ANDROID_STL)
    set(ANDROID_STL none)
endif()
project(podcast_native LANGUAGES C CXX)

get_filename_component(cache_dir "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../apple/NativeCache" ABSOLUTE)

if(ANDROID)
    add_library(podcast_native SHARED source_line_scan.c native_source_lines.cpp
        native_offline_cache.cpp "${cache_dir}/PodcastNativeCache.cpp")
    target_include_directories(podcast_native PRIVATE "${cache_dir}/include")
    target_compile_definitions(podcast_native PRIVATE _FILE_OFFSET_BITS=64)
    # Keep load segments 16 KiB aligned on all ABIs, including 32-bit ones.
    target_link_options(podcast_native PRIVATE "-Wl,-z,max-page-size=16384")
    set(scan_target podcast_native)
else()
    enable_testing()
    add_executable(source_line_scan_test source_line_scan.c tests/source_line_scan_test.cpp)
    target_include_directories(source_line_scan_test PRIVATE .)
    add_test(NAME source_line_scan COMMAND source_line_scan_test)
    add_executable(offline_cache_test tests/offline_cache_test.cpp "${cache_dir}/PodcastNativeCache.cpp")
    target_include_directories(offline_cache_test PRIVATE "${cache_dir}/include")
    target_compile_features(offline_cache_test PRIVATE cxx_std_17)
    add_test(NAME offline_cache COMMAND offline_cache_test)
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
