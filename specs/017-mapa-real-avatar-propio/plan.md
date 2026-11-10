# plan.md — 017 Mapa real y avatar propio

Estado: APROBADO (spec aprobada por Miguel el 10 de noviembre de 2026). Spec: `specs/017-mapa-real-avatar-propio/spec.md`.

Regla: ningún slice de código se ejecuta hasta que la spec esté APROBADA.

## Orden de slices

### Slice 1 — Avatar propio con la hoja de Miguel

**Estado: HECHO en host (2026-11-10, rama `017-slice-1` desde `fix-sprite-flips` / 735a020). Pendiente: build en PC + verificación en POCO.**

**Objetivo:** El mapa dibuja el avatar propio de Miguel (su hoja, retocada) en vez del sprite extraído de la ROM; la animación sigue al movimiento y la dirección sale del rumbo.

**Archivos**
- `app/src/main/java/com/gpsquest96/avatar/AvatarSprites.java` (nuevo): 12 cuadros 32x56 como cadenas de índices de paleta (paleta global de 20 colores, índice 0 transparente), `Direction` FRONT/BACK/SIDE, `directionFor`/`mirrorFor` por rumbo, `phaseFor` (STEP_MS=180, IDLE_MS=500), `pixels`/`pixelsMirrored`/`decode`/`mirror`. Java puro, cero bytes de la ROM. Generado por `specs/017-mapa-real-avatar-propio/ref/build_avatar.py` desde `ref/avatar_data.json`; no editar los datos a mano.
- `app/src/main/java/com/gpsquest96/ui/MainActivity.java`: `GameRenderer` generalizado de 16x16 a FRAME_W x FRAME_H (`setSpritePixels`, `createSpriteTexture`, `uploadSpriteTexture`); `buildSpriteQuad` rectangular (1 celda de ancho, 1,75 de alto, proporción conservada); `updateAvatarSprite()` en el hilo GL (cuadro por rumbo + movimiento, género del perfil vía `SaveManager.getPlayerGender()` → `AvatarData.genderOf()`); retirado el bloque del sprite ROM de `refreshAtlasSnapshot()` (identificación, OAM, composición) y los buffers `vram1Buf`/`oamBuf`/`objPalBuf` + el campo `spriteExtractor`. La pantalla del emulador no se toca.
- `tools/host-test/java/test/hosttest/AvatarSpritesHostTest.java` (nuevo): 167 comprobaciones.
- `tools/host-test/run-java.ps1`: dado de alta el test (fuentes `AvatarData` + `AvatarSprites` + test; bloque de ejecución).
- `specs/017-mapa-real-avatar-propio/ref/`: hoja limpia, máscara, recortes por cuadro, vista previa etiquetada (`vista-previa-cuadros.png`), GIF de caminata (`vista-previa-caminata.gif`), `build_avatar.py`, `celdas.json`, `avatar_data.json`.

**Tareas**
1. Analizar la hoja por proyección (filas/columnas, cajas por figura) y documentar el mapeo exacto. ✓
2. Retocar (fondo a transparente, paleta cuantizada, cuadros 32x56) y generar `AvatarSprites.java`. ✓
3. Escribir `AvatarSpritesHostTest.java` y darlo de alta en `run-java.ps1`. ✓
4. Cablear el avatar en `MainActivity`/`GameRenderer` y retirar el sprite ROM del mapa. ✓
5. Host tests en verde; spec + plan + `ESTADO.md`. ✓
6. Build en PC de Miguel + caminata en POCO (pendiente, de Miguel).

**Criterio de salida:** Host tests en verde (167 nuevas, 457 totales, 0 fallos); cero referencias ROM en `MainActivity`; build `assembleDebug` verde en la PC de Miguel; avatar caminando en el POCO sin dividirse, chico/chica según su perfil.

---

### Slice 2 — Capa de tiles OSM

**Estado: HECHO en host (2026-11-10, rama `017-slice-2` desde `017-slice-1` / 8c933e7). Pendiente: build en PC + verificación en POCO.**

**Objetivo:** El mapa base son tiles raster reales de OpenStreetMap (slippy map, `tile.openstreetmap.org`), con caché en disco y atribución "© OpenStreetMap contributors" visible.

**Archivos**
- `app/src/main/java/com/gpsquest96/gps/TileMath.java` (nuevo, Java puro): matemática slippy (lat/lon ↔ z/x/y con recorte de polos y vuelta en el antimeridiano), ancho de tesela en metros, `zoomForUiZoom` (zoom UI → nivel z), clave de caché `z_x_y`, TTL 7 días (`isExpired`, misma semántica que `OsmDataFetcher`: mtime desconocido no caduca), rejilla 3x3 (`tilesForCenter`), `covers` (marco cubre el viewport o toca respaldo), `halfSpanM` (mitad del viewport en metros, misma escala 8 m/celda que el raster).
- `app/src/main/java/com/gpsquest96/gps/OsmTileManager.java` (nuevo): reimplementación desde cero del viejo `TileQueueManager` (oct 2026): pool fijo de 2 hilos, RAM `LruCache` ~6 MB, disco `filesDir/maps/z_x_y.png` con TTL 7 días, dedupe en vuelo, endpoint `tile.openstreetmap.org` + User-Agent `GPSQuest96/0.1.0 (https://github.com/MiguelCaballeroHdz)`, throttle de área 2 s, timeouts 15 s, sin reintento interno. Entrega `TileDelivery` (z, centro cx/cy de la rejilla, x/y, bitmap) al listener; `stats()` y `shutdown()`.
- `app/src/main/java/com/gpsquest96/render/OsmTileLayer.java` (nuevo): shader propio de quad texturado; marco 3x3 inmutable que solo se publica con las 9 teselas subidas; `setTileCentered` (hilo GL; cambio de objetivo descarta el marco en curso y borra sus texturas; duplicados ignorados); `draw` devuelve true solo si el marco cubre el viewport (misma proyección que el raster, metros → píxeles con la celda de 8 m, V invertida como el sprite, filtro LINEAR) y false en cualquier otro caso (el llamador DEBE caer al raster).
- `app/src/main/java/com/gpsquest96/ui/MainActivity.java`: campo `tileManager` (creado junto a `osmFetcher`, listener que encola al renderer) y `tvOsmAttr`; `requestMapTiles` pide también el área de tiles al zoom de tesela del zoom UI actual; `applyMapZoom` repide las tiles al cambiar el zoom (con `lastOsmLat/lastOsmLon`, sin pedir si nunca hubo centro); `GameRenderer`: campo `tileLayer` (creado en `onSurfaceCreated`, viewport en `onSurfaceChanged`), cola de entregas (máx 36, drenaje máx 2/frame), `getMapZoom()`, `feedTile()`, rama de dibujo de una sola capa base (`tilesDrawn ? tiles : raster`, nunca ambas), atribución por listener (`AttributionListener`, el renderer es static y no toca la UI directamente: solo avisa al cambiar); `onDestroy` apaga el manager.
- `app/src/main/res/layout/activity_main.xml`: `TextView` `tv_osm_attr` ("© OpenStreetMap contributors", abajo centrado, `gone` por defecto).
- `tools/host-test/java/test/hosttest/TileMathHostTest.java` (nuevo): 93 comprobaciones (conversiones slippy con valores conocidos de Xalapa z15–18, bordes, polos, antimeridiano, inversas, ancho en metros, zoom UI → z, clave de caché, TTL, rejilla 3x3, `covers`, mitad del viewport).
- `tools/host-test/run-java.ps1`: dadas de alta las fuentes `TileMath` + test y el bloque de ejecución.

**Tareas**
1. Descarga de tiles por z/x/y con caché en disco (sin descarga masiva, User-Agent propio). ✓
2. Dibujo del tile en el quad del mapa con el zoom actual. ✓
3. Atribución visible. ✓
4. Host tests donde aplique; build en PC + prueba en POCO con y sin red. ✓ host (93 nuevas, 550 totales, 0 fallos); build en PC + POCO pendientes (de Miguel).

**Criterio de salida:** Host tests en verde (93 nuevas, 550 totales, 0 fallos); una sola capa base activa (tiles o raster, nunca ambas — garantizado por `OsmTileLayer.draw()` + la rama de `GameRenderer`, ver spec §10); `assembleDebug` verde en la PC de Miguel; en el POCO con red se ve el mapa real de la ciudad con el avatar encima; sin red y sin caché, el raster pixel art sigue como respaldo (D3).

---

### Slice 3 — Integración (tiles + POIs/ruta + avatar + respaldo + atribución)

**Estado: PENDIENTE (depende del Slice 2).**

**Objetivo:** Todo junto: tiles reales, POIs y ruta encima, avatar propio, respaldo pixel art sin conexión, atribución en UI.

**Tareas**
1. Orden de capas: tiles → raster de respaldo (solo sin tile) → POIs/ruta → avatar.
2. Atribución en UI en la vista de mapa.
3. Prueba en POCO: con red, sin red con caché, sin red sin caché.
4. Host tests donde aplique.

**Criterio de salida:** Los tres modos se ven correctos en el POCO y el avatar sigue al movimiento en todos ellos.
