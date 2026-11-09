#pragma once

// ÉPICO ARM (Fase GBA) — slice 4: bus mínimo solo-ROM (solo lectura).
// Cabecera autocontenida (inline) para no tocar el CMake todavía.
// Mapea la ROM en 0x08000000-0x0DFFFFFF con espejos (como el bus GBA).
// Escrituras: ignoradas en este slice. Regiones WRAM/VRAM/OAM/paletas
// llegan con la MMU32 completa. Límite honesto: la ROM se carga entera
// (4-32 MB, despreciable en el POCO; el streaming por fetch vendrá si
// el perfil térmico lo pide).

#include <cstdint>
#include <cstdio>
#include <vector>

class Mmu32 {
public:
    static constexpr uint32_t ROM_BASE = 0x08000000u;
    static constexpr uint32_t ROM_MIRROR_END = 0x0DFFFFFFu;
    static constexpr size_t MAX_ROM = 32u * 1024u * 1024u;

    Mmu32() = default;

    bool open(const char* path) {
        rom_.clear();
        if (path == nullptr) return false;
        FILE* f = std::fopen(path, "rb");
        if (f == nullptr) return false;
        std::fseek(f, 0, SEEK_END);
        long n = std::ftell(f);
        std::fseek(f, 0, SEEK_SET);
        if (n <= 0 || static_cast<size_t>(n) > MAX_ROM) {
            std::fclose(f);
            return false;
        }
        rom_.resize(static_cast<size_t>(n));
        size_t got = std::fread(rom_.data(), 1, rom_.size(), f);
        std::fclose(f);
        return got == rom_.size();
    }

    size_t romSize() const { return rom_.size(); }
    bool ready() const { return !rom_.empty(); }

    uint8_t read8(uint32_t addr) const {
        size_t off = romOffset(addr);
        return (off < rom_.size()) ? rom_[off] : 0;
    }

    // Little-endian, con rotación natural por lecturas no alineadas
    // (el ARM7 real rota; aquí se compone sin fallar).
    uint16_t read16(uint32_t addr) const {
        uint16_t lo = read8(addr);
        uint16_t hi = read8(addr + 1);
        return static_cast<uint16_t>(lo | (hi << 8));
    }

    uint32_t read32(uint32_t addr) const {
        uint32_t b0 = read8(addr);
        uint32_t b1 = read8(addr + 1);
        uint32_t b2 = read8(addr + 2);
        uint32_t b3 = read8(addr + 3);
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    void write8(uint32_t /*addr*/, uint8_t /*v*/) {}
    void write16(uint32_t /*addr*/, uint16_t /*v*/) {}
    void write32(uint32_t /*addr*/, uint32_t /*v*/) {}

private:
    // ROM en 0x08-0x0D con espejo cada tamaño de ROM (potencia de 2 real
    // del cartucho; aquí módulo simple sobre lo cargado).
    size_t romOffset(uint32_t addr) const {
        if (addr < ROM_BASE || addr > ROM_MIRROR_END || rom_.empty()) {
            return rom_.size(); // fuera de rango = abierto (0)
        }
        return static_cast<size_t>((addr - ROM_BASE) % rom_.size());
    }

    std::vector<uint8_t> rom_;
};
