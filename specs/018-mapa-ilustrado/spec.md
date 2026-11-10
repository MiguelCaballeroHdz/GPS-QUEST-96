# spec.md — 018 Mapa ilustrado

Estado: APROBADA — aprobada por Miguel el 10 de noviembre de 2026 (decisión en WhatsApp: "Para no seguir implementando slices y slices, por que no mejor sustituimos el slice de pixel art para sepultarlo para siempre y dejamos esta nueva idea de mapa, fíjate que sin nombres está bien, solo es un juego, no hay que llenarlo de información innecesaria").

Referencia de estilo: `specs/018-mapa-ilustrado/ref/mapa-xalapa-referencia.jpg` — mapa turístico ilustrado de Xalapa enviado por Miguel: pergamino de fondo, calles en tono arena, parques verdes suaves, agua azul, iconos de edificios emblemáticos (Catedral, Parque Juárez, Museo de Antropología, Estadio Xalapeño, UV, Los Lagos, Macuiltépetl), escala y brújula. La imagen NO está a escala y NO es el mapa en vivo: es la referencia de estilo. El mapa del juego se dibuja con los datos OSM reales, en ese estilo.

## 1. Objetivo

El mapa deja de ser pixel art (celdas) y deja de ser tiles raster: pasa a ser un mapa ilustrado dibujado con la geometría vectorial OSM que la app ya descarga. Misma geografía real, a escala, sigue al jugador, offline con la caché de vector del corredor. Un solo slice: el pintor nuevo + la sepultura del pixel art y de las tiles.

## 2. Decisiones

- **D1 — El pintor ilustrado es LA base del mapa.** Estilo de la referencia: fondo pergamino, calles en tono arena (líneas gruesas, uniones redondeadas), parques verde suave, agua azul, edificios como bloques tenues, iconos dibujados (sin texto) para lugares emblemáticos según POI (tourism/amenity/historic: museo, servicio, comercio, árbol, gota). **CERO texto en el mapa: ni nombres de calles ni etiquetas de POI** (decisión explícita de Miguel: "solo es un juego"). Brújula dibujada (flecha al norte, sin texto) en una esquina; la barra de escala queda fuera (sin números no aporta nada).
- **D2 — El pixel art queda SEPULTADO.** Se borra con `git rm` el camino de render raster (`GBMapRenderer` como pintor de celdas + `OsmRasterGrid`) y todo su cableado. La CAPA DE DATOS se queda: la descarga Overpass + la clasificación alimentan al pintor. Los nodos de ruta se dibujan ENCIMA de la base (marcador dorado), como hacía el raster con prioridad máxima. La corrección pendiente de `kindToCell` para TOURISM/AMENITY queda MOOT: ese código se borra.
- **D3 — Las tiles OSM también se retiran (una sola capa base, sin excepciones).** Con el ilustrado de base, las tiles nunca se pedirían para zonas nuevas (la caché no se llenaría) y sus letreros densos son justo la "información innecesaria" que Miguel rechazó. Se borra con `git rm` `OsmTileManager`, `OsmTileLayer`, `TileMath` y su cableado (incluida la petición de tiles en `requestMapTiles`/`applyMapZoom`, que pasa a llamarse `requestMapData`).
- **D4 — La atribución "© OpenStreetMap contributors" SE QUEDA visible** (los datos son de OSM; la atribución es por los datos, no por las tiles). El `TextView` existente queda siempre visible; muere el `AttributionListener` que la encendía solo con tiles.
- **D5 (offline) — Sin datos de vector (ni caché ni red), el mapa muestra pergamino + avatar + nodos**, sin crashear y sin raster de respaldo. La caché del corredor sigue siendo el mecanismo offline (igual que antes: el raster también dependía de ella).

## 3. Por qué no es "semanas como el pixel art"

Lo que atoró el pixel art no fue pintarlo, fue todo lo de abajo: la geometría (corredor, poda a 1 km, diagonales), el rendimiento, la clasificación, y que con celdas de 8 m el mapa nunca se iba a parecer a la ciudad. Todo eso ya existe y se reutiliza: los datos OSM ya bajan, ya están clasificados, la proyección ya está, la caché ya está. Lo que cambia es CÓMO se pinta. Y sin nombres de calles no hay que resolver el render de texto en GL, que era la parte cara.

## 4. El pintor (implementación)

- **Clase pura** `render/IllustratedMap.java` (Java puro, sin OpenGL, testeable en host): construye la escena (triángulos con color) desde `List<OsmElement>` + `List<RouteNode>`.
  - Escena en **metros** relativos al centro (este = +x, norte = +y): no depende del viewport ni del zoom y solo se reconstruye cuando cambian los datos.
  - Misma escala que el raster: 1 unidad = 8 m (`SCALE_METERS`), misma cobertura a zoom 1 (504x1112 m). El avatar sigue midiendo 1 unidad de ancho; el mapa no cambia de escala al enterrar el raster.
  - Áreas cerradas (anillo primero ≈ último): relleno por ear clipping (sin agujeros; abanico desde el centroide si el polígono degenera). Color por kind (§5).
  - Calles (`HIGHWAY` abierta): quads gruesos de 7 m por segmento + discos en cada vértice (uniones redondeadas y cabos redondos).
  - Áreas abiertas (anillo recortado por el corredor): su borde en el color del relleno (3 m), para no perder el área.
  - POI puntual: icono por kind (§5). POI de área (tourism/amenity/shop): relleno + icono en el centroide.
  - Nodos de ruta: disco dorado + anillo oscuro (9 m), encima de todo lo demás.
  - Recorte: ventana fija de ±1200 m (cubre el zoom mínimo 0.5x: 1008x2224 m); el resto lo recorta la GPU. Regla de visibilidad: un vértice dentro, un segmento que cruza, o la ventana entera dentro del polígono.
  - Nada inventado: solo se dibuja lo que trae un elemento OSM o un nodo de ruta.
- **Renderer GL** `render/IllustratedMapRenderer.java`: shader propio de triángulos de color (posición + color, sin texturas). Aplica por frame la escala (viewport + zoom) y el desplazamiento (slide continuo por lat/lon en vivo), igual que hacía el raster. Dibuja el fondo pergamino y la brújula (NDC fijo, sin offset) con el mismo programa. `draw()` devuelve true siempre que esté inicializado (pergamino siempre, con o sin datos).
- **Paleta pergamino** (referencia): pergamino #F2E8CE, calle #C9B18C, parque #A9C68B, bosque #8FB573, agua #7FB2D9, edificio #DCCFA8, comercio #E3C98F, turismo #E8D9A0, amenity #E5C5A3, tinta #33475B, medallón #F7F0DC, nodo #F2C230 + anillo #8A6D1B. Todos los vértices opacos.
- **Iconos** (geometría, sin texto): museo = frontón + cuerpo + base; servicio = cruz; comercio = casa; árbol = copa + tronco; gota = disco + punta. Sin tags en los elementos (el fetcher solo guarda el kind), el icono se decide por kind: tourism → museo, amenity → servicio, shop → comercio, parque/bosque puntual → árbol, agua puntual → gota. Calles y edificios no llevan icono.
- **Cableado** (`MainActivity`/`GameRenderer`): el campo `gbMap` pasa a `map` (`IllustratedMapRenderer`); `feedOsmData`/`feedRouteNodes`/`setMapZoom`/`getMapZoom`/`cellPixels()` conservan su API (la escala no cambia); `requestMapTiles` pasa a `requestMapData` (centro + datos OSM, sin tiles); mueren `feedTile`/`drainTiles`/`AttributionListener`/`updateAttribution`/`tileManager`; la subida del atlas VRAM se conserva (diagnósticos; el mapa ya no lo muestrea).

## 5. Tablas

### 5.1 kind → relleno

| Kind OSM | Relleno |
|---|---|
| HIGHWAY | calle (línea, no relleno) |
| PARK | verde suave |
| WOOD | verde bosque |
| WATER | azul |
| BUILDING | bloque tenue |
| SHOP | arena comercio |
| TOURISM | arena turismo |
| AMENITY | arena servicio |

### 5.2 kind → icono

| Kind OSM | Icono |
|---|---|
| TOURISM | museo (siempre: puntual o centroide de área) |
| AMENITY | servicio (siempre) |
| SHOP | comercio (siempre) |
| PARK / WOOD | árbol (solo puntual) |
| WATER | gota (solo puntual) |
| HIGHWAY / BUILDING | ninguno |

## 6. Sepultura (git rm)

| Archivo | Por qué muere |
|---|---|
| `render/GBMapRenderer.java` | pintor de celdas (pixel art) |
| `render/OsmRasterGrid.java` | rejilla de celdas 63x139 |
| `gps/OsmTileManager.java` | descarga de tiles |
| `render/OsmTileLayer.java` | capa de tiles |
| `gps/TileMath.java` | matemática slippy (solo la usaban las tiles) |
| `tools/host-test/java/test/hosttest/TileMathHostTest.java` | su clase murió |
| `tools/host-test/java/test/hosttest/RasterGridHostTest.java` | su clase murió |

La CAPA DE DATOS se queda: `OsmDataFetcher` (Overpass + caché 7 días + clasificación), `OsmAccumulator`, `OsmClassifyHostTest`. El avatar propio (017 Slice 1) no se toca. `SpriteExtractor`/`TileAtlas` no se tocan (la subida del atlas queda para diagnósticos; su limpieza es otro trabajo).

## 7. Limitación conocida — borrado en GitHub

El conector de GitHub aquí solo sube archivos con contenido (snapshot de texto); **NO puede borrar archivos**. El `git rm` local es real en la rama local `018-slice-1`, pero en origin los 7 archivos viejos seguirán listados como código muerto sin referencias hasta que Miguel los borre en su PC con `git rm` (comandos en el reporte final de la entrega). No se intentó ningún rodeo para borrar en remoto.

## 8. Fuera de alcance

- Nombres de calles o etiquetas (decisión explícita: cero texto).
- Barra de escala (sin números no aporta nada).
- Cambiar la pantalla del emulador (BATTLE/pantalla completa).
- Cambiar la capa de datos OSM (query Overpass, caché, clasificación).
- Rediseñar el avatar de Miguel.
- Audio APU (deuda abierta).

## 9. Criterios de aceptación (Slice 1)

1. `IllustratedMapHostTest`: 84 comprobaciones nuevas, 0 fallos; suite host completa 552 comprobaciones, 0 fallos (604 − 93 TileMath − 43 RasterGrid + 84 nuevas).
2. Cero referencias a `GBMapRenderer`, `OsmRasterGrid`, `OsmTileManager`, `OsmTileLayer`, `TileMath`, `tile.openstreetmap` en `app/src` (verificado con grep).
3. Una sola capa base: el mapa ilustrado. Sin datos de vector, pergamino + avatar + nodos (D5).
4. Cero dependencias Gradle nuevas (sin cambios en `build.gradle`).
5. `.\gradlew.bat clean assembleDebug --offline` → `EXIT=0` en la PC de Miguel (pendiente: `assembleDebug` no corre en la VM).
6. En el POCO con red: mapa estilo pergamino con calles/parques/agua, iconos sin texto, avatar encima, nodos de ruta, atribución visible. Sin red con caché: el mismo mapa. Sin red sin caché: pergamino + avatar + nodos (pendiente: verificación de Miguel).
7. La pantalla del emulador no cambia (sin toques al camino PPU).
