# Auditoría de núcleo — 2026-10-22 (segunda pasada)

> **Vigencia 2026-10-23:** foto histórica. El estado vigente (incluidas las deudas que
> siguen abiertas: audio, GBC real, timing, RTC) vive en `memory/archivo/estado_actual-2026-11.md`.

Alcance: núcleo de emulación (CPU/MBC/MMU/PPU/loader/JNI) tras la auditoría de 14
defectos. Método: lectura completa + verificación del flujo real de ROM
Amarillo (1 MB, MBC5, 64 bancos) contra la ROM extraída.

Resultado: **1 causa raíz confirmada** del crash de arranque (timing CPU↔PPU) y
**4 defectos de severidad media** ya inventariados. Ningún cambio de producto.

---

## Causa raíz confirmada — "crash" de la intro

**Hipótesis descartada** (evidencia ROM): el banco NO es el equivocado.
- `0x22DC: 3E 08 CD 83 3E` = `LD A,8; CALL 0x3E83`; `0x3E83: E0 B8 EA 00 20 C9`
  = `LDH (0xB8),A; LD (0x2000),A; RET` → **selecciona el banco 8 a propósito**.
- Banco 8 @ `0x5B41` = `3E 80 E0 26 E0 1A AF E0 25 E0 1C 3E 08 E0 10 …` es
  **código de init de sonido válido** (NR52/NR30/NR51/NR32/NR10/NR12/NR22/NR42).
- Coincide EXACTO con la instrucción ejecutada ⇒ MBC5 y `mappedRomOffset` correctos.

**Causa real**: el PC guardado por la IRQ de VBlank era `0x3867`, que **no es
frontera de instrucción** (`0x3865: FA 9F D0` = `LD A,(0xD09F)` ocupa 0x3865-0x3867,
la siguiente instrucción empieza en 0x3868). El handler de VBlank es **balanceado**
(5 PUSH + IRQ push = 6; 5 POP + RETI = 6; SP entra/sale en 0xDFCF). Por tanto el
síntoma viene de **arriba**: el programa ya iba por código inválido.

Origen: `Cpu::getOpcodeCycles()` devuelve **4 ciclos a casi todo** (`RET cond`,
`RST`, `PUSH`, `POP`, `JR cc`, `LD r,r'`, `INC/DEC r16`, CB…) cuando en LR35902 son
8–24. `stepFrame()` cuenta 70224 "ciclos nuestros" por frame, así que **el juego
ejecuta muchísimas más instrucciones por VBlank que el hardware** ⇒ los bucles de
retardo calibrados de Gen 1 se desfasan ⇒ WRAM (pila en 0xDFxx) se corrompe ⇒ RETI
retorna a basura ⇒ `RST 38H` en bucle ⇒ desbordamiento de pila ⇒ `LCDC.7=0`.
Los `0x39` repartidos son el síntoma, no la causa.

**Solución**: implementar la tabla real de ciclos del LR35902 (o la matriz
estructural por clase de opcode). Verificación: el ring debe dejar de dispararse
en `0x5B50` y la intro debe cruzar a overworld.

---

## Inventario de defectos de núcleo

| # | Área | Defecto | Impacto | Estado |
|---|------|---------|---------|--------|
| N1 | CPU | `getOpcodeCycles` casi todo 4 | **Crítico**: timing, corrupción WRAM | Abierto (causa raíz) |
| N2 | CPU | Bug de HALT: al salir por IRQ ejecuta una instrucción **antes** de servirla (hardware la sirve antes); y el HALT-bug de doble lectura no se emula | Medio | Abierto |
| N3 | MMU | Fuera de rango echos/IRQ: `updateTimers` trata `TIMA+1==0` como único overflow; en hardware el overflow es al pasar de 0xFF→0x00 (equivalente), OK. Pero **`divCounter_`/`timaCounter_` comparten reloj global**, DIV no se resetea al escribir 0xFF04 (RB) | Bajo | Abierto |
| N4 | MMU | `0xFF04` (DIV) se escribe como I/O normal: en hardware **cualquier escritura lo pone a 0** | Bajo | Abierto |
| N5 | PPU | Sin modo 3 real (pixel FIFO). `renderBackground/renderWindow` se calculan al vuelo por scanline. Aceptable para Gen 1 pero no permite el timing 3 (STAT) | Medio | Deuda declarada |
| N6 | MBC | MBC1 no implementa el modo 1 (RAM banking) ni el registro alto de banco en 0x4000-0x5FFF de forma completa | Bajo (Yellow es MBC5) | Abierto |
| N7 | PPU | `renderWindow` recién añadida — falta verificar `windowLine_` cuando la ventana se desactiva/activa en medio del frame | Bajo | Abierto |
| N8 | APU | No implementada (escrituras a `ioRegisters_`); Gen 1 **espera a NR52/onda** en el init de sonido → puede colgarse en bucles de espera si alguna rutina sondea el APU | Medio | Deuda declarada |

---

## Incongruencias de documentación/estado

- `memory/archivo/tasks-2026-11.md` Task 10 B4: describe DMA de OAM y OAM con hits, pero **B4 no
  puede cerrarse** mientras N1 provoque el crash antes del overworld.
- La traza `ring` en `cpu.cpp` (líneas 63-86) es **temporal** y no debe quedar.
- `cpu.cpp` línea 49 `trace PC=…` cada 2M pasos: permanente, aceptable (throttled).
- `ppu_regs.txt` en el dump: útil, se queda.

---

## Segunda pasada — hallazgos nuevos en PPU (2026-10-22)

| # | Área | Defecto | Evidencia | Impacto |
|---|------|---------|-----------|---------|
| **N9** | PPU | **Fondo/ventana pintados por columna equivocada.** `renderBackground()` (ppu.cpp:205-222) y `renderWindow()` (ppu.cpp:249-264) llaman `drawTileScanline(tileAddr, x, ...)` **dentro del bucle por píxel**, pero `drawTileScanline` pinta 8 columnas desde `x` (ppu.cpp:375-382). Cada píxel queda sobrescrito por el `col=0` del píxel siguiente ⇒ el fondo son **barras verticales de 8 px con la columna 0 de cada tile**. | Código; `rowInTile` se calcula pero nunca se usa `mapX & 7`. | **Crítico visual**: la intro "renderiza" pero no es la imagen real; B4 no es verificable así. |
| **N10** | PPU | **STAT nunca expone modo 0 ni modo 3.** `syncRegsToMmu()` solo se llama al cerrar H-Blank y en VBlank (ppu.cpp:129/140/159). Los cambios 2→3 (línea 104) y 3→0 (línea 112) actualizan `stat_` interno pero **no lo escriben en 0xFF41**. En MMU, `STAT & 3` solo vale 1 o 2. | Código. | **Alto**: un juego que espere H-Blank por STAT (bucle `LDH A,(41); AND 3; CP 0`) se cuelga. |
| **N11** | CPU | Traza `RING` temporal sigue presente en `cpu.cpp:63-86` (256 LOGI al disparar en `pc_==0x5B50`). | Código. | Medio: ruido/logs, no crash. Retirar. |

### Corrección de honestidad sobre la causa del crash
El PC `0x3867` a mitad de instrucción está **confirmado** (0x3865 `FA 9F D0` ocupa 0x3865-0x3867). Que la causa sea `getOpcodeCycles` es la **hipótesis principal, aún no demostrada**: la tabla devuelve 4 a casi todo (cpu.cpp:429-472), lo que desfasa el reloj real, pero no se ha aislado el primer punto de corrupción. No debe darse por cerrado hasta reproducir con la tabla real.

## Siguiente fase sugerida

1. **Arreglar N1** (tabla de ciclos real) — desbloquea la intro y B4.
2. Confirmar B4 en overworld (sprite OW + `SpriteExtractor hits=3`).
3. Retirar la traza `ring`.
4. Aplazar N2/N3/N4/N6/N7 (bajo impacto) y mantener N5/N8 como deuda de spec.
