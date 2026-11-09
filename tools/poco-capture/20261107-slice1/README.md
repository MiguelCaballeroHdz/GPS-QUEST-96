# 2026-11-07 — Spec 004 slice 1 (OSM POI-first): cierre en POCO

Verificación de la query y la clasificación nuevas en el dispositivo, con la caché OSM de la
celda borrada para forzar una consulta real.

## Evidencia del binario

| Dato | Valor |
|---|---|
| `BUILD_TAG` en logcat | `BUILD 2026-11-07+spec004-poi-slice1` (15:47:39.696) |
| Consulta nueva | `OSM OK desde overpass-api.de (138203 B)` (15:48:14.904) |
| APK | 11.378.265 B · `SHA-256 A67CDF78…590875DC` |
| Instalado | hash remoto `a67cdf78…590875dc` |

## Evidencia de la consulta (misma celda `19.528_-96.924`)

| Métrica | Antes (consulta vieja) | Después (consulta nueva) | Δ |
|---|---|---|---|
| Elementos | 198 | **278** | +80 |
| Nodos | 14 | 82 | +68 |
| Vías | 184 | 196 | +12 |
| `AMENITY` | 5 | 79 | +74 |
| `TOURISM` | 4 | 10 | +6 |
| `SHOP` / `BUILDING` / `HIGHWAY` / `PARK` | 15 / 68 / 103 / 3 | 15 / 68 / 103 / 3 | 0 |

Los 68 nodos nuevos son POIs con `amenity`/`tourism`/`historic`: bancos (HSBC, Banorte),
cafés, restaurantes, bicicletero, cibercafé, estatua de Juan de la Luz Enríquez, etc.

Overlay del dispositivo: `Red: OK 278 elem · tiles:307` (`overlay.txt`, `overlay-278elem.png`).

## Archivos

- `overlay.txt` / `overlay-278elem.png`: UI del overlay con el contador.
- `logcat.txt`: líneas del tag y de la consulta.
- `conteo.txt`: desglose por clase.
- `osm-celda-OLD-198.json` (`sha256 2704662F…`): JSON viejo, 118.058 B.
- `osm-celda-NEW-278.json` (`sha256 7E68D9A8…`): JSON nuevo, 138.261 B (en el dispositivo
  138.203 B servidos; la copia local añade el salto de línea final de `cat`).

## Método (y trampas)

1. `adb exec-out run-as com.gpsquest96 cat files/osm/19.528_-96.924.json > viejo.json`.
2. `adb shell run-as com.gpsquest96 rm files/osm/19.528_-96.924.json` (la caché dura 7 días;
   sin borrarla se habría reutilizado el resultado viejo).
3. Relanzar y esperar fix GPS: la consulta se dispara con la posición.
4. Comparar y leer el overlay.

Las capturas y los `cat` binarios se redirigen con `cmd /c "... > archivo"`. Con `>` de
PowerShell el PNG sale corrupto (misma trampa que el dump del `.sav` del 11-06).
