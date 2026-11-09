# Evidencia — 015 / B5 (geovallas de agua desde OSM) y B6 (spawn sin mentir)

POCO X7 Pro · 2412DPC0AG · 2026-11-03 20:38 · build `assembleDebug --offline -q` `EXIT=0`.

## B5 — el agua de OSM ya es geovalla real

### Defecto verificado en código

| Archivo : línea | Efecto |
|---|---|
| `GeofenceManager.java:50` | `addWaterZone` sin ninguna llamada: lista de agua siempre vacía. |
| `GeofenceManager.java:92-100` | La distancia se medía al **punto** registrado, no al borde, y `radius` se ignoraba. |
| `MainActivity.java:2427` | `onNearWater()` vacío: aun detectando agua, el HUD y el modo no cambiaban. |
| `LocationService.java:180` | `setGeofenceListener` **sustituía** el listener de `GameEngine`: el último en registrarse dejaba mudo al otro (el HUD de agua nunca podía enterarse). |

### Cambios

1. `GeofenceManager`
   - `WaterZone` guarda la **geometría** de OSM (`double[] lats/lons`) y `distanceMeters()`
     calcula la distancia mínima al vértice o al **segmento** más cercano (proyección
     equirectangular local; error despreciable a 20 m).
   - `replaceWaterZones(List<WaterZone>)` sustituye la carga anterior y devuelve el
     número real de zonas (tope 512). No resetea `nearWater`: soltar la protección
     antes del siguiente fix sería un fallo de seguridad (§2).
   - Lista de listeners (`addGeofenceListener`) en vez de un único listener.
   - `waterZones`/`storyZones` pasan a `CopyOnWriteArrayList`: el worker de Overpass
     escribe mientras el hilo de GPS recorre.
2. `MainActivity`
   - `syncWaterGeofences(elements)` en el callback OSM: cada elemento `Kind.WATER` se
     registra con su polilínea/polígono completo. Log `Geovallas agua: N zonas desde OSM`.
   - `onNearWater(boolean)`: `controlModeManager.setGpsNearWater(...)` + aviso en el HUD
     (`msg_water_sofa` / `msg_water_clear`), en el hilo de UI.
3. `LocationService`: `addGeofenceListener` (una sola vez) en vez de `setGeofenceListener`.

### Verificación sin POCO (host tests, `tools/host-test/run-java.ps1`)

```
OK: 39 checks            (PuebloProfile/SaveManager, sin regresión)
  ok borde medio < 20 m (fue 15.0282)
  ok borde medio a 30 m queda fuera
  ok extremo cuenta como borde
  ok sobre la orilla => nearWater
  ok lejos => sin nearWater
  ok listener 1 recibe nearWater
  ok listener 2 recibe nearWater (no se pisa)
  ok ambos reciben el 'despejado'
  ok replace devuelve 2
  ok getWaterZoneCount == 2
  ok reemplazo vacío limpia
GEOFENCE HOST TEST OK (11 comprobaciones)
```

Nuevo stub `stub/android/location/Location.java` (lat/lon + haversine) para poder
compilar la clase real `GeofenceManager` en el host.

### Verificación en POCO

```
I GameEngine: Geovallas agua: 0 zonas desde OSM
D GameEngine: PUMP012 ticks=1024 frames=649 dt_medio_ms=10,6 fps=59,8
```

`0 zonas` es el resultado **correcto** en este punto: la caché OSM de 150 m
(`files/osm/19.528_-96.924.json`, 1 111 nodos) no trae ni un solo tag `natural=water`
ni `waterway` (histograma de tags revisado). El enganche OSM → geovalla se ejecuta.

**Pendiente de gate físico:** ver el aviso de `<20 m` exige un fix GPS junto a agua
mapeada (caminar a un río/lago). No se simula.

## B6 — spawn sin mentir

`MainActivity.onWildEncounter()` mostraba `¡ROM #16 Nivel 2!` aunque el juego se
quedaba quieto: la tabla se lee de la ROM (009 slices 1-2), pero el combate real depende
de `nativeTryWildEncounter` (009 slice 3, pendiente). Ahora solo queda el log:

```
B2 spawn ROM detectado (sin combate aún): #<especie> Nv <nivel> mapa <g>/<m>
```

Sin banner falso. La tregua de 180 s y la detección se mantienen intactas.

## Archivos tocados

- `app/src/main/java/com/gpsquest96/gps/GeofenceManager.java`
- `app/src/main/java/com/gpsquest96/gps/LocationService.java`
- `app/src/main/java/com/gpsquest96/ui/MainActivity.java`
- `app/src/main/java/com/gpsquest96/emulator/GameEngine.java` (listener aditivo)
- `app/src/main/res/values/strings.xml` (2 cadenas nuevas)
- `tools/host-test/run-java.ps1`, `java/stub/android/location/Location.java`,
  `java/test/hosttest/GeofenceHostTest.java`
