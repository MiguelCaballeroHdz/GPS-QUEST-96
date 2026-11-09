# plan.md — 004 Mapeo global dinámico y Ruta Maestra

Estado: **APROBADO 2026-11-06.** **Slices 0, 1 y 2 HECHOS 2026-11-07** (evidencia:
`memory/archivo/estado_actual-2026-11.md` §11.8 y §11.9). Reemplaza al plan del 2026-10-28 (clasificador +
atlas + determinismo), que queda **superado**: su alcance no incluía ruta, ancla, nodos
tipados ni validación de los datos del juego.

Spec: `specs/004-mapeo-global-dinamico/spec.md` (APROBADO 2026-11-06).

Referencias de decisiones: §2 (D1-D3) y §12 (D-01…D-05) de la spec.

## Reglas del plan

- Un slice por turno, con `./gradlew assembleDebug --offline` → `EXIT=0` al cerrar.
- El `BUILD_TAG` se cambia **al final** del slice, en el mismo turno en que entra el
  contenido; el hash y la fecha se capturan **después** del rebuild.
- Criterios de host (inspección/compilación) se cierran en el turno; los de POCO se
  marcan "pendiente de dispositivo" y no bloquean el cierre (precedente 015 B5).
- Ningún slice introduce contenido inventado: sin POI compatible → nodo sintético
  declarado, nunca terreno falso.

## Orden de slices

### Slice 0 — Esquema de ruta y validación de datos

- **Objetivo:** fijar el contrato de datos antes de generar nada.
- **Archivos:** nuevo modelo de ruta (Java, paquete `gps/route` o equivalente) + esquema
  JSON versionado.
- **Tareas:**
  - Definir `route_[rom_key].json`: `schema_version`, `rom_key`, `anchor`, `nodes[]`.
  - Validar `action` contra el enum de ruta (`walk`, `enter`, `talk`, `use`, `battle`, `wait`).
  - Rechazar nodo `blocked` sin predicado de desbloqueo.
  - Rechazar JSON con `schema_version` desconocida, `rom_key` vacío o nodos sin `index`.
- **Criterios:** §7.4 (nodo bloqueado sin predicado → rechazo con motivo) y §7.8 (`EXIT=0`).
- **No incluye:** lectura OSM, generación, UI.
- **Estado: HECHO 2026-11-07** (absorbido por el slice 2): `Route` / `RouteNode` /
  `RouteFormatException` con esquema v1 y rechazo con motivo (esquema desconocido,
  `rom_key` vacío, índice fuera de secuencia, acción desconocida, `blocked` sin predicado,
  destino a medias). Cubierto por `RouteHostTest` (44 comprobaciones).

### Slice 1 - OSM POI-first (query, clasificación y poda)

**Estado: HECHO 2026-11-07.** Host test 81 comprobaciones `EXIT=0`; build `EXIT=0`; APK
`A67CDF78…875DC`; POCO `Red: OK 278 elem` (antes 198).

- **Objetivo:** que `tourism`/`amenity`/`historic` lleguen de verdad y que la clasificación
  no oculte un POI bajo `building`.
- **Archivos:** `app/src/main/java/com/gpsquest96/gps/OsmDataFetcher.java`.
- **Tareas:**
  - Query Overpass: pedir `node` **y** `way` para `amenity`, `tourism` e `historic`
    (hoy solo hay `node[shop]`; una farmacia es nodo y nunca llega).
  - Reordenar `classify()`: `amenity` → `tourism`/`historic` → `shop` → `water` → `park`/
    `wood` → `highway` → `building` como último recurso.
  - Podar del enum OSM los valores sin uso y sin dominio: `GYM`, `LAB`, `POKESHOP`,
    `CAVE`, `HOME`, `WATERZONE`, `GOODS`. Quedan 8: `HIGHWAY, PARK, WOOD, BUILDING,
    WATER, SHOP, TOURISM, AMENITY`.
  - `historic=*` se clasifica como `TOURISM` (monumento, castillo, ruinas).
  - Resolver el `historic` de la query sin duplicar nodos ya devueltos por otra clave.
- **Criterios:** hospital (`building=yes` + `amenity=hospital`) → `AMENITY`; monumento
  (`historic=monument`) → `TOURISM`; cero constantes con 0 usos; `EXIT=0` con hash y
  fecha; en POCO `Red: OK n elem` con más POIs que hoy.
- **Cierre:** `BUILD_TAG` → `2026-11-07+spec004-poi-slice1` **después** de que el código
  compile; luego rebuild, hash, instalar y log.
- **No incluye:** ancla, generación de ruta, UI de nodos, `RADIUS_M` por corredor.

### Slice 2 — Ancla confirmada y generación POI-first

- **Objetivo:** producir una ruta determinista a partir de un ancla confirmada.
- **Archivos:** modelo de ruta, `OsmDataFetcher`, UI de confirmación de ancla.
- **Tareas:**
  - Ancla: exterior, precisión ≤ 20 m, confirmación explícita, re-anclable solo hasta
    resolver el nodo 0; guardar fecha y precisión.
  - Asignación POI-first por afinidad de categoría; respaldo sintético a distancia fija
    (8 m/metatile, §5.2) cuando no hay POI compatible.
  - Nunca asignar nodo sobre `WATER`.
  - `RADIUS_M` deja de ser constante global: pasa a parámetro de corredor/tramo de consulta.
  - Determinismo: mismo `rom_key` + mismo ancla → misma ruta.
- **Criterios:** §7.1, §7.2 y §7.4 (host); §7.8 queda pendiente de calle.
- **Depende de:** slice 1.
- **Estado: HECHO 2026-11-07.** Host `RouteHostTest` 44 comprobaciones (`EXIT=0`) y build
  `EXIT=0`; en dispositivo `ANCLA OK 19.562807,-96.917928 ±3 m rom_key=PM_CRYSTAL-v0` y
  ruta final de **180 nodos** (2 POI, 178 sintéticos, máximo **249.1 m** del ancla).
  Evidencia: `tools/poco-capture/20261107-slice2/`.
- **Archivos nuevos:** `route/Route.java` (esquema v1, `MAX_NODES=500`),
  `route/RouteNode.java` (acción, POI, radio, `dest`, `blocked`/`unlock`),
  `route/RouteFormatException.java` y `route/RouteGenerator.java`.
- **Generación:** `METATILE_M=8.0`, `NODE_RADIUS_M=15.0`, `DEFAULT_NODE_TARGET=180`,
  `MAX_ANCHOR_ACCURACY_M=20.0`, `MAX_POI_RADIUS_M=250.0`,
  `MAX_SYNTHETIC_RADIUS_M=250.0`, `WATER_MARGIN_M=10.0`. POI por afinidad (`actionFor`),
  respaldo sintético a 8 m/metatile y ningún nodo sobre agua.
- **Consulta:** `OsmDataFetcher.requestArea(lat, lon, radiusM)` y
  `requestCorridor(lat, lon, radiusM, spanM)`. `RADIUS_M=150` queda como defecto, no como
  única constante; la clave de caché incluye radio/corredor.
- **UI:** `confirmAnchorFlow()` exige fix exterior y precisión ≤20 m, guarda fecha y
  precisión; `regenerateRouteIfAnchored()` escribe
  `filesDir/routes/route_<rom_key>.json` y publica `Ruta: N nodos (M POI)`.
- **Mapa:** `GBMapRenderer.setRouteNodes()` + tipo `NODE` (prioridad 8, por encima de
  agua/amenity/tourism/calle); `kindToCell` ya pinta `AMENITY` y `TOURISM`.
- **Defecto corregido en el slice:** la primera ruta salió con 111 nodos, todos POI y
  hasta **4.1 km** del ancla (sin tope de radio). Con `MAX_POI_RADIUS_M=250` y tramos
  `walk` de 8 m quedó en 180 nodos, 2 POI y máximo 249.1 m.
- **Follow-up 2026-11-08 (radio):** la geometría se recorta a la unión de círculos de la
  consulta (una vía ya no entra completa hasta 1,1 km), el acumulador poda a 1 km del
  centro consultado, el corredor 150/120 se pide en cada actualización y el seed sin fix
  usa el ancla antes que el fallback de Xalapa. En POCO: 180 nodos, 2 POI, máximo
  249,4 m; raster de 159 nodos (antes 1290).
- **Follow-up 2026-11-08 (mapa):** la rejilla pasa de 12×27 celdas de 4,4 m (53×119 m) a
  63×139 celdas de 8 m (504×1.112 m) en la clase pura `OsmRasterGrid`; así todo el radio
  de trabajo de 250 m entra en pantalla y los POIs de 217/239 m se pintan. El buffer GL
  se reutiliza (spec 002 RF-02). En POCO: 619→676 celdas pintadas (antes 13), 605 de
  calle, 12 de amenity, 1 de comercio y 70 de nodo de ruta (antes 3), a 59,7 fps.
  Pendiente: cubrir también los diagonales del corredor (hoy el fetch llega a ~208 m en
  diagonal y a ~270 m en los ejes).

### Slice 3 — Doble validación en modo lectura

- **Objetivo:** medir las tres señales sin mover nada.
- **Archivos:** lectura de estado del emulador (JNI), `GameEngine`.
- **Tareas:**
  - Snapshot de posición ROM en VBlank; comparación por fix GPS.
  - Loguear las tres señales (llegada / consumo / destino) y marcar lecturas dudosas.
  - Cero escrituras: no avanza `posicion`.
- **Criterios:** §7.9, §7.10 y §7.11 en una sesión de calle.
- **Depende de:** 016 (identidad de mapa por perfil) para no leer offsets fijos de Gen 2.

### Slice 4 — Avance monotónico, `resueltos[]` y re-enganche

- **Objetivo:** progreso correcto sin deshacer eventos.
- **Archivos:** modelo de ruta + persistencia (SQLite) + `GameEngine`.
- **Tareas:**
  - `posicion` puede bajar; `resueltos[]` solo crece.
  - Re-enganche solo hacia atrás, nunca incrementa `posicion`; umbral 120 m.
  - Anti-skip: sin cambio de posición ROM, no hay avance.
- **Criterios:** §7.5 y §7.6.
- **Depende de:** slice 3.

### Slice 5 — `enter`/`talk` automáticos

- **Objetivo:** puertas y NPCs resueltos dentro del radio, sin cambiar de modo.
- **Archivos:** `GameEngine`, `MainActivity`, puente de entrada.
- **Tareas:**
  - `enter`/`talk` se disparan dentro del radio; `use`/`battle`/`wait` siguen yendo a
    Modo Sofá.
  - Anti-atasco acotado (3 × 300 ms) solo en estado `MAP`, abortando ante cambio de pantalla.
- **Criterios:** §7.12.
- **Depende de:** 016 (estado del emulador: MAP vs diálogo/menú/batalla).

### Slice 6 — Export/import y válvula de escape

- **Objetivo:** mover perfiles sin ejecutar contenido ajeno y no dejar al usuario atrapado.
- **Archivos:** `route/RouteBundle.java` + UI SAF de `MainActivity` (layout y strings).
- **Tareas:**
  - ZIP validado (esquema, versión, tamaño, rutas sin `..`), extracción en memoria,
    checksum opcional, nunca ejecución.
  - Rechazo explícito si `rom_key` no coincide.
  - Salida manual por nodo bloqueado: saltar con confirmación y registro.
- **Criterios:** §7.7 + R10.
- **Estado 6a (export/import): HECHO 2026-11-07.** `RouteBundle` con formato
  `gpsquest96.route` v1 y entradas `manifest.json` + `route.json`; topes de 64 KB y
  2 MB, máximo 8 entradas; rechazo de `..`, rutas absolutas, `\` y `:`, y de
  cualquier entrada desconocida; checksum SHA-256 opcional; `rom_key` obligatorio
  y comparado con la partida cargada. `RouteBundle.save()` escribe
  `route_<rom_key>.json` con `.tmp → .bak → rename`. UI: “Exportar/Importar ruta
  (.ruta)”; al importar se actualiza el ancla confirmada y los nodos se publican en
  el mapa. Host `RouteBundleHostTest` **26 comprobaciones `EXIT=0`** (round-trip,
  otro `rom_key`, formato/versión, checksum, traversal, entrada desconocida, tope
  de tamaño y guardado atómico). `BUILD_TAG 2026-11-07+spec004-slice6`; APK
  `F532C426…0E7AEA8`. **El ZIP solo se parsea: nada se ejecuta.**
- **Estado 6b (válvula de escape / R10): pendiente.** Depende de `posicion` y
  `resueltos[]` (slice 4): sin estado de nodo bloqueado no hay nada que saltar ni
  que registrar. El §7.7 de la tabla corresponde a `enter` (slice 5) y no se cierra
  con este paquete.

## Trazabilidad

| Slice | Spec § | Criterios | Estado |
|---|---|---|---|
| 0 | 3.2, 6 | 4, 8 | HECHO 2026-11-07 (validación con motivo, 44 checks) |
| 1 | 3.5, 4/R2 | 3, 8 + POCO | HECHO 2026-11-07 (host 81 + POCO 278 elem) |
| 2 | 3.1, 4/R1, 5.2 | 1, 2, 4 (host) · 8 (dispositivo) | HECHO host 2026-11-07; criterio de dispositivo pendiente |
| 3 | 4/R3, 5.3 | 9, 10, 11 | pendiente |
| 4 | 3.3, 4/R5-R8 | 5, 6 | pendiente |
| 5 | 3.4, 4/R7, R9 | 12 | pendiente |
| 6 | 4/R10, 6 | 7 | 6a export/import HECHO 2026-11-07 (host 26); 6b válvula pendiente del slice 4 |

## Riesgos de secuencia

- Los slices 0-2 son los únicos que **no** dependen de 016; por eso van primero.
- Los slices 3-5 quedan bloqueados hasta que 016 entregue identidad de mapa por perfil.
- El criterio de POI real → Punto de Restauración necesita calle real: se cierra con el
  dispositivo, no con inspección.
- Hasta que exista `resueltos[]` (slice 4), `route_nodo0_resuelto` no tiene escritor y el
  ancla sigue siendo re-anclable.
