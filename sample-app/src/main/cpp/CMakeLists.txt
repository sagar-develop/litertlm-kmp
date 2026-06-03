# Builds libwhisper.so (whisper.cpp + ggml CPU backend + our JNI bridge) for on-device
# speech-to-text. Sources come from the pinned whisper.cpp submodule. arm64-v8a only
# (the app's abiFilters), matching the LLM's 64-bit-only footprint.
cmake_minimum_required(VERSION 3.22.1)
project(whisper_jni)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# sample-app/src/main/cpp -> repo root is four levels up.
set(WHISPER_DIR ${CMAKE_SOURCE_DIR}/../../../../third_party/whisper.cpp)

add_library(whisper SHARED
    ${WHISPER_DIR}/src/whisper.cpp
    ${WHISPER_DIR}/ggml/src/ggml.c
    ${WHISPER_DIR}/ggml/src/ggml-alloc.c
    ${WHISPER_DIR}/ggml/src/ggml-backend.cpp
    ${WHISPER_DIR}/ggml/src/ggml-backend-reg.cpp
    ${WHISPER_DIR}/ggml/src/ggml-quants.c
    ${WHISPER_DIR}/ggml/src/ggml-threading.cpp
    ${WHISPER_DIR}/ggml/src/ggml-cpu/ggml-cpu.c
    ${WHISPER_DIR}/ggml/src/ggml-cpu/ggml-cpu.cpp
    ${WHISPER_DIR}/ggml/src/ggml-cpu/ggml-cpu-aarch64.cpp
    ${WHISPER_DIR}/ggml/src/ggml-cpu/ggml-cpu-hbm.cpp
    ${WHISPER_DIR}/ggml/src/ggml-cpu/ggml-cpu-quants.c
    ${WHISPER_DIR}/ggml/src/ggml-cpu/ggml-cpu-traits.cpp
    ${CMAKE_SOURCE_DIR}/whisper_jni.c
)

target_include_directories(whisper PRIVATE
    ${WHISPER_DIR}
    ${WHISPER_DIR}/src
    ${WHISPER_DIR}/include
    ${WHISPER_DIR}/ggml/include
    ${WHISPER_DIR}/ggml/src
    ${WHISPER_DIR}/ggml/src/ggml-cpu
)

# ggml expects the CPU backend compiled in; GGML_USE_CPU wires its registration.
target_compile_definitions(whisper PRIVATE GGML_USE_CPU)

find_library(LOG_LIB log)
target_link_libraries(whisper ${LOG_LIB} android)

if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_options(whisper PRIVATE -O3 -DNDEBUG -fvisibility=hidden -ffunction-sections -fdata-sections)
    target_link_options(whisper PRIVATE -Wl,--gc-sections)
endif ()
