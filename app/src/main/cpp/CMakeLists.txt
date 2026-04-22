cmake_minimum_required(VERSION 3.22.1)

project("studiopop_native" CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)

#
# 디버그 빌드에서도 native 는 Release 최적화로 강제.
#
# 이유: ggml/whisper.cpp 는 -O0 (Gradle debug 기본) 로 빌드되면 mobile CPU 에서 추론이
# 10-50배 느려져 실질적으로 사용 불가 — 5분 영상이 수십 분 걸리고 사용자는 "멈췄다" 로 착각.
# Kotlin 측 디버깅에 영향이 없고 C++ 디버거 스텝은 거의 안 쓰므로 항상 Release 로 강제.
#
set(CMAKE_BUILD_TYPE Release CACHE STRING "Native 는 항상 Release" FORCE)

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
