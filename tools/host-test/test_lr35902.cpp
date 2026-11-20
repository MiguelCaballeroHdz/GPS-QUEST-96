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
    // Rutinas para callRoutine (spec 009, slice 3).
    rom[0x150] = 0x3E; rom[0x151] = 0x42; rom[0x152] = 0xC9; // LD A,0x42; RET
    rom[0x160] = 0xCD; rom[0x161] = 0x70; rom[0x162] = 0x01; // CALL 0x0170
    rom[0x163] = 0x47; rom[0x164] = 0xC9;                   // LD B,A; RET
    rom[0x170] = 0x3E; rom[0x171] = 0x77; rom[0x172] = 0xC9; // LD A,0x77; RET
    rom[0x180] = 0xC3; rom[0x181] = 0x80; rom[0x182] = 0x01; // JP 0x0180 (bucle)
    rom[0x190] = 0x76;                                       // HALT
    rom[0x4000] = 0x3E; rom[0x4001] = 0x99; rom[0x4002] = 0xC9; // banco 1: LD A,0x99; RET
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


    // --- callRoutine (spec 009, slice 3): llamada controlada ---
    {
        cpu.initialize(); // PC=0x0100, SP=0xFFFE, IME=0, sin HALT
        cpu.setAF(0x1230);
        cpu.setBC(0x5678);
        cpu.setDE(0x9ABC);
        cpu.setHL(0xDEF0);
        cpu.setSP(0xFFFE);
        const uint16_t bankBefore = mmu.currentRomBank();
        uint16_t outAF = 0;

        // (a) Rutina en banco 0: devuelve A=0x42 y restaura todo.
        auto r = cpu.callRoutine(0, 0x0150, 1000, outAF);
        CHECK(r == gpsquest96::Cpu::CallResult::OK);
        CHECK((outAF >> 8) == 0x42);
        CHECK(cpu.getAF() == 0x1230);
        CHECK(cpu.getBC() == 0x5678);
        CHECK(cpu.getDE() == 0x9ABC);
        CHECK(cpu.getHL() == 0xDEF0);
        CHECK(cpu.getSP() == 0xFFFE);
        CHECK(cpu.getPC() == 0x0100);
        CHECK(mmu.currentRomBank() == bankBefore);

        // (b) Llamada anidada: CALL interno + RET; A=0x77 al salir.
        r = cpu.callRoutine(0, 0x0160, 1000, outAF);
        CHECK(r == gpsquest96::Cpu::CallResult::OK);
        CHECK((outAF >> 8) == 0x77);
        CHECK(cpu.getAF() == 0x1230);
        CHECK(cpu.getSP() == 0xFFFE);
        CHECK(cpu.getPC() == 0x0100);

        // (c) Bucle infinito: TIMEOUT y estado restaurado.
        r = cpu.callRoutine(0, 0x0180, 100, outAF);
        CHECK(r == gpsquest96::Cpu::CallResult::TIMEOUT);
        CHECK(cpu.getAF() == 0x1230);
        CHECK(cpu.getSP() == 0xFFFE);
        CHECK(cpu.getPC() == 0x0100);

        // (d) Rutina en banco 1: cambia el banco, ejecuta y restaura.
        r = cpu.callRoutine(1, 0x4000, 1000, outAF);
        CHECK(r == gpsquest96::Cpu::CallResult::OK);
        CHECK((outAF >> 8) == 0x99);
        CHECK(mmu.currentRomBank() == bankBefore);
        CHECK(cpu.getPC() == 0x0100);

        // (e) CPU en HALT: la llamada corre igual y el HALT se restaura.
        cpu.setPC(0x0190);
        cpu.step(); // HALT
        CHECK(cpu.isHalted());
        r = cpu.callRoutine(0, 0x0150, 1000, outAF);
        CHECK(r == gpsquest96::Cpu::CallResult::OK);
        CHECK((outAF >> 8) == 0x42);
        CHECK(cpu.isHalted());
        CHECK(cpu.getPC() == 0x0191); // HALT en 0x0190: el fetch avanza PC

        // (f) Interrupcion pendiente + IME: no desvia la llamada y el
        // estado de IME se restaura (el siguiente step() la atiende).
        cpu.setHalted(false);
        cpu.setInterruptsEnabled(true);
        mmu.setInterruptEnable(0x01);
        mmu.setInterruptFlags(0x00);
        mmu.requestInterrupt(0);
        r = cpu.callRoutine(0, 0x0150, 1000, outAF);
        CHECK(r == gpsquest96::Cpu::CallResult::OK);
        CHECK((outAF >> 8) == 0x42);
        CHECK(cpu.interruptsEnabled());
        CHECK(cpu.getPC() == 0x0191);
        cpu.step(); // NOP en 0x0191 y la IRQ pendiente se atiende
        CHECK(cpu.getPC() == 0x0040); // vector VBlank
        mmu.setInterruptFlags(0x00);
        mmu.setInterruptEnable(0x00);
        cpu.setInterruptsEnabled(false);
    }

    loader.close();
    std::remove(rom.c_str());

    if (failures == 0) {
        std::printf("OK: %d checks\n", checks);
        return 0;
    }
    std::printf("FAILED: %d de %d checks\n", failures, checks);
    return 1;
}
