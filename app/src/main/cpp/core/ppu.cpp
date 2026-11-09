#include "core/ppu.h"
#include "core/mmu.h"
#include "core/cgb_palette.h"
#include "renderer/renderer.h"

#include <android/log.h>
#include <cstring>

#define LOG_TAG "GPSQuest96"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace gpsquest96 {

// Colores de la paleta de hardware de 8 bits (verde clásico).
// Formato de subida GL: GL_RGBA + GL_UNSIGNED_BYTE lee los bytes en orden
// R,G,B,A desde memoria little-endian, asÃ­ que cada uint32 debe guardarse
// como 0xAABBGGRR (p. ej. #9BBC0F -> 0xFF0FBC9B). Guardarlo como 0xAARRGGBB
// intercambiaba R y B y pintaba el juego en teal.
static const uint32_t GB_PALETTE[4] = {
    0xFF0FBC9B, // #9BBC0F (mÃ¡s claro)
    0xFF0FAC8B, // #8BAC0F
    0xFF306230, // #306230 (R=B, sin cambio aparente)
    0xFF0F380F  // #0F380F (mÃ¡s oscuro)
};

Ppu::Ppu()
    : lcdc_(0), stat_(0), scy_(0), scx_(0), ly_(0), lyc_(0),
      bgPalette_(0), objPalette0_(0), objPalette1_(0), wy_(0), wx_(0),
      mode_(0), cycleCounter_(0), scanline_(0), windowLine_(0),
      lycMatch_(false), presentIndex_(1), drawIndex_(0),
      mmu_(nullptr), renderer_(nullptr) {
    drawBuffer_ = frameBuffers_[0];
    memset(oamSnapshot_, 0, sizeof(oamSnapshot_));
    memset(bgColorIndex_, 0, sizeof(bgColorIndex_));
    memset(bgPriority_, 0, sizeof(bgPriority_));
    fillTestPattern();
}

Ppu::~Ppu() {
}

void Ppu::fillTestPattern() {
    for (int y = 0; y < 144; y++) {
        for (int x = 0; x < 160; x++) {
            bool check = ((x / 8) + (y / 8)) % 2 == 0;
            const uint32_t color = check ? GB_PALETTE[0] : GB_PALETTE[3];
            for (uint32_t (&slot)[160 * 144] : frameBuffers_) {
                slot[y * 160 + x] = color;
            }
            displayBuffer_[y * 160 + x] = color;
        }
    }
}

void Ppu::initialize() {
    lcdc_ = 0x91; // LCD habilitado, sprites 8x8
    stat_ = 0;
    scy_ = 0;
    scx_ = 0;
    ly_ = 0;
    lyc_ = 0;
    bgPalette_ = 0xFC;
    objPalette0_ = 0xFF;
    objPalette1_ = 0xFF;
    wy_ = 0;
    wx_ = 0;
    mode_ = 2; // OAM Search
    cycleCounter_ = 0;
    scanline_ = 0;
    windowLine_ = 0;
    lycMatch_ = false;
    memset(bgColorIndex_, 0, sizeof(bgColorIndex_));
    memset(bgPriority_, 0, sizeof(bgPriority_));

    fillTestPattern();

    LOGI("PPU inicializado con patron de prueba retro");
}

bool Ppu::step(int cycles) {
    if (mmu_ == nullptr) {
        return false;
    }

    // LCDC vivo desde la MMU en cada step: el juego lo escribe por LDH/LD
    // y arranca con el LCD apagado (0x00 tras MMU::initialize). Si el gate
    // solo mirara la cachÃ© (0x91 de Ppu::initialize, refrescada Ãºnicamente
    // en renderScanline), la primera scanline la pisa a 0x00 y la PPU no
    // vuelve a avanzar jamÃ¡s: LY=0 eterno y el boot gira en su espera
    // VBlank legÃ­tima (0x6B-0x6F, Azul). Releer aquÃ­ permite reanudar
    // cuando el juego enciende el LCD, sin hilos ni JNI nuevos.
    lcdc_ = mmu_->read(0xFF40);

    // LCD apagado: comportamiento hardware â€” LY vuelve a 0 y la PPU
    // reposa en modo 2; al encender, el ciclo retoma desde la scanline 0.
    if ((lcdc_ & 0x80) == 0) {
        if (ly_ != 0 || mode_ != 2 || cycleCounter_ != 0) {
            ly_ = 0;
            scanline_ = 0;
            mode_ = 2;
            cycleCounter_ = 0;
            windowLine_ = 0;
            stat_ = (stat_ & 0xFC) | mode_;
            syncRegsToMmu();
        }
        return false;
    }

    cycleCounter_ += cycles;

    switch (mode_) {
        case 2: // OAM Search (80 ciclos)
            if (cycleCounter_ >= 80) {
                cycleCounter_ -= 80;
                mode_ = 3; // Pixel Transfer
                stat_ = (stat_ & 0xFC) | mode_;
                // STAT debe reflejar el modo 3: hay juegos que lo sondean.
                syncRegsToMmu();
            }
            break;

        case 3: // Pixel Transfer (172 ciclos)
            if (cycleCounter_ >= 172) {
                cycleCounter_ -= 172;
                mode_ = 0; // H-Blank
                stat_ = (stat_ & 0xFC) | mode_;
                // STAT debe exponer el modo 0 (H-Blank) al juego.
                syncRegsToMmu();
                requestStatModeIrq();

                // Renderizar scanline
                renderScanline();

                // HDMA (spec 011 slice 2): un bloque de 16 B por HBlank,
                // tras pintar la lÃ­nea, como el hardware (lo que la ROM
                // mueva aquÃ­ fuera afecta a las lÃ­neas SIGUIENTES, no a
                // la que se acaba de dibujar).
                if (mmu_ != nullptr && mmu_->isCgbMode()) {
                    mmu_->runHdmaChunk();
                }
            }
            break;

        case 0: // H-Blank (204 ciclos)
            if (cycleCounter_ >= 204) {
                cycleCounter_ -= 204;
                ly_++;
                scanline_ = ly_;

                if (ly_ >= 144) {
                    // V-Blank
                    mode_ = 1;
                    stat_ = (stat_ & 0xFC) | mode_;
                    syncRegsToMmu();
                    requestStatModeIrq();
                    if (mmu_ != nullptr) mmu_->requestInterrupt(0);

                    // VBlank: el frame acaba de completarse.
                    memcpy(displayBuffer_, drawBuffer_, sizeof(displayBuffer_));

                    // spec 015 D-25: la foto de OAM se toma al pintar la
                    // scanline 0 (renderScanline), justo los bytes con los que
                    // el PPU dibuja el frame visible.

                    const int publishedDraw = drawIndex_;
                    int next = 0;
                    while (next == publishedDraw || next == presentIndex_.load()) next++;
                    drawIndex_ = next;
                    drawBuffer_ = frameBuffers_[next];
                    presentIndex_.store(publishedDraw, std::memory_order_release);

                    // Frame completo
                    updateEmulatorState();
                    if (renderer_ != nullptr) {
                        renderer_->renderFrame(getFrameBuffer());
                    }
                    return true;
                } else {
                    mode_ = 2; // OAM Search
                    stat_ = (stat_ & 0xFC) | mode_;
                    syncRegsToMmu();
                    requestStatModeIrq();
                }
            }
            break;

        case 1: // V-Blank (456 ciclos por scanline)
            if (cycleCounter_ >= 456) {
                cycleCounter_ -= 456;
                ly_++;
                scanline_ = ly_;

                if (ly_ >= 154) {
                    // Fin del frame. Nota (spec 011 slice 2): windowLine_ NO
                    // se reinicia aquÃ­; el hardware lo hace en LY==WY con la
                    // ventana activa (ver renderScanline).
                    ly_ = 0;
                    scanline_ = 0;
                    mode_ = 2; // OAM Search
                    stat_ = (stat_ & 0xFC) | mode_;
                }
                syncRegsToMmu();
                if (ly_ == 0) requestStatModeIrq();
            }
            break;
    }

    return false;
}

void Ppu::renderScanline() {
    if (mmu_ == nullptr) return;

    // spec 015 D-25: foto de la OAM al empezar el frame visible. La CPU de
    // este emulador corre desacoplada del PPU: si se muestrea al entrar en
    // VBlank, la ROM ya puede haber vuelto a limpiar su buffer y salen 160
    // ceros. En la scanline 0 la OAM es todavÃ­a la que pinta los sprites.
    if (scanline_ == 0) {
        for (int i = 0; i < 160; i++) {
            oamSnapshot_[i] = mmu_->read(static_cast<uint16_t>(0xFE00 + i));
        }
    }

    // La scanline se repinta entera: BG/ventana/sprites parten de cero.
    memset(bgColorIndex_, 0, sizeof(bgColorIndex_));
    memset(bgPriority_, 0, sizeof(bgPriority_));

    // Registros vivos desde la MMU (el juego los escribe por LDH/LD).
    lcdc_ = mmu_->read(0xFF40);
    scy_ = mmu_->read(0xFF42);
    scx_ = mmu_->read(0xFF43);
    lyc_ = mmu_->read(0xFF45);
    bgPalette_ = mmu_->read(0xFF47);
    objPalette0_ = mmu_->read(0xFF48);
    objPalette1_ = mmu_->read(0xFF49);
    wy_ = mmu_->read(0xFF4A);
    wx_ = mmu_->read(0xFF4B);

    // WLY (spec 011 slice 2, RF-03): el contador interno de la ventana se
    // reinicia cuando LY==WY con la ventana activa en LCDC.5 (Pan Docs).
    // El hardware NO lo reinicia por fin de frame: asÃ­ el conteo realinea
    // a mitad de frame cuando la ROM cambia WY (reglas de batalla, diÃ¡logos).
    if (scanline_ == wy_ && (lcdc_ & 0x20) != 0) {
        windowLine_ = 0;
    }

    // Renderizar fondo
    renderBackground();

    // Renderizar ventana
    renderWindow();

    // Renderizar sprites
    renderSprites();
}

void Ppu::renderBackground() {
    const bool cgb = (mmu_ != nullptr) && mmu_->isCgbMode();

    // En CGB, LCDC.0 no apaga el fondo (el bit se ignora). En DMG sÃ­.
    if (!cgb && (lcdc_ & 0x01) == 0) return;

    uint16_t tileMapAddr = (lcdc_ & 0x08) ? 0x9C00 : 0x9800;
    // 0x8000: Ã­ndice sin signo (0-255). 0x8800: Ã­ndice con signo
    // (-128..127 desde 0x9000). Usar el desplazamiento sin signo sobre
    // 0x8800 pintaba tiles equivocados en los juegos que usan ese modo.
    const bool unsignedTiles = (lcdc_ & 0x10) != 0;

    // Esta scanline solo pinta SU fila del tile (fila = mapY & 7). Antes se
    // llamaba a drawTile() completo (8 filas) por scanline y cada lÃ­nea
    // pisaba las 7 siguientes: el framebuffer salÃ­a corrupto.
    const int rowInTile = (scanline_ + scy_) & 7;

    for (int x = 0; x < 160; x++) {
        int mapX = (x + scx_) & 0xFF;
        int mapY = (scanline_ + scy_) & 0xFF;

        int tileX = mapX / 8;
        int tileY = mapY / 8;
        int tileOffset = tileY * 32 + tileX;

        if (cgb) {
            // Banco 0 = Ã­ndice del tile, banco 1 = atributo. La PPU lee
            // ambos explÃ­citamente: el VBK del CPU no manda aquÃ­.
            drawCgbBgPixel(tileMapAddr, tileOffset, x, scanline_,
                           rowInTile, mapX & 7);
            continue;
        }

        uint8_t tileIndex = readVram(tileMapAddr + tileOffset);

        uint16_t tileAddr;
        if (unsignedTiles) {
            tileAddr = static_cast<uint16_t>(0x8000 + tileIndex * 16);
        } else {
            tileAddr = static_cast<uint16_t>(0x9000 + static_cast<int8_t>(tileIndex) * 16);
        }
        drawTilePixel(tileAddr, x, scanline_, rowInTile, mapX & 7, bgPalette_);
    }
}

void Ppu::renderWindow() {
    if ((lcdc_ & 0x20) == 0) return; // Ventana deshabilitada
    if (mmu_ == nullptr) return;
    if (scanline_ < wy_) return;      // la ventana empieza en su lÃ­nea WY

    const bool cgb = mmu_->isCgbMode();

    // PosiciÃ³n horizontal: el hardware coloca la ventana en WX-7.
    // WX=0..6 => arranca a la izquierda (primeras columnas fuera);
    // WX=166 => 1 columna visible; WX>166 => ventana deshabilitada.
    const int winX = (int) wx_ - 7;
    if (winX >= 160) return;          // fuera de pantalla: no avanza el contador

    // Tilemap de la ventana: LCDC.6 elige 0x9C00; si no, 0x9800.
    const uint16_t tileMapAddr = (lcdc_ & 0x40) ? 0x9C00 : 0x9800;
    // Mismo modo de direccionamiento de tiles que el fondo (LCDC.4).
    const bool unsignedTiles = (lcdc_ & 0x10) != 0;

    // La ventana no hace scroll: su fila viene del contador interno.
    const int rowInTile = windowLine_ & 7;
    const int tileY = windowLine_ >> 3;

    for (int x = 0; x < 160; x++) {
        if (x < winX) continue;             // a la izquierda de la ventana
        const int winCol = x - winX;         // columna dentro de la ventana
        const int tileX = winCol >> 3;
        const int tileOffset = tileY * 32 + tileX;

        if (cgb) {
            drawCgbBgPixel(tileMapAddr, tileOffset, x, scanline_,
                           rowInTile, winCol & 7);
            continue;
        }

        const uint8_t tileIndex = readVram(tileMapAddr + tileOffset);

        uint16_t tileAddr;
        if (unsignedTiles) {
            tileAddr = static_cast<uint16_t>(0x8000 + tileIndex * 16);
        } else {
            tileAddr = static_cast<uint16_t>(
                    0x9000 + static_cast<int8_t>(tileIndex) * 16);
        }
        // Columna DENTRO del tile (winCol & 7), no el borde izquierdo.
        drawTilePixel(tileAddr, x, scanline_, rowInTile, winCol & 7, bgPalette_);
    }

    windowLine_++;
}

void Ppu::renderSprites() {
    if ((lcdc_ & 0x02) == 0) return; // Sprites deshabilitados
    if (mmu_ == nullptr) return;

    const bool cgb = mmu_->isCgbMode();
    const int spriteHeight = (lcdc_ & 0x04) ? 16 : 8;
    const uint32_t bg0 = getColor(0, bgPalette_);

    struct Hit { int index; int sx; };
    Hit hits[10];
    int count = 0;

    // SelecciÃ³n hardware: primeras 10 entradas de OAM visibles en la scanline.
    for (int i = 0; i < 40 && count < 10; i++) {
        int sy = (int) mmu_->read(0xFE00 + i * 4) - 16;
        if (scanline_ < sy || scanline_ >= sy + spriteHeight) continue;
        int sx = (int) mmu_->read(0xFE00 + i * 4 + 1) - 8;
        if (sx <= -8 || sx >= 160) continue;
        hits[count].index = i;
        hits[count].sx = sx;
        count++;
    }

    // Prioridad:
    // - DMG y CGB con OPRI=0: gana la X menor; empate â†’ Ã­ndice OAM menor.
    // - CGB con OPRI=1: gana el Ã­ndice OAM menor (sin ordenar por X).
    // Se pinta al revÃ©s para que el ganador quede encima.
    if (!(cgb && mmu_->getOpri())) {
        for (int a = 1; a < count; a++) {
            Hit key = hits[a];
            int b = a - 1;
            while (b >= 0 && hits[b].sx > key.sx) {
                hits[b + 1] = hits[b];
                b--;
            }
            hits[b + 1] = key;
        }
    }

    for (int k = count - 1; k >= 0; k--) {
        int i = hits[k].index;
        int sy = (int) mmu_->read(0xFE00 + i * 4) - 16;
        int sx = hits[k].sx;
        uint8_t tile = mmu_->read(0xFE00 + i * 4 + 2);
        uint8_t flags = mmu_->read(0xFE00 + i * 4 + 3);

        int row = scanline_ - sy;
        if (flags & 0x40) row = spriteHeight - 1 - row; // flipY
        uint8_t tileIndex = (spriteHeight == 16)
                ? (uint8_t)((tile & 0xFE) + (row >= 8 ? 1 : 0))
                : tile;
        int tileRow = row % 8;
        uint16_t tileAddr = 0x8000 + (uint16_t) tileIndex * 16;

        // CGB: paleta OAM 0-7 y banco de tile (bit 3). DMG: OBP0/OBP1 (bit 4).
        const uint8_t tileBank = cgb ? ((flags & 0x08) ? 1 : 0) : 0;
        const uint8_t paletteIndex = cgb ? (flags & 0x07)
                                         : ((flags & 0x10) ? 1 : 0);

        uint8_t byte1 = readVramBank(tileBank, tileAddr + tileRow * 2);
        uint8_t byte2 = readVramBank(tileBank, tileAddr + tileRow * 2 + 1);
        bool behind = (flags & 0x80) != 0;

        for (int col = 0; col < 8; col++) {
            int bit = (flags & 0x20) ? col : (7 - col); // flipX
            uint8_t ci = (uint8_t)(((byte2 >> bit) & 0x01) << 1
                    | ((byte1 >> bit) & 0x01));
            if (ci == 0) continue; // transparente
            int px = sx + col;
            if (px < 0 || px >= 160) continue;

            if (behind) {
                if (cgb) {
                    // En CGB el sprite queda detrÃ¡s solo si el BG tiene
                    // prioridad Y color distinto de 0 en ese pÃ­xel.
                    if (bgColorIndex_[px] != 0 && bgPriority_[px]) continue;
                } else if (drawBuffer_[scanline_ * 160 + px] != bg0) {
                    continue; // detrÃ¡s del fondo (DMG)
                }
            }

            uint32_t* dst = &drawBuffer_[scanline_ * 160 + px];
            *dst = cgb
                   ? getCgbColor(ci, paletteIndex, true)
                   : getColor(ci, paletteIndex ? objPalette1_ : objPalette0_);
        }
    }
}

void Ppu::drawTile(uint16_t tileAddr, int x, int y, bool flipX, bool /*flipY*/, uint8_t palette) {
    if (mmu_ == nullptr) return;

    for (int row = 0; row < 8; row++) {
        uint8_t byte1 = readVram(tileAddr + row * 2);
        uint8_t byte2 = readVram(tileAddr + row * 2 + 1);

        for (int col = 0; col < 8; col++) {
            int bit = 7 - col;
            if (flipX) bit = col;

            uint8_t colorIndex = ((byte2 >> bit) & 0x01) << 1;
            colorIndex |= (byte1 >> bit) & 0x01;

            uint32_t color = getColor(colorIndex, palette);

            int px = x + col;
            int py = y + row;

            if (px >= 0 && px < 160 && py >= 0 && py < 144) {
                drawBuffer_[py * 160 + px] = color;
            }
        }
    }
}

/**
 * Pinta UN pÃ­xel de un tile (fila y columna dentro del tile).
 * Fondo y ventana recorren la pantalla pÃ­xel a pÃ­xel, asÃ­ que el caller
 * debe pasar la columna dentro del tile (mapX & 7 / winCol & 7). Pintar
 * el tile completo desde x repetÃ­a la columna 0 en cada pÃ­xel.
 */
void Ppu::drawTilePixel(uint16_t tileAddr, int px, int py, int rowInTile,
                        int colInTile, uint8_t palette) {
    if (mmu_ == nullptr) return;
    if (rowInTile < 0 || rowInTile > 7) return;
    if (colInTile < 0 || colInTile > 7) return;
    if (px < 0 || px >= 160 || py < 0 || py >= 144) return;

    uint8_t byte1 = readVram(tileAddr + rowInTile * 2);
    uint8_t byte2 = readVram(tileAddr + rowInTile * 2 + 1);
    int bit = 7 - colInTile;
    uint8_t colorIndex = (uint8_t)((((byte2 >> bit) & 0x01) << 1)
                                   | ((byte1 >> bit) & 0x01));
    drawBuffer_[py * 160 + px] = getColor(colorIndex, palette);
}

uint32_t Ppu::getColor(uint8_t colorIndex, uint8_t palette) {
    uint8_t color = (palette >> (colorIndex * 2)) & 0x03;
    return GB_PALETTE[color];
}

/**
 * PÃ­xel de BG/ventana en CGB.
 * - Ãndice del tile: VRAM banco 0.
 * - Atributo del tile: VRAM banco 1 (misma direcciÃ³n del tilemap).
 * - Formato del atributo: bits 0-2 paleta, bit 3 banco de tile, bit 4-5
 *   flips, bit 6 prioridad sobre OBJ.
 * La PPU lee ambos bancos a la vez; el VBK del CPU no interviene.
 */
void Ppu::drawCgbBgPixel(uint16_t tileMapAddr, int tileOffset, int px, int py,
                         int rowInTile, int colInTile) {
    if (mmu_ == nullptr) return;
    if (px < 0 || px >= 160 || py < 0 || py >= 144) return;

    const uint16_t attrAddr = static_cast<uint16_t>(tileMapAddr + tileOffset);
    const uint8_t tileIndex = readVram(attrAddr);
    const uint8_t attr = readVramBank(1, attrAddr);

    uint16_t tileAddr;
    if ((lcdc_ & 0x10) != 0) {
        tileAddr = static_cast<uint16_t>(0x8000 + tileIndex * 16);
    } else {
        tileAddr = static_cast<uint16_t>(
                0x9000 + static_cast<int8_t>(tileIndex) * 16);
    }

    const int row = (attr & 0x40) ? (7 - rowInTile) : rowInTile;  // flipY
    const int col = (attr & 0x20) ? colInTile : (7 - colInTile);  // flipX
    const uint8_t tileBank = (attr & 0x08) ? 1 : 0;

    const uint8_t byte1 = readVramBank(tileBank, tileAddr + row * 2);
    const uint8_t byte2 = readVramBank(tileBank, tileAddr + row * 2 + 1);
    const uint8_t ci = static_cast<uint8_t>(
            (((byte2 >> col) & 0x01) << 1) | ((byte1 >> col) & 0x01));

    drawBuffer_[py * 160 + px] = getCgbColor(ci, attr & 0x07, false);
    bgColorIndex_[px] = ci;
    bgPriority_[px] = (attr & 0x80) ? 1 : 0;
}

uint32_t Ppu::getCgbColor(uint8_t colorIndex, uint8_t paletteIndex, bool obj) {
    if (mmu_ == nullptr) {
        return GB_PALETTE[0];
    }
    const uint8_t base = static_cast<uint8_t>(
            ((paletteIndex & 0x07) * 8) + ((colorIndex & 0x03) * 2));
    const uint8_t lo = obj ? mmu_->readObjPalette(base)
                           : mmu_->readBgPalette(base);
    const uint8_t hi = obj ? mmu_->readObjPalette(base + 1)
                           : mmu_->readBgPalette(base + 1);
    const uint16_t rgb15 = static_cast<uint16_t>(lo) |
                           (static_cast<uint16_t>(hi) << 8);
    return cgbRgb15ToRgba(rgb15);
}

uint8_t Ppu::readVramBank(uint8_t bank, uint16_t address) {
    if (mmu_ != nullptr) {
        return mmu_->readVramBank(bank, address);
    }
    return 0;
}

uint8_t Ppu::readVram(uint16_t address) {
    if (mmu_ != nullptr) {
        // Los tiles viven siempre en el banco 0. El banco 1 (VBK=1) guarda
        // atributos CGB y no debe alterar el render DMG ni el de tiles.
        return mmu_->readVramBank(0, address);
    }
    return 0;
}

void Ppu::writeVram(uint16_t address, uint8_t value) {
    if (mmu_ != nullptr) {
        mmu_->write(address, value);
    }
}

void Ppu::syncRegsToMmu() {
    if (mmu_ == nullptr) return;
    // Conserva la configuraciÃ³n que escribe el juego en STAT (bits 3-6);
    // el PPU solo es dueÃ±o de los bits 0-1 (modo) y 2 (coincidencia LYC).
    stat_ = (stat_ & 0x07) | (mmu_->read(0xFF41) & 0x78);
    mmu_->write(0xFF44, ly_);

    // LYC se lee en vivo: el juego puede cambiarlo a mitad de frame y STAT
    // debe reflejarlo ya, no en la siguiente scanline renderizada.
    const uint8_t lyc = mmu_->read(0xFF45);
    const bool match = (ly_ == lyc);
    if (match) stat_ |= 0x04;
    else stat_ &= ~0x04;
    mmu_->write(0xFF41, stat_);

    // IRQ de coincidencia LYC: solo en el flanco (una vez por lÃ­nea) y con
    // el LCD encendido. Si no, cada sync de modo (2->3->0) repetirÃ­a la IRQ.
    if (match && !lycMatch_ && (stat_ & 0x40) && (lcdc_ & 0x80)) {
        mmu_->requestInterrupt(1);
    }
    lycMatch_ = match;
}

void Ppu::requestStatModeIrq() {
    if (mmu_ == nullptr) return;
    // Bits de STAT: 3=mode0, 4=mode1, 5=mode2. El bit 6 (LYC) se maneja en
    // syncRegsToMmu por flanco.
    const uint8_t stat = mmu_->read(0xFF41);
    const bool fire = (mode_ == 0 && (stat & 0x08)) ||
                      (mode_ == 1 && (stat & 0x10)) ||
                      (mode_ == 2 && (stat & 0x20));
    if (fire) {
        mmu_->requestInterrupt(1);
    }
}


// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// updateEmulatorState — espec 016, slice 2
// Detecta el estado del emulador desde registros de hardware de 8 bits genéricos,
// sin direcciones de memoria específicas de una sola ROM.
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
void Ppu::updateEmulatorState() {
    if (mmu_ == nullptr) return;

    const uint8_t lcdc = mmu_->read(0xFF40);
    const uint8_t stat = mmu_->read(0xFF41);
    const uint8_t scx = mmu_->read(0xFF43);
    const uint8_t scy = mmu_->read(0xFF42);
    const uint8_t wy = mmu_->read(0xFF4A);
    const uint8_t wx = mmu_->read(0xFF4B);
    const uint8_t ly = mmu_->read(0xFF44);

    // LCD apagado: aún no hemos arrancado el juego (BOOT o INTRO temprana)
    if ((lcdc & 0x80) == 0) {
        emulatorState_ = EmulatorState::BOOT;
        return;
    }

    // Ventana activa (WY/WX configurados): posible batalla o menú de texto
    // Si WY es razonable y WX está en pantalla, es probable que sea batalla o diálogo.
    const bool windowActive = (wy < 144 && wx <= 166);

    // Si la ventana está activa y no estamos en VBlank, es BATTLE o MENU
    if (windowActive) {
        // Diferenciar BATTLE vs MENU por SCX/SCY: en batalla el scroll se congela
        emulatorState_ = EmulatorState::BATTLE;
        return;
    }

    // Sin ventana, LCD encendido: overworld (MAP) o intro (INTRO)
    // Si SCX/SCY cambian activamente y LY avanza, estamos en MAP.
    if (ly >= 144 || ly == 0) {
        // En VBlank o inicio de frame: sin ventana, LCD encendido → MAP
        emulatorState_ = EmulatorState::MAP;
    } else {
        // Pantalla pintándose, sin ventana, LY avanzando → INTRO o MAP
        emulatorState_ = EmulatorState::INTRO;
    }
}

} // namespace gpsquest96

