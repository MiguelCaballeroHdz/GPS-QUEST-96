# Evidencia — Spec 014 Slice 3: sprite OW desde VRAM banco 1 (POCO)

**Dispositivo:** POCO X7 Pro (`2412DPC0AG`, `XWWSAY4LAAPZHIA6`) · APK debug ·
build con `Mmu.readVramBlock` + `Mmu.readObjPaletteBlock`.

## Causa raíz (medida, no supuesta)

Volcado del overworld con el jugador quieto (`ow2/`, `ow3/`, `fresh/`):

| idx OAM | x,y | tile | atributo |
|--------:|-----|-----:|---------:|
| 0 | 64,60 | 0 | `0x08` |
| 1 | 72,60 | 1 | `0x08` |
| 2 | 64,68 | 2 | `0x88` |
| 3 | 72,68 | 3 | `0x88` |

- `LCDC=0xE3` → sprites de 8x8: el jugador son 4 OBJ contiguos (bloque 2x2).
- Atributo bit 3 (`0x08`) = **banco 1 de VRAM**; bits 0-2 = paleta OAM 0.
- El código anterior copiaba VRAM con `readBlock()` (depende de `VBK`, que el juego cambia) y
  componía desde banco 0 → glifos de fuente (el "blob" verde del mapa).

## Verificación

1. **Log en overworld** (`logcat`, tras el fix):
   `sprite OW base=0 via=OAM bank=1 pal=0 tiles=0,1,2,3 hits=6`.
2. **Caminata de 30 s** (joystick por `input swipe`, derecha/izquierda): solo
   `base=8/9/136/137`, todos `bank=1 pal=0`; ninguna vuelta al banco 0. Cada base produce un
   frame válido del jugador (`t1-bank.png`, `t2-walk.png`, `t3-overlay.png`).
   Antes del fix: `hits` de 11 123 con base=133/137 alternando cada tick.
3. **Silueta contra la PPU:** `compare-base0.png` y `compare-base8.png` comparan el bloque
   compuesto desde VRAM banco 1 con el frame que dibuja el PPU en las mismas coordenadas
   (64,60): la forma coincide píxel a píxel (los colores dependen del instante de la paleta).

## Herramientas dejadas en esta carpeta

- `dump-sprite.ps1` — vista ASCII de los 4 órdenes posibles de un bloque de tiles.
- `render-sprite.ps1` — compone un bloque 2x2 con la paleta CGB a PNG.
- `compare-sprite.ps1` — compara composición vs frame del PPU en coordenadas dadas.
- `capture-ow-sprite.ps1` — dispara el dump, trae frame/OAM/VRAM/paleta, detecta el bloque
  del jugador (OAM centrada, banco por atributo) y lanza la comparación.
- `frame-to-png.ps1` — convierte `ppu_frame.rgba` (160x144) a PNG.

## Arnés y build

`tools/host-test/run.ps1` → 34 mmu + 18 sav + 51 ppu + 23 cpu + 24 wildtable = **150 C++**;
`run-java.ps1` → **39 Java**; `gradlew assembleDebug --offline -q` → `EXIT=0`.
