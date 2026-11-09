# Plan 013 — Doble velocidad del modo a color (paridad con la BIOS)

## Slice 1 — Estado post-boot a 2× + timers en SYSCLK (2 archivos core, 1 host-test)

**Alcance mínimo verificable.** Un punto en el tiempo: `loadRom` (o el reset de Mmu) y el
paso de timers. Nada de PPU/render.

- `app/src/main/cpp/core/mmu.cpp` (+/ mmu.h):
  - En el reset/carga (vía que hoy pone `doubleSpeed_ = false`, vbk_=0, svbk_=1):
    si la bandera de modo a color del header (0x80 o 0xC0 en 0x0143) está activa, arrancar
    con `doubleSpeed_ = true` (paridad con el estado post-boot de la BIOS del modo a
    color). El modo retro de 8 bits no cambia.
    - SI el juego ejecuta su propio KEY1+STOP, el switch togglea como hoy (a 1× de
      vuelta: el hardware también lo permite).
- `app/src/main/cpp/core/cpu.cpp`:
  - `Cpu::step()`: `mmu_->updateTimers(costo-reducido)` (a 2×: `cycles >> 1` = ticks de
    SYSCLK; a 1×: `cycles` tal cual) — hardware: DIV/TIMA no se aceleran.
  - `Cpu::checkInterrupts()`: el `updateTimers(20)` pasa al mismo reducridor.
  - `stepFrame` (native_bridge.cpp): ya usa 140448 a 2× — sin cambio.
- `tools/host-test/` — test del arnés:
  - Verificar qué suites cuentan ciclos a 2× (test_lr35902 STOP/DIV/TIMA) y ajustar
    expectativas si el rediseño de timers las cambia (documentado en la bitácora).
  - Checks nuevos: loadRom con modo a color ⇒ KEY1 bit 7 = 1 y DIV cadencia correcta a 2×
    (DIV+1 cada 256 SYSCLK, no cada 128).

**Verificación:** arnés completo correcto (C++ + Java) + `assembleDebug --offline` +
dispositivo de prueba: dump KEY1=0xFE, log EMU con cycles/frame ~140448, caminata
perceptible a velocidad real, sin regresión de batalla/menús (011). Evidencia en la
carpeta de capturas del dispositivo correspondiente a
`20261028-spec013-doblevelocidad/` (bajo `tools/`).

**Riesgos / rollback:**
- ZIP backup de los 3 archivos antes de tocar (patrón de siempre).
- Si el juego del usuario a 2× muestra problemas nuevos de timing (probable: algunos efectos del
  overworld dependen de ese timing), el revert es restaurar los 3 archivos del ZIP.
- Cambios de conteo en el arnés se justifican línea a línea en la bitácora (nada a ciegas).
- La velocidad del mundo percibida será ~2× la actual: es el objetivo (paridad hardware),
  pero el usuario debe saberlo ANTES del gate (percepción nueva ≠ régimen previo).

## Slices 2+ (candidatos, NO en este plan)

- Acid tests (blargg acid2 para modo a color y para modo retro de 8 bits) en el arnés como red de regresión PPU —
  evaluación aparte (requiere comparación PNG o diff de bancos).
- Si tras el 2× persisten defectos del overworld, spec 014 "integridad de frame overworld"
  con dump_now + análisis de VRAM bancos (ya probado el pipeline de datos).
