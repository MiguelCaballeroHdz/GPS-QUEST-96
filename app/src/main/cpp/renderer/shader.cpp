#include "renderer/shader.h"

#include <android/log.h>
#include <cstring>

#define LOG_TAG "GPSQuest96"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace gpsquest96 {

Shader::Shader()
    : programId_(0), vertexShaderId_(0), fragmentShaderId_(0) {
}

Shader::~Shader() {
    destroy();
}

bool Shader::loadFromSource(const char* vertexSource, const char* fragmentSource) {
    // Crear shaders
    vertexShaderId_ = glCreateShader(GL_VERTEX_SHADER);
    fragmentShaderId_ = glCreateShader(GL_FRAGMENT_SHADER);

    if (vertexShaderId_ == 0 || fragmentShaderId_ == 0) {
        LOGE("Error creando shaders");
        return false;
    }

    // Compilar
    if (!compileShader(vertexShaderId_, vertexSource)) {
        LOGE("Error compilando shader de vértices");
        return false;
    }

    if (!compileShader(fragmentShaderId_, fragmentSource)) {
        LOGE("Error compilando shader de fragmentos");
        return false;
    }

    // Crear programa
    programId_ = glCreateProgram();
    if (programId_ == 0) {
        LOGE("Error creando programa shader");
        return false;
    }

    glAttachShader(programId_, vertexShaderId_);
    glAttachShader(programId_, fragmentShaderId_);

    // Enlazar
    if (!linkProgram()) {
        LOGE("Error enlazando programa");
        return false;
    }

    LOGI("Shader compilado y enlazado correctamente");
    return true;
}

bool Shader::compileShader(GLuint shaderId, const char* source) {
    glShaderSource(shaderId, 1, &source, nullptr);
    glCompileShader(shaderId);

    GLint compiled = 0;
    glGetShaderiv(shaderId, GL_COMPILE_STATUS, &compiled);

    if (!compiled) {
        GLint infoLen = 0;
        glGetShaderiv(shaderId, GL_INFO_LOG_LENGTH, &infoLen);

        if (infoLen > 1) {
            char* infoLog = new char[infoLen];
            glGetShaderInfoLog(shaderId, infoLen, nullptr, infoLog);
            LOGE("Error compilando shader: %s", infoLog);
            delete[] infoLog;
        }

        glDeleteShader(shaderId);
        return false;
    }

    return true;
}

bool Shader::linkProgram() {
    glLinkProgram(programId_);

    GLint linked = 0;
    glGetProgramiv(programId_, GL_LINK_STATUS, &linked);

    if (!linked) {
        GLint infoLen = 0;
        glGetProgramiv(programId_, GL_INFO_LOG_LENGTH, &infoLen);

        if (infoLen > 1) {
            char* infoLog = new char[infoLen];
            glGetProgramInfoLog(programId_, infoLen, nullptr, infoLog);
            LOGE("Error enlazando programa: %s", infoLog);
            delete[] infoLog;
        }

        glDeleteProgram(programId_);
        return false;
    }

    return true;
}

void Shader::use() const {
    glUseProgram(programId_);
}

GLint Shader::getAttribLocation(const char* name) const {
    return glGetAttribLocation(programId_, name);
}

GLint Shader::getUniformLocation(const char* name) const {
    return glGetUniformLocation(programId_, name);
}

void Shader::setUniform1i(const char* name, GLint value) const {
    glUniform1i(getUniformLocation(name), value);
}

void Shader::setUniform1f(const char* name, GLfloat value) const {
    glUniform1f(getUniformLocation(name), value);
}

void Shader::setUniform2f(const char* name, GLfloat x, GLfloat y) const {
    glUniform2f(getUniformLocation(name), x, y);
}

void Shader::setUniform4f(const char* name, GLfloat x, GLfloat y, GLfloat z, GLfloat w) const {
    glUniform4f(getUniformLocation(name), x, y, z, w);
}

void Shader::setUniformMatrix4fv(const char* name, const GLfloat* matrix) const {
    glUniformMatrix4fv(getUniformLocation(name), 1, GL_FALSE, matrix);
}

void Shader::destroy() {
    if (programId_ != 0) {
        glDeleteProgram(programId_);
        programId_ = 0;
    }
    if (vertexShaderId_ != 0) {
        glDeleteShader(vertexShaderId_);
        vertexShaderId_ = 0;
    }
    if (fragmentShaderId_ != 0) {
        glDeleteShader(fragmentShaderId_);
        fragmentShaderId_ = 0;
    }
}

} // namespace gpsquest96
