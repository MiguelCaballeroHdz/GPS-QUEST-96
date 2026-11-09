#include "core/rom_loader.h"

#include <android/log.h>

#define LOG_TAG "GPSQuest96"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace gpsquest96 {

RomLoader::RomLoader() : file_(nullptr), fileSize_(0) {
    path_[0] = '\0';
}

RomLoader::~RomLoader() {
    close();
}

bool RomLoader::open(const char* path) {
    if (file_ != nullptr) {
        close();
    }
    if (path == nullptr) {
        return false;
    }

    file_ = fopen(path, "rb");
    if (file_ == nullptr) {
        LOGE("No se pudo abrir la ROM: %s", path);
        return false;
    }

    // Tamaño del archivo
    if (fseek(file_, 0, SEEK_END) != 0) {
        LOGE("ROM sin tamaño legible: %s", path);
        close();
        return false;
    }
    long size = ftell(file_);
    if (size < 0x150 || size > 16L * 1024 * 1024) {
        LOGE("ROM inválida (%ld bytes): %s", size, path);
        close();
        return false;
    }
    fseek(file_, 0, SEEK_SET);

    // Copia completa a RAM: el fetch de la CPU es O(1), sin fseek/fread
    // por byte (~500k syscalls/s antes).
    romData_.resize(static_cast<size_t>(size));
    size_t n = fread(romData_.data(), 1, romData_.size(), file_);
    if (n != romData_.size()) {
        LOGE("ROM truncada: leídos %zu de %zu bytes", n, romData_.size());
        romData_.clear();
        close();
        return false;
    }
    fileSize_ = static_cast<uint32_t>(size);

    strncpy(path_, path, sizeof(path_) - 1);
    path_[sizeof(path_) - 1] = '\0';

    LOGI("ROM en RAM: %s (%u bytes)", path_, fileSize_);
    return true;
}

void RomLoader::close() {
    romData_.clear();
    if (file_ != nullptr) {
        fclose(file_);
        file_ = nullptr;
        fileSize_ = 0;
        path_[0] = '\0';
        LOGI("ROM cerrada");
    }
}

bool RomLoader::readHeader(RomHeader& header) {
    if (file_ == nullptr || romData_.empty()) {
        LOGE("No hay ROM cargada");
        return false;
    }

    // Leer bloque de cabecera completo (0x100 - 0x14F)
    uint8_t buffer[80];
    size_t read = readBytes(0x100, buffer, sizeof(buffer));

    if (read < sizeof(buffer)) {
        LOGE("Error leyendo cabecera: leídos %zu bytes", read);
        return false;
    }

    // Título: 0x134 - 0x143 (16 bytes)
    memcpy(header.title, buffer + 0x34, 16);
    header.title[16] = '\0';

    // Game Code: 0x13F - 0x142 (4 bytes)
    memcpy(header.gameCode, buffer + 0x3F, 4);
    header.gameCode[4] = '\0';

    // Flag CGB: 0x143 (0x80 enhanced, 0xC0 only, 0x00 DMG)
    header.cgbFlag = buffer[0x43];

    // Tipo de cartucho: 0x147
    header.cartridgeType = buffer[0x47];

    // Tamaño ROM: 0x148
    header.romSize = buffer[0x48];

    // Tamaño RAM: 0x149
    header.ramSize = buffer[0x49];

    // Versión: 0x14C
    header.version = buffer[0x4C];

    // Checksum de cabecera: 0x14D
    header.headerChecksum = buffer[0x4D];

    // Checksum global: 0x14E - 0x14F
    header.globalChecksum = (static_cast<uint16_t>(buffer[0x4E]) << 8) | buffer[0x4F];

    // Header log: solo una vez por título (los getters JNI lo releen siempre).
    static std::string lastLoggedTitle;
    std::string curTitle(header.title, strnlen(header.title, 16));
    if (curTitle != lastLoggedTitle) {
        lastLoggedTitle = curTitle;
        LOGI("Título: %s", header.title);
        LOGI("Game Code: %s", header.gameCode);
        LOGI("Tipo cartucho: 0x%02X", header.cartridgeType);
        LOGI("Tamaño ROM: 0x%02X", header.romSize);
        LOGI("CGB flag: 0x%02X (%s)", header.cgbFlag,
             (header.cgbFlag & 0x80) ? ((header.cgbFlag & 0xC0) == 0xC0
                                        ? "CGB-only" : "CGB-enhanced")
                                     : "DMG");
    }

    return true;
}

size_t RomLoader::readBytes(uint32_t offset, void* buffer, size_t size) {
    if (buffer == nullptr || romData_.empty() || offset >= romData_.size()) {
        return 0;
    }
    size_t available = romData_.size() - offset;
    size_t n = (size < available) ? size : available;
    memcpy(buffer, romData_.data() + offset, n);
    return n;
}

} // namespace gpsquest96
