# plan.md — 007 CGB real

Spec: `specs/007-gbc-color/spec.md` (APROBADA 2026-10-25).
Regla de ejecución: un slice verificable por turno; build verde; si rompe, se repara ese
archivo antes de seguir.

## Slice 1 — Fundación CGB (HECHO 2026-10-25)

**Objetivo:** que el core sepa que la ROM es CGB y tenga la memoria/registros base, sin
cambiar el render DMG.

Archivos:
- `core/rom_loader.h|cpp`: leer `0x143`, `cgbFlag()`, `isCgbCapable()`, `isCgbOnly()`, log.
- `core/mmu.h|cpp`:
  - `vram_[0x4000]` (2 bancos), `ram_[0x8000]` (8 bancos de 4 KB).
  - `readVramBank/writeVramBank`, `readWram/writeWram`.
  - `setCgbMode`, `isCgbMode`, `isDoubleSpeed`, `trySpeedSwitch`.
  - Registros `KEY1` (`FF4D`), `VBK` (`FF4F`), `SVBK` (`FF70`), `BCPS/BCPD` (`FF68/69`),
    `OCPS/OCPD` (`FF6A/6B`), `OPRI` (`FF6C`), `HDMA1-5` (`FF51-55`).
  - `startHdma`: copia síncrona de `blocks*16` bytes.
- `core/cpu.h|cpp`: `toPpuCycles()` (mitad en doble velocidad), `STOP` completa el cambio,
  `A=0x11` en CGB.
- `core/ppu.cpp`: `readVram` fuerza banco 0 (los tiles nunca salen del banco de atributos).
- `jni/native_bridge.cpp`: fijar CGB antes de `Cpu::initialize()`, enlazar MMU/PPU primero,
  `stepFrame` con 140448 ciclos en doble velocidad, reset a DMG al cerrar ROM.
- `tools/host-test/`: arnés de host con stub de `android/log.h` (hoy 30 checks).

Verificación:
- `tools\host-test\run.ps1` → `OK: 30 checks`.
- `.\gradlew assembleDebug --offline` → `BUILD SUCCESSFUL`.

Evidencia:
- Arnés: DMG sin alias, SVBK 1/3/0, VBK 0/1, KEY1+STOP, paletas con auto-incremento,
  GDMA de 1 bloque, color RGB15, vuelta a DMG.

Riesgo residual: el arnés prueba la lógica de CPU/PPU con ROM/VRAM sintéticas, pero no ejecuta
Gen 2 ni GLES2; el gate real sigue siendo POCO.

## Slice 2 — Render CGB (HECHO 2026-10-25 · falta POCO)

**Objetivo:** que Gen 2 se vea en color.

Implementado:
- `core/ppu.h|cpp`:
  - `drawCgbBgPixel`: índice del tile en banco 0, atributo en banco 1; aplica paleta 0-7,
    flipX (bit 5), flipY (bit 6), banco de tile (bit 3) y prioridad (bit 7).
  - `renderBackground`/`renderWindow` usan esa ruta cuando `mmu_->isCgbMode()`.
  - `getCgbColor`: paletas BG/OBJ de 64 B, sin caché (se lee directo; 1,3 M px/s lo absorbe).
  - `renderSprites`: paleta OAM 0-7, banco de tile, `OPRI` (prioridad por X vs índice OAM) y
    regla CGB de “detrás del BG” (`bgColorIndex_` + `bgPriority_` por píxel).
  - `LCDC.0`: en CGB el BG siempre se dibuja; en DMG sigue apagándolo.
- `core/cgb_palette.h`: `cgbRgb15ToRgba` (RGB15 → RGBA, R en byte bajo). Header puro para
  poder testearlo sin Android.
- `core/mmu.h`: `getOpri()`.

Verificación:
- `assembleDebug` verde.
- Arnés de host: `test_mmu` 30/30 (5 de conversión RGB15) + `test_cgb_ppu` **26/26** (paletas
  BG/OBJ, atributo de banco 1, banco de tile, flips, prioridad BG↔OBJ, `OPRI`, ventana,
  `LCDC.0` CGB, sprite transparente, STAT modo 0/1/2 y LYC por flanco).
- POCO: **pendiente** (Gen 2 intro/overworld en color; Azul idéntico).

Criterio de salida: captura de Gen 2 en color + captura de Azul DMG sin regresión.

## Slice 3 — Timing fino (PARCIAL HECHO 2026-10-25 · HDMA HBlank pendiente)

**Objetivo:** que los juegos que dependen de HDMA/IRQ no se desincronicen.

- ✅ `DIV` en doble velocidad: `Cpu::step()`/`checkInterrupts()` pasan el ciclo completo a
  `Mmu::updateTimers()`; antes usaban `toPpuCycles()` y DIV/TIMA corrían a mitad. `STOP`
  resetea `DIV`. Evidencia: `test_lr35902` 20/20 (4 NOPs → TIMA+1; 64 NOPs → DIV+1).
- ✅ `STAT/LYC` en CGB: IRQ por entrada en modo 0/1/2 y por coincidencia LYC, con flanco para
  no repetir en 2→3→0. Evidencia: `test_cgb_ppu` 26/26.
- ⏳ HDMA troceado por HBlank (16 B por línea), `FF55` con remaining real e IRQ de HBlank.
  El GDMA sigue siendo síncrono; solo se implementa si el gate POCO lo exige.

## Slice 4 — Gate POCO y cierre

- Instalar APK, abrir Gen 2: log `CGB ON`, intro en color, overworld, mover joystick.
- Abrir Azul: regresión DMG (título, profesor, cuarto, overworld).
- Fotos en `out/poco/` y línea en `memory/archivo/bitacora-historico.md`.
- Actualizar `memory/archivo/estado_actual-2026-11.md` §4 (GBC real cerrado) y `memory/archivo/tasks-2026-11.md` Task 17.

## Orden de dependencias

```
Slice 1 (hecho) → Slice 2 (hecho) → Slice 3 parcial (DIV/STAT, hecho)
  → Slice 4 (gate POCO) → HDMA HBlank (solo si el gate falla)
```

Después de 007: **008 guardado real + `.pueblo`** y **009 B2 spawns**. No se adelanta
post-game (fases 2-8) ni GBA.
