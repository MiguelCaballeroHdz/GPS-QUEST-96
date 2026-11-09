// Host test del MMU CGB. No emula PPU ni CPU: solo memoria y registros.
// Ejecutar con tools\host-test\run.ps1 (compila mmu/mbc/rom_loader con un
// stub de android/log.h) o con el README.
#include "core/mmu.h"
#include "core/cgb_palette.h"

#include <cstdint>
#include <cstdio>

static int failures = 0;
static int checks = 0;

#define CHECK(cond) do { \
    ++checks; \
    if (!(cond)) { \
        std::printf("FAIL line %d: %s\n", __LINE__, #cond); \
        ++failures; \
    } \
} while (0)

int main() {
    gpsquest96::Mmu m;
    m.initialize();

    // --- DMG: mapa clásico, sin alias 0xD0xx/0xC0xx ---
    m.write(0xC000, 0x11);
    m.write(0xD000, 0x22);
    CHECK(m.read(0xC000) == 0x11);
    CHECK(m.read(0xD000) == 0x22);
    CHECK(m.read(0xE000) == 0x11);
    CHECK(m.read(0xF000) == 0x22);
    CHECK(m.read(0xD000) != m.read(0xC000));

    // --- CGB: SVBK cambia banco; 0 se comporta como 1 ---
    m.setCgbMode(true);
    m.write(0xFF70, 3);
    m.write(0xD000, 0x33);
    m.write(0xFF70, 1);
    CHECK(m.read(0xD000) == 0x22);
    m.write(0xFF70, 3);
    CHECK(m.read(0xD000) == 0x33);
    m.write(0xFF70, 0);
    CHECK(m.read(0xD000) == 0x22);

    // --- CGB: VBK separa VRAM; la PPU lee banco 0 ---
    m.write(0xFF4F, 0);
    m.write(0x8000, 0xAA);
    m.write(0xFF4F, 1);
    m.write(0x8000, 0xBB);
    CHECK(m.readVramBank(0, 0x8000) == 0xAA);
    CHECK(m.readVramBank(1, 0x8000) == 0xBB);
    m.write(0xFF4F, 0);
    CHECK(m.read(0x8000) == 0xAA);

    // --- CGB: paridad con el boot del hardware (spec 013 RF-01) ---
    // setCgbMode(true) ES el post-boot: la BIOS del CGB entrega el control
    // en 0x0100 con la CPU YA a doble velocidad (KEY1 bit 7 = 1). Este core
    // no corre la BIOS ⇒ su activación de CGB cumple ese estado.
    CHECK(m.isDoubleSpeed());
    CHECK((m.read(0xFF4D) & 0x80) != 0);

    // --- CGB: KEY1 arma y STOP completa el cambio (toggle 2x -> 1x) ---
    m.write(0xFF4D, 1);
    CHECK((m.read(0xFF4D) & 0x01) == 1);
    CHECK(m.trySpeedSwitch());
    CHECK(!m.isDoubleSpeed());
    CHECK((m.read(0xFF4D) & 0x80) == 0);
    CHECK(!m.trySpeedSwitch());
    // El hardware permite volver (toggle 1x -> 2x con KEY1+STOP).
    m.write(0xFF4D, 1);
    CHECK(m.trySpeedSwitch());
    CHECK(m.isDoubleSpeed());

    // --- CGB: paletas con auto-incremento ---
    m.write(0xFF68, 0x80);
    m.write(0xFF69, 0x12);
    m.write(0xFF69, 0x34);
    CHECK(m.readBgPalette(0) == 0x12);
    CHECK(m.readBgPalette(1) == 0x34);
    CHECK((m.read(0xFF68) & 0x3F) == 2);

    // --- CGB: GDMA de 1 bloque (16 B) ---
    for (int i = 0; i < 16; i++) {
        m.write(0xC100 + i, static_cast<uint8_t>(0x40 + i));
    }
    m.write(0xFF51, 0xC1);
    m.write(0xFF52, 0x00);
    m.write(0xFF53, 0x00);
    m.write(0xFF54, 0x00);
    m.write(0xFF4F, 1);
    m.write(0xFF55, 0x00);
    CHECK(m.readVramBank(1, 0x8000) == 0x40);
    CHECK(m.readVramBank(1, 0x800F) == 0x4F);
    CHECK(m.read(0xFF55) == 0xFF);

    // --- Color CGB: RGB15 -> RGBA con R en el byte bajo ---
    CHECK(gpsquest96::cgbRgb15ToRgba(0x0000) == 0xFF000000u); // negro
    CHECK(gpsquest96::cgbRgb15ToRgba(0x7FFF) == 0xFFFFFFFFu); // blanco
    CHECK(gpsquest96::cgbRgb15ToRgba(0x001F) == 0xFF0000FFu); // rojo puro
    CHECK(gpsquest96::cgbRgb15ToRgba(0x03E0) == 0xFF00FF00u); // verde puro
    CHECK(gpsquest96::cgbRgb15ToRgba(0x7C00) == 0xFFFF0000u); // azul puro

    // --- Volver a DMG: registros CGB desaparecen y SVBK no manda ---
    m.setCgbMode(false);
    CHECK(m.read(0xFF4D) == 0xFF);
    CHECK(m.read(0xFF70) == 0xFF);
    m.write(0xFF70, 3);
    CHECK(m.read(0xD000) == 0x22);

    if (failures == 0) {
        std::printf("OK: %d checks\n", checks);
        return 0;
    }
    std::printf("FAILED: %d de %d checks\n", failures, checks);
    return 1;
}
