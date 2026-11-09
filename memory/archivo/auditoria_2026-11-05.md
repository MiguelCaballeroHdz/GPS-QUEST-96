# Auditoría GPS Quest '96 — 2026-11-05

> Auditoría de solo lectura sobre el árbol completo (25 Java, 26 C++, 16 specs), más dos
> reparaciones de compilación aplicadas al final (§9). Producto de referencia: `PLAN.md`.
> Estado verificado: **build verde** el 2026-11-05 14:24.

---

## 0. Resumen ejecutivo

| Pilar propuesto por el usuario | Lo que ya existe | Lo que falta | Distancia |
|---|---|---|---|
| **Savestates** para arrancar en el gimnasio | Inyección de SRAM + hash de ROM + recarga en caliente (`.pueblo`) | Serialización CPU/MMU/PPU/APU/MBC; API nativa; formato versionado | **0 % del savestate**; ~90 % de la inyección de partida |
| **Spawn por metros** (50 m, tirada 1-10) | Tablas de encuentro leídas de la ROM, mapa actual leído, podómetro, `setJoypad` | Acumulador de 50 m, tirada y **disparo del combate** (borrado) | **~35 %** |
| **Progreso/medallas en SQLite** | `eventFlags[256]` + SharedPreferences | Todo lo demás; mapeo de flags reales de SRAM | **~10 %** |
| **"Emulador apagado 80 %"** | `GameEngine.pause()` | `applyViewMode(false)` no pausa la bomba de frames | **0 %** |

**Veredicto:** el enfoque es técnicamente posible, pero no es un "truco": el savestate es
ingeniería de core de semanas, y el 80 % de batería no lo da el savestate sino pausar la
bomba de frames. Orden recomendado: **geofencing primero** (§7).

---

## 1. Qué es el proyecto realmente

Emulador GBC propio, funcional:

- **Render**: PPU → framebuffer → OpenGL; mapa de ciudad con tiles de VRAM + geometría OSM.
- **Persistencia**: `.sav` de batería real (SRAM 32 KB), escritura atómica `.tmp/.bak`
  (`mbc.cpp:242-276`).
- **Perfiles `.pueblo`**: ZIP con `battery.sav` + `geo.sav` + `prefs.json`, validado por
  SHA-256 de la ROM (`PuebloProfile.java:27-59`).
- **GPS**: servicio foreground 5 s/5 m, geovallas de agua desde OSM, Modo Sofá.
- **Podómetro**: `TYPE_STEP_COUNTER` diferencial (`MainActivity.java:2413-2432`).
- **Cero** batallas, **cero** gimnasios, **cero** medallas, **cero** teleport del jugador.

La arquitectura es "emulador primero": con ROM cargada, la vista por defecto es **Juego a
pantalla completa** (`MainActivity.java:1507-1515`); el mapa es manual. La propuesta del
usuario invierte eso a "mapa primero, emulador solo en el evento": es un **cambio de
producto**, no solo de ingeniería.

---

## 2. Inventario

### 2.1 Java (25 archivos)

| Archivo | Líneas | Qué hace hoy | Para el plan |
|---|---:|---|---|
| `ui/MainActivity.java` | 3074 | Vista, GL, HUD, ajustes, derrota, `.pueblo`, podómetro, joystick | Todo el flujo vive aquí; sin máquina de estados |
| `emulator/GameEngine.java` | 571 | Orquesta pipeline, clima, geovallas, flags | `onRealStep()` es TODO → no dispara nada (`:287-291`) |
| `emulator/SaveManager.java` | 208 | Prefs: ruta ROM, perfil, `event_flags`, tregua, última ubicación | `saveState()` = solo flags (`:45-60`). **No hay savestate** |
| `emulator/EmulatorBridge.java` | 249 | 15 funciones nativas | Ninguna serializa CPU/PPU/MMU |
| `emulator/PuebloProfile.java` | 366 | Export/import ZIP con SHA-256 y escritura atómica | Base más cercana a "inyectar estado", a nivel SRAM |
| `emulator/DatabaseHelper.java` | 74 | SQLite v2 **sin tablas** (`onCreate` vacío, `:47-51`) | Punto 3 en cero |
| `emulator/ControlModeManager.java` | 128 | GPS Real / Sofá | Sirve para pruebas de geovalla |
| `emulator/core/EmulationPipeline.java` | 219 | Wrappers JNI; `setJoypad(int)` (`:183`) | Inyección de botones **ya existe** |
| `gps/GeofenceManager.java` | 254 | `WaterZone` (distancia al borde) + `StoryZone` | `addStoryZone()` **sin llamadores** (`:100`) |
| `gps/LocationService.java` | 188 | Foreground 5 s/5 m, filtro de velocidad | No acumula distancia |
| `gps/OsmDataFetcher.java` | 297 | Overpass: highway, building, natural, park, shop | **No pide `name` ni `tourism/historic`** (`:177-183`) |
| `gps/WeatherService.java` | 92 | Open-Meteo con throttle | Sin consumidor |
| `render/GBMapRenderer.java` | 505 | Ciudad opaca con tiles | Listo |
| `render/SpriteExtractor.java` | 396 | Sprite OW desde OAM/VRAM | Listo |
| `render/TileAtlas.java` | 90 | Snapshot de tiles | Listo |
| `bluetooth/*` (3) | 638 | accesorio BLE BLE | Ajeno |
| `ui/ConfigActivity.java`, `ui/IntroActivity.java`, `ui/JoystickView.java` | 334 | Idioma, onboarding, cruceta | — |
| `avatar/AvatarData.java`, `utils/LocaleHelper.java` | 88 | Soporte | — |

### 2.2 C++ (26 archivos)

| Archivo | Qué aporta | Para savestate |
|---|---|---|
| `core/cpu.{h,cpp}` | LR35902 completo, `stepFrame()` | Registros con getters/setters, pero `halted_`, `ime_`, `haltBug_` privados sin API (`cpu.h:140-158`) |
| `core/mmu.{h,cpp}` | VRAM 2 bancos, WRAM 8, IO, HRAM, OAM, HDMA, paletas | Todo privado; solo lecturas parciales por bloque |
| `core/ppu.{h,cpp}` | Render por scanline, triple búfer, `EmulatorState` | Estado interno sin serializar (`ppu.h:233-274`) |
| `core/mbc.{h,cpp}` | MBC1/3/5, SRAM, `.sav`, RTC | RTC **desde reloj del host**, no persistido (`:363-376`) |
| `core/wild_table.{h,cpp}` | Localiza tablas de encuentro por firma | Funcional |
| `jni/native_bridge.cpp` | 15 JNI | `nativeGetCurrentMap` lee **0xDCB5-0xDCB8 de Gen 2** (`:391-394`) |
| `core/arm7.*`, `mmu32.h` | GBA congelado | Fuera de fase |

### 2.3 Specs (16)

| Spec | Estado | Realidad |
|---|---|---|
| 002, 003, 006, 007, 008, 010, 011, 012, 015 | Aprobadas/cerradas | Implementadas |
| **004 mapeo global** | BORRADOR | Sin regla monumento→locación |
| **005 gating** | BORRADOR **bloqueada** | Espera diff de dumps de SRAM |
| **009 spawns** | APROBADA | Slices 1-2 hechos (tablas); **slice 3 retirado** |
| **016 arquitectura genérica** | BORRADOR | Slice 2 aterrizó en la PPU; slice 1 borró `callRoutine`/`nativeTryWildEncounter` |

`memory/archivo/estado_actual-2026-11.md` decía "build verde" (3-nov) mientras el árbol del 4-nov no
compilaba. La memoria iba un día por delante de la realidad.

---

## 3. Savestate: no es un `.sav`

- **`.sav` = SRAM de batería (32 KB)**: la partida del juego. Se carga al abrir la ROM y se
  guarda atómicamente.
- **Savestate = CPU + MMU + PPU + APU + MBC + RTC**: no existe ni formato ni un byte de
  serialización.

Evidencia: `SaveManager.saveState()` solo escribe índices de flags (`:45-60`);
`EmulatorBridge` expone 15 nativas y ninguna guarda/restaura estado;
`MainActivity.preBattleCheckpoint()` (`:1596-1601`) se llama "checkpoint" pero solo hace
`flushSram()` + flags; el modal de derrota "restaura snapshot" (`:1654`) recarga `.sav` y
flags, no un estado de CPU.

Lo que **sí** está resuelto: SHA-256 de ROM, escritura atómica, importación validada y
`reloadRom(false)` (`MainActivity.java:970-975`). El problema es que al recargar vuelves al
**menú del juego**, no al punto exacto. Ese es el hueco que el savestate quiere tapar.

### Tres caminos

| Camino | Implica | Coste | Fidelidad |
|---|---|---|---|
| **A. Savestate completo** | ~80-100 KB de estado (WRAM 32K, VRAM 16K, SRAM 32K, OAM/HRAM/IO/paletas/HDMA, registros, contadores internos), versión + hash de ROM, restaurar en frame consistente | **Semanas de core** | Alta si se hace bien |
| **B. SRAM + macro** | `.sav` guardado frente al líder + automatizar A/A/Continuar con `setJoypad` | **Días** | Media: no es instantáneo |
| **C. Poke de WRAM** | Escribir `0xDCB7/0xDCB8` (x,y) para poner al jugador en la puerta | **Horas**, ROM-specific | Baja: salta colisiones, scripts y gating |

Detalle decisivo: **un savestate grabado tiene especie y nivel fijos**. Usarlo para "spawn
aleatorio" deja de leer la tabla del tramo y pasa a ser contenido pregrabado: choca con la
regla 1 y con la spec 009 ("no un inyector que escriba datos arbitrarios en WRAM",
`specs/009-b2-spawns/spec.md:18-19`). Para ser fiel necesitarías uno por combinación
especie×nivel×mapa: inviable.

---

## 4. Spawn por metros: lo más cerca de estar listo

La lectura de tablas **ya está hecha**: `nativeGetWildTable` + `nativeGetCurrentMap`
funcionan y se usan en un volcado de diagnóstico (`MainActivity.java:1833-1875`). Falta solo
el disparo:

- `GameEngine.onRealStep()` devuelve `false` con TODO (`:287-291`).
- `MainActivity.onWildEncounter()` (`:2507-2521`) es **código muerto**: nadie lo invoca.
- No hay acumulador de 50 m ni tirada 1-10; el podómetro entrega pasos absolutos.
- El disparo real (`nativeTryWildEncounter` + `callRoutine`) **fue eliminado a propósito**
  por la spec 016, por ser específico de Gen 2.

La mitad "detectar 50 m y tirar el dado" es barata. La mitad "inyectar el combate limpio"
es el 65 % restante y es justo lo que el proyecto decidió no volver a hacer a la antigua.

---

## 5. Progreso/medallas: es una segunda verdad

`DatabaseHelper` está vacío a propósito: `dojo_medals` y `creatures` se eliminaron en v2
(`:14-17`, `:52-56`). El progreso vive en `eventFlags[256]` y `SRAM_FLAG_MAP` está vacío
porque no hay diff de dumps (`GameEngine.java:491-499`).

Una tabla `medallas_obtenidas` en SQLite es el patrón que la spec 005 bloqueó:

1. **Desincronización**: importar un `.pueblo` viejo deja la DB en 3 y la SRAM en 1.
2. **Gating inventado**: la regla 2 exige que las compuertas las manden flags reales
   (`specs/005-gating/spec.md:8`).
3. **Doble mantenimiento**: cada avance se escribe en dos sitios.

Lo correcto es **leer los flags reales de SRAM**. Está bloqueado por algo barato: un diff de
dos dumps (fresco vs. con una medalla) para localizar offsets. Una tarde con el POCO.

---

## 6. "El emulador apagado el 80 %"

Hoy es **0 %**. `applyViewMode(false)` solo cambia `ppuFullscreen`
(`MainActivity.java:1517-1529`); `FramePumperTask` sigue llamando `gameEngine.update()` a
59,7 fps, y el hilo de audio solo respeta `isPaused` (`GameEngine.java:176-180`). En "Vista:
Mapa" el juego sigue corriendo detrás.

No hace falta savestate para eso: basta con no arrancar la bomba en Vista Mapa y reanudarla
al entrar al evento. Pero hoy caminar mueve al personaje **dentro del juego** traduciendo el
rumbo GPS a la cruceta (`MainActivity.java:1381-1405`); si pausas el emulador en el mapa, el
personaje deja de caminar. Es el pivote "mapa propio + instancias de combate", un cambio de
arquitectura, no un ahorro.

---

## 7. Choques con `PLAN.md`

| Regla | Propuesta | Veredicto |
|---|---|---|
| 1. Extracción 100 % ROM | Savestate pregrabado con combate fijo | **Choca** si sustituye la tabla del tramo: inventa *qué* aparece |
| 2. Gating 100 % original | Saltar a la Catedral por geovalla | **Choca**: teletransportar se salta las compuertas |
| 3. Spawn progresivo y fiel | Gimnasio 1 con 0 medallas, luego el 2 | Compatible en idea; incompatible en mecanismo artificial |
| 4. Solo LR35902 | Savestate de GBC | Compatible |
| 5. Post-game congelado | — | Compatible |

Riesgo legal: distribuir savestates pregrabados de una obra concreta es zona gris. `.pueblo`
ya resolvió esto elegantemente: el usuario aporta su ROM y su partida.

---

## 8. Recomendación: geofencing primero

No porque sea más fácil, sino porque el geofencing es la condición de entrada de todo:

- Sin POI con identidad ("Catedral", "Oxxo") no sabes *qué* inyectar ni *cuándo*.
  `OsmDataFetcher` ni siquiera pide `name`, `tourism` ni `historic` (`:177-183`).
- Sin regla monumento→locación (spec 004) el savestate sería un atajo que oculta el problema.
- El savestate es de semanas; el geofencing de días y se valida caminando con el POCO.
- El 80 % de batería lo da pausar la bomba, no el savestate.

Orden:

1. **Build verde y estable** (§9). Sin esto nada es verificable.
2. **Geofencing v0 con identidad de POI**: `name`/`tourism`/`historic`/`amenity` en la query,
   `StoryZone` reales para Catedral y Oxxo, entrada/salida con histéresis.
   Criterio: estar en la Catedral dispara un evento una sola vez.
3. **Pausar el emulador en Vista Mapa** y despertarlo en el evento.
   Criterio: batería medida en el POCO con la pantalla del mapa.
4. **Acumulador 50 m + tirada 1-10** con la tabla real.
   Criterio: 50 m en el mapa correcto producen una tirada registrada.
5. **Solo entonces decidir** A/B/C con datos: tamaño del estado, tiempo de restauración y si
   sobrevive a un kill del proceso en el POCO.

Si se ataca el savestate ya, el prototipo de menor riesgo es **B**: `.sav` frente al líder +
macro de botones. Reutiliza el 90 % de la infraestructura y dice en un día si la experiencia
se siente bien, sin tocar el core.

---

## 9. Reparaciones aplicadas (2026-11-05)

| # | Problema | Evidencia | Acción |
|---|---|---|---|
| 1 | 5 Java con BOM UTF-8; `javac` los rechaza | `illegal character '\ufeff'` en `ControlModeManager`, `EmulatorBridge`, `GameEngine`, `LocationService`, `MainActivity` | `fix_bom.py` (rutas corregidas) → 5 archivos, 3 bytes menos cada uno |
| 2 | `cpu.cpp` sin llave de cierre de `namespace gpsquest96` (residuo del slice 1) | balance 119 `{` / 118 `}`; `error: expected '}'` en `:787` | añadida `} // namespace gpsquest96` |
| 3 | Mojibake en literales visibles (38 en 3 archivos, 2 con doble codificación) | `EspaÃ±ol`, `Modo SofÃ¡`, `Partidas Guardadas Â· `, `DERROTA â€”`, nombres ja/ko/zh/ru ilegibles | re-decodificación cp1252 → UTF-8 por literal, con tabla inversa para los bytes indefinidos; respaldos en `%TEMP%\opencode\backup-mojibake*` |

Resultado: `./gradlew assembleDebug --offline -q` → **EXIT=0**; APK 10,85 MB (14:36).
Verificación en el APK: `classes4.dex` contiene `Español`, `Português`, `日本語`, `Русский`
y **no** contiene `EspaÃ±ol` ni `â€¦`.

### Deuda detectada y no tocada

- **`nativeGetCurrentMap` sigue leyendo 0xDCB5-0xDCB8 de Gen 2** (`native_bridge.cpp:391-394`).
  La spec 016 está a medias: se borró la rutina de combate, no la lectura ROM-specific.
  Propuesta: aislarla en un perfil de ROM por game code (`nativeGetGameCode` ya existe), con
  fallback "sin datos" para ROMs no soportadas.
- **Mojibake en comentarios Java**: los literales visibles ya están corregidos (§9 #3), pero
  los comentarios siguen mojibakeados (`mÃ³dulos`, 785 ocurrencias en `GameEngine.java`, 212
  en `MainActivity.java`). No afectan al producto; normalizarlos requiere la misma tabla
  inversa cp1252 y debe hacerse en un cambio aislado. `strings.xml` está limpio.
- **`BUILD_TAG` rancio**: `"2026-10-28+spec013-doblevelocidad-slice1"` (`native_bridge.cpp:21`).
  Si se instala un APK con la máquina de estados, el log miente.
- **`EmulatorState` a medias**: ver §10.

---

## 10. Revisión del slice 2 (máquina de estados)

Cinco defectos, verificados contra `ppu.cpp`:

1. **`INTRO` es inalcanzable y `MAP` se reporta desde el primer frame.** `updateEmulatorState()`
   se llama en `ppu.cpp:174`, dentro de la rama `ly_ >= 144`, tras `syncRegsToMmu()`: siempre
   se evalúa con `LY = 144`, así que gana la rama `MAP`. `nativeOpenRom` ya deja `LCDC=0x91`
   en CGB (`native_bridge.cpp:106`), así que `BOOT` tampoco aparece.
2. **`windowActive` ignora el bit 5 de `LCDC`.** `ppu.cpp:631` usa `wy < 144 && wx <= 166` sin
   comprobar window enable. La MMU arranca con IO a cero (`mmu.cpp:24, 37`), así que el primer
   VBlank reporta `BATTLE`. Y cualquier diálogo del overworld también cae ahí.
3. **`MENU` y `PAUSE` nunca se asignan.** Solo hay cuatro escrituras (`:625, 636, 644, 647`).
   `PAUSE` pertenece a `GameEngine.isPaused`, no a la PPU.
4. **Sin debounce y con `applyViewMode` caro.** Escribe prefs y hace `refreshAtlasSnapshot()`
   en cada llamada (`MainActivity.java:1491-1500, 1517-1529`). A 60 Hz: jank y parpadeo.
5. **Carrera de hilos.** `emulatorState_` es `uint8_t` plano escrito en el hilo de emulación y
   leído por la UI. Precedente del mismo riesgo: `onEnterBlockedZone` → `showBlockMessage`
   toca vistas fuera del hilo principal (`MainActivity.java:2485-2489`).

Además: el compilador marca `stat`, `scx` y `scy` como variables sin usar (`ppu.cpp:616-618`),
señal de que la heurística se escribió sin terminar.

**Antes de cablear Java:** bit 5 de LCDC → muestreo/debounce → `PAUSE` desde `isPaused` →
`jint` (no `jintArray`) en las tres capas → y no conectar `applyViewMode` hasta que el estado
sea estable. `applyViewMode(false)` debe además parar la bomba de frames, o no hay ahorro.

---

## 11. Riesgos abiertos

- **RTC del host** (`mbc.cpp:363-376`): ni `.sav` ni savestate conservan el tiempo del juego;
  los encuentros por hora del día se mueven solos.
- **Detección de batalla por ventana**: falsos positivos con diálogos; no sirve para cerrar
  una batalla automáticamente.
- **Divergencia árbol/APK**: el BOM demostró que pueden compilarse fuentes distintas de las
  que corren en el POCO. `assembleDebug` antes de cada verificación.
- **Sin git**: los respaldos son manuales; un borrado accidental no se recupera.
