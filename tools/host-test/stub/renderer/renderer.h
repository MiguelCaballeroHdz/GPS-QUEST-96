#ifndef GPSQUEST96_RENDERER_H
#define GPSQUEST96_RENDERER_H

#include <cstdint>

namespace gpsquest96 {

/**
 * Stub de Renderer para el host test de la PPU.
 *
 * `ppu.cpp` solo usa `renderFrame(const uint32_t*)`; no toca OpenGL. Así el
 * arnés puede compilar la PPU real sin GLES2 ni dispositivo.
 */
class Renderer {
public:
    Renderer() = default;
    ~Renderer() = default;

    void renderFrame(const uint32_t* /*frame*/) {
        // Sin salida: el test inspecciona Ppu::getFrameBuffer() directamente.
    }
};

} // namespace gpsquest96

#endif // GPSQUEST96_RENDERER_H
