cmake_minimum_required(VERSION 3.22.1)

project(mobimic LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Oboe arrives as a prefab package from the com.google.oboe:oboe AAR.
find_package(oboe REQUIRED CONFIG)

add_library(mobimic SHARED
        jni_bridge.cpp
        AudioEngine.cpp
        WavWriter.cpp
        net/UdpSender.cpp
        dsp/DspChain.cpp
        dsp/SpectralNoiseSuppressor.cpp
)

target_include_directories(mobimic PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

target_compile_options(mobimic PRIVATE
        -O3
        # Deliberately NOT -ffast-math: it breaks the denormal and NaN assumptions
        # the DSP filters rely on. Flush-to-zero is set explicitly on the audio thread.
        -fno-math-errno
        -Wall
        -Wextra
)

target_link_libraries(mobimic
        PRIVATE
        oboe::oboe
        android
        log
)
