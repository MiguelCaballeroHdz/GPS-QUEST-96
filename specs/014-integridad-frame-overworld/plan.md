# Plan 014 — Integridad del mapa OSM y del frame

**Estado: APROBADO** · 3 slices · un cambio verificable por turno + build correcto.

---

## Slice 1 — Red OSM usable + caché honesta (`OsmDataFetcher.java`)

**Archivo:** `app/src/main/java/com/gpsquest96/gps/OsmDataFetcher.java`

**Cambio:**
1. `TIMEOUT_MS` 10 000 → **40 000** (connect + read). Medido: Overpass tarda 26–34 s.
2. Lista de endpoints con espejo: `overpass-api.de` → `overpass.kumi.systems`. Se recorre
   por intento; la query y el parseo no cambian.
3. `loadFromDisk`: si `lastModified() <= 0` se considera **edad desconocida** y se usa el
   fichero si parsea; solo se descarta y borra si el `mtime` real supera el TTL.
4. `lastError` sigue siendo el diagnóstico que pinta el overlay (`Red: ...`).

**Aceptación:** `assembleDebug --offline` OK · arnés Java 39/39 · dispositivo de prueba: el
overlay deja de mostrar `Red: timeout` y llega contenido (`Red: OK · N vías` o similar); si
no hay red, el mensaje sigue siendo honesto y **no** se pinta terreno.

**Riesgo / rollback:** ZIP del archivo. Si el espejo devuelve HTML, el parseo ya devuelve
lista vacía sin romper. Revert = restaurar 1 archivo.

---

## Slice 2 — Fuera el terreno inventado + celdas cuadradas (`GBMapRenderer.java`, `MainActivity.java`)

**Archivos:**
- `app/src/main/java/com/gpsquest96/render/GBMapRenderer.java`
- `app/src/main/java/com/gpsquest96/ui/MainActivity.java`
- *(se elimina)* `app/src/main/java/com/gpsquest96/render/GBTileRenderer.java`

**Cambio:**
1. **Eliminar** `generateFallbackGrid()` y todo uso de `java.util.Random`: sin OSM,
   `draw()` devuelve `false` y no pinta nada.
2. **Celdas cuadradas:** el ancho de celda en NDC se deriva del alto para que
   `ancho_px == alto_px` con la relación de aspecto real de la superficie; la rejilla se
   centra en pantalla. Sin deformar el tile.
3. **Mapeo terreno→tile** en una tabla única y documentada (`TILE_FOR_CELL`), sin
   constantes dispersas; comentario explícito de que es cromo UI y se valida en el
   dispositivo de prueba.
4. `MainActivity.onDrawFrame`: el *else* del mapa deja de dibujar pasto procedural y
   damero; queda el `glClearColor` neutro. Se retiran `grassRenderer`, `drawCheckerboard`,
   `buildCheckerboard` y el import.

**Aceptación:** `assembleDebug --offline` OK · host 150 + 39 · dispositivo de prueba: sin OSM se ve lienzo
liso (cero camuflaje); con OSM, celdas cuadradas y terreno legible. `grep -i "Random"` en
`render/` vacío.

**Riesgo / rollback:** ZIP de los 3 archivos. Si el lienzo liso molesta, revert de 1 archivo.

---

## Slice 3 — Sprite OW estable (`SpriteExtractor.java`)

**Archivo:** `app/src/main/java/com/gpsquest96/render/SpriteExtractor.java`

**Cambio:** publicar un candidato solo tras N ticks consecutivos con el mismo `base`
(histéresis ya existente, revisar umbral) y rechazar candidatos cuyo `hits` se desborde
(falso positivo por tile de fondo). El sprite expuesto al mapa no cambia hasta confirmarse.

**Aceptación:** `assembleDebug --offline` OK · log del dispositivo de prueba: `base=` estable durante la caminata
(≤ 2 cambios en 30 s) y el sprite dibujado coincide con el jugador del frame nativo.

**Riesgo / rollback:** si el umbral congela el sprite, revert de 1 archivo.

---

## Puertas del dispositivo de prueba

- **G1 (Slice 1):** red real → `Red:` sin `timeout`.
- **G2 (Slice 2):** mapa con celdas cuadradas + lienzo liso sin datos.
- **G3 (Slice 3):** sprite estable en caminata de 30 s.

## Fuera de alcance

- PPU / render del modo a color (verificado sano), audio/APU, spawns (009), gating (005), post-game.
- Cualquier patrón procedural como sustituto de datos reales.

## Desvíos registrados (2026-10-30)

1. **Slice 2 — fuente del mapa.** El plan hablaba de mapear terreno→tile con `TILE_FOR_CELL`
   usando tiles del atlas de los datos del juego original del usuario. Evidencia
   (`atlas-vram.png`) mostró que la VRAM viva contiene
   fuentes/UI en el instante del muestreo: usar esos tiles habría pintado glifos como terreno.
   Se pinta cada celda OSM con un color plano documentado (`colorForCell`): cromo de UI, sin
   contenido inventado. Mismos gates G2.
2. **Slice 3 — alcance real del fix.** La histéresis sola no bastaba: el jugador del juego
   del usuario vive en **VRAM banco 1** (atributo OAM `0x08`) y el código leía banco
   0/`VBK`. Se añadieron
   `Mmu.readVramBlock` y `Mmu.readObjPaletteBlock` (JNI, solo lectura, sin tocar registros),
   banco/paleta por atributo en `SpriteExtractor` y los buffers en `MainActivity`. Archivos
   extra respecto al plan: `native_bridge.cpp`, `EmulationPipeline.java`, `MainActivity.java`.
   Gate G3 verificado en el dispositivo de prueba (base estable en banco 1 y silueta igual
   al frame de la PPU).
