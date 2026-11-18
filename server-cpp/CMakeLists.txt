cmake_minimum_required(VERSION 3.16)
project(podcast_server_cpp VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

add_compile_options(-Wall -Wextra)

option(PODCAST_ENABLE_SSL "Enable HTTPS for RSS fetching" ON)
option(PODCAST_ENABLE_WEBDAV "Build optional HTTPS WebDAV connector" OFF)

find_package(OpenSSL REQUIRED)
find_package(Threads REQUIRED)
find_package(SQLite3 REQUIRED)

add_library(podcast_core STATIC
    src/util.cpp
    src/config.cpp
    src/db.cpp
    src/json.cpp
    src/crypto.cpp
    src/http.cpp
    src/router.cpp
    src/xml.cpp
    src/fetch.cpp
    src/handlers/auth.cpp
    src/handlers/content.cpp
    src/handlers/library.cpp
    src/handlers/sync.cpp
    src/handlers/stations.cpp
    src/handlers/media.cpp
    src/storage/local.cpp
    src/storage/webdav.cpp
)

target_include_directories(podcast_core PUBLIC src)
target_link_libraries(podcast_core PUBLIC OpenSSL::SSL OpenSSL::Crypto SQLite::SQLite3 Threads::Threads)
if(PODCAST_ENABLE_SSL)
    target_compile_definitions(podcast_core PUBLIC PODCAST_ENABLE_SSL=1)
endif()

if(PODCAST_ENABLE_WEBDAV)
    find_package(CURL 7.62 REQUIRED)
    find_package(LibXml2 REQUIRED)
    target_compile_definitions(podcast_core PRIVATE PODCAST_ENABLE_WEBDAV=1)
    target_link_libraries(podcast_core PRIVATE CURL::libcurl LibXml2::LibXml2)
endif()

add_executable(podcast-server src/main.cpp)
target_link_libraries(podcast-server PRIVATE podcast_core)

enable_testing()
add_executable(podcast-tests tests/test_main.cpp)
target_link_libraries(podcast-tests PRIVATE podcast_core)
add_test(NAME podcast-tests COMMAND podcast-tests)
add_executable(storage_local_test tests/storage_local_test.cpp)
target_link_libraries(storage_local_test PRIVATE podcast_core)
add_test(NAME storage_local_test COMMAND storage_local_test)
if(PODCAST_ENABLE_WEBDAV)
    find_package(Python3 COMPONENTS Interpreter QUIET)
    find_program(PODCAST_OPENSSL_TOOL openssl)
    if(Python3_Interpreter_FOUND AND PODCAST_OPENSSL_TOOL)
        add_executable(storage_webdav_test tests/storage_webdav_test.cpp)
        target_compile_definitions(storage_webdav_test PRIVATE PODCAST_ENABLE_WEBDAV=1)
        target_link_libraries(storage_webdav_test PRIVATE podcast_core CURL::libcurl)
        target_link_options(storage_webdav_test PRIVATE -Wl,--wrap=curl_easy_init)
        add_test(NAME storage_webdav_test COMMAND "${Python3_EXECUTABLE}"
            "${CMAKE_CURRENT_SOURCE_DIR}/tests/storage_webdav_fixture.py" $<TARGET_FILE:storage_webdav_test>)
        set_tests_properties(storage_webdav_test PROPERTIES TIMEOUT 50)
    endif()
endif()

install(TARGETS podcast-server RUNTIME DESTINATION bin)
