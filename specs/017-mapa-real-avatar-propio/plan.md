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

**Estado: PENDIENTE.**

**Objetivo:** El mapa base son tiles raster reales de OpenStreetMap (slippy map, `tile.openstreetmap.org`), con caché en disco y atribución "© OpenStreetMap contributors" visible.

**Archivos previstos**
- Nueva clase de descarga/caché de tiles (sin dependencias Gradle nuevas; `HttpURLConnection` o el cliente que ya usa la app).
- `MainActivity.java` / `GameRenderer`: capa de tiles bajo el avatar y los POIs.
- UI: texto de atribución visible en la vista de mapa.

**Tareas**
1. Descarga de tiles por z/x/y con caché en disco (sin descarga masiva, User-Agent propio).
2. Dibujo del tile en el quad del mapa con el zoom actual.
3. Atribución visible.
4. Host tests donde aplique; build en PC + prueba en POCO con y sin red.

**Criterio de salida:** En el POCO con red se ve el mapa real de la ciudad con el avatar encima; sin red y sin caché, el raster pixel art sigue como respaldo (D3).

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
