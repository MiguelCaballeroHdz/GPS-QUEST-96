# plan.md — 018 Mapa ilustrado

Estado: **APROBADO 2026-11-10** (decisión de Miguel en WhatsApp). Spec: `specs/018-mapa-ilustrado/spec.md`. Referencia de estilo: `specs/018-mapa-ilustrado/ref/mapa-xalapa-referencia.jpg` (binario, solo local: NUNCA va en un manifiesto de subida al conector, que no admite binarios).

Reemplaza el Slice 3 de la 017 (SUPERSEDED 2026-11-10, ver `specs/017-mapa-real-avatar-propio/plan.md`): la integración que faltaba se hace aquí, con el pixel art y las tiles sepultados en vez de integrados.

## Reglas del plan

- Un solo slice: pintor + sepultura. Sin slices de integración después.
- Ningún slice introduce contenido inventado: sin elemento OSM o nodo de ruta no hay dibujo (D5: pergamino vacío).
- Cero texto en el mapa (decisión de Miguel): no se implementa render de texto en GL en este slice ni en otro sin su aprobación explícita.
- `BUILD_TAG` sin tocar (el rebuild con hash/fecha se hace en la PC de Miguel).
- Los host tests que prueban una clase borrada se borran con ella (`TileMathHostTest`, `RasterGridHostTest`); el total de la suite baja y se reporta con honestidad.

## Orden de slices

### Slice 1 — Pintor + sepultura

- **Objetivo:** el mapa ilustrado es la única base; el pixel art y las tiles dejan de existir en el árbol.
- **Archivos:**
  - `render/IllustratedMap.java` (nuevo, Java puro): escena en metros, rellenos por ear clipping, calles como quads + discos, iconos por kind, recorte ±1200 m, misma escala que el raster (8 m).
  - `render/IllustratedMapRenderer.java` (nuevo, GL): shader de triángulos de color, escala + slide por frame, fondo pergamino + brújula en NDC.
  - `ui/MainActivity.java`: `gbMap` → `map`; `requestMapTiles` → `requestMapData`; mueren `feedTile`/`drainTiles`/`AttributionListener`/`updateAttribution`/`tileManager`; atribución siempre visible.
  - `res/layout/activity_main.xml`: `tv_osm_attr` siempre visible.
  - `tools/host-test/java/test/hosttest/IllustratedMapHostTest.java` (nuevo) + `run-java.ps1` (alta del test; baja de `TileMath` y `OsmRasterGrid`).
  - `git rm`: `GBMapRenderer`, `OsmRasterGrid`, `OsmTileManager`, `OsmTileLayer`, `TileMath`, `TileMathHostTest`, `RasterGridHostTest`.
  - `specs/018-mapa-ilustrado/spec.md` + `plan.md` + `ref/mapa-xalapa-referencia.jpg`; `ESTADO.md`; anotación de superseded en el plan de la 017.
- **Criterios:** spec §9.
- **Estado: HECHO en host (2026-11-10, rama `018-slice-1` desde `004-slice-4` / `db3bfdf`). Pendiente: build en PC + verificación en POCO.**
  `IllustratedMapHostTest`: 84 comprobaciones nuevas, 0 fallos. Suite host completa: 552 comprobaciones, 0 fallos (604 − 93 TileMath − 43 RasterGrid + 84 nuevas). Cero referencias a las clases sepultadas en `app/src` (grep verificado). Build `assembleDebug` NO verificado en esta VM (no corre aquí); queda para la PC de Miguel.

## Limitación conocida — borrado en GitHub

El conector de GitHub aquí solo sube archivos con contenido (snapshot de texto); **NO puede borrar archivos**. El `git rm` local es real en la rama local `018-slice-1`, pero en origin los 7 archivos viejos seguirán listados como código muerto sin referencias hasta que Miguel los borre en su PC con `git rm` (comandos en el reporte final de la entrega). No se intentó ningún rodeo para borrar en remoto.

## Trazabilidad

| Slice | Spec § | Criterios | Estado |
|---|---|---|---|
| 1 | D1–D5, §4–§6 | §9 (1–7) | HECHO host 2026-11-10 (84 checks nuevas, total 552); dispositivo pendiente |
