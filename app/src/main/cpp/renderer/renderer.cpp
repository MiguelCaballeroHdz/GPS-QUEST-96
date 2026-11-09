#include "renderer/renderer.h"

#include <android/log.h>
#include <cstring>

#define LOG_TAG "GPSQuest96"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace gpsquest96 {

// ═══════════════════════════════════════════════════════
// SHADERS
// ═══════════════════════════════════════════════════════

static const char* SPRITE_VERTEX_SHADER = R"(
attribute vec2 a_position;
attribute vec2 a_texCoord;
varying vec2 v_texCoord;
uniform mat4 u_projection;

void main() {
    gl_Position = u_projection * vec4(a_position, 0.0, 1.0);
    v_texCoord = a_texCoord;
}
)";

static const char* SPRITE_FRAGMENT_SHADER = R"(
precision mediump float;
varying vec2 v_texCoord;
uniform sampler2D u_texture;
uniform vec4 u_tint;

void main() {
    vec4 color = texture2D(u_texture, v_texCoord);
    gl_FragColor = color * u_tint;
}
)";

static const char* RECT_VERTEX_SHADER = R"(
attribute vec2 a_position;
uniform mat4 u_projection;

void main() {
    gl_Position = u_projection * vec4(a_position, 0.0, 1.0);
}
)";

static const char* RECT_FRAGMENT_SHADER = R"(
precision mediump float;
uniform vec4 u_color;

void main() {
    gl_FragColor = u_color;
}
)";

// ═══════════════════════════════════════════════════════
// IMPLEMENTACIÓN
// ═══════════════════════════════════════════════════════

Renderer::Renderer()
    : screenWidth_(0), screenHeight_(0), frameTextureReady_(false),
      quadVbo_(0), texCoordVbo_(0) {
}

Renderer::~Renderer() {
    destroy();
}

bool Renderer::initialize(int width, int height) {
    screenWidth_ = width;
    screenHeight_ = height;

    LOGI("Inicializando renderer: %dx%d", width, height);

    // Cargar shaders
    if (!loadShaders()) {
        LOGE("Error cargando shaders");
        return false;
    }

    // Crear quad para sprites
    if (!createQuad()) {
        LOGE("Error creando quad");
        return false;
    }

    // Configurar viewport
    setupViewport();

    // Habilitar blending para transparencias
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

    LOGI("Renderer inicializado correctamente");
    return true;
}

bool Renderer::loadShaders() {
    // Shader de sprites
    if (!spriteShader_.loadFromSource(SPRITE_VERTEX_SHADER, SPRITE_FRAGMENT_SHADER)) {
        LOGE("Error cargando shader de sprites");
        return false;
    }

    // Shader de rectángulos
    if (!rectShader_.loadFromSource(RECT_VERTEX_SHADER, RECT_FRAGMENT_SHADER)) {
        LOGE("Error cargando shader de rectángulos");
        return false;
    }

    return true;
}

bool Renderer::createQuad() {
    // Vértices de un quad (dos triángulos)
    GLfloat vertices[] = {
        -0.5f, -0.5f,
         0.5f, -0.5f,
        -0.5f,  0.5f,
         0.5f,  0.5f
    };

    // Coordenadas de textura
    GLfloat texCoords[] = {
        0.0f, 1.0f,
        1.0f, 1.0f,
        0.0f, 0.0f,
        1.0f, 0.0f
    };

    // Crear VBO de vértices
    glGenBuffers(1, &quadVbo_);
    glBindBuffer(GL_ARRAY_BUFFER, quadVbo_);
    glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_STATIC_DRAW);

    // Crear VBO de coordenadas de textura
    glGenBuffers(1, &texCoordVbo_);
    glBindBuffer(GL_ARRAY_BUFFER, texCoordVbo_);
    glBufferData(GL_ARRAY_BUFFER, sizeof(texCoords), texCoords, GL_STATIC_DRAW);

    glBindBuffer(GL_ARRAY_BUFFER, 0);

    return true;
}

void Renderer::setupViewport() {
    glViewport(0, 0, screenWidth_, screenHeight_);
}

void Renderer::clear() {
    glClear(GL_COLOR_BUFFER_BIT);
}

void Renderer::setClearColor(float r, float g, float b, float a) {
    glClearColor(r, g, b, a);
}

void Renderer::drawSprite(const Texture& texture, int /*x*/, int /*y*/, int /*width*/, int /*height*/) {
    spriteShader_.use();

    // Configurar proyección ortográfica
    GLfloat projection[16];
    memset(projection, 0, sizeof(projection));
    projection[0] = 2.0f / screenWidth_;
    projection[5] = -2.0f / screenHeight_;
    projection[10] = 1.0f;
    projection[12] = -1.0f;
    projection[13] = 1.0f;
    projection[15] = 1.0f;

    spriteShader_.setUniformMatrix4fv("u_projection", projection);
    spriteShader_.setUniform1i("u_texture", 0);
    spriteShader_.setUniform4f("u_tint", 1.0f, 1.0f, 1.0f, 1.0f);

    // Posición del sprite (quad unitario en VBO; x/y reservados para api futura)

    // Configurar vértices
    glBindBuffer(GL_ARRAY_BUFFER, quadVbo_);
    GLint posLoc = spriteShader_.getAttribLocation("a_position");
    glEnableVertexAttribArray(posLoc);
    glVertexAttribPointer(posLoc, 2, GL_FLOAT, GL_FALSE, 0, 0);

    // Configurar coordenadas de textura
    glBindBuffer(GL_ARRAY_BUFFER, texCoordVbo_);
    GLint texLoc = spriteShader_.getAttribLocation("a_texCoord");
    glEnableVertexAttribArray(texLoc);
    glVertexAttribPointer(texLoc, 2, GL_FLOAT, GL_FALSE, 0, 0);

    // Enlazar textura
    glActiveTexture(GL_TEXTURE0);
    texture.bind();

    // Dibujar
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    // Limpiar
    glDisableVertexAttribArray(posLoc);
    glDisableVertexAttribArray(texLoc);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    texture.unbind();
}

void Renderer::drawSpriteRotated(const Texture& texture, int x, int y, int width, int height, float /*rotation*/) {
    // TODO: Implementar rotación con matriz de transformación
    drawSprite(texture, x, y, width, height);
}

void Renderer::drawRect(int x, int y, int width, int height, float r, float g, float b, float a) {
    rectShader_.use();

    // Configurar proyección ortográfica
    GLfloat projection[16];
    memset(projection, 0, sizeof(projection));
    projection[0] = 2.0f / screenWidth_;
    projection[5] = -2.0f / screenHeight_;
    projection[10] = 1.0f;
    projection[12] = -1.0f;
    projection[13] = 1.0f;
    projection[15] = 1.0f;

    rectShader_.setUniformMatrix4fv("u_projection", projection);
    rectShader_.setUniform4f("u_color", r, g, b, a);

    // Posición del rectángulo
    GLfloat posX = (float)x;
    GLfloat posY = (float)(screenHeight_ - y - height);

    // Vértices del rectángulo
    GLfloat vertices[] = {
        posX, posY,
        posX + width, posY,
        posX, posY + height,
        posX + width, posY + height
    };

    // Dibujar
    GLuint vbo;
    glGenBuffers(1, &vbo);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_DYNAMIC_DRAW);

    GLint posLoc = rectShader_.getAttribLocation("a_position");
    glEnableVertexAttribArray(posLoc);
    glVertexAttribPointer(posLoc, 2, GL_FLOAT, GL_FALSE, 0, 0);

    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    glDisableVertexAttribArray(posLoc);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glDeleteBuffers(1, &vbo);
}

void Renderer::drawTexturedRect(const Texture& texture, int x, int y, int width, int height) {
    drawSprite(texture, x, y, width, height);
}

void Renderer::applyColorOverlay(float r, float g, float b, float alpha) {
    // Dibujar un rectángulo semitransparente sobre toda la pantalla
    drawRect(0, 0, screenWidth_, screenHeight_, r, g, b, alpha);
}

void Renderer::renderFrame(const uint32_t* frameBuffer) {
    if (frameBuffer == nullptr) return;

    // Base de textura única: crea una vez, actualiza por frame.
    const uint8_t* pixels = reinterpret_cast<const uint8_t*>(frameBuffer);
    if (!frameTextureReady_) {
        if (!frameTexture_.createFromPixels(160, 144, pixels)) {
            clear();
            return;
        }
        frameTextureReady_ = true;
    } else {
        frameTexture_.updatePixels(pixels);
    }

    clear();
    drawTexturedRect(frameTexture_, 0, 0, screenWidth_, screenHeight_);
}

void Renderer::destroy() {
    frameTexture_.destroy();
    frameTextureReady_ = false;
    if (quadVbo_ != 0) {
        glDeleteBuffers(1, &quadVbo_);
        quadVbo_ = 0;
    }
    if (texCoordVbo_ != 0) {
        glDeleteBuffers(1, &texCoordVbo_);
        texCoordVbo_ = 0;
    }
    spriteShader_.destroy();
    rectShader_.destroy();
}

} // namespace gpsquest96
