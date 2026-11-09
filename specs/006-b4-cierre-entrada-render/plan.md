# plan.md — 006 Cierre de B4: entrada, instrumentación y render fiel

Estado: **APROBADO** 2026-10-23 (spec aprobada por delegación explícita del usuario).
Ejecución por slices; un cambio verificable por turno y `assembleDebug` verde tras cada uno.

## Orden de slices

### Slice 1 — Retirar instrumentación temporal del core
- **Archivo:** `app/src/main/cpp/core/cpu.cpp`.
- **Qué se va:** histograma `PChot` (+ array `pcHist[65536]` y su escaneo), `trace PC=` cada
  2 M instrucciones y la inyección WRAM por `romBank==63 && PC∈{0x5A9A,0x5AB4}`.
- **Qué se queda:** `halted_`, `hasPendingInterrupt()`, `checkInterrupts()` y el orden
  hardware (IRQ antes de ejecutar otra instrucción tras HALT).
- **Verificación:** build verde; `Select-String cpu.cpp -Pattern 'PChot|pcHist|hJoyHeld|currentRomBank\(\) == 63'` vacío;
  ciclos/s medidos en POCO con log de la app (objetivo ≥ 3.5 M).
- **Riesgo esperado:** Verde puede volver a bloquearse en el prompt. Es la señal que
  necesitamos para atacar la causa raíz en el slice 2, no un retroceso.

### Slice 2 — Entrada fiel en prompts
- **Archivos previstos:** `cpu.cpp` (si el hueco es de HALT/IRQ) y/o `mmu.cpp` (`0xFF00`, IRQ de joypad).
- **Método:** instrumentación mínima y temporal *con propósito de diagnóstico*, apagada al
  cerrar el slice: log de lecturas de `0xFF00` y de despachos de IRQ (vector + IF/IE) durante
  el prompt, en una sola ROM, para decidir con evidencia.
- **Criterio de salida:** el prompt de profesor avanza en Azul y Verde sin condiciones por ROM.

### Slice 3 — Render fiel de sprites y ventana
- **Archivos previstos:** `ppu.cpp` (`renderSprites`, `renderScanline`, ventana).
- **Método:** comparar OAM/VRAM reales (`out/poco/ppu_oam.bin`, `ppu_vram.bin`) contra lo
  pintado; corregir sin romper prioridad DMG (A1/A2 de la auditoría del 22).
- **Criterio de salida:** cursor de menú visible, sin rayas, texto dentro del cuadro.

### Gate final — POCO
- Overworld en Azul y Verde, joystick movido, `SpriteExtractor hits=3` del jugador + foto.
- Evidencia a `out/poco/`; entrada en `memory/archivo/bitacora-historico.md`; `memory/archivo/estado_actual-2026-11.md` §2/§3 al día.

## Reglas de ejecución
- Nada fuera de `app/src/main/cpp/**` salvo que el slice lo justifique (se anota en la bitácora).
- Prohibido: `0xD000`, offsets inventados, tablas por juego, tocar B2/004/005/post-game.
- Si un slice rompe el build: se repara solo ese archivo antes de seguir.
