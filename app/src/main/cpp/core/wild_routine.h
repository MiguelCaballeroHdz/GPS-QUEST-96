#ifndef GPSQUEST96_WILD_ROUTINE_H
#define GPSQUEST96_WILD_ROUTINE_H

#include <cstddef>
#include <cstdint>

namespace gpsquest96 {

class RomLoader;

/**
 * Localizador de rutinas del motor por firma de instrucciones (spec 009,
 * slice 3).
 *
 * El disparo fiel por paso real NO elige especie/nivel en la app: llama a
 * la rutina del propio juego (`RandomEncounter`) y el juego decide con
 * sus tablas, su `Random`, su repelente, su cooldown y su colision con
 * hierba. Si hay encuentro, la propia rutina instala `WildBattleScript`
 * con `CallScript` y la batalla arranca sola en el motor de scripts.
 *
 * Fuente de las firmas: desensamblado publico de Crystal
 * (pret/pokecrystal, rama master), verificado en esta sesion contra
 * `engine/overworld/events.asm` y `engine/overworld/wildmons.asm`.
 *
 * - `TryWildEncounter` (wildmons.asm): su cuerpo completo (28 B).
 *   Devuelve A=0 (Z=1) con el encuentro preparado (especie en
 *   wTempWildMonSpecies, nivel en wCurPartyLevel, wBattleType = 0) y
 *   A=1 (Z=0) si no hay batalla. NO arranca la batalla: eso lo hace
 *   su llamador.
 * - `ChooseWildEncounter`: ancla de los cuatro `cp` de porcentaje
 *   seguidos de `ld a, b; ld [wCurPartyLevel], a; ld b, [hl]` (los
 *   inmediatos de `cp` son comodines: dependen de la macro `percent`).
 * - `RandomEncounter` (events.asm): su cuerpo completo (57 B). Es el
 *   unico llamador de `TryWildEncounter`: revisa el cooldown del
 *   juego, `CanEncounterWildMon` (hierba/colision), el concurso de
 *   bichos y, si hay encuentro, carga el banco/direccion de
 *   `WildBattleScript` (o del guion del concurso) y llama a
 *   `CallScript`. Devuelve con carry puesto si instalo el guion de
 *   batalla, y con carry libre si no hay encuentro. Su firma revela
 *   la direccion de `TryWildEncounter` (el `farcall`: `ld a, BANK`;
 *   `ld hl`; `rst FarCall`, FarCall en RST $08 = 0xCF), el banco y la
 *   direccion de `WildBattleScript` y del guion del concurso, y la
 *   direccion de `CallScript`. Esas direcciones se cruzan con las de
 *   las demas firmas: si no coinciden, la localizacion falla.
 * - `CallScript` (home/map.asm, ROM0): su cuerpo completo (18 B).
 *   Guarda banco/direccion del guion en wScriptBank/wScriptPos y
 *   PLAYEREVENT_MAPSCRIPT en wScriptRunning. Su firma revela las tres
 *   direcciones WRAM y el valor numerico de PLAYEREVENT_MAPSCRIPT
 *   (el inmediato de `ld a, n`: no se hardcodea). Solo se busca en
 *   ROM0 (0x0000-0x3FFF): `CallScript` vive en home.
 *
 * Reglas de seguridad:
 * - Una firma debe aparecer EXACTAMENTE una vez en la ROM. Cero
 *   coincidencias = NotFound; mas de una = Ambiguous. En ambos casos NO
 *   se llama a nada: el slice se bloquea con evidencia, no se simula.
 * - Las direcciones WRAM que las firmas revelan (wTempWildMonSpecies,
 *   wBattleType, wCurPartyLevel, wScriptBank, wScriptPos,
 *   wScriptRunning) se validan en 0xD000-0xDFFF; si no lo estan, la
 *   firma se rechaza.
 * - La coincidencia no puede cruzar un limite de banco de 0x4000 B: el
 *   codigo de una rutina vive dentro de un solo banco.
 * - Solo lectura: nunca escribe en la ROM ni en el estado del juego.
 */
enum class WildRoutineStatus : uint8_t {
    OK = 0,
    NOT_FOUND = 1,  // la firma no aparece en la ROM
    AMBIGUOUS = 2,  // la firma aparece mas de una vez
    NO_ROM = 3      // sin ROM cargada o demasiado pequena
};

struct WildRoutineLocation {
    uint32_t romOffset = 0;   // offset en el archivo ROM
    uint16_t bank = 0;        // banco MBC (0 = banco fijo 0x0000-0x3FFF)
    uint16_t cpuAddress = 0;  // direccion CPU (0x0000-0x7FFF)
};

/**
 * Rutinas localizadas + direcciones WRAM que sus firmas revelan.
 *
 * `randomEncounter` es la entrada fiel por paso real: devuelve con
 * carry puesto si el juego instalo el guion de batalla (especie en
 * wTempWildMonSpecies, nivel en wCurPartyLevel) y con carry libre si
 * no hay encuentro. La instalacion se verifica con wScriptRunning /
 * wScriptBank / wScriptPos contra los valores que revelan las firmas
 * (guion salvaje o guion del concurso de bichos).
 */
struct WildRoutineInfo {
    WildRoutineLocation tryWildEncounter;
    uint16_t tempWildMonSpeciesAddr = 0;  // wTempWildMonSpecies
    uint16_t battleTypeAddr = 0;          // wBattleType
    WildRoutineLocation chooseWildEncounter;
    uint16_t curPartyLevelAddr = 0;       // wCurPartyLevel
    WildRoutineLocation randomEncounter;
    WildRoutineLocation callScript;       // siempre en ROM0
    uint16_t scriptBankAddr = 0;          // wScriptBank
    uint16_t scriptPosAddr = 0;           // wScriptPos
    uint16_t scriptRunningAddr = 0;       // wScriptRunning
    uint8_t playerEventMapscript = 0;     // valor de PLAYEREVENT_MAPSCRIPT
    uint8_t wildBattleScriptBank = 0;     // BANK(WildBattleScript)
    uint16_t wildBattleScriptAddr = 0;    // WildBattleScript
    uint8_t bugContestScriptBank = 0;     // BANK(BugCatchingContestBattleScript)
    uint16_t bugContestScriptAddr = 0;    // BugCatchingContestBattleScript
};

class WildRoutineLocator {
public:
    bool load(const RomLoader& loader);

    bool isLoaded() const { return loaded_; }

    WildRoutineStatus locate(WildRoutineInfo& out) const;

    void clear();

private:
    const RomLoader* source_ = nullptr;
    const uint8_t* data_ = nullptr;
    size_t size_ = 0;
    bool loaded_ = false;
};

} // namespace gpsquest96

#endif // GPSQUEST96_WILD_ROUTINE_H
