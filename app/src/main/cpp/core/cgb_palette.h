#ifndef GPSQUEST96_CGB_PALETTE_H
#define GPSQUEST96_CGB_PALETTE_H

#include <cstdint>

namespace gpsquest96 {

/**
 * Convierte un color RGB15 de CGB (formato BGR555: BBBBBGGGGGRRRRR) al
 * formato que sube el renderer a OpenGL ES.
 *
 * El renderer usa GL_RGBA + GL_UNSIGNED_BYTE, que lee los bytes en orden
 * R,G,B,A desde memoria little-endian. Por eso el uint32 se empaqueta como
 * 0xAABBGGRR (R en el byte bajo), igual que la paleta DMG de `ppu.cpp`.
 *
 * Expansión de 5 a 8 bits: (v << 3) | (v >> 2). Reparte el rango completo
 * (0→0, 31→255) sin dejar el blanco en 248.
 */
inline uint32_t cgbRgb15ToRgba(uint16_t rgb15) {
    const uint8_t r5 = static_cast<uint8_t>(rgb15 & 0x1F);
    const uint8_t g5 = static_cast<uint8_t>((rgb15 >> 5) & 0x1F);
    const uint8_t b5 = static_cast<uint8_t>((rgb15 >> 10) & 0x1F);

    const uint8_t r = static_cast<uint8_t>((r5 << 3) | (r5 >> 2));
    const uint8_t g = static_cast<uint8_t>((g5 << 3) | (g5 >> 2));
    const uint8_t b = static_cast<uint8_t>((b5 << 3) | (b5 >> 2));

    return 0xFF000000u | (static_cast<uint32_t>(b) << 16)
                       | (static_cast<uint32_t>(g) << 8)
                       | static_cast<uint32_t>(r);
}

} // namespace gpsquest96

#endif // GPSQUEST96_CGB_PALETTE_H
