// Host test de cadencia de frame (diagnóstico del núcleo, spec 020).
// Mide la PPU real (`core/ppu.cpp`) con una MMU real: duraciones de modo,
// ciclos por frame y entrada en VBlank. Sin Android ni POCO.
//
// Hardware de referencia (Pan Docs):
//  - Línea visible: modo 2 (OAM, 80) + modo 3 (transfer, 172) + modo 0
//    (HBlank, 204) = 456 ciclos; 144 líneas = 65664 ciclos.
//  - VBlank: 10 líneas x 456 = 4560 ciclos; frame completo = 70224
//    ciclos (59,73 fps). IRQ de VBlank (bit 0) al entrar en LY 144.
#include "core/mmu.h"
#include "core/ppu.h"

#include <cstdint>
#include <cstdio>

using gpsquest96::Mmu;
using gpsquest96::Ppu;

static int failures = 0;
static int checks = 0;

#define CHECK(cond) do { \
    ++checks; \
    if (!(cond)) { \
        std::printf("FAIL line %d: %s\n", __LINE__, #cond); \
        ++failures; \
    } \
} while (0)

// Avanza 1 ciclo cada vez hasta que el modo cambie; devuelve los ciclos
// que duró el modo actual. Guarda en 100000 para no colgar el test.
static int measureMode(Ppu& ppu, uint8_t mode) {
    int cycles = 0;
    while (cycles < 100000 && ppu.getMode() == mode) {
        ppu.step(1);
        ++cycles;
    }
    return cycles;
}

int main() {
    Mmu m;
    Ppu ppu;
    m.initialize();
    ppu.setMmu(&m);
    m.write(0xFF40, 0x91); // LCD on, BG + sprites 8x8
    m.write(0xFF42, 0);
    m.write(0xFF43, 0);
    m.write(0xFF47, 0xE4);
    ppu.initialize();

    // ── Estado inicial ─────────────────────────────────────────────────
    CHECK(ppu.getMode() == 2); // OAM Search
    CHECK(ppu.getScanline() == 0);
    CHECK(ppu.getLy() == 0);

    // ── Duraciones de modo en la línea 0 ──────────────────────────────
    int d2 = measureMode(ppu, 2);
    CHECK(d2 == 80);
    int d3 = measureMode(ppu, 3);
    CHECK(d3 == 172);
    int d0 = measureMode(ppu, 0);
    CHECK(d0 == 204);
    CHECK(ppu.getScanline() == 1);
    CHECK(ppu.getMode() == 2);
    std::printf("linea 0: modo2=%d, modo3=%d, modo0=%d (esperado 80/172/204)\n",
                d2, d3, d0);

    // ── Entrada en VBlank: 144 líneas x 456 = 65664 ciclos ────────────
    // Ya van 456 ciclos de la línea 0; faltan 143 líneas hasta LY 144.
    int cycles = 456;
    bool frameDone = false;
    while (cycles < 200000 && !frameDone) {
        frameDone = ppu.step(4);
        cycles += 4;
    }
    CHECK(frameDone); // step() devuelve true al entrar en VBlank
    CHECK(cycles == 65664);
    CHECK(ppu.getLy() == 144);
    CHECK(ppu.getMode() == 1);
    CHECK((m.getInterruptFlags() & 0x01) != 0); // IRQ de VBlank pedida
    CHECK((m.read(0xFF41) & 0x03) == 1); // STAT refleja modo 1
    std::printf("VBlank: %d ciclos hasta LY 144 (esperado 65664)\n", cycles);

    // ── Frame completo: 70224 ciclos hasta LY 0 / modo 2 ──────────────
    while (cycles < 200000 && (ppu.getLy() != 0 || ppu.getMode() != 2)) {
        ppu.step(4);
        cycles += 4;
    }
    CHECK(cycles == 70224);
    CHECK(ppu.getLy() == 0);
    CHECK(ppu.getMode() == 2);
    std::printf("frame: %d ciclos (esperado 70224 = 59,73 fps)\n", cycles);

    // ── Segundo frame: la cadencia se repite sin deriva ───────────────
    int c2 = 0;
    bool done2 = false;
    while (c2 < 200000 && !done2) {
        done2 = ppu.step(4);
        c2 += 4;
    }
    CHECK(done2 && c2 == 65664);
    while (c2 < 200000 && (ppu.getLy() != 0 || ppu.getMode() != 2)) {
        ppu.step(4);
        c2 += 4;
    }
    CHECK(c2 == 70224);

    std::printf("test_timing: %d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
