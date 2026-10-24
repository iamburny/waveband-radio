cmake_minimum_required(VERSION 3.18)
project(dabtuner LANGUAGES C CXX)

set(THIRD_PARTY ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party)

# Third-party code is built as separate shared libraries (LGPL-2.1: dynamic linking only).
add_subdirectory(${THIRD_PARTY}/omri-usb ${CMAKE_CURRENT_BINARY_DIR}/omri-usb)

set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(BUILD_LIBOUT123 OFF CACHE BOOL "" FORCE)
set(BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(NETWORK OFF CACHE BOOL "" FORCE)
set(NO_ICY ON CACHE BOOL "" FORCE)
set(NO_ID3V2 ON CACHE BOOL "" FORCE)
set(NO_MESSAGES ON CACHE BOOL "" FORCE)
add_subdirectory(${THIRD_PARTY}/mpg123/ports/cmake ${CMAKE_CURRENT_BINARY_DIR}/mpg123 EXCLUDE_FROM_ALL)
target_link_options(libmpg123 PRIVATE "-Wl,-z,max-page-size=16384")

add_library(dabtuner_jni SHARED
        jni_util.cpp
        omri_jni.cpp
        mp2_decoder_jni.cpp)
set_target_properties(dabtuner_jni PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)
target_compile_options(dabtuner_jni PRIVATE -Wall -Wextra -fvisibility=hidden)
target_link_options(dabtuner_jni PRIVATE "-Wl,-z,max-page-size=16384")
target_include_directories(dabtuner_jni PRIVATE ${THIRD_PARTY}/mpg123/src/include)
target_link_libraries(dabtuner_jni PRIVATE omri libmpg123 log)
