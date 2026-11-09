#ifndef GPSQUEST96_ROM_LOADER_H
#define GPSQUEST96_ROM_LOADER_H

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

namespace gpsquest96 {

/**
 * Estructura con la información de la cabecera de una ROM de hardware de 8 bits.
 * Los offsets son según la especificación del hardware original:
 * - Título: 0x134 (16 bytes)
 * - Game Code: 0x13F (4 bytes)
 * - Tipo de cartucho: 0x147
 * - Tamaño ROM: 0x148
 * - Tamaño RAM: 0x149
 */
struct RomHeader {
    char title[17];           // 16 bytes + null terminator
    char gameCode[5];         // 4 bytes + null terminator
    uint8_t cgbFlag;          // 0x143: 0x80 CGB-enhanced, 0xC0 CGB-only
    uint8_t cartridgeType;
    uint8_t romSize;
    uint8_t ramSize;
    uint8_t version;
    uint8_t headerChecksum;
    uint16_t globalChecksum;
};

/**
 * Cargador de ROM.
 * La ROM se copia entera a memoria al abrir (máx. 8 MB de un cartucho GB);
 * leer byte a byte del disco en cada fetch era ~500k syscalls/s y hundía
 * la batería. El archivo se mantiene abierto para no alterar el contrato.
 */
class RomLoader {
public:
    RomLoader();
    ~RomLoader();

    /**
     * Abre una ROM en modo binario.
     * @param path Ruta absoluta al archivo .gb/.gbc
     * @return true si se abrió correctamente
     */
    bool open(const char* path);

    /**
     * Cierra el archivo y libera recursos.
     */
    void close();

    /**
     * Lee la cabecera completa de la ROM.
     * @param header Estructura a rellenar
     * @return true si la lectura fue exitosa
     */
    bool readHeader(RomHeader& header);

    /**
     * Lee un bloque de bytes desde la ROM.
     * @param offset Desplazamiento desde el inicio del archivo
     * @param buffer Buffer de destino
     * @param size Número de bytes a leer
     * @return Número real de bytes leídos
     */
    size_t readBytes(uint32_t offset, void* buffer, size_t size);

    /**
     * Obtiene el tamaño total del archivo ROM.
     */
    uint32_t getFileSize() const { return fileSize_; }

    /**
     * Verifica si hay una ROM cargada.
     */
    bool isOpen() const { return file_ != nullptr && !romData_.empty(); }

    /**
     * Flag CGB de la cabecera (0x143). 0x80 = enhanced, 0xC0 = only.
     * Sin ROM leída devuelve 0 (modo DMG).
     */
    uint8_t cgbFlag() const {
        return (romData_.size() > 0x143) ? romData_[0x143] : 0;
    }

    /**
     * true si la ROM declara modo CGB (color). No implica que el core ya
     * lo emule por completo: solo que la cabecera lo pide.
     */
    bool isCgbCapable() const { return (cgbFlag() & 0x80) != 0; }

    /** true si la ROM es CGB-only (no arranca en DMG). */
    bool isCgbOnly() const { return (cgbFlag() & 0xC0) == 0xC0; }

    const char* getPath() const { return path_; }

    /**
     * Acceso de solo lectura a la ROM completa en RAM.
     * Lo usa el lector de tablas de encuentro (spec 009); no transfiere
     * propiedad y queda inválido al cerrar/reabrir la ROM.
     */
    const uint8_t* data() const {
        return romData_.empty() ? nullptr : romData_.data();
    }
    size_t size() const { return romData_.size(); }

private:
    FILE* file_;
    uint32_t fileSize_;
    char path_[512];
    std::vector<uint8_t> romData_;   // ROM completa en RAM (fetch O(1))
};

} // namespace gpsquest96

#endif // GPSQUEST96_ROM_LOADER_H
