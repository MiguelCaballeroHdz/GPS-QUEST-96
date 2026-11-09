# Auditoría POCO — Mapa + Juego (2026-10-29 19:04–19:18)

Dispositivo: POCO X7 Pro `2412DPC0AG` (Android 16 / SDK 36), APK debug `0.1.0`
(Build `2026-10-28+spec013-doblevelocidad-slice1`), ROM **ROM Gen 2 (español-Nckam)**.

## 1. Emulación del juego — VERDE

| Comprobación | Resultado | Evidencia |
|---|---|---|
| CGB activo | `CGB ON (flag 0xC0, only)` + `MMU modo CGB` | logcat |
| Velocidad de reloj | `EMU 45600 frames, 3202813360 cycles` → **70 238 ciclos/frame** (hardware 70 224 = 1×) | logcat |
| Doble velocidad | `KEY1=0x7E` (1×) — Gen 2 llama `NormalSpeed` por diseño (spec 013) | `ppu_regs.txt` |
| Pump de frames | `PUMP012 ... fps=59,7` estable | logcat |
| Título + intro | Gen 2 a color, logo y la mascota del título correctos | `10-menu-juego.png` |
| `CONTINUE` tras force-stop | Carga `JUGAD. AAAAAAAA 0:18` | `13-overworld.png` |
| Overworld | Pueblo Primavera: pasto, árboles, valla, casa, NPC, jugador | `15-overworld-carga2.png` |
| Scroll horizontal | Sin rasgado ni rastro; tiles coherentes | `16/17-caminar-derecha*.png` |
| Scroll vertical | Sin rasgado ni rastro | `21/23` (`23-estado.png`) |
| Menú START | ROM/MOCHILA/DISPOSITIVO/GUARDAR/OPCIÓN/SALIR legible, sin distorsión | `24-menu-start.png` |
| Framebuffer nativo | **160×144 limpio** (escena idéntica a pantalla) | `ppu_frame.png` + `ppu_frame.rgba` |

**Conclusión:** el core (CPU/MMU/PPU/MBC) renderiza el overworld correctamente.
El defecto reportado en `walk012c.mp4` **no se reproduce** en `Vista: Juego`.

## 2. Capa de mapa (OSM) — ROJO

| Hallazgo | Dato |
|---|---|
| M-1 Overpass nunca responde a tiempo | `TIMEOUT_MS = 10000`; consulta real medida desde PC: **25 943–33 764 ms** (HTTP 200, 123 785 B) ⇒ 3 intentos siempre agotan el read-timeout |
| M-2 Fallback procedural | `GBMapRenderer.draw()` → `generateFallbackGrid()` (Random sembrado por geohash) ⇒ terreno **inventado** (70% pasto, 15% tierra, 5% casa, 10% agua) |
| M-3 Tiles arbitrarios | `T_PASTO=0`, `T_CALLE=1`, `T_EDIFICIO=2`, `T_AGUA=3` apuntan a los 4 primeros tiles de la VRAM **viva** (en Gen 2 son pasto/árboles) ⇒ patrón camuflaje / "árboles negros" |
| M-4 Celdas no cuadradas | `cw=2/20` (61 px) vs `ch=2/18` (150 px) ⇒ cada tile se estira 2,5:1 |
| M-5 Caché descartada | `files/osm/19.528_-96.932.json` válido (28 elementos) pero `mtime = 1969-12-31` ⇒ `now - mtime > 7 d` ⇒ se borra y se reintenta la red |
| M-6 Error visible | Overlay muestra `Red: red: timeout` (diagnóstico correcto) | 

Evidencia visual: `19-vista-mapa.png`, `20-mapa-limpio.png`, `ppu_frame.png` (frame nativo limpio
vs. pantalla con camuflaje ⇒ el defecto está **en la capa de mapa**, no en la PPU).

## 3. Sprite OW sobre el mapa — ROJO

`SpriteExtractor` no converge: `base=0`, `6`, `19`, `52`, `123`, `136`, `133`, `137` con `hits`
de 3 a 11 123. El sprite dibujado sobre el mapa es una mancha marrón
(`20-mapa-limpio.png`, centro inferior) en vez del jugador.

## 4. UI/UX

- El engrane superior derecho (`btn_settings`) abre `ConfigActivity`, no el overlay.
- El overlay real (`btn_gear_overlay`) está en la esquina superior izquierda y mide 32 dp;
  con el tema verde queda casi invisible.
- `control_mode=gps` en prefs, pero el HUD dice **"Modo Sofá"** (sin fix GPS ⇒ fallback a Sofá).
- `Pasos: 0` en Modo Sofá (por diseño: el podómetro solo acumula en GPS_REAL).

## 5. Cómo reproducir

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb logcat -c; adb shell am start -n com.gpsquest96/.ui.MainActivity
# Overlay (esquina superior izquierda) -> "Vista: Mapa" / "Vista: Juego"
# Long-press en "Vista" vuelca ppu_frame/oam/vram/regs a filesDir
```
