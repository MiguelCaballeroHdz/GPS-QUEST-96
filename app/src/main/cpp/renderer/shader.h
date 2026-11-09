#ifndef GPSQUEST96_SHADER_H
#define GPSQUEST96_SHADER_H

#include <GLES2/gl2.h>
#include <string>

namespace gpsquest96 {

/**
 * Clase para compilar y gestionar shaders de OpenGL ES 2.0.
 * Soporta shaders de vértices y fragmentos con atributos y uniforms.
 */
class Shader {
public:
    Shader();
    ~Shader();

    /**
     * Compila y enlaza un programa shader.
     * @param vertexSource Código fuente del shader de vértices
     * @param fragmentSource Código fuente del shader de fragmentos
     * @return true si la compilación fue exitosa
     */
    bool loadFromSource(const char* vertexSource, const char* fragmentSource);

    /**
     * Usa este programa shader.
     */
    void use() const;

    /**
     * Obtiene la ubicación de un atributo.
     */
    GLint getAttribLocation(const char* name) const;

    /**
     * Obtiene la ubicación de un uniform.
     */
    GLint getUniformLocation(const char* name) const;

    /**
     * Establece un uniform entero.
     */
    void setUniform1i(const char* name, GLint value) const;

    /**
     * Establece un uniform float.
     */
    void setUniform1f(const char* name, GLfloat value) const;

    /**
     * Establece un uniform vec2.
     */
    void setUniform2f(const char* name, GLfloat x, GLfloat y) const;

    /**
     * Establece un uniform vec4.
     */
    void setUniform4f(const char* name, GLfloat x, GLfloat y, GLfloat z, GLfloat w) const;

    /**
     * Establece una matriz 4x4.
     */
    void setUniformMatrix4fv(const char* name, const GLfloat* matrix) const;

    /**
     * Libera recursos.
     */
    void destroy();

    GLuint getProgramId() const { return programId_; }

private:
    GLuint programId_;
    GLuint vertexShaderId_;
    GLuint fragmentShaderId_;

    bool compileShader(GLuint shaderId, const char* source);
    bool linkProgram();
};

} // namespace gpsquest96

#endif // GPSQUEST96_SHADER_H
