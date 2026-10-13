# MKVDCU Android native runtime (libmkvdcu.so)
#
# Builds the ReXGlue runtime for arm64-v8a and links it into the app's native
# library. The precompiled game code (produced offline by `rexglue codegen`
# from a legally owned default.xex) plugs in here later; without it this
# library provides the runtime and the GPU/platform capability probe.

cmake_minimum_required(VERSION 3.25)
project(mkvdcu LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Android GPU backend: Vulkan only (D3D12 is Windows).
set(REXGLUE_USE_D3D12 OFF CACHE BOOL "" FORCE)
set(REXGLUE_USE_VULKAN ON CACHE BOOL "" FORCE)
set(REXGLUE_ENABLE_TRACY OFF CACHE BOOL "" FORCE)
set(REXGLUE_ENABLE_PERF_COUNTERS OFF CACHE BOOL "" FORCE)
set(REXGLUE_BUILD_TESTS OFF CACHE BOOL "" FORCE)

get_filename_component(REXGLUE_SDK_DIR
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../references/rexglue-sdk"
    ABSOLUTE)

add_subdirectory("${REXGLUE_SDK_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/rexglue-sdk")

# Shared libraries the APK must package. Gradle picks up libmkvdcu.so; the
# runtime and GPU plugin are copied into jniLibs by scripts/build-android.ps1.
set(MKVDCU_JNI_DIR "${CMAKE_CURRENT_BINARY_DIR}/jniLibs")

add_library(mkvdcu SHARED mkvdcu_jni.cpp)
target_include_directories(mkvdcu PRIVATE "${REXGLUE_SDK_DIR}/include")
target_link_libraries(mkvdcu PRIVATE
    rexruntime
    rexui
    rexinput
    rexaudio
    SDL3::SDL3
    android
    log
)
set_target_properties(mkvdcu PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY "${MKVDCU_JNI_DIR}"
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
)

if(TARGET rexruntime)
    set_target_properties(rexruntime PROPERTIES LIBRARY_OUTPUT_DIRECTORY "${MKVDCU_JNI_DIR}")
endif()
if(TARGET rexgpu-xenos)
    set_target_properties(rexgpu-xenos PROPERTIES LIBRARY_OUTPUT_DIRECTORY "${MKVDCU_JNI_DIR}")
endif()
