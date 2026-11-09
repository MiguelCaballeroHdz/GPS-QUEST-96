#include "core/mbc.h"
#include "core/rom_loader.h"

#include <android/log.h>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <string>

#define LOG_TAG "GPSQuest96"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace gpsquest96 {

static uint32_t banksFromRomSizeCode(uint8_t code) {
    if (code <= 8) {
        return 2u << code;
    }
    return 2;
}

static uint32_t bytesFromRamSizeCode(uint8_t code) {
    switch (code) {
        case 0x01: return 2048;
        case 0x02: return 8192;
        case 0x03: return 32768;
        case 0x04: return 131072;
        case 0x05: return 65536;
        default: return 0;
    }
}

static bool cartridgeHasBattery(uint8_t type) {
    switch (type) {
        case 0x03:
        case 0x06:
        case 0x09:
        case 0x0F:
        case 0x10:
        case 0x13:
        case 0x1B:
        case 0x1E:
            return true;
        default:
            return false;
    }
}

static void makeSavPath(const char* romPath, char* out, size_t cap) {
    if (out == nullptr || cap == 0) {
        return;
    }
    out[0] = '\0';
    if (romPath == nullptr || cap < 5) {
        return;
    }
    // std::string evita el overflow del strncpy(dot, ".sav", cap - off - 1)
    // cuando la extensión cae cerca del final del buffer.
    std::string p(romPath);
    size_t slash = p.find_last_of("/\\");
    size_t dot = p.find_last_of('.');
    if (dot != std::string::npos && (slash == std::string::npos || dot > slash)) {
        p.resize(dot);
    }
    p += ".sav";
    if (p.size() >= cap) {
        p.resize(cap - 1);
    }
    memcpy(out, p.data(), p.size());
    out[p.size()] = '\0';
}

static const char* kindName(Mbc::Kind k) {
    switch (k) {
        case Mbc::Kind::MBC1: return "MBC1";
        case Mbc::Kind::MBC3: return "MBC3";
        case Mbc::Kind::MBC5: return "MBC5";
        default: return "ROM";
    }
}

Mbc::Mbc()
    : loader_(nullptr),
      kind_(Kind::ROM_ONLY),
      configured_(false),
      hasBattery_(false),
      hasRtc_(false),
      ramEnabled_(false),
      ramDirty_(false),
      mbc1ModeRam_(false),
      rtcLatchPrev_(false),
      romBank_(1),
      ramBank_(0),
      mbc1High_(0),
      mbc5BankHi_(0),
      romBanks_(2),
      ramSize_(0) {
    savPath_[0] = '\0';
    memset(rtcLatched_, 0, sizeof(rtcLatched_));
}

Mbc::~Mbc() {
    detach();
}

void Mbc::attach(RomLoader* loader) {
    if (loader == nullptr || !loader->isOpen()) {
        detach();
        return;
    }

    char nextSav[512];
    makeSavPath(loader->getPath(), nextSav, sizeof(nextSav));
    if (configured_ && loader_ == loader && strcmp(savPath_, nextSav) == 0) {
        resetRegisters();
        return;
    }

    detach();
    loader_ = loader;
    strncpy(savPath_, nextSav, sizeof(savPath_) - 1);
    savPath_[sizeof(savPath_) - 1] = '\0';
    configureFromHeader();
    loadSav();
    resetRegisters();
    configured_ = true;
    LOGI("MBC ready type=%s cartBanks=%u ram=%uB battery=%d sav=%s",
         kindName(kind_), romBanks_, ramSize_, hasBattery_ ? 1 : 0, savPath_);
}

void Mbc::detach() {
    if (configured_) {
        saveSav();
    }
    loader_ = nullptr;
    configured_ = false;
    sram_.clear();
    ramSize_ = 0;
    savPath_[0] = '\0';
    kind_ = Kind::ROM_ONLY;
}

void Mbc::flush() {
    saveSav();
}

void Mbc::resetRegisters() {
    ramEnabled_ = false;
    mbc1ModeRam_ = false;
    rtcLatchPrev_ = false;
    romBank_ = 1;
    ramBank_ = 0;
    mbc1High_ = 0;
    mbc5BankHi_ = 0;
}

void Mbc::configureFromHeader() {
    kind_ = Kind::ROM_ONLY;
    hasBattery_ = false;
    hasRtc_ = false;
    romBanks_ = 2;
    ramSize_ = 0;
    sram_.clear();
    if (loader_ == nullptr) {
        return;
    }

    RomHeader header;
    if (!loader_->readHeader(header)) {
        LOGW("MBC: cabecera ilegible, ROM-only");
        return;
    }

    romBanks_ = banksFromRomSizeCode(header.romSize);
    ramSize_ = bytesFromRamSizeCode(header.ramSize);
    hasBattery_ = cartridgeHasBattery(header.cartridgeType);

    switch (header.cartridgeType) {
        case 0x01:
        case 0x02:
        case 0x03:
            kind_ = Kind::MBC1;
            break;
        case 0x0F:
        case 0x10:
        case 0x11:
        case 0x12:
        case 0x13:
            kind_ = Kind::MBC3;
            hasRtc_ = (header.cartridgeType == 0x0F || header.cartridgeType == 0x10);
            break;
        case 0x19:
        case 0x1A:
        case 0x1B:
        case 0x1C:
        case 0x1D:
        case 0x1E:
            kind_ = Kind::MBC5;
            break;
        case 0x00:
        case 0x08:
        case 0x09:
            kind_ = Kind::ROM_ONLY;
            break;
        default:
            kind_ = Kind::ROM_ONLY;
            LOGW("MBC: cartucho 0x%02X no soportado, ROM-only", header.cartridgeType);
            break;
    }

    if (ramSize_ > 0) {
        sram_.assign(ramSize_, 0xFF);
    }
}

void Mbc::loadSav() {
    if (!hasBattery_ || sram_.empty() || savPath_[0] == '\0') {
        LOGI("MBC SRAM sin bateria o tamano 0: sin .sav");
        return;
    }

    FILE* f = fopen(savPath_, "rb");
    if (f == nullptr) {
        // Primera partida: la SRAM queda como la inicializó configureFromHeader.
        LOGI("MBC SRAM nueva (%u B): %s", ramSize_, savPath_);
        return;
    }

    const size_t n = fread(sram_.data(), 1, sram_.size(), f);
    fclose(f);

    // .sav más corto que la SRAM declarada: el resto es bus flotante (0xFF).
    if (n < sram_.size()) {
        memset(sram_.data() + n, 0xFF, sram_.size() - n);
    }
    ramDirty_ = false;
    LOGI("MBC SRAM cargada: %u/%u B (%s)",
         static_cast<unsigned>(n), ramSize_, savPath_);
}

void Mbc::saveSav() {
    if (!hasBattery_ || sram_.empty() || !ramDirty_ || savPath_[0] == '\0') {
        return;
    }

    // Escritura atómica: temporal + respaldo del anterior + rename.
    // Si el proceso muere a mitad, el .sav viejo sigue intacto.
    const std::string tmp = std::string(savPath_) + ".tmp";
    const std::string bak = std::string(savPath_) + ".bak";

    FILE* f = fopen(tmp.c_str(), "wb");
    if (f == nullptr) {
        LOGW("MBC no pudo escribir %s", tmp.c_str());
        return;
    }
    const size_t n = fwrite(sram_.data(), 1, sram_.size(), f);
    fflush(f);
    fclose(f);

    if (n != sram_.size()) {
        LOGW("MBC escritura incompleta %u/%u B", static_cast<unsigned>(n), ramSize_);
        std::remove(tmp.c_str());
        return;
    }

    std::remove(bak.c_str());
    std::rename(savPath_, bak.c_str()); // si no existe, falla sin daño
    if (std::rename(tmp.c_str(), savPath_) != 0) {
        LOGW("MBC no pudo promover %s", savPath_);
        std::rename(bak.c_str(), savPath_); // intentar restaurar
        return;
    }

    ramDirty_ = false;
    LOGI("MBC SRAM guardada: %u B (%s)", ramSize_, savPath_);
}

uint16_t Mbc::maskRomBank(uint32_t bank) const {
    if (romBanks_ == 0) {
        return 1;
    }
    return static_cast<uint16_t>(bank % romBanks_);
}

uint32_t Mbc::mappedRomOffset(uint16_t address) const {
    uint32_t bank;
    if (address < 0x4000) {
        bank = 0;
        if (kind_ == Kind::MBC1 && mbc1ModeRam_) {
            bank = static_cast<uint32_t>(mbc1High_) << 5;
        }
    } else if (kind_ == Kind::MBC1) {
        uint32_t low = romBank_ & 0x1F;
        if (low == 0) {
            low = 1;
        }
        bank = mbc1ModeRam_ ? low : (low | (static_cast<uint32_t>(mbc1High_) << 5));
    } else {
        bank = romBank_;
    }
    if (romBanks_ > 0) {
        bank %= romBanks_;
    }
    return bank * 0x4000u + (static_cast<uint32_t>(address) & 0x3FFFu);
}

uint8_t Mbc::readRom(uint16_t address) {
    if (loader_ == nullptr) {
        return 0xFF;
    }
    uint32_t offset = mappedRomOffset(address);
    if (offset >= loader_->getFileSize()) {
        return 0xFF;
    }
    uint8_t byte = 0;
    if (loader_->readBytes(offset, &byte, 1) == 0) {
        return 0xFF;
    }
    return byte;
}

uint8_t Mbc::readRam(uint16_t address) {
    if (!ramEnabled_) {
        return 0xFF;
    }
    if (hasRtc_ && ramBank_ >= 0x08 && ramBank_ <= 0x0C) {
        return rtcLatched_[ramBank_ - 0x08];
    }
    if (sram_.empty()) {
        return 0xFF;
    }
    uint32_t ramOff = (static_cast<uint32_t>(ramBank_ & 0x0F) * 0x2000u)
                      + (static_cast<uint32_t>(address) & 0x1FFFu);
    if (ramOff >= sram_.size()) {
        return 0xFF; // fuera de la SRAM declarada: bus flotante, no wrap
    }
    return sram_[ramOff];
}

void Mbc::writeRam(uint16_t address, uint8_t value) {
    if (!ramEnabled_) {
        return;
    }
    if (hasRtc_ && ramBank_ >= 0x08 && ramBank_ <= 0x0C) {
        rtcLatched_[ramBank_ - 0x08] = value;
        return;
    }
    if (sram_.empty()) {
        return;
    }
    uint32_t ramOff = (static_cast<uint32_t>(ramBank_ & 0x0F) * 0x2000u)
                      + (static_cast<uint32_t>(address) & 0x1FFFu);
    if (ramOff >= sram_.size()) {
        return; // fuera de la SRAM declarada: escritura ignorada
    }
    if (sram_[ramOff] != value) {
        sram_[ramOff] = value;
        ramDirty_ = true;
    }
}

void Mbc::latchRtc() {
    time_t now = time(nullptr);
    struct tm local {};
#if defined(_WIN32)
    localtime_s(&local, &now);
#else
    localtime_r(&now, &local);
#endif
    rtcLatched_[0] = static_cast<uint8_t>(local.tm_sec);
    rtcLatched_[1] = static_cast<uint8_t>(local.tm_min);
    rtcLatched_[2] = static_cast<uint8_t>(local.tm_hour);
    uint16_t days = static_cast<uint16_t>(local.tm_yday);
    rtcLatched_[3] = static_cast<uint8_t>(days & 0xFF);
    rtcLatched_[4] = static_cast<uint8_t>((days >> 8) & 0x01);
}

void Mbc::writeControl(uint16_t address, uint8_t value) {
    if (kind_ == Kind::ROM_ONLY) {
        return;
    }

    if (address <= 0x1FFF) {
        bool enable = (value & 0x0F) == 0x0A;
        if (ramEnabled_ && !enable) {
            saveSav();
        }
        ramEnabled_ = enable;
        return;
    }

    if (kind_ == Kind::MBC1) {
        if (address <= 0x3FFF) {
            uint8_t low = value & 0x1F;
            if (low == 0) {
                low = 1;
            }
            romBank_ = maskRomBank(low | (static_cast<uint32_t>(mbc1High_) << 5));
        } else if (address <= 0x5FFF) {
            mbc1High_ = value & 0x03;
            if (mbc1ModeRam_) {
                ramBank_ = mbc1High_;
            } else {
                uint8_t low = romBank_ & 0x1F;
                if (low == 0) {
                    low = 1;
                }
                romBank_ = maskRomBank(low | (static_cast<uint32_t>(mbc1High_) << 5));
            }
        } else {
            mbc1ModeRam_ = (value & 0x01) != 0;
        }
        return;
    }

    if (kind_ == Kind::MBC3) {
        if (address <= 0x3FFF) {
            uint8_t bank = value & 0x7F;
            if (bank == 0) {
                bank = 1;
            }
            romBank_ = maskRomBank(bank);
        } else if (address <= 0x5FFF) {
            ramBank_ = value & 0x0F;
        } else {
            bool latch = (value & 0x01) != 0;
            if (!rtcLatchPrev_ && latch) {
                latchRtc();
            }
            rtcLatchPrev_ = latch;
        }
        return;
    }

    // MBC5
    if (address <= 0x2FFF) {
        romBank_ = maskRomBank(value | (static_cast<uint32_t>(mbc5BankHi_) << 8));
    } else if (address <= 0x3FFF) {
        mbc5BankHi_ = value & 0x01;
        romBank_ = maskRomBank((romBank_ & 0xFF) | (static_cast<uint32_t>(mbc5BankHi_) << 8));
    } else if (address <= 0x5FFF) {
        ramBank_ = value & 0x0F;
    }
}

} // namespace gpsquest96
