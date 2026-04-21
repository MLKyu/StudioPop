cmake_minimum_required(VERSION 3.22.1)

project("studiopop_native" CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)

#
# whisper.cpp 자동 fetch (특정 태그 고정 — 재현성 위해)
# 첫 빌드 시 wget/git clone 으로 ${CMAKE_BINARY_DIR}/_deps/ 에 받아짐
#
include(FetchContent)

# Android 빌드에 부적합한 옵션은 비활성. whisper.cpp 의 기본값을 override
set(BUILD_SHARED_LIBS         OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS       OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER      OFF CACHE BOOL "" FORCE)
set(GGML_CUDA                 OFF CACHE BOOL "" FORCE)
set(GGML_METAL                OFF CACHE BOOL "" FORCE)
set(GGML_BLAS                 OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP               OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL               OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN               OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS          OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES       OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    whisper_cpp
    GIT_REPOSITORY https://github.com/ggerganov/whisper.cpp.git
    GIT_TAG        v1.7.4
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(whisper_cpp)

#
# 우리 JNI bridge
#
add_library(studiopop_native SHARED jni_bridge.cpp)

target_include_directories(studiopop_native PRIVATE
    ${whisper_cpp_SOURCE_DIR}/include
    ${whisper_cpp_SOURCE_DIR}/ggml/include
)

target_link_libraries(studiopop_native
    whisper
    android
    log
)
