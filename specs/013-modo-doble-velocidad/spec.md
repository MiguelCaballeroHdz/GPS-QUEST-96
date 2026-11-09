# Spec 013 — Doble velocidad del modo a color (paridad con la BIOS al volver de boot)

## Problema

El core arranca `Cpu` en `PC=0x0100` (cpu.cpp 35) — **no ejecuta la BIOS del modo a
color**, que es quien bascularía la CPU a 2× (KEY1 bit 7) para los juegos del modo a
color antes de entregar a 0x0100. El juego del usuario nunca cambia el modo por su
cuenta (evidencia `KEY1=0x7E`, sin bit 0 armado, y ausencia del aviso de doble velocidad
del modo a color en 3 sesiones de logcat). Resultado:

- El juego recibe LA MITAD del presupuesto de CPU por VBlank que espera → mundo
  sub-real (cámara lenta persistente = la otra mitad de "velocidad real", la que el
  catch-up de spec 012 no puede dar), y desfase de timing LCD (datos del usuario en
  el vídeo walk012c: tiles corruptos/repetidos, árboles negros abajo, rastro sin
  limpiar, transición a batalla en blanco).
- En hardware real, post-boot, KEY1 lee `0xFE` (bit 7=1: doble velocidad).

## Requisitos funcionales

- **RF-01 (estado post-boot):** al cargar los datos del juego original del usuario con
  bandera de modo a color (0x80/0xC0 en 0x0143), el estado de la Mmu debe salir como el
  hardware tras el boot: `doubleSpeed_ = true` (KEY1 bit 7 = 1). El modo retro de 8 bits
  (0x00) lo deja en false.
- **RF-02 (timers en el SYSCLK, hardware-correcto):** a doble velocidad, los timers
  (DIV/TIMA/TMA/TAC) NO se aceleran: `updateTimers` debe recibir el costo reducido
  (ticks del SYSCLK, = costo CPU >> 1) tanto desde `Cpu::step()` como desde
  `checkInterrupts()`. A velocidad simple el comportamiento actual es correcto.
- **RF-03 (presupuesto del frame):** `stepFrame` usa 140448 ciclos-CPU a 2× y 70224 a 1×
  (ya implementado; no se toca) — la PPU completa 70224 dots por stepFrame en ambos
  modos. El contador `EMU` a 2× reflejará ~140448 cycles/frame: es el valor hardware.
- **RF-04 (safespace .sav):** el cambio de estado de velocidad no puede suceder a mitad
  de partida cargada del .sav: se aplica al CARGAR los datos del juego original del
  usuario (mismo punto en el que hoy se resetea vbk_/svbk_/doubleSpeed_ en el reset de
  Mmu). Un force-stop→CONTINUE entra por la misma vía (sin discrepancia de estado).
- **RF-05 (regresión 011):** la PPU, HDMA, ventana y atributos del modo a color no
  cambian; los 143 checks del arnés deben seguir correctos (con ajustes menores
  documentados en el plan si los tests a 2× cuentan ciclos).

## Criterios de aceptación

- Host: arnés C++ (test_lr35902 tiene cobertura STOP/DIV/TIMA a doble velocidad)
  correcto + checks nuevos: tras `loadRom` (modo a color) KEY1 bit 7 = 1; DIV avanza a
  16384 Hz-equivalente (256 SYSCLK por incremento hinter) a 2×; `assembleDebug --offline`
  OK.
- Dispositivo de prueba: con el juego del usuario cargado, `dump_now` → `KEY1=0xFE` (o
  0xFF según bit armado inexistente: bit 7=1); caminata: percepción de velocidad **igual
  a un dispositivo de modo a color real** (comparable por el usuario contra sus recuerdos
  del teléfono o el emulador de referencia), log `EMU` ≥55 fps; sin regresión de
  batalla/menús/diálogos (los defectos
  del vídeo walk012c se re-evalúan como cámara independiente: el overworld corrupto
  puede mejorarse aquí, pero el candidato real es el 014 de la integridad del frame;
  quedará capturado con el mismo vídeo/dump machinery).

## Anti-metas

- No implementar audio/APU, ni 004/005.
- No tocar la PPU ni el render del modo a color (spec 011 cerrada; cualquier defecto
  residual de render se residencia en su propia spec con dump_now como evidencia).
- No introducir catch-up adicional ni aceleraciones >1× (el objetivo es PARIDAD
  hardware, no turbo).
- No recrear `rules/` ni duplicar PLAN.md.
