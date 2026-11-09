#ifndef GPSQUEST96_TEXTURE_H
#define GPSQUEST96_TEXTURE_H

#include <GLES2/gl2.h>
#include <cstdint>

namespace gpsquest96 {

/**
 * Clase para gestionar texturas OpenGL ES 2.0.
 * Soporta texturas RGBA y filtrado bilineal.
 */
class Texture {
public:
    Texture();
    ~Texture();

    /**
     * Crea una textura desde datos de píxeles en memoria.
     * @param width Ancho en píxeles
     * @param height Alto en píxeles
     * @param data Datos RGBA (4 bytes por píxel)
     * @return true si la textura se creó correctamente
     */
    bool createFromPixels(int width, int height, const uint8_t* data);

    /**
     * Crea una textura vacía (para render-to-texture).
     */
    bool createEmpty(int width, int height);

    /**
     * Actualiza los datos de la textura.
     */
    void updatePixels(const uint8_t* data);

    /**
     * Enlaza la textura para su uso.
     */
    void bind() const;

    /**
     * Desenlaza la textura.
     */
    void unbind() const;

    /**
     * Obtiene el ancho de la textura.
     */
    int getWidth() const { return width_; }

    /**
     * Obtiene el alto de la textura.
     */
    int getHeight() const { return height_; }

    /**
     * Obtiene el ID de la textura.
     */
    GLuint getTextureId() const { return textureId_; }

    /**
     * Libera recursos.
     */
    void destroy();

private:
    GLuint textureId_;
    int width_;
    int height_;
};

} // namespace gpsquest96

#endif // GPSQUEST96_TEXTURE_H
