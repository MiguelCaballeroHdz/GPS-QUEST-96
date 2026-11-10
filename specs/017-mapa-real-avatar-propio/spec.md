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

## 10. Slice 2 — capa de tiles OSM (implementación)

### 10.1 La referencia vieja: qué se reutiliza y qué se evita

En oct 2026 la app ya tuvo mapa open source (ver `memory/archivo/bitacora-historico.md`): osmdroid + `TileQueueManager.java` propio, borrado entero el 9 oct 2026 ("adiós OSM") a favor del raster procedural. El fallo que Miguel recuerda ("dos mapas cruzados uno sobre el otro") fue real: la capa de tiles y el raster/checker dibujaban **a la vez** (tiles como base bajo un checker translúcido alfa 0.45 + rejilla de celdas), con proyecciones y anclas distintas. Nada de eso queda en el código actual (grep verificado: ni `TileQueueManager`, ni osmdroid, ni `GBTileRenderer`).

**Se reutilizan (parámetros que sí funcionaban, reimplementados desde cero; el archivo viejo no existe):**
- Pool fijo de 2 hilos de descarga (`OsmTileManager`).
- RAM `LruCache` ~6 MB, tamaño por `Bitmap.getByteCount()`.
- Disco `filesDir/maps/z_x_y.png` con TTL de 7 días (precedente de `OsmDataFetcher`).
- Dedupe en vuelo (`ConcurrentHashMap` con `putIfAbsent`).
- Endpoint `https://tile.openstreetmap.org/{z}/{x}/{y}.png` + User-Agent propio con contacto (`GPSQuest96/0.1.0 (https://github.com/MiguelCaballeroHdz)`).
- Rejilla 3x3 alrededor del punto, zoom base 16.
- Throttle de petición de área: 2 s por mismo centro y zoom.
- Solo `HttpURLConnection` + hilos worker + `org.json` donde aplique. Cero dependencias nuevas (STACK.md: Miguel compila con `--offline`; no osmdroid/okhttp/gson/Glide).

**Se evita (lo que rompió el mapa viejo):**
- Dibujar tiles y raster a la vez: la regla es estructural (§10.3), no de disciplina.
- Checker translúcido o rejilla de celdas sobre las tiles.
- osmdroid y su atribución de librería: la atribución va en la UI propia (`tv_osm_attr`, §10.4).
- Subir solo la tesela central en el hilo GL: aquí el marco se publica entero o no se dibuja (§10.3).

### 10.2 Zoom UI → nivel z

El zoom del mapa lo gobierna `GBMapRenderer` (`setZoom` 0.5–4.0, botones ±0.5, preferencia `map_zoom`). El nivel de tesela se deriva de ese zoom, no al revés:

| Zoom UI | Nivel z | Ancho de tesela (Xalapa, lat 19.5°) |
|---|---|---|
| 0.5x | 15 | ~1153 m |
| 1.0x | 16 | ~576 m |
| 1.5x | 17 | ~288 m |
| 2.0x | 17 | ~288 m |
| 3.0x | 18 | ~144 m |
| 4.0x | 18 | ~144 m |

Fórmula (`TileMath.zoomForUiZoom`): `z = 16 + round(log2(uiZoom))`, recortado a 15–18; zoom inválido (0, negativo, NaN) → 16. Al cambiar el zoom UI, `MainActivity.applyMapZoom` repide las 9 teselas del nuevo nivel para el último centro conocido (sin pedir si nunca hubo centro). La rejilla 3x3 a z16 cubre ~1729 m de ancho; el viewport del raster es 504x1112 m, así que el marco cubre la vista con margen.

### 10.3 Garantía de una sola capa base (estructural)

1. `OsmTileLayer` solo publica un marco **completo**: 9 teselas del mismo (z, cx, cy) subidas a textura. Un marco parcial nunca se dibuja; un cambio de centro o de zoom descarta el marco en curso y borra sus texturas; una tesela duplicada se ignora; una tesela de un marco viejo se borra en el acto.
2. `OsmTileLayer.draw()` devuelve `true` solo si el marco publicado **cubre todo el viewport** (`TileMath.covers`, borde tocado cuenta como cubierto); en cualquier otro caso (sin marco, marco incompleto, viewport fuera del marco) devuelve `false`.
3. `GameRenderer.onDrawFrame` dibuja **una sola**: `tilesDrawn = tileLayer.draw(...)`; `if (!tilesDrawn) gbMap.draw(...)`. No existe camino de código que dibuje ambas en el mismo frame.
4. La proyección es la misma que la del raster (metros → píxeles con la celda de 8 m de `GBMapRenderer`, misma ancla en el centro del mapa), así que al caer al respaldo la geografía no salta. El avatar (Slice 1) y los POIs/nodos de ruta se dibujan **encima** de la capa base, sea cual sea.
5. Sin red y sin caché: el manager no entrega nada, el marco nunca se publica, `draw()` devuelve `false` y el mapa es raster pixel art (D3). Sin red **con** caché: las 9 teselas salen de disco (TTL 7 días) y el mapa es real.

### 10.4 Política de uso de tiles OSM

- User-Agent propio con contacto en cada petición; `Referer` al repo.
- Sin descarga masiva: rejilla 3x3 por área, throttle 2 s por mismo centro y zoom, sin reintento interno (el próximo `requestArea` reintenta), timeouts 15 s, descarga solo por movimiento/zoom del usuario.
- Caché en disco con TTL 7 días: el mapa funciona sin conexión con lo ya descargado.
- Atribución "© OpenStreetMap contributors" visible en la UI del mapa (`tv_osm_attr`, abajo centrado, fondo semitransparente) **solo cuando las tiles están activas**; se oculta al caer al respaldo raster. El `GameRenderer` (static) no toca la UI: avisa por `AttributionListener` y la Activity cambia la visibilidad en el hilo UI, solo cuando cambia el estado.
- Sondeo de validación del endpoint (2026-11-10, una sola petición): `GET https://tile.openstreetmap.org/16/15123/29142.png` (Xalapa centro, z16) con el User-Agent de la app → **HTTP 200, 23 477 bytes, PNG 256x256, 0,8 s**.

## 11. Criterios de aceptación (Slice 2)

1. `TileMathHostTest`: 93 comprobaciones, 0 fallos; suite host completa 550 comprobaciones, 0 fallos (39 Pueblo + 11 Geofence + 55 OSM Classify + 47 Route + 36 RouteValidator + 26 RouteBundle + 43 RasterGrid + 33 SpriteExtractor + 167 AvatarSprites + 93 TileMath).
2. Una sola capa base activa en todo momento (§10.3): no existe camino de código que dibuje tiles + raster en el mismo frame.
3. Cero dependencias Gradle nuevas (sin cambios en `build.gradle`).
4. `.\gradlew.bat clean assembleDebug --offline` → `EXIT=0` en la PC de Miguel (pendiente: `assembleDebug` no corre en la VM).
5. En el POCO con red: mapa real con calles y nombres, avatar encima, atribución visible abajo. Sin red con caché: mismo mapa real. Sin red sin caché: raster pixel art y atribución oculta (pendiente: verificación de Miguel).
6. La pantalla del emulador no cambia (sin toques al camino PPU).
