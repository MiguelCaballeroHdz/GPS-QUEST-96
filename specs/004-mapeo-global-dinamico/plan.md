# plan.md — 004 Mapeo global dinámico y Ruta Maestra

Estado: **APROBADO 2026-11-06.** **Slices 0, 1 y 2 HECHOS 2026-11-07** (evidencia:
`memory/archivo/estado_actual-2026-11.md` §11.8 y §11.9). Reemplaza al plan del 2026-10-28 (clasificador +
atlas + determinismo), que queda **superado**: su alcance no incluía ruta, ancla, nodos
tipados ni validación de los datos del juego.

Spec: `specs/004-mapeo-global-dinamico/spec.md` (APROBADO 2026-11-06).

Referencias de decisiones: §2 (D1-D3) de la spec. Corrección 2026-11-10:
el plan citaba "§12 (D-01…D-05) de la spec", pero la spec aprobada tiene
9 secciones: no existe §12. Las D-01…D-05 del archivo pertenecen a la
spec 015, no a la 004.

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
  Follow-up 2026-11-09: diagonales cubiertas (9 centros: ancla + 4 cardinales + 4
  diagonales a spanM; alcance ~270 m también en diagonal, antes ~208 m). Clave de
  caché nueva `corr9_` (las entradas viejas `corr_` de 5 centros no se reutilizan).
  Host `OsmClassifyHostTest` 55 comprobaciones (19 nuevas de geometría del corredor).
  `BUILD_TAG 2026-11-09+spec004-corredor-diagonal`. Verificación en POCO pendiente.

### Slice 3 — Doble validación en modo lectura

- **Objetivo:** medir las tres señales sin mover nada.
- **Archivos:** `route/RouteValidator.java` (nuevo, Java puro), `GameEngine`
  (cableado), `ControlModeManager` + `LocationService` (precisión del fix
  y contador de fixes), host test `RouteValidatorHostTest`.
- **Tareas:**
  - Snapshot de posición ROM en VBlank; comparación por fix GPS.
  - Loguear las tres señales (llegada / consumo / destino) y marcar lecturas dudosas.
  - Cero escrituras: no avanza `posicion`.
- **Criterios:** §7.9, §7.10 y §7.11 en una sesión de calle.
- **Depende de:** 016 (identidad de mapa por perfil) para no leer offsets fijos de Gen 2.
- **Estado: HECHO 2026-11-10 (host; dispositivo pendiente).**
  `RouteValidator.evaluate(prev, cur, fix, node)` mide R3: llegada
  (haversine fix→nodo ≤ radio, 15 m §5.2), consumo (la posición ROM
  cambió entre snapshots) y destino (mapa + casilla del nodo). El snapshot
  es `nativeGetCurrentMap` (009 slice 2, `{grupo, mapa, y, x}`): no se
  añadieron offsets nuevos ni JNI nuevo. El snapshot se toma en el frame
  en que se procesa cada fix (no cada frame: el log nativo de 009-B2
  saturaría el log a 60 fps) y la comparación es por fix GPS
  (LocationService, 5 s / 5 m), con el fix REAL
  (`getRealLocation()`, nunca el simulado de Modo Sofá). La precisión del
  fix se plomó desde `LocationService` para marcar "fix impreciso".
  Lecturas dudosas marcadas: fix impreciso, precisión desconocida, sin
  fix, sin snapshot ROM, avatar sin cambio de posición ROM (bloqueado o
  parado), mapa incompatible y nodo sin destino declarado.
  **Sin destino no se inventa terreno:** hoy todos los nodos generados
  tienen `destMapId == NO_DEST`, así que la señal 3 queda
  `NOT_APPLICABLE` hasta que un slice posterior (o el volcado POCO, 005)
  declare destinos reales. La codificación `map_id = grupo * 256 + mapa`
  es provisional y debe confirmarse con el volcado POCO antes de que el
  slice 4 la use para avanzar `posicion`.
  Host `RouteValidatorHostTest`: **36 comprobaciones, `EXIT=0`** (llegada,
  consumo, destino, dudosas, cero escrituras: la ruta no cambia al
  evaluar). Suite Java completa: 257 comprobaciones, 0 fallos. Build
  `assembleDebug` NO verificado en esta VM (timeout de Maven
  documentado); queda para la PC de Miguel.
  `BUILD_TAG` sin tocar (el rebuild con hash/fecha se hace en su PC).
- **Discrepancia de numeración (anotada, no inventada):** el plan cita
  §7.9, §7.10 y §7.11, pero la spec aprobada §7 solo numera 8 criterios
  (1–4 host, 5–8 dispositivo). No existen §7.9–11 en la spec. Los checks
  de host que sí se verifican en este slice son: (a) las tres señales se
  loguean por fix, (b) cero escrituras a estado del juego y a `posicion`,
  (c) lecturas dudosas marcadas. Los criterios de dispositivo quedan
  "pendiente de sesión de calle en POCO" y NO bloquean el cierre
  (precedente 015 B5).

### Slice 4 — Avance monotónico, `resueltos[]` y re-enganche

- **Objetivo:** progreso correcto sin deshacer eventos.
- **Archivos:** modelo de ruta + persistencia (JSON) + `GameEngine`.
- **Tareas:**
  - `posicion` puede bajar; `resueltos[]` solo crece.
  - Re-enganche solo hacia atrás, nunca incrementa `posicion`; umbral 120 m.
  - Anti-skip: sin cambio de posición ROM, no hay avance.
- **Criterios:** §7.5 y §7.6.
- **Depende de:** slice 3.
- **Estado: HECHO 2026-11-10 (host; dispositivo pendiente).**
  `RouteProgress` (nueva, Java puro): evalúa solo el nodo en `posicion`
  por fix; avance con llegada + consumo + lectura no bloqueada
  (`Validation.blocked`: sin fix, fix impreciso o de precisión
  desconocida, cadena de snapshots rota); resolución con destino `MATCH`
  o `NOT_APPLICABLE` (hoy todos los nodos son `NO_DEST`: resolver =
  llegar + moverse; el `MATCH` se exigirá solo cuando 005 declare
  destinos, sin cambiar código); avance sin resolver con destino
  `MISMATCH`; re-enganche antes de evaluar, solo hacia atrás, al
  pendiente más alto dentro de 120 m con `map_id` compatible
  (`RouteValidator.mapIdOf`, función única, codificación provisional
  pendiente del volcado POCO); fin de ruta explícito (`posicion == N`,
  sin más evaluación). Persistencia en
  `filesDir/routes/progress_<rom_key>.json` con guardado atómico
  (`.tmp` → `.bak` → rename): no SQLite (sin tablas por diseño) ni
  SharedPreferences (dato por ruta, junto a su archivo). Cableado en
  `GameEngine.setRoute` (generar/importar/cargar ROM/arranque) con
  persistencia en cada cambio y aviso `onRouteNodeResolved`; en
  `MainActivity` el nodo 0 resuelto escribe por fin
  `route_nodo0_resuelto` (ancla inmutable, R1) y la línea de ruta muestra
  `pos N/M · resueltos K`. `setValidationRoute`/`setValidationNodeIndex`
  retirados (sin usos: la ruta nunca llegaba al motor en el slice 3).
  Host `RouteProgressHostTest`: **47 comprobaciones, `EXIT=0`**
  (monotonicidad, re-enganche, anti-skip, puerta de destino,
  persistencia y rechazos); `RouteValidatorHostTest` 43 (7 nuevas del
  flag `blocked`). Suite Java completa: **604 comprobaciones, 0 fallos**.
  Build `assembleDebug` NO verificado en esta VM (timeout de Maven
  documentado); queda para la PC de Miguel. `BUILD_TAG` sin tocar.
  **Dispositivo pendiente de sesión de calle en POCO** (criterios §7.5
  y §7.6) y del volcado POCO (005) para confirmar `map_id`.

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
| 3 | 4/R3, 5.3 | 9, 10, 11 (*) | HECHO host 2026-11-10 (36 checks); dispositivo pendiente de calle |
| 4 | 3.3, 4/R5-R8 | 5, 6 | HECHO host 2026-11-10 (47 checks); dispositivo pendiente |
| 5 | 3.4, 4/R7, R9 | 12 | pendiente (desbloqueado: dependía del 4) |
| 6 | 4/R10, 6 | 7 | 6a export/import HECHO 2026-11-07 (host 26); 6b válvula desbloqueada (el estado ya existe) |

(*) Discrepancia: la spec aprobada §7 solo numera 8 criterios; no existen §7.9–11. Los checks de host del slice 3 son: tres señales logueadas por fix, cero escrituras, lecturas dudosas marcadas. Dispositivo pendiente de calle.

## Riesgos de secuencia

- Los slices 0-2 son los únicos que **no** dependen de 016; por eso van primero.
- Los slices 3-5 quedan bloqueados hasta que 016 entregue identidad de mapa por perfil.
- El criterio de POI real → Punto de Restauración necesita calle real: se cierra con el
  dispositivo, no con inspección.
- `resueltos[]` existe desde el slice 4 (2026-11-10) y `route_nodo0_resuelto`
  ya tiene escritor (`onRouteNodeResolved` en MainActivity): el ancla deja
  de ser re-anclable en cuanto se resuelve el nodo 0.
