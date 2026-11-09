cmake_minimum_required(VERSION 3.22.1)
project(gpsquest96 VERSION 0.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ═══════════════════════════════════════════════════════
# BIBLIOTECA NATIVA: libgpsquest96.so
# ═══════════════════════════════════════════════════════
add_library(gpsquest96 SHARED
    jni/native_bridge.cpp
    core/rom_loader.cpp
    core/mbc.cpp
    core/cpu.cpp
    core/mmu.cpp
    core/ppu.cpp
    core/apu.cpp
    core/wild_table.cpp
    renderer/shader.cpp
    renderer/texture.cpp
    renderer/renderer.cpp
)

target_include_directories(gpsquest96 PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_link_libraries(gpsquest96
    android
    log
    GLESv2
)
