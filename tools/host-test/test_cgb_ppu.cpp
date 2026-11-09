// Host test de la PPU CGB. Compila `ppu.cpp` real contra un stub de Renderer
// (sin GLES2) y comprueba color, paletas, bancos, flips, prioridad BG, OPRI,
// ventana y una regresión DMG. No sustituye el gate POCO, pero saca los bugs
// de lógica de color antes de probar en el móvil.
#include "core/cgb_palette.h"
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

// ── Utilidades ──────────────────────────────────────────────────────────────

static void setupCgb(Mmu& m, Ppu& ppu, uint8_t lcdc) {
    m.initialize();
    m.setCgbMode(true);
    m.write(0xFF40, lcdc);
    m.write(0xFF42, 0);   // SCY
    m.write(0xFF43, 0);   // SCX
    m.write(0xFF47, 0xE4); // BGP (fallback DMG)
    m.write(0xFF4A, 0);   // WY
    m.write(0xFF4B, 7);   // WX
    m.write(0xFF4F, 0);   // VBK = banco 0
    ppu.initialize();
}

static void setupDmg(Mmu& m, Ppu& ppu, uint8_t lcdc) {
    m.initialize();
    m.write(0xFF40, lcdc);
    m.write(0xFF42, 0);
    m.write(0xFF43, 0);
    m.write(0xFF47, 0xE4);
    m.write(0xFF4A, 0);
    m.write(0xFF4B, 7);
    m.write(0xFF4F, 0);
    ppu.initialize();
}

/** Avanza desde el inicio de la línea 0 (modo 2) y la renderiza. */
static void renderLine0(Ppu& ppu) {
    ppu.step(80);  // OAM search -> pixel transfer
    ppu.step(172); // pixel transfer -> HBlank (renderiza la línea)
}

/** Avanza hasta entrar en `mode` (con guarda para no colgar el test). */
static void runUntilMode(Ppu& ppu, uint8_t mode) {
    for (int i = 0; i < 200000 && ppu.getMode() != mode; i++) {
        ppu.step(1);
    }
}

/** Avanza hasta que LY sea `line` (la transición ya sincronizó STAT). */
static void runUntilLine(Ppu& ppu, uint8_t line) {
    for (int i = 0; i < 200000 && ppu.getScanline() != line; i++) {
        ppu.step(1);
    }
}

static uint32_t pixel(const Ppu& ppu, int x, int y) {
    // Buffer de DIBUJO: los checks pintan scanlines sueltas sin llegar a
    // VBlank, y con el doble búfer (spec 011) eso va al slot de dibujo.
    return ppu.getDrawFrameBuffer()[y * 160 + x];
}

static void tileRow(Mmu& m, int bank, uint16_t tileAddr, int row,
                    uint8_t byte1, uint8_t byte2) {
    const uint16_t addr = static_cast<uint16_t>(tileAddr + row * 2);
    m.writeVramBank(static_cast<uint8_t>(bank), addr, byte1);
    m.writeVramBank(static_cast<uint8_t>(bank),
                    static_cast<uint16_t>(addr + 1), byte2);
}

static void mapEntry(Mmu& m, uint16_t mapAddr, uint8_t tile, uint8_t attr) {
    m.writeVramBank(0, mapAddr, tile);
    m.writeVramBank(1, mapAddr, attr);
}

static void bgColor(Mmu& m, int palette, int color, uint16_t rgb15) {
    const int index = palette * 8 + color * 2;
    m.write(0xFF68, static_cast<uint8_t>(0x80 | index)); // BCPS autoincremento
    m.write(0xFF69, static_cast<uint8_t>(rgb15 & 0xFF));
    m.write(0xFF69, static_cast<uint8_t>((rgb15 >> 8) & 0xFF));
}

static void objColor(Mmu& m, int palette, int color, uint16_t rgb15) {
    const int index = palette * 8 + color * 2;
    m.write(0xFF6A, static_cast<uint8_t>(0x80 | index)); // OCPS autoincremento
    m.write(0xFF6B, static_cast<uint8_t>(rgb15 & 0xFF));
    m.write(0xFF6B, static_cast<uint8_t>((rgb15 >> 8) & 0xFF));
}

static void oam(Mmu& m, int index, uint8_t y, uint8_t x, uint8_t tile,
                uint8_t flags) {
    const uint16_t base = static_cast<uint16_t>(0xFE00 + index * 4);
    m.write(base, y);
    m.write(static_cast<uint16_t>(base + 1), x);
    m.write(static_cast<uint16_t>(base + 2), tile);
    m.write(static_cast<uint16_t>(base + 3), flags);
}

// ── Tests ───────────────────────────────────────────────────────────────────

int main() {
    Mmu m;
    Ppu ppu;
    ppu.setMmu(&m);

    // 1. CGB: índice de color del BG y paleta 0.
    {
        setupCgb(m, ppu, 0x91);
        tileRow(m, 0, 0x8000, 0, 0xFF, 0x00); // fila 0: color 1 en todo el tile
        mapEntry(m, 0x9800, 0, 0x00);
        bgColor(m, 0, 0, 0x0000); // negro
        bgColor(m, 0, 1, 0x001F); // rojo
        renderLine0(ppu);
        CHECK(pixel(ppu, 0, 0) == gpsquest96::cgbRgb15ToRgba(0x001F));
        CHECK(pixel(ppu, 7, 0) == gpsquest96::cgbRgb15ToRgba(0x001F));
    }

    // 2. CGB: attr del banco 1 (paleta 2 + banco de tile 1) y VBK independiente.
    {
        setupCgb(m, ppu, 0x91);
        m.writeVramBank(0, 0x9800, 0); // índice del tile en banco 0
        m.write(0xFF4F, 1);            // CPU escribe atributos en banco 1
        m.write(0x9800, 0x0A);         // paleta 2 (bits 0-2) + banco de tile (bit 3)
        tileRow(m, 0, 0x8000, 0, 0x00, 0x00); // banco 0: color 0
        tileRow(m, 1, 0x8000, 0, 0xFF, 0xFF); // banco 1: color 3
        bgColor(m, 2, 3, 0x7C00); // azul
        renderLine0(ppu);
        CHECK(pixel(ppu, 0, 0) == gpsquest96::cgbRgb15ToRgba(0x7C00));
    }

    // 3. CGB: flip X (bit 5 del atributo).
    {
        setupCgb(m, ppu, 0x91);
        tileRow(m, 0, 0x8000, 0, 0x80, 0x00); // solo el píxel izquierdo (col 0)
        mapEntry(m, 0x9800, 0, 0x00);
        bgColor(m, 0, 0, 0x0000);
        bgColor(m, 0, 1, 0x001F);
        renderLine0(ppu);
        CHECK(pixel(ppu, 0, 0) == gpsquest96::cgbRgb15ToRgba(0x001F));
        CHECK(pixel(ppu, 7, 0) == gpsquest96::cgbRgb15ToRgba(0x0000));

        m.writeVramBank(1, 0x9800, 0x20); // flip X
        ppu.initialize();
        renderLine0(ppu);
        CHECK(pixel(ppu, 0, 0) == gpsquest96::cgbRgb15ToRgba(0x0000));
        CHECK(pixel(ppu, 7, 0) == gpsquest96::cgbRgb15ToRgba(0x001F));
    }

    // 4. CGB: flip Y (bit 6 del atributo).
    {
        setupCgb(m, ppu, 0x91);
        tileRow(m, 0, 0x8000, 0, 0x00, 0x00); // fila 0: color 0
        tileRow(m, 0, 0x8000, 7, 0xFF, 0x00); // fila 7: color 1
        mapEntry(m, 0x9800, 0, 0x00);
        bgColor(m, 0, 1, 0x001F);
        renderLine0(ppu);
        CHECK(pixel(ppu, 0, 0) == gpsquest96::cgbRgb15ToRgba(0x0000));

        m.writeVramBank(1, 0x9800, 0x40); // flip Y en la línea 0 -> fila 7
        ppu.initialize();
        renderLine0(ppu);
        CHECK(pixel(ppu, 0, 0) == gpsquest96::cgbRgb15ToRgba(0x001F));
    }

    // 5. CGB: prioridad del BG sobre el sprite (bit 7 attr + bit 7 OAM).
    {
        setupCgb(m, ppu, 0x93); // LCDC con sprites 8x8 + BG
        tileRow(m, 0, 0x8000, 0, 0xFF, 0x00); // BG color 1
        mapEntry(m, 0x9800, 0, 0x80);         // BG con prioridad
        bgColor(m, 0, 1, 0x001F);             // BG rojo
        tileRow(m, 0, 0x8010, 0, 0xFF, 0xFF); // sprite tile 1: color 3
        objColor(m, 0, 3, 0x03E0);            // sprite verde
        oam(m, 0, 16, 8, 1, 0x80);            // y=16 (línea 0), x=8 (px 0), detrás
        renderLine0(ppu);
        CHECK(pixel(ppu, 0, 0) == gpsquest96::cgbRgb15ToRgba(0x001F));

        m.writeVramBank(1, 0x9800, 0x00); // sin prioridad BG
        ppu.initialize();
        renderLine0(ppu);
        CHECK(pixel(ppu, 0, 0) == gpsquest96::cgbRgb15ToRgba(0x03E0));

        m.writeVramBank(1, 0x9800, 0x80); // prioridad BG, pero color 0
        m.writeVramBank(0, 0x8000, 0x00);
        m.writeVramBank(0, 0x8001, 0x00);
        ppu.initialize();
        renderLine0(ppu);
        CHECK(pixel(ppu, 0, 0) == gpsquest96::cgbRgb15ToRgba(0x03E0));
    }

    // 6. CGB: OPRI=0 ordena por X; OPRI=1 por índice de OAM.
    {
        setupCgb(m, ppu, 0x93);
        tileRow(m, 0, 0x8000, 0, 0x00, 0x00); // BG color 0
        mapEntry(m, 0x9800, 0, 0x00);
        tileRow(m, 0, 0x8010, 0, 0xFF, 0xFF); // sprite 0 (tile 1)
        tileRow(m, 0, 0x8020, 0, 0xFF, 0xFF); // sprite 1 (tile 2)
        objColor(m, 1, 3, 0x001F); // rojo
        objColor(m, 2, 3, 0x7C00); // azul
        // Sprite 0: OAM 0, X=20 (px 12), paleta 1.
        oam(m, 0, 16, 20, 1, 0x01);
        // Sprite 1: OAM 1, X=16 (px 8), paleta 2. Se solapan en px 12-15.
        oam(m, 1, 16, 16, 2, 0x02);

        m.write(0xFF6C, 0); // OPRI=0: gana la X menor (sprite 1, azul)
        renderLine0(ppu);
        CHECK(pixel(ppu, 12, 0) == gpsquest96::cgbRgb15ToRgba(0x7C00));

        m.write(0xFF6C, 1); // OPRI=1: gana el índice OAM menor (sprite 0, rojo)
        ppu.initialize();
        renderLine0(ppu);
        CHECK(pixel(ppu, 12, 0) == gpsquest96::cgbRgb15ToRgba(0x001F));
    }

    // 7. CGB: ventana con atributo de paleta del banco 1.
    {
        setupCgb(m, ppu, 0xF1); // LCD, BG, ventana, map 0x9C00, tiles 0x8000
        tileRow(m, 0, 0x8010, 0, 0xFF, 0x00); // tile 1 del BG: color 1
        mapEntry(m, 0x9800, 1, 0x00);
        bgColor(m, 0, 1, 0x001F); // BG rojo
        tileRow(m, 0, 0x8020, 0, 0xFF, 0xFF); // tile 2 de la ventana: color 3
        mapEntry(m, 0x9C00, 2, 0x02);
        bgColor(m, 2, 3, 0x7C00); // ventana azul
        renderLine0(ppu);
        CHECK(pixel(ppu, 0, 0) == gpsquest96::cgbRgb15ToRgba(0x7C00));
    }

    // 8. CGB: LCDC.0 no apaga el BG (en DMG sí).
    {
        setupCgb(m, ppu, 0x90); // bit 0 = 0, pero CGB
        tileRow(m, 0, 0x8000, 0, 0xFF, 0x00);
        mapEntry(m, 0x9800, 0, 0x00);
        bgColor(m, 0, 1, 0x001F);
        renderLine0(ppu);
        CHECK(pixel(ppu, 0, 0) == gpsquest96::cgbRgb15ToRgba(0x001F));
    }

    // 9. DMG: regresión de la paleta clásica y el tile bank 0.
    {
        setupDmg(m, ppu, 0x91);
        tileRow(m, 0, 0x8000, 0, 0xFF, 0x00); // color 1
        mapEntry(m, 0x9800, 0, 0x00);
        renderLine0(ppu);
        CHECK(pixel(ppu, 0, 0) == 0xFF0FAC8B); // GB_PALETTE[1]
    }

    // 10. CGB: sprite con tile en el banco 1.
    {
        setupCgb(m, ppu, 0x93);
        tileRow(m, 0, 0x8000, 0, 0x00, 0x00); // BG color 0
        mapEntry(m, 0x9800, 0, 0x00);
        tileRow(m, 0, 0x8010, 0, 0x00, 0x00); // sprite bank 0: transparente
        tileRow(m, 1, 0x8010, 0, 0xFF, 0xFF); // sprite bank 1: color 3
        objColor(m, 0, 3, 0x03E0);
        oam(m, 0, 16, 8, 1, 0x08); // banco de tile 1, paleta 0
        renderLine0(ppu);
        CHECK(pixel(ppu, 0, 0) == gpsquest96::cgbRgb15ToRgba(0x03E0));
    }

    // 11. CGB: sprite transparente (color 0) deja ver el BG.
    {
        setupCgb(m, ppu, 0x93);
        tileRow(m, 0, 0x8000, 0, 0xFF, 0x00); // BG color 1
        mapEntry(m, 0x9800, 0, 0x00);
        bgColor(m, 0, 1, 0x001F); // BG rojo
        // Sprite: píxel 0 (bit 7) = 0 (transparente), píxel 1 (bit 6) = 1.
        tileRow(m, 0, 0x8010, 0, 0x7F, 0x00);
        objColor(m, 0, 1, 0x03E0); // sprite verde
        oam(m, 0, 16, 8, 1, 0x00);
        renderLine0(ppu);
        CHECK(pixel(ppu, 0, 0) == gpsquest96::cgbRgb15ToRgba(0x001F));
        CHECK(pixel(ppu, 1, 0) == gpsquest96::cgbRgb15ToRgba(0x03E0));
    }

    // 12. IRQ de STAT: modo 0/1/2, LYC por flanco y gating.
    {
        setupCgb(m, ppu, 0x91);
        m.write(0xFFFF, 0x02); // IE: STAT
        m.write(0xFF0F, 0x00); // IF limpio
        m.write(0xFF41, 0x08); // IRQ al entrar en modo 0
        renderLine0(ppu);
        CHECK((m.read(0xFF0F) & 0x02) != 0);

        m.write(0xFF0F, 0x00);
        m.write(0xFF41, 0x10); // IRQ al entrar en modo 1 (VBlank)
        runUntilMode(ppu, 1);
        CHECK((m.read(0xFF0F) & 0x02) != 0);

        m.write(0xFF0F, 0x00);
        m.write(0xFF41, 0x20); // IRQ al entrar en modo 2
        runUntilMode(ppu, 2);
        CHECK((m.read(0xFF0F) & 0x02) != 0);

        m.write(0xFF0F, 0x00);
        m.write(0xFF41, 0x40); // IRQ por LYC==LY
        m.write(0xFF45, 2);
        runUntilLine(ppu, 2);
        CHECK((m.read(0xFF0F) & 0x02) != 0);

        // La misma línea no debe repetir la IRQ al pasar 2->3->0.
        m.write(0xFF0F, 0x00);
        ppu.step(80);
        ppu.step(172);
        CHECK((m.read(0xFF0F) & 0x02) == 0);

        // Sin bits de STAT habilitados no hay IRQ.
        m.write(0xFF0F, 0x00);
        m.write(0xFF41, 0x00);
        runUntilLine(ppu, 3);
        CHECK((m.read(0xFF0F) & 0x02) == 0);
    }

    // 13. HDMA en vuelo (spec 011 slice 2): FF55, chunk 16 B por HBlank y
    //     cancelación; GDMA y LCD apagado siguen síncronos.
    {
        setupCgb(m, ppu, 0x91); // LCD encendido
        // Fuente: WRAM 0xC000-0xC01F con patrón; destino: VRAM 0x8000.
        for (int i = 0; i < 32; i++) {
            m.write(static_cast<uint16_t>(0xC000 + i), 0x40 + i);
        }
        m.write(0xFF51, 0xC0); // HDMA1: src alto 0xC0
        m.write(0xFF52, 0x00); // HDMA2: src bajo 0x00
        m.write(0xFF53, 0x00); // HDMA3: dst alto 0x00 -> 0x8000
        m.write(0xFF54, 0x00); // HDMA4: dst bajo 0x00

        // 13a. Arranque HDMA de 2 bloques: en vuelo, nada copiado aún.
        m.write(0xFF55, 0x81);
        CHECK(ppu.getMode() != 0);
        CHECK((m.read(0xFF55) & 0x80) == 0);
        CHECK(m.read(0xFF55) == 1);

        // 13b. Un HBlank = un bloque de 16 B.
        renderLine0(ppu); // renderiza LY=0 y ejecuta el chunk de HBlank
        CHECK((m.read(0xFF55) & 0x80) == 0);
        CHECK(m.read(0xFF55) == 0);
        bool ok01 = true, ok2 = true;
        for (int i = 0; i < 16; i++) {
            if (m.read(0x8000 + i) != 0x40 + i) ok01 = false;
        }
        for (int i = 16; i < 32; i++) {
            if (m.read(0x8000 + i) != 0) ok2 = false;
        }
        CHECK(ok01);
        CHECK(ok2);

        // 13c. Segundo HBlank agota la transferencia: FF55 = 0xFF.
        runUntilLine(ppu, 1);   // avanza hasta el arranque de la línea 1
        renderLine0(ppu);       // pinta la línea 1 y ejecuta chunk de HBlank
        CHECK(m.read(0xFF55) == 0xFF);
        bool ok3 = true;
        for (int i = 16; i < 32; i++) {
            if (m.read(0x8000 + i) != 0x40 + i) ok3 = false;
        }
        CHECK(ok3);

        // 13d. Cancelación: escribir 0xFF con transferencia en vuelo.
        m.write(0xFF55, 0x82); // 3 bloques
        CHECK(m.read(0xFF55) == 2);
        m.write(0xFF55, 0xFF);
        CHECK(m.read(0xFF55) == 0xFF);
        CHECK(m.read(0x8020) == 0); // VRAM 0x8000+0x20 intacta

        // 13e. GDMA sigue síncrona: 2 bloques en una sola escritura.
        m.write(0xFF55, 0x01);
        CHECK(m.read(0xFF55) == 0xFF);
        ok3 = true;
        for (int i = 0; i < 32; i++) {
            if (m.read(0x8000 + i) != 0x40 + i) ok3 = false;
        }
        CHECK(ok3);
    }

    // 13f. HDMA con LCD apagado: copia síncrona completa (hardware).
    {
        m.write(0xFF40, 0x11); // LCD off
        for (int i = 0; i < 16; i++) {
            m.read(0x8000 + i);
            m.write(0x8000 + i, 0); // limpia la zona de destino
        }
        m.write(0xFF51, 0xC0);
        m.write(0xFF52, 0x00);
        m.write(0xFF53, 0x00);
        m.write(0xFF54, 0x40); // dst 0x8000+0x40 = 0x8040
        m.write(0xFF55, 0x81); // 2 bloques con LCD apagado
        CHECK(m.read(0xFF55) == 0xFF);
        bool ok = true;
        for (int i = 0; i < 16; i++) {
            if (m.read(0x8040 + i) != 0x40 + i) ok = false;
        }
        CHECK(ok);
        m.write(0xFF40, 0x91); // LCD on de nuevo
    }

    // 14. WLY (spec 011 slice 2): reinicio en LY==WY y cambio mid-frame.
    {
        setupCgb(m, ppu, 0xF1); // LCD, BG+ventana, map ventana 0x9C00, tiles 0x8000
        m.write(0xFF4A, 40); // WY = 40
        m.write(0xFF4B, 7); // WX = 7 -> ventana desde x=0
        tileRow(m, 0, 0x8020, 0, 0xFF, 0x00); // tile 2 de la ventana: color 1
        mapEntry(m, 0x9C00, 2, 0x00);
        bgColor(m, 0, 1, 0x001F);

        renderLine0(ppu); // LY=0
        CHECK(ppu.getWindowLine() == 0); // antes de WY no pinta ni cuenta

        // LY=1..39: sin ventana visible, WLY sigue en 0.
        for (int l = 1; l <= 39; l++) {
            runUntilLine(ppu, static_cast<uint8_t>(l));
        }
        CHECK(ppu.getWindowLine() == 0);

        // LY=40: reinicio en LY==WY + la primera línea de la ventana.
        runUntilLine(ppu, 40);
        renderLine0(ppu);
        CHECK(ppu.getWindowLine() == 1);
        CHECK(pixel(ppu, 0, 40) == gpsquest96::cgbRgb15ToRgba(0x001F));
        CHECK(pixel(ppu, 0, 39) == gpsquest96::cgbRgb15ToRgba(0x0000));

        // LY=41..45: la ventana cuenta cada línea pintada (40..44 = 5).
        runUntilLine(ppu, 45);
        CHECK(ppu.getWindowLine() == 5);

        // Cambio de WY a mitad de frame (a LY=50): al llegar a LY==WY el
        // contador vuelve a 0 y realinea — es lo que rompía batallas y menús.
        m.write(0xFF4A, 50);
        runUntilLine(ppu, 50);
        renderLine0(ppu); // pinta LY=50
        CHECK(ppu.getWindowLine() == 1);

        // Ventana deshabilitada (LCDC.5=0): no pinta ni cuenta.
        m.write(0xFF40, 0x91);
        runUntilLine(ppu, 60);
        CHECK(ppu.getWindowLine() == 1);

        // Re-habilitada: vuelve a contar desde donde quedaba WLY.
        m.write(0xFF40, 0xF1);
        runUntilLine(ppu, 65);
        CHECK(ppu.getWindowLine() == 6);
    }

    if (failures == 0) {
        std::printf("OK: %d checks\n", checks);
        return 0;
    }
    std::printf("FAILED: %d de %d checks\n", failures, checks);
    return 1;
}
