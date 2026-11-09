# Evidencia — B2/009 slice 1 (lectura de tablas)

Fecha: 2026-10-27
Dispositivo: POCO X7 Pro (`XWWSAY4LAAPZHIA6`)
Build: `2026-10-27+b2-wildtable`
Spec: `specs/009-b2-spawns/spec.md` — **APROBADA** el 2026-10-27.

## Alcance verificado

Slice 1 es **solo lectura**. No hay spawn, no se escribe en la ROM/SRAM y no se
toca el gameplay.

- Localización estructural de las tablas grass/water de Gen 2 desde la ROM.
- Parseo de la tabla de ruta inicial (grupo 24, mapa 3).
- Mapa sin tabla (casa del jugador, grupo 24, mapa 7) devuelve vacío.
- Host test sintético y host test contra la ROM real de Gen 2.

## Host tests

Con `GPSQUEST_ROM_GEN2` apuntando a la ROM de Gen 2:

- `test_wildtable`: **41 checks, 0 failures**
  - 24 checks sintéticos (tabla válida, agua, mapa ausente, tabla corrupta).
  - 17 checks reales: 4 tablas (61/38/30/24 entradas), ruta inicial en
    `0x2AE04`, tasas `25/25/25`, `Pidgey`/`Sentret`/`una criatura`, agua de Ruinas
    de Alph.
- Total del arnés con ROM real: **174 checks** (30 + 18 + 26 + 20 + 41 C++ +
  39 Java).
- Sin la variable de entorno: **157 checks** (el test real se omite).

## POCO

Disparador: long-press en “Vista: Juego/Mapa” (diagnóstico existente).

Logs relevantes:

```text
I GPSQuest96: BUILD 2026-10-27+b2-wildtable
I GPSQuest96: B2 mapa 24/3: grass tabla=0x2A5F0 entrada=0x2AE04 n=61 tasas=25/25/25
I GameEngine: B2 probe 24/3 (ruta inicial): kind=1 tabla=0x2a5f0 entrada=0x2ae04 n=61 tasas=25/25/25 primer=2/16
I GPSQuest96: B2 mapa 24/7: sin tabla
I GameEngine: B2 probe 24/7 (Casa jugador): sin tabla
```

Interpretación:

- La tabla de ruta inicial se localiza en la ROM real y la entrada del mapa es la
  esperada: `0x2AE04`, 61 entradas en el bloque, tasas `25/25/25`.
- El primer par de la mañana es nivel `2`, especie `16` (`Pidgey`).
- La casa del jugador no tiene tabla: no se inventa un spawn para interiores.
- `CONTINUE` y el `.sav` no se tocaron; el diagnóstico es de solo lectura.

## Archivos

- `app/src/main/cpp/core/wild_table.h/.cpp`
- `app/src/main/cpp/core/rom_loader.h` (acceso de solo lectura)
- `app/src/main/cpp/jni/native_bridge.cpp`
- `app/src/main/java/com/gpsquest96/emulator/EmulatorBridge.java`
- `app/src/main/java/com/gpsquest96/emulator/GameEngine.java`
- `app/src/main/java/com/gpsquest96/ui/MainActivity.java`
- `app/src/main/cpp/CMakeLists.txt`
- `tools/host-test/test_wildtable.cpp`
- `tools/host-test/run.ps1`
- `tools/host-test/README.md`

## Pendiente

- Slice 2: leer `wMapGroup`/`wMapNumber` reales y registrar la tabla del mapa
  actual.
- Slice 3: inyección fiel por paso real con `TryWildEncounter` y
  `WildBattleScript` del juego.
- Gate B2 completo: encuentro en ruta inicial con especie/nivel de la tabla, sin
  spawn en interior/ciudad y `CONTINUE` intacto.
