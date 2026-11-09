# plan.md — 002 Composición de Mapa con Tiles del Atlas VRAM (B1-2)

Estado: CERRADO 2026-10-19. Slices 1, 2 y 3 ACEPTADOS de punta a punta en POCO X7 Pro (zero Shader rechazado/GLAudit, mapa compuesto con tiles de VRAM viva extraídos de la ROM).

## Slice 1 — Subida de Textura Atlas a OpenGL

- Archivos: `app/src/main/java/com/gpsquest96/ui/MainActivity.java` (GameRenderer), `app/src/main/java/com/gpsquest96/render/TileAtlas.java` (si hace falta exponer el buffer).
- Hacer: Crear textura 2D en GameRenderer con los 384 tiles × 8×8 píxeles del atlas. Subida thread-safe desde worker (volatile) en `onDrawFrame`. Cero asignaciones en el loop GL.
- Aceptación: `assembleDebug` verde + textura atlas subida sin errores GL en logcat.
- Riesgo: Formato de píxel del atlas (RGBA vs paleta) → verificar con `TileAtlas.snapshot()` existente.
- Puerta POCO: no (solo compilación).

## Slice 2 — Mapeo Terreno→Tile y Composición por Celda

- Archivos: `app/src/main/java/com/gpsquest96/render/GBMapRenderer.java`.
- Hacer: Definir constantes T_CALLE, T_EDIFICIO, T_PASTO, T_AGUA (índices del atlas). Actualizar `draw()` para muestrear la textura del atlas por celda según el tipo de terreno OSM. Fallback: tile 0 si atlas no disponible.
- Aceptación: `assembleDebug` verde + celdas compuestas con tiles del atlas en POCO.
- Riesgo: Índices T_* que no se vean bien → ajuste de constantes tras verificación visual (no de canon).
- Puerta POCO: sí (foto del mapa con celdas compuestas + `tiles:M>0`).

## Slice 3 — Verificación en POCO X7 Pro

- Archivos: ninguno (observación).
- Hacer: Cargar Azul, abrir engrane, verificar `tiles:M>0`, foto del mapa en modo Mapa con celdas compuestas por tiles reales del atlas.
- Aceptación: Foto con celdas visibles compuestas (no colores planos) + `tiles:M>0` + cero caídas de frames + cero FATAL.
- Puerta POCO: sí.

## Cierre

- `reviewer`: veredicto contra spec 002 + este plan. Sin APROBADO suyo no hay B2/B4 con este renderer.

## Fuera de alcance (rechazar si aparece)

Spawns, gating por flags, sprite OW (B4), GBA, post-game, assets propios, cambio de paleta del atlas, mapeo por contenido semántico de tiles (el mapeo es por tipo OSM, no por análisis de píxeles).
