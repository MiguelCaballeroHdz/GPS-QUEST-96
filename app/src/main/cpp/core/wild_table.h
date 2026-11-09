#ifndef GPSQUEST96_WILD_TABLE_H
#define GPSQUEST96_WILD_TABLE_H

#include <cstddef>
#include <cstdint>
#include <vector>

namespace gpsquest96 {

class RomLoader;

/**
 * Tablas de encuentro salvaje de Gen 2.
 *
 * Estructura real de la ROM (no inventada):
 * - Grass: 2 B grupo/mapa + 3 B tasas morn/day/nite + 7 pares nivel/especie
 *   por cada momento = 47 B por entrada; terminador 0xFF.
 * - Water: 2 B grupo/mapa + 1 B tasa + 3 pares nivel/especie = 9 B por
 *   entrada; terminador 0xFF.
 *
 * El lector localiza las tablas por estructura y valida rangos. Nunca usa
 * offsets fijos: si la ROM cambia de región/parche, o no encuentra una
 * secuencia suficientemente larga, devuelve vacío.
 */
enum class WildTableKind : uint8_t {
    NONE = 0,
    GRASS = 1,
    WATER = 2
};

struct WildTableEntry {
    uint8_t group = 0;
    uint8_t map = 0;
    uint8_t rates[3] = {0, 0, 0};       // grass: morn/day/nite; water: [0]
    uint8_t levels[3][7] = {};          // grass: 3x7; water: [0][0..2]
    uint8_t species[3][7] = {};         // grass: 3x7; water: [0][0..2]
    uint32_t entryOffset = 0;           // offset de esta entrada en la ROM
    uint32_t blockOffset = 0;           // inicio de la tabla que la contiene
    uint32_t blockEntryCount = 0;       // entradas de esa tabla
};

struct WildTableBlock {
    WildTableKind kind = WildTableKind::NONE;
    uint32_t offset = 0;
    uint32_t entryCount = 0;
};

struct WildTable {
    WildTableKind kind = WildTableKind::NONE;
    uint8_t group = 0;
    uint8_t map = 0;
    uint8_t rates[3] = {0, 0, 0};
    uint8_t levels[3][7] = {};
    uint8_t species[3][7] = {};
    uint32_t romOffset = 0;             // inicio de la tabla
    uint32_t entryOffset = 0;           // offset de la entrada del mapa
    uint32_t tableEntryCount = 0;
    int slotsPerPeriod = 0;             // 7 grass, 3 water
};

/**
 * Lector cacheado de tablas de encuentro.
 *
 * `load()` solo reescanea si cambia el puntero/tamaño de la ROM. Es de solo
 * lectura: no escribe en la ROM ni en el estado del juego.
 */
class WildTableReader {
public:
    bool load(const RomLoader& loader);

    bool isLoaded() const { return loaded_; }
    bool foundAny() const { return !blocks_.empty(); }

    /**
     * Busca el mapa en las tablas aceptadas. `preferred` permite pedir
     * grass/water; NONE significa grass y, si no hay, water.
     */
    bool lookup(uint8_t group, uint8_t map, WildTableKind preferred,
                WildTable& out) const;

    bool lookup(uint8_t group, uint8_t map, WildTable& out) const {
        return lookup(group, map, WildTableKind::NONE, out);
    }

    const std::vector<WildTableBlock>& blocks() const { return blocks_; }
    const std::vector<WildTableEntry>& grassEntries() const { return grass_; }
    const std::vector<WildTableEntry>& waterEntries() const { return water_; }

    void clear();

private:
    const RomLoader* source_ = nullptr;
    const uint8_t* data_ = nullptr;
    size_t size_ = 0;
    bool loaded_ = false;
    std::vector<WildTableBlock> blocks_;
    std::vector<WildTableEntry> grass_;
    std::vector<WildTableEntry> water_;

    bool scan();
};

} // namespace gpsquest96

#endif // GPSQUEST96_WILD_TABLE_H
