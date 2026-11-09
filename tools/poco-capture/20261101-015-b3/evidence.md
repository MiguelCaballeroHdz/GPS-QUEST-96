# Evidencia — 015 / B3a (D-25): sprite del jugador en el mapa desde OAM real

POCO X7 Pro · 2412DPC0AG · build `assembleDebug` (`EXIT=0`) · 2026-11-02 19:42–19:43.

## Defecto

El `SpriteExtractor` pintaba sobre el mapa un **blob púrpura** que venía del título:
un OBJ 2x2 de la ROM caía dentro de la tolerancia de centro y se publicaba como
"jugador". Al mismo tiempo, el jugador real **nunca** se publicaba.

## Causa raíz medida en POCO (dos capas)

1. **Ventana de lectura.** `mmu.readBlock(0xFE00, …)` desde el hilo Java cae en el
   tramo del frame en que la ROM ya limpió su buffer: `160 ceros`. Log del
   arnés: `|0,0,0,0` × 40 entradas, una y otra vez.
2. **Timing CPU/PPU.** Muestrear la OAM al **entrar en VBlank** tampoco sirve: la
   CPU de este emulador corre desacoplada del PPU y para cuando el PPU entra en
   VBlank la ROM ya puede haber vuelto a limpiar la OAM. Resultado: 160 ceros.

## Cambios

| Archivo | Cambio |
|---|---|
| `core/ppu.h` | `uint8_t oamSnapshot_[160]` + `getOamSnapshot()`. |
| `core/ppu.cpp` | Copia de `0xFE00..0xFE9F` **al pintar la scanline 0** (inicio del frame visible): es la OAM con la que el PPU dibuja los sprites de ese frame. `memset` en el constructor. |
| `jni/native_bridge.cpp` | `…_00024Mmu_readOamSnapshot(byte[])` (160 B, un solo cruce JNI). Se restauró `…_00024Mmu_write`, que se había quedado sin cuerpo en el hueco del JNI nuevo. |
| `EmulationPipeline.java` | `Mmu.readOamSnapshot(byte[])` documentado. |
| `MainActivity.java` | La lectura de OAM pasa de `readBlock(0xFE00,…)` a `readOamSnapshot(oamBuf)`. |
| `SpriteExtractor.java` | Gate D-25: se publica el bloque centrado si **hay animación** (cambia el índice base) **o hay movimiento registrado** (`notePlayerMove`, joystick o paso real dentro de `MOVE_WINDOW_MS`). Un OBJ de título no cumple ninguna. Log de rechazo reducido a `dist` (sin volcar OAM), throttle 10 s. |

## Verificación

- Log vivo en overworld (Nuevo Pueblo Primavera), tras mover el joystick:

```
I SpriteExtractor: sprite OW base=0   via=OAM bank=1 pal=0 tiles=0,1,2,3   hits=6
I SpriteExtractor: sprite OW base=132 via=OAM bank=1 pal=0 tiles=132,133,134,135 hits=6
I SpriteExtractor: tiles cambiados (jugador moviéndose): 1 → [306]
```

  `via=OAM` con `bank=1` confirma CGB: el jugador vive en el banco 1 de VRAM,
  índice base 0 (quieto) y 4/132 (animación de paso).

- `b3a-final-titulo.png`: en el título, **Vista: Mapa** solo muestra el marcador
  blanco. No aparece el blob púrpura (gate D-25 sin evidencia de movimiento).
- `b3a-final-mapa.png`: tras caminar, el mapa pinta el **sprite OAM del jugador**
  (Ethan, gorro rojo) en la celda del marcador, escalado con el zoom.
- `b3a-overworld2.png`: misma ROM en `Vista: Juego`, referencia de que el sprite
  extraído es el del jugador y no un OBJ decorativo.
- `assembleDebug --offline -q` → `EXIT=0`, sin excepciones en `logcat`
  (`PUMP012 … fps=59,7`).

## Pendiente (B3b, D-02)

El mapa sigue pintándose con **color plano por celda**. El atlas VRAM
(`TileAtlas`, 384 tiles) ya sube a GL pero no se usa en el mapa: falta volcar
VRAM en overworld, hoja de contactos y elegir índices con evidencia visual.
