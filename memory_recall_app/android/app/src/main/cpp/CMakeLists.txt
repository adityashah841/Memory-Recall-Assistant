# Native QNN + Genie bridge for libsynapse_inference.so (IMPLEMENTATION_PLAN.md §2, §8.2, §22 CP3).
#
# Only built when `qnnSdkRoot` / QNN_SDK_ROOT is supplied (see app/build.gradle.kts). Provides the JNI
# entry points declared in ai/NativeInference.kt: CavaFace + Whisper (raw QNN context binaries) and
# the Llama Genie dialog. The QAIRT 2.45 runtime .so libs (libQnnHtp*, libQnnSystem, libGenie*) live
# in src/main/jniLibs/arm64-v8a/ and are loaded at runtime.
cmake_minimum_required(VERSION 3.22.1)
project(synapse_inference LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT DEFINED QNN_SDK_ROOT)
    message(FATAL_ERROR "QNN_SDK_ROOT not set — pass -DQNN_SDK_ROOT or set qnnSdkRoot in local.properties")
endif()

add_library(synapse_inference SHARED synapse_inference.cpp)

# QAIRT 2.45 headers: QNN/* (Saver, System, HtpDevice, ...) and GenieDialog.h, GenieCommon.h.
target_include_directories(synapse_inference PRIVATE
    ${QNN_SDK_ROOT}/include/QNN
    ${QNN_SDK_ROOT}/include/Genie
)

find_library(log-lib log)

# Genie is linked directly; the QNN HTP backend libs are dlopen'd by the QNN runtime from jniLibs.
find_library(genie-lib Genie PATHS ${CMAKE_SOURCE_DIR}/../jniLibs/arm64-v8a)

target_link_libraries(synapse_inference
    ${log-lib}
    ${genie-lib}
)
