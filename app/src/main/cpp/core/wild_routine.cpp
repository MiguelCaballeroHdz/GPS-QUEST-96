#include "core/wild_routine.h"

#include "core/rom_loader.h"

#include <cstring>

namespace gpsquest96 {

namespace {

// Firma de TryWildEncounter (28 B, ver wild_routine.h).
//   CD ?? ?? 30 ?? CD ?? ?? 20 ?? CD ?? ?? 30 ?? AF C9
//   AF EA ?? ?? EA ?? ?? 3E 01 A7 C9
// Los ?? de los CALL son los destinos (misma banca o home); los de los
// JR, los desplazamientos; los de los EA, las direcciones WRAM que la
// firma revela (bytes 19-20 y 22-23).
constexpr uint8_t kTryWildEncounterPattern[] = {
    0xCD, 0x00, 0x00, 0x30, 0x00, 0xCD, 0x00, 0x00, 0x20, 0x00,
    0xCD, 0x00, 0x00, 0x30, 0x00, 0xAF, 0xC9, 0xAF, 0xEA, 0x00,
    0x00, 0xEA, 0x00, 0x00, 0x3E, 0x01, 0xA7, 0xC9
};
constexpr bool kTryWildEncounterMask[] = {
    true, false, false, true, false, true, false, false, true, false,
    true, false, false, true, false, true, true, true, true, false,
    false, true, false, false, true, true, true, true
};

// Ancla de ChooseWildEncounter (28 B): cuatro rondas
//   call Random; cp <pct>; jr c, .ok; inc b
// seguidas de
//   ld a, b; ld [wCurPartyLevel], a; ld b, [hl]
// Los inmediatos de cp son comodines (dependen de la macro `percent`);
// la estructura repetida x4 es la que hace unica a la firma.
constexpr uint8_t kChooseWildEncounterPattern[] = {
    0xCD, 0x00, 0x00, 0xFE, 0x00, 0x38, 0x00, 0x04,
    0xFE, 0x00, 0x38, 0x00, 0x04, 0xFE, 0x00, 0x38,
    0x00, 0x04, 0xFE, 0x00, 0x38, 0x00, 0x04, 0x78,
    0xEA, 0x00, 0x00, 0x46
};
constexpr bool kChooseWildEncounterMask[] = {
    true, false, false, true, false, true, false, true,
    true, false, true, false, true, true, false, true,
    false, true, true, false, true, false, true, true,
    true, false, false, true
};

// Firma de RandomEncounter (57 B, ver wild_routine.h).
//   call CheckWildEncounterCooldown; jr c, .nope
//   call CanEncounterWildMon; jr nc, .nope
//   ld hl, wStatusFlags2; bit n, [hl]; jr nz, .bug_contest
//   farcall TryWildEncounter (3E ?? 21 ?? ?? CF: rst FarCall, RST $08)
//   jr nz, .nope; jr .ok
// .bug_contest
//   call _TryWildEncounter_BugContest; jr nc, .nope; jr .ok_bug_contest
// .nope
//   ld a, 1; and a; ret
// .ok
//   ld a, BANK(WildBattleScript); ld hl, WildBattleScript; jr .done
// .ok_bug_contest
//   ld a, BANK(BugCatchingContestBattleScript)
//   ld hl, BugCatchingContestBattleScript; jr .done
// .done
//   call CallScript; scf; ret
// Revela: direccion de TryWildEncounter (bytes 20-21), banco (39) y
// direccion (41-42) de WildBattleScript, banco (46) y direccion (48-49)
// del guion del concurso, y direccion de CallScript (bytes 53-54).
constexpr uint8_t kRandomEncounterPattern[] = {
    0xCD, 0x00, 0x00, 0x38, 0x00, 0xCD, 0x00, 0x00, 0x30, 0x00,
    0x21, 0x00, 0x00, 0xCB, 0x00, 0x20, 0x00, 0x3E, 0x00, 0x21,
    0x00, 0x00, 0xCF, 0x20, 0x00, 0x18, 0x00, 0xCD, 0x00, 0x00,
    0x30, 0x00, 0x18, 0x00, 0x3E, 0x01, 0xA7, 0xC9, 0x3E, 0x00,
    0x21, 0x00, 0x00, 0x18, 0x00, 0x3E, 0x00, 0x21, 0x00, 0x00,
    0x18, 0x00, 0xCD, 0x00, 0x00, 0x37, 0xC9
};
constexpr bool kRandomEncounterMask[] = {
    true, false, false, true, false, true, false, false, true, false,
    true, false, false, true, false, true, false, true, false, true,
    false, false, true, true, false, true, false, true, false, false,
    true, false, true, false, true, true, true, true, true, false,
    true, false, false, true, false, true, false, true, false, false,
    true, false, true, false, false, true, true
};

// Firma de CallScript (18 B, home/map.asm): cuerpo completo.
//   ld [wScriptBank], a; ld a, l; ld [wScriptPos], a
//   ld a, h; ld [wScriptPos + 1], a
//   ld a, PLAYEREVENT_MAPSCRIPT; ld [wScriptRunning], a; scf; ret
// Revela wScriptBank (bytes 1-2), wScriptPos (5-6), wScriptPos+1 (9-10,
// debe ser wScriptPos+1), PLAYEREVENT_MAPSCRIPT (byte 12) y
// wScriptRunning (bytes 14-15). Solo se busca en ROM0.
constexpr uint8_t kCallScriptPattern[] = {
    0xEA, 0x00, 0x00, 0x7D, 0xEA, 0x00, 0x00, 0x7C, 0xEA, 0x00,
    0x00, 0x3E, 0x00, 0xEA, 0x00, 0x00, 0x37, 0xC9
};
constexpr bool kCallScriptMask[] = {
    true, false, false, true, true, false, false, true, true, false,
    false, true, false, true, false, false, true, true
};

constexpr uint32_t kBankSize = 0x4000;

bool isWram(uint16_t addr) {
    return addr >= 0xD000 && addr <= 0xDFFF;
}

bool isPlausibleCodeAddr(uint16_t addr) {
    // Destino de CALL dentro del juego: home (0x0000-0x3FFF) o banca
    // conmutable (0x4000-0x7FFF). Nunca RAM/IO.
    return addr < 0x8000;
}

bool matchAt(const uint8_t* data, size_t size, uint32_t off,
             const uint8_t* pattern, const bool* mask, size_t len) {
    if (off + len > size) {
        return false;
    }
    // Sin cruzar bancos: una rutina vive dentro de un solo banco.
    if ((off / kBankSize) != ((off + len - 1) / kBankSize)) {
        return false;
    }
    for (size_t i = 0; i < len; ++i) {
        if (mask[i] && data[off + i] != pattern[i]) {
            return false;
        }
    }
    return true;
}

// Busca la firma; exige EXACTAMENTE una coincidencia.
bool findUnique(const uint8_t* data, size_t size, const uint8_t* pattern,
                const bool* mask, size_t len, uint32_t& outOffset) {
    uint32_t found = 0;
    uint32_t foundOff = 0;
    for (uint32_t off = 0; off + len <= size; ++off) {
        if (matchAt(data, size, off, pattern, mask, len)) {
            ++found;
            foundOff = off;
            if (found > 1) {
                return false;
            }
        }
    }
    if (found != 1) {
        return false;
    }
    outOffset = foundOff;
    return true;
}

// Cuenta las coincidencias de una firma en toda la ROM.
uint32_t countMatches(const uint8_t* data, size_t size,
                      const uint8_t* pattern, const bool* mask,
                      size_t len) {
    uint32_t count = 0;
    for (uint32_t o = 0; o + len <= size; ++o) {
        if (matchAt(data, size, o, pattern, mask, len)) {
            ++count;
        }
    }
    return count;
}

// Busca la firma exigiendo EXACTAMENTE una coincidencia en [0, limit).
// Devuelve OK / NOT_FOUND / AMBIGUOUS.
WildRoutineStatus findUniqueInRange(const uint8_t* data, size_t size,
                                    const uint8_t* pattern,
                                    const bool* mask, size_t len,
                                    uint32_t limit, uint32_t& outOffset) {
    if (limit > size) {
        limit = static_cast<uint32_t>(size);
    }
    uint32_t found = 0;
    uint32_t foundOff = 0;
    for (uint32_t off = 0; off + len <= limit; ++off) {
        if (matchAt(data, size, off, pattern, mask, len)) {
            ++found;
            foundOff = off;
            if (found > 1) {
                return WildRoutineStatus::AMBIGUOUS;
            }
        }
    }
    if (found != 1) {
        return WildRoutineStatus::NOT_FOUND;
    }
    outOffset = foundOff;
    return WildRoutineStatus::OK;
}

WildRoutineLocation toLocation(uint32_t romOffset) {
    WildRoutineLocation loc;
    loc.romOffset = romOffset;
    if (romOffset < kBankSize) {
        loc.bank = 0;
        loc.cpuAddress = static_cast<uint16_t>(romOffset);
    } else {
        loc.bank = static_cast<uint16_t>(romOffset / kBankSize);
        loc.cpuAddress = static_cast<uint16_t>(
            0x4000 + (romOffset % kBankSize));
    }
    return loc;
}

uint16_t readWordLE(const uint8_t* p) {
    return static_cast<uint16_t>(p[0] | (static_cast<uint16_t>(p[1]) << 8));
}

} // namespace

void WildRoutineLocator::clear() {
    source_ = nullptr;
    data_ = nullptr;
    size_ = 0;
    loaded_ = false;
}

bool WildRoutineLocator::load(const RomLoader& loader) {
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

    loaded_ = true;
    return true;
}

WildRoutineStatus WildRoutineLocator::locate(WildRoutineInfo& out) const {
    out = WildRoutineInfo{};
    if (!loaded_ || data_ == nullptr || size_ < 0x150) {
        return WildRoutineStatus::NO_ROM;
    }

    // --- TryWildEncounter ---
    uint32_t off = 0;
    if (!findUnique(data_, size_, kTryWildEncounterPattern,
                    kTryWildEncounterMask,
                    sizeof(kTryWildEncounterPattern), off)) {
        // Distinguir "no esta" de "esta dos veces" para el diagnostico.
        uint32_t count = 0;
        for (uint32_t o = 0; o + sizeof(kTryWildEncounterPattern) <= size_; ++o) {
            if (matchAt(data_, size_, o, kTryWildEncounterPattern,
                        kTryWildEncounterMask,
                        sizeof(kTryWildEncounterPattern))) {
                ++count;
            }
        }
        return count == 0 ? WildRoutineStatus::NOT_FOUND
                          : WildRoutineStatus::AMBIGUOUS;
    }
    out.tryWildEncounter = toLocation(off);
    const uint8_t* p = data_ + off;
    out.tempWildMonSpeciesAddr = readWordLE(p + 19);
    out.battleTypeAddr = readWordLE(p + 22);
    if (!isWram(out.tempWildMonSpeciesAddr) ||
        !isWram(out.battleTypeAddr) ||
        out.tempWildMonSpeciesAddr == out.battleTypeAddr) {
        return WildRoutineStatus::NOT_FOUND;
    }
    // Los tres CALL de la rutina apuntan a codigo del juego.
    if (!isPlausibleCodeAddr(readWordLE(p + 1)) ||
        !isPlausibleCodeAddr(readWordLE(p + 6)) ||
        !isPlausibleCodeAddr(readWordLE(p + 11))) {
        return WildRoutineStatus::NOT_FOUND;
    }

    // --- ChooseWildEncounter (para wCurPartyLevel: el nivel preparado) ---
    if (!findUnique(data_, size_, kChooseWildEncounterPattern,
                    kChooseWildEncounterMask,
                    sizeof(kChooseWildEncounterPattern), off)) {
        uint32_t count = 0;
        for (uint32_t o = 0; o + sizeof(kChooseWildEncounterPattern) <= size_; ++o) {
            if (matchAt(data_, size_, o, kChooseWildEncounterPattern,
                        kChooseWildEncounterMask,
                        sizeof(kChooseWildEncounterPattern))) {
                ++count;
            }
        }
        return count == 0 ? WildRoutineStatus::NOT_FOUND
                          : WildRoutineStatus::AMBIGUOUS;
    }
    out.chooseWildEncounter = toLocation(off);
    out.curPartyLevelAddr = readWordLE(data_ + off + 25);
    if (!isWram(out.curPartyLevelAddr)) {
        return WildRoutineStatus::NOT_FOUND;
    }

    // --- RandomEncounter (la entrada fiel: cooldown + hierba +
    // TryWildEncounter + instalacion del guion de batalla) ---
    if (!findUnique(data_, size_, kRandomEncounterPattern,
                    kRandomEncounterMask,
                    sizeof(kRandomEncounterPattern), off)) {
        return countMatches(data_, size_, kRandomEncounterPattern,
                            kRandomEncounterMask,
                            sizeof(kRandomEncounterPattern)) == 0
                   ? WildRoutineStatus::NOT_FOUND
                   : WildRoutineStatus::AMBIGUOUS;
    }
    out.randomEncounter = toLocation(off);
    const uint8_t* q = data_ + off;
    const uint16_t revealedTryWild = readWordLE(q + 20);
    out.wildBattleScriptBank = q[39];
    out.wildBattleScriptAddr = readWordLE(q + 41);
    out.bugContestScriptBank = q[46];
    out.bugContestScriptAddr = readWordLE(q + 48);
    const uint16_t revealedCallScript = readWordLE(q + 53);
    // Cruce con TryWildEncounter: el farcall debe apuntar a la rutina
    // localizada por su propia firma. Si no coincide, la ROM no es la
    // que creemos: bloquear, no llamar.
    if (revealedTryWild != out.tryWildEncounter.cpuAddress) {
        return WildRoutineStatus::NOT_FOUND;
    }
    // CallScript vive en home (ROM0): su direccion CPU es < 0x4000.
    if (revealedCallScript >= 0x4000) {
        return WildRoutineStatus::NOT_FOUND;
    }
    // Los guiones de batalla viven en bancos conmutables.
    if (out.wildBattleScriptBank == 0 ||
        out.wildBattleScriptAddr < 0x4000 ||
        out.wildBattleScriptAddr >= 0x8000 ||
        out.bugContestScriptBank == 0 ||
        out.bugContestScriptAddr < 0x4000 ||
        out.bugContestScriptAddr >= 0x8000) {
        return WildRoutineStatus::NOT_FOUND;
    }

    // --- CallScript (solo ROM0: vive en home) ---
    WildRoutineStatus st = findUniqueInRange(
        data_, size_, kCallScriptPattern, kCallScriptMask,
        sizeof(kCallScriptPattern), kBankSize, off);
    if (st != WildRoutineStatus::OK) {
        return st;
    }
    out.callScript = toLocation(off);
    if (out.callScript.bank != 0) {
        return WildRoutineStatus::NOT_FOUND;
    }
    const uint8_t* r = data_ + off;
    out.scriptBankAddr = readWordLE(r + 1);
    out.scriptPosAddr = readWordLE(r + 5);
    const uint16_t scriptPosPlus1 = readWordLE(r + 9);
    out.playerEventMapscript = r[12];
    out.scriptRunningAddr = readWordLE(r + 14);
    if (!isWram(out.scriptBankAddr) || !isWram(out.scriptPosAddr) ||
        !isWram(out.scriptRunningAddr) ||
        scriptPosPlus1 !=
            static_cast<uint16_t>(out.scriptPosAddr + 1) ||
        out.playerEventMapscript == 0) {
        return WildRoutineStatus::NOT_FOUND;
    }
    // Cruce con RandomEncounter: el `call CallScript` de .done debe
    // apuntar a la CallScript localizada por su propia firma.
    if (out.callScript.cpuAddress != revealedCallScript) {
        return WildRoutineStatus::NOT_FOUND;
    }

    return WildRoutineStatus::OK;
}

} // namespace gpsquest96
