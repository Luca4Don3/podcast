cmake_minimum_required(VERSION 3.22)
project(podcast_windows LANGUAGES CXX)

if(NOT WIN32 OR NOT MSVC)
    message(FATAL_ERROR "Use the Visual Studio MSVC toolchain and Windows SDK to build podcast for Windows.")
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED YES)
set(CMAKE_CXX_EXTENSIONS NO)
# Standalone preview: no separate VC++ redistributable download for these executables.
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")

add_compile_definitions(UNICODE _UNICODE WIN32_LEAN_AND_MEAN NOMINMAX _WIN32_WINNT=0x0A00)
add_compile_options(/W4 /utf-8 /permissive- /EHsc)

set(cache_dir "${CMAKE_CURRENT_SOURCE_DIR}/../apple/NativeCache")
add_library(podcast_cache STATIC "${cache_dir}/PodcastNativeCache.cpp")
target_include_directories(podcast_cache PUBLIC "${cache_dir}/include")

add_executable(podcast WIN32 src/main.cpp podcast.manifest)
target_link_libraries(podcast PRIVATE podcast_cache windowsapp ole32 shell32 comctl32 comdlg32 user32 gdi32)

include(CTest)
if(BUILD_TESTING)
    add_executable(podcast_cache_test tests/cache_test.cpp)
    target_link_libraries(podcast_cache_test PRIVATE podcast_cache)
    add_test(NAME windows_cache COMMAND podcast_cache_test)
    add_test(NAME windows_startup COMMAND podcast --smoke-test)
    set_tests_properties(windows_cache windows_startup PROPERTIES TIMEOUT 20)
endif()
