// Host test de guardado real (.sav). No toca Android: usa RomLoader + Mbc.
// Crea una ROM sintética con cabecera MBC3+batería, escribe SRAM, hace flush,
// reabre y comprueba que el progreso sobrevive. Ejecutar con run.ps1.
#include "core/rom_loader.h"
#include "core/mbc.h"

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

static int failures = 0;
static int checks = 0;

#define CHECK(cond) do { \
    ++checks; \
    if (!(cond)) { \
        std::printf("FAIL line %d: %s\n", __LINE__, #cond); \
        ++failures; \
    } \
} while (0)

static long fileSize(const std::string& path) {
    FILE* f = std::fopen(path.c_str(), "rb");
    if (f == nullptr) return -1;
    std::fseek(f, 0, SEEK_END);
    const long n = std::ftell(f);
    std::fclose(f);
    return n;
}

static bool readByteAt(const std::string& path, long offset, uint8_t* out) {
    FILE* f = std::fopen(path.c_str(), "rb");
    if (f == nullptr) return false;
    if (std::fseek(f, offset, SEEK_SET) != 0) {
        std::fclose(f);
        return false;
    }
    const int c = std::fgetc(f);
    std::fclose(f);
    if (c == EOF) return false;
    *out = static_cast<uint8_t>(c);
    return true;
}

/**
 * ROM sintética mínima: 2 bancos (32 KB), título, tipo de cartucho y tamaño
 * de RAM. `readHeader` no valida checksum, pero se calcula igual para que el
 * archivo sea realista.
 */
static bool makeRom(const std::string& path, uint8_t cartType, uint8_t ramCode) {
    std::vector<uint8_t> rom(0x8000, 0x00);
    const char* title = "TESTSAV";
    std::memcpy(&rom[0x134], title, std::strlen(title));
    rom[0x143] = 0xC0; // CGB-only, como Gen 2
    rom[0x147] = cartType;
    rom[0x148] = 0x00; // 2 bancos
    rom[0x149] = ramCode;

    uint8_t chk = 0;
    for (int i = 0x134; i <= 0x14C; i++) {
        chk = static_cast<uint8_t>(chk - rom[i] - 1);
    }
    rom[0x14D] = chk;

    FILE* f = std::fopen(path.c_str(), "wb");
    if (f == nullptr) return false;
    const size_t n = std::fwrite(rom.data(), 1, rom.size(), f);
    std::fclose(f);
    return n == rom.size();
}

int main(int argc, char** argv) {
    const std::string dir = (argc > 1) ? argv[1] : ".";
    const std::string rom = dir + "/testsav.gbc";
    const std::string sav = dir + "/testsav.sav";
    const std::string bak = sav + ".bak";

    std::remove(sav.c_str());
    std::remove(bak.c_str());
    std::remove(rom.c_str());

    CHECK(makeRom(rom, 0x10, 0x03)); // MBC3 + RTC + RAM + batería, 32 KB

    // --- Primera sesión: escribir en dos bancos y vaciar a disco ---
    {
        gpsquest96::RomLoader loader;
        CHECK(loader.open(rom.c_str()));
        gpsquest96::Mbc mbc;
        mbc.attach(&loader);
        CHECK(mbc.kind() == gpsquest96::Mbc::Kind::MBC3);
        CHECK(mbc.sramSize() == 32768);
        CHECK(fileSize(sav) == -1); // aún no hay .sav

        mbc.writeControl(0x0000, 0x0A); // habilitar RAM
        mbc.writeRam(0xA000, 0x42);     // banco 0, offset 0
        mbc.writeControl(0x4000, 0x01); // banco 1
        mbc.writeRam(0xA000, 0x99);     // banco 1, offset 0
        mbc.flush();
    }
    CHECK(fileSize(sav) == 32768);
    CHECK(fileSize(bak) == -1); // primera escritura: no hay anterior que respaldar

    // --- Segunda sesión: el .sav se carga y conserva ambos bancos ---
    {
        gpsquest96::RomLoader loader;
        CHECK(loader.open(rom.c_str()));
        gpsquest96::Mbc mbc;
        mbc.attach(&loader);
        mbc.writeControl(0x0000, 0x0A);
        CHECK(mbc.readRam(0xA000) == 0x42);
        mbc.writeControl(0x4000, 0x01);
        CHECK(mbc.readRam(0xA000) == 0x99);

        // Cambio + flush: debe crear .bak con el contenido anterior.
        mbc.writeControl(0x4000, 0x00);
        mbc.writeRam(0xA000, 0x43);
        mbc.flush();
    }
    CHECK(fileSize(bak) == 32768);
    uint8_t backupFirst = 0;
    CHECK(readByteAt(bak, 0, &backupFirst));
    CHECK(backupFirst == 0x42);

    // --- Tercera sesión: se cargó el valor nuevo, no el respaldo ---
    {
        gpsquest96::RomLoader loader;
        CHECK(loader.open(rom.c_str()));
        gpsquest96::Mbc mbc;
        mbc.attach(&loader);
        mbc.writeControl(0x0000, 0x0A);
        CHECK(mbc.readRam(0xA000) == 0x43);
    }

    // --- Sin batería no se escribe .sav ---
    const std::string romNb = dir + "/testsav_nb.gbc";
    const std::string savNb = dir + "/testsav_nb.sav";
    std::remove(savNb.c_str());
    std::remove(romNb.c_str());
    CHECK(makeRom(romNb, 0x01, 0x02)); // MBC1 + RAM, sin batería
    {
        gpsquest96::RomLoader loader;
        CHECK(loader.open(romNb.c_str()));
        gpsquest96::Mbc mbc;
        mbc.attach(&loader);
        mbc.writeControl(0x0000, 0x0A);
        mbc.writeRam(0xA000, 0x55);
        mbc.flush();
    }
    CHECK(fileSize(savNb) == -1);

    // Limpieza (no deja basura en el árbol).
    std::remove(rom.c_str());
    std::remove(romNb.c_str());
    std::remove(sav.c_str());
    std::remove(bak.c_str());
    std::remove(savNb.c_str());

    if (failures == 0) {
        std::printf("OK: %d checks\n", checks);
        return 0;
    }
    std::printf("FAILED: %d de %d checks\n", failures, checks);
    return 1;
}
