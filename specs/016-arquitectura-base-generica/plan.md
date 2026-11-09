# plan.md — 016 Arquitectura base genérica

Estado: APROBADO (una vez la spec sea aprobada). Spec: `specs/016-arquitectura-base-generica/spec.md`.

Regla: ningún slice de código se ejecuta hasta que la spec esté APROBADA.

## Orden de slices

### Slice 1 — Eliminar código específico de los datos del juego original del usuario

**Objetivo:** Quitar todo código que hardcodea direcciones del juego original del usuario, para que el emulador sea agnóstico.

**Archivos previstos**
- `app/src/main/cpp/core/cpu.h`: remover `callRoutine()`.
- `app/src/main/cpp/core/cpu.cpp`: remover implementación de `callRoutine()`.
- `app/src/main/cpp/jni/native_bridge.h`: remover declaración de `nativeTryWildEncounter`.
- `app/src/main/cpp/jni/native_bridge.cpp`: remover cuerpo de `nativeTryWildEncounter` y `#define LOGW` si solo lo usaba esa función.

**Tareas**
1. Remover `callRoutine` de `cpu.h`.
2. Remover `callRoutine` de `cpu.cpp`.
3. Remover declaración JNI de `nativeTryWildEncounter` de `native_bridge.h`.
4. Remover implementación de `nativeTryWildEncounter` de `native_bridge.cpp`.
5. Build correcto: `./gradlew assembleDebug --offline`.

**Criterio de salida:** Build correcto y cero referencias a offsets de esa partida en el código fuente.

---

### Slice 2 — Máquina de estados del emulador en C++

**Objetivo:** Detectar el estado actual del emulador (`BOOT`, `INTRO`, `MAP`, `BATTLE`, `MENU`, `PAUSE`) usando registros de hardware genéricos del modo retro de 8 bits.

**Archivos previstos**
- `app/src/main/cpp/core/cpu.h`: agregar `EmulatorState` enum y `getEmulatorState()`.
- `app/src/main/cpp/core/cpu.cpp`: implementar detección de estado.
- `app/src/main/cpp/core/ppu.h`: exponer registros relevantes si faltan.
- `app/src/main/cpp/core/ppu.cpp`: lógica de transiciones de estado en VBlank.

**Tareas**
1. Definir enum `EmulatorState` en `cpu.h`.
2. Agregar `getEmulatorState()` a `Cpu`.
3. Implementar detección de transiciones basada en LCDC, STAT, SCX, SCY, WY, WX.
4. Build correcto.

**Criterio de salida:** El emulador reporta su estado actual sin leer direcciones de WRAM de una partida específica.

---

### Slice 3 — API JNI y Java para estado genérico

**Objetivo:** Exponer el estado del emulador a Java y notificar cambios a la UI.

**Archivos previstos**
- `app/src/main/cpp/jni/native_bridge.h`: declarar `nativeGetEmulatorState`.
- `app/src/main/cpp/jni/native_bridge.cpp`: implementar `nativeGetEmulatorState`.
- `app/src/main/java/com/gpsquest96/emulator/EmulatorBridge.java`: wrapper Java + `nativeGetEmulatorState()`.
- `app/src/main/java/com/gpsquest96/emulator/GameEngine.java`: enum `EmulatorState`, listener `onEmulatorStateChanged`, campo `currentState`.

**Tareas**
1. Agregar JNI `nativeGetEmulatorState()`.
2. Agregar wrapper Java en `EmulatorBridge`.
3. Agregar enum `EmulatorState` en `GameEngine`.
4. Agregar `onEmulatorStateChanged()` al `GameEngineListener`.
5. Build correcto.

**Criterio de salida:** Java puede consultar el estado del emulador y recibir notificaciones de cambio.

---

### Slice 4 — UI reacciona a cambios de estado

**Objetivo:** `MainActivity` cambia automáticamente de vista según el estado del emulador.

**Archivos previstos**
- `app/src/main/java/com/gpsquest96/ui/MainActivity.java`: escuchar `onEmulatorStateChanged`, llamar `applyViewMode(true/false)`.

**Tareas**
1. Implementar `onEmulatorStateChanged()` en el listener de `MainActivity`.
2. `BATTLE` → `applyViewMode(true)` (pantalla completa de combate).
3. `MAP` → `applyViewMode(false)` (vuelve al mapa real).
4. `INTRO`/`BOOT` → no abrir mapa todavía.
5. Build correcto.

**Criterio de salida:** La UI cambia de vista automáticamente sin intervención del usuario.

---

## Dependencias
- Spec 001 (núcleo del modo a color) — borrador, pero el core existe y compila.
- Spec 011 (render nativo estable) — aprobado, doble búfer ya existe.

## Riesgos de ejecución
| Riesgo | Mitigación |
|--------|------------|
| Remover `callRoutine` rompe la compilación si algo lo usa | Verificar que solo `native_bridge.cpp` lo referenciaba (ya se removió en Slice 1). |
| Detección de estado por hardware es imperfecta | Empezar con detección básica; refinar en slices posteriores. |

## Regla operativa
Un slice por turno, build correcto después de cada slice.
