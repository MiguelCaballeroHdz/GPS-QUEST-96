#ifndef GPSQUEST96_RENDERER_H
#define GPSQUEST96_RENDERER_H

#include <GLES2/gl2.h>
#include <cstdint>

#include "renderer/shader.h"
#include "renderer/texture.h"

namespace gpsquest96 {

/**
 * Renderer principal OpenGL ES 2.0.
 * <p>
 * Pipeline de renderizado:
 * 1. Limpiar pantalla
 * 2. Dibujar fondo (mapa)
 * 3. Dibujar sprites (jugador, criaturas, objetos)
 * 4. Dibujar HUD (reloj, pasos, clima)
 * 5. Aplicar efectos (clima, transiciones)
 */
class Renderer {
public:
    Renderer();
    ~Renderer();

    /**
     * Inicializa el renderer.
     * @param width Ancho de la pantalla
     * @param height Alto de la pantalla
     * @return true si la inicialización fue exitosa
     */
    bool initialize(int width, int height);

    /**
     * Limpia la pantalla.
     */
    void clear();

    /**
     * Establece el color de fondo.
     */
    void setClearColor(float r, float g, float b, float a);

    /**
     * Dibuja un sprite en la pantalla.
     * @param texture Textura del sprite
     * @param x Posición X en píxeles
     * @param y Posición Y en píxeles
     * @param width Ancho del sprite
     * @param height Alto del sprite
     */
    void drawSprite(const Texture& texture, int x, int y, int width, int height);

    /**
     * Dibuja un sprite con rotación.
     */
    void drawSpriteRotated(const Texture& texture, int x, int y, int width, int height, float rotation);

    /**
     * Dibuja un rectángulo sólido.
     */
    void drawRect(int x, int y, int width, int height, float r, float g, float b, float a);

    /**
     * Dibuja un rectángulo con textura.
     */
    void drawTexturedRect(const Texture& texture, int x, int y, int width, int height);

    /**
     * Aplica un tinte de color a toda la pantalla (efectos de clima).
     */
    void applyColorOverlay(float r, float g, float b, float alpha);

    /**
     * Renderiza un frame completo desde el buffer de píxeles.
     * @param frameBuffer Buffer de píxeles RGBA (160x144)
     */
    void renderFrame(const uint32_t* frameBuffer);

    /**
     * Obtiene el ancho de la pantalla.
     */
    int getScreenWidth() const { return screenWidth_; }

    /**
     * Obtiene el alto de la pantalla.
     */
    int getScreenHeight() const { return screenHeight_; }

    /**
     * Libera recursos.
     */
    void destroy();

private:
    int screenWidth_;
    int screenHeight_;

    Shader spriteShader_;
    Shader rectShader_;

    // Textura única del frame del PPU (160x144 RGBA)
    Texture frameTexture_;
    bool frameTextureReady_;

    // VBOs para sprites
    GLuint quadVbo_;
    GLuint texCoordVbo_;

    bool loadShaders();
    bool createQuad();
    void setupViewport();
};

} // namespace gpsquest96

#endif // GPSQUEST96_RENDERER_H
