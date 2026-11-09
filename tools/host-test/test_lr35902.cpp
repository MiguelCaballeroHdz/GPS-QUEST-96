// Host test del CPU LR35902 + timer. Compila `cpu.cpp` real y comprueba
// instrucciones básicas, STOP/cambio de velocidad y la frecuencia de DIV/TIMA
// en doble velocidad. Regresión del bug de timers a mitad de ritmo.
#include "core/cpu.h"
#include "core/mmu.h"
#include "core/rom_loader.h"

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

/**
 * ROM sintética de 32 KB (MBC1, sin RAM) con un programa mínimo:
 *   0x0100: NOP
 *   0x0101: LD A, 0x42
 *   0x0200: STOP
 *   0x0300: STOP
 * El resto son NOP (0x00) para poder encadenar instrucciones.
 */
static bool makeRom(const std::string& path) {
    std::vector<uint8_t> rom(0x8000, 0x00);
    const char* title = "TESTCPU";
    std::memcpy(&rom[0x134], title, std::strlen(title));
    rom[0x143] = 0xC0; // CGB-only: permite KEY1/STOP
    rom[0x147] = 0x01; // MBC1
    rom[0x148] = 0x00; // 2 bancos
    rom[0x149] = 0x00; // sin RAM

    rom[0x100] = 0x00;             // NOP
    rom[0x101] = 0x3E;             // LD A, n8
    rom[0x102] = 0x42;
    rom[0x200] = 0x10;             // STOP
    rom[0x201] = 0x00;
    rom[0x300] = 0x10;             // STOP
    rom[0x301] = 0x00;

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
    const std::string rom = dir + "/test_lr35902.gbc";
    std::remove(rom.c_str());
    CHECK(makeRom(rom));

    gpsquest96::RomLoader loader;
    CHECK(loader.open(rom.c_str()));

    gpsquest96::Mmu mmu;
    mmu.initialize();
    mmu.setRomLoader(&loader);
    mmu.setCgbMode(true);
    // Paridad con el boot del hardware (spec 013 RF-01): un CGB sale del
    // boot de la BIOS YA a doble velocidad (KEY1 bit 7 = 1).
    CHECK(mmu.isDoubleSpeed());

    gpsquest96::Cpu cpu;
    cpu.setMmu(&mmu);
    cpu.initialize();

    // --- Instrucciones básicas ---
    CHECK(cpu.getPC() == 0x0100);
    int cycles = cpu.step(); // NOP
    CHECK(cycles == 4);
    CHECK(cpu.getPC() == 0x0101);

    cycles = cpu.step(); // LD A, 0x42
    CHECK(cycles == 8);
    CHECK(cpu.getA() == 0x42);
    CHECK(cpu.getPC() == 0x0103);

    // --- STOP: paridad spec 013 — el CGB arranca YA a 2x, así que el STOP
    // con KEY1 armado TOGLEA hacia abajo (2x -> 1x) y resetea DIV ---
    cpu.setPC(0x0200);
    mmu.updateTimers(300); // DIV = 1 (300 ticks SYSCLK)
    CHECK(mmu.read(0xFF04) == 1);
    mmu.write(0xFF4D, 0x01); // armar cambio de velocidad
    CHECK((mmu.read(0xFF4D) & 0x01) != 0);

    cycles = cpu.step(); // STOP
    CHECK(cycles == 4);
    CHECK(!mmu.isDoubleSpeed());
    CHECK((mmu.read(0xFF4D) & 0x80) == 0);
    CHECK((mmu.read(0xFF4D) & 0x01) == 0);
    CHECK(mmu.read(0xFF04) == 0); // STOP resetea DIV

    // --- TIMA a velocidad simple: 4 NOPs = 16 ticks SYSCLK -> +1 ---
    mmu.write(0xFF07, 0x05); // TAC: enable + 16 ciclos (262144 Hz normal)
    mmu.write(0xFF05, 0x00); // TIMA
    mmu.write(0xFF06, 0x00); // TMA
    for (int i = 0; i < 4; i++) {
        cpu.step(); // NOPs desde 0x0202
    }
    CHECK(mmu.read(0xFF05) == 1);

    // --- DIV a velocidad simple: 64 NOPs = 256 ticks SYSCLK -> +1 ---
    mmu.write(0xFF07, 0x00); // TAC off
    mmu.write(0xFF04, 0x00); // reset DIV
    for (int i = 0; i < 64; i++) {
        cpu.step(); // NOPs desde 0x0206
    }
    CHECK(mmu.read(0xFF04) == 1);

    // --- STOP de vuelta a doble velocidad (toggle 1x -> 2x) ---
    cpu.setPC(0x0300);
    mmu.write(0xFF4D, 0x01);
    cpu.step();
    CHECK(mmu.isDoubleSpeed());
    CHECK((mmu.read(0xFF4D) & 0x80) != 0);
    CHECK(mmu.read(0xFF04) == 0);

    // --- TIMA en doble velocidad: los timers NO se aceleran (hardware:
    // DIV/TIMA van al SYSCLK) — 8 NOPs = 16 ticks SYSCLK -> +1 ---
    mmu.write(0xFF07, 0x05); // TAC: enable + 16 ticks SYSCLK
    mmu.write(0xFF05, 0x00);
    for (int i = 0; i < 8; i++) {
        cpu.step(); // cada NOP consume 4 ciclos CPU = 2 ticks SYSCLK
    }
    CHECK(mmu.read(0xFF05) == 1);

    // --- DIV en doble velocidad: 128 NOPs = 256 ticks SYSCLK -> +1 ---
    // (hardware: DIV incrementa cada 256 SYSCLK SIEMPRE, también a 2x)
    mmu.write(0xFF07, 0x00); // TAC off
    mmu.write(0xFF04, 0x00); // reset DIV
    for (int i = 0; i < 128; i++) {
        cpu.step(); // NOPs: 128 x 4 = 512 CPU-cycles = 256 ticks SYSCLK
    }
    CHECK(mmu.read(0xFF04) == 1);

    loader.close();
    std::remove(rom.c_str());

    if (failures == 0) {
        std::printf("OK: %d checks\n", checks);
        return 0;
    }
    std::printf("FAILED: %d de %d checks\n", failures, checks);
    return 1;
}
