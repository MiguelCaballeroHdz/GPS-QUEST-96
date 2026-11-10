# spec.md — 017 Mapa real y avatar propio

Estado: APROBADA — aprobada por Miguel el 10 de noviembre de 2026 (decisión en WhatsApp: "Mapa normal + sprite propio").

## 1. Objetivo

El mapa deja de ser un raster pixel art generado a partir de la clasificación OSM y pasa a ser el mapa real de OpenStreetMap, y el jugador deja de ser un sprite extraído en tiempo real de la ROM del juego del usuario y pasa a ser el avatar propio dibujado por Miguel. La identidad retro del juego se conserva en el avatar, los POI, el HUD y las escenas de pantalla completa del emulador; el mapa es el mapa real de la ciudad.

## 2. Decisiones

- **D1 — Avatar propio = el arte de Miguel.** La hoja de sprites enviada por Miguel el 2026-11-10 es la única fuente de arte del avatar. Cero bytes de la ROM: la animación la gobierna el movimiento (paso real validado o joystick en Modo Sofá), no OAM/VRAM. Retoque autorizado por Miguel ("los puedes retocar a tu gusto"): limpiar y adaptar, sin rediseñar el personaje.
- **D2 — Mapa base = tiles raster OSM reales** (slippy map, `tile.openstreetmap.org`), con atribución "© OpenStreetMap contributors" visible y respeto a la política de uso de tiles: caché en disco, sin descarga masiva, User-Agent propio. Cero dependencias Gradle nuevas (Miguel compila con `--offline`).
- **D3 — El raster pixel art actual queda como respaldo sin conexión** cuando no hay tile en caché. No se borra `GBMapRenderer`/`OsmRasterGrid` en el Slice 1.
- **D4 — La pantalla del emulador (BATTLE/pantalla completa) NO cambia**: sigue dibujando el juego del usuario. Solo cambia el sprite del mapa.

## 3. La hoja de Miguel (fuente de arte)

- Archivo original: hoja enviada por WhatsApp el 2026-11-10, 1599x1066, RGB, fondo blanco, JPG (con ruido de compresión). Personaje chibi con ropa de camuflaje y mochila: chico de pelo castaño y chica de coletas rosas; vistas frente/espalda/lado con cuadros de caminata.
- Rejilla verificada por proyección (no supuesta): **4 filas x 9 columnas = 36 figuras**. Bandas de columnas: C1 23–175, C2 189–342, C3 355–510, C4 518–674, hueco 675–781, C5 782–929, C6 945–1095, C7 1100–1252, C8 1263–1413, C9 1428–1579. Bandas de filas: R1 y24–306, R2 y314–561, R3 y568–810, R4 y811–1041. Cajas exactas de las 36 celdas en `ref/celdas.json`.
- Mapeo cuadro → celda (verificado visualmente + estadísticas de pelo rosa; cuadro parado = fase A):

| Cuadro | Celda | Caja (x0,y0)-(x1,y1) | Tamaño |
|---|---|---|---|
| BOY_FRONT_A | R1C1 | (23,24)-(175,306) | 153x283 |
| BOY_FRONT_B | R1C2 | (189,24)-(342,306) | 154x283 |
| BOY_BACK_A | R1C3 | (355,24)-(510,306) | 156x283 |
| BOY_BACK_B | R1C4 | (520,24)-(674,306) | 155x283 |
| BOY_SIDE_A | R2C1 | (30,322)-(167,561) | 138x240 |
| BOY_SIDE_B | R3C1 | (31,573)-(168,805) | 138x233 |
| GIRL_FRONT_A | R4C2 | (196,821)-(338,1041) | 143x221 |
| GIRL_FRONT_B | R4C5 | (801,817)-(929,1038) | 129x222 |
| GIRL_BACK_A | R4C3 | (361,812)-(501,1029) | 141x218 |
| GIRL_BACK_B | R4C4 | (518,812)-(659,1030) | 142x219 |
| GIRL_SIDE_A | R2C1 + recolor | — | — |
| GIRL_SIDE_B | R3C1 + recolor | — | — |

- **La hoja NO trae lado de chica.** GIRL_SIDE_A/B son el lado chico (R2C1/R3C1) con el pelo castaño pasado a rosa en RGB antes de cuantizar (mismo sombreado, sin inventar poses). Retoque documentado, no pose nueva. Izquierda = espejo del lado derecho.
- Celdas no usadas en el Slice 1: R1C5–C9, R2C2–C9, R3C2–C9, R4C6–C9 (variantes extra de las mismas poses) y R4C1 (lado chico extra). La animación usa 2 fases por dirección; las variantes sobrantes quedan como referencia en `ref/`.

## 4. Retoque (autorizado)

Script: `ref/build_avatar.py` (regenerable; no editar los datos a mano).

1. Fondo eliminado por flood-fill desde los bordes sobre casi-blanco (ruido JPG fuera).
2. RGB transparente rellenado con el color de figura más cercano (sin halo blanco).
3. Escala uniforme (figura más alta 283 px → 56 px), pies alineados abajo, centrado horizontal.
4. Paleta global por k-means (20 colores) solo sobre píxeles de figura; cuadros **32x56**.
5. Recolor lado chica en RGB antes de cuantizar (pelo castaño +70R/+30G/+120B en el 40% superior, sombreado conservado).
6. Paleta final: 20 colores, índice 0 = transparente. Pelo castaño (chico): índices 7, 10, 13. Pelo rosa (chica): 16, 18, 20. Verificado: ningún cuadro chico contiene rosa vivo; todos los cuadros chica lo contienen.

Arte en la app: datos de píxeles en código (`avatar/AvatarSprites.java`, Java puro, sin bytes de la ROM), no PNGs en `res/drawable/`. Razón: el render actual (`GameRenderer`) ya sube el sprite como textura desde un `int[]` ARGB por `setSpritePixels`; los datos en código reutilizan ese camino sin tocar el pipeline GL ni añadir recursos, y se verifican con host tests sin Android.

## 5. Alcance

- **Slice 1 — Avatar propio con la hoja de Miguel (este trabajo).** Cuadros en código, cableado en `MainActivity`/`GameRenderer`, animación por movimiento, host tests. El sprite ROM sale del mapa.
- **Slice 2 — Capa de tiles OSM.** Descarga y caché en disco de tiles raster, atribución visible, sin dependencias nuevas.
- **Slice 3 — Integración.** Tiles + POIs/ruta + avatar + respaldo pixel art sin conexión + atribución en UI.

## 6. Fuera de alcance

- Rediseñar el personaje de Miguel o inventar poses, direcciones o cuadros nuevos.
- Cambiar la pantalla del emulador (BATTLE/pantalla completa).
- Borrar `GBMapRenderer`/`OsmRasterGrid` (respaldo, D3).
- Audio APU (deuda abierta).
- Spawns de batalla por paso real (009 slice 3, se rehace sobre esta base).

## 7. Criterios de aceptación (Slice 1)

1. Los 12 cuadros tienen las dimensiones declaradas (32x56), píxeles no vacíos y transparencia donde toca; izquierda = espejo exacto de derecha; el cuadro avanza con el paso.
2. `AvatarSpritesHostTest`: 167 comprobaciones, 0 fallos; suite host completa 457 comprobaciones, 0 fallos.
3. Cero referencias a `identifySprite`/`composeSprite`/`SpriteExtractor` en `MainActivity` (verificado con grep).
4. `.\gradlew.bat clean assembleDebug --offline` → `EXIT=0` en la PC de Miguel (pendiente: `assembleDebug` no corre en la VM).
5. En el POCO: el avatar de Miguel camina en el mapa sin dividirse, chico/chica según su perfil, dirección según el movimiento, cuadro parado al detenerse (pendiente: verificación de Miguel).
6. La pantalla del emulador no cambia (sin toques al camino PPU).

## 8. Limpieza pendiente (no en este slice)

- `SpriteExtractor.java` queda sin uso en el mapa: NO se borra en el Slice 1; su retirada es limpieza pendiente (la clase sigue siendo correcta: el fix de flips de `fix-sprite-flips` queda dentro, sin uso).
- La verificación en POCO del fix de flips (rama `fix-sprite-flips`: caminar girando izquierda/derecha, `flipX=`/`flipY=` en logcat) queda **sustituida en el camino del mapa** por la verificación del avatar propio (criterio 5). El fix no se revierte: el sprite ROM ya no se dibuja en el mapa.
- `GameRenderer.clearSpritePixels()` queda sin llamador por ahora; se conserva como API del renderer.

## 9. Riesgos y mitigaciones

| Riesgo | Mitigación |
|---|---|
| Identificación errónea de cuadros en la hoja | Mapeo verificado por proyección + vista visual (contact sheet y GIF en `ref/`); cajas exactas en `ref/celdas.json`. |
| El lado chica es un recolor, no un dibujo propio | Documentado en §3; Miguel autorizó el retoque; el sombreado se conserva y el rosa domina al castaño en la zona del pelo (verificado por host test). |
| El cuadro 32x56 es mayor que el 16x16 ROM | El quad conserva la proporción (1 celda de ancho, 1,75 de alto), con precedente en el viejo `PlayerRenderer` 16x24; la textura se crea y se sube con FRAME_W x FRAME_H. |
| `MainActivity` no compila en la VM | No se puede verificar aquí (faltan stubs de Android); revisión estática del diff + build en la PC de Miguel (criterio 4). |
