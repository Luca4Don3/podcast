cmake_minimum_required(VERSION 3.22)
set(CMAKE_OSX_DEPLOYMENT_TARGET "13.0" CACHE STRING "Minimum supported macOS version")
project(podcast_macos VERSION 1.9.1 LANGUAGES CXX)

if(NOT CMAKE_SYSTEM_NAME STREQUAL "Darwin")
    message(FATAL_ERROR "The desktop bundle requires macOS and the Apple macOS SDK.")
endif()
if(CMAKE_OSX_DEPLOYMENT_TARGET VERSION_LESS 13.0)
    message(FATAL_ERROR "The desktop bundle requires macOS 13.0 or newer.")
endif()
enable_language(OBJCXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED YES)
set(CMAKE_CXX_EXTENSIONS NO)
set(CMAKE_OBJCXX_STANDARD 17)
set(CMAKE_OBJCXX_STANDARD_REQUIRED YES)
set(CMAKE_OBJCXX_EXTENSIONS NO)
add_compile_options($<$<COMPILE_LANGUAGE:OBJCXX>:-fobjc-arc> -Wall -Wextra)

find_library(APPKIT AppKit REQUIRED)
find_library(FOUNDATION Foundation REQUIRED)
find_library(AVFOUNDATION AVFoundation REQUIRED)
find_library(COREMEDIA CoreMedia REQUIRED)

# Reuse the existing portable episode planner without building or modifying the iOS app.
set(library_dir "${CMAKE_CURRENT_SOURCE_DIR}/../apple/NativeCache")
add_library(podcast_macos_feed STATIC src/Feed.mm "${library_dir}/PodcastLibraryCore.cpp")
target_include_directories(podcast_macos_feed PUBLIC src PRIVATE "${library_dir}/include")
# Use the active SDK headers and system linker stub, not a Homebrew build of libxml2.
target_compile_options(podcast_macos_feed PRIVATE "$<$<COMPILE_LANGUAGE:OBJCXX>:-iwithsysroot;/usr/include/libxml2>")
target_link_libraries(podcast_macos_feed PRIVATE xml2 PUBLIC "${FOUNDATION}")

add_executable(podcast MACOSX_BUNDLE src/main.mm src/FeedFetch.mm src/Smoke.mm Info.plist.in)
set_target_properties(podcast PROPERTIES
    MACOSX_BUNDLE_INFO_PLIST "${CMAKE_CURRENT_SOURCE_DIR}/Info.plist.in"
    MACOSX_BUNDLE_GUI_IDENTIFIER "io.github.luca4don3.podcast.macos"
    MACOSX_BUNDLE_BUNDLE_NAME "podcast"
    MACOSX_BUNDLE_BUNDLE_VERSION "${PROJECT_VERSION}"
    MACOSX_BUNDLE_SHORT_VERSION_STRING "${PROJECT_VERSION}"
    XCODE_ATTRIBUTE_CODE_SIGNING_ALLOWED NO)
target_link_libraries(podcast PRIVATE podcast_macos_feed "${APPKIT}" "${AVFOUNDATION}" "${COREMEDIA}")

option(PODCAST_ADHOC_SIGN "Ad hoc sign the finished bundle for local/CI testing" ON)
if(PODCAST_ADHOC_SIGN)
    find_program(CODESIGN codesign REQUIRED)
    add_custom_command(TARGET podcast POST_BUILD
        COMMAND "${CODESIGN}" --force --sign - "$<TARGET_BUNDLE_DIR:podcast>"
        VERBATIM)
endif()

include(CTest)
if(BUILD_TESTING)
    add_executable(podcast_feed_test tests/FeedTests.mm)
    target_link_libraries(podcast_feed_test PRIVATE podcast_macos_feed)
    add_test(NAME macos_feed COMMAND podcast_feed_test)
    add_test(NAME macos_startup COMMAND "$<TARGET_FILE:podcast>" --smoke-test)
    set_tests_properties(macos_feed macos_startup PROPERTIES TIMEOUT 20)
endif()
