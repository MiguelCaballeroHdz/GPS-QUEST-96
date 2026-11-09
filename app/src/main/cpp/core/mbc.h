#ifndef GPSQUEST96_MBC_H
#define GPSQUEST96_MBC_H

#include <cstdint>
#include <vector>

namespace gpsquest96 {

class RomLoader;

/**
 * Controlador de cartucho GB/GBC (MBC1 / MBC3 / MBC5 + SRAM).
 * No toca el archivo ROM; solo traduce bancos y RAM externa 0xA000-0xBFFF.
 */
class Mbc {
public:
    enum class Kind : uint8_t {
        ROM_ONLY = 0,
        MBC1 = 1,
        MBC3 = 3,
        MBC5 = 5
    };

    Mbc();
    ~Mbc();

    void attach(RomLoader* loader);
    void detach();
    void resetRegisters();
    void flush();

    uint8_t readRom(uint16_t address);
    uint8_t readRam(uint16_t address);
    void writeRam(uint16_t address, uint8_t value);
    void writeControl(uint16_t address, uint8_t value);

    uint16_t romBank() const { return romBank_; }
    uint8_t ramBank() const { return ramBank_; }
    Kind kind() const { return kind_; }

    /**
     * Lectura cruda de la SRAM por offset (0 = inicio del .sav).
     * Solo diagnóstico para mapear event flags reales (VISION.md B3);
     * no interpreta nada, no escribe, fuera de rango devuelve 0xFF.
     */
    uint32_t sramSize() const { return ramSize_; }
    uint8_t readSramRaw(uint32_t off) const {
        return (off < sram_.size()) ? sram_[off] : 0xFF;
    }

private:
    RomLoader* loader_;
    Kind kind_;
    bool configured_;
    bool hasBattery_;
    bool hasRtc_;
    bool ramEnabled_;
    bool ramDirty_;
    bool mbc1ModeRam_;
    bool rtcLatchPrev_;
    uint16_t romBank_;
    uint8_t ramBank_;
    uint8_t mbc1High_;
    uint8_t mbc5BankHi_;
    uint32_t romBanks_;
    uint32_t ramSize_;
    uint8_t rtcLatched_[5];
    char savPath_[512];
    std::vector<uint8_t> sram_;

    void configureFromHeader();
    void loadSav();
    void saveSav();
    uint32_t mappedRomOffset(uint16_t address) const;
    uint16_t maskRomBank(uint32_t bank) const;
    void latchRtc();
};

} // namespace gpsquest96

#endif // GPSQUEST96_MBC_H
