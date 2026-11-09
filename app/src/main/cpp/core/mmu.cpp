#include "core/mmu.h"
#include "core/rom_loader.h"

#include <android/log.h>
#include <cstring>

#define LOG_TAG "GPSQuest96"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace gpsquest96 {

// Hook de escritura SOLO para el arnés nativo de diagnóstico (compilado con
// GPS96_DIAG_WRITE_HOOK). En el APK no existe: el #ifdef lo elimina.
#ifdef GPS96_DIAG_WRITE_HOOK
void (*g_diagWriteHook)(uint16_t, uint8_t) = nullptr;
#endif

Mmu::Mmu()
    : interruptEnable_(0), interruptFlags_(0), romLoader_(nullptr),
      divCounter_(0), timaCounter_(0) {
    memset(vram_, 0, sizeof(vram_));
    memset(ram_, 0, sizeof(ram_));
    memset(ioRegisters_, 0, sizeof(ioRegisters_));
    memset(hram_, 0, sizeof(hram_));
    memset(oam_, 0, sizeof(oam_));
    memset(bgPaletteRam_, 0, sizeof(bgPaletteRam_));
    memset(objPaletteRam_, 0, sizeof(objPaletteRam_));
}

Mmu::~Mmu() {
}

void Mmu::initialize() {
    memset(vram_, 0, sizeof(vram_));
    memset(ram_, 0, sizeof(ram_));
    memset(ioRegisters_, 0, sizeof(ioRegisters_));
    memset(hram_, 0, sizeof(hram_));
    memset(oam_, 0, sizeof(oam_));
    memset(bgPaletteRam_, 0, sizeof(bgPaletteRam_));
    memset(objPaletteRam_, 0, sizeof(objPaletteRam_));
    interruptEnable_ = 0;
    interruptFlags_ = 0;
    divCounter_ = 0;
    timaCounter_ = 0;

    // CGB arranca en DMG hasta que nativeOpenRom lea la cabecera 0x143.
    cgbMode_ = false;
    doubleSpeed_ = false;
    speedSwitchArmed_ = false;
    vbk_ = 0;
    svbk_ = 1;          // DMG: 0xC000 banco 0, 0xD000 banco 1 (mapa clásico)
    bcps_ = 0;
    ocps_ = 0;
    hdmaSrcHi_ = hdmaSrcLo_ = hdmaDstHi_ = hdmaDstLo_ = 0;
    hdmaRemaining_ = 0;
    opri_ = 0;

    apu_.initialize();

    mbc_.resetRegisters();

    LOGI("MMU inicializada");
}

void Mmu::setCgbMode(bool on) {
    cgbMode_ = on;
    if (!on) {
        // Volver a DMG no debe dejar bancos CGB seleccionados.
        doubleSpeed_ = false;
        speedSwitchArmed_ = false;
        vbk_ = 0;
        svbk_ = 1;
        LOGI("MMU modo %s", on ? "CGB" : "DMG");
        return;
    }
    // Paridad con el boot del hardware (spec 013 RF-01): la BIOS del CGB
    // entrega el control en 0x0100 con la CPU YA a doble velocidad (KEY1
    // bit 7 = 1) para juegos CGB. Este core no corre esa BIOS, así que la
    // activación de CGB ES el post-boot y debe quedar a 2×. Queda como
    // atestiguado en dump PPOCO: sin esto KEY1=0x7E (1×) y el juego recibe
    // la mitad del presupuesto de CPU por VBlank (mundo sub-real + LCD
    // desfasado). El KEY1+STOP del juego sigue operando encima (toggle).
    doubleSpeed_ = true;
    LOGI("MMU modo CGB (post-boot 2x, paridad BIOS)");
}

bool Mmu::trySpeedSwitch() {
    if (!cgbMode_ || !speedSwitchArmed_) {
        return false;
    }
    doubleSpeed_ = !doubleSpeed_;
    speedSwitchArmed_ = false;
    LOGI("CGB doble velocidad %s", doubleSpeed_ ? "ON" : "OFF");
    return true;
}

/**
 * GDMA/HDMA (0xFF51-0xFF55). Spec 011 slice 2 (fidelidad CGB):
 * - GDMA (bit7 de FF55 = 0): copia síncrona completa (como el slice 1).
 * - HDMA (bit7 = 1): queda EN VUELO; la PPU llama runHdmaChunk() en cada
 *   HBlank y copia un bloque de 16 B por línea. Mientras hay bloques
 *   pendientes, FF55 lee bit7=0 con (restantes-1); al terminar, 0xFF.
 *   Escribir en FF55 durante el vuelo cancela (hardware).
 * - Con el LCD apagado no hay HBlanks: se copia síncrona, como el hardware.
 */
void Mmu::startHdma(uint8_t value) {
    // Cualquier escritura con una transferencia en vuelo la cancela.
    hdmaRemaining_ = 0;

    if (value == 0xFF) { // dejar de transferir
        ioRegisters_[0x55] = 0xFF;
        return;
    }

    const uint16_t src = static_cast<uint16_t>(
            (static_cast<uint16_t>(hdmaSrcHi_) << 8) | hdmaSrcLo_) & 0xFFF0;
    const uint16_t dst = static_cast<uint16_t>(
            0x8000 | (((static_cast<uint16_t>(hdmaDstHi_) << 8) | hdmaDstLo_) & 0x1FF0));
    const int bytes = ((value & 0x7F) + 1) * 16;

    // Sin LCD no hay HBlanks que troceen: copia instantánea (hardware).
    if ((value & 0x80) == 0 || (ioRegisters_[0x40] & 0x80) == 0) {
        // La fuente puede ser ROM, WRAM o SRAM.
        for (int i = 0; i < bytes; i++) {
            const uint8_t b = read(static_cast<uint16_t>(src + i));
            write(static_cast<uint16_t>(dst + i), b);
        }
        ioRegisters_[0x55] = 0xFF;
        return;
    }

    // HDMA en vuelo: la PPU copiará un bloque de 16 B por HBlank.
    hdmaSrc_ = src;
    hdmaDst_ = dst;
    hdmaRemaining_ = static_cast<uint8_t>((value & 0x7F) + 1);
}

/**
 * HBlank chunk (spec 011 slice 2): copia 16 B al VRAM y avanza los
 * punteros. El destino se enmascara con 0x1FF0 para que nunca salga de
 * los dos bancos de VRAM (hardware: el alto del destino queda fijo a
 * 0x80xx/0x9xx0 con envoltura). Al agotar los bloques, FF55 queda 0xFF.
 */
void Mmu::runHdmaChunk() {
    if (hdmaRemaining_ == 0) return;

    const uint16_t dst = static_cast<uint16_t>(0x8000 | (hdmaDst_ & 0x1FF0));
    for (int i = 0; i < 16; i++) {
        const uint8_t b = read(static_cast<uint16_t>(hdmaSrc_ + i));
        write(static_cast<uint16_t>(dst + i), b);
    }

    hdmaSrc_ = static_cast<uint16_t>(hdmaSrc_ + 16);
    hdmaDst_ = static_cast<uint16_t>((hdmaDst_ + 16) & 0x1FF0);
    if (--hdmaRemaining_ == 0) {
        ioRegisters_[0x55] = 0xFF;
    }
}

void Mmu::setRomLoader(RomLoader* romLoader) {
    romLoader_ = romLoader;
    mbc_.attach(romLoader_);
}

void Mmu::flushCartridge() {
    mbc_.flush();
}

uint8_t Mmu::read(uint16_t address) {
    if (address <= 0x7FFF) {
        return mbc_.readRom(address);
    } else if (address <= 0x9FFF) {
        // VRAM (banco seleccionado por VBK en CGB)
        return readVram(address - 0x8000);
    } else if (address <= 0xBFFF) {
        return mbc_.readRam(address);
    } else if (address <= 0xCFFF) {
        // RAM interna banco 0 (fijo)
        return readWram(0, address - 0xC000);
    } else if (address <= 0xDFFF) {
        // RAM interna banco 1-7 (SVBK). En DMG SVBK=1: mismo mapa de siempre.
        return readWram(effectiveWramBank(), address - 0xD000);
    } else if (address <= 0xFDFF) {
        // Espejo de 0xC000-0xDDFF (E000-EFFF banco 0, F000-FDFF banco SVBK)
        const uint16_t mirror = address - 0xE000;
        if (mirror < 0x1000) {
            return readWram(0, mirror);
        }
        return readWram(effectiveWramBank(), mirror - 0x1000);
    } else if (address <= 0xFE9F) {
        // OAM (40 sprites x 4 B: y, x, tile, flags)
        return oam_[address - 0xFE00];
    } else if (address == 0xFF0F) {
        // IF: vive en interruptFlags_, no en ioRegisters_
        return interruptFlags_;
    } else if (address <= 0xFF7F) {
        // Registros de E/S
        return readIo(address - 0xFF00);
    } else if (address <= 0xFFFE) {
        // HRAM
        return hram_[address - 0xFF80];
    } else if (address == 0xFFFF) {
        // Registro IE
        return interruptEnable_;
    }
    return 0;
}

void Mmu::write(uint16_t address, uint8_t value) {
#ifdef GPS96_DIAG_WRITE_HOOK
    if (g_diagWriteHook) g_diagWriteHook(address, value);
#endif
    if (address <= 0x7FFF) {
        mbc_.writeControl(address, value);
    } else if (address <= 0x9FFF) {
        // VRAM
        writeVram(address - 0x8000, value);
    } else if (address <= 0xBFFF) {
        mbc_.writeRam(address, value);
    } else if (address <= 0xCFFF) {
        // RAM interna banco 0
        writeWram(0, address - 0xC000, value);
    } else if (address <= 0xDFFF) {
        // RAM interna banco 1-7 (SVBK). En DMG SVBK=1: 0xD0xx sigue siendo
        // el segundo banco de 4 KB, nunca un alias de 0xC0xx.
        writeWram(effectiveWramBank(), address - 0xD000, value);
    } else if (address <= 0xFDFF) {
        // Espejo de RAM interna
        const uint16_t mirror = address - 0xE000;
        if (mirror < 0x1000) {
            writeWram(0, mirror, value);
        } else {
            writeWram(effectiveWramBank(), mirror - 0x1000, value);
        }
    } else if (address <= 0xFE9F) {
        // OAM
        oam_[address - 0xFE00] = value;
    } else if (address == 0xFF04) {
        // DIV: escribir resetea el registro y su contador interno
        divCounter_ = 0;
        writeIo(0x04, 0);
    } else if (address == 0xFF0F) {
        // IF coherente con la CPU
        interruptFlags_ = value;
    } else if (address == 0xFF46) {
        // DMA de OAM: los juegos (Gen 1/2 incluidos) copian su shadow OAM
        // de la WRAM a 0xFE00 por aquí. Sin esto la OAM queda a cero y no
        // hay sprites ni en pantalla ni para el extractor B4.
        writeIo(0x46, value);
        dmaOam(value);
    } else if (address <= 0xFF7F) {
        // Registros de E/S
        writeIo(address - 0xFF00, value);
    } else if (address <= 0xFFFE) {
        // HRAM
        hram_[address - 0xFF80] = value;
    } else if (address == 0xFFFF) {
        // Registro IE
        interruptEnable_ = value;
    }
}

uint16_t Mmu::readWord(uint16_t address) {
    uint8_t lo = read(address);
    uint8_t hi = read(address + 1);
    return (static_cast<uint16_t>(hi) << 8) | lo;
}

void Mmu::writeWord(uint16_t address, uint16_t value) {
    write(address, value & 0xFF);
    write(address + 1, (value >> 8) & 0xFF);
}

uint8_t Mmu::readVramBank(uint8_t bank, uint16_t address) const {
    if (address < 0x8000 || address > 0x9FFF) {
        return 0xFF;
    }
    const uint32_t off = static_cast<uint32_t>(bank & 0x01) * 0x2000
                       + static_cast<uint32_t>(address - 0x8000);
    return vram_[off];
}

void Mmu::writeVramBank(uint8_t bank, uint16_t address, uint8_t value) {
    if (address < 0x8000 || address > 0x9FFF) {
        return;
    }
    const uint32_t off = static_cast<uint32_t>(bank & 0x01) * 0x2000
                       + static_cast<uint32_t>(address - 0x8000);
    vram_[off] = value;
}

uint8_t Mmu::readVram(uint16_t address) const {
    return readVramBank(vbk_, static_cast<uint16_t>(0x8000 + address));
}

void Mmu::writeVram(uint16_t address, uint8_t value) {
    writeVramBank(vbk_, static_cast<uint16_t>(0x8000 + address), value);
}

uint8_t Mmu::readWram(uint8_t bank, uint16_t offset) const {
    if (bank > 7) {
        bank = 7;
    }
    const uint32_t off = static_cast<uint32_t>(bank) * 0x1000 + offset;
    if (off < sizeof(ram_)) {
        return ram_[off];
    }
    return 0;
}

void Mmu::writeWram(uint8_t bank, uint16_t offset, uint8_t value) {
    if (bank > 7) {
        bank = 7;
    }
    const uint32_t off = static_cast<uint32_t>(bank) * 0x1000 + offset;
    if (off < sizeof(ram_)) {
        ram_[off] = value;
    }
}

uint8_t Mmu::readIo(uint16_t address) {
    // 0xFF00 (P1/JOYP): protocolo de selección de la hardware de 8 bits.
    if (address == 0) {
        uint8_t sel = ioRegisters_[0] & 0x30;
        uint8_t result = 0xC0 | sel;
        if ((sel & 0x10) == 0) { // P14=0 → direcciones (bits 4-7 de joypad_)
            result |= (joypad_ >> 4) & 0x0F;
        }
        if ((sel & 0x20) == 0) { // P15=0 → acciones (bits 0-3 de joypad_)
            result |= joypad_ & 0x0F;
        }
        return result;
    }
    // Registros de Audio APU (0xFF10..0xFF3F)
    if (address >= 0x10 && address <= 0x3F) {
        return apu_.read(static_cast<uint16_t>(0xFF00 + address));
    }
    // Registros CGB. En DMG no existen: se leen como 0xFF.
    switch (address) {
        case 0x4D: // KEY1: bit7 velocidad actual, bit0 cambio armado
            if (!cgbMode_) return 0xFF;
            return static_cast<uint8_t>(0x7E | (doubleSpeed_ ? 0x80 : 0x00)
                                             | (speedSwitchArmed_ ? 0x01 : 0x00));
        case 0x4F: // VBK: banco VRAM activo
            return cgbMode_ ? static_cast<uint8_t>(0xFE | (vbk_ & 0x01)) : 0xFF;
        case 0x55: // HDMA5: bit7=1 terminado, bits 0-6 = bloques-1
            if (!cgbMode_) return 0xFF;
            return hdmaRemaining_
                   ? static_cast<uint8_t>((hdmaRemaining_ - 1) & 0x7F)
                   : 0xFF;
        case 0x68: // BCPS
            return cgbMode_ ? bcps_ : 0xFF;
        case 0x69: // BCPD
            return cgbMode_ ? bgPaletteRam_[bcps_ & 0x3F] : 0xFF;
        case 0x6A: // OCPS
            return cgbMode_ ? ocps_ : 0xFF;
        case 0x6B: // OCPD
            return cgbMode_ ? objPaletteRam_[ocps_ & 0x3F] : 0xFF;
        case 0x6C: // OPRI
            return cgbMode_ ? opri_ : 0xFF;
        case 0x70: // SVBK
            return cgbMode_ ? static_cast<uint8_t>(0xF8 | (svbk_ & 0x07)) : 0xFF;
        default:
            break;
    }
    if (address < sizeof(ioRegisters_)) {
        return ioRegisters_[address];
    }
    return 0;
}

void Mmu::writeIo(uint16_t address, uint8_t value) {
    // 0xFF00: solo importan los bits 4/5 (selección de mitad).
    if (address == 0) {
        ioRegisters_[0] = value & 0x30;
        return;
    }
    // Registros de Audio APU (0xFF10..0xFF3F)
    if (address >= 0x10 && address <= 0x3F) {
        apu_.write(static_cast<uint16_t>(0xFF00 + address), value);
        return;
    }
    // Registros CGB. En DMG se ignoran (no existen en el hardware).
    switch (address) {
        case 0x4D: // KEY1: solo se puede armar el cambio de velocidad
            if (cgbMode_ && (value & 0x01)) {
                speedSwitchArmed_ = true;
            }
            return;
        case 0x4F: // VBK
            if (cgbMode_) vbk_ = value & 0x01;
            return;
        case 0x51: hdmaSrcHi_ = value; ioRegisters_[0x51] = value; return;
        case 0x52: hdmaSrcLo_ = value; ioRegisters_[0x52] = value; return;
        case 0x53: hdmaDstHi_ = value; ioRegisters_[0x53] = value; return;
        case 0x54: hdmaDstLo_ = value; ioRegisters_[0x54] = value; return;
        case 0x55: // HDMA5: arranca GDMA (bit7=0) o HDMA (bit7=1)
            if (cgbMode_) startHdma(value);
            return;
        case 0x68: // BCPS: índice + auto-incremento (bit7)
            if (cgbMode_) bcps_ = value & 0xBF;
            return;
        case 0x69: // BCPD
            if (cgbMode_) {
                bgPaletteRam_[bcps_ & 0x3F] = value;
                if (bcps_ & 0x80) {
                    bcps_ = static_cast<uint8_t>(0x80 | ((bcps_ + 1) & 0x3F));
                }
            }
            return;
        case 0x6A: // OCPS
            if (cgbMode_) ocps_ = value & 0xBF;
            return;
        case 0x6B: // OCPD
            if (cgbMode_) {
                objPaletteRam_[ocps_ & 0x3F] = value;
                if (ocps_ & 0x80) {
                    ocps_ = static_cast<uint8_t>(0x80 | ((ocps_ + 1) & 0x3F));
                }
            }
            return;
        case 0x6C: // OPRI
            if (cgbMode_) opri_ = value;
            return;
        case 0x70: // SVBK: 0 se comporta como 1
            if (cgbMode_) svbk_ = value & 0x07;
            return;
        default:
            break;
    }
    // 0xFF02 (SC): arrancar transferencia serie. Sin cable, el reloj interno
    // completa el byte solo (SB = 0xFF) y levanta la IRQ de serie, igual que
    // una hardware de 8 bits sin socio: la ROM deja de esperar y sigue.
    if (address == 0x02) {
        ioRegisters_[2] = value;
        if ((value & 0x80) != 0 && (value & 0x01) != 0) {
            serialActive_ = true;
            serialCycles_ = 4096; // 1 byte a 8192 Hz
        } else {
            serialActive_ = false; // reloj externo: sin socio no hay byte
        }
        return;
    }
    if (address < sizeof(ioRegisters_)) {
        ioRegisters_[address] = value;
    }
}

void Mmu::dmaOam(uint8_t sourceHigh) {
    const uint16_t src = static_cast<uint16_t>(sourceHigh) << 8;
    // Copia síncrona de 160 B. En hardware tarda ~160 µs, pero el efecto
    // neto (OAM lista al volver del write) es el mismo para los juegos.
    for (int i = 0; i < 160; i++) {
        oam_[i] = read(static_cast<uint16_t>(src + i));
    }
}

void Mmu::updateTimers(int cycles) {
    apu_.step(cycles);

    // Serie (0xFF01/0xFF02): completar la transferencia interna aunque no haya
    // cable. Va antes del early-return del timer para que no dependa de TAC.
    if (serialActive_) {
        serialCycles_ -= cycles;
        if (serialCycles_ <= 0) {
            serialActive_ = false;
            ioRegisters_[0x01] = 0xFF;  // SB: sin socio entra 0xFF
            ioRegisters_[0x02] &= 0x7F; // SC bit7 = 0 → transferencia lista
            requestInterrupt(3);        // IRQ de serie
        }
    }

    // DIV: 16384 Hz = +1 cada 256 ciclos de reloj
    divCounter_ += cycles;
    while (divCounter_ >= 256) {
        divCounter_ -= 256;
        ioRegisters_[0x04]++;
    }

    // TIMA según TAC (0xFF07): bit 2 enable, bits 1-0 frecuencia
    uint8_t tac = ioRegisters_[0x07];
    if ((tac & 0x04) == 0) return;

    int threshold = 1024;
    switch (tac & 0x03) {
        case 0: threshold = 1024; break; // 4096 Hz
        case 1: threshold = 16; break;   // 262144 Hz
        case 2: threshold = 64; break;   // 65536 Hz
        case 3: threshold = 256; break;  // 16384 Hz
    }

    timaCounter_ += cycles;
    while (timaCounter_ >= threshold) {
        timaCounter_ -= threshold;
        uint8_t tima = ioRegisters_[0x05] + 1;
        if (tima == 0) { // Overflow: recarga con TMA + IRQ de timer
            tima = ioRegisters_[0x06];
            requestInterrupt(2);
        }
        ioRegisters_[0x05] = tima;
    }
}

} // namespace gpsquest96
