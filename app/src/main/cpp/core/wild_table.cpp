#include "core/wild_table.h"

#include "core/rom_loader.h"

#include <algorithm>
#include <cstring>

namespace gpsquest96 {

namespace {

constexpr uint32_t kGrassEntrySize = 47;
constexpr uint32_t kWaterEntrySize = 9;

// Una tabla real de Gen 2 tiene 61/30 entradas grass y 38/13 water. Los
// mínimos altos evitan que una secuencia casual de bytes se acepte como tabla.
constexpr uint32_t kMinGrassEntries = 15;
// Las tablas water reales de Gen 2 tienen 38/24 entradas. Un mínimo de 10
// descarta una secuencia casual de 9 entradas detectada en el banco 0x08.
constexpr uint32_t kMinWaterEntries = 10;

bool validMap(uint8_t group, uint8_t map) {
    return group >= 1 && group <= 26 && map >= 1;
}

bool validLevelSpecies(uint8_t level, uint8_t species) {
    return level >= 1 && level <= 100 && species >= 1 && species <= 251;
}

bool validGrassEntry(const uint8_t* p) {
    if (!validMap(p[0], p[1])) {
        return false;
    }
    for (int i = 0; i < 21; ++i) {
        if (!validLevelSpecies(p[5 + i * 2], p[6 + i * 2])) {
            return false;
        }
    }
    return true;
}

bool validWaterEntry(const uint8_t* p) {
    if (!validMap(p[0], p[1])) {
        return false;
    }
    for (int i = 0; i < 3; ++i) {
        if (!validLevelSpecies(p[3 + i * 2], p[4 + i * 2])) {
            return false;
        }
    }
    return true;
}

/**
 * Detecta una entrada "casi válida" justo antes del candidato. Sirve para no
 * aceptar la cola de una tabla corrupta como si fuera una tabla nueva.
 */
bool looksLikeGrassContinuation(const uint8_t* p) {
    if (!validMap(p[0], p[1])) {
        return false;
    }
    int validPairs = 0;
    for (int i = 0; i < 21; ++i) {
        if (validLevelSpecies(p[5 + i * 2], p[6 + i * 2])) {
            ++validPairs;
        }
    }
    return validPairs >= 19;
}

bool looksLikeWaterContinuation(const uint8_t* p) {
    if (!validMap(p[0], p[1])) {
        return false;
    }
    int validPairs = 0;
    for (int i = 0; i < 3; ++i) {
        if (validLevelSpecies(p[3 + i * 2], p[4 + i * 2])) {
            ++validPairs;
        }
    }
    return validPairs >= 2;
}

struct Candidate {
    WildTableKind kind;
    uint32_t offset;
    uint32_t count;
};

bool overlaps(uint32_t a, uint32_t aLen, uint32_t b, uint32_t bLen) {
    return a < b + bLen && b < a + aLen;
}

uint32_t blockLength(WildTableKind kind, uint32_t entryCount) {
    const uint32_t entrySize = (kind == WildTableKind::GRASS)
                                   ? kGrassEntrySize
                                   : kWaterEntrySize;
    return entryCount * entrySize + 1; // + terminador 0xFF
}

WildTableEntry parseEntry(const uint8_t* p, WildTableKind kind,
                          uint32_t entryOffset, uint32_t blockOffset,
                          uint32_t blockEntryCount) {
    WildTableEntry e;
    e.group = p[0];
    e.map = p[1];
    e.entryOffset = entryOffset;
    e.blockOffset = blockOffset;
    e.blockEntryCount = blockEntryCount;

    if (kind == WildTableKind::GRASS) {
        e.rates[0] = p[2];
        e.rates[1] = p[3];
        e.rates[2] = p[4];
        for (int period = 0; period < 3; ++period) {
            for (int slot = 0; slot < 7; ++slot) {
                const int base = 5 + period * 14 + slot * 2;
                e.levels[period][slot] = p[base];
                e.species[period][slot] = p[base + 1];
            }
        }
    } else {
        e.rates[0] = p[2];
        for (int slot = 0; slot < 3; ++slot) {
            e.levels[0][slot] = p[3 + slot * 2];
            e.species[0][slot] = p[4 + slot * 2];
        }
    }
    return e;
}

} // namespace

void WildTableReader::clear() {
    source_ = nullptr;
    data_ = nullptr;
    size_ = 0;
    loaded_ = false;
    blocks_.clear();
    grass_.clear();
    water_.clear();
}

bool WildTableReader::load(const RomLoader& loader) {
    const uint8_t* newData = loader.data();
    const size_t newSize = loader.size();

    if (loaded_ && source_ == &loader && data_ == newData && size_ == newSize) {
        return true;
    }

    clear();
    source_ = &loader;
    data_ = newData;
    size_ = newSize;

    if (data_ == nullptr || size_ < 0x150) {
        return false;
    }

    scan();
    loaded_ = true;
    return true;
}

bool WildTableReader::scan() {
    blocks_.clear();
    grass_.clear();
    water_.clear();

    if (data_ == nullptr || size_ < kGrassEntrySize) {
        return false;
    }

    std::vector<Candidate> candidates;

    // Grass: buscar secuencias de entradas de 47 B terminadas en 0xFF.
    for (uint32_t off = 0; off + kGrassEntrySize <= size_; ++off) {
        if (!validGrassEntry(data_ + off)) {
            continue;
        }
        uint32_t pos = off;
        uint32_t count = 0;
        while (pos + kGrassEntrySize <= size_ &&
               data_[pos] != 0xFF &&
               validGrassEntry(data_ + pos)) {
            ++count;
            pos += kGrassEntrySize;
        }
        if (count >= kMinGrassEntries && pos < size_ && data_[pos] == 0xFF) {
            const bool afterTerminator = off > 0 && data_[off - 1] == 0xFF;
            const bool continuation = off >= kGrassEntrySize &&
                                      looksLikeGrassContinuation(
                                          data_ + off - kGrassEntrySize);
            if (afterTerminator || !continuation) {
                candidates.push_back({WildTableKind::GRASS, off, count});
            }
        }
    }

    // Water: secuencias de entradas de 9 B terminadas en 0xFF.
    for (uint32_t off = 0; off + kWaterEntrySize <= size_; ++off) {
        if (!validWaterEntry(data_ + off)) {
            continue;
        }
        uint32_t pos = off;
        uint32_t count = 0;
        while (pos + kWaterEntrySize <= size_ &&
               data_[pos] != 0xFF &&
               validWaterEntry(data_ + pos)) {
            ++count;
            pos += kWaterEntrySize;
        }
        if (count >= kMinWaterEntries && pos < size_ && data_[pos] == 0xFF) {
            const bool afterTerminator = off > 0 && data_[off - 1] == 0xFF;
            const bool continuation = off >= kWaterEntrySize &&
                                      looksLikeWaterContinuation(
                                          data_ + off - kWaterEntrySize);
            if (afterTerminator || !continuation) {
                candidates.push_back({WildTableKind::WATER, off, count});
            }
        }
    }

    // La tabla real gana por longitud. Se descartan candidatos solapados para
    // no duplicar la misma tabla si el escaneo empieza en un byte interior.
    std::sort(candidates.begin(), candidates.end(),
              [](const Candidate& a, const Candidate& b) {
                  if (a.count != b.count) {
                      return a.count > b.count;
                  }
                  return a.offset < b.offset;
              });

    for (const Candidate& c : candidates) {
        const uint32_t len = blockLength(c.kind, c.count);
        bool overlap = false;
        for (const WildTableBlock& b : blocks_) {
            if (overlaps(c.offset, len, b.offset,
                         blockLength(b.kind, b.entryCount))) {
                overlap = true;
                break;
            }
        }
        if (overlap) {
            continue;
        }

        blocks_.push_back({c.kind, c.offset, c.count});
        const uint32_t entrySize = (c.kind == WildTableKind::GRASS)
                                       ? kGrassEntrySize
                                       : kWaterEntrySize;
        for (uint32_t i = 0; i < c.count; ++i) {
            const uint32_t entryOffset = c.offset + i * entrySize;
            const uint8_t* p = data_ + entryOffset;
            WildTableEntry e = parseEntry(p, c.kind, entryOffset, c.offset,
                                          c.count);
            if (c.kind == WildTableKind::GRASS) {
                grass_.push_back(e);
            } else {
                water_.push_back(e);
            }
        }
    }

    std::sort(blocks_.begin(), blocks_.end(),
              [](const WildTableBlock& a, const WildTableBlock& b) {
                  return a.offset < b.offset;
              });
    return !blocks_.empty();
}

bool WildTableReader::lookup(uint8_t group, uint8_t map,
                             WildTableKind preferred, WildTable& out) const {
    if (!validMap(group, map)) {
        return false;
    }

    auto fill = [&](const WildTableEntry& e, WildTableKind kind) {
        out = WildTable{};
        out.kind = kind;
        out.group = e.group;
        out.map = e.map;
        out.rates[0] = e.rates[0];
        out.rates[1] = e.rates[1];
        out.rates[2] = e.rates[2];
        std::memcpy(out.levels, e.levels, sizeof(out.levels));
        std::memcpy(out.species, e.species, sizeof(out.species));
        out.romOffset = e.blockOffset;
        out.entryOffset = e.entryOffset;
        out.tableEntryCount = e.blockEntryCount;
        out.slotsPerPeriod = (kind == WildTableKind::GRASS) ? 7 : 3;
    };

    auto findIn = [&](const std::vector<WildTableEntry>& entries,
                      WildTableKind kind) -> bool {
        for (const WildTableEntry& e : entries) {
            if (e.group == group && e.map == map) {
                fill(e, kind);
                return true;
            }
        }
        return false;
    };

    if (preferred == WildTableKind::GRASS) {
        return findIn(grass_, WildTableKind::GRASS);
    }
    if (preferred == WildTableKind::WATER) {
        return findIn(water_, WildTableKind::WATER);
    }
    if (findIn(grass_, WildTableKind::GRASS)) {
        return true;
    }
    return findIn(water_, WildTableKind::WATER);
}

} // namespace gpsquest96
