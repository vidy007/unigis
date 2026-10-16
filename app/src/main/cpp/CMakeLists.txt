cmake_minimum_required(VERSION 3.22.1)
project(unigismap LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Platform-independent core (public API: unigismap.hpp): builds for Android and for host unit tests.
add_library(unigismap_core STATIC json.cpp campus.cpp search.cpp router.cpp camera.cpp)
target_include_directories(unigismap_core PUBLIC .)
target_compile_options(unigismap_core PRIVATE -Wall -Wextra)
set_target_properties(unigismap_core PROPERTIES POSITION_INDEPENDENT_CODE ON)

option(UNIGISMAP_TESTS "Build the core_test executable" OFF)
if (UNIGISMAP_TESTS)
    add_executable(core_test tests/core_test.cpp)
    target_link_libraries(core_test PRIVATE unigismap_core)
endif ()

if (ANDROID)
    add_library(unigismap SHARED jni_bridge.cpp)
    target_link_libraries(unigismap PRIVATE unigismap_core log)
    target_compile_options(unigismap PRIVATE -Wall -Wextra)
endif ()
