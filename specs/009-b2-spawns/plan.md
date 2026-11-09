# plan.md — 009 B2 spawns desde ROM (Gen 2)

Estado: **APROBADO** 2026-10-27. **Slices 1 y 2 HECHOS**; siguiente slice 3.
Spec: `specs/009-b2-spawns/spec.md`.
Regla: ningún slice de código se ejecuta hasta que la spec esté APROBADA.

## Orden de slices

### Slice 1 — Parser de tablas (solo lectura)

**Objetivo:** leer las cuatro tablas de encuentro de Gen 2 y exponerlas por JNI sin
tocar el estado del juego.

**Archivos previstos**
- `app/src/main/cpp/core/wild_table.h` (nuevo): estructura `WildTable`, `WildEntry`.
- `app/src/main/cpp/core/wild_table.cpp` (nuevo): localización estructural + parseo.
- `app/src/main/cpp/jni/native_bridge.cpp`: JNI de solo lectura.
- `app/src/main/java/com/gpsquest96/emulator/EmulatorBridge.java`: declaración JNI.
- `tools/host-test/test_wildtable.cpp` (nuevo): pruebas con la ROM local.
- `tools/host-test/run.ps1`: registrar el nuevo test.

**Tareas**
1. Definir estructuras y errores (`Ok`, `NotFound`, `Invalid`).
2. Implementar escaneo de tablas grass (47 B) y water (9 B), terminador `0xFF`.
3. Validar rangos y elegir la tabla con más entradas; cachear por ROM.
4. Exponer `nativeGetWildTable(group, map)` con tasas + 3×7 o 3×3 pares.
5. Añadir `test_wildtable` con la ROM de prueba:
   - 61 entradas Johto grass;
   - ruta inicial (24/3) con los datos medidos;
   - mapa sin tabla → vacío;
   - tabla corrupta → `Invalid`, sin falso positivo.
6. Build host + `assembleDebug --offline`.

**Criterio de salida:** host verde y log nativo de la tabla de ruta inicial en POCO, sin
inyección ni cambios de comportamiento del juego.

**Resultado (2026-10-27):** cumplido. `test_wildtable` 24 checks sintéticos / 41 con
ROM real (0 fallos); total 174 con ROM real. En POCO, build
`2026-10-27+b2-wildtable`: ruta inicial (`24/3`) → `grass tabla=0x2A5F0
entrada=0x2AE04 n=61 tasas=25/25/25`; casa (`24/7`) → `sin tabla`. Evidencia:
`tools/poco-capture/20261027-b2-slice1/`. No hay spawn todavía.

### Slice 2 — Mapa actual y diagnóstico (solo lectura)

**Objetivo:** leer el mapa actual de WRAM y loguear el tramo/tabla disponible.

**Archivos previstos**
- `app/src/main/cpp/jni/native_bridge.cpp`: `nativeGetCurrentMap()`.
- `app/src/main/java/com/gpsquest96/emulator/EmulatorBridge.java`.
- `app/src/main/java/com/gpsquest96/emulator/GameEngine.java`: acceso de solo lectura.
- `app/src/main/java/com/gpsquest96/ui/MainActivity.java`: log diagnóstico detrás de
  un flag de depuración, sin UI nueva.

**Tareas**
1. Leer `0xDCB5..0xDCB8` con guardas (mapa 1..26; si no, inválido).
2. Resolver la tabla del mapa y loguear `B2 mapa G/M tabla=... entradas=...`.
3. Verificar en POCO: habitación 24/7 sin tabla; ruta inicial 24/3 con tabla.
4. No disparar encuentros todavía.

**Criterio de salida:** logs correctos en POCO y cero cambios de gameplay.

**Resultado (2026-10-27):** cumplido. Build `2026-10-27+b2-currentmap`.
`nativeGetCurrentMap()` lee `0xDCB5..0xDCB8` con guardas; el long-press de
diagnostico loguea el mapa actual y su tabla. En POCO, ruta inicial devuelve
`24/3 (y=10 x=46): kind=1 tabla=0x2a5f0 entrada=0x2ae04 n=61 tasas=25/25/25
primer=2/16`; la sonda fija de la casa (`24/7`) sigue sin tabla. `CONTINUE`
funciona tras reinstalar y el juego sigue en ruta inicial. Evidencia:
`tools/poco-capture/20261027-b2-slice2/`. Sin spawn.

### Slice 3 — Disparo fiel por paso real

**Objetivo:** al completar un paso real en `GPS_REAL`, ejecutar la rutina de encuentro
del propio juego si el mapa actual tiene tabla.

**Archivos previstos**
- `app/src/main/cpp/core/cpu.{h,cpp}`: helper de llamada controlada (salvar/restaurar
  registros, pila y PC; detectar retorno).
- `app/src/main/cpp/core/wild_table.{h,cpp}`: localizar `TryWildEncounter`,
  `CallScript` y `WildBattleScript` por firma.
- `app/src/main/cpp/jni/native_bridge.cpp`: `nativeTryWildEncounter()`.
- `app/src/main/java/com/gpsquest96/emulator/GameEngine.java`: puerta de estado y
  cooldown.
- `app/src/main/java/com/gpsquest96/ui/MainActivity.java`: llamada desde el paso real
  validado (misma puerta del podómetro), solo `GPS_REAL`.
- `tools/host-test/test_wildtable.cpp`: pruebas de localización de rutinas.

**Tareas**
1. Localizar las rutinas por firma de instrucciones, no por offset fijo.
2. Implementar la llamada controlada al CPU con timeout y restauración garantizada.
3. Puerta de estado: overworld, sin batalla/texto/menú; si no, no disparar.
4. Cooldown propio + tregua de 180 s; respetar el cooldown del juego.
5. Enlazar con el paso real del podómetro; no en `SOFA`.
6. Probar en POCO en ruta inicial: especie/nivel dentro de la tabla; sin encuentro en
   Ciudad Hoja/interior.
7. Regresión: `CONTINUE`, `.sav`, CGB color y Modo Sofá.

**Criterio de salida:** gate POCO de B2 con evidencia de al menos un encuentro real
cuya especie/nivel esté en la tabla de ruta inicial.

### Slice 4 — Cierre documental y gate

- Actualizar `memory/archivo/tasks-2026-11.md`, `memory/archivo/estado_actual-2026-11.md`, `memory/archivo/bitacora-historico.md` y
  `memory/archivo/traspaso-2026-11.md`.
- Guardar capturas/logs en `tools/poco-capture/`.
- Declarar B2 cerrado solo con el gate POCO del slice 3.

## Dependencias

- 007 (CGB) y 008 (guardado) ya están verdes en lo esencial.
- 005 (gating) no bloquea este B2; se mantiene congelado.
- 004 (mapeo) no se toca.
- La partida de prueba necesita un starter para salir de Ciudad Hoja; se prepara con
  el usuario antes del gate de ruta inicial.

## Riesgos de ejecución

- **No** escribir a `0xD000` ni a `wTempWildMonSpecies` a mano.
- **No** hardcodear `0x2A5F0`; el parser debe validar.
- Si la rutina del juego no se localiza, el slice 1-2 sigue siendo válido y el slice 3
  se bloquea con evidencia, no se simula.
