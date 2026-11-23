# Diagnóstico del núcleo — spec 020 (pulir la emulación)

Fecha: 2026-11-23. Sesión lun 23 nov 06:00 (la del dom 22 nov se saltó por la
pausa de tokens; se reanuda aquí).
Base: `004-slice-5` (commit local `e6dcdb9`; rama `020-diag-nucleo`).
Alcance: CPU LR35902, PPU, APU, timing, frame pacing, latencia de audio y
video. Sin tocar el juego ni el MVP: solo medir y documentar.

## Método

1. Lectura del núcleo C++ (`core/cpu.cpp`, `core/ppu.cpp`, `core/apu.cpp`,
   `core/mmu.cpp`, `jni/native_bridge.cpp`) y del lado Java
   (`GameEngine.java`, `EmulatorBridge.java`, `MainActivity.java`,
   `EmulationPipeline.java`).
2. Host tests nuevos, en Linux con `g++` (`tools/host-test/run.sh`):
   `test_apu.cpp` (35 comprobaciones) y `test_timing.cpp` (19).
   Suite C++ completa: 180 comprobaciones, 0 fallos
   (mmu 34, sav 18, cgb_ppu 51, lr35902 23, apu 35, timing 19).
   `test_wildtable` se salta en esta base (no existe `wild_routine.cpp`
   sin la 009 slice 3; vive en el PR #4). Los host tests Java no corren en
   esta base (faltan 009 slice 3 y 004 slice 6b, en sus PRs).
3. Medición del audio del video del gate POCO del 20 nov
   (`~/workspace/user/files/1000392126_0_4fe5.mp4`, 6:47, AAC mono 44,1 kHz):
   `ffmpeg silencedetect` y `volumedetect`.

## Resumen ejecutivo

El núcleo está bien en lo grueso y roto en el audio. Hay UN bug gordo que
explica casi todo lo que Miguel escucha mal, y dos defectos de diseño en el
camino Java que explican los cortes y el "render a medias".

1. **La APU lee sus registros con +0x10 (D-APU-1).** `write()` guarda cada
   registro en `regs_[address - 0xFF10]`, pero la lógica (length, envelope,
   sweep, duty, volumen, DAC) los lee en `regs_[0x10..0x24]`, es decir, el
   registro de 0x10 más arriba. Medido con host tests: **el canal 3 (wave)
   y el canal 4 (ruido) son MUDOS siempre**, y los canales 1 y 2 suenan con
   el tono, el volumen y el envelope del canal vecino. La música pierde la
   mitad de sus voces y el resto suena desafinado. Es un fix mecánico
   (reindexar), no un rediseño.
2. **El audio es esclavo de la velocidad de emulación (D-AUD-1).** La única
   fuente de muestras es un hilo bombeado por `postDelayed(16 ms)` con una
   deuda que se descarta (tope 3 frames/tick, clamp 100 ms). Si la emu va
   por debajo de 59,73 fps, la APU produce menos de 44 100 muestras/s y el
   AudioTrack (≥ 46 ms) se vacía: cortes. No hay contador de underruns.
3. **El video se pinta a medias por una carrera sin lock (D-VID-1).** La PPU
   tiene triple búfer con `presentIndex_`, pero el hilo GL lee
   `displayBuffer_`, una copia única que el hilo de emulación sobrescribe
   con `memcpy` en VBlank. Si la subida a textura coincide con el `memcpy`,
   la mitad del frame es viejo y la mitad nuevo. Es el candidato directo
   del "medio renderiza, medio no".
4. **Lo que está bien:** cadencia de frame exacta (70224 ciclos, 59,73 fps;
   modos 80/172/204; VBlank en 65664), tabla de ciclos de CPU correcta,
   timers DIV/TIMA correctos a 1x y 2x, doble velocidad bien separada
   (PPU/timers/APU en SYSCLK), tasa de muestras exacta (738/frame).

## Mediciones

| Medida | Esperado (hardware) | Medido | Veredicto |
|---|---|---|---|
| Muestras/frame GB (70224 ciclos) | 738,4 | 738 (1476/2 frames) | OK |
| Anillo APU sin drenar (6 frames) | 4095 frames máx. | 4095 frames (8190 int16) | OK (333 muestras tiradas) |
| Cadencia 10 frames drenando 1/frame | 7383 frames | 7383 frames | OK |
| Modos PPU línea 0 | 80 / 172 / 204 | 80 / 172 / 204 | OK |
| Ciclos hasta VBlank (LY 144) | 65664 | 65664 | OK |
| Ciclos por frame completo | 70224 | 70224 (2 frames seguidos) | OK |
| Length ch1, tick en paso 2 | off a 16384 ciclos | sigue on (lee NR50) | BUG D-APU-1 |
| Envelope ch1, tick en paso 7 | vol 0→1 a 57344 ciclos | vol 0 (lee NR43) | BUG D-APU-1 |
| Sweep ch1, overflow en paso 2 | off a 16384 ciclos | sigue on (lee NR41) | BUG D-APU-1 |
| Ch3 wave, nibble 1, vol 100% | muestra 546 | 0 (DAC lee 0xFF2A) | BUG D-APU-1 |
| Ch4 ruido, vol 15, 1 s | >0 muestras | 0 (vol lee Wave RAM) | BUG D-APU-1 |
| Audio del video, silencios ≥50 ms | — | 185 en 407 s (mediana 84 ms) | Ver §5 |

## Hallazgos

### D-APU-1 — La APU lee `regs_[]` con +0x10 (GRAVE, causa del audio cutre)

`core/apu.cpp`. `write()` guarda en `regs_[address - 0xFF10]` (offsets
0x00-0x2F), pero casi toda la lógica lee `regs_[0x10..0x24]`:

| Lo que lee el código | Slot que lee de verdad | Debería leer |
|---|---|---|
| duty ch1 (`generateSample`) | NR42 (0xFF21) | NR11 |
| envelope ch1 (`clockEnvelope`, trigger) | NR43 (0xFF22) | NR12 |
| freq baja ch1 (trigger) | NR44 (0xFF23) | NR13 |
| length-enable ch1 (`clockLength`) | NR50 (0xFF24) | NR14 |
| sweep ch1 (`clockSweep`, trigger) | NR41 (0xFF20) | NR10 |
| duty ch2 | NR51 (0xFF25) | NR16 |
| envelope ch2 | NR52 (0xFF26) | NR17 |
| freq baja ch2 | 0xFF28 (sin uso) | NR18 |
| length-enable ch2 | 0xFF29 (sin uso) | NR19 |
| DAC ch3 (`generateSample`) | 0xFF2A (sin uso) | NR30 |
| length ch3 | 0xFF2B (sin uso) | NR31 |
| volumen ch3 | 0xFF2C (sin uso) | NR32 |
| freq baja ch3 | 0xFF2D (sin uso) | NR33 |
| length-enable ch3 | 0xFF2E (sin uso) | NR34 |
| length ch4 (trigger) | slot 0x20 = Wave RAM 0 | NR41 |
| volumen ch4 (trigger) | slot 0x21 = Wave RAM 1 | NR42 |
| freq ch4 (`step`) | slot 0x22 = Wave RAM 2 | NR43 |
| length-enable ch4 | slot 0x24 = Wave RAM 4 | NR44 |

Los slots 0x20-0x2F de `regs_` no se pueden escribir jamás (0xFF30-0xFF3F
van a `waveRam_`), así que valen 0 siempre.

Efecto en un juego real, medido con sondas en `test_apu.cpp` (cada sonda
escribe el registro que el código lee de verdad y el comportamiento
cambia, lo que prueba el desplazamiento):

- **Ch3 y ch4 MUDOS siempre.** El DAC del ch3 lee un registro sin uso (0)
  y el volumen del ch4 lee un slot no escribible (0). No hay sonda que los
  despierte; solo el reindexado los arregla.
- **Ch1 y ch2 suenan mal.** Duty, volumen, envelope y frecuencia vienen
  del canal vecino o de NR50/NR51. En juegos reales NR50 suele ser 0x77
  (bit 6 = 1), así que el length del ch1 "funciona por accidente"; el
  resto no.
- `nr50_`/`nr51_` (volumen maestro y paneo) sí se actualizan bien en
  `write()` (casos 0xFF24/0xFF25): el volumen general no es el problema.

Fix (slice 1): reindexar todas las lecturas `regs_[0x1X]` de la lógica a
`regs_[0x0X]` (mejor con constantes con nombre NR10..NR44). `test_apu.cpp`
ya pincha el comportamiento actual con sondas; el fix cambia los checks
a su valor hardware (length off a 16384, envelope 546 a 57344, sweep off a
16384, ch3 546, ch4 >0 muestras).

### D-APU-2 — Periodo del LFSR de ruido 16x más rápido (solo lectura de código)

`core/apu.cpp`, `step()` ch4: `ch4Timer_ += (DIVISORS[divisorCode] << shift)`.
Hardware: periodo = divisor × 2^(shift+4) ciclos (f = 524288 /
(divisor × 2^(shift+1))). El código usa divisor × 2^shift: 16x más
rápido. No es medible por muestras mientras el ch4 es mudo (D-APU-1);
tras el fix, el objetivo es ~128 transiciones/s con shift=7, divisor 8
(hoy serían ~2048/s). Va en el mismo slice 1.

### D-APU-3 — Los temporizadores de canal arrancan en 0 (menor)

`core/apu.cpp`: `ch1Timer_`/`ch2Timer_`/`ch3Timer_`/`ch4Timer_` arrancan en
0, así que el primer paso de duty/wave/LFSR ocurre en el primer `step()`
en vez de tras un periodo completo. Desfasa las fases hasta un paso. El
test lo tuvo en cuenta (la sonda de envelope escribe NR42=0x80 para que
el bit sea 1 en la posición 0). Va en el slice 1 (inicializar el timer al
disparar).

### D-APU-4 — El frame sequencer y los timers usan `if`, no `while` (menor)

`core/apu.cpp`, `step()`: el acumulador de muestras usa `while`
(correcto), pero el sequencer y los temporizadores de canal usan un solo
`if` por llamada. Con pasos grandes (>8192 ciclos) el sequencer se
atrasa. En producción no se da (`stepAndSync` pasa 4-24 ciclos), pero es
una trampa latente: cualquier futuro paso por lotes rompería el audio.
Va en el slice 1 (cambiar a `while`, como el acumulador).

### D-APU-5 — NR52 no limpia `nr50_`/`nr51_` al apagar (menor)

`core/apu.cpp`, `write()` caso 0xFF26: al apagar se hace `memset(regs_)`,
pero `nr50_`/`nr51_` son miembros aparte y conservan su valor. Al
reencender, `read(0xFF24)` devuelve 0 pero `generateSample` usa el
volumen viejo. Va en el slice 1.

### D-AUD-1 — El audio es esclavo de la velocidad de emulación (GRAVE)

`MainActivity.java:217,219-220,243-252,268,273` + `GameEngine.java:232-267`.

- La bomba es un `HandlerThread` con `postDelayed(16 ms)`, sin
  Choreographer ni vsync. Cada tick suma `dt × 59,7275` a una deuda y
  ejecuta `n` frames, con tope `FRAMES_TICK_TOPE = 3` y clamp
  `MAX_TICK_NS = 100 ms`: tras un stall la deuda se descarta
  (`deuda = 0.0`). **El tiempo perdido se pierde para siempre**: si el
  hilo va justo, la emu corre permanentemente por debajo de 59,73 fps.
- La APU solo genera muestras cuando el hilo de emulación ejecuta
  `stepFrame`. Si la emu promedia menos de 59,73 fps, produce menos de
  44 100 muestras/s y el ring se vacía.
- `refreshAtlasSnapshot()` corre en CADA tick del hilo de emulación
  (`MainActivity.java:268`): un JNI + `TileAtlas.fromVram` + texturas
  cada 16 ms, antes del siguiente tick. Es trabajo ajeno a la emulación
  en el hilo que alimenta el audio. `GameEngine.update()` suma por frame
  `checkEmulatorState()`, `maybeTryWildEncounter()`, `maybeAdvanceRoute()`
  y `geofenceManager.updatePlayerLocation(...)`.
- AudioTrack: `bufSize = max(minBufSize, 8192 B)` = ≥ 2048 frames estéreo
  ≈ ≥ 46 ms; cada iteración drena como máximo `short[1024]` = 512 frames
  (11,6 ms). Cuando `readSamples` devuelve 0, el hilo duerme 5 ms.
  Un stall de la emu de más de ~140 ms (anillo 93 ms + AudioTrack ≥46 ms)
  vacía todo y suena un corte.
- No hay `getUnderrunCount()` ni ningún contador de underruns, ni
  medición de jitter entre frames ni de tiempo de `stepFrame`. Sin
  instrumentación no se puede saber si un corte es underrun, overrun o
  lentitud de la emu.

Fix (slice 2): AudioTrack con más margen (2-4x), prioridad
`THREAD_PRIORITY_AUDIO` al hilo, contador de underruns + log, no dormir
5 ms a ciegas, `join` en `stopAudioThread()`. Y medir: log de FPS real
de la emu (ya existe `PUMP012` cada 512 ticks) + underruns.

### D-AUD-2 — El anillo de la APU tira muestras en silencio (overrun)

`core/apu.cpp`, `generateSample()`: si el anillo está lleno, la muestra
se descarta sin más. Medido: 6 frames sin drenar tiran 333 de 4428
muestras. En ráfagas de catch-up (3 frames/tick) se pierden muestras:
saltos audibles y cambio de tono, no un simple retraso. Además
`sampleReadIdx_`/`sampleWriteIdx_` son `int` planos, sin `atomic` ni
mutex: el hilo de audio lee mientras el hilo de emulación escribe
(carrera de datos; en ARM64 suele "funcionar", pero puede leer un índice
a medio actualizar). Va en el slice 2 (anillo mayor o con `atomic`, y
contar muestras tiradas).

### D-AUD-3 — `stopAudioThread()` sin `join`; `resume()` puede duplicar el hilo

`GameEngine.java:232-236,277-283,317`: `stopAudioThread` pone
`audioRunning=false`, interrumpe y pone el hilo a `null` sin esperar a
que termine; `resume()` llama a `startAudioThread()`, que solo mira el
flag. Hay una ventana de doble hilo de audio y de `audioTrack` recreado
mientras el viejo aún se usa. También se ignora el retorno de
`AudioTrack.write()` (`GameEngine.java:261`): un error de escritura pasa
desapercibido. Va en el slice 2.

### D-VID-1 — Carrera en `displayBuffer_`: el frame se lee mientras se escribe (GRAVE)

`core/ppu.cpp:160` (`memcpy(displayBuffer_, drawBuffer_, ...)` en VBlank)
contra `MainActivity.java:3813-3841` (`uploadPpuTexture` lee el
`DirectByteBuffer` de `displayBuffer_` vía `native_bridge.cpp:1105-1113`).

La PPU tiene triple búfer con `presentIndex_` atómico (`ppu.h:249-259`)
que protege los slots de dibujo, pero `getFrameBuffer()` devuelve
`displayBuffer_`, una copia única sin lock que el hilo de emulación
sobrescribe en VBlank. Si `glTexSubImage2D` coincide con el `memcpy`,
la mitad superior del frame es viejo y la inferior nuevo (o al revés).
**Candidato directo del "medio renderiza, medio no"**. Fix (slice 3):
que el hilo GL lea el slot marcado por `presentIndex_` (el triple búfer
ya existe para eso) en vez de `displayBuffer_`, o un lock alrededor del
`memcpy`/subida.

### D-VID-2 — Sin vsync en ningún lado del pipeline

`MainActivity.java:217,273,700,3952-3958`: la bomba de emu usa
`postDelayed(16 ms)` y el GL `RENDERMODE_CONTINUOUSLY` con throttle
manual por early-return (~60 fps). Dos relojes distintos (~60 Hz
pantalla vs 59,7275 Hz GB) sin sincronización: tearing y frames
repetidos/saltados son esperables aunque la emu fuera perfecta. Va en el
slice 3 (Choreographer o señal "frame listo" del hilo de emulación al GL).

### D-TIM-1 — EI activa IME de inmediato (menor)

`core/cpu.cpp:520`: `case 0xFB: ime_ = true`. El hardware activa IME tras
la SIGUIENTE instrucción, no en la misma. Una IRQ puede servirse una
instrucción antes. Va en el slice 4 (timing fino CPU/PPU).

### D-TIM-2 — Modo 3 fijo en 172 ciclos (menor)

`core/ppu.cpp`, `step()` modo 3: 172 ciclos siempre. El hardware varía
172-289 según sprites y scroll. Afecta a juegos que sondean STAT/modo 3.
Va en el slice 4.

### D-TIM-3 — TIMA por contador de umbral, no por flanco de DIV (menor)

`core/mmu.cpp`, `updateTimers()`: TIMA suma por umbral (1024/16/64/256).
El hardware incrementa TIMA en el flanco de bajada de un bit de DIV:
al cambiar TAC a mitad de cuenta o escribir DIV, el comportamiento
difiere en el borde. Los umbrales en sí son correctos (4096/262144/
65536/16384 Hz) y DIV (+1 cada 256) también. Va en el slice 4.

### D-TIM-4 — RTC MBC3 sigue pendiente

Deuda abierta conocida (ESTADO.md). No se midió en este diagnóstico:
requiere el volcado POCO de la 005. Queda fuera de los slices 1-4; se
revisa cuando la 005 se desbloquee.

## Lo que está bien (no tocar)

- **PPU:** cadencia exacta (80/172/204, VBlank 65664, frame 70224,
  59,73 fps, 2 frames seguidos sin deriva). IRQ de VBlank y STAT por
  flanco correctos. Triple búfer de dibujo correcto.
- **CPU:** tabla de ciclos correcta en los opcodes revisados (NOP 4, LD
  8/12, JP cc 16/12, CALL 24/12, RET cc 20/8, RST 16, PUSH 16, POP 12,
  CB 8/12/16, HALT 4). HALT con IRQ pendiente, HALT bug, STOP + KEY1,
  `checkInterrupts` antes de la siguiente instrucción. `toPpuCycles`
  correcto (PPU/timers/APU en SYSCLK, la mitad por M-cycle a 2x).
- **Timers:** DIV +1/256 ciclos, TIMA según TAC, `updateTimers` también
  en HALT, serie antes del early-return.
- **APU (lo que sí funciona):** tasa de muestras exacta (738/frame),
  frame sequencer con los pasos correctos (length en 0/2/4/6, sweep en
  2/6, envelope en 7), `read()` de NR52 con `| 0x70`, apagado por NR52
  (cero muestras).
- **Frame driver:** un frame = un JNI de 70224 ciclos (`stepFrame`),
  140448 a 2x. Métrica `EMU frames/cycles` cada 600 frames.

## El video del gate (20 nov) — medición de audio

`1000392126_0_4fe5.mp4`, 407 s, AAC mono 44,1 kHz, media -27 dB, máx -1,2 dB.
`silencedetect` (umbral -35 dB, mínimo 50 ms): **185 silencios**,
duración mediana 84 ms (rango 50-522 ms), 19,5 s de silencio en total
(4,8% del video). Distribución irregular: 150-180 s concentra 55
silencios (6,5 s en 30 s); 120-150 s y 210-240 s no tienen ninguno.
La mediana del hueco entre silencios es 0,299 s, con rachas regulares
de ~0,303 s (p. ej. 8,2-9,8 s: un silencio de ~64 ms cada ~303 ms).

Lectura honesta: la medición sola NO distingue "descansos de la música"
de "cortes del emulador". Pero el patrón (rachas regulares de ~64 ms
cada ~0,3 s, densas en unos tramos y ausentes en otros) es compatible
con underruns sistemáticos, y el lado Java da el mecanismo (D-AUD-1:
la emu pierde deuda y la APU deja de producir). El gate POCO del slice
2 dirá si los cortes desaparecen al arreglar el camino Java.

## Plan de slices de corrección (uno por sesión)

1. **Slice 1 — APU: reindexado + LFSR + timers.** D-APU-1 (reindexar
   `regs_[]` con constantes NR10..NR44), D-APU-2 (LFSR ×16), D-APU-3
   (timers a periodo al disparar), D-APU-4 (`while` en sequencer/timers),
   D-APU-5 (NR52 limpia `nr50_`/`nr51_`). Host tests: `test_apu.cpp`
   pasa de pinchar el bug a exigir el valor hardware (length off a
   16384, envelope 546 a 57344, sweep off, ch3 546, ch4 >0 muestras,
   transiciones de ruido ~128/s). Gate POCO: la música suena completa
   (4 canales) y afinada.
2. **Slice 2 — Audio Java: margen + prioridad + medición.** D-AUD-1,
   D-AUD-2, D-AUD-3. AudioTrack mayor, `THREAD_PRIORITY_AUDIO`, contador
   de underruns y de muestras tiradas, `join` en stop, retorno de
   `write()` comprobado. Gate POCO: sin cortes en 5 min de juego con el
   video grabado (silencedetect sin rachas regulares).
3. **Slice 3 — Video: publicar por triple búfer + vsync.** D-VID-1
   (GL lee el slot de `presentIndex_`), D-VID-2 (Choreographer o señal
   de frame listo; sacar `refreshAtlasSnapshot()` del tick de emulación).
   Gate POCO: frames completos, sin mitades, FPS estable 59,7.
4. **Slice 4 — Timing fino CPU/PPU.** D-TIM-1 (EI con retardo), D-TIM-2
   (modo 3 variable), D-TIM-3 (TIMA por flanco de DIV). Host tests nuevos
   en `test_lr35902`/`test_timing`. Gate POCO: juegos que sondean STAT y
   timers se comportan como hardware.
5. Después, con la emulación sólida: **spec 019** (GPS = avance,
   pathfinding, auto-desatorar, botón Desatorar, Modo Seguro, contrato
   de modo blindado), luego 005 (bloqueada por volcado POCO), 008 y 007.

## Archivos de este diagnóstico

- `specs/020-pulir-emulacion/diagnostico-nucleo.md` (este archivo).
- `tools/host-test/test_apu.cpp` (35 comprobaciones, 0 fallos).
- `tools/host-test/test_timing.cpp` (19 comprobaciones, 0 fallos).
- `tools/host-test/run.sh` (corredor Linux con `g++`, equivalente de
  `run.ps1`; `test_wildtable` se salta si la base no trae
  `wild_routine.cpp`).
- `tools/host-test/README.md` (conteos actualizados).
