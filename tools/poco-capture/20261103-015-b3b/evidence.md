# Evidencia — 015 / B3b: pixel art del mapa desde la VRAM real (D-02)

POCO X7 Pro · 2412DPC0AG · 2026-11-03 20:52 · build `assembleDebug --offline -q` `EXIT=0`.

## Defecto verificado en código

| Archivo : línea | Efecto |
|---|---|
| `MainActivity.java:2911-2971` (`createAtlasTexture`/`uploadAtlasTexture`) | Los 384 tiles de VRAM se subían a una textura GL… |
| `GBMapRenderer.java` (antes) | …y **nadie la muestreaba**: el mapa pintaba `glDrawArrays` con un color plano por celda. El atlas era memoria muerta y el mapa no era pixel art. |
| `TileAtlas.java:75` | El atlas se indexaba con la base **0x8000 sin signo**, pero el overworld de Gen 2 corre con `LCDC=0xE3` (bit 4 = 0 ⇒ datos en 0x8800 con signo). Sin corregir el índice, cualquier muestreo caía en el tile equivocado. |

## Cómo se eligió cada tile (sin inventar)

`ppu_regs.txt` del volcado: `LCDC=0xE3`, `SCX=0`, `SCY=0`, `BGP=0xE4`, `LY=0x0A`.
Con `LCDC` bit 4 = 0 el tilemap apunta a **0x9800** y los índices usan la base
**0x9000+i·16**. La ventana visible usa solo 16 tiles; tres de ellos son terreno:

| Índice de tilemap | VRAM | Índice en el atlas | Qué es (comprobado en pantalla) |
|---|---|---|---|
| `0x05` | `0x9050` | `0x105` | césped (dither base) |
| `0x1E` | `0x91E0` | `0x11E` | copa de árbol |
| `0x3D` | `0x93D0` | `0x13D` | camino de tierra |

### Herramienta de evidencia (nueva)

`tools/vram-sheet.ps1` (PowerShell + C# embebido, solo lectura):

- `-Mode Sheet` → hoja de contactos de 384 tiles con su índice; borde rojo en los
  que usa la tilemap actual. Salida: `atlas-sheet.png`.
- `-Mode Screen` → reconstruye la ventana 20×18 desde la tilemap. Salida:
  `vram-screen.png`, que coincide con la pantalla real del juego (árboles arriba a
  la izquierda, camino vertical en la columna 3, hierba alta desde la fila 8,
  cartel a la derecha, valla abajo). Es la prueba de que la dirección de tiles
  resuelta (con signo, tilemap 0x9800) es la correcta.

```
0x04->0x9040 0x05->0x9050 0x06->0x9060 0x1E->0x91E0 0x1F->0x91F0
0x2E->0x92E0 0x2F->0x92F0 0x3D->0x93D0 0x3E->0x93E0 0x3F->0x93F0
0x4C->0x94C0 0x4D->0x94D0 0x4E->0x94E0 0x4F->0x94F0 0x5E->0x95E0 0x5F->0x95F0
```

## Cambios

1. `GBMapRenderer` con **atributos de tile**: `a_tile` (base uv en el atlas) y
   `a_rep` (repeticiones dentro de la celda). El fragment shader hace
   `fract(v_rep)` para repetir el patrón 8×8 sin salirse del tile:
   `uv = base + clamp(fract(rep), 0, 0.9999) * (1/384, 1)`.
2. Tamaño de tile en pantalla **12 px** (1,5× los 8 px de la GB). A 24 px el
   dither de 1 px se leía como rayado y ensuciaba el mapa.
3. **Tinte de UI sobre el patrón ROM**: `color * (0.35 + 1.10 * luminancia)`. El
   patrón (dither real del juego) viene de VRAM; el tono mantiene la legibilidad
   del mapa (césped verde, arbolado oscuro, calle beige). Con el verde GB puro,
   calle y césped se confundían.
4. `MainActivity`: el atlas solo se publica como textura cuando tiene tiles
   reales (`lastAtlasTiles > 0`, evita un atlas plano amarillo al arrancar) y
   `gbMap.setAtlasTexture(atlasTexId)` se llama tras el `glTexSubImage2D`.
5. Tipos **sin tile verificado** (agua, edificio, comercio) siguen a color plano:
   en este volcado no había ni un tile de agua ni de casa en pantalla, y no se
   inventa el índice.

### Tabla final tipo de celda → tile

| Celda | Tile | VRAM | Tinte |
|---|---|---|---|
| `EMPTY` | `0x105` césped | `0x9050` | verde `0,42 0,62 0,22` |
| `PARK` | `0x11E` árbol | `0x91E0` | verde oscuro `0,16 0,36 0,14` |
| `ROAD` | `0x13D` camino | `0x93D0` | beige `0,80 0,78 0,70` |
| `WATER` | — (sin tile verificado) | — | azul plano |
| `BUILDING` | — | — | marrón plano |
| `SHOP` | — | — | ocre plano |

## Verificación en POCO

```
I GBMapRenderer: OSM raster: 175 celdas / 1111 nodos (rejilla 12x27)
I GBMapRenderer: tipos: vacío=149 parque=8 calle=72 edificio=94 comercio=1 agua=0 · con tile ROM: vacío/parque/calle
I GBMapRenderer: rejilla 12x27 celdas cuadradas de 101.666664 px (pantalla 1220x2712, zoom 1.0, tile x8)
I GBMapRenderer: rejilla 12x27 celdas cuadradas de 152.5 px (pantalla 1220x2712, zoom 1.5, tile x13)
D GameEngine: PUMP012 ticks=4608 frames=618 dt_medio_ms=2,2 fps=59,7
```

- `tile x8` / `tile x13`: repeticiones por celda (101,7 px y 152,5 px entre 12 px).
  El patrón se mantiene fino al cambiar el zoom.
- Captura `b3b-6.png` (zoom 1,5): calle gris con dither, césped verde con dither,
  celda de arbolado oscura al pie y edificios planos. Sin excepciones y a
  59,6–59,8 fps (sin regresión por el muestreo).
- `b3b-8-game.png`: Vista: Juego intacta (la ROM manda al 100 %).

## Artefactos

- `ppu_vram.bin`, `ppu_vram1.bin`, `ppu_oam.bin`, `ppu_bg_palette.bin`,
  `ppu_obj_palette.bin`, `ppu_regs.txt`: volcado crudo del overworld
  (long-press en "Vista:"), extraído con `run-as … base64` para no corromper
  binarios por la consola.
- `atlas-sheet.png`, `vram-screen.png`: hoja de contactos y reconstrucción.
- `b3b-6.png`: mapa con tiles en zoom 1,5. `b3b-8-game.png`: Vista: Juego.
  (`b3b-7.png` es la pantalla de partida guardada del menú: no es evidencia de B3b.)

## Pendiente

- Tiles de **agua**, **casa** y **tienda**: requieren un volcado con esos tiles en
  pantalla (caminar a una casa/zona de agua con el joystick) y repetir la hoja de
  contactos. Hasta entonces esos tres tipos siguen a color plano (honesto).
- El marcador blanco del centro sigue siendo el respaldo cuando el sprite OW no
  tiene evidencia (B3a ya cerrado; no depende de B3b).
