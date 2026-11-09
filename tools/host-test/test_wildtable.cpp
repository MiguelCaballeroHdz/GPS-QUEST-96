// Host test del lector de tablas de encuentro de Gen 2 (spec 009).
//
// Sin ROM real: crea tablas sintéticas con la estructura de Gen 2 y verifica
// el escaneo, los rangos y el rechazo de una tabla corrupta.
// Con ROM real: define GPSQUEST_ROM_GEN2 y comprueba las cuatro tablas y
// la entrada de ruta inicial (grupo 24, mapa 3). Ejecutar con run.ps1.
#include "core/rom_loader.h"
#include "core/wild_table.h"

#include <cstdint>
#include <cstdio>
#include <cstdlib>
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

static bool writeFile(const std::string& path, const std::vector<uint8_t>& data) {
    FILE* f = std::fopen(path.c_str(), "wb");
    if (f == nullptr) {
        return false;
    }
    const size_t n = std::fwrite(data.data(), 1, data.size(), f);
    std::fclose(f);
    return n == data.size();
}

static void putGrassTable(std::vector<uint8_t>& rom, size_t offset, int entries,
                          int corruptEntry = -1) {
    for (int e = 0; e < entries; ++e) {
        const size_t p = offset + static_cast<size_t>(e) * 47;
        rom[p] = 24;             // grupo NEW_BARK
        rom[p + 1] = static_cast<uint8_t>(3 + e);
        rom[p + 2] = 25;         // morn
        rom[p + 3] = 25;         // day
        rom[p + 4] = 25;         // nite
        for (int period = 0; period < 3; ++period) {
            for (int slot = 0; slot < 7; ++slot) {
                const size_t base = p + 5 + period * 14 + slot * 2;
                rom[base] = static_cast<uint8_t>(2 + (slot % 3));
                rom[base + 1] = static_cast<uint8_t>(period == 2 ? 163 : 16);
            }
        }
    }
    if (corruptEntry >= 0 && corruptEntry < entries) {
        const size_t p = offset + static_cast<size_t>(corruptEntry) * 47;
        rom[p + 6] = 0; // especie inválida en el primer par
    }
    rom[offset + static_cast<size_t>(entries) * 47] = 0xFF;
}

static void putWaterTable(std::vector<uint8_t>& rom, size_t offset, int entries) {
    for (int e = 0; e < entries; ++e) {
        const size_t p = offset + static_cast<size_t>(e) * 9;
        rom[p] = 24;
        rom[p + 1] = static_cast<uint8_t>(3 + e);
        rom[p + 2] = 5; // 2 percent
        for (int slot = 0; slot < 3; ++slot) {
            rom[p + 3 + slot * 2] = static_cast<uint8_t>(15 + slot);
            rom[p + 4 + slot * 2] = 0xC2; // Wooper
        }
    }
    rom[offset + static_cast<size_t>(entries) * 9] = 0xFF;
}

static bool makeSyntheticRom(const std::string& path, bool corrupt) {
    std::vector<uint8_t> rom(0x4000, 0x00);
    putGrassTable(rom, 0x1000, corrupt ? 30 : 20, corrupt ? 5 : -1);
    putWaterTable(rom, 0x2000, 10);
    return writeFile(path, rom);
}

int main(int argc, char** argv) {
    const std::string dir = (argc > 1) ? argv[1] : ".";
    const std::string romPath = dir + "/test_wildtable.gbc";
    const std::string corruptPath = dir + "/test_wildtable_corrupt.gbc";

    // --- Tabla sintética válida ---
    CHECK(makeSyntheticRom(romPath, false));
    {
        gpsquest96::RomLoader loader;
        CHECK(loader.open(romPath.c_str()));
        gpsquest96::WildTableReader reader;
        CHECK(reader.load(loader));
        CHECK(reader.foundAny());
        CHECK(reader.grassEntries().size() == 20);
        CHECK(reader.waterEntries().size() == 10);

        gpsquest96::WildTable table;
        CHECK(reader.lookup(24, 3, gpsquest96::WildTableKind::GRASS, table));
        CHECK(table.kind == gpsquest96::WildTableKind::GRASS);
        CHECK(table.rates[0] == 25 && table.rates[1] == 25 && table.rates[2] == 25);
        CHECK(table.slotsPerPeriod == 7);
        CHECK(table.levels[0][0] == 2);
        CHECK(table.species[0][0] == 16);   // Pidgey
        CHECK(table.species[2][0] == 163);  // una criatura de noche

        gpsquest96::WildTable water;
        CHECK(reader.lookup(24, 3, gpsquest96::WildTableKind::WATER, water));
        CHECK(water.kind == gpsquest96::WildTableKind::WATER);
        CHECK(water.rates[0] == 5);
        CHECK(water.slotsPerPeriod == 3);
        CHECK(water.species[0][0] == 0xC2); // Wooper

        gpsquest96::WildTable missing;
        CHECK(!reader.lookup(24, 99, missing));
    }

    // --- Tabla corrupta: la cola no debe aceptarse como tabla nueva ---
    CHECK(makeSyntheticRom(corruptPath, true));
    {
        gpsquest96::RomLoader loader;
        CHECK(loader.open(corruptPath.c_str()));
        gpsquest96::WildTableReader reader;
        CHECK(reader.load(loader));
        CHECK(reader.grassEntries().empty());
        gpsquest96::WildTable table;
        CHECK(!reader.lookup(24, 3, gpsquest96::WildTableKind::GRASS, table));
    }

    // --- ROM real opcional: Gen 2 español ---
    const char* realPath = std::getenv("GPSQUEST_ROM_GEN2");
    if (realPath != nullptr && realPath[0] != '\0') {
        gpsquest96::RomLoader loader;
        CHECK(loader.open(realPath));
        gpsquest96::WildTableReader reader;
        CHECK(reader.load(loader));
        CHECK(reader.foundAny());
        CHECK(reader.blocks().size() == 4);
        CHECK(reader.grassEntries().size() == 91); // 61 Johto + 30 Kanto
        CHECK(reader.waterEntries().size() == 62); // 38 Johto + 24 Kanto

        gpsquest96::WildTable route29;
        CHECK(reader.lookup(24, 3, gpsquest96::WildTableKind::GRASS, route29));
        CHECK(route29.romOffset == 0x2A5F0);
        CHECK(route29.entryOffset == 0x2AE04);
        CHECK(route29.tableEntryCount == 61);
        CHECK(route29.rates[0] == 25 && route29.rates[1] == 25 &&
              route29.rates[2] == 25);
        CHECK(route29.species[0][0] == 16);   // Pidgey
        CHECK(route29.species[0][1] == 161);  // Sentret
        CHECK(route29.species[2][0] == 163);  // una criatura

        gpsquest96::WildTable ruinsWater;
        CHECK(reader.lookup(3, 22, gpsquest96::WildTableKind::WATER,
                            ruinsWater));
        CHECK(ruinsWater.rates[0] == 5);
        CHECK(ruinsWater.species[0][0] == 0xC2); // Wooper
    } else {
        std::printf("SKIP real ROM: define GPSQUEST_ROM_GEN2 para las "
                    "comprobaciones de Gen 2.\n");
    }

    std::printf("test_wildtable: %d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
