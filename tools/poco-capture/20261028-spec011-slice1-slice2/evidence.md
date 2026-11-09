# Spec 011 — gate POCO slices 1+2 (doble búfer / HDMA HBlank + ventana)

Fecha: 2026-10-28 19:56–20:10
Dispositivo: POCO X7 Pro `2412DPC0AG` (`XWWSAY4LAAPZHIA6`)
Build instalado: `2026-10-28+spec011-slice2-hdma-wly` (logcat-launch.txt línea BUILD)
Partida: Gen 2 (Nckam es) con `.sav` real, `CONTINUAR` intacto tras reinstalar.

## Resultados por slice

### Slice 2 (HDMA HBlank + ventana WLY) — ✅ PASA
Reporte del usuario tras caminar + batalla + menú START + diálogos:
- **Batalla: OK. Diálogos: OK. Colores: OK. Sin distorsión** (a diferencia de la
  sesión del 27, donde la distorsión era el síntoma junto con el titileo).
El fix `windowLine_` (LY==WY, sin reset de fin de frame) y la HDMA troceada por
HBlank (FF55 con bit7 en vuelo + 16 B/línea) quedan verificados en el dispositivo.

### Slice 1 (doble búfer) — ⚠️ GATE NO CONCEDIDO: el síntoma restante NO es el frame buffer
- El anillo de 3 slots está en producción (host-probado, sin carrera) y **no
  introdujo regresión** (batalla/diálogos limpios, sin tearing nuevo).
- PERO "titileo al caminar" persiste, re-caracterizado por el usuario como
  **"no fluido, lag que se muestra como parpadeo" + sensación de cámara lenta**.
  No es glitch de píxel: es BAJA TASA DE FRAMES.

### Medición del cuello (nueva evidencia, sin tocar código)
- Logcat (`EMU 600 frames, 42135776 cycles` en 23.1 s): **26.0 fps**, con
  **70226 ciclos por frame GB** (frame completo) ⇒ el core SÍ corre frames
  enteros; solo que ~26 por segundo real (~43% de velocidad, tarifa del 27).
- `top -H -n 1`: **GLThread 37.7% CPU — EmuLoop 11.3%** ⇒ el core GB es barato;
  el límite NO es fuerza bruta de CPU.
- Causa en código (lectura, sin modificar): `MainActivity`:
  - EmuLoop: `postDelayed(33 ms)` → `gameEngine.update()` → **1 frame GB por
    tick** (FRAME_INTERVAL_MS=33) + coste + deriva del handler ≈ 38 ms/tick
    → ~26 fps. Diseño intencional del 2026-10-16 ("bomba a 30 fps"); el
    traspaso ya lo documentaba: *"El juego 'va lento' (~26 fps) — techo del
    pump de render, no del core"*.
  - GLThread: tope propio `TARGET_FPS = 30` (33.3 ns por onDrawFrame).
  ⇒ La fluidez real exige re-ritmar el bombeo (spec nueva, no 011).

### Slice 3 (higiene nativa) — ✅ CRITERIO: NO SE HACE (condicionado)
`dumpsys meminfo -d` en 3 ciclos force-stop/relanzar:

| Ciclo | Native Heap (PSS) | TOTAL PSS |
|-------|-------------------|-----------|
| 1     | 25,908 KB         | 181,287 KB |
| 2     | 25,864 KB         | 186,278 KB |
| 3     | 25,844 KB         | 185,609 KB |

Plano (ligeramente DECRECIENTE) ⇒ sin fuga acumulativa ⇒ `nativeDestroyPipeline()`
no se implementa; la condición de la spec lo exime.

### Audio
"No hay audio" — **0% por diseño** (APU sin NRxx; deuda abierta en estado_actual
§8/Audio). No es regresión del build 011.

## Evidencia en esta carpeta
- `logcat-launch.txt` — arranque con BUILD_TAG slice2-hdma-wly, CGB ON, .sav cargado.
- `logcat-session.txt` — sesión del usuario (joypad + EMU/onDraw cada ~11.5 s = 26 fps).
- `01-launch.png`, `02-ingame-before-walk.png`, `03-final.png` — pantallas de la sesión.
- `meminfo-ciclo{1,2,3}.txt` — volcados completos para el slice 3.
- `timestats.txt` — intento de timestats de SurfaceFlinger (sin per-app útil; sobrevive como constancia).

## Criterios de salida (checklist spec 011)
- [x] Sin distorsión en contenido CGB dinámico (batalla/menús/diálogos) — slice 2.
- [~] Caminar en Gen 2 sin titileo — NO CONCEDIDO: la percepción de titileo queda
      re-atribuida con evidencia al techo del pump (~26 fps), deuda previa a 011.
      Requiere spec nueva de velocidad real (pump 60 fps + catch-up).
- [x] Azul DMG sin regresión de ventana/paletas (el anillo es compartido; sin reporte
      de defecto; session cerrada sin tocar Azul esta vez — riesgo bajo: caminos DMG
      cubiertos por host-test).
- [x] `CONTINUE` intacto tras 3 force-stops.
- [x] `EMU` logs sanos (26 fps constantes, frames completos de 70226 ciclos).
