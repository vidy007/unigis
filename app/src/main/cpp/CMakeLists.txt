cmake_minimum_required(VERSION 3.22.1)
project(unigis LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Platform-independent core (public API: unigis.hpp): builds for Android and for host unit tests.
add_library(unigis_core STATIC json.cpp campus.cpp search.cpp router.cpp camera.cpp)
target_include_directories(unigis_core PUBLIC .)
target_compile_options(unigis_core PRIVATE -Wall -Wextra)
set_target_properties(unigis_core PROPERTIES POSITION_INDEPENDENT_CODE ON)

option(UNIGIS_TESTS "Build the core_test executable" OFF)
if (UNIGIS_TESTS)
    add_executable(core_test tests/core_test.cpp)
    target_link_libraries(core_test PRIVATE unigis_core)
endif ()

if (ANDROID)
    add_library(unigis SHARED jni_bridge.cpp)
    target_link_libraries(unigis PRIVATE unigis_core log)
    target_compile_options(unigis PRIVATE -Wall -Wextra)
endif ()
