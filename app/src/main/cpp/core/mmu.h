#ifndef GPSQUEST96_MMU_H
#define GPSQUEST96_MMU_H

#include "core/mbc.h"
#include "core/apu.h"

#include <cstdint>
#include <cstring>

namespace gpsquest96 {

/**
 * MMU (Memory Management Unit) — Unidad de memoria de hardware de 8 bits.
 * <p>
 * Gestiona el mapa de memoria:
 * - 0x0000-0x3FFF: ROM Bank 0 (fijo)
 * - 0x4000-0x7FFF: ROM Bank 1-N (conmutable)
 * - 0x8000-0x9FFF: VRAM (Memoria de video)
 * - 0xA000-0xBFFF: RAM externa (cartucho)
 * - 0xC000-0xCFFF: RAM interna Bank 0
 * - 0xD000-0xDFFF: RAM interna Bank 1-7
 * - 0xE000-0xFDFF: Espejo de RAM interna
 * - 0xFE00-0xFE9F: OAM (Attribute de objetos)
 * - 0xFF00-0xFF7F: Registros de E/S
 * - 0xFF80-0xFFFE: HRAM (RAM alta)
 * - 0xFFFF: Registro de interrupciones
 */
class Mmu {
public:
    Mmu();
    ~Mmu();

    /**
     * Inicializa la MMU.
     */
    void initialize();

    /**
     * Lee un byte de memoria.
     */
    uint8_t read(uint16_t address);

    /**
     * Escribe un byte en memoria.
     */
    void write(uint16_t address, uint8_t value);

    /**
     * Lee una palabra (2 bytes) de memoria.
     */
    uint16_t readWord(uint16_t address);

    /**
     * Escribe una palabra (2 bytes) en memoria.
     */
    void writeWord(uint16_t address, uint16_t value);

    /**
     * Establece el puntero al cargador de ROM.
     */
    void setRomLoader(class RomLoader* romLoader);
    void flushCartridge();

    /**
     * Modo CGB (8 bits a color). Se llama al abrir la ROM según la cabecera
     * 0x143. En DMG (false) los registros/bancos CGB no existen y el
     * comportamiento es el de siempre.
     */
    void setCgbMode(bool on);
    bool isCgbMode() const { return cgbMode_; }

    /**
     * HDMA en vuelo (spec 011 slice 2): la PPU llama runHdmaChunk() en cada
     * HBlank; la transferencia activa se revela vía 0xFF55 (bit7=0).
     */
    bool hdmaActive() const { return hdmaRemaining_ != 0; }
    /** Copia el siguiente bloque de 16 B si hay una HDMA en vuelo. */
    void runHdmaChunk();

    /** Velocidad doble (KEY1 bit 7). Solo tiene efecto en CGB. */
    bool isDoubleSpeed() const { return doubleSpeed_; }

    /**
     * Fija el modo de velocidad directamente. Uso: paridad con el estado
     * post-boot de la BIOS CGB (spec 013 RF-01): al cargar un ROM CGB el
     * hardware sale del boot YA a doble velocidad (KEY1 bit 7 = 1). El
     * switch por KEY1+STOP del juego sigue funcionando encima (togglea).
     */
    void setDoubleSpeed(bool on) { doubleSpeed_ = on; }

    /**
     * Intenta completar el cambio de velocidad armado por KEY1. Se llama
     * desde STOP. Devuelve true si cambió de velocidad.
     */
    bool trySpeedSwitch();

    /**
     * Lectura/escritura de VRAM por banco explícito. La PPU usa el banco 0
     * para tiles y el 1 para atributos CGB; así el estado de VBK no corrompe
     * el render.
     */
    uint8_t readVramBank(uint8_t bank, uint16_t address) const;
    void writeVramBank(uint8_t bank, uint16_t address, uint8_t value);

    /** Paletas CGB (64 bytes: 8 paletas x 4 colores x 2 bytes). */
    uint8_t readBgPalette(uint8_t index) const { return bgPaletteRam_[index & 0x3F]; }
    uint8_t readObjPalette(uint8_t index) const { return objPaletteRam_[index & 0x3F]; }

    uint8_t getVbk() const { return vbk_; }
    uint8_t getSvbk() const { return svbk_; }
    uint8_t getOpri() const { return opri_; }

    /**
     * Diagnóstico SRAM cruda (mapeo de event flags, VISION.md B3).
     * Lee el .sav en memoria por offset, sin bancos ni interpretación.
     */
    uint32_t sramSize() { return mbc_.sramSize(); }
    uint16_t currentRomBank() const { return mbc_.romBank(); }
    uint8_t readSramRaw(uint32_t off) { return mbc_.readSramRaw(off); }

    /**
     * Avanza DIV/TIMA según ciclos consumidos (llamar en cada step).
     */
    void updateTimers(int cycles);

    /**
     * Activa un bit de IF (0=VBlank, 1=STAT, 2=Timer, 3=Serial, 4=Joypad).
     */
    void requestInterrupt(uint8_t bit) { interruptFlags_ |= (1 << bit); }

    /**
     * Obtiene un puntero a la VRAM.
     */
    uint8_t* getVram() { return vram_; }

    /**
     * Obtiene un puntero a la RAM interna.
     */
    uint8_t* getRam() { return ram_; }

    /**
     * Obtiene un puntero a los registros de E/S.
     */
    uint8_t* getIoRegisters() { return ioRegisters_; }

    /**
     * Obtiene un puntero a la HRAM.
     */
    uint8_t* getHram() { return hram_; }

    /**
     * Obtiene el valor del registro de interrupciones.
     */
    uint8_t getInterruptEnable() const { return interruptEnable_; }

    /**
     * Establece el valor del registro de interrupciones.
     */
    void setInterruptEnable(uint8_t value) { interruptEnable_ = value; }

    /**
     * Obtiene el valor del registro de flags de interrupción.
     */
    uint8_t getInterruptFlags() const { return interruptFlags_; }

    /**
     * Establece el valor del registro de flags de interrupción.
     */
    void setInterruptFlags(uint8_t value) { interruptFlags_ = value; }

    /**
     * Estado de botones del joypad (0 = presionado). Bits estándar P1:
     * 0=A, 1=B, 2=Select, 3=Start, 4=Right, 5=Left, 6=Up, 7=Down.
     */
    void setJoypad(uint8_t buttons) {
        if (buttons == joypad_) return;
        const uint8_t old = joypad_;
        joypad_ = buttons;

        // IRQ de joypad (IF bit 4): flanco de bajada en las líneas
        // actualmente seleccionadas por P14/P15. Crystal y otros juegos
        // duermen en HALT esperando esta interrupción; sin ella, el
        // botón se escribe en P1 pero el juego no despierta.
        const uint8_t sel = ioRegisters_[0] & 0x30;
        uint8_t oldLines = 0x0F;
        uint8_t newLines = 0x0F;
        if ((sel & 0x10) == 0) {
            oldLines &= static_cast<uint8_t>((old >> 4) & 0x0F);
            newLines &= static_cast<uint8_t>((buttons >> 4) & 0x0F);
        }
        if ((sel & 0x20) == 0) {
            oldLines &= static_cast<uint8_t>(old & 0x0F);
            newLines &= static_cast<uint8_t>(buttons & 0x0F);
        }
        if ((oldLines & static_cast<uint8_t>(~newLines)) != 0) {
            requestInterrupt(4);
        }
    }
    uint8_t getJoypad() const { return joypad_; }

private:
    // Memoria. En CGB hay 2 bancos de VRAM (VBK) y 8 de WRAM (SVBK);
    // en DMG solo se usan el banco 0 de VRAM y los bancos 0-1 de WRAM,
    // que es exactamente el mapa de siempre (0xC000 banco 0, 0xD000 banco 1).
    uint8_t vram_[0x2000 * 2];    // 16KB VRAM (2 bancos)
    uint8_t ram_[0x1000 * 8];     // 32KB RAM interna (8 bancos de 4KB)
    uint8_t ioRegisters_[0x80];   // Registros de E/S
    uint8_t hram_[0x7F];          // HRAM (127 bytes)
    uint8_t interruptEnable_;     // Registro IE
    uint8_t interruptFlags_;      // Registro IF
    uint8_t oam_[160];            // OAM: 40 sprites x 4 B
    uint8_t joypad_ = 0xFF;       // Botones (0 = presionado); 0xFF = ninguno
    bool serialActive_ = false;   // Transferencia serie en curso
    int serialCycles_ = 0;            // Ciclos restantes del byte serie

    // Estado CGB
    bool cgbMode_ = false;
    bool doubleSpeed_ = false;
    bool speedSwitchArmed_ = false;
    uint8_t vbk_ = 0;             // 0xFF4F: banco VRAM activo (0/1)
    uint8_t svbk_ = 1;            // 0xFF70: banco WRAM activo (1-7; 0 => 1)
    uint8_t bgPaletteRam_[64];    // 0xFF68/69
    uint8_t objPaletteRam_[64];   // 0xFF6A/6B
    uint8_t bcps_ = 0;            // índice BG (bit7 = auto-incremento)
    uint8_t ocps_ = 0;            // índice OBJ
    // HDMA/GDMA (0xFF51-0xFF55). GDMA (bit7 de FF55=0) y LCD apagado: copia
    // síncrona completa. HDMA (bit7=1): queda EN VUELO y la PPU copia 16 B
    // por cada HBlank (spec 011 slice 2, fidelidad hardware).
    uint8_t hdmaSrcHi_ = 0, hdmaSrcLo_ = 0;
    uint8_t hdmaDstHi_ = 0, hdmaDstLo_ = 0;
    uint8_t hdmaRemaining_ = 0;   // bloques pendientes; 0 = sin transferencia
    uint16_t hdmaSrc_ = 0;        // fuente absoluta del siguiente bloque
    uint16_t hdmaDst_ = 0;        // destino absoluto del siguiente bloque
    uint8_t opri_ = 0;            // 0xFF6C: prioridad OBJ CGB

    // ROM / cartucho
    class RomLoader* romLoader_;
    Mbc mbc_;
    Apu apu_;

public:
    Apu& getApu() { return apu_; }

    // Timers (DIV 16384 Hz, TIMA según TAC)
    int divCounter_;
    int timaCounter_;

    // Utilidades
    uint8_t readVram(uint16_t address) const;
    void writeVram(uint16_t address, uint8_t value);
    uint8_t effectiveWramBank() const { return (svbk_ & 0x07) ? (svbk_ & 0x07) : 1; }
    uint8_t readWram(uint8_t bank, uint16_t offset) const;
    void writeWram(uint8_t bank, uint16_t offset, uint8_t value);
    uint8_t readIo(uint16_t address);
    void writeIo(uint16_t address, uint8_t value);
    void startHdma(uint8_t value);
    /** Copia DMA de 160 B a la OAM (registro 0xFF46). */
    void dmaOam(uint8_t sourceHigh);
};

} // namespace gpsquest96

#endif // GPSQUEST96_MMU_H
