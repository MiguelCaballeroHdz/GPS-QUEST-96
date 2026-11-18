// Host test del lector de tablas de encuentro de Gen 2 (spec 009).
//
// Sin ROM real: crea tablas sintéticas con la estructura de Gen 2 y verifica
// el escaneo, los rangos y el rechazo de una tabla corrupta.
// Con ROM real: define GPSQUEST_ROM_GEN2 y comprueba las cuatro tablas y
// la entrada de ruta inicial (grupo 24, mapa 3). Ejecutar con run.ps1.
#include "core/rom_loader.h"
#include "core/wild_table.h"
#include "core/wild_routine.h"

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


// --- Localizador de rutinas (spec 009, slice 3) ---
// Bytes de TryWildEncounter segun el desensamblado publico de Crystal
// (pret/pokecrystal, engine/overworld/wildmons.asm):
//   call EncRate; jr nc; call ChooseWild; jr nz; call CheckRepel; jr nc
//   xor a; ret; xor a; ld [wTempWildMonSpecies],a; ld [wBattleType],a
//   ld a,1; and a; ret
static void putTryWildEncounter(std::vector<uint8_t>& rom, size_t off,
                                uint16_t tempSpecies, uint16_t battleType) {
    const uint8_t code[] = {
        0xCD, 0x00, 0x42, 0x30, 0x10, 0xCD, 0x00, 0x43, 0x20, 0x10,
        0xCD, 0x00, 0x44, 0x30, 0x10, 0xAF, 0xC9, 0xAF, 0xEA,
        static_cast<uint8_t>(tempSpecies & 0xFF),
        static_cast<uint8_t>(tempSpecies >> 8),
        0xEA,
        static_cast<uint8_t>(battleType & 0xFF),
        static_cast<uint8_t>(battleType >> 8),
        0x3E, 0x01, 0xA7, 0xC9
    };
    std::memcpy(&rom[off], code, sizeof(code));
}

// Ancla de ChooseWildEncounter: cuatro rondas
//   call Random; cp <pct>; jr c; inc b
// seguidas de ld a,b; ld [wCurPartyLevel],a; ld b,[hl].
// Los inmediatos de cp son los de la macro `percent` (x * 256 / 100).
static void putChooseWildEncounter(std::vector<uint8_t>& rom, size_t off,
                                   uint16_t curLevel) {
    const uint8_t code[] = {
        0xCD, 0x50, 0x01, 0xFE, 0x59, 0x38, 0x02, 0x04,
        0xFE, 0xA6, 0x38, 0x02, 0x04, 0xFE, 0xD9, 0x38,
        0x02, 0x04, 0xFE, 0xF3, 0x38, 0x02, 0x04, 0x78,
        0xEA,
        static_cast<uint8_t>(curLevel & 0xFF),
        static_cast<uint8_t>(curLevel >> 8),
        0x46
    };
    std::memcpy(&rom[off], code, sizeof(code));
}

// RandomEncounter segun el desensamblado (events.asm): cooldown, hierba,
// farcall TryWildEncounter (rst $08 = 0xCF), guion salvaje o de concurso,
// call CallScript, scf, ret. Los destinos de call/jr son arbitrarios
// (comodines en la firma); las direcciones reveladas son parametros.
static void putRandomEncounter(std::vector<uint8_t>& rom, size_t off,
                               uint16_t tryWildAddr, uint8_t wildBank,
                               uint16_t wildAddr, uint8_t contestBank,
                               uint16_t contestAddr,
                               uint16_t callScriptAddr) {
    const uint8_t code[] = {
        0xCD, 0x00, 0x40, 0x38, 0x10, 0xCD, 0x00, 0x41, 0x30, 0x10,
        0x21, 0x00, 0xD1, 0xCB, 0x46, 0x20, 0x10, 0x3E, 0x03, 0x21,
        static_cast<uint8_t>(tryWildAddr & 0xFF),
        static_cast<uint8_t>(tryWildAddr >> 8),
        0xCF, 0x20, 0x10, 0x18, 0x10, 0xCD, 0x00, 0x42,
        0x30, 0x10, 0x18, 0x10, 0x3E, 0x01, 0xA7, 0xC9, 0x3E, wildBank,
        0x21,
        static_cast<uint8_t>(wildAddr & 0xFF),
        static_cast<uint8_t>(wildAddr >> 8),
        0x18, 0x10, 0x3E, contestBank, 0x21,
        static_cast<uint8_t>(contestAddr & 0xFF),
        static_cast<uint8_t>(contestAddr >> 8),
        0x18, 0x10, 0xCD,
        static_cast<uint8_t>(callScriptAddr & 0xFF),
        static_cast<uint8_t>(callScriptAddr >> 8),
        0x37, 0xC9
    };
    std::memcpy(&rom[off], code, sizeof(code));
}

// CallScript segun home/map.asm: wScriptBank/wScriptPos/wScriptRunning.
static void putCallScript(std::vector<uint8_t>& rom, size_t off,
                          uint16_t scriptBank, uint16_t scriptPos,
                          uint8_t mapscript, uint16_t scriptRunning) {
    const uint8_t code[] = {
        0xEA,
        static_cast<uint8_t>(scriptBank & 0xFF),
        static_cast<uint8_t>(scriptBank >> 8),
        0x7D, 0xEA,
        static_cast<uint8_t>(scriptPos & 0xFF),
        static_cast<uint8_t>(scriptPos >> 8),
        0x7C, 0xEA,
        static_cast<uint8_t>((scriptPos + 1) & 0xFF),
        static_cast<uint8_t>((scriptPos + 1) >> 8),
        0x3E, mapscript, 0xEA,
        static_cast<uint8_t>(scriptRunning & 0xFF),
        static_cast<uint8_t>(scriptRunning >> 8),
        0x37, 0xC9
    };
    std::memcpy(&rom[off], code, sizeof(code));
}

static bool makeRoutineRom(const std::string& path, int variant) {
    // 64 KB = 4 bancos. Variante 0: las cuatro rutinas; 1: solo
    // TryWildEncounter; 2: TryWildEncounter dos veces (ambigua);
    // 3: TryWildEncounter con wTempWildMonSpecies fuera de WRAM (firma
    // rechazada); 4: las cuatro pero el farcall de RandomEncounter
    // apunta a otra direccion (cruce fallido); 5: las cuatro menos
    // CallScript.
    std::vector<uint8_t> rom(0x10000, 0x00);
    putTryWildEncounter(rom, 0xC100, 0xD123, 0xD456);
    if (variant == 0 || variant == 4 || variant == 5) {
        putChooseWildEncounter(rom, 0x8200, 0xD789);
        const uint16_t tryWildAddr =
            (variant == 4) ? 0x4111 : 0x4100;
        putRandomEncounter(rom, 0xC300, tryWildAddr, 0x05, 0x5100,
                           0x06, 0x5200, 0x0150);
        if (variant != 5) {
            putCallScript(rom, 0x0150, 0xD100, 0xD101, 0x07, 0xD103);
        }
    } else if (variant == 2) {
        putTryWildEncounter(rom, 0xC200, 0xD123, 0xD456);
        putChooseWildEncounter(rom, 0x8200, 0xD789);
    } else if (variant == 3) {
        putTryWildEncounter(rom, 0xC100, 0xC123, 0xD456);
        putChooseWildEncounter(rom, 0x8200, 0xD789);
    }
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
        gpsquest96::WildRoutineLocator locator;
        CHECK(locator.load(loader));
        gpsquest96::WildRoutineInfo info;
        CHECK(locator.locate(info) == gpsquest96::WildRoutineStatus::OK);
        CHECK(info.tryWildEncounter.bank >= 1);
        CHECK(info.tryWildEncounter.cpuAddress >= 0x4000);
        CHECK(info.tempWildMonSpeciesAddr >= 0xD000);
        CHECK(info.battleTypeAddr >= 0xD000);
        CHECK(info.curPartyLevelAddr >= 0xD000);
        CHECK(info.randomEncounter.bank >= 1);
        CHECK(info.randomEncounter.cpuAddress >= 0x4000);
        CHECK(info.callScript.bank == 0);
        CHECK(info.callScript.cpuAddress < 0x4000);
        CHECK(info.wildBattleScriptBank >= 1);
        CHECK(info.wildBattleScriptAddr >= 0x4000);
        CHECK(info.scriptBankAddr >= 0xD000);
        CHECK(info.scriptPosAddr >= 0xD000);
        CHECK(info.scriptRunningAddr >= 0xD000);
        CHECK(info.playerEventMapscript != 0);
    } else {
        std::printf("SKIP real ROM: define GPSQUEST_ROM_GEN2 para las "
                    "comprobaciones de Gen 2.\n");
    }

    // --- Localizador de rutinas por firma (slice 3) ---
    const std::string routinePath = dir + "/test_wildroutine.gbc";
    const std::string routinePath1 = dir + "/test_wildroutine_1.gbc";
    const std::string routinePath2 = dir + "/test_wildroutine_2.gbc";
    const std::string routinePath3 = dir + "/test_wildroutine_3.gbc";
    const std::string routinePath4 = dir + "/test_wildroutine_4.gbc";
    const std::string routinePath5 = dir + "/test_wildroutine_5.gbc";
    CHECK(makeRoutineRom(routinePath, 0));
    CHECK(makeRoutineRom(routinePath1, 1));
    CHECK(makeRoutineRom(routinePath2, 2));
    CHECK(makeRoutineRom(routinePath3, 3));
    CHECK(makeRoutineRom(routinePath4, 4));
    CHECK(makeRoutineRom(routinePath5, 5));
    {
        gpsquest96::RomLoader loader;
        CHECK(loader.open(routinePath.c_str()));
        gpsquest96::WildRoutineLocator locator;
        CHECK(locator.load(loader));
        gpsquest96::WildRoutineInfo info;
        CHECK(locator.locate(info) == gpsquest96::WildRoutineStatus::OK);
        CHECK(info.tryWildEncounter.romOffset == 0xC100);
        CHECK(info.tryWildEncounter.bank == 3);
        CHECK(info.tryWildEncounter.cpuAddress == 0x4100);
        CHECK(info.tempWildMonSpeciesAddr == 0xD123);
        CHECK(info.battleTypeAddr == 0xD456);
        CHECK(info.chooseWildEncounter.romOffset == 0x8200);
        CHECK(info.chooseWildEncounter.bank == 2);
        CHECK(info.chooseWildEncounter.cpuAddress == 0x4200);
        CHECK(info.curPartyLevelAddr == 0xD789);
        CHECK(info.randomEncounter.romOffset == 0xC300);
        CHECK(info.randomEncounter.bank == 3);
        CHECK(info.randomEncounter.cpuAddress == 0x4300);
        CHECK(info.wildBattleScriptBank == 0x05);
        CHECK(info.wildBattleScriptAddr == 0x5100);
        CHECK(info.bugContestScriptBank == 0x06);
        CHECK(info.bugContestScriptAddr == 0x5200);
        CHECK(info.callScript.romOffset == 0x0150);
        CHECK(info.callScript.bank == 0);
        CHECK(info.callScript.cpuAddress == 0x0150);
        CHECK(info.scriptBankAddr == 0xD100);
        CHECK(info.scriptPosAddr == 0xD101);
        CHECK(info.scriptRunningAddr == 0xD103);
        CHECK(info.playerEventMapscript == 0x07);
    }
    {
        // Solo TryWildEncounter, sin ChooseWildEncounter: no localizada.
        gpsquest96::RomLoader loader;
        CHECK(loader.open(routinePath1.c_str()));
        gpsquest96::WildRoutineLocator locator;
        CHECK(locator.load(loader));
        gpsquest96::WildRoutineInfo info;
        CHECK(locator.locate(info) ==
              gpsquest96::WildRoutineStatus::NOT_FOUND);
    }
    {
        // TryWildEncounter dos veces: ambigua, no se llama a nada.
        gpsquest96::RomLoader loader;
        CHECK(loader.open(routinePath2.c_str()));
        gpsquest96::WildRoutineLocator locator;
        CHECK(locator.load(loader));
        gpsquest96::WildRoutineInfo info;
        CHECK(locator.locate(info) ==
              gpsquest96::WildRoutineStatus::AMBIGUOUS);
    }
    {
        // wTempWildMonSpecies fuera de WRAM: firma rechazada.
        gpsquest96::RomLoader loader;
        CHECK(loader.open(routinePath3.c_str()));
        gpsquest96::WildRoutineLocator locator;
        CHECK(locator.load(loader));
        gpsquest96::WildRoutineInfo info;
        CHECK(locator.locate(info) ==
              gpsquest96::WildRoutineStatus::NOT_FOUND);
    }
    {
        // El farcall de RandomEncounter no apunta a TryWildEncounter:
        // cruce fallido, no se llama a nada.
        gpsquest96::RomLoader loader;
        CHECK(loader.open(routinePath4.c_str()));
        gpsquest96::WildRoutineLocator locator;
        CHECK(locator.load(loader));
        gpsquest96::WildRoutineInfo info;
        CHECK(locator.locate(info) ==
              gpsquest96::WildRoutineStatus::NOT_FOUND);
    }
    {
        // Sin CallScript: no se puede verificar el guion instalado.
        gpsquest96::RomLoader loader;
        CHECK(loader.open(routinePath5.c_str()));
        gpsquest96::WildRoutineLocator locator;
        CHECK(locator.load(loader));
        gpsquest96::WildRoutineInfo info;
        CHECK(locator.locate(info) ==
              gpsquest96::WildRoutineStatus::NOT_FOUND);
    }

    std::printf("test_wildtable: %d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
