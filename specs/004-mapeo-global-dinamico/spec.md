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

## 9. Dependencias

- **016** — estado del emulador e identidad de mapa por perfil.
- **009 slice 3** — disparo fiel de eventos por paso real.
- **005** — gating por banderas de estado reales.
- **015 B4/B5** — verdad GPS y geovallas de agua.
