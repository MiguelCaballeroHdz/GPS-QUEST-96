# spec.md — 004 Mapeo global dinámico y Ruta Maestra

Estado: **APROBADO 2026-11-06.**

Aprobación sustentada en: decisiones D1-D3 de producto (POI-first con respaldo sintético, progreso monotónico, `enter`/`talk` automáticos) y las cinco respuestas de §12 refrendadas por el usuario.

## 0. Principio rector

**Los datos de juego disponen, el GPS propone.** Ningún desplazamiento físico adelanta contenido: el avance de la historia lo confirma el estado de los datos del usuario. La ruta no inventa eventos, no reubica puntos y no sobrescribe las probabilidades de encuentro del motor.

## 1. Objetivo

Convertir los puntos de interés reales (OSM) en locaciones del juego, en orden secuencial, de forma determinista por perfil y sin listas por ciudad. El jugador camina su ciudad; los datos del usuario ejecutan su historia.

## 2. Decisiones de producto ya tomadas

| # | Decisión | Consecuencia en esta spec |
|---|---|---|
| D1 | **POI-first**, con respaldo sintético | La ruta se ancla en POIs reales por afinidad; la distancia fija es solo el respaldo cuando no hay POI compatible |
| D2 | **Progreso monotónico** | La *posición* en la ruta puede retroceder; los eventos *resueltos* nunca se deshacen |
| D3 | **`enter`/`talk` automáticos** dentro del radio | Puzles y batallas van a Modo Sofá; puertas y NPCs se resuelven sin cambiar de modo |

## 3. Modelo conceptual

### 3.1 Ancla (Punto 0)

- Se **confirma** sobre el mapa; no se toma del primer fix.
- Requisitos: exterior, precisión GPS ≤ 20 m, confirmación explícita del usuario.
- Re-anclable **solo** hasta que el nodo 0 esté resuelto. Después es inmutable.
- Se guarda con fecha, precisión y coordenadas.

### 3.2 Nodo de ruta

Cada nodo declara:

| Campo | Contenido |
|---|---|
| `index` | Orden secuencial inmutable |
| `action` | `walk`, `enter`, `talk`, `use`, `battle`, `wait` |
| `destino` | `map_id` + casilla (x, y) del juego |
| `anclaje` | latitud, longitud y radio real |
| `poi` | Categoría OSM de origen, o `synthetic` |
| `unlock` | Predicado de desbloqueo (ver R4) |

### 3.3 Dos estados separados

| Estado | Puede retroceder | Qué representa |
|---|---|---|
| `posicion` | Sí | Dónde está el jugador en la secuencia |
| `resueltos[]` | **No** | Qué eventos ya ocurrieron |

### 3.4 Tipos de nodo

`walk` (andar), `enter` (auto-A en puerta dentro del radio), `talk` (auto-A encarando), `use` (objeto/habilidad), `battle`, `wait` (cutscene).

### 3.5 Tipos OSM (enum propio, separado del de ruta)

`HIGHWAY, PARK, WOOD, BUILDING, WATER, SHOP, TOURISM, AMENITY`.

## 4. Reglas funcionales

- **R1 — Anclaje:** Punto 0 confirmado; inmutable tras resolver el nodo 0.
- **R2 — Generación POI-first:** buscar POIs por afinidad de categoría en el radio de trabajo; asignar en orden secuencial a los nodos de la historia. Si no hay POI compatible, nodo sintético a distancia fija. Nunca sobre agua. La distancia se mide por **red de calles**, no en línea recta.
- **R3 — Doble validación (tres señales):**
  1. *Llegada*: el fix está dentro del radio del nodo.
  2. *Consumo*: la posición de los datos del usuario cambió (el avatar no está bloqueado).
  3. *Destino*: el motor alcanzó la casilla del nodo.
- **R4 — Desbloqueo declarado:** cada nodo `blocked` declara su predicado: bandera de estado con valor, tile transitable, o cambio de `map_id`. Sofá resuelve; GPS avanza.
- **R5 — Anti-skip:** si el motor no se movió, `posicion` no avanza aunque el GPS recorra kilómetros.
- **R6 — Re-enganche:** solo **hacia atrás**. Elige el nodo pendiente más alto dentro del radio y con `map_id` compatible. **Nunca incrementa** `posicion`.
- **R7 — Anti-atasco:** máximo 3 intentos de 300 ms en las cardinales del vector de ruta, **solo** en estado `MAP`. Aviso háptico una vez por nodo.
- **R8 — Monotonicidad:** `posicion` puede bajar; `resueltos[]` no.
- **R9 — Interacción:** `enter` y `talk` se ejecutan dentro del radio; `use`, `battle` y `wait` pasan a Modo Sofá.
- **R10 — Fin y válvula de escape:** al terminar la ruta, estado explícito de fin.

## 5. Unidades, umbrales y muestreo

### 5.1 Tres sistemas de coordenadas

| Sistema | Unidad | Fuente |
|---|---|---|
| GPS | metros | `LocationService` (5 s / 5 m) |
| Datos del usuario | metatile de 16×16 px | posición del jugador en WRAM |
| Mapa de la app | celda de 8 m (≈19,5 px en 1220×2712) | `GBMapRenderer` + `OsmRasterGrid` (spec 015 B2) |

### 5.2 Umbrales (configurables)

| Parámetro | Valor | Motivo |
|---|---|---|
| Escala metatile → metros | **8 m** | Fija la longitud real de la ruta |
| Radio de llegada | **15 m** | Tolerancia GPS urbana sin regalar el nodo |
| Nodos por perfil | **180** (rango 150-200) | Duración razonable de la historia |
| Precisión máxima del ancla | 20 m | Evita anclar con un fix degradado |
| Re-enganche máximo | 120 m | Evita saltos por drift |

## 6. Datos y persistencia

- `route_[game_key].json` con `schema_version`, `game_key`, `anchor` y `nodes[]`.
- `game_key` = código de juego + revisión de los datos del usuario.
- **Ciudad:** un perfil por ciudad. Cambiar de ciudad exige confirmación explícita del usuario.
- **Multi-Juego:** se almacenan varias rutas (una por `game_key`), pero solo hay **una ruta activa** a la vez.

## 7. Criterios de aceptación

### Verificables en host

1. Dos POIs de categoría distinta producen dos tipos de nodo distintos.
2. Mismo perfil y mismo ancla, dos arranques: misma ruta (determinismo).
3. Un establecimiento OSM clasifica correctamente en su categoría.
4. `./gradlew assembleDebug --offline` → `EXIT=0`.

### Verificables en dispositivo

5. Caminar 200 m sobre la ruta avanza exactamente N nodos.
6. Cruzar un nodo en vehículo no avanza `posicion`.
7. `enter` dentro del radio cambia de mapa sin intervención del usuario.
8. Un establecimiento real se mapea como Punto de Restauración por afinidad OSM.

## 8. Fuera de alcance

Arquitecturas de 32 bits, eventos secundarios congelados (§8), batalla con UI propia. **La cadencia de eventos la deciden los datos del usuario**.

## 10. Notas de implementación — slice 4 (2026-11-10, host)

Modelo en dos niveles (§3.3), decidido en este slice: en cada fix GPS
se evalúa SOLO el nodo en `posicion` con la doble validación del slice 3.
Un nodo distinto de `posicion` nunca se resuelve por proximidad: eso
impediría saltar nodos caminando (o en vehículo) por encima de la
secuencia.

- **Avance:** llegada + consumo + lectura no bloqueada ⇒ `posicion` sube
  en uno. Sin cambio de posición ROM no hay avance (R5).
- **Resolución:** el nodo se marca resuelto cuando, además, el destino
  está bien: `MATCH`, o `NOT_APPLICABLE` cuando el nodo no declara
  destino. Hoy TODOS los nodos generados son `NO_DEST`, así que resolver
  = llegar + moverse; en cuanto el volcado POCO (005) declare destinos
  reales, el `MATCH` se exige solo, sin cambiar código.
- **Avance sin resolver:** llegada + consumo con destino `MISMATCH` ⇒
  `posicion` avanza pero el nodo queda pendiente. El re-enganche puede
  devolver al jugador allí; si nunca completa el destino, la válvula de
  escape (slice 6b) es la salida.
- **Lecturas bloqueadas** (`Validation.blocked`, slice 4): sin fix, fix
  impreciso o de precisión desconocida, y cadena de snapshots rota. Una
  lectura bloqueada no avanza ni resuelve nada. Las dudas de destino
  (sin destino declarado, mapa incompatible, casilla distinta) NO
  bloquean el avance: las decide la regla de destino.
- **Re-enganche (R6):** corre ANTES de evaluar el nodo actual. Solo
  hacia atrás, al pendiente más alto dentro de 120 m con `map_id`
  compatible; nunca incrementa `posicion`. El fix debe ser fiable para
  mover la posición (precisión desconocida se permite, > 120 m no).
- **Fin de ruta (R10):** `posicion == N` es el estado explícito de fin;
  una vez terminada no se evalúa más. Los pendientes que queden son
  trabajo del slice 6b.
- **Persistencia:** `progress_<rom_key>.json` en `filesDir/routes/`,
  guardado atómico (`.tmp` → `.bak` → rename, mismo patrón que
  `RouteBundle.save`). No usa SQLite: `DatabaseHelper` no tiene tablas
  por diseño (purga VISION; el schema fiel a ROM llega con la Fase B).
  Tampoco SharedPreferences: el progreso es un dato por ruta y pertenece
  junto a su archivo de ruta. Un progreso corrupto o de otra partida se
  rechaza con motivo y se empieza en 0 (nunca se aplica a medias).
- **Cableado:** `GameEngine.setRoute(route)` al generar/importar la ruta
  y al cargar la ROM (también al arrancar con ROM guardada); el progreso
  se persiste en su archivo en cada cambio. El aviso
  `onRouteNodeResolved` llega a `MainActivity`: el nodo 0 resuelto
  escribe por fin `route_nodo0_resuelto` (el ancla se vuelve inmutable,
  R1) y la línea de ruta muestra `pos N/M · resueltos K`.
  `setValidationRoute`/`setValidationNodeIndex` se retiran (sin usos: la
  ruta nunca llegaba al motor en el slice 3).
- **`map_id` provisional:** la codificación `grupo * 256 + mapa` vive en
  UNA sola función, `RouteValidator.mapIdOf` (usada por el validador y
  por el re-enganche; grep verificado). Sigue pendiente confirmarla con
  el volcado POCO (005); corregirla después es un cambio de una línea.
- **Discrepancia (anotada, no inventada):** el plan cita "§12
  (D-01…D-05) de la spec", pero la spec aprobada tiene 9 secciones: no
  existe §12. Las D-01…D-05 que aparecen en el archivo pertenecen a la
  spec 015 (auditoría), no a la 004.

## 9. Dependencias

- **016** — estado del emulador e identidad de mapa por perfil.
- **009 slice 3** — disparo fiel de eventos por paso real.
- **005** — gating por banderas de estado reales.
- **015 B4/B5** — verdad GPS y geovallas de agua.
