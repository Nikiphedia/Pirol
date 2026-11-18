cmake_minimum_required(VERSION 3.22.1)
project(pirol_audio LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# --- Oboe via FetchContent ---
include(FetchContent)
FetchContent_Declare(
    oboe
    GIT_REPOSITORY https://github.com/google/oboe.git
    GIT_TAG        1.9.0
)
FetchContent_MakeAvailable(oboe)

# --- pirol_audio shared library ---
add_library(pirol_audio SHARED
    AudioEngine.cpp
    jni_bridge.cpp
)

target_include_directories(pirol_audio PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# Oboe linken
target_link_libraries(pirol_audio
    oboe
    android
    log
)

# 16-KB-Page-Size: ELF-Segmente auf 16384 ausrichten (zusaetzlich zu ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES)
target_link_options(pirol_audio PRIVATE
    -Wl,-z,max-page-size=16384
    -Wl,-z,common-page-size=16384
)
