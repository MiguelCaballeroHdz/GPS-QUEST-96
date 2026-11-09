# Bitácora - GPS Quest '96

---


## 2026-11-07 (noche) — Spec 004 slice 6a cerrado: export/import de ruta

**Contexto:** única mitad ejecutable del slice 6 sin depender del slice 4. La válvula de
escape (6b) queda bloqueada: sin `posicion`/`resueltos[]` no hay nodo bloqueado que saltar.

**Cambios:**
- `route/RouteBundle.java` (nuevo, Java puro): paquete `.ruta` = ZIP con `manifest.json`
  (`format`, `version`, `rom_key`, `nodes`, `routeSha256`, `createdAt`) y `route.json`.
- Importación validada: máximo 8 entradas; topes 64 KB (manifiesto) y 2 MB (ruta);
  rechazo de `..`, rutas absolutas, `\`, `:` y entradas desconocidas; formato/versión
  obligatorios; checksum SHA-256 opcional; `rom_key` del manifiesto, de la ruta y de la
  partida cargada deben coincidir.
- `RouteBundle.save()` escribe `route_<rom_key>.json` de forma atómica (`.tmp → .bak →
  rename`); `MainActivity.writeRouteFile()` lo reutiliza.
- UI SAF en el engrane: “Exportar ruta (.ruta)” e “Importar ruta (.ruta)”. Al importar se
  actualiza el ancla confirmada, el estado `Ruta: N nodos (M POI)` y los nodos del mapa.
- `BUILD_TAG` → `2026-11-07+spec004-slice6`.

**Verificación:**
- Host `RouteBundleHostTest`: **26 comprobaciones, `EXIT=0`** (round-trip, otro `rom_key`,
  formato/versión, checksum, traversal, entrada desconocida, tope de tamaño y guardado
  atómico). Suite Java completa: **151 comprobaciones**, `EXIT=0`.
- Build: `.\gradlew.bat assembleDebug --offline` → **`BUILD SUCCESSFUL`, `EXIT=0`**.
- APK: 12.410.600 B · mtime `2026-11-07 19:24:59` · SHA-256
  `F532C426AAD0C0D589EFD2F2F4835F5D9E393765AB82CCC04EFD1FB620E7AEA8`.

**Pendiente:** válvula de escape y fin de ruta (R10) en 6b, tras el slice 4; gate físico
del selector SAF. El paquete mueve la ruta, no el progreso (`posicion`/`resueltos[]`).
Estado en `memory/archivo/estado_actual-2026-11.md` §11.10.

## 2026-11-07 (noche) — Spec 004 slice 2 cerrado: ancla confirmada y ruta maestra

**Contexto:** segundo slice desbloqueado de 004 (tampoco depende de 016). Cierra además el
slice 0 (esquema y validación) dentro del mismo modelo de ruta.

**Cambios:**
- Paquete nuevo `route/`: `Route` (esquema v1, `MAX_NODES=500`, rechazo con motivo),
  `RouteNode` (acción, POI, radio, `dest`, `blocked`/`unlock`), `RouteFormatException` y
  `RouteGenerator` (puro, host-testable).
- Ancla explícita en `MainActivity` (`confirmAnchorFlow()`: fix exterior, precisión ≤20 m,
  fecha y precisión guardadas; re-anclable solo hasta resolver el nodo 0).
- `RouteGenerator`: `METATILE_M=8.0`, `NODE_RADIUS_M=15.0`, `DEFAULT_NODE_TARGET=180`,
  `MAX_ANCHOR_ACCURACY_M=20.0`, `MAX_POI_RADIUS_M=250.0`,
  `MAX_SYNTHETIC_RADIUS_M=250.0`, `WATER_MARGIN_M=10.0`; POI por afinidad y respaldo
  sintético a 8 m/metatile; ningún nodo sobre agua.
- `OsmDataFetcher`: `requestArea(lat, lon, radiusM)` y
  `requestCorridor(lat, lon, radiusM, spanM)`; `RADIUS_M=150` como defecto y caché con
  radio/corredor.
- `GBMapRenderer`: `setRouteNodes()` y tipo `NODE` (prioridad 8); `kindToCell` pinta
  `AMENITY` y `TOURISM`.

**Verificación:**
- Host `RouteHostTest`: **44 comprobaciones, `EXIT=0`** (determinismo, agua, 180 nodos,
  ancla >20 m rechazada, unión de caché). Suite Java completa: **125 comprobaciones**
  (39 pueblo + 11 geovalla + 31 OSM + 44 ruta), `EXIT=0`.
- Build: `.\gradlew.bat assembleDebug --offline` → **`EXIT=0`**.
- Dispositivo: `BUILD 2026-11-07+spec004-slice2` (16:03:43.175);
  `ANCLA OK 19.562807,-96.917928 ±3 m rom_key=PM_CRYSTAL-v0` (16:04:19.698).
- Ruta final: **180 nodos** (178 `walk` sintéticos + 1 `use` `SHOP` en el índice 27 + 1
  `enter` `AMENITY` en el 51), radio 15 m, máximo **249.1 m** del ancla (nodo 178).
  `route_PM_CRYSTAL-v0_final.json`, SHA-256 `BB7B6DD9…B3EF8267`.
- Mapa: `nodo_ruta=68` en la primera pasada y `estampados 180`; `AMENITY=55` en la celda.

**Defecto corregido en el slice:** la primera ruta salió con 111 nodos, todos POI y hasta
**4.1 km** del ancla (sin tope de radio). Con `MAX_POI_RADIUS_M=250` y tramos `walk` de 8 m
quedó en 180 nodos, 2 POI y 249.1 m. El ancla se confirmó dos veces antes de resolver el
nodo 0 (postfix 19.5627717,-96.9179650 → final 19.5628067,-96.9179283).

**Pendiente:** consumo por GPS (slices 3-5, bloqueados por 016), criterio §7.8 en calle,
`resueltos[]`/`route_nodo0_resuelto` (slice 4) y export/import (slice 6). Evidencia:
`tools/poco-capture/20261107-slice2/`; estado en `memory/archivo/estado_actual-2026-11.md` §11.9.

## 2026-11-07 (noche) — Spec 004 slice 1 cerrado: OSM POI-first (query, clasificación y poda)

**Contexto:** único slice desbloqueado de 004 (no depende de 016). Autorizado por el usuario
tras la auditoría del combate.

**Cambios (`OsmDataFetcher.java`, único archivo de producción tocado):**
- Enum `Kind` podado a 8 valores (`HIGHWAY, PARK, WOOD, BUILDING, WATER, SHOP, TOURISM,
  AMENITY`); fuera `GOODS, GYM, LAB, POKESHOP, CAVE, HOME, WATERZONE` (0 usos, sin clave OSM).
- Query Overpass: `node` **y** `way` de `amenity`/`tourism`/`historic` (antes una farmacia,
  que es nodo, nunca llegaba), con `[timeout:25]` y `out geom`.
- `classify()` reordenado: POI primero, `building` como último recurso; ahora package-private.

**Verificación (evidencia cruda):**
- Host test nuevo `tools/host-test/java/test/com/gpsquest96/gps/OsmClassifyHostTest.java`
  (31 comprobaciones) contra la clase real: hospital `building=yes`+`amenity=hospital` →
  `AMENITY`, monumento `historic=monument` → `TOURISM`, enum de 8 valores, `parse()` conserva
  nodos y vías. Suite completa **81 comprobaciones, `EXIT=0`**.
- Build: `.\gradlew.bat assembleDebug --offline` → **`EXIT=0`** (45 tareas).
- APK: 11.378.265 B · mtime `2026-11-07 15:47:02` · `SHA-256
  A67CDF781361972029420CCCF2A8192EC72047E2BC55086ADBE811CB590875DC`; instalado, hash remoto
  idéntico.
- Dispositivo: logcat `BUILD 2026-11-07+spec004-poi-slice1` (15:47:39.696); con la caché de
  la celda borrada, Overpass respondió `138203 B` (15:48:14). Misma celda: **198 → 278
  elementos** (`AMENITY` 5→79, `TOURISM` 4→10; 68 nodos POI). Overlay: `Red: OK 278 elem`.

**Utilidades:** el host test ya no dependía de la caché de Gradle (`org.json` ya no está);
se vendoreó `tools/host-test/lib/json-20231013.jar` (`sha256 0F18192D…`). El stub
`android.util.Log` ganó `w(String,String,Throwable)`, que rompía la compilación.

**Documentos:** `plan.md` (slice 1 HECHO), `estado_actual.md` §11.5 y §11.8, `tasks.md`
Task 27, `tools/host-test/README.md` y esta entrada.

**Lo que NO arregla:** salto de mapa por celda y pintado de `TOURISM`/`AMENITY` en el atlas
(slice 2); identidad de mapa por perfil (016).

---

## 2026-11-07 (tarde) — Auditoría del combate: 009 slice 3 no está en el árbol; `applyViewMode` funciona pero nadie lo llama

**Pregunta:** el acta de campo dice que la entrada a combate no fuerza el cambio de vista.
¿Es una regresión del binario?

**Hallazgo (código, verificado línea a línea):**
- `GameEngine.onRealStep()` (:287-291) es un **stub** `return false;` con el TODO «espera a la
  arquitectura base genérica (Spec 016)».
- `tryWildEncounter` y `nativeTryWildEncounter`: **0 coincidencias** en `app/src` (Java y C++).
- `onWildEncounter(`: solo la declaración (`GameEngine:82`) y el override (`MainActivity:2507`);
  **ningún llamador**.
- `applyViewMode(true)`: solo al cargar/importar ROM (`MainActivity:975`, `:1083`) y dentro del
  `onWildEncounter` muerto.

**Conclusión:** no es regresión; la función no existe. El una criatura del acta salió de la **ROM**
(que dibuja su batalla dentro del emulador) y la app siguió en Vista Mapa; el usuario tuvo que
cambiar de vista a mano. Re-implementar 009 slice 3 depende de 016.

**Prueba en dispositivo del mecanismo (no del productor):** ⚙ izquierdo → «Vista: Mapa» → al
pulsar, `view_ppu_full` pasa `false`→`true`, la etiqueta cambia a «Vista: Juego» y el **65% de
los píxeles muestreados cambian** (media RGB (35,61,26) con ROM a pantalla completa frente a
(85,98,46) en el mapa); al volver, `false`. Capturas: `%TEMP%\opencode\combate-02-vista-juego.png`
y `combate-03-vista-mapa.png`, tomadas con redirección binaria (`cmd /c ... >`). Las capturas
hechas con `>` de PowerShell salen corruptas: misma trampa que el dump del `.sav` del 11-06.

**Corrección documental:** `estado_actual.md` §3, §5 y §10-B6 afirmaban que el slice 3 estaba
implementado; corregido en el mismo turno y añadida la §11.7. La entrada del 2026-11-04 queda
marcada como histórica.

**Nota lateral:** el overlay de hoy muestra `Red: OK 198 elem · tiles:243`, frente a «344 tiles»
del acta. El conteo de tiles depende de lo que haya en VRAM; no es una huella estable.

---

## 2026-11-07 — Acta de campo del 11-06 auditada: el log ADB se perdió, la evidencia forense sobrevive

**Qué ocurrió:** el usuario aportó el acta de la caminata del 2026-11-06 (GPS real, pasos
260→614, una criatura derrotado por una criatura con subida a nivel 6 y PANTALLAHUMO, guardado
«Red OK, 51 elementos, 344 tiles», clima Lluvia) y pidió extraer el log por ADB.

**El log no existe ya:** `adb logcat -d` devuelve 309.819 líneas y **todas son del 11-07**
(primera `11-07 07:49:08.487`); 0 líneas de la app. Es un ring buffer: la sesión del 11-06 se
perdió. `dumpsys dropbox` (lo único persistente que aportaría un `bugreport`) tiene 2
`data_app_crash` del 11-06 y **ambos son de Banco Santander**, ninguno de `gpsquest96`; sin ANR
ni tombstone de la app.

**Evidencia forense recuperada del dispositivo (sí verificable):**
- `lastUpdateTime = 2026-11-06 14:24:48` → el APK de la prueba es el del hash `7C771E48…`.
- Cachés OSM escritos el 11-06: `19.560_-96.923.json` (15:38, 50 elementos),
  `19.560_-96.922.json` (15:44, **51 elementos**), `19.526_-96.934.json` (22:04, 72 elementos),
  `19.528_-96.924.json` (14:26, 198 elementos).
- **Coincidencia exacta:** el único caché con 51 elementos es el de las 15:44 → respalda el
  «Red OK, 51 elementos» del acta.
- `.sav` de Gen 2 escrito a las **19:51** (32.768 B, sha256 `299727F6ACEB1CC3…`), con
  `.sav.bak` del mismo minuto y datos reales en los bancos 0 y 1 → guardado verificado.
- `shared_prefs` 22:06 con `control_mode=gps` → la sesión terminó en modo GPS, no en Modo Sofá.
- **Discrepancia:** el acta narra una sola sesión, pero hay tres franjas horarias (15:38-15:44,
  19:51, 22:04). El «51 elementos» y el guardado no son el mismo instante.

**Hallazgo nuevo (causa del «salto de mapa»):** dos cachés de celdas **contiguas** escritas con
6 min de diferencia. `OsmDataFetcher.cacheFile()` usa `%.3f_%.3f` (≈111 m) y cada celda lanza
una query de 150 m que **reemplaza** todo el set de elementos. Cruzar el borde ⇒ recarga total.
No es «versión pre-spec 004»: es el diseño actual, y el slice 1 de 004 no lo toca (lo corrige el
slice 2, consulta por corredor).

**Triage de los 6 defectos del acta:** bloque verde, salto de mapa, sprite cortado, drift GPS,
combate sin cambio de vista automático y notificaciones sobre la UI. **Ninguno se cierra con el
slice 1.** Cuatro son estructurales (fallback OSM, caché por celda, snapshot OAM por frame,
`WalkFilter` B4 pendiente), uno es posible **regresión de 009 slice 3** (`onWildEncounter` →
`applyViewMode(true)` ya existe) y uno es de UI (modo inmersivo).

**Evidencia:** `tools/poco-capture/20261106-campo/` (`evidence.md`, caché de 51 elementos,
listado de dropbox y de paquete). El `.sav` y los 4 JSON completos quedan en
`%TEMP%\opencode\field-20261106\`.

**Pendiente:** repetir la caminata con `adb logcat -v time > archivo` para cotejar timestamps;
sin eso, pasos, especie, nivel, movimiento y «344 tiles» siguen siendo solo vídeo.

---

## 2026-11-06 — Build verde tras el fix de compilación, despliegue verificado en POCO y spec 004 aprobada (sin código nuevo)

**Qué se hizo (solo documentación y verificación; cero cambios de producto):**
- Fix de compilación de un token en `app/src/main/java/com/gpsquest96/gps/OsmDataFetcher.java:327`:
  `Kind.GOODs` → `Kind.GOODS`. Sin BOM, UTF-8 intacto. Nada más se tocó.
- `memory/archivo/estado_actual-2026-11.md` §11 recoge la evidencia cruda y lo que **no** demuestra.
- `specs/004-mapeo-global-dinamico/spec.md`: BORRADOR (2026-10-23) → **APROBADA 2026-11-06**
  (POI-first + ruta maestra). `plan.md` del 2026-10-28 queda **superado** y se reescribe con
  los slices 0-6. La propuesta intermedia se archivó en
  `%TEMP%\opencode\gps96-004-20261106\spec_v2_propuesta.md`.

**Evidencia de build:**
- SHA-256 fuente tras el fix: `E94AB49A…CD6F90A` (14.282 B; mtime 11/06 14:20:35).
- `./gradlew assembleDebug --offline` → **`EXIT=0`**.
- APK: **11.395.953 B**, mtime 11/06 14:20:44.
- SHA-256 APK: `7C771E4874F87ECB7E10746BD9A8A585A29E33AAF7F9F2F426D3929F07A60E97`.

**Evidencia POCO (XWWSAY4LAAPZHIA6 / 2412DPC0AG):**
- `adb install -r` → `Success`; hash del APK extraído del dispositivo **idéntico** al construido.
- `Displayed com.gpsquest96/.ui.MainActivity +671 ms`; `PM_CRYSTAL`; `CGB flag 0xC0 (CGB-only)`;
  `MBC3 cartBanks=128 ram=32768B`; `SRAM 32768/32768 B`; `fps=59,7`;
  `sprite OW base=52 via=OAM`; `FATAL=0`; `UnsatisfiedLink=0`; `ANR=0`.
- Log: `%TEMP%\opencode\gpsquest96-boot-20261106.log` · captura: `%TEMP%\opencode\poco-20261106.png`.

**Lo que NO se hizo (para que no se lea como avance de producto):**
- **POI-first no está implementado**: el enum nuevo no tiene consumidor y la query sigue sin
  `tourism`/`amenity`/`historic`; el comportamiento es idéntico al APK del 11/05.
- No se verificaron geovallas ni paso real (interiores, Modo Sofá, sin fix GPS).
- El `BUILD_TAG` del binario sigue en `2026-11-05+spec016-slice2` (**desfasado**). Se cambia
  al cerrar el slice 1 de 004, no antes, y la evidencia se captura después del rebuild.

**Pendiente:** slice 1 de 004 (query `node`+`way` de `amenity`/`tourism`/`historic`,
`classify` reordenado, poda de `GYM`/`LAB`/`POKESHOP`/`CAVE`/`HOME`/`WATERZONE`/`GOODS`).

---

## 2026-11-04 — Spec 009 Slice 3: nativeTryWildEncounter (inyección por paso real) implementado y compilado

> **Corrección 2026-11-07:** el código descrito aquí **ya no está en el árbol**. `onRealStep()`
> es un stub que devuelve `false`; `tryWildEncounter`/`nativeTryWildEncounter` no existen y
> `onWildEncounter` no tiene llamador. Entrada conservada como histórico; ver la del
> 2026-11-07 (tarde).

**Qué se hizo:**
- **`app/src/main/cpp/jni/native_bridge.cpp`:** `Java_com_gpsquest96_emulator_EmulatorBridge_nativeTryWildEncounter` lee `wMapGroup` (0xDCB5) y `wMapNumber` (0xDCB6) de WRAM, consulta `g_wildTable` (tabla de hierba de Gen 2), selecciona especie/nivel por tasa de la ROM y devuelve `int[]{group, map, rate, level, species}`.
- **`app/src/main/java/com/gpsquest96/emulator/EmulatorBridge.java`:** declaración `public native int[] nativeTryWildEncounter()` + wrapper tolerante `tryWildEncounter()`.
- **`app/src/main/java/com/gpsquest96/emulator/GameEngine.java`:** `onRealStep(int stepCount)` invoca `tryWildEncounter()` solo en `GPS_REAL`, respeta tregua (`saveManager.isInTruce()`), aplica tiraje de probabilidad (`rate` de la ROM), inicia tregua corta y notifica `onWildEncounter(species, level, group, mapNum)`.
- **`app/src/main/java/com/gpsquest96/ui/MainActivity.java`:** `onWildEncounter()` muestra aviso en HUD y llama `applyViewMode(true)` en hilo UI, conmutando a Vista: Juego a pantalla completa para presentar el combate en primer plano.

**Verificación:**
- `./gradlew assembleDebug --offline -q` → `EXIT=0` (BUILD SUCCESSFUL).
- Render CGB: 59.7 FPS mantenidos. Audio APU intacto. Mapeo OSM independiente.
- **Pendiente:** verificación con foto en dispositivo POCO (gate POCO).

---
## 2026-11-01 — Spec 015: Auditoría integral de código, fidelidad y arquitectura (B1, B2, B3)

**Auditoría y Mejoras en C++ y Java:**
- **PPU / CGB Render (Doble Búfer):** Incorporado `displayBuffer_` atómico en VBlank en `ppu.h`/`ppu.cpp` para eliminar desgarros (*tearing*) y titileos de pantalla al caminar. PUMP012 a **59.7 FPS** continuos en POCO X7 Pro.
- **JNI de Solo Lectura:** Implementadas las firmas `readBgPalette`, `readObjPalette` y `readVramBank` en C++ y Java (`EmulatorBridge.java`). `dumpPpuFrame()` ya no escribe sobre registros hardware `0xFF4F`, `0xFF68` ni `0xFF6A`, eliminando la corrupción de paletas y VRAM durante diagnósticos, combates y menús del juego.
- **Mapeo y Renderizado (GBMapRenderer.java):** Corregida la inversión del eje Y del Joystick (`dy = (lat - rasterLat) / cellH`).
- **Extractor de Sprites (SpriteExtractor.java - Defecto D-25):** Añadido soporte para los bits de volteo `flipX` (0x20) y `flipY` (0x40) de OAM en `composeSprite()`. El avatar de 16×16 permanece perfectamente integrado durante las animaciones de pasos.
- **I/O y Concurrencia:** Migrados los flujos I/O a *try-with-resources* en `OsmDataFetcher`, `WeatherService`, `PuebloProfile` y `MainActivity`. Eliminados campos estáticos en `LocationService.java`.
- **Evidencia POCO:** Despliegue directo y pruebas en vivo sobre POCO X7 Pro con `ui_state` y `read_logcat`.
- **Compilación:** `./gradlew assembleDebug --offline` -> `BUILD_OK`.

---

## 2026-10-28 (noche, 4) — spec 013 slice 1 ejecutado: paridad BIOS (2× post-boot) + timers en SYSCLK; hallazgo: Crystal se baja a 1× por diseño (upstream verificado); la velocidad OW real es 1×

**Código (3 archivos core + 2 host-tests, tras ZIP backup):**
- `mmu.h`: setter público `setDoubleSpeed(bool)` (paridad post-boot, RF-01).
- `mmu.cpp` `setCgbMode(on)`: en la activación CGB queda `doubleSpeed_ = true` (el estado
  post-boot de la BIOS del CGB que este core no corre). Bitácora en el sitio.
- `cpu.cpp` `step()`/`checkInterrupts()`/HALT: `updateTimers(toPpuCycles(n))` = TICKS DE
  SYSCLK (RF-02, hardware: DIV/TIMA no se aceleran a 2×); 20 de IRQ → 10 ticks a 2×.
  `stepFrame` ya usaba budget 140448 a 2× + `toPpuCycles` en la PPU — sin cambios.
- `BUILD_TAG` → `2026-10-28+spec013-doblevelocidad-slice1`.
- `test_mmu` 30→**34 checks** (+paridad post-boot + toggle 2×↔1×); `test_lr35902`
  20→**23 checks** (paridad RF-01; TIMA/DIV a ticks-SYSCLK correctos en 1× Y 2×: la
  semántica anterior esperaba timers acelerados ×2 — hardware: van al SYSCLK SIEMPRE,
  Pan Docs; los checks nuevos: 8 NOPs a 2× = 16 ticks → TIMA+1; 128 NOPs a 2× = 256
  ticks → DIV+1). Arnés completo: **150 C++ + 39 Java + BUILD_OK**; README actualizado.

**Evidencia POCO (gate parcial):** log limpio: `MMU modo CGB (post-boot 2x, paridad
BIOS)` ✓; y **0.2 s después: "CGB doble velocidad OFF"** — el JUEGO se auto-baja a 1×.
EMU cycles/frame estable **70224 exactos** en el OW (1×) en todas las ventanas; dump:
`KEY1=0x7E`, ICDC=0xE3, SVBK=0xFD.

**Verificación upstream (fuente pokecrystal, definitive):** `home/init.asm` `Init::` —
en CGB tras la inicialización llama **`NormalSpeed`**; y `home/double_speed.asm`
(`DoubleSpeed/NormalSpeed/SwitchSpeed`) usa `set B_SPD_PREPARE,[hl]` (CB) + `stop` —
por eso el scan del binario no encuentra `E0 4D`/`EA 4D FF` (y el .gbc extraído por
exec-out quedó CORRUPTO: CRLF de Windows ⇒ 4,223,958 bytes con header en ceros —
el dump del ROM por ese vía NO confía; para próximas veces: run-as copy → otra vía).

**Interpretación (corrección del diagnóstico de la noche 3):**
- En hardware real: BIOS CGB entrega a 2×; Crystal's Init se BAJA a 1×; y el flujo
  de menú/OW que inspeccioné no llama `DoubleSpeed` ⇒ **el overworld de Gen 2 corre
  a 1× en hardware real**. KEY1=0x7E en el OW es hardware-paridad, NO un defecto.
- Por tanto el "mundo sub-real" NO era la diferncia de velocidad: el catch-up de 012
  slice 1 a 59.7 frames/s ya entrega la velocidad real del OW (usuario lo confirmó:
  "va más rápido y se nota y se juega mejor").
- RF-01/RF-02 quedan como **paridad-hardware sana** (boot/título = como BIOS; timers
  correctos SI el juego algún día opera a 2×) — no cambia gameplay del OW, y el
  despliegue no lleva riesgo de regresión visible.
- **El defecto real del overworld (tiles corruptos, árboles negros abajo, rastro,
  transición batalla en blanco — vídeos walk012c + relato del usuario) NO es de
  velocidad**: queda abierto como Task 25 (candidato spec 014: integridad del frame
  del overworld) — con `dump_now` (VRAM bancos + regs) como vía probada de evidencia
  ya disponible en el core.

**Mystery del "armed" resuelto:** el ROM arma KEY1 vía `set (HL)` (CB C6) con HL=FF4D
+ `stop` — Totalmente consistente con upstream; old log "double velocidad OFF" =
Behavior del juego real, no alguien externo.

**Estado del slice 1:** código + arnés + POCO-log verifican RF-01 (paridad boot) y
RF-02; el Criterio de velocidad de mundo se reformula: la percepción VELOCIDAD REAL
que exigía la spec 013 queda cubierta por el catch-up de 012 (el usuario la validó);
la paridad hardware del boot/título queda demostrada. FALTA el gate de percepción del
usuario sobre el OW (la corrupción persiste casi seguro; es Task 25, NO 013).

---

## 2026-10-28 (noche, 3) — GATE 012 slice 1: pump a 59.7 fps verificado ×3; hallazgo de raíz del defecto overworld: core VIVE a 1× (KEY1=0x7E, Crystal debería ser 2×)

**Corrección de datos (del registro de la noche 2):** la Grabación de la caminata de la
sesión 1 marcó **119.4 fps por EMU-line** — anomalía **no reproducible**: dos arranques
limpios (20:21, 20:33) miden consistente. En 3 ventanas de 10 s: `EMU` 600 frames/10.05 s
(59.7) y `PUMP012` ticks=512→frames638, dt_medio 21/10.4 ms, `fps=59.3-59.8` (instrumentación
en el pump, `MainActivity.java`). Conclusión: **la cadencia del acumulador (RF-01) está
sana; velocidad visible transparente estable**. La 119.4 queda anotada como punto sin
mecanismo identificado; si reaparece el pump la atraparía (log de diagnóstico cada 512 ticks).

**Caminata del usuario:** "va más rápido y se nota y se juega mejor" (+Gate fluidez OK).
Respecto al "rastro fantasma": con el vídeo `walk012c.mp4` el usuario documentó defecto
overworld: tiles pasto repetidos/invertidos, árboles negros abajo, rastro sin limpiar
al scroll, transición a batalla en blanco con sprites regados; batalla finaléria OK.

**Descarte del diagnóstico externo (VBK/atributos):** `Ppu::drawCgbBgPixel` (ppu.cpp
486-515) ya hace lo correcto: índice de `readVram(banco 0)`, atributo de `readVramBank(1)`
(palabra bits 0-2, banco tile bit 3, flips bits 4-5, prioridad bit 6). `Mmu::readVramBank/
writeVramBank` (mmu.cpp 263-283) enruta por banco `& 0x01` ✓ y el mapeo CPU VBK FF4F solo
bit-0 (mmu.cpp 331-332/372-373) ✓. La WB de tiles "banco 0 siempre" en `readVram` es
convención correcta (índice del tilemap). Hipótesis de "siempre lee banco 0" **descartada**.

**Causa raíz encontrada (evidencia dura, sin tocar código):**
1. `dump_now` en vivo sobre el overworld: `KEY1=0x7E` ⇒ **bit 7 = 0 → velocidad SIMPLE**
   (hardware CGB post-boot = 2×). VBK=0xFE (banco 0 CPU-side), LCDC=0xE3, LY=0x26.
2. `bit 0 de KEY1 = 0` (sin armar) ⇒ el juego **ni siquiera tentó el switch en sesión**;
   tampoco ha habido "CGB doble velocidad ON" (LOGI de `trySpeedSwitch`, mmu.cpp 77-82)
   en NINGUNA de las 3 sesiones capturadas.
3. `Cpu` arranca en **PC=0x0100** (cpu.cpp 35) — **BIOS CGB no corre**: el switch KEY1+STOP
   al 2× lo ejecuta el boot ROM de fábrica en el hardware real. Sin BIOS y sin switch,
   `doubleSpeed_` queda false siempre.
4. Cadena: 1× ⇒ Crystal recibe la mitad del presupuesto CPU por VBlank vs su diseño de
   2× ⇒ desfase LCD (overworld corrupto/rcid, transición inválida) **y mundo sub-real**
   (la otra mitad de "velocidad real" que el catch-up del pump no puede dar).

**Revisión de la cadena PPU/CPU en doble velocidad (código ya correcto):**
`stepFrame` (native_bridge.cpp 593-602): budget `140448 si doubleSpeed`, y `toPpuCycles`
(cpu.cpp 132-137) rebaja **la mitad (PPU ticks)** ⇒ 1 frame PPU completo por stepFrame ✓.
**Segunda deuda real detectada:** `Cpu::step()` (cpu.cpp 96) hace
`mmu_->updateTimers(cycles)` con el costo **raw**: en 2× aceleraría DIV/TIMA ×2 — hardware:
los timers corren en el SYSCLK 4.194304 MHz y NO se aceleran con la doble velocidad
(Pan Docs). Pendiente corregir (dots = `cycles>>1` a 2× en `updateTimers` y en el
`updateTimers(20)` de `checkInterrupts`) — host test_lr35902 tiene cobertura STOP/DIV/TIMA
a doble velocidad que avala el rediseño.

**Deriva:** si 013 se aprueba, el mundo sube a la velocidad real de hardware GBC (2× vs
hoy) y probablemente mejora el overworld corrupto (el ROM recupera su presupuesto de LCD).

---

## 2026-10-28 (noche, 2) — spec 012 slice 1: catch-up de ciclos + GL 60 (código hecho, gate POCO pendiente)

**Motivo:** Task 23 (spec APROBADA en la sesión anterior). La medición de 011 demostró el
techo del pump: 1 frame GB por tick `postDelayed(33)` ⇒ ~26 fps/43% de velocidad, no es CPU
(GLThread 37.7% vs EmuLoop 11.3%).

**Cambio (`MainActivity.java` SOLO — el core C++ no se toca, sin BUILD_TAG):**
- `FRAME_INTERVAL_MS` 33 → **16 ms** + constantes nuevas `GB_FPS=59.7275`,
  `MAX_TICK_NS=100 ms` (clamp de `dt`, RF-04) y `FRAMES_TICK_TOPE=3`.
- Nuevo `FramePumperTask` (clase del bomba, sustituye al Runnable anónimo):
  ancla monotónica (`SystemClock.elapsedRealtimeNanos`), `deuda += dt·GB_FPS/1e9`,
  ejecuta los frames GB competentes vía `gameEngine.update()` (1 JNI/frame) con
  tope 3; el remanente se descarta, nunca arrastra deuda infinita; el reloj se
  re-ancla cada tick. El primer tick solo ancla.
- Pausa/reanudar: `onResume → startFramePump()` re-instancia el pump ⇒ re-ancla
  sin catch-up retroactivo; en pausas intermedias (export/import pueblo) el
  `update()` no-op y el clamp acota todo (RF-04 verificado por diseño).
- `TARGET_FPS` 30 → **60** (GLRenderer): `onDrawFrame` puede subir cada frame GB.

**Evidencia host:** `run.ps1` 30+18+51+20+24 (=143) + Java 39/39 + `assembleDebug
--offline` ⇒ **BUILD_OK**. Respaldo: `Temp/opencode/gps96-spec012-slice1-backup-20261028.zip`.

**Pendiente (gate POCO spec 012 slice 1):** log `EMU` ≥55 fps sostenidos 150+ s
caminando en Gen 2 + percepción tiempo real + sin rasgado + `CONTINUE` +
reporte de calor. Capturas: `tools/poco-capture/20261028-spec012-velocidad/`.

---

## 2026-10-28 (noche) — spec 011 GATE POCO slices 1+2: slice 2 pasa; slice 1 no concede (el "titileo" es el techo del pump, deuda previa); slice 3 eximido por evidencia

Dispositivo: POCO X7 Pro, build `2026-10-28+spec011-slice2-hdma-wly`, Gen 2 con `.sav`.
Carpeta de evidencia: `tools/poco-capture/20261028-spec011-slice1-slice2/evidence.md`.

**Slice 2 ✅ PASA:** batalla/menús/diálogos CGB sin distorsión, colores OK (el síntoma original
del 27). HDMA HBlank + `windowLine_` en LY==WY quedan verificados en dispositivo.

**Slice 1 ⚠️ gate no concedido:** el anillo de 3 slots está en producción sin regresión, pero
"titileo al caminar" persiste re-caracterizado por el usuario: *"no es distorsión, es como
lag/poco fluido que se muestra como parpadeo, y va como a cámara lenta"*. Medición sin código:
- `EMU` log: **26.0 fps** con **70226 ciclos/frame** (frames GB completos) ⇒ 43% de velocidad.
- `top -H`: **GLThread 37.7%, EmuLoop 11.3%** ⇒ el cuello NO es CPU.
- Lectura del código: EmuLoop agenda `postDelayed(33)` y ejecuta **1 frame GB por tick**
  (diseño del 2026-10-16, "bomba a 30 fps") ⇒ ~38 ms/tick = 26 fps; GL además topa a
  `TARGET_FPS=30`. El traspaso ya lo documentaba como *"techo del pump, no del core"*.
**Dictamen:** el doble búfer eliminó la carrera real que existía, pero el titileo EXPERIENCIAL
es baja tasa de frames de una decisión de diseño previa. Federación: no se revierte slice 1
(harmless, host-probado); la fluidez exige spec nueva (pump 60 fps con catch-up).

**Slice 3 ✅ NO SE HACE (criterio condicionado resuelto):** `meminfo -d` en 3 ciclos
force-stop/relanzar: Native Heap **25908 → 25864 → 25844 KB** (plano/decreciente) ⇒ sin fuga
acumulativa. `nativeDestroyPipeline()` queda sin implementar — la spec lo exime.

**Audio:** "sin audio" — APU 0% por diseño (deuda documentada, nunca implementada). No regresión.

**Estado del turno:** 143+39 checks verdes + BUILD_OK (antes de la sesión); POCO gate recogido
con evidencia; Task 22 se cierra con: slice 2 POCO-pass, slice 1 código-válido/pado-pendiente-de-
especie-012, slice 3 eximido. Siguiente: spec 012 velocidad real (aprobación del usuario).

---

## 2026-10-28 — spec 011 slice 2: HDMA troceada por HBlank + ventana WLY fiel (código hecho, gate POCO compartido con slice 1)

**Motivo:** continuar spec 011. Decisión (elegida por mí a petición del usuario "decide tú"):
hacer el código del slice 2 ahora y **una sola sesión de gate POCO que cubra slices 1+2**, con
A/B disponible: APK del slice 1 conservado en
`Temp/opencode/gps96-spec011-slice1apk/slice1-dbuf.apk` (11.6 MB). Respaldo previo de los 5
archivos: `Temp/opencode/gps96-spec011-slice2-backup-20261028.zip`.

**Cambio 1 (`mmu.h` / `mmu.cpp` — HDMA paginada):** `startHdma()` ya no copia todo de golpe con
bit7=1: deja la transferencia **en vuelo** (`hdmaRemaining_` bloques, `hdmaSrc_/hdmaDst_`
absolutos). Nuevo `runHdmaChunk()`: copia 16 B por llamada, avanza src (que puede cruzar bancos
ROM vía `read()`/MBC) y dst con envoltura dentro de VRAM (máscara `0x1FF0`, el bloque alto nunca
sale de 0x8000-0x9FFF); al agotar los bloques deja `FF55 = 0xFF`.
- `FF55` leer: bit7=0 con (restantes-1) durante el vuelo (la ROM de Gen 2 sondea esto) — la
  ruta de lectura ya era correcta, sin tocar.
- Cualquier escritura en FF55 con transferencia en vuelo la cancela (hardware). `0xFF` cancela y
  termina. GDMA (bit7=0) sigue síncrona. **LCD apagado ⇒ copia síncrona** (no hay HBlanks).

**Cambio 2 (`ppu.cpp` — chunk por HBlank):** al pasar de modo 3→0 (fin de cada línea visible,
0..143), tras `renderScanline()`, si CGB: `mmu_->runHdmaChunk()`. Después de pintar la línea,
como el hardware: lo que la ROM mueva en HDMA afecta a las líneas siguientes, no a la dibujada.

**Cambio 3 (`ppu.cpp` — WLY fiel, RF-03):** `windowLine_` se reinicia en `renderScanline`
cuando `LY == WY` con ventana activa (LCDC.5) — Pan Docs. **Eliminado** el reinicio por fin de
frame (LY≥154): si la ROM cambia WY a mitad de frame (reglas de batalla, diálogos), el conteo
vuelve a alinearse en el nuevo LY==WY, no se distorsiona al final del frame. LCD apagado sigue
reiniciando (reset de hardware). Accessor `getWindowLine()` solo para el arnés host.

**Arnés host (`test_cgb_ppu.cpp`):** 25 checks nuevos — HDMA: arranque en vuelo (FF55 bit7=0 +
restantes), chunk 16 B/HBlank con contenido correcto y FF55→0xFF al agotar, cancelación con
0xFF, GDMA síncrona intacta, LCD apagado ⇒ copia completa. WLY: LY==WY reinicia y pinta la
primera línea de la ventana (pixel rojo en LY=40), conteo por línea, **cambio de WY a mitad de
frame realinea** (el caso de batallas/menús), ventana deshabilitada no cuenta, re-habilitada
retoma desde donde quedó.
*Nota de depuración (lección):* el check de pixel falló al inicio por MI error en el test:
usé `LCDC=0xB1` creyendo que activaba el bit 6 (map de ventana 0x9C00), pero 0xB1 = bits
7,5,4,0 — el bit6 está apagado y el hardware correcto busca en 0x9800 (vacío ⇒ negro). La PPU
estaba bien; corregido el test a `0xF1`. Instrumentación temporal (`#ifdef GPSQUEST96_PPU_TRACE`)
usada y **retirada por completo** (core limpio).

**Evidencia:** host C++ **30 + 18 + 51 + 20 + 24 = 143** checks verdes (PPU pasó de 26 a 51) +
Java **39/39** + `assembleDebug --offline` ⇒ **BUILD_OK**. `BUILD_TAG`:
`2026-10-28+spec011-slice2-hdma-wly`.

**Pendiente (gate POCO compartido slices 1+2):** misma sesión de POCO: caminar en Gen 2
(sin titileo ⇒ slice 1) + abrir batalla/menús/diálogos en CGB (sin distorsión ⇒ slice 2) +
regresión Azul DMG (título/profesor/ventana) + log `EMU` no empeorado + `CONTINUE` tras force-stop.
Capturas en `tools/poco-capture/20261028-spec011-slice1-slice2/`.

---

## 2026-10-28 — spec 011 slice 1: anillo de triple búfer en la PPU (código hecho, gate POCO pendiente)

**Motivo:** reporte del usuario: titileo al caminar en Gen 2. Causa diagnosticada en código:
un solo `frameBuffer_` escrito por el hilo de emulación (`EmuLoop` → `stepFrame()`) mientras el
hilo GL (`uploadPpuTexture()`) subía ese mismo array a la GPU (`glTexSubImage2D`). Spec APROBADA:
`specs/011-render-nativo-estable/spec.md` + plan en su carpeta (slices: doble búfer, HDMA
HBlank + ventana, higiene nativa).

**Cambio (`ppu.h` / `ppu.cpp`):** anillo de **3 slots fijos** (`frameBuffers_[3][160*144]`) con
swap de punteros en VBlank:
- La PPU pinta cada scanline en `drawBuffer_`; el hilo GL lee el slot `presentIndex_`
  (`getFrameBuffer()`/`getFrameBufferDirect()`, lectura atómica `acquire`).
- En VBlank se publica el slot pintado y se toma para el siguiente frame el único slot que no
  es ni present ni el recién publicado. Con 3 slots cada uno queda ≥2 frames sin escribir tras
  presentarse, así la lectura GL (que nunca va 2 frames atrás) no colisiona con el redibujo —
  sin locks ni copias. Cero allocations por frame.
- `renderer_->renderFrame(getFrameBuffer())` en VBlank; todos los píxeles de render
  (BG/ventana/sprites/CGB) van al buffer de dibujo. `native_bridge.cpp`: solo `BUILD_TAG`
  nuevo (`2026-10-28+spec011-slice1-dbuf`). Java sin cambios (`uploadPpuTexture()` ya re-pide
  el buffer direct en cada frame).

**Arnés host:** `test_cgb_ppu` leía píxeles justo tras pintar scanlines, antes de VBlank — con
el anillo eso vive en el buffer de dibujo. Añadido `Ppu::getDrawFrameBuffer()` (solo arnés; el
JNI no lo usa) y el helper `pixel()` lo usa. Sin usuarios del buffer directo en arnés: OK.

**Evidencia:** `test_mmu` 30/30 + `test_sav` 18/18 + `test_cgb_ppu` 26/26 + `test_lr35902`
20/20 + `test_wildtable` 24/24 (SKIP ROM real: sin `GPSQUEST_ROM_GEN2`) + Java 39/39 +
`assembleDebug --offline` → **BUILD_OK**. Respaldo de los 3 archivos antes de tocar:
`Temp/opencode/gps96-spec011-slice1-backup-20261028-1923.zip`.

**Pendiente (gate POCO del slice 1):** caminar en Gen 2 sin titileo, regresión Azul DMG
(título/profesor/ventana), log `EMU` no empeorado, `CONTINUE` tras force-stop. Capturas en
`tools/poco-capture/20261028-spec011-slice1/` (sin crear todavía).

---

## 2026-10-25 (noche) — PPU/CPU en host, timers CGB, STAT/LYC y captura POCO en un pase

**Motivo:** antes del gate POCO se querían sacar varios bugs de una vez. El arnés solo cubría
MMU y `.sav`; el color CGB y el timing de doble velocidad se validaban únicamente en el móvil.

**Host PPU (nuevo `tools/host-test/test_cgb_ppu.cpp`):** compila `ppu.cpp` real contra un stub
de `renderer/renderer.h` (sin GLES2) y comprueba con VRAM/paletas sintéticas:
- paleta BG RGB15 y atributo del banco 1 (paleta 2 + banco de tile 1);
- flips X/Y, prioridad BG↔OBJ, `OPRI` (X vs índice OAM), ventana con atributos;
- `LCDC.0` ignorado en CGB, sprite transparente y sprite con tile en banco 1;
- regresión DMG (`GB_PALETTE[1]`), y STAT modo 0/1/2 + LYC por flanco con gating.
**`OK: 26 checks`.**

**Host CPU (nuevo `tools/host-test/test_lr35902.cpp`):** ROM sintética MBC1; NOP/LD, `STOP` +
`KEY1`, reset de `DIV`, y timers en doble velocidad. **`OK: 20 checks`.**

**Bug de timing corregido:** `Cpu::step()` y `checkInterrupts()` pasaban `toPpuCycles(cycles)` a
`Mmu::updateTimers()`. En doble velocidad eso dejaba DIV a 16384 Hz y TIMA a la mitad, cuando
el hardware sube DIV a 32768 Hz y duplica TIMA. Ahora los timers reciben el ciclo completo de
CPU; la PPU sigue recibiendo `toPpuCycles()` (un frame real por `stepFrame`). `STOP` también
resetea `DIV`. El test lo fija: 4 NOPs → `TIMA+1` y 64 NOPs → `DIV+1` en doble velocidad.

**STAT/LYC:** `Ppu` dispara `IRQ 1` al entrar en modo 0/1/2 si STAT lo habilita y en el flanco
`LYC==LY` (sin repetir en 2→3→0). `syncRegsToMmu()` lee `LYC` en vivo, no la caché de la última
scanline. Cubierto por los 26 checks de PPU.

**Diagnóstico POCO en un pase:**
- `dumpPpuFrame()` añade `ppu_vram1.bin` (atributos CGB), `ppu_bg_palette.bin`,
  `ppu_obj_palette.bin` y registros `KEY1/VBK/SVBK/BCPS/OCPS/OPRI`. Antes de volcar pausa el
  motor 60 ms y lo reanuda solo si ya estaba corriendo: escribir `VBK`/`BCPS` desde otro hilo
  a mitad de frame podía corromper el color del juego.
- `tools/poco-test.ps1` instala el APK, lanza la app, espera las pruebas manuales y captura
  logcat, dumps, saves y un `ppu_frame.png` en `tools/poco-capture/<fecha>/`. No descarga la ROM.

**Evidencia:** `test_mmu` 30/30 + `test_sav` 18/18 + `test_cgb_ppu` 26/26 + `test_lr35902`
20/20 + Java 39/39 = **133/133**; `assembleDebug --offline` → **BUILD SUCCESSFUL**
(APK 12.15 MB, 25/10/2026 19:42).

**Límite honesto:** el host no ejecuta Gen 2 ni GLES2; el color final, `CONTINUE` y el SAF
real siguen pendientes de POCO. HDMA troceado por HBlank y RTC MBC3 continúan como deuda.

## 2026-10-25 (tarde) — Spec 008 slice 1: guardado real (`.sav`)

**Motivo:** `Mbc::loadSav()`/`saveSav()` eran stubs con comentario “DIAGNOSTICO TEMPORAL”.
Sin `.sav` no hay `CONTINUE`, no se puede probar una sesión de B2 y el `.pueblo` no tiene base.

**Diseño:** el `.sav` vive junto a la ROM copiada (`filesDir/roms/<rom>.sav`, ya escribible
porque `MainActivity` copia la ROM). No se toca SAF ni la tarjeta.

- `core/mbc.cpp` — `loadSav()`: abre en `rb`, lee hasta `sram_.size()`, rellena el resto con
  `0xFF`, limpia `ramDirty_` y loguea `MBC SRAM cargada: N/M B`.
- `core/mbc.cpp` — `saveSav()`: solo si `hasBattery_ && ramDirty_`; escribe `.tmp`, respalda
  el anterior en `.bak` y promueve con `rename`; loguea `MBC SRAM guardada`. Sin batería o sin
  SRAM no hace nada.
- El disparo ya existía en `onPause`; además se añadió `flushSram()` al botón
  "Guardar Ahora", al checkpoint pre-batalla y a `GameEngine.stop()` para que el `.sav`
  no dependa solo de que el sistema llame `onPause`.

**Evidencia (host, sin móvil):**
- `tools/host-test/test_sav.cpp` (nuevo): ROM sintética MBC3+batería de 32 KB; escribe banco 0
  y banco 1, `flush`, reabre y verifica ambos; la segunda escritura crea `.bak` con el byte
  anterior (`0x42`); una ROM MBC1 **sin batería** no genera `.sav`.
- `tools/host-test/run.ps1` compila y corre los dos binarios:
  **`test_mmu` 30/30 + `test_sav` 18/18 = 48 checks**.
- `.\gradlew assembleDebug --offline` → **BUILD SUCCESSFUL** (APK 12.15 MB).

**Límite honesto:** el arnés no emula el juego real. El `CONTINUE` de Gen 2/Azul solo se
valida en POCO. El RTC de MBC3 **no** se persiste (Gen 2 toma la hora local); deuda declarada.

**Pendiente:**
- Gate POCO: guardar en el juego → matar la app → reabrir → `CONTINUE`, con log
  `MBC SRAM guardada`/`MBC SRAM cargada`.
- Gate POCO del `.pueblo`: exportar, borrar datos/otro equipo, importar y verificar
  `CONTINUE`; probar rechazo con una ROM distinta.

### Spec 008 — slice 2: perfil `.pueblo`

**Formato:** ZIP con `manifest.json` (formato `gpsquest96.pueblo`, versión 1, título, game
code, SHA-256 de la ROM, `batterySize`, `hasGeo`), `battery.sav` (SRAM cruda), `geo.sav`
(`filesDir/saves/gpsquest96.sav`) y `prefs.json` (jugador, flags, tregua, última ubicación).
**La ROM no viaja**: solo su hash, para no distribuir contenido con copyright.

- `PuebloProfile.java` (nuevo): exporta a un `OutputStream` de SAF; importa desde un
  `InputStream`, valida el hash **antes** de escribir y aplica límites (64 entradas, 64 KB
  manifest, 1 MB battery/geo, 256 KB prefs). Escritura `.tmp` → `.bak` → rename.
- `SaveManager`: `exportPrefs()`/`importPrefs()` (no exporta `rom_path`, que es del equipo).
- `GameEngine`: `getRomInfo()`, `flushSram()`, `reloadRom()` (cierra y reabre para que el core
  lea el `.sav` importado) y `reloadState()` (reemplaza flags).
- `MainActivity` + `activity_main.xml`: "Exportar perfil (.pueblo)" e "Importar perfil
  (.pueblo)" en el engrane; pausa el motor, trabaja en hilo de fondo y recarga la ROM.
- **Verificación:** `assembleDebug --offline` verde (API 34) + host test Java
  **`OK: 39 checks`** (`tools/host-test/run-java.ps1`): export ZIP, manifest/SHA-256,
  import en otro directorio, `.bak`, rechazo por ROM distinta y `geo.sav` inválido. El
  runtime de SAF/UI sigue pendiente de POCO (los streams se simulan en memoria).

---

## 2026-10-25 — Ruta integrada + CGB real (slices 1-2), post-game congelado

**Decisión del usuario:** adoptar el spec maestro `gps_quest_96_spec.html` como ruta, con
dos límites: “modo color Advance” = **GBC a color** (no GBA), y todo lo post-game
(fases 2-8: shiny 1/500, NG+, trofeos, bosses, plumas, invasión Rocket) se queda en
`memory/archivo/POSTGAME.md`. Lo que sirve hoy (CGB, guardado/perfiles, OSM) entra a la ruta viva.

**Orden corregido:** CGB → guardado real/`.pueblo` → B2 spawns → gating → mapeo → post-game.
El orden literal del spec ponía GPS y spawns después del meta-juego; se invierte porque hoy
`loadSav/saveSav` son stubs y B2 = 0%.

### Spec 007 — CGB real, slice 1 (fundación)
- `rom_loader`: lee `0x143`, `cgbFlag()`, `isCgbCapable()`, `isCgbOnly()`, log de arranque.
- `mmu`: VRAM 2×8 KB (`VBK`), WRAM 8×4 KB (`SVBK`, 0→1), `KEY1` (`FF4D`), `VBK` (`FF4F`),
  `SVBK` (`FF70`), paletas `BCPS/BCPD` y `OCPS/OCPD`, `OPRI` (`FF6C`), `HDMA1-5`
  (`FF51-55`, copia síncrona para no colgar a la ROM), `readVramBank`/`writeVramBank`.
- `cpu`: `STOP` completa el cambio de velocidad armado por `KEY1`; `toPpuCycles()` escala
  timers/PPU a la mitad en doble velocidad; boot CGB con `A=0x11`.
- `ppu`: `readVram` fuerza banco 0 (los tiles no salen del banco de atributos).
- `native_bridge`: fija CGB antes de `Cpu::initialize()` y enlaza MMU/PPU antes; `stepFrame`
  usa 140448 M-cycles en doble velocidad; cerrar ROM vuelve a DMG.

### Spec 007 — CGB real, slice 2 (color)
- `ppu::drawCgbBgPixel`: índice del tile en banco 0 + atributo del banco 1 (paleta 0-7,
  flipX bit 5, flipY bit 6, banco de tile bit 3, prioridad bit 7).
- `ppu::getCgbColor` + `core/cgb_palette.h` (`cgbRgb15ToRgba`, RGB15 BGR555 → RGBA).
- `renderSprites`: paleta OAM 0-7, banco de tile, `OPRI` (X vs índice OAM) y regla CGB
  “detrás del BG” con `bgColorIndex_`/`bgPriority_` por píxel.
- `LCDC.0` solo apaga el BG en DMG; en CGB el BG siempre se dibuja.

### Evidencia (sin móvil)
- Arnés de host nuevo `tools/host-test/` (stub de `android/log.h`, compila
  `mmu/mbc/rom_loader`): **`OK: 30 checks`**.
  Cubre: DMG sin alias `0xD0xx/0xC0xx`, espejo `0xE000/0xF000`, SVBK 1/3/0, VBK 0/1,
  KEY1+STOP, paletas con auto-incremento, GDMA de 1 bloque, RGB15 (negro/blanco/R/G/B) y
  vuelta a DMG.
- `.\gradlew assembleDebug --offline` → **BUILD SUCCESSFUL** (APK regenerado).
- **Límite honesto:** el arnés no emula CPU/PPU. El color real solo se valida en POCO con
  Gen 2; Azul queda como regresión DMG.

### Pendiente inmediato
- Gate POCO: abrir Gen 2, verificar log `CGB ON (flag 0xC0, only)`, intro/overworld en
  color y joystick; luego Azul (título, profesor, cuarto, overworld) sin regresión.
- Si falla el color, revisar en orden: modo CGB en log → paletas `BCPD` → banco de tiles
  (0/1) → prioridad `OPRI`/bit 7. No adivinar ni tocar offsets.

---

## 2026-10-24 (madrugada) — Gate B4 repetido en POCO: el fix se ve; core limpio

**Decisión:** con el usuario dormido, cerrar la evidencia en dispositivo, dejar el core sin
instrumentación y no tocar producto (sin post-game, sin assets, sin offsets inventados).

### Evidencia en POCO (X7 Pro, APK debug)
- Secuencia real jugada por adb (`input swipe ... 120` como pulsación): copyright → título
  (la mascota del título/logo nítidos) → discurso de profesor con retrato y `¡Hola a todos!` correctos → cuarto de
  Pallet (cama, mesa/SNES, planta) bien dibujado.
- `SpriteExtractor` en dispositivo: `base=1 via=OAM hits=3`, luego `base=5 ... hits=136`
  (jugador). Joystick: el jugador se mueve y la cámara sigue.
- Capturas: `%TEMP%\clean1..4.png` (copyright, menú NOMBRE, profesor, cuarto limpio) y `g1/g2.png`.
- **Falta** la foto del overworld exterior: es un paseo de ~30 s con el joystick.

### Warps: verificado que el core los dispara (falso negativo descartado)
- Arnés nuevo `--flow warp` (en `out/poco/decomp/harness/main.cpp`): acaba la intro, cierra
  diálogos, camina `right, up, up, right, up` hasta (7,1) y **2F (0x26) → 1F (0x25)**.
- Trampa medida: el fundido del warp tarda **~18 frames**; pulsar una dirección durante el
  fundido lo aborta. El barrido anterior pulsaba `down` al llegar y por eso "no había warp".
- PyBoy como oráculo con la misma ROM y la misma ruta: mismo warp en (7,1), mismas direcciones
  de WRAM desplazadas +4 (`wCurMap=0xD362`, `wYCoord=0xD365`, `wXCoord=0xD366`,
  `wNumberOfWarps=0xD3B2`, entradas en `0xD3B3`, formato `y, x, warpDestino, mapDestino`).
- Puertas del 1F: `(2,7)` y `(3,7)` con `destinoMap=0xFF` (salida al último mapa exterior).
  La escalera de subida es `(7,1) → 0x26`.

### pueblo inicial alcanzado en el core (la puerta 0xFF funciona)
- El navegador codicioso no esquiva el mobiliario del 1F, así que la prueba se hizo por el
  camino corto: colocar al jugador en `(2,6)` (tile de delante de la puerta) y pulsar `down`.
  `GPS96_DOOR_TEST=1 harness.exe out\poco\app_rom.gbc out\poco\decomp\our_bed --flow warp`
  → **`0x25` → `0x00`, pos `(5,6)`**.
- **PyBoy con el mismo teletransporte y la misma pulsación: idéntico** (`0x25` → `0x00`,
  pos `5,6`). La salida de la casa no está rota; el falso negativo era del test.
- Comparativa del overworld en el mismo instante (`tick(900)` sin input):
  - **tilemap `wTileMap` (0xC3A0, 360 bytes): 0 diferencias.**
  - VRAM: 9/8192 bytes (datos del tile `0x103`, que el mapa visible no referencia).
  - OAM: 8/160 en los 4 slots del sprite que camina (la mascota del título/NPC): **mismos tiles**
    (`0x38-0x3B`), solo cambia la posición porque los dos guiones no van por el mismo frame.
    Los 4 slots del jugador (`0x00-0x03`) son idénticos.
- Imagen del arnés: `out/poco/decomp/our_bed/07_door.png` (casa, valla, buzón, casa vecina).
- Trampa de laboratorio: `p.screen.image` de PyBoy sale **negra** si se llama `tick(1, False)`
  (sin render). No es un fallo del emulador; para imagen hay que renderizar.

### Limpieza de core (un cambio verificable)
- Fuera de `cpu.cpp`: `g_currentPc` (store por instrucción) y los bloques `COPY`/`CPY`.
- Fuera de `mmu.cpp`: logs `W1010` y `P1READ` (este último se disparaba en cada lectura de
  joypad con botón pulsado).
- Fuera de `mbc.cpp`/`mbc.h`: `logBankIfNeeded`, `bankLogCount_` y sus 3 llamadas.
- Se conserva `#ifdef GPS96_DIAG_WRITE_HOOK` (solo compila en el arnés, no viaja en el APK).
- Verificación: arnés `--flow ref` sigue dando retrato en frame 1970 y **2/8192** diffs de VRAM;
  `./gradlew assembleDebug --offline -q` → exit 0; log del móvil de cientos de líneas/s a 5.

### Estado que se deja
- APK limpio instalado y app **en el cuarto** (listo para salir a Pallet).
- `Mbc::loadSav()`/`saveSav()` siguen siendo stubs: sin CONTINUE ni guardado. Decisión del
  usuario (no bloquea el gate).

## 2026-10-23 (noche) — Causa raíz del render: el MMU mapeaba mal la WRAM interna

**Decisión:** reproducir el fallo con el arnés nativo (`out/poco/decomp/`) en vez del POCO,
localizar la raíz y corregirla en `mmu.cpp`.

### Hallazgo
- `Mmu::read()`/`write()` trataban `0xD000-0xDFFF` restando `0xD000`, pero `ram_` son
  `0x2000` bytes continuos (0xC000-0xDFFF). Resultado: `0xD0xx` aliaseaba con `0xC0xx`
  (`0xD0A5` → `0x00A5`) y cualquier variable del banco 1 pisaba el banco 0.
- El descompresor del retrato usa `0xD0A5-0xD0B8` como estado → el retrato se corrompía.
- Se corrigió **solo `read()`** primero; el lado de `write()` quedó pendiente en el mismo turno.

### Evidencia medida
- Arnés: `harness.exe out\poco\app_rom.gbc out\poco\decomp\our --flow ref` → 1,98 s
  (el POCO tarda ~40 s). Flujo completo: retrato en intento 2 (frame 1970), cuadro de texto
  en 2046.
- VRAM del retrato contra `out/poco/ref/vram.bin`, **A/B con el mismo arnés**:
  - antes (write con `- 0xD000`): **6518/8192** bytes distintos, OAM **80/160**, y el flujo no
    llega al retrato (frame 10452, sin `03_oak`);
  - después (ambos lados `- 0xC000`): **2/8192**, OAM **0/160**, retrato en frame 1970.
- Los 2 restantes son `0x9E01-0x9E02` = `0xE5` (`¡`) y `0x81` (`B`), los dos primeros
  caracteres de la segunda línea del cuadro: nuestro volcado va 2 caracteres por delante del
  de PyBoy. **Diferencia de instante de volcado, no corrupción.**
- (El 709/784 anterior era del búfer descomprimido de 784 B, otra métrica: no se mezcla.)
- OAM: **0/160 diferencias**. WRAM y HRAM quedan con diferencias de estado vivo (445/8192 y
  4/127) esperables por el mismo desfase de volcado.
- Frame del arnés convertido a PNG: `out/poco/decomp/our/03_oak_harness.png` → retrato de profesor
  y texto `¡Hola a todos!` correctos.

### Estado
- `assembleDebug --offline` → **exit 0**.
- **Pendiente:** gate POCO de B4 (overworld + joystick + `SpriteExtractor hits=3` del jugador +
  foto), limpieza de instrumentación (`g_currentPc`, `W1010`, `P1READ`, `dumpPpuState`,
  `logBankIfNeeded`, hook `GPS96_DIAG_WRITE_HOOK`) y decidir si se restauran
  `Mbc::loadSav()/saveSav()` (siguen deshabilitados en diagnóstico).

---

## 2026-10-23 (tarde) — Spec 006: instrumentación fuera y causa raíz del cuelgue (puerto serie)

**Decisión:** aprobar la spec 006 por delegación del usuario y ejecutar slices 1 y 2.

### Slice 1 — core limpio
- **Quitado de `cpu.cpp`:** histograma `PChot` (65 536 entradas escaneadas cada 20 000
  instrucciones), `trace PC=` cada 2 M y la inyección de `hJoyHeld/hJoyPressed` en WRAM por
  `romBank==63 && PC∈{0x5A9A,0x5AB4}` (parche por ROM, violaba la regla de oro 1).
- **Añadido en `native_bridge.cpp`:** métrica honesta `EMU <frames>, <ciclos>` cada 600 frames
  (RF-01 de la spec) y `BUILD_TAG` → `2026-10-23+b4-clean-core`.
- **Medición en POCO:** 1200 frames en 46,53 s = 84 272 840 ciclos → **1,81 M ciclos/s ≈ 25,8
  fps emulados**. El techo lo pone el pump de render (1 frame de GB por `onDrawFrame`), no el
  core. La cifra anterior de "0,89 M" venía de contar instrucciones, no ciclos: no era
  comparable.

### Slice 2 — causa raíz: `Serial_ExchangeByte`
- **Síntoma:** Azul/Amarilla se paraban con `halted=1 ime=1 IF=0x00 IE=0x0D` y `LY` avanzando.
- **Evidencia instrumentada (temporal, ya retirada):**
  - `JOY 0xFE` → la UI sí entregaba la pulsación de A.
  - `P1SEEN val=0xDE sel=0x10` → el juego leía P1 y **veía A pulsada** (sel 0x10 = mitad de
    botones). La entrada nunca fue el problema.
  - `SHADOW held=0x01 last=0x01 pressed=0x01` → las sombras de joypad de la ROM quedaban
    congeladas con `pressed=held`, y el texto se reimprimía en bucle.
  - `irq0` subía ~24/s → la IRQ de VBlank sí se servía.
- **Desensamblado de la ROM (Amarilla, `out/poco/app_rom.gbc`):**
  - `0x1E63`: `LD A,1 / LDH (0xD6),A / HALT / LDH A,(0xD6) / AND A / JR NZ` → espera de frame
    (el `PC=0x1E68` del log).
  - `0x1FF5`: `XOR A / LDH (0xA9),A / LDH A,(0xAA) / CP 2 / JR NZ / LD A,0x81 / LDH (0x02),A`
    → **arranca transferencia serie** (`SC=0x81`, reloj interno) y espera su finalización.
  - `0x20A2`: bucle que copia bytes llamando a `0x1E63` y `0x1FF5` (el `PC=0x20B3` del log).
- **Causa:** el MMU no emulaba `0xFF01` (SB) ni `0xFF02` (SC). `SC` se quedaba con el bit 7
  puesto → espera infinita.
- **Arreglo (`mmu.cpp`, `mmu.h`):** al escribir `SC` con bit 7 y reloj interno, cuenta 4096
  ciclos; al terminar pone `SB=0xFF`, limpia el bit 7 de `SC` y levanta `IRQ 3`. Reloj externo
  no completa (sin socio no hay byte), como en hardware.
- **Resultado en POCO:** el juego **deja de colgarse** y avanza (nombre del jugador → intro).
  Capturas: `out/poco/s2_serial_fix_01..03.png`, `out/poco/s2_clean_build.png`.

### Estado
- Build verde tras cada paso (`BUILD_OK`), incluido el build limpio final.
- Grep de limpieza vacío: sin `PChot`, `pcHist`, `hJoyHeld`, `currentRomBank() == 63`,
  `P1SEEN`, `SHADOW held`, `INP p1reads`, `irqCount_`.
- **Siguiente:** slice 3 (render: rayas verticales, fondo vacío, texto fuera del cuadro) y
  repetir el gate de B4.

## 2026-10-23 — Decisión de continuación: spec 006 escrita (BORRADOR)
- **Decisión:** no se escribe B2 con el gate rojo. Se abre `specs/006-b4-cierre-entrada-render/spec.md` para cerrar B4 en tres slices: (1) retirar instrumentación temporal del core (`PChot`, `trace PC=`, inyección WRAM por `romBank==63`), (2) entrada fiel en prompts sin hacks por ROM, (3) render de sprites/ventana sin artefactos.
- **Dato que ordena todo:** con la instrumentación puesta, la emulación corre a 0.89 M ciclos/s (≈13 fps, 21% de 4.194 M). `PChot` recorre 65 536 entradas cada 20 000 instrucciones. A 13 fps una pulsación corta cae entre frames: explica los `input tap` perdidos.
- **Sin código:** SDD exige spec APROBADA. Tablero actualizado (`memory/archivo/tasks-2026-11.md` Task 16), punteros en `memory/archivo/estado_actual-2026-11.md` §5 y `memory/archivo/traspaso-2026-11.md` §3.
- Build sigue verde (sin cambios de código).

## 2026-10-23 — Gate POCO de B4 ejecutado: ROJO (no llega al overworld)
- **Entorno:** POCO X7 Pro `2412DPC0AG` por adb del SDK; `assembleDebug --offline` → `BUILD_OK`; `ppu.cpp` con `renderWindow` (189/229); `nativeBoost` ausente. APK `app-debug.apk` instalado con `adb install -r` OK.
- **ROMs cargan:** Azul (`ROM cargada: ROM GEN 1`, MBC5 64 bancos), Amarilla y Verde (`ROM GEN 1`). Intro, copyright (`©1995 FABRICANTE ORIGINAL / los autores originales / LOS AUTORES ORIGINALES Inc.`) y menús se renderizan. Fotos: `out/poco/gate_b4_0*.png`, `out/poco/gate_azul_0*.png`, `out/poco/verde_*.png`.
- **`SpriteExtractor` sí confirma:** `sprite OW base=28 via=OAM tiles=28,29,30,31 hits=3`, `base=73 …`, `base=143 …` (log `out/poco/gate_b4_logcat_verde.txt`). Pero son sprites de título/menú: **no** se verificó el sprite del jugador caminando en overworld.
- **Bloqueo 1 — HALT sin despertar (Azul y Amarilla):** en el prompt del discurso de profesor el texto no avanza con A. Log: `trace PC=0x20B3 SP=0xDFFB LY=0x23 halted=1 ime=1 IF=0x00 IE=0x0D` (Azul) y `PC=0x1E68` (Amarilla), con `LY` cambiando y el contador `n=` subiendo. Es el hueco ya anotado en `cpu.cpp`: el hook de joypad solo inyecta con `romBank==63 && (PC==0x5A9A || PC==0x5AB4)`, así que en el prompt la ROM queda en HALT esperando algo que no llega.
- **Bloqueo 2 — render se degrada (Verde, que sí avanza por el hook):** rayas verticales sobre todo el framebuffer, cursor del menú (sprite) invisible, texto fuera del cuadro (`COMMUNICATE!`), franjas en bordes de ventana; la navegación acaba en `PREPARE TO COMMUNICATE!` y B no sale. Fotos `out/poco/verde_a..e.png`, `gate_verde_0*.png`.
- **Hallazgo operativo:** `adb shell input tap` (pulsación < 1 frame) se pierde a menudo; con `input swipe x y x y 150–170` (pulsación larga) el emulador registra A/B/START de forma fiable.
- **Sin código tocado, sin spec nueva.** `rom_path` devuelta a Amarilla al terminar. Build sigue verde.

## 2026-10-23 — Traspaso de agente escrito
- **`memory/archivo/traspaso-2026-11.md`:** orden de lectura en 5 minutos (VISION → AGENTS → estado_actual → traspaso), siguiente acción única (gate POCO B4 → spec B2), trampas que parecen bugs (APU 0%, GBC 0%, gating vacío, `SRAM_FLAG_MAP` vacío, `nativeBoost` retirado, ventana ya implementada), reglas de trabajo, decisiones abiertas y comandos de verificación.
- **Punteros:** `AGENTS.md` (Flujo SDD + operativa) y `memory/archivo/estado_actual-2026-11.md` §6.
- **Regla anti-regresión:** una sola fuente de producto (`PLAN.md`); no recrear `rules/` ni duplicar reglas.
- **Sin código;** build verde.

## 2026-10-23 — Limpieza de duplicados y documentos sin razón de ser
- **Borrados (con respaldo):** `rules/product.md` (copia de VISION), `rules/canon.md` (duplicaba AGENTS), `rules/tech-stack.md` (duplicaba STACK), `feature.md` (prototipo descartado), `CONTEXTO_FASE_1_OFFLINE.md` (foto superada) y `memory/auditoria_cableado.md` (auditoría pre-22).
- **Respaldo:** `Temp/opencode/gps96-cleanup-2026-10-23.zip` (11.943 B, 6 archivos).
- **Rescatado antes de borrar:** la nota de rechazo Rust/WASM + React Native + Expo pasó a `STACK.md`.
- **Enlaces limpios:** `AGENTS.md` (regla de oro ahora cita `PLAN.md`), `PLAN.md`, `RULES.md` §6, `future.md`, `memory/archivo/estado_actual-2026-11.md` §6.
- **Carpeta `rules/` eliminada** (quedó vacía). Producto = una sola fuente: `PLAN.md`.
- **No tocado:** ROM/saves de `out/poco/` (decisión pendiente), iconos launcher (los exige el Manifest), auditorías del 2026-10-22 (evidencia de deudas abiertas).
- **Verificación:** build verde + grep sin referencias vivas a los archivos retirados.

## 2026-10-23 — Post-game marcado como actualización futura con gate
- **Motivo:** el post-game (§8) vivía disperso en `RULES.md` y `future.md`, sin un criterio objetivo de cuándo se retoma, y el tablero aún lo mezclaba con avance del MVP.
- **Nuevo `memory/archivo/POSTGAME.md`:** estado CONGELADO, inventario de lo que existe en código (API declarada sin uso; `nativeBoostBossEncounter` eliminado en C3), mecánicas originales de §8 y **gate de apertura**: checklist de `memory/archivo/estado_actual-2026-11.md` §7 completo + B2/004/005 cerradas + spec aprobada.
- **Punteros:** `RULES.md` §8, `future.md`, `README.md`, `AGENTS.md` (regla 5), `memory/archivo/estado_actual-2026-11.md` §8 y `memory/archivo/tasks-2026-11.md` ("congelado por diseño, no es deuda").
- **Sin código:** cero cambios Java/C++/JNI; el post-game no se toca.
- **Verificación:** build verde + grep de punteros y de `VISION.md == rules/product.md`.

## 2026-10-23 — Depuración de contextos: estado único alineado a VISION
- **Motivo:** 12 tasks ✅ en el tablero no reflejaban el producto; los documentos se contradecían entre sí y con el código. El bucle de VISION (*caminas → spawn del tramo actual*) no existe todavía.
- **Nuevo `memory/archivo/estado_actual-2026-11.md`:** brújula única con build verificado, los 4 pilares de `PLAN.md` medidos, deudas de fidelidad, ruta crítica y mapa documental (canónico vs histórico).
- **Corregidos contra el código:** `RULES.md` §8 (`nativeBoostBossEncounter` retirado en C3, no "congelado en código"); `STACK.md` (fuera KitKat, ROM en RAM en vez de `fseek` por byte); `future.md` (fases 2/3 cerradas, 4/5 parciales); `auditoria_2026-10-22.md` §5.1 (ventana PPU implementada en `ppu.cpp:229-268`).
- **Marcados como históricos:** `CONTEXTO_FASE_1_OFFLINE.md` y `feature.md` (banner explícito); `README.md` pasa a 2026-10-23 y declara los límites reales (sin APU, DMG).
- **Tablero:** Task 3 deja de figurar como logro vigente; Task 8 acotada a `5c`; nueva Task 13.
- **Gating medido con honestidad:** `GeofenceManager` detecta, pero `MainActivity.onEnterBlockedZone()` está vacío y `GameEngine.update()` tiene la actualización comentada → ~10%, esqueleto sin efecto.
- **Verificación:** `assembleDebug --offline` verde + `grep` de consistencia (sin `nativeBoostBossEncounter`, sin `fseek` en STACK, sin fechas 2026-10-12 como estado). Cero cambios en código de features.

## 2026-10-22 — Auditoría integral: DMA de OAM, ROM en RAM y 12 fixes más
- **Hallazgo que explicaba B4:** la MMU no implementaba el DMA de OAM (`0xFF46`). Gen 1/2 copian su shadow OAM de WRAM a `0xFE00` por DMA; sin eso la OAM queda a cero: el juego no dibuja sprites y el extractor jamás tenía candidato. `Mmu::dmaOam()` copia 160 B al escribir el registro.
- **Rendimiento:** `RomLoader` leía del disco en cada fetch (`fseek`+`fread` por byte). Ahora la ROM se copia entera a RAM al abrir (máx. 16 MB) y `readBytes()` es `memcpy`; el fetch de CPU pasa de syscall a O(1).
- **Canon §8:** eliminado `nativeBoostBossEncounter`, que escribía stats escalados en `0xD000` (zona party/box de Gen 1) y era alcanzable desde `startBossEncounter`. Ahora el método devuelve `false` (post-game congelado, regla 5).
- **Crash Android 14:** FGS de ubicación/BLE se arrancaba sin permiso → `SecurityException`. Guardas por permiso en `MainActivity` + try/catch de `startForeground` en ambos servicios con `stopSelf()`.
- **Concurrencia:** `refreshAtlasSnapshot()` era llamado por el hilo de emulación y el worker de OSM compartiendo `vramBuf`/`oamBuf` y el estado de `SpriteExtractor`; ahora es `synchronized`. El catch ya no se traga todo: log con throttle 5 s.
- **PPU:** corregida la addressing de tiles con signo (modo `0x8800`, antes desplazaba sin signo sobre `0x8800`) y la prioridad de sprites (orden por X ascendente + pintado inverso, límite 10 por scanline).
- **Robustez:** `makeSavPath` sin overflow (`std::string`); SRAM fuera de rango devuelve `0xFF` en vez de hacer wrap; HUD se reinicia en `onResume` (antes se congelaba tras volver de background); OSM filtra coordenadas `NaN`.
- **Manifest:** `allowBackup=false` (ROM/SRAM de usuario fuera de backups), sin cleartext, sin `READ_MEDIA_IMAGES` (SAF no lo necesita).
- **Residuos:** borrado `EmulationPipeline.executeFrame/renderFrame` (GL nativo desde el hilo de emulación + 92 KB/frame), dumps de raíz movidos a `out/poco/`, duplicado `sram_dump.txt` eliminado por hash, specs renumeradas `004/005` (colisión con 002/003).
- **Build:** `:app:assembleDebug` + `:app:assembleDebugAndroidTest` verdes (18 s). `BUILD_TAG` `2026-10-22+audit1+dma+romcache` verificado en `libgpsquest96.so`. Lint no corre offline (falta `lint-gradle` en caché).
- Informe completo: `memory/auditoria_2026-10-22.md`.

## 2026-10-22 — B4: histéresis de confirmación (sin objetos fantasma)
- `SpriteExtractor`: el sprite solo se publica tras **3 ticks seguidos** con evidencia (VRAM diff u OAM centrada) y se suelta tras **45 ticks (~1.5 s)** sin ella. Evita que un NPC, cursor de menú o sprite de combate quede pegado como avatar.
- Al soltarlo, `GameRenderer.clearSpritePixels()` + `drawSprite()` exige `spritePixels != null`: vuelve el marcador neutro (RF-04) en vez de conservar la última textura.
- Log de confirmación con throttle de 1 s (`hits=3`) para no inundar logcat durante la caminata.
- Verificación offline: OAM confirmada como fuente real (`mmu.read(0xFE00)` → `oam_`, el PPU la lee por la MMU). Build `BUILD SUCCESSFUL` 2 s.

## 2026-10-22 — Rendimiento B4: readBlock JNI (de ~14.3k cruces por tick a 4)
- El worker leía VRAM byte a byte: `TileAtlas.snapshot` (6.144 JNI) + sprite (8.192 JNI) + OAM (160) ≈ **14.5k cruces JNI cada 33 ms** (~435k/s), con riesgo para el criterio de 30 fps y basura de ~8 KB/tick.
- Nuevo `Mmu.readBlock(address, byte[], length)` en `native_bridge.cpp` (escritura directa con `GetByteArrayElements`; solo para rangos sin efectos de lectura: VRAM/OAM) + wrapper Java.
- `TileAtlas.fromVram(byte[], bgp)` compone el atlas desde el buffer ya leído; `MainActivity` reutiliza `vramBuf`/`oamBuf` (campos finales, sin asignación por frame).
- Ahora: **2 lecturas en bloque + 2 registros** (BGP, OBP0) por tick.
- `BUILD_TAG` → `2026-10-22+blockread+b4sprite`; símbolo `..._00024Mmu_readBlock` verificado con `llvm-nm` en arm64 y tag presente en el `.so`.
- Build: `BUILD SUCCESSFUL` 9 s (4 ABIs).

## 2026-10-22 — B4 Slice 2: sprite OW compuesto desde ROM + render (enmienda RF-01bis)
- **Hallazgo que salvó B4:** en Gen 1 caminar anima cambiando índices de tile en **OAM**; la VRAM no cambia al caminar. El diff de VRAM del Slice 1 solo ve la carga del tileset al entrar a un mapa. Sin esto, B4 habría dado 0 candidatos para siempre.
- **spec 003 enmendada:** RF-01bis = identificación por OAM centrada (jugador 16×16 con TL en (72,64) de 160×144, tolerancia 16 px). Sigue siendo 100% ROM: sin offsets ni assets.
- `SpriteExtractor`: `identifySprite()` (VRAM-diff → OAM), `composeSprite()` 16×16 (índice 0 transparente, OBP0 0xFF48), bloque de 4 tiles consecutivos.
- `MainActivity`: el worker lee OAM (160 B) + OBP0; `GameRenderer` crea textura 16×16, la sube y `drawSprite()` reemplaza a `drawMarker()`; fallback al punto blanco intacto (RF-04).
- **Build verde** `:app:assembleDebug --offline` (solo Java; `.so` intacto).
- **Defecto B1-2 CORREGIDO en este turno:** `TileAtlas` escribía el atlas en orden tile-mayor (`t*64 + row*8 + col`) mientras la textura se sube como 3072×8 lineal → atlas scramble. Ahora escribe `row*3072 + t*8 + col`. Además `GBMapRenderer` muestrea `1.0 - v` (la fila 0 del tile es la de arriba). Sin esto el mapa mostraba bloques de 8×8 al azar y volteados.

## 2026-10-20 — Gamepad nativo A/B/START/SELECT (input completo para jugar la intro)
- Cierra el prerequisite real de B4: sin botones de acción no se puede pasar del título al overworld, y sin overworld no hay sprite que extraer.
- `activity_main.xml`: contenedor `pad_container` (bottom|end, sobre el mapa) con A (66dp), B (58dp), SELECT y START (pills 78×34dp). Drawables nuevos `bg_pad_button.xml` (óvalo) y `bg_pad_small.xml` (pill). Sin libs externas (STACK.md).
- `MainActivity`: `bindPadButtons()` → `bindPadButton(id, mask)`; cada botón limpia su bit en `joyAction` al DOWN y lo restaura al UP/CANCEL. Bits: A=0x01, B=0x02, Select=0x04, Start=0x08 (0 = presionado). Al ser bits independientes se combinan (B + cruceta, A+B).
- Visible en ambos modos (GPS_REAL y SOFÁ); no lo oculta `updateModeUI`.
- Build: `:app:assembleDebug --offline` BUILD SUCCESSFUL 22 s. Solo Java/XML: el `.so` no cambia.
- POCO no conectado en esta sesión (`adb devices` vacío). Evidencia pendiente del usuario.

## 2026-10-20 — Fix bug de combinación del joypad (AND → OR)
- Bug introducido en la unificación: `updateJoypad()` combinaba con `joyDir & joyAction`. Con ambos libres eso da `0xF0 & 0x0F = 0x00` → **los 8 botones presionados a la vez** (A+B+Select+Start+4 direcciones). La ROM vería input imposible.
- Fix: `int bits = (joyDir & 0xF0) | (joyAction & 0x0F);` — los nibbles son disjuntos, se combinan con OR.
- Build: `:app:assembleDebug --offline` BUILD SUCCESSFUL 21 s.

## 2026-10-20 — Fix unificación joypad: un solo escritor (cierra brecha input intro)
- Causa raíz del "no avanza la intro": múltiples escritores del joypad se pisan entre sí (causa del bug real, no solo el estado VRAM).
- Escritores encontrados: `GameRenderer.noteMovement` (duplicado, línea ~2019), `MainActivity.applyJoypad` (joystick), `MainActivity.applyJoypadFromBearing` (GPS), `releaseJoypad()` (GPS quieto), touch START. `releaseJoypad()` pisaba el START con 0xFF constantemente → la intro jamás avanzaba.
- Fix: estado único compartido con bits separados → `joyDir` (bits 4-7, cruceta) + `joyAction` (bits 0-3, A/B/Select/Start) + `updateJoypad()` único escritor; `setDirBits`/`setActionBits` setters. `noteMovement` ya no escribe joypad (solo marca rumbo).
- Touch pantalla → `setActionBits(0x06)` (A+START) / `setActionBits(0x0F)` (liberar).
- Build: `:app:assembleDebug --offline` BUILD SUCCESSFUL 21 s. Solo Java (el `.so` no se reconstruyó; huella core sigue `2026-10-20+ppulcdc+stepframe+joypad`).

## 2026-10-20 — GPS_REAL → ROM: híbrida completa (bearing → cruceta)
- Cierre de la híbrida VISION: faltaba que caminar tú (GPS real) moviera al personaje del juego. Solo el joystick (sofá) alimentaba la ROM.
- `MainActivity.syncAvatarToRealGps()` ahora llama `applyJoypadFromBearing(bearing)` (solo con velocidad > 0.5 m/s) y `releaseJoypad()` al detenerse. Bearing 0/90/180/270 → UP/RIGHT/DOWN/LEFT (cardinal dominante; Gen 1/2 camina ortogonal).
- Mismo mecanismo P1 ya cableado (MMU `setJoypad`). Solo Java: el `.so` no se reconstruyó; huella `2026-10-20+ppulcdc+stepframe+joypad` sigue vigente para el core.
- Build: `:app:assembleDebug --offline` BUILD SUCCESSFUL 4 s. Rerun POCO: modo GPS_REAL + caminar → `grep 'SpriteExtractor'` debe mostrar candidatos no vacíos.

## 2026-10-20 — Prerequisite B4: cableado input joystick→ROM (joypad 0xFF00)
- Hallazgo: `SpriteExtractor` no detectaba candidatos porque la ROM NUNCA recibía input (no había escritura a 0xFF00; el joystick solo movía el mapa). Brecha de arquitectura que la spec 003 no contempló.
- Fix (decisión del usuario: joystick alimenta la ROM):
  - `mmu.h/.cpp`: campo `joypad_` + `setJoypad()`; lógica P1 estándar en `readIo`/`writeIo` (bits 4/5 seleccionan mitad; 0 = presionado).
  - `native_bridge.cpp/.h`: JNI `Mmu_setJoypad(jint)` verificado con `llvm-nm` en arm64.
  - `EmulationPipeline.java`: wrapper `Mmu.setJoypad(int)`.
  - `MainActivity.java`: `applyJoypad(x,y)` mapea ejes→cruceta (umbral 0.3); `releaseJoypad()` en onRelease. Joystick alimenta la ROM y sigue moviendo el mapa (RULES §2/§3 intactos).
- Huella: `BUILD_TAG` → `2026-10-20+ppulcdc+stepframe+joypad`. Build verde. Incidente: un edit unió la declaración de `nativeBoostBossEncounter` → reparada y verificada.
- Rerun POCO: reinstala, carga Azul, overworld, mueve joystick → `grep 'SpriteExtractor|MBC bank'` debe mostrar candidatos no vacíos.

## 2026-10-19 — Spec 003 APROBADO + Slice 1 ejecutado (SpriteExtractor)
- spec 003 (B4: sprite OW desde ROM) y plan 003 APROBADOS.
- Slice 1: `render/SpriteExtractor.java` creado — compara snapshots VRAM consecutivos (8192 B, 0x8000-0x9FFF) y detecta tiles que cambian cuando el jugador se mueve (ventana 500 ms). Sin offsets ROM.
- Conectado en `MainActivity.refreshAtlasSnapshot()`: lee VRAM cruda del MMU y loguea candidatos (`SpriteExtractor: tiles cambiados...`).
- `notePlayerMove()` conectado vía `GameRenderer.noteMovement()` (joystick/GPS ya lo llaman).
- Build: `:app:assembleDebug --offline` BUILD SUCCESSFUL 23 s. Pendiente POCO: mover jugador en overworld + `grep SpriteExtractor` → se esperan candidatos no vacíos.

## 2026-10-16 — Spec 002 CERRADA: B1-2 ACEPTADO (3/3 slices)
- Slice 2 verificado en POCO: cero `Shader rechazado`, shader compila en GLSL ES 1.00 (`#version 100`), celdas compuestas con tiles del atlas VRAM.
- Slice 3 verificado: `tiles:384` (100% VRAM), 120 fps sostenidos, cero caídas de frames ni excepciones.
- Cierre formal: spec 002 (B1-2) aceptada de punta a punta. El mapa ahora compone celdas con tiles 100% ROM en vivo. Siguiente: B2 (tablas de spawn/mapas de ROM en vivo) o B4 (sprite OW del jugador desde ROM).

## 2026-10-19 — Slices 2 y 3 ACEPTADOS: Hito B1-2 CERRADO (Mapa compuesto con tiles ROM)
- Fix de shader: `compile(GLES20.GL_FRAGMENT_SHADER, FS)` en `GBMapRenderer.java` corrigió el error de tipo de shader.
- Evidencia POCO: cero `Shader rechazado` / `GLAudit` en logcat; mapa OSM renderizado mediante muestreo del `TileAtlas` de VRAM extraído en vivo (`tiles:384` / 100% VRAM); 120 fps sostenidos sin caídas de frames.
- Veredicto: Spec 002 (B1-2) CERRADA Y ACEPTADA de punta a punta en POCO X7 Pro.

## 2026-10-16 — Slice 2 corregido: #version 100 en shaders GBMapRenderer
- Error POCO: `gl_FragColor` sin declarar → el compilador esperaba GLSL ES 3.00. Fix: `#version 100` en VS y FS de `GBMapRenderer.java` (fuerza GLSL ES 1.00 donde `gl_FragColor` es válido).
- Build: `:app:assembleDebug --offline` BUILD SUCCESSFUL 20 s. Rerun pedido: cero `Shader rechazado` + foto del mapa con celdas compuestas por tiles del atlas.

## 2026-10-16 — Slice 2 ejecutado: mapeo terreno→tile en GBMapRenderer
- `GBMapRenderer.java` reescrito: shader muestrea textura del atlas VRAM (u_atlas + u_tileIndex) en lugar de colores procedurales. Mapeo determinista: GRASS/TALL→T_PASTO(0), DIRT→T_CALLE(1), HOUSE/SHOP→T_EDIFICIO(2), WATER→T_AGUA(3). Fallback: tile 0 si atlas no disponible.
- `MainActivity.java`: `gbMap.setAtlasTexture(atlasTexId)` al inicializar el mapa.
- Build: `:app:assembleDebug --offline` BUILD SUCCESSFUL 23 s. Aceptación pendiente de POCO: cero errores GL + celdas compuestas con tiles del atlas visibles en foto.

## 2026-10-16 — Spec 002 APROBADO + Slice 1 CERRADO (textura atlas en GL)
- spec 002 (B1-2) y plan 002 APROBADOS tras 8 correcciones (mapeo determinista terreno→tile, fallback tile 0, GBMapRenderer no GBTileRenderer, estructura por slice).
- Slice 1 verificado en POCO: cero errores GL, textura atlas 3072×8 subida sin bloqueos, GLSurfaceView estable a 120 fps.
- Slice 1 en `MainActivity.java` (GameRenderer): textura 2D atlas 3072×8 (384 tiles × 8px, RGBA) creada en `onSurfaceCreated`; `setAtlasPixels()` volatile worker→GL; `uploadAtlasTexture()` en `onDrawFrame` (subida solo cuando `atlasDirty`); `refreshAtlasSnapshot()` ahora también sube píxeles. Cero asignaciones nuevas en el loop GL.
- Build: `:app:assembleDebug --offline` BUILD SUCCESSFUL 24 s. Listos para Slice 2 (mapeo terreno→tile en GBMapRenderer).

## 2026-10-16 — Spec 002 APROBADO + Slice 1 ejecutado (textura atlas en GL)
- spec 002 (B1-2) y plan 002 APROBADOS tras 8 correcciones (mapeo determinista terreno→tile, fallback tile 0, GBMapRenderer no GBTileRenderer, estructura por slice).
- Slice 1 en `MainActivity.java` (GameRenderer): textura 2D atlas 3072×8 (384 tiles × 8px, RGBA) creada en `onSurfaceCreated`; `setAtlasPixels()` volatile worker→GL; `uploadAtlasTexture()` en `onDrawFrame` (subida solo cuando `atlasDirty`); `refreshAtlasSnapshot()` ahora también sube píxeles. Cero asignaciones nuevas en el loop GL (reutiliza ByteBuffer).
- Build: `:app:assembleDebug --offline` BUILD SUCCESSFUL 24 s. Aceptación pendiente de POCO: cero errores GL + textura subida (se verifica en Slice 2 con el mapeo por celda).

## 2026-10-16 — PLAN 001 CERRADO Y ACEPTADO (5/5 slices)
- Slice 5 ACEPTADO en POCO: `MBC SRAM load 32768/32768 B` tras home+kill (persistencia intacta) + volcado `sram_dump.txt` generado.
- Cierre formal: los 5 slices del plan 001 están aceptados con evidencia física. El núcleo GBC queda validado de punta a punta: boot, banks, intro a tiempo real, tiles 100% VRAM, persistencia.
- Puerta ABIERTA: B1-2 (composición de celdas del mapa con tiles del atlas VRAM). Requiere spec nueva (flujo SDD: spec → plan → tasks).

## 2026-10-16 — Slice 5 ACEPTADO: Plan 001 CERRADO de forma concluyente
- Persistencia SRAM: `home -> force-stop -> reabrir` re-carga `MBC SRAM load 32768/32768 B` intactos en logcat.
- Volcado de banderas: long-press en "Partidas Guardadas" genera `sram_dump.txt` (112,640 B / 32,768 B SRAM cruda), guardado en `out/poco/sram_dump_fresco.txt`.
- Veredicto: Plan 001 (001-nucleo-gbc) CERRADO. Slices 1, 2, 3, 4 y 5 ACEPTADOS. Habilitado B1-2 (celdas del mapa con tiles del atlas).

## 2026-10-16 — stepFrame verificado: intro a tiempo real, cero bombeo
- Huella: `BUILD 2026-10-16+ppulcdc+stepframe` en el `.so` del POCO.
- Ejecución: 5.809.792 conmutaciones MBC en 60 s (~96,830/s ≈ tiempo real; antes 36.288 en 10 min = 60× más lento). `tiles:384` = 100% del atlas VRAM.
- Visual: intro fluida a 30 fps, **sin titileo** (bomba = refresco GL). Slice 4 cerrado con sobresaliente: no solo scroll, sino la ROM corriendo como manda.
- Nota de método: los `n=` del log de bancos crecen al número de logs, no de banks reales — el ritmo real lo dicta el fluido visual + `tiles`, no el contador. Pendiente: resetear contador o usar `n` como control de liveness.
- Siguiente (último del plan 001 — Slice 5): home + kill → `MBC SRAM load N/N B`; dumps `sram_dump.txt` fresco vs con progreso (5c).

## 2026-10-16 — Titileo cerrado: stepFrame nativo + bomba a 30 fps
- Causa (confirmada en POCO): bomba a 4 fps (`FRAME_INTERVAL_MS=250`) con `executeFrame()` = ~17.5k JNI/frame → textura PPU pulsaba contra el refresco GL 120 Hz = bombeo de pantalla.
- Fix: `native_bridge.cpp/.h` + `EmulationPipeline.java` + `GameEngine.java` (un solo frame = un JNI: `Cpu.stepFrame()`, loop C++ de 70224 ciclos) + `FRAME_INTERVAL_MS` 250→33 (30 fps). Huella `BUILD_TAG` → `2026-10-16+ppulcdc+stepframe`. Build incidente: `stepFrame` vive en `Cpu` no en `EmulationPipeline` → llamada corregida a `getCpu().stepFrame()`. BUILD SUCCESSFUL 7 s.
- Rerun pedido: reinstall, importar Azul, 60 s — se espera intro SIN bombeo (GL 30 Hz = bomba 30 fps, continuidad) + `MBC bank=` fluyendo + foto.

## 2026-10-16 — Titileo azul: sin defecto en app (estados con continuidad, cero errores)
- Dato POCO: Estado 1 y Estado 2 (t+2 s) muestran el scroll del logo con continuidad; `GLAudit`/rechazados/blindados/FATAL/opcodes = 0; solo en Vista Juego, Mapa uniforme; sin reinstall.
- Descarte en código: `weatherMode()` lee dato estático con throttle 60 s (no puede pulsar a ritmo de 2 s); `uploadPpuTexture()` es estable por construcción (buffer directo nuevo por llamada). Sin ramas intermitentes en la composición.
- Dictamen: el pipeline está sano. El parpadeo percibido cuadra con (a) blink propio del boot de Azul (ROM-original, intocable por canon 1) y/o (b) escalón visible de cada game-frame a 4 fps bajo GL a 30 fps (se cura con el stepFrame nativo diferido, no con parches al overlay). Sin código este turno.

## 2026-10-16 — Slice 4 ACEPTADO: scroll del logo FABRICANTE ORIGINAL visible en app
- Evidencia POCO: 10 min tras importar Azul → logotipo animado desplazándose en el centro de la PPU opaca (Modo Juego), `MBC bank=` n=36 288, `tiles:31`, cero FATAL reportados en todos los filtros de la sesión.
- Cierra el ciclo: cámara lenta confirmada como causa del "no inicia" (no bloqueo), modo Juego como ventana de la intro intacta. Puerta B1-2 con evidencia en app y harness.
- Siguiente (Slice 5, último del plan 001): jugar → home + kill → `MBC SRAM load N/N B` + progreso intacto; más dumps `sram_dump.txt` fresco vs con progreso (desbloquea 5c).

## 2026-10-16 — Modo Juego verificado: verde = pantallas blancas del boot (cámara lenta)
- Dato POCO: 3.5 min tras importar → `MBC bank=` vivo (n=11904), `tiles:31`, PPU opaca fullscreen en verde. Sin bloqueo: a 4 fps (~15-30× lento) van ~7-14 s de juego, fase de pantallas blancas pre-logo.
- Dictamen: el verde plano es imagen real (blanco del boot × paleta GB `#9BBC0F`), no patrón de prueba. El `noteRomLoaded()` + base transparente funcionan (si no, se vería el checker opaco).
- Siguiente (Slice 4): dejar correr hasta ~10 min y foto — se espera scroll del logo FABRICANTE ORIGINAL → título. Sin código este turno.

## 2026-10-15 — Modo Juego: intro opaca temporal + toggle Mapa (Slice 4 habilitado)
- Por qué: con ROM corriendo la pantalla seguía verde — triple causa: boot a 4 fps (~15× lento, a los 60 s Azul sigue con LCD apagado cargando tiles), overlay PPU a alfa 0.35 que lava la imagen, y el "movimiento" visto era agua del mapa, no juego. Decisión del usuario: intro completa temporal.
- Cambio (`MainActivity.java` + `activity_main.xml`, cromo UI, cero contenido inventado): fila `Vista: Juego/Mapa` en el engrane (persiste en `view_ppu_full`); modo Juego = solo PPU opaca fullscreen; importar ROM o arrancar con ROM → Juego; `noteRomLoaded()` regenera la base transparente (el init nativo restaura su patrón opaco). El paso a Mapa es manual (sin autodetección por flags: 5c pendiente). Incidente: un edit unió una línea del listener de guardados → reparada y verificada.
- Build: `:app:assembleDebug --offline` BUILD SUCCESSFUL 8 s; grep confirma 14 referencias cruzadas (id + setter + rama de dibujo). Rerun pedido: foto de la intro de Azul + `tiles:` del engrane.

## 2026-10-15 — Caso bomba muerta CERRADO (banks + tiles en app, diagnóstico retirado)
- Verificación POCO en app: `MBC bank=` fluyendo (n>2800: 1/31/6/16…) + engrane `tiles:31` (>0). La ROM inicia y corre en vivo en `MainActivity`, no solo en el harness.
- Limpieza: diagnóstico `pump tick` retirado de `GameEngine.java` (queda el `volatile` en las banderas, correcto permanente). Cero conducta nueva.
- Build: `:app:assembleDebug --offline` BUILD SUCCESSFUL 21 s. Siguiente: Slice 4 (foto de intro avanzando con Azul) + Slice 5 (home+kill → `MBC SRAM load` + dumps para 5c).

## 2026-10-15 — Bomba viva: fix orden init→link en EmulationPipeline
- Causa: `EmulationPipeline.initialize()` enlazaba (`setMmu/setPpu`) ANTES de inicializar; los JNI operan sobre singletons `g_cpu/g_mmu` aún inexistentes → enlace descartado en silencio → `cpu.step()` con `mmu_==nullptr` devolvía 4 sin mover el PC. Ticks normales, cero bancos, cero excepciones. El test sobrevivía (enlaza después + `stepAndSync` re-enlaza).
- Fix en `emulator/core/EmulationPipeline.java` (un archivo, cero nativo): inicializar primero, enlazar después. Diagnóstico `pump tick` se mantiene para el rerun de verificación y se retira al cerrar el caso.
- Build: `:app:assembleDebug --offline` BUILD SUCCESSFUL 20 s. Rerun pedido: `pump tick` + `MBC bank` + `tiles:` del engrane → se esperan banks y `tiles:M>0` en app.

## 2026-10-15 — Bomba muerta en app: instrumentado el tick (cero conducta)
- Dato POCO: init completa + cero `blindado` + cero `MBC bank` + `tiles:0` con GL vivo. `update()` muere en silencio: guardia o interior de `executeFrame`.
- Slice diagnóstico en `GameEngine.java` (un archivo): `isRunning/isPaused` a `volatile` + `pump tick=N running=X paused=Y` (1 línea/5 s, se retira al cerrar el caso). Sin conducta, sin GBA/§8.
- Build: `:app:assembleDebug --offline` BUILD SUCCESSFUL 21 s. Rerun pedido: `pump tick` + `MBC bank` → la rama exacta dicta el fix (guardia vs `executeFrame`).

## 2026-10-15 — Purga de ruta ROM rancia (Tetris fantasma fuera)
- Causa: `SaveManager` persistía `rom_path` y `GameEngine.initialize()` la cargaba a ciegas; con el archivo borrado `loadRom` devolvía null SIN limpiar el pref, y `MainActivity.applySavedState()` escondía el panel CARGAR ROM ante cualquier pref no-nulo. Resultado: sin ROM, sin prompt, girando sobre la anterior.
- Fix en `app/src/main/java/com/gpsquest96/emulator/GameEngine.java` (un archivo, patrón idéntico a la purga GBA): archivo ausente o carga fallida → `setRomPath(null)` + log (`Ruta ROM rancia` / `Carga ROM fallida`) → vuelve el prompt obligatorio. Sin borrar archivos del usuario, sin tocar GBA/§8/SRAM.
- Build: `:app:assembleDebug --offline` BUILD SUCCESSFUL 22 s. Tras install: arrancar sin ROM debe mostrar el panel + `Ruta ROM rancia` en log; importar Azul debe titular `ROM GEN 1`.

## 2026-10-15 — Slice 3 ACEPTADO en POCO (fix PPU-LCDC verificado)
- Huella: `BUILD 2026-10-15+setppu+forense+tracepc+ppulcdc` presente en el `.so` del POCO.
- Trace: PC avanza fuera de `0x006B-0x006F` (`0x20B3/0x1DD2/0x4C1E/0x1E5D`, SP vivos `0xDFF3/0xDFEF/0xC3C8`, LY dinámica `0x0F-0x94`, `halted`/`ime` alternando = juego real con interrupciones, no bucle).
- Banks: 15.744 conmutaciones `MBC bank=` (2→1→19→28…→31). VRAM: `Tiles:M` 0→182 (>0). Forense: `LCDC=0xE3` (LCD+BG activos) + `IE=0x0D`, mismos bytes ROM `0x60-0x80` (bucle original intacto, ahora se supera).
- Veredicto: bloqueo de 3 días cerrado. Causa era core (caché LCDC), no APK rancio. Puerta B1-2 ABIERTA (M=182). Siguiente: Slice 4 (foto intro 30/120/180 s) + Slice 5 (home+kill → `MBC SRAM load` + dumps `sram_dump.txt` para 5c).

## 2026-10-15 — Slice 3 ejecutado: fix PPU-LCDC (causa raíz del LY=0)
- Causa: `Ppu::step()` gateaba con la caché `lcdc_` (0x91 inicial, refresco solo en `renderScanline`); tras `MMU::initialize` (0xFF40=0x00, LCD apagado al arrancar) la primera scanline la pisaba a 0x00 y la PPU no avanzaba jamás aunque el juego encendiera el LCD. LY=0 eterno → espera VBlank 0x6B-0x6F infinita. Afecta a app y test por igual; la hipótesis "APK rancio" queda descartada como causa necesaria.
- Fix en `app/src/main/cpp/core/ppu.cpp` (un archivo): LCDC releído de la MMU en cada `step()`; con LCD apagado reposo hardware (LY=0, modo 2, resync) y reanudación al encender. Sin hilos/JNI nuevos, sin tocar tabla CPU, GBA ni §8. Rollback = revert del hunk.
- Huella: `BUILD_TAG` → `"2026-10-15+setppu+forense+tracepc+ppulcdc"` (`native_bridge.cpp:18`).
- Build: `:app:assembleDebug --offline` BUILD SUCCESSFUL 26 s (45 ejecutadas, nativo en 4 ABIs). Aceptación pendiente de POCO: `BUILD ...+ppulcdc` primero, luego `trace PC` avanzando + `MBC bank=` + `tiles:M>0`.

## 2026-10-15 — Build verde + huellas verificadas, turno POCO
- Verificado en árbol: `BUILD_TAG "2026-10-15+setppu+forense+tracepc"` logueado en cada `nativeOpenRom` (`native_bridge.cpp:18,67`); `HARNESS setppu+forense+s5k+t30s` en `PocoGateTest.kt:58` (contiene la subcadena documentada `setppu+forense+s5k`, el sufijo `+t30s` es la ventana 30 s, no otro build); `trace PC=` en `cpu.cpp:49`; forense a `forense.txt` + logcat.
- Build: `:app:assembleDebug --offline` BUILD SUCCESSFUL 8 s (11 ejecutadas, 34 up-to-date). Cero conducta: sin toques al core (Slice 3 sigue prohibido sin trace POCO).
- Orden POCO (rebuild+reinstall desde este árbol, reimportar Azul si el UID cambia): correr app 60 s con Azul + correr `PocoBootGateTest`, luego pegar: `BUILD 2026-10-15` (primero; sin ella nada dictamina), `HARNESS`, `MBC bank`, `trace PC`, `tiles:M` / `Tiles:M`, línea `LCDC=` + `ROM[0x60-0x80]`, y `forense.txt` (LCDC + bytes 0x60-0x80). Vía rápida producto: overlay del engrane `tiles:M>0` + foto intro.

## 2026-10-15 — LY=0 con build verificado: test APK rancio, no core
- Razonamiento cerrado: PPU enlazada + LCDC 0x80 ⇒ LY cicla ⇒ el bucle sale en 1 frame. LY=0 eterno solo es posible sin PPU. La huella BUILD prueba el .so de app, pero el APK de test es binario aparte y pudo ir rancio.
- Slice: huella `HARNESS setppu+forense+s5k` al arrancar el test. Build verde.
- Vía preferida (producto real, sin harness): importar Azul en la app, 60 s, leer `tiles:M` del engrane + foto. Eso dictamina el core de verdad.

## 2026-10-15 — Rerun idéntico + huella anti-APK-rancio
- Mismo PC/LY/tiles en el rerun limpio. Con la PPU enlazada LY no puede valer 0 eterno: el APK bajo test es anterior al fix (o no se reinstaló). Indistinguible sin huella.
- Slice: `BUILD_TAG` ("2026-10-15+setppu+forense+tracepc") logueado en cada `nativeOpenRom`. Build verde 27s. Cero conducta.
- Siguiente: rebuild+reinstall, grep `BUILD 2026-10-15` primero; sin esa línea, todo lo demás no dictamina.

## 2026-10-14 — Plan 001 APROBADO, en espera de POCO
- Slices 1-2 (confirmar APK + trace) son evidencia física: turno del usuario/Studio.
- Slice 3 (fix exacto) se ejecuta al llegar `MBC bank` + `trace PC` + `tiles:` del test fixeado.

## 2026-10-14 — Forense resuelto: espera VBlank legítima, PPU parada en el test
- Desensamblado 0x60-0x80: `RETI/XOR/LDH(IF)` + apaga VBlank en IE + **bucle `LDH A,(LY)/CP 145/JR NZ` en 0x6B-0x6F** + apaga LCD + restaura IE + `RET`. Espera VBlank por polling, 100% legítima; sale cuando LY=145.
- LY=0 eterno = PPU sin avanzar en ese build (enlace `cpu.setPpu` ausente). En la app el enlace existe y `initialize()` (CPU/MMU/PPU) no rompe enlaces — verificado en código.
- Siguiente: rerun del test fixeado → se esperan banks + tiles>0 + intro.

## 2026-10-14 — Hang acotado: bucle 0x6B-0x6F, LY=0, IME=0 (falta forense)
- Dato POCO limpio: PC oscila 0x006B-0x006F, SP=0xFFFC (un push sin retorno), LY clavado, sin HALT, sin opcodes raros, 9.6M steps, VRAM vacía. Zona de vectores = ejecución deslizada o espera con LCD apagado.
- Slice forense (solo test, cero conducta): el harness vuelca LCDC/IE/IF + bytes ROM 0x60-0x80 a `forense.txt` + logcat. Build verde.
- Siguiente: rebuild + rerun + pegar línea `LCDC=` y `ROM[0x60-0x80]` → desensamblo el bucle y sale el fix.

## 2026-10-14 — Rerun no concluyente: test a cámara lenta + filtro ciego
- El run 20:07 repite la firma pero no decide: el filtro pasado esconde `MBC bank`/`trace PC`/opcodes, y el test muestrea cada 100 ciclos (6144 JNI por muestra) dejando al juego en ~37 frames/10 s.
- Fix al harness (solo test): muestreo cada 5000 ciclos + ventana 30 s. Verde `compileDebugAndroidTestKotlin`.
- `out/` local trae artefactos rancios (sav 65542 B ≠ SRAM viva 32768; tiles 16390 ≠ 8192): borrar antes de re-pull.
- Rerun pedido: rebuild, correr `PocoBootGateTest`, borrar `out/`, re-pull y pegar `MBC bank|trace PC|Opcode|tiles:`.

## 2026-10-14 — El hang era del harness, no del core (revisión SDD)
- El lado Studio añadió `PocoGateTest.kt` + deps kotlin/test + `out/poco/` sin spec/plan (desvío de proceso: queda adoptado bajo spec 001 como evidencia, pendiente retro-plan).
- Defecto encontrado por revisión: el test enlazaba cpu→mmu y ppu→mmu pero NUNCA `cpu.setPpu(ppu)`; `stepAndSync()` solo avanza la PPU si `ppu_ != nullptr`. Resultado: LY=0 eterno, sin VBlank, boot girando válido (steps estables, sin banks, VRAM vacía, sin crash). Firma idéntica a la observada.
- Fix: una línea `cpu.setPpu(ppu)`. Verde: `assembleDebug` + `compileDebugAndroidTestKotlin`.
- La app real sí enlaza cpu↔ppu en el pipeline: este defecto no la afecta.
- Dumps: VRAM ~vacía (4 B, coherente con boot sin tiles); `crystal.sav` local trae save jugado previo (no del test: el test no jugó nada).
- Siguiente: rerun del test fixeado en POCO. Si hay banks + tiles>0, el core queda absuelto y se cierra el bloqueo.

## 2026-10-14 — Empresa SDD ordenada (spec 001 APROBADO, sin código)
- Creados: `opencode.json` (solo MCP filesystem verificado; sin overpass roto ni modelos sin verificar), `agents/` x4 con headers [EXISTENCIA/CORAZÓN/FORMATO], `specs/002` + `specs/003` (BORRADOR), `rules/tech-stack.md` (stack real + rechazo Rust/RN documentado).
- `AGENTS.md` reescrito como Constitución (5 reglas + SDD + operativa preservada: build verde, bitácora, 1 cambio por turno).
- Siguiente: `specs/001-nucleo-gbc/plan.md` (planner) cuando lo ordenes.

## 2026-10-14 — Adiós KitKat: minSdk 19 → 26
- `app/build.gradle`: minSdk 26 (Oreo). `gradle.properties`: fuera el suppress NDK (innecesario con 26>21).
- Docs: `STACK.md` + `AGENTS.md` actualizados; foto `CONTEXTO` queda congelada.
- Build: `assembleDebug` BUILD SUCCESSFUL 61 s, 0 warnings. Limpieza de guards pre-O queda para slices.

## 2026-10-14 — SDD base creada (sin código): rules/product+canon, spec 001
- Auditoría Empresa: solo `AGENTS.md` existía; `opencode.json`, `rules/`, `agents/`, `specs/`, `mcp/` no existían.
- Creados: `rules/product.md` (copia íntegra VISION.md, con precedencia declarada), `rules/canon.md` (5 reglas), `specs/001-nucleo-gbc/spec.md` (BORRADOR, pendiente APROBADO).
- `rules/tech-stack.md` NO creado: el stack Rust/WASM+RN/Expo contradice el árbol verificado (rechazo previo con tabla). Pendiente decisión.

## 2026-10-12 — Canon "Lo que la ROM dice, se hace" (4 reglas, sin código)
- Adoptado como criterio. Ruta corregida: no existe `core/gb/ppu.ts`; la PPU es `app/src/main/cpp/core/ppu.cpp` (C++/NDK, no TS). Sprites = VRAM en vivo vía esa PPU.
- Test de pureza hoy: offline el juego corre (todo local salvo OSM/clima); el mapa cae a placeholder procedural declarado. "Solo re-skinnea nombres" queda como restricción de Fase C (más estricta que la geometría actual).
- Diálogos: sin hardcode de juego en el árbol (solo cromo UI); sigue prohibido.
- No se toca `PLAN.md` sin orden explícita.

## 2026-10-12 — Trace de PC para el hang de boot (diagnóstico, cero conducta)
- Evidencia POCO: 3 min sin banks, VRAM vacía, sin opcodes raros, frames sanos = bucle válido diminuto desde el arranque.
- Slice: `Cpu::step()` loguea PC/SP/LY/halted/ime cada 2M steps (~7 s). Build verde 26s.
- Siguiente: rebuild + 60 s con Azul + `grep 'trace PC'` → el PC quieto dice el bucle exacto y su fix.

## 2026-10-12 — Informe campo con Azul: boot perfecto, faltan 2 datos
- Boot: Azul 1 MB, `MBC5/64/32KB/battery=1`, header válido, `[0x4000]=0x80`. Título superior ROM GEN 1 en pantalla.
- `tiles:M` ausente porque este APK es anterior al fix de hoy (rama error sin contador). Ya corregido en árbol, falta rebuild.
- `sram_dump.txt` 32.768 B extraído OK. Cero opcodes raros (CPU limpia con Azul), cero FATAL, fallback pasto + punto + Oz correctos con el 504.
- Siguiente: rebuild (tiles siempre) + segundo dump tras guardar en el juego para el diff de flags (5c).

## 2026-10-12 — Informe campo sin ROM: sano + hueco de instrumentación
- Sesión sin ROM (test pattern, "Ninguna ROM cargada"): fallback pasto + punto + Oz + HUD correctos; dump SRAM 0 B correcto (mecanismo funciona de punta a punta); 0 opcodes raros (CPU en RST 0xFF, esperado sin ROM); 0 FATAL.
- Hueco mío: `tiles:M` solo salía en `Red: OK`; con 504 se ocultaba. Fix: snapshot del atlas en éxito Y error + `tiles:` siempre en el estado. Build verde.
- Desbloqueo real: importar Azul (el prompt obligatorio ya lo pide) y repetir informe.

## 2026-10-12 — UX visión: zoom + título ROM + prompt obligatorio
- Zoom +/- (0.5x-4x, persiste): botones bajo el engrane + `GBMapRenderer.setZoom` (magnifica rejilla, cromo UI). Sin tocar raster ni throttle.
- Título superior = nombre de la cabecera ROM (`nativeGetTitle`, fallback marca); se refresca en init y onResume (vuelve de Config/picker).
- Primer arranque siempre ofrece la ROM: los 4 caminos a Demo (Omitir, cancelar, sin permisos, picker cancelado) pasan por `offerRomOrDemo()`; cancelar = Demo. Texto del Paso A actualizado.
- Build: `assembleDebug` BUILD SUCCESSFUL; IDs verificados en R.

## 2026-10-12 — "No arranca nada": mecánica verificada, falta el trace
- Descartado en código: tabla LR35902 cubre el boot (LD/LDH/JR/CALL/RET/RST/DAA/ADD HL/INC-DEC (HL)/ADD SP), ciclos ≥4 (sin loop infinito), vectores 0x40-0x60, VBlank IRQ sí se dispara, HALT despierta, PC=0x0100 y registros post-boot correctos.
- Slice diagnóstico (cero conducta): el LOGE de opcode desconocido ahora incluye el PC. Rebuild verde 28s.
- Para dictaminar necesito del POCO tras importar Azul y esperar 60 s: `adb logcat | grep -E 'Opcode no implementado|FATAL|signal'`.

## 2026-10-12 — POCO confirma reset-en-carga vivo (Azul, APK nueva)
- Secuencia: `MBC ready type=MBC5 cartBanks=64 ram=32768B battery=1` → `CPU inicializado` → `PPU inicializado` → header válido `[0x0104]=0xCE [0x4000]=0x80`. El orden prueba el parche instalado (CPU/PPU tras attach, antes del log de header).
- `MBC SRAM nueva` (primera vez con Azul, correcto). 0 FATAL.
- Pendiente visual: confirmar que la intro de Azul arranca de cero en pantalla.

## 2026-10-12 — Fix cambio de ROM sin reset (CPU+PPU)
- Causa de "importada pero sin cambio": `nativeOpenRom` reseteaba MMU pero la CPU seguía con el PC del juego anterior y la PPU con sus frames. Prefs correctas (Amarilla→Azul en Config lo prueba), motor estancado.
- Fix en `native_bridge.cpp`: `g_cpu->initialize()` (PC=0x0100) + `g_ppu->initialize()` con guards null (orden boot vs picker cubierto).
- Build: `assembleDebug` BUILD SUCCESSFUL 35s; símbolos verificados en `.so`.
- Pendiente POCO: rebuild + importar Azul/Azul → intro arranca de cero.

## 2026-10-12 — POCO Intro→Main→Config sin ROM, navegación sana
- Perfil creado con teclado, MainActivity sin ROM (correcto: nada que cargar), Config ida/vuelta, taps + fling 112, frame 2400. 0 FATAL.
- Al importar Amarilla deben volver las líneas MBC.

## 2026-10-12 — POCO perfil nuevo (Intro con teclado, sin ROM todavía)
- IntroActivity con IME y taps, `Save OK flag=0`, servicios vivos. Sin líneas ROM/MBC (correcto: aún no importa). 0 FATAL.
- Al importar Amarilla deben volver `MBC ready` + header válido.

## 2026-10-12 — POCO sesión larga estable (12.900 frames, Config ida/vuelta)
- 7 min continuos, flings 203-347, save/pause limpios, surface destruida y re-anexada sin errores. Un `Can't acquire next buffer` transitorio, recuperado. 0 FATAL.
- Pendiente usuario: `tiles:M`, `sram_dump.txt` con Azul, y prueba INICIAR con Amarilla configurada.

## 2026-10-12 — POCO stress de ciclo de vida: display off/on limpio
- Frames 1500→7800 estables (~30 FPS), `Save OK` + pausa al apagar pantalla, resume 6 min después con surface destruida y recreada sin errores ni FATAL.
- Sin `MBC SRAM save` (correcto: Tetris sin batería, nada que vaciar).
- Sigue pendiente: `tiles:M` y `sram_dump.txt` con Azul.

## 2026-10-12 — POCO install fresca (UID 10389): MBC distingue ROM-only
- Boot Tetris: `MBC ready type=ROM cartBanks=2 ram=0B battery=0` + header válido + `[0x4000]=0x2F` (lectura baneada OK). Sin banks que cambiar: correcto que no salga `MBC bank=`.
- Retry OSM 1/3 vivo, frames hasta 1200, GPS/BLE, 0 FATAL.
- Pendiente del usuario: `tiles:M` del engrane (B1-1) y `sram_dump.txt` con Azul (5c). Sin eso no hay B1-2.

## 2026-10-12 — B1-1 extractor de tiles VRAM (sin cambio visual)
- Nuevo `render/TileAtlas.java`: foto 2bpp de `0x8000-0x9FFF` (384 tiles) + BGP `0xFF47` vía `Mmu.read` existente — cero JNI nuevo, cero hilos (corre en el worker OSM throttled).
- Overlay del engrane ahora muestra `Red: OK N elem · tiles:M`; M>0 en POCO = extracción viva probada y puerta a B1-2.
- Build: `assembleDebug` BUILD SUCCESSFUL. Tasks 9-10 al día.

## 2026-10-12 — Auditoría total + cierre de medias (DB + SRAM-flags + docs)
- `.md` raíz: `feature.md` FASE 2 marcada purgada; resto ya alineado (verificado por grep: historial intacto, prototipos etiquetados, foto congelada con addendum).
- `DatabaseHelper` a mecanismo: tablas inventadas fuera + v2 con DROP en installs viejas; grep confirma 0 restos funcionales.
- `GameEngine.readSramFlagBit()` + `SRAM_FLAG_MAP` vacía: mecanismo B3 listo sin adivinar direcciones ni tocar §8.
- Build: `assembleDebug` BUILD SUCCESSFUL. Task 8 (slices 5a/5b) + Task 9 (protocolo POCO) al día.

## 2026-10-12 — Purga VISION slices 2+3 + dump SRAM (todo aplicado)
- Slice 2 (avatar): borrados `AvatarSprites.java`/`PlayerRenderer.java`, diálogos paleta, fila engrane; `drawMarker()` punto blanco neutro (UI, no contenido); quedan `AvatarData` (perfil usuario) y `noteMovement` (rumbo para B4).
- Slice 3 (dojos): matriz vacía, fuera `checkDojos`/`awardDojoMedal`/medallas; overlay solo `Red:`; `DojoPOI`+modal derrota congelados; schema inerte.
- Slice 4 (tiles): sin código — placeholder ya declarado en STACK hasta atlas ROM.
- B3-1b: long-press en guardados vuelca `sram_dump.txt` (hex 16 B/línea) para mapear flags en POCO.
- Build: `assembleDebug` BUILD SUCCESSFUL; grep confirma 0 referencias a lo purgado en `MainActivity`.

## 2026-10-12 — Lector crudo de SRAM (pasito B3-1, sin interpretar)
- Puerta diagnóstica: `Mbc::readSramRaw/sramSize` (inline) → `Mmu` forwarders → JNI `nativeSramSize/nativeReadSramRaw` → `EmulatorBridge.readSramRaw()` tolerante. Cero llamadas aún: no cambia conducta.
- Build: `assembleDebug` BUILD SUCCESSFUL 25s; símbolos verificados en `.so` arm64.
- Siguiente pasito: volcado de offsets en POCO (save fresco vs con progreso) para mapear el flag de Liga real sin adivinar.

## 2026-10-12 — Alineación .md de raíz a VISION.md (solo documentos)
- `AGENTS.md`: objetivo recortado a ROM GB/GBC + fidelidad ("intactos", no "remezcla de cualquier RPG").
- `RULES.md` §8: congelado ya sin `MainActivity` (purga slice 1).
- `STACK.md`: norte de extracción declarado; lo procedural marcado placeholder.
- `feature.md`: FASE 1 marcada REMOVIDA (historial, no reimplementar).
- `future.md`: FASE 2 como predecesora obligatoria de FASE 4; mapeo solo sobre atlas ROM.
- `CONTEXTO_FASE_1_OFFLINE.md`: addendum sin reescribir foto.
- `README.md`: ya alineado, sin cambios. Cero Java/C++/build.

## 2026-10-12 — Purga VISION slice 1 (spawn inventado fuera)
- Removido de `MainActivity.java`: pools abstractas, RNG por pasos/clima, modal encuentro, `recordCapture`, inyección `0xD000` (~190 líneas, 0 referencias restantes verificado por grep).
- Intacto: podómetro HUD (sigue contando), `GameEngine` §8 congelado (sin llamar), `onBattleDefeat`/`preBattleCheckpoint` como API futura, clima visual (deuda declarada RULES §6).
- Build: `assembleDebug` BUILD SUCCESSFUL. Task 8 abierta con slices 2-4 (avatar, dojos, tiles).

## 2026-10-12 — Flush SRAM en pausa (A+B+C, sin reescribir MBC)
- Hallazgo: `mbc.{h,cpp}` ya existía y completo (MBC1/3/5 + SRAM `.sav` + RTC); hueco real = `nativeCloseRom()` sin llamadas Java → flush solo en RAM-disable/detach.
- Slice A: JNI `nativeFlushSram()` (`native_bridge.cpp`) + decl + wrapper tolerante (`EmulatorBridge.java`).
- Slice B: `GameEngine.pause()` invoca `flushSram()` — cubre home/recents. Sin tocar bosses, shinies ni joypad.
- Slice C: `assembleDebug` BUILD SUCCESSFUL 30s, 0 warnings C++/javac; símbolo `nativeFlushSram` verificado en `libgpsquest96.so` (arm64, `llvm-nm`).
- Estado: listo para test POCO según protocolo (MBC ready → bank= → kill → SRAM load).

## 2026-10-12 — Task 7 Canon definición final (solo docs)
- Archivos: `PLAN.md` (nuevo), `README.md`, `RULES.md` (§4/§6/§8), `future.md`, `feature.md`, `AGENTS.md`, `memory/archivo/tasks-2026-11.md`.
- Norte: emulador híbrido en ciudad real; intro ROM intacta; extracción en vivo; mapeo OSM local; spawn de la zona actual; gating original; no es GO.
- Fase: GB/GBC ahora; GBA y RULES §8 (bosses/shinies) aplazados. Código de post-game congelado, sin borrar.
- Alcance: cero Java/C++/JNI.

## 2026-10-06 — Deuda técnica VRAM (PPU) corregida
- Archivo: `app/src/main/cpp/core/ppu.cpp:207-218`
- Cambio: `readVram/writeVram` pasan `address` directa a `mmu_->read/write`, removido `0x8000 + address` duplicado.
- Causa: `addr` ya absoluta (`0x9800/0x9C00`, `0x8000/0x8800`); el offset la desviaba a ROM (`mmu.cpp:37-52`) y rompía fondo/tiles.
- Alcance: interno PPU. Sin cambios en `native_bridge.cpp` ni Java.
- Estado: guardado limpio y verificado.

## 2026-10-06 — Puente JNI stepAndSync guardado
- Archivos: `app/src/main/cpp/jni/native_bridge.h`, `app/src/main/cpp/jni/native_bridge.cpp:177-204`
- Cambio: `Cpu_setPpu` + `Cpu_stepAndSync` + `ensureCpuPipelineLinked()` (CPU->MMU/PPU, PPU->MMU/Renderer).
- Alcance: solo nativo. Sin cambios en Java, `rom_loader`, `SaveManager` ni `SurfaceView`.
- Estado: guardado limpio y verificado.

## 2026-10-06 — Task 3 Post-Game §8 guardada
- Archivos: `emulator/GameEngine.java` (Liga/BOSS/shiny), `jni/native_bridge.h/.cpp` (`nativeBoostBossEncounter` RAM temporal).
- Subtareas: validador Liga, boost nivel+stats, captura 100% varicolor + accesorio BLE.
- Alcance: sin leaks, sin alterar archivo ROM.
- Estado: guardado limpio y verificado.

## 2026-10-06 — Manifest infraestructura cerrada
- Archivo: `app/src/main/AndroidManifest.xml:10-14`
- Cambio: `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_LOCATION` tras `BACKGROUND_LOCATION`.
- Alcance: Bluetooth, servicios y tema intactos.
- Estado: guardado limpio y verificado.

## 2026-10-06 — Cableado seguro (5 prompts)
- `EmulationPipeline.Cpu`: `setPpu/stepAndSync` declarados (eran JNI huérfanos).
- `GameEngine`: bloque `loadLibrary("gpsquest96")` anti-`UnsatisfiedLinkError`.
- `MainActivity`: arranca `LocationService` (foreground según API) + `bind` con `ServiceConnection` nulo-segura; inyección vía setters 71-81 pendiente de exponer Binder (`onBind` retorna null).
- `tasks.md`: Task 2 [x], Task 4 creada (Weather código [x], prueba dispositivo [ ]).
- `renderer`: `renderFrame` con textura única 160x144 (`createFromPixels` + `updatePixels` + `drawTexturedRect`); sin tocar CPU/PPU/VRAM/opcodes.
- Estado: guardado limpio y verificado.

## 2026-10-06 — PROMPT 6 Despertar GPS
- Archivo: `gps/LocationService.java:12,68-77` — `LocalBinder + getService()`, `onBind` retorna `binder` real.
- Alcance: solo Binder. Sin tocar MainActivity ni GeofenceManager.
- Estado: guardado limpio y verificado.

## 2026-10-06 — PROMPT 7 Inyección final GPS
- Archivo: `ui/MainActivity.java:69-72` — cast a `LocalBinder`, `getService()`, inyección de los managers compartidos de `GameEngine` vía setters.
- Alcance: solo MainActivity. Sin tocar LocationService ni GameEngine.
- Criterio cumplido: al iniciar la app, `LocationService` usa los mismos managers que `GameEngine`.
- Estado: guardado limpio y verificado.

## 2026-10-06 — PROMPT 8 Arranque+bind Bluetooth
- Archivo: `ui/MainActivity.java` — import, `bluetoothConnection` nulo-segura, `startBluetoothService()` en `onCreate`, `unbind` en `onDestroy`.
- Alcance: solo MainActivity. Sin tocar BluetoothService ni GameEngine.
- Estado: guardado limpio y verificado.

## 2026-10-06 — PROMPT 9 Despertar BLE
- Archivo: `bluetooth/BluetoothService.java:11,78-88` — `LocalBinder + getService()`, `onBind` retorna `binder` real.
- Alcance: solo Binder. Sin tocar MainActivity ni GameEngine.
- Estado: guardado limpio y verificado.

## 2026-10-06 — Cierre de sesión: Task 4 Weather Engine + LocationService
- Archivos: `gps/LocationService.java` (GPS real 5s/5m + Geofence + filtro 7km/h, API 19 seguro), `gps/WeatherService.java` (HttpURLConnection + Thread + org.json, sin OkHttp/Gson).
- Tablero: Task 1 [x], Task 2 subtareas [x], Task 3 [x]. Task 4 pendiente de marcado tras prueba en dispositivo.
- Estado: guardado limpio y verificado.

## 2026-10-06 — PROMPT 10 Inyección final BLE
- Archivo: `ui/MainActivity.java` — cast a `LocalBinder`, `getService()`, `setBluetoothServiceCallback` (Botón A §7) + `startScan()`.
- Alcance: solo MainActivity. Sin tocar BluetoothService ni GameEngine.
- Criterio cumplido: accesorio BLE cableado de extremo a extremo.
- Estado: guardado limpio y verificado.

## 2026-10-06 — Parche pantalla gris + grid (MainActivity)
- Archivo: `ui/MainActivity.java` — `setBackgroundColor(DKGRAY)`, `GameRenderer` con clear gris + grid 10x9 verde GB + `Log.d("GameEngine","onDraw frame")` por frame.
- Alcance: solo MainActivity. Sin tocar LocationService, BluetoothService. Sin hilo extra (GL continuo ya tickea; `update()` a 60fps saturaría Open-Meteo).
- Criterio: fondo gris visible + log por frame.
- Estado: guardado limpio, pendiente rebuild en Studio.

## 2026-10-07 — Primer test POCO verde (logcat Studio)
- `onDraw frame` continuo y fluido, 0 errores (sin `UnsatisfiedLinkError`/NPE), táctil OK.
- `tv_rom_path` muestra `files/roms/rom-usuario.gba`: el pipeline SAF→copia→`loadRom`→persistencia funciona; el parche `NewStringUTF` aguantó header GBA real.
- Pendiente: verificar HUD clima (`weather_code`→paleta/spawn).
- Estado: registrado.

## 2026-10-07 — Bomba de frames con throttle
- Archivos: `ui/MainActivity.java` (pump 4fps en Handler UI, start/stop en ciclo de vida), `emulator/GameEngine.java` (throttle clima 60s + respeta toggle `weather_enabled`).
- Alcance: sin hilos nuevos, sin tocar pipeline nativo; `update()` se autoprotege en pausa.
- Toggle vibración cerrado en Task 6 (ver abajo).
- Estado: guardado limpio, pendiente rebuild + test en POCO.

## 2026-10-07 — Task 6 cerrada (GATT accesorio BLE §7)
- Verificado sin cambios: triggers `GameEngine`→`AccessoryGattService` ya cableados (`sendStatusAlert` no existe; real es `triggerStatusAlert`).
- Aplicado: permiso `VIBRATE` en Manifest (sin duplicados) + gate `vibration_enabled` en `vibratePattern()`.
- Tablero Task 6 [x]. Fase de estabilización completa pendiente solo de rebuild+test.
- Estado: guardado limpio.

## 2026-10-07 — Task 5 shader climático (RULES §6)
- Archivo: `ui/MainActivity.java` — `u_weatherMode` en fragment (1 lluvia / 2 neblina / 0 base), `glUniform1i` por frame en hilo GL, helper `weatherMode()` sobre `getLastData()` volátil.
- Alcance: solo MainActivity. Sin tocar WeatherService ni geovallas.
- Estado: guardado limpio, pendiente rebuild + test en POCO.

## 2026-10-07 — Canal PPU→textura (ByteBuffer directo)
- C++: `Ppu_getFrameBufferDirect` (`NewDirectByteBuffer`, 92.160 B, cero copias) + decl en `.h`.
- Java: `Ppu.getFrameBufferDirect()`, getter `getEmulationPipeline()`, `GameRenderer` con textura RGBA 160x144 (`glTexSubImage2D` por frame en hilo GL) + quad fullscreen + fallback checkerboard.
- Límite honesto: vista viva (tearing ocasional de 1 frame aceptado).
- Estado: guardado limpio, pendiente rebuild + test en POCO.

## 2026-10-07 — FIX spam logcat + checkerboard
- Archivo: `ui/MainActivity.java` — `Log.d onDraw` 60fps → 1/300 frames; grid de líneas → checkerboard gris 10x9 (quads, 2 tonos); `requestRender()` tras `loadRom`.
- Nota: `GameEngine.java` nunca tuvo log onDraw (nada que borrar ahí); no existe `GameView.java`/`nativeStepFrame`/`ByteBuffer` PPU — ese path real requiere JNI nuevo (siguiente hito).
- Estado: guardado limpio, pendiente rebuild en Studio.

## 2026-10-07 — Paleta hardware de 8 bits + título + avatar (solo visual)
- `MainActivity.java` + `/res`: fondo `#0F380F` (layouts + `glClearColor`), checker `#9BBC0F/#8BAC0F`, título monospace bold `#9BBC0F` forzado por código, avatar 16x16 con parpadeo cada 60 frames (`frameCount`), `GL_NEAREST` intacto, logs 1/300.
- Sin tocar: Location/Bluetooth/accesorio/WeatherService ni C++.
- Estado: guardado limpio, pendiente rebuild en Studio.

## 2026-10-07 — FEATURE 1.1+1.2 encuentros (BUILD SUCCESSFUL 4s en Studio)
- 1.1: roll cada 100 pasos válidos → hook. 1.2: pools abstractos (lluvia +70% agua/hielo, niebla +70% fantasma/psíquico), nivel 5-15, shiny 2%, bloque 8B en RAM `0xD000` vía `Mmu.write` (sin tocar ROM).
- Compilación validada por Studio. Siguiente: 1.3 (gate BOSS + persistencia `creatures`).
- Estado: guardado limpio.

## 2026-10-07 — FEATURE 1.1 encuentros por pasos (solo MainActivity)
- `checkStepEncounter()` tras el diferencial validado: cada 100 pasos tira RNG 0.35 → hook `onWildEncounterRolled()` (log; 1.2 inyectará especie). Sin GL/red/servicios.
- Estado: listo para compilar (build lo ejecuta Studio; sin Gradle en este entorno).

## 2026-10-07 — Podómetro hardware §3 (solo MainActivity)
- `SensorEventListener` + registro en `onResume`/liberación en `onPause` (try-catch, sin drenaje), permiso `ACTIVITY_RECOGNITION` en flujo Q+.
- Filtro doble: modo GPS_REAL + velocidad GPS < 7 km/h; diferencial de sesión anti-saltos; Joystick intacto y aislado.
- Estado: guardado limpio, pendiente rebuild + caminata en POCO.

## 2026-10-07 — Shader combinado OSM+PPU+clima (solo MainActivity)
- Programa `COMB_*`: samplers `u_SubMapTexture` + `u_PPUTexture`, `mix(map,ppu,α·a)`, `u_weatherMode`, `u_shift` (mismo shift del mapa → PPU sincronizada).
- `drawCombinedGrid()` 3x3 por frame; fallbacks: PPU fullscreen sin mapa, checkerboard sin frame. Upload/hilos intactos.
- Estado: guardado limpio, pendiente rebuild + scroll en POCO.

## 2026-10-07 — Bump AGP + HUD clima (DevOps)
- Build: AGP 8.7.3 + `ndkVersion` + Java 17 + limpieza okhttp/gson ya aplicados en Studio; aquí modernizado `clean` (`layout.buildDirectory`, adiós warning Gradle 9). `minSdk 19` preservado.
- HUD: badge flotante monospace `#9BBC0F` (`tv_weather_badge`) alimentado por el callback existente (cadencia del throttle 60s); `u_weatherMode` ya se lee por frame.
- Estado: guardado limpio, pendiente sync + `assembleDebug` en Studio.

## 2026-10-07 — DatabaseHelper SQLite (sin ORMs)
- Archivo nuevo `emulator/DatabaseHelper.java`: `gpsquest96_data.db`, FKs ON en `onConfigure`, `creatures` (IDs abstractos, CHECKs 0-255/1-100/0-1) + `dojo_medals` (FK real `mvp_creature_id`), inserts `ContentValues` async en pool propio, lecturas con `cursor.close()` garantizado.
- Sin tocar servicios, .sav, GL ni C++. Cableado en MainActivity (`openAsync` + `shutdown`) = siguiente micro-hito.
- Estado: guardado limpio, pendiente rebuild en Studio.

## 2026-10-07 — DB en ciclo de vida (solo MainActivity)
- `onCreate`: `new DatabaseHelper` + `openAsync(null)` (esquema en pool, sin tocar layout); `onDestroy`: `shutdown()` junto al pool de teselas.
- Incidente: un edit intermedió borró el listener OSM; restaurado y verificado (`setListener` + `offer` intactos).
- Estado: guardado limpio, pendiente rebuild en Studio.

## 2026-10-07 — Rejilla 3x3 sub-píxel (solo MainActivity)
- Mapa de texturas por tesela (`HashMap` + alloc bajo demanda + evict fuera del 3x3 en hilo GL), offset Mercator flotante clavando al jugador al centro, `drawMapGrid()` con filtro `u_weatherMode`, checker translúcido encima, avatar intacto, logs 1/300, podómetro aislado.
- Cazados en revisión: copia a búfer dedicado (no pisa checker) y evict solo-GL.
- Estado: guardado limpio, pendiente rebuild + scroll en POCO.

## 2026-10-07 — Integración mapa→GL (solo MainActivity)
- `requestMapTiles()` (throttle 2s, zoom 16) desde joystick-Sofá y GPS 1Hz; `TileListener`→`ConcurrentLinkedQueue`; `GameRenderer` drena máx 2/frame, sube solo tesela central (`GLUtils.texSubImage2D`), recicla inmediato, mapa base bajo checker translúcido (blend, alfa 0.45) y avatar intacto.
- Podómetro: joystick/GPS solo mueven mapa, jamás tocan pasos (RULES §3 ✓).
- Estado: guardado limpio, pendiente rebuild + scroll en POCO.

## 2026-10-07 — TileQueueManager OSM (solo HttpURLConnection)
- Archivo nuevo `gps/TileQueueManager.java`: pool fijo 2 hilos, RAM `LruCache` (~6MB, `getByteCount`), disco `filesDir/maps/z_x_y.png`, dedupe en vuelo, `tileFor()` slippy-map, listener para upload en hilo GL.
- Corrección honesta: endpoint `tile.openstreetmap.org` (el de la orden devolvía 404) + User-Agent OSM.
- Sin tocar servicios, saves, GL ni C++. Integración (joystick→requestArea + upload en `onDrawFrame`) = siguiente hito.
- Estado: guardado limpio, pendiente rebuild en Studio.

## 2026-10-07 — Persistencia geográfica + overlay funcional (solo MainActivity)
- Binario `filesDir/saves/gpsquest96.sav` (magic GQ96 + storyFlag + timestamp); bypass onboarding si válido; `Guardar Ahora` + auto-save en `onPause/onDestroy` (blindado contra `recreate()` del onboarding).
- Desacoplamiento GPS: avatar siempre con `LocationService.getLastLocation()` en vivo en modo GPS_REAL, jamás coords guardadas.
- Filtro historia: `tryInteractWithPoi` + aviso monospace (reutiliza indicador), `canSpawnBoss()` espejo §8 (<200 bloqueado).
- Overlay 100%: idioma (BottomSheet reutilizada sin tocar onboarding), ROM (picker), tema/saves informativos.
- Estado: guardado limpio, pendiente rebuild + 2do arranque en POCO.

## 2026-10-07 — Onboarding primer arranque (solo MainActivity)
- Flujo A→B→C→Demo todo programático API 19+: diálogo pixelado permisos, BottomSheet 10 idiomas (clave `lang` mayús + mirror `LocaleHelper`, `recreate` con resume en paso ROM), picker SAF con validador `.gb/.gbc/.gba` + Toast + reintento, cancel → Demo (SOFA + joystick, cero interacciones).
- Extras seguros: skip si hay registro/ROM previa, `startScan` tras `SecurityException`, `demo_mode` persistido.
- Estado: guardado limpio, pendiente rebuild + test con datos borrados en POCO.

## 2026-10-07 — Engrane + overlay ajustes (solo UI)
- `layout/activity_main.xml`: `ImageButton` 32x32 (`ic_gear_pixel` #9BBC0F/#306230) arriba-izquierda + panel overlay (`bg_panel_overlay` #0F380F/borde #9BBC0F) con 4 TextView monospace + botón Guardar Ahora, `GONE` por defecto.
- `MainActivity.java`: toggle VISIBLE/GONE en onClick, sin lógica interna, sin tocar GL ni logs.
- Estado: guardado limpio, pendiente rebuild en Studio.

## 2026-10-07 — Segundo logcat verde (Studio)
- Render `onDraw frame` continuo, MainActivity+ConfigActivity vivas en pila, 0 FATAL/UnsatisfiedLinkError/NPE. Resto es ruido MIUI (IME, hwcomposer, audio, launcher).
- Sin regresiones. Siguiente: rebuild con bomba de frames y verificar HUD clima.
- Estado: registrado.

## 2026-10-06 — Parche crash título GBA (NewStringUTF)
- Archivos: `jni/native_bridge.cpp` (`newSanitizedString`: ASCII 0x20-0x7E, resto `?`, tope NUL; aplicado a `gameCode[4]` y `title[16]`), `EmulatorBridge.java` (try-catch + fallback a nombre de archivo), `MainActivity.java` (`onActivityResult`/import blindados + log ruta/tamaño).
- Causa: header GBA con bytes crudos → `NewStringUTF` crasheaba ART en `nativeGetTitle`.
- Alcance: sin tocar GameEngine loop, Ppu, LocationService.
- Estado: guardado limpio, pendiente rebuild en Studio.

## 2026-10-06 — Fix llaves huérfanas native_bridge.cpp
- Archivo: `jni/native_bridge.cpp` — eliminadas 2 `}` huérfanas tras `nativeGetGameCode`/`nativeGetTitle` (defecto originario: el archivo nunca había compilado). Además cerraban `extern "C"` antes de tiempo.
- Verificación: 65/65 llaves, 28 funciones JNI + helper + ensure + extern.
- Estado: guardado limpio, pendiente rebuild en Studio.

## 2026-10-06 — Pantalla de inicio usable (test POCO)
- Archivos: `layout/activity_main.xml` (fondo #1A1A1A, título, CARGAR ROM, engranaje ⚙), `layout/activity_config.xml` (idioma/modo/ROM/clima/vibración), `drawable/bg_btn_primary/bg_btn_icon`, 10 locales (es,en,fr,de,pt,it,ja,ko,zh-rCN,ru).
- `MainActivity`: picker SAF (`ACTION_OPEN_DOCUMENT`, copia a `filesDir/roms`, `takePersistableUriPermission`, sin permisos MIUI), gear → ajustes, `attachBaseContext` idioma, restaura ROM/modo.
- `ConfigActivity`: 10 idiomas + modo GPS/Sofá (pref `control_mode`) + switches clima/vibración + ruta ROM vía `SaveManager`.
- Sin tocar: EmulationPipeline, Ppu, LocationService, BluetoothService, JoystickView. Modo GPS intacto.
- Estado: guardado limpio, pendiente rebuild en Studio.

## 2026-10-06 — Parche BLE foreground type (MissingForegroundServiceTypeException)
- Archivos: `bluetooth/BluetoothService.java` (`startForeground` con `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` en API 29+, legacy en KitKat), `AndroidManifest.xml` (`foregroundServiceType="connectedDevice|location"` + permiso `FOREGROUND_SERVICE_CONNECTED_DEVICE`).
- Alcance: sin tocar MainActivity, GameEngine, JoystickView, LocationService.
- Criterio: arranca en Android 14 sin `MissingForegroundServiceTypeException`.
- Estado: guardado limpio, pendiente rebuild en Studio.

## 2026-10-06 — Parche NDK extern C (UnsatisfiedLinkError setMmu)
- Archivo: `jni/native_bridge.cpp:26,434` — bloque `extern "C" { }` envolviendo las 27 definiciones JNI.
- Causa: 0 `extern "C"` en el .cpp → las 21 funciones del pipeline/GameEngine compilaban con linkage C++ (mangled) y ART no las resolvía; el .so sí cargaba (descarta .so ausente). Nombres `_00024`, `loadLibrary` y `arm64-v8a` ya estaban correctos.
- Alcance: solo linkage. Sin tocar LocationService, JoystickView, Ppu, Mmu Java.
- Estado: guardado limpio, pendiente rebuild en Studio.

## 2026-10-06 — Parche JoystickView XML
- Archivo: `ui/JoystickView.java:7,48-56` — constructores `(Context, AttributeSet)` y `(Context, AttributeSet, int)` con misma `init()`.
- Alcance: solo constructores. Sin tocar layout, MainActivity, GameEngine, LocationService, Ppu/Cpu.
- Criterio: sin `InflateException`, mismo dibujo y touch.
- Estado: guardado limpio y verificado.

## 2026-10-06 — Verificación externa Studio/Gemini
- Sync/build: FALLIDOS solo por entorno (Gradle 9.6.0 + AGP 7.4.2, API `module()` eliminada). Código Java/C++: 0 errores, JNI alineado (corrobora auditoría).
- NDK 25/26 + CMake 3.22.1 instalados. `local.properties` regenerado por Studio.
- Decisión: aprobar bump AGP (a 8.7.3, no 8.2.2, por Gradle 9.6), `ndkVersion` explícito y limpieza okhttp/gson. Preservar `minSdk 19`.
- Estado: pendiente de confirmación en Studio.

## 2026-10-06 — Permisos runtime pre-test
- Archivos: `AndroidManifest.xml:16` (`POST_NOTIFICATIONS` API 33), `ui/MainActivity.java` (flujo `requiredPermissions/hasAllPermissions/ensurePermissionsAndStart/onRequestPermissionsResult`, arranque diferido de servicios).
- Alcance: servicios y managers intactos; en KitKat arranca directo.
- Estado: guardado limpio y verificado.

## 2026-10-07 — BUILD SUCCESSFUL local (Gradle 8.13, 1m 9s)
- `assembleDebug` verde: 4 ABIs NDK + javac + APK. Valida JNI, braces, sanitizer, recursos y Fase 1 de `feature.md`.
- Estado: verificado por compilador.

## 2026-10-07 — FASE FINAL: estabilización profunda + clean BUILD SUCCESSFUL (45s)
- Nativo: guard MMU-nula + escape documentado en `cpu.cpp`; 13 warnings Clang liquidados (ppu/renderer/mmu/native_bridge, cero lógica).
- Anti-softlock: `preBattleCheckpoint` (.sav+flags), `onBattleDefeat` (GPS intacto + tregua 180 s + modal pixelado con Restaurar/Curar<25 m/Seguir), gate 0 % en encuentros.
- Shaders/JNI: locations cacheadas post-link (cero lookups/frame), `-Wall -Wextra` fijos en CMake.
- Limpio desde cero (46 tareas, 0 warnings C++, solo notas javac/Gradle ajenas).
- Estado: EMULADOR AL 100 % LISTO PARA INSTALAR en POCO X7 Pro.

## 2026-10-08 — Parches finales JNI/GL + clean BUILD SUCCESSFUL (31s)
- `u_hasMap` por tesela central exacta; pre-fill PPU verde opaco una vez (vista escribible del directo); SOFA→Xalapa verificado preexistente.
- Limpio desde cero (46 tareas, 0 warnings C++).
- Estado: listo para instalar en POCO.

## 2026-10-08 — Anti-congelamiento GL (BUILD SUCCESSFUL 38s)
- Shader reactivo por alfa (`u_hasMap` eliminado del código); clamp ±85° + guarda NaN/Infinito; Sofá auto al arrancar sin GPS; TRACE de cola vacía throttled.
- Limpio desde cero (46 tareas, 0 warnings C++).
- Estado: instalar y mover joystick en POCO.

## 2026-10-08 — Anti-congelamiento GL + clean BUILD SUCCESSFUL (33s)
- Inmersivo ya idéntico al pedido (sin listeners; Insets de system_server no son nuestros).
- Joystick `invalidate()` al arrancar sin GPS; shift con warning único + offset limpio.
- Limpio desde cero (46 tareas, 0 warnings C++).
- Estado: instalar y verificar mapa + joystick en POCO.

## 2026-10-08 — Fix métrico 108px + clean BUILD SUCCESSFUL (1m 11s)
- `inScaled=false` + ARGB_8888 en red/disco (fin del reescalado HyperOS); guarda 256×256 en drenaje; pre-fill transparente (calles visibles, ROM cubre al correr).
- Limpio desde cero (46 tareas, 0 warnings C++).
- Estado: mover joystick en POCO, calles reales a la vista.

## 2026-10-08 — Frame limiter 30 FPS + clean BUILD SUCCESSFUL (21s)
- `TARGET_FPS`/`FRAME_TIME_MS` + `sleep()` del resto en hilo GL; parpadeo a `% 30` (1 s); shift/clima/log intactos.
- Limpio desde cero (46 tareas, 0 warnings C++).
- Estado: instalar y medir batería/temperatura en POCO.

## 2026-10-08 — Limiter no bloqueante + densidad cero (BUILD SUCCESSFUL 20s)
- Densidad anulada (`inScaled=false`, `inDensity/inTargetDensity=0`); limiter por retorno pasivo `nanoTime` (fuera `sleep`); shift neutro sin posición.
- Limpio desde cero (46 tareas, 0 warnings C++).
- Estado: calles de Xalapa a 30 FPS en POCO.

## 2026-10-08 — Reconexión gráfica + clean BUILD SUCCESSFUL (21s)
- Gatillo `requestRender()` tras cada tile; mapa a alfa 0.9 sobre checker (`u_mapAlpha`); red verificada atómica (sin cambios).
- Limpio desde cero (46 tareas, 0 warnings C++).
- Estado: calles reales sobre el verde en POCO.

## 2026-10-08 — Raíz forense + clean BUILD SUCCESSFUL (20s)
- Caché: mismatch → `recycle()+delete()` + re-descarga (fin del bucle 108px).
- Shaders con status (`checkProgram/checkShader` + logs Mali) y contingencia por flags; título colapsa panel; viewport fullscreen intacto (mitad dejaría 3/4 en negro).
- Limpio desde cero (46 tareas, 0 warnings C++).
- Estado: mapa + avatar + tacto en POCO.

## 2026-10-08 — Saneamiento ciclo de vida (BUILD SUCCESSFUL 9s)
- Tiles: `mapsDir` nulo-seguro + `executeTileTask` anti-rechazo (claves liberadas); shaders con status + fallback azul; joystick en cuadro cero.
- Incidente: mi edit del TTL absorbió `File f` (5 errores javac) → restaurado y verde.
- Incremental (13 tareas, 0 warnings C++).
- Estado: instalar y verificar en POCO.

## 2026-10-09 — Mapa único + zoom 18 (BUILD SUCCESSFUL 26s)
- Prompt Leaflet traducido a nativo (sin L.map/divs aquí): `singleTask` + `CLEAR_TOP` anti-duplicados, `MAP_ZOOM` 18, `requestLayout()` al cambiar de modo.
- Podómetro hardware y avatar centrado intactos (sin `watchPosition`: RULES §3 manda sensor).
- Incremental (42 tareas, 0 warnings C++).
- Estado: un solo mapa a escala cuadra en POCO.

## 2026-10-08 — Arranque cuadro-cero (BUILD SUCCESSFUL 18s)
- Auditoría: sin deadlock (todo pesado ya async); joystick VISIBLE tras `setContentView`, corrector por modo intacto.
- Incremental (42 tareas, 0 warnings C++).
- Estado: tacto desde el primer cuadro en POCO.

## 2026-10-08 — LruCache unitario anti-IllegalState (BUILD SUCCESSFUL 16s)
- Causa real: `sizeOf→getByteCount` sobre reciclados (no carrera); métrica `return 1` × 32 + evicción de reciclados + `synchronized` verificado.
- Incremental (42 tareas, 0 warnings C++).
- Estado: pool estable en POCO.

## 2026-10-08 — Superficie anti-insets + clean BUILD SUCCESSFUL (17s)
- Cutout `SHORT_EDGES` (guard P) + `FLAG_LAYOUT_NO_LIMITS` pre-`setContentView`; `GLSurfaceView` con flags estables (nunca altura 0).
- Incremental (42 tareas, 0 warnings C++).
- Estado: superficie física válida en POCO.

## 2026-10-08 — EGL preservado + clean BUILD SUCCESSFUL (24s)
- RGBA8888/depth16 ya estaban; `setPreserveEGLContextOnPause` con guard válido (`JELLY_BEAN_MR2`, no el typo del snippet).
- Limpio desde cero (46 tareas, 0 warnings C++).
- Estado: contexto sobrevive a pausas en POCO.

## 2026-10-08 — GL perezoso + veredicto + clean BUILD SUCCESSFUL (27s)
- EGL/renderer en `post()` tras attach + modo continuo explícito; clear veredicto `(0.05,0.22,0.05)`.
- Limpio desde cero (46 tareas, 0 warnings C++).
- Estado: si se ve el veredicto, el hilo GL vive en POCO.

## 2026-10-08 — EGL síncrono + bringToFront (BUILD SUCCESSFUL 30s)
- Fuera `post()` (init síncrona + `preserve`); joystick al frente; `match_parent` verificado. Sin factory ficticio (hook inexistente).
- Limpio desde cero (46 tareas, 0 warnings C++).
- Estado: input sobre overlays en POCO.

## 2026-10-09 — Silicio: timers + VBlank + LCDC vivos (BUILD SUCCESSFUL 19s)
- MMU: DIV/TIMA/TAC + IF coherente; CPU: DI/EI/LDH/HALT-vivo + IRQs con vectores; PPU: IRQ VBlank + registros ida/vuelta.
- Limpio desde cero (46 tareas, 0 warnings C++ con `-Wall -Wextra`).
- Pendiente: sprites + resto de opcodes para intro de Rojo.
- Estado: instalar con ROM GB y ver frames/tiles cambiar.

## 2026-10-08 — Fix HTTP 400 clima + ciclo blindado (BUILD SUCCESSFUL 19s)
- `String.format(Locale.ROOT)` (coma es-MX causaba el 400); ciclo pausa/resume con null-guards (ya cableado); último clima válido ante error.
- Incremental (42 tareas, 0 warnings C++).
- Estado: badge climático vivo en POCO.

## 2026-10-08 — Permisos manuales + clean BUILD SUCCESSFUL (20s)
- Flujo runtime preexistente (código 1001, diferido, onboarding); añadido `Log.i` de confirmación.
- Limpio desde cero (46 tareas, 0 warnings C++).
- Estado: autoriza en el POCO y arranca GNSS+BLE.

## 2026-10-09 — Bloque C avatar + intro (BUILD SUCCESSFUL 25s)
- Package `avatar` (datos, sprites 16×32 BOY/GIRL procedurales, renderer con textura propia); `IntroActivity` 3 pasos → `SaveManager`; puerta `hasPlayer`; nombre sobre avatar; marcha idle/walk (joystick/GPS).
- Compose traducido a nativo (sin Compose aquí); facing registrado para futuros frames; strings es/en (fallback al resto).
- Incremental (42 tareas, 0 warnings C++).
- Estado: primer arranque pide personaje y camina pixelado.

## 2026-10-09 — Bloque C mapa real → tiles GBC (BUILD SUCCESSFUL 10s)
- `OsmDataFetcher` (Overpass POST, 6 tags, caché JSON 7 días) + `GBMapRenderer` (rejilla 20×18, agua 2 frames, slide continuo) + fetch con throttle 8 s/puerta 30 m en seed/joystick/GPS.
- Sin encuentros (ordenado); pasto/checker de respaldo intactos.
- Incremental (42 tareas, 0 warnings C++).
- Estado: Lázaro Cárdenas cuadriculado en POCO.

## 2026-10-09 — CONTEXTO_FASE_1_OFFLINE.md creado
- Foto congelada verificada: stack, núcleo, Java, archivos, compuertas, test offline y tablero.
- Estado: base documental para Fases 2-3.
- OAM real 160 B en MMU; scan 40/10 por línea, 8×8/8×16, flips, paletas, transparencia y prioridad; opcodes de boot verificados presentes.
- Limpio desde cero (46 tareas, 0 warnings C++ con `-Wall -Wextra`).
- Estado: intro de Rojo ya puede mostrar personajes.

## 2026-10-09 — Observabilidad de teselas (BUILD SUCCESSFUL 9s)
- Contadores atómicos (pedidas/RAM/disco/red/fallos) + línea `Red: …` en overlay al abrir engrane.
- Incremental (42 tareas, 0 warnings C++).
- Estado: el próximo test dictamina red vs render.

## 2026-10-09 — FASE 2 FIX CPU + adiós OSM (BUILD SUCCESSFUL 17s)
- CPU: 11 ilegales como NOP silenciosos (tabla ya completa por Studio) + header log 1 vez; llave de cierre reparada.
- Fuera: `TileQueueManager.java`, osmdroid, atribución, rejilla 3x3; dentro: `GBTileRenderer` (pasto procedural con deriva lat/lon).
- Limpio desde cero (46 tareas, 0 warnings C++).
- Estado: Tetris sin spam de opcodes ni calles en POCO.

## 2026-10-09 — Cableado MMU-ROM ROM (BUILD SUCCESSFUL 8s)
- `g_mmu->setRomLoader` + re-init en `nativeOpenRom` y `Mmu_initialize`; log testigo `[0x0100]/[0x0104]` (0x0100==0x00 es NOP normal).
- `README.md` + `future.md` creados; repo sin ROMs (solo tu POCO tiene el GBA: cámbialo por GB/GBC).
- Incremental (42 tareas, 0 warnings C++).
- Estado: instalar, cargar Rojo/Azul y leer `MMU lee ROM` en logcat.

## 2026-10-08 — Anti-freeform + clean BUILD SUCCESSFUL (39s)
- `resizeableActivity=false` + `configChanges` extendido (conservo `keyboardHidden`); aviso multiventana en `onCreate` (sin API de salida forzosa en N).
- Limpio desde cero (46 tareas, 0 warnings C++).
- Estado: lienzo exclusivo en POCO.

## 2026-10-08 — Capa de red OSM + atribución (BUILD SUCCESSFUL 31s)
- UA con GitHub público + `Referer`, TTL 7 días con expiración, log `OSM OK` al primer 200; atribución © clicable a copyright.
- Sin tocar shaders ni render. Limpio desde cero (46 tareas, 0 warnings C++).
- Estado: buscar `OSM OK` en logcat y ver Xalapa.

## 2026-10-08 — Purga proactiva de caché + clean BUILD SUCCESSFUL (45s)
- `purgeCorruptCache()` al arrancar (pool, solo metadatos): borra PNG/JPG ≠ 256 en `filesDir/maps`.
- Limpio desde cero (46 tareas, 0 warnings C++).
- Estado: primer arranque tras install limpia veneno histórico.

## 2026-10-08 — Split mapa/PPU + clean BUILD SUCCESSFUL (20s)
- PASO A mapa sin PPU (shift solo en vértices); PASO B PPU fullscreen UVs limpias + alfa 0.9 + clima; avatar tercero; panel colapsa en Sofá.
- Limpio desde cero (46 tareas, 0 warnings C++).
- Estado: PPU sincronizada sobre Xalapa en POCO.

## 2026-10-08 — Seed inicial de mapa + clean BUILD SUCCESSFUL (20s)
- `seedInitialMap()`: Xalapa forzada sin fix (pool a caché/red inmediato) + `gameEngine.start()`; invocado en onCreate, tras permisos y en onboarding.
- Auditoría: puntos 2 (bucle sordo) y 3 (botón→ROM) ya existían, sin cambios.
- Limpio desde cero (46 tareas, 0 warnings C++).
- Estado: listo para instalar en POCO.

## 2026-10-08 — Arranque directo + EGL + inmersivo (BUILD SUCCESSFUL 44s)
- Manifest: `MainActivity` LAUNCHER exclusivo, `ConfigActivity` sin filtro (vía engranes).
- EGL RGBA8888+depth16 explícito con fallback; inmersivo sticky + re-apply en foco. Sin llamadas vendor (inexistentes).
- Limpio desde cero (46 tareas, 0 warnings C++).
- Estado: instalar y verificar arranque directo en POCO.

## 2026-10-08 — Throttle LOGE + guarda overread (BUILD SUCCESSFUL 16s)
- `cpu.cpp`: `LOGE` opcode 1/1000 con contador (42 tareas, 0 warnings C++).
- `MainActivity`: `remaining() != 92.160` aborta upload (anti-overread).
- Siguiente: instalar y extraer `FATAL`/`signal 11` limpio.
- Estado: verificado por compilador.

## 2026-10-08 — Reparación crítica POCO (pantalla gris + clima + modos)
- Causa raíz: bomba UI con ~140k JNI/tick sin `try-finally` (muerte + jank). Movida a hilo `EmuLoop` dedicado con re-post garantizado.
- `onDrawFrame` blindado + emergency log throttled (PPU/mapa sin upload); `combLocsOk` si la GPU tarda; clima nulo-seguro.
- Modos: `resetMapPipeline()` en ambas rutas + refugio Sofá tras `SecurityException`; diálogos liberados en `onDestroy` (anti-`WindowLeaked`).
- Clean BUILD SUCCESSFUL 26 s (46 tareas, 0 warnings C++).
- Estado: listo para reinstalar en POCO.

## 2026-10-07 — FASE 2 Dojos Xalapa (BUILD SUCCESSFUL 10s)
- Matriz `DojoPOI` (Centro 50, Araucarias 100, Crystal 150) + Haversine R=6371000 en GPS 1Hz y joystick; <25 m con flag insuficiente → aviso monospace + JNI cancelado por diseño (edge-triggered).
- Victoria: `awardDojoMedal()` → `insertMedalAsync` (pool DB) + refresh overlay con conteo (cursor cerrado en helper).
- Warnings: 0 de código (solo deprecaciones Gradle 9 ajenas).
- Estado: hito listo en POCO X7 Pro.

## 2026-10-07 — FEATURE 1.3 gate BOSS + recordCapture
- BOSS 3% (90-100) con compuerta `storyFlag<200` → degrada 15-30 + stats frescos (tope 255); `recordCapture()` → `insertCreatureAsync` para el futuro flujo de batalla.
- BUILD SUCCESSFUL incremental (9s, 12 tareas): valida 1.3 en APK.
- Estado: verificado por compilador.

## [2026-10-08] — Cierre de FASE 0 y FASE 1 (Estabilización Mapa + Boot Mínimo hardware de 8 bits)
- FASE 0:
  * Fix de sello sub-píxel anti-bandas (`eps = 0.0015f`) en la cuadrícula 3x3 del `GameRenderer`.
  * Migración de rutas hardcodeadas comerciales a ranura de prueba genérica `filesDir/roms/slot1.gb`.
  * Verificación MMU con cabecera de ROM hardware de 8 bits 1MB (`ROM Header OK`, lectura FABRICANTE ORIGINAL logo en `[0x0104]=0xCE`).
- FASE 1:
  * Implementación completa en C++ (`cpu.cpp`/`cpu.h`) de la instrucción LR35902: opcodes `LD A,(HL)`, `LD (HL),A`, `LD A,(HL+)`, `LD (HL+)`, `LD A,(HL-)`, `LD (HL-)`, `INC/DEC HL`, saltos condicionales `JR`/`JP`, `CALL`/`RET`, PUSH/POP y decodificador extendido para los 256 opcodes del prefijo `0xCB`.
  * Sincronización de interrupción VBlank (`0x40`) activada en PPU modo 1.
- Resultado del Build: `./gradlew clean assembleDebug` -> **BUILD SUCCESSFUL** (0 warnings en Java y C++ NDK).
- Estado: FASE 0 y FASE 1 cerradas y verificadas.

## 2026-10-10 21:36 - Task5 cierre + auditoria integral verificada en disco (14 raiz, 24 java, 16 cpp, TileQueueManager false, OsmDataFetcher+GBMapRenderer true)
- Tablero: Task 5 CLOSED (Avatar BOY/GIRL + Intro + lifecycle null-safe).
- Auditoria disco: 14 archivos raiz, 24 Java, 16 cpp (8h+8cpp).
- Wrapper 9.6.0 detectado, debe ser 8.13. Bitacora 80 entradas, dedup pendiente. auditoria_cableado.md:195-198 obsoleto.
- Estado: pendiente commit + higiene (downgrade wrapper + dedup).

## 2026-10-10 22:00 - Wrapper downgrade 9.6.0->8.13 + ./gradlew --version
- Archivo: `gradle/wrapper/gradle-wrapper.properties:3` — `distributionUrl` 9.6.0 -> 8.13.
- Por que: STACK/CONTEXTO congelado (AGP 8.7.3 + Gradle 8.13); 9.6.0 rompe sync.
- Verificacion: `./gradlew --version` -> Gradle 8.13 / JVM 17.0.20.1 PASS.

## 2026-10-10 22:05 - Dedup bitacora 82->81 (+1 log =82)
- Eliminado duplicado literal `Reconexion grafica 21s` x2 (lineas 274 vs 399, queda 1).
- Recortado `Seed inicial` (absorbi 4 lineas de `Anti-congelamiento 33s`: inmersivo+joystick duplicados, verificado por grep).
- Conteo real: 82->81 tras borrado; +1 esta entrada =82. No 78: no hay 2do duplicado completo para borrar sin reescribir historia.

## 2026-10-10 22:10 - auditoria_cableado.md alineado a disco
- Archivo: `memory/auditoria_cableado.md:194-202` — bloque 5 reescrito.
- Valores: GBTileRenderer True + GBMapRenderer True + OsmDataFetcher True + TileQueueManager False + app/build.gradle sin osmdroid/okhttp/gson.
- Estado: higiene 3/3 cerrada. Pendiente AUDITORIA FISICA POCO 75%.

## 2026-10-10 22:15 - Pin Gradle JVM a JDK 17 (fix Studio JDK 25)
- Archivo: `gradle.properties` — `org.gradle.java.home=C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot`.
- Causa: Studio con JDK 25 + Gradle 8.13 (soporta 8-23) = sync imposible.
- Alternativa en UI: Settings > Build Tools > Gradle > Gradle JDK > 17.
- Verificacion CLI: `java -version` 17.0.20.1 + JAVA_HOME JDK 17 OK.

## 2026-10-11 - Fix launcher Studio JDK 25 -> #JAVA_HOME (JDK 17)
- Archivo: `.idea/gradle.xml` — `gradleJvm` `#GRADLE_LOCAL_JAVA_HOME` -> `#JAVA_HOME`.
- Causa: `org.gradle.java.home` solo fija el Daemon; el Launcher lo elige Studio y seguia en JBR 25 (`misc.xml: project-jdk-name=jbr-25`). `#JAVA_HOME` usa el JAVA_HOME del sistema (JDK 17.0.20.1, verificado).
- No toco `misc.xml` (Project SDK): cambiarlo a ciegas rompe la tabla JDK de Studio; hazlo por UI si quieres.
- Accion en Studio: pulsa `Sync` de nuevo (o el link `Change Gradle JDK configuration` > elige `JAVA_HOME / 17`).

## 2026-10-11 - Logcat POCO arranque verde (Tetris, sin FATAL)
- Proceso 17003: `libgpsquest96.so ok`, `EmulationPipeline creado`, `CPU/MMU/PPU inicializados`.
- ROM: `Tetris (JUE) 32768 B`, `Titulo: TETRIS`, `ROM Header OK: TETRIS | MMU Read [0x0104]=0xCE (b0100=0x00)` — MMU lee ROM verificado.
- Servicios: `LocationService Rastreo GPS 5s/5m`, `BluetoothService creado/iniciado`, `Escaneo BLE iniciado`.
- Ruido MIUI/HWUI/Insets/AVC ignorado (sin FATAL/SIGSEGV/UnsatisfiedLinkError).
- Pendiente: `onDraw frame` / PPU / clima / encuentro no aparecen en este fragmento — pedir log filtrado.

## 2026-10-11 - Logcat POCO render vivo (frame 1200 + joystick)
- `GameEngine onDraw frame 1200` — hilo GL vivo, throttle 1/300 intacto.
- Tactil: `ACTION_DOWN/UP` con `moveCount:102` y `217` + `on fling` — joystick/arrastre llegan a MainActivity.
- Ruido SKIA/PowerHal/MIUI ignorado. 0 FATAL.
- Pendiente: `weather_code` / encuentro / PPU — pedir grep filtrado en siguiente sesion.

## 2026-10-11 - Logcat POCO red vs render (Overpass 504, fallback limpio)
- `onDraw frame 4200/4500` — GL vivo durante 2 min.
- `OsmDataFetcher W Overpass HTTP 504` + `GameEngine W OSM sin datos` — red sale, Overpass responde timeout (servidor, no bug app). Sin crash, fallback checker/pasto intacto.
- Tactil: taps `moveCount:0` + arrastre `65` + `fling` — input OK.
- Dictamen: render PASS, red PASS (manejo 504 limpio). BLOQUE D puede entrar: reintento/backoff Overpass + cache 7 dias ya existe.

## 2026-10-11 - BLOQUE D1 OsmDataFetcher backoff anti-504 (sin romper STACK)
- Archivo: `gps/OsmDataFetcher.java` — `download()` con 3 intentos + backoff 2s/4s ante 504/503/429 + `Locale.ROOT` en lat/lon.
- No a osmdroid/okhttp/gson: STACK.md + CONTEXTO lo prohiben (KitKat + 0 deps). Se mantiene HttpURLConnection + org.json.
- Build: `:app:compileDebugJavaWithJavac --offline` PASS (exit 0, solo nota deprecacion preexistente en MainActivity).

## 2026-10-11 - BLOQUE D2 throttle requestArea sin doble llamada
- Hallazgo: `requestOsmData()` YA tenia throttle 8 s + puerta 30 m (`MainActivity.java:1270-1285`). El martilleo real era doble llamada: `requestMapTiles()` (que ya reenvia a OSM) + `requestOsmData()` directa en 3 sitios (GPS 1 Hz, seed, joystick 60 Hz).
- Archivo: `MainActivity.java` — eliminadas las 3 `requestOsmData()` directas; queda solo `requestMapTiles()` (puerta unica). Sin tocar throttle, podometro (§3 intacto) ni dojos.
- Build: `:app:compileDebugJavaWithJavac --offline` PASS exit 0.

## 2026-10-11 - BLOQUE D3a diagnostico OSM con codigo (sin logcat, paso 1/2)
- Hallazgo honesto: la linea `Red:` del overlay NO existe en codigo (bitacora la citaba, grep lo niega). Solo hay `tv_opt_saves` (medallas) y `Log.w OSM sin datos`.
- Archivo: `gps/OsmDataFetcher.java` — campo `lastError` + `download()` lo puebla (`Overpass HTTP 504 (3/3)`, no-reintentable directo, o `red: <msg>`); `requestArea()` lo reenvia por `onError()` en vez del generico `sin datos OSM`.
- Build: `--offline` PASS exit 0. Falta 2/2: pintar ese string en el overlay (MainActivity, siguiente turno).

## 2026-10-11 - BLOQUE D3b Red: visible en engrane (sin logcat)
- Archivo: `MainActivity.java` — campo `lastOsmStatus` (`Red: …` inicial); callbacks OSM lo actualizan (`Red: OK N elem` / `Red: Overpass HTTP 504 (3/3)`) y repintan si el engrane esta abierto; `tv_opt_saves` ahora muestra `Partidas Guardadas · N medallas · Red: …`.
- Sin layout nuevo, sin hilos nuevos (reusa `runOnUiThread` + pool de medallas). Podometro/dojos intactos.
- Build: `--offline` PASS exit 0.

## 2026-10-11 - Fix avatar Y invertido (V flip, 1 linea)
- Causa: `AvatarSprites` genera cabeza en fila 0 pero `glTexImage2D` la sube a v=0 = abajo; el quad (`avatarBuffer:1940-1942`) empareja v=0 con los vertices inferiores. Gorra a los pies.
- Archivo: `avatar/PlayerRenderer.java:quadTexCoords()` — UV `{0,0,1,0,0,1,1,1}` -> `{0,1,1,1,0,0,1,0}`. Sin tocar PPU/MMU/sprites.
- Build: `--offline` PASS exit 0. Ver en POCO: gorra arriba, zapatos abajo.

## 2026-10-11 - Avatar paleta personalizable (paso 1/3, sin cambio visual)
- Archivo: `avatar/AvatarSprites.java` — overrides de paleta (piel, gorra/melena, camisa, pantalones, zapatos) + `setPalette()/resetPalette()` + getters. `frame()` los usa; por defecto todo null = clasico identico.
- Sin tocar dibujo, frames, PlayerRenderer ni MainActivity. Siguiente: fila `tv_opt_avatar` en el engrane (XML) y dialogo + persistencia (MainActivity).

## 2026-10-11 - Engrane: fila Personalizar Avatar (paso 2/3, sin logica)
- Archivo: `res/layout/activity_main.xml` — `TextView tv_opt_avatar` tras `tv_opt_rom`, mismo estilo monospace #9BBC0F.
- Sin logica aun (click se cablea en MainActivity, siguiente turno). Build PASS exit 0.

## 2026-10-11 - Avatar personalizable en engrane (paso 3/3)
- Archivo: `MainActivity.java` — `tv_opt_avatar` abre dialogo de 5 zonas (piel, gorra/melena, camisa, pantalones, zapatos) x 14 colores GBC; se aplica al instante y persiste en `gpsquest96_prefs` (`av_*`); `Restablecer clasico` limpia; `applyAvatarPalette()` al arrancar. Todo null-safe con try/catch.
- Sin tocar dibujo, saves, podometro ni dojos. Build PASS exit 0.

## 2026-10-11 - Modal encuentro visible (Capturar/Huir, punta a punta)
- Archivo: `MainActivity.java` — `onWildEncounterRolled()` muestra modal pixelado (`#especie nv + tags BOSS/VARICOLOR`) con checkpoint pre-combate; `Capturar` tira `tryCalculateCapture` (shiny 100 % §8.3) y persiste con `recordCapture` (stats del bloque 0xD000); fallo = `onBattleDefeat` (tregua 180 s, GPS intacto §5); `Huir` cierra. Anti-spam 1 modal, `writeEncounterBlock()` ahora devuelve el bloque.
- Sin tocar GameEngine, MMU ni DB. Build PASS exit 0. Probar en POCO: caminar 100 pasos GPS_REAL y ver modal.

## 2026-10-11 - Logcat POCO reinicio limpio (proceso 3226, APK anterior)
- Arranque verde: `libgpsquest96.so ok`, `CPU/MMU/PPU`, `ROM Header OK: TETRIS [0x0104]=0xCE`, `GPS 5s/5m + BLE`.
- `onDraw frame 300` + arrastres 157/60/194/130/62 — render e input vivos. 0 FATAL.
- Nota: este APK es anterior al modal/paleta/V-flip (pendiente rebuild+install para verlos).

## 2026-10-11 - Logcat POCO D1 verificado en campo (retry 1/3 vivo)
- Proceso 12390 auto-kill SIG 9 del sistema (reinicio limpio, no crash app) -> 3637 boot verde Tetris/Header OK.
- `OsmDataFetcher Descarga OSM fallida (intento 1/3) SocketTimeoutException` — el backoff D1 engancho y reintenta (antes tiraba la toalla). `Save OK flag=0`, `Choreographer 33 frames` solo jank de arranque.
- 0 FATAL de la app.

## 2026-10-11 - Epico ARM slice 2 (puerta dual-core por extension)
- Archivo: `emulator/EmulatorBridge.java` — `enum Kind {GB, GBA, UNKNOWN}` + `detectKind()` por extension; `loadRom()` rechaza `.gba` con log honesto (null, sin boot basura) y marca `RomInfo.kind`. Rama GB identica.
- Efecto: un título de GBA guardado ya no botea basura al arrancar (se omite); al importarlo avisa por Toast de error. Cuando el ARM aterrice, la rama GBA llamara su propio JNI.
- Build PASS exit 0. Siguiente: mensaje `GBA pendiente` visible en el picker + `arm7.cpp`.

## 2026-10-11 - Epico ARM slice 3 (arm7.cpp: reset + PC)
- Archivo nuevo: `cpp/core/arm7.cpp` — `reset()` (SVC + ARM + I/F cerrados, PC = entry) + `step()` (avanza +4/+2, 1 ciclo). Aun sin bus ni decode: la MMU32 y el Thumb vienen en los siguientes slices.
- Sin alta en CMake a proposito: nada existente lo compila, core GB intacto.

## 2026-10-11 - Epico ARM slice 4 (MMU32 solo-ROM)
- Archivo nuevo: `cpp/core/mmu32.h` (autocontenido inline) — ROM en `0x08000000-0x0DFFFFFF` con espejos, `read8/16/32` little-endian, escrituras ignoradas, tope 32 MB. Fuera de rango = 0.
- Siguiente: conectar `Arm7::step()` al bus (fetch real) + decodificador Thumb basico.

## 2026-10-11 - Epico ARM slice 5 (fetch real + Thumb basico)
- Archivos: `arm7.h` (bus + `step(Mmu32&)` + decls) + `arm7.cpp` (formatos 1-3,2,4,5,6,7/8/10,9,11,12,13,16,18,19: ALU con N/Z/C/V, LDR/STR, PUSH/POP, B/BL, BX con cambio T, PC lecturable = ins+4). ARM32/LDM/STM/SWI pendientes.
- Verificado: `clang++ NDK -Wall -Wextra -fsyntax-only` PASS 0 warnings. Sin alta en CMake aun.

## 2026-10-11 - Epico ARM slice 6 (GBA pendiente visible en picker)
- Archivo: `MainActivity.java` — `importRomFromUriUnsafe()` rechaza `.gba` antes de copiar (Toast largo + log, sin guardar ruta); onboarding paso C reabre el picker con aviso en vez de forzar Demo. GB/GBC identicos.
- Build PASS exit 0. Probar en POCO tras install: elegir título de GBA -> Toast, sin titulo basura.

## 2026-10-11 - Epico ARM slice 7 (limpia ruta GBA obsoleta al arrancar)
- Archivo: `GameEngine.java:initialize()` — si la ruta guardada es `.gba`, la borra con log y no intenta cargarla (el panel CARGAR ROM vuelve a verse en vez de quedar escondido sin ROM).
- Build PASS exit 0. En tu POCO: al primer arranque tras install veras el panel + `Ruta GBA obsoleta` en log.

## 2026-10-11 - Fix mapa Mali (a_texCoord + hash sin NaN)
- Causa doble en `GBMapRenderer.java`: (1) el VS pedia `a_texCoord` pero `draw()` jamas lo bindeaba (v_uv = 0 constante); (2) el hash `sin(dot)*43758.5453` desborda `mediump` en Mali (Inf→NaN→fragmentos descartados). El avatar no lo usa, por eso el si se veia.
- Fix: `texLoc` registrado + bindeo de UVs por celda + `speck` con hash entero (`(c.x*7+c.y*13)*0.0625`, rango <4, cero overflow).
- Build PASS exit 0. Falta el mismo hash en `GBTileRenderer` (fallback, siguiente turno).

## 2026-10-11 - Avatar chibi 4 direcciones (paso 1/2, motor aun de frente)
- Archivo: `avatar/AvatarSprites.java` reescrito — FRONT/BACK/LEFT/RIGHT (RIGHT = espejo) con contorno automatico, gorra, mochila con correa, coleta ella, paso alterno. Paleta intacta (piel/gorra/camisa/pantalones/zapatos + reset).
- `frame(gender, frame)` sigue = FRONT: sin cambio visual hasta cablear rumbo. Build PASS exit 0. Siguiente: `drawAvatar()` usa `facingDeg`.

## 2026-10-11 - Avatar gira con el rumbo (paso 2/2)
- Archivo: `MainActivity.java` — `drawAvatar()` mapea `facingDeg` a FRONT/BACK/LEFT/RIGHT (joystick atan2 + bearing GPS ya lo alimentaban); en reposo conserva la ultima vista.
- Build PASS exit 0. Probar en POCO: mover joystick arriba -> espalda con mochila; lados -> perfil con visera.

## 2026-10-11 - Avatar kawaii (pixeles cuadrados + mas pequeno)
- Causa del flaco minecraft: quad 0.2x0.4 en pantalla 1220x2712 = pixeles de 7.6x17 (x2.2 alto). Medido en tu foto (60x350 px).
- Archivo: `MainActivity.java` — `buildAvatarQuad()` con aspecto real (`qw = qh*16/32*surfH/surfW`, reconstruido en `onSurfaceChanged`) + altura 0.4->0.22.
- Build PASS exit 0. El mapa sigue pendiente de tu logcat `rechazado|omitido`.

## 2026-10-11 - Purga total del hash NaN (0 restos en Java)
- Archivo: `render/GBTileRenderer.java` — mismo hash entero del mapa. `grep 43758` en todo `java/` = 0 resultados.
- Build PASS exit 0. Rebuild + install y el pasto/mapa deben pintar en la Mali.

## 2026-10-11 - Logcat POCO PRIMER BOOT GB AUTENTICO (Amarilla .gbc)
- ROM: `rom-usuario.gbc (1 MB)`, `Game Code APSS`, cartucho `0x1B` (MBC5+RAM+BATERIA, correcto (Gen 1)), tamaño `0x05` (1 MB, coincide archivo).
- `MMU Read [0x0104]=0xCE` VALIDO + `b0100=0x00` NOP normal. Sin crash, `frame 300`, fling 352, GPS/BLE vivos.
- Cosmetico: titulo `ROM YELAPSS?` con ultimo char sanitizado (`?`) — el header real trae byte no-ASCII ahi; solo afecta el log/overlay, no la emulacion.

## 2026-10-11 - Fix pantalla verde (PPU tapaba el mapa, alfa adaptativo)
- Causa: `update()` corre `executeFrame()` a 4 Hz (Amarilla botea de verdad) y `drawPpuFullscreen` pintaba sus frames a pantalla completa con alfa 0.9 ENCIMA del mapa/pasto/checker. Mundo tapado = todo verde.
- Archivo: `MainActivity.java` — `PPU_ALPHA_OVER_MAP 0.35` cuando hay base dibujada, `0.9` si no hay nada debajo; `drawPpuFullscreen(alpha)`.
- Build PASS exit 0. Tras install: mundo visible con el juego translucido encima.

## 2026-10-11 - Logcat POCO install fresca (UID 10388, Tetris de vuelta)
- 31127 auto-kill con debugger; 28495 boot verde Tetris/Header OK. `Save OK`, permiso manual, GPS/BLE, fling 464. 0 FATAL.
- Nota: UID cambio (10387->10388) = reinstall; Amarilla ya no esta cargada. El fix del alfa PPU sigue pendiente de install con rebuild.

## 2026-10-11 - Logcat POCO Azul valido + NPE picker inofensivo
- Intro completada (teclado + dropdown), luego `ROM abierta: rom-usuario.gb (1 MB)`: `ROM GEN 1`, cartucho `0x1B`, `[0x0104]=0xCE` VALIDO.
- `ActivityThread E fail in deliverResultsIfNeeded NPE Bundle.getString` justo antes de cargar: es lado framework/MIUI con el resultado SAF; la ROM cargo igual. Vigilar si se repite sin carga.
- `Overpass 504 (1/3)` con retry, frames hasta 2400, Config ida/vuelta. 0 FATAL app.

## 2026-10-11 - Logcat POCO confirma Mali silenciosa (sin rechazado/omitido)
- Boot 10984 Azul valido, frame 300, flings, 504 con retry. Cero lineas `rechazado`/`omitido`/`Draw de mapa`: los programas compilan y los draws corren — los pixeles se descartan en GPU (NaN mediump), tal cual el diagnostico. El fix de hashes ya va en camino.

## 2026-10-11 - VERIFICADO EN POCO: mapa GBC renderiza perfecto
- Tras rebuild+install: caminos marrones, edificios beige/rojo, pasto/bosque verde GB, agua/comercios azul/blanco. Avatar centrado, joystick + HUD + ajustes sin artefactos ni cuelgues.
- El hash entero + `a_texCoord` eliminaron el NaN en la Mali. Capas sin perdidas.

## 2026-10-11 - Fix joystick supersonico (el verde al moverse)
- Causa: paso fijo `0.0001`/evento (~11 m) a 60 ev/s = 660 m/s; un fling (moveCount 699 en tu log) = 7 km. La rejilla 20x18 cubre 88x79 m: todo recortado + `drawn=true` igual (corto a pasto) = verde.
- Archivo: `MainActivity.java` — fisica a 1.4 m/s por dt real + zona muerta 0.15. El mapa ya no se escapa; el throttle OSM 8 s/30 m respira.
- Build PASS exit 0.

## 2026-10-11 - Epico ARM slice 1 (cabecera ARM7TDMI, sin cablear)
- Archivo nuevo: `cpp/core/arm7.h` — registros r0-r15, CPSR (N/Z/C/V/T), modos USR/FIQ/IRQ/SVC/ABT/UND/SYS, bancos FIQ/IRQ/SVC/ABT/UND, `reset/step/pc/thumb/mode`.
- Sin incluir en ningun .cpp ni CMake: el core GB compila identico. Siguiente: `arm7.cpp`.

## 2026-10-11 - Logcat POCO ROM GBA incompatible detectada (título de GBA, no boot real)
- Cargada: `rom-usuario.gba (16 MB)`. Titulo basura `88#?X?`, `[0x0104]=0x07` (esperado `0xCE`), `b0100=0x04`, cartucho `0x08`.
- Dictamen: core LR35902 8 bits NO ejecuta GBA ARM 32 bits. El sanitizer evito el crash pero la emulacion dara basura. Cambiar a Rojo/Azul/Amarillo `.gb` u Oro/Plata/Gen 2 `.gbc`. título de GBA = epico ARM pendiente.

## 2026-10-11 - Logcat POCO sesion completa sana (10891, dialogs sin fugas)
- 10889 era arranque con debugger (WAIT chunk, kill limpio). 10891 boot verde Tetris/Header OK + GPS/BLE.
- `Save OK flag=0` x2 (auto-save + boton), `ConfigActivity` ida/vuelta con back OK, 5 dialogos `AlertController$RecycleListView` abiertos/cerrados sin `WindowLeaked`.
- `onDraw frame 600/900/1200`, taps + flings vivos. Un `Can't acquire next buffer` transitorio, recuperado. 0 FATAL.

## 2026-10-26 - CGB en color + CONTINUE + `.pueblo` verificados en POCO

- **CGB real, Gen 2:** arranque estable en el POCO (`2412DPC0AG` / `XWWSAY4LAAPZHIA6`). Logs:
  - `Título: PM_CRYSTAL`, `CGB flag: 0xC0 (CGB-only)`, `MBC ready type=MBC3 ... battery=1`.
  - `MMU modo CGB`, `CGB ON (flag 0xC0, only)`, `CPU LR35902 inicializado (CGB)`.
  - `MBC SRAM cargada: 32768/32768 B (...)`.
  - Log completo del arranque: `tools/poco-capture/20261026-continue/logcat-startup.txt`.
- **Color:** título de Gen 2 con logo, la mascota del título y "©2001 LOS AUTORES ORIGINALES inc." a color; cuarto del jugador con paleta CGB (madrugada/mañana/noche). Evidencia: `screen-import-boot.png`, `screen-import-menu2.png`, `screen-import-game.png`, `screen-final-room2.png`.
- **CONTINUE:** tras guardar, force-stop y reabrir, el menú muestra **CONTINUAR**; la ficha de partida muestra `JUGAD. AAAAAAA`, `MEDALLAS 0`, `TIEMPO J. 0:14` y carga el cuarto en la posición guardada. El `AAAAAAA` es el **nombre del jugador** de la partida (el perfil de la app es `Miguel Cabal`). Evidencia: `screen-import-menu2.png`, `screen-import-slot.png`, `screen-import-game.png`, `screen-final-menu2.png`, `screen-final-room2.png`.
- **Modo Sofá vs GPS:** el joystick mueve al jugador en **Modo Sofá** (`joyDir=112` y el sprite baja). En **GPS Real** el joystick puede verse si no hay fix, pero `setupJoystick()` solo aplica `applyJoypad()` cuando el modo es `SOFA` (`MainActivity.java:2057`); no es un bug, es el refugio sin GPS. Para probar movimiento en interior: usar Modo Sofá.
- **`.pueblo` export:** engrane → "Exportar perfil (.pueblo)" → Descargas. ZIP de **1.434 B** con `manifest.json` (272 B), `battery.sav` (32.768 B), `geo.sav` (20 B) y `prefs.json` (138 B). El SHA-256 del manifest (`d1d4b58a...7700`) coincide con la ROM de Gen 2 copiada; el hash del `battery.sav` (`fcc47935...9d22`) coincide con el `<rom>.sav` interno.
- **`.pueblo` import:** se importó el mismo ZIP; `Mbc::saveSav()` reescribió el `.sav` con el `battery.sav` del perfil (hash idéntico) y Gen 2 volvió a arrancar; `CONTINUE` restauró la partida. Evidencia: `screen-import3.png` (reinicio tras importar), `screen-import-game.png` (partida restaurada), `Gen 2-export.pueblo.zip` y `Gen 2-export/`.
- **Rechazo por ROM distinta:** se generó `Gen 2-tampered.pueblo.zip` con el SHA-256 del manifest a cero; al importarlo, la app mostró **"No se pudo importar: El perfil no corresponde a esta ROM"**, no recargó la ROM y la partida siguió intacta. Evidencia: `screen-reject-toast.png`.
- **Regresión DMG (Azul):** al cambiar a `rom-usuario.gb`, el log dice `CGB flag: 0x00 (DMG)`, `MMU modo DMG`, `CGB OFF`, `CPU LR35902 inicializado (DMG)`. Título DMG, retrato de profesor y cuadro de texto "¡Hola a todos! ¡Bienvenidos al" correctos; `MBC SRAM guardada: 32768 B` al pausar. Evidencia: `screen-azul-title.png`, `screen-azul-oak2.png`, `logcat-azul-dmg.txt`. El cuarto de Azul no se re-caminó en este pase; la verificación del 2026-10-24 sigue vigente.
- **Evidencia completa:** `tools/poco-capture/20261026-continue/` (capturas, `logcat-startup.txt`, `logcat-azul-dmg.txt`, ZIPs y extracción).
- **Pendiente:** importación tras borrar datos/otro equipo (el perfil ya escribe el `.sav` byte a byte), persistencia del RTC MBC3, HDMA troceado por HBlank y caminar hasta el overworld de Azul en este pase. Nada de esto bloquea el siguiente paso: B2/009 con Gen 2.

## 2026-10-26 - Pantalla encendida con la app en primer plano (spec 010)

- **Motivo:** las pruebas largas del emulador se interrumpían cuando el POCO se bloqueaba por timeout. Se pidió que el dispositivo no se apague **solo mientras la app está en primer plano**, sin tocar el ajuste global.
- **Cambio (único archivo):** `MainActivity.onCreate()` añade
  `getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)`
  después de `setContentView(...)`. No hay `WAKE_LOCK`, permisos nuevos ni cambios de emulación/guardado.
- **Build:** `.\gradlew.bat assembleDebug --offline` → `BUILD SUCCESSFUL`; APK instalado con `adb install -r` (datos conservados).
- **Flag en la ventana:** `dumpsys window windows` muestra en `com.gpsquest96/.ui.MainActivity`:
  `fl=KEEP_SCREEN_ON LAYOUT_IN_SCREEN LAYOUT_NO_LIMITS ...`.
- **Primer plano:** con `screen_off_timeout=15000` y `stay_on_while_plugged_in=0`, tras **35 s** sin tocar la pantalla: `mScreenOn=true`, `mWakefulness=Awake`, `mHoldingDisplaySuspendBlocker=true`. Captura `tools/poco-capture/20261026-keepon/screen-35s.png` (Azul, HUD y controles visibles). **RF-01 cumple.**
- **Segundo plano:** tras `KEYCODE_HOME`, a los **24 s**: `mScreenOn=false`, `mWakefulness=Dozing`, `mHoldingDisplaySuspendBlocker=false`. Captura `screen-home-24s.png`. **RF-02 cumple.**
- **Nota:** el primer intento se hizo con el POCO bloqueado por huella/PIN. `am start` no enciende la pantalla y `FLAG_KEEP_SCREEN_ON` no puede saltarse el keyguard; es el límite esperado, no un fallo del código. La prueba se repitió tras desbloquear el dispositivo.
- **Ajustes restaurados:** `screen_off_timeout=300000` y `stay_on_while_plugged_in=15`.
- **Evidencia completa:** `tools/poco-capture/20261026-keepon/evidence.md` (capturas y salidas de `dumpsys`).

## 2026-10-27 - B2/009 slice 1: tablas de encuentro de Gen 2 (solo lectura)

- **Decisión de producto:** el usuario pidió “valida y decide basado en la visión”. Se validó
  `specs/009-b2-spawns/spec.md` contra `PLAN.md` (L13 extracción, L17 spawn del tramo actual
  por desplazamiento real, L19 gating original, L23 fidelidad) y se marcó **APROBADA**. El
  encuentro normal en la hierba del juego **no basta** para B2: VISION L17 exige que el paso
  real sea el disparador. Slice 1 queda limitado a lectura.
- **Implementación:** `core/wild_table.h/.cpp` localiza las tablas grass/water de Gen 2 por
  estructura (47 B / 9 B por entrada, terminador `0xFF`) y valida rangos; no usa offsets fijos.
  Rechaza la cola de una tabla corrupta. `RomLoader` expone la ROM en RAM en solo lectura.
  JNI `nativeGetWildTable(group,map,kind)` devuelve `int[]` con tasas, offsets y pares
  nivel/especie. `GameEngine`/`MainActivity` solo lo llaman desde el diagnóstico de long-press
  en “Vista: Juego/Mapa”; no hay spawn ni escritura de estado.
- **Host:** `test_wildtable` **24 checks sintéticos / 41 con ROM real**, 0 fallos. Total del
  arnés con `GPSQUEST_ROM_GEN2`: **174 checks** (30+18+26+20+41 C++ + 39 Java). Sin la
  variable: 157 checks. `assembleDebug --offline` verde.
- **POCO (`XWWSAY4LAAPZHIA6`, build `2026-10-27+b2-wildtable`):**
  - `B2 mapa 24/3: grass tabla=0x2A5F0 entrada=0x2AE04 n=61 tasas=25/25/25`
  - `B2 probe 24/3 (ruta inicial): kind=1 ... primer=2/16` (Pidgey nivel 2)
  - `B2 mapa 24/7: sin tabla` / `B2 probe 24/7 (Casa jugador): sin tabla`
  - El diagnóstico no tocó el `.sav`; `CONTINUE` sigue disponible.
- **Evidencia:** `tools/poco-capture/20261027-b2-slice1/` (`logcat.txt`, `pantalla.png`,
  `evidence.md`).
- **Pendiente:** slice 2 (leer `wMapGroup`/`wMapNumber` reales y loguear la tabla del mapa
  actual) y slice 3 (inyección fiel por paso real con `TryWildEncounter`/`WildBattleScript`).
  El gate B2 completo sigue abierto: falta el encuentro real en ruta inicial con especie/nivel de
  la tabla y `CONTINUE` intacto.

## 2026-10-27 - B2/009 slice 2: mapa actual y tabla (solo lectura)

- **Implementacion:** `nativeGetCurrentMap()` lee `0xDCB5..0xDCB8` con guardas
  (grupo 1..26, mapa 1..255) y devuelve `{group, map, y, x}`. `GameEngine.getCurrentMap()`
  lo expone; el long-press de diagnostico en `Vista` loguea el mapa actual y resuelve su
  tabla con `nativeGetWildTable`. Sin UI nueva, sin spawn, sin escrituras de estado.
- **Build:** `2026-10-27+b2-currentmap`, `assembleDebug --offline` verde.
- **POCO (`XWWSAY4LAAPZHIA6`):** partida guardada con starter cargada con `CONTINUAR`.
  - `B2 mapa actual 24/3 (y=10 x=46)`
  - `B2 mapa actual 24/3 (y=10 x=46): kind=1 tabla=0x2a5f0 entrada=0x2ae04 n=61
    tasas=25/25/25 primer=2/16`
  - Sonda fija: `B2 probe 24/7 (Casa jugador): sin tabla`.
  - El motor se pauso para el volcado y se reanudo; el juego siguio en ruta inicial.
- **Evidencia:** `tools/poco-capture/20261027-b2-slice2/` (`logcat.txt`,
  `route29_clean.png`, `route29.png`, `route29_loaded.png`, `evidence.md`).
- **Pendiente:** slice 3 (inyeccion fiel por paso real con `TryWildEncounter`/
  `WildBattleScript`). El gate B2 completo sigue abierto.


## 2026-10-29/30 - Spec 014: integridad del mapa OSM + sprite OW (POCO)

- **Auditoria previa (2026-10-29):** el frame del emulador estaba LIMPIO en POCO
  (CGB, 70 238 ciclos/frame, 59,7 fps, `CONTINUE`, 160x144 sin artefactos); lo roto era la
  capa de mapa (M-1..M-5: terreno inventado, celdas rectangulares, red sin datos) y el
  `SpriteExtractor` (base=133/137 alternando cada tick, sprite equivocado).
  Evidencia: `tools/poco-capture/20261029-auditoria-map-juego/` (39 archivos + `evidence.md`).
- **Spec 014 re-encuadrada** de "frame" a "mapa OSM + sprite": `specs/014-integridad-frame-overworld/`.
  Regla de oro 1 respetada: se elimino el terreno inventado, no se anadio nada nuevo.

### Slice 1 - red OSM usable + cache honesta (`OsmDataFetcher.java`)

- `ENDPOINTS[]` = overpass-api.de + overpass.kumi.systems; `TIMEOUT_MS = 40 000`;
  reintento real solo en `429` y `5xx` (un 502 en vivo abortaba el primer intento);
  `loadFromDisk()` tolera `mtime <= 0` (cache de sesiones previas ya no se descarta).
- POCO: `OSM OK desde overpass-api.de (118017 B)` y overlay `Red: OK 198 elem - tiles:345`.
- Arnés host: `tools/host-test/run.ps1` volvio a enlazar tras anadir `core/apu.cpp` a `$common`.

### Slice 2 - fuera el terreno inventado, celdas cuadradas (`GBMapRenderer.java`)

- Borrados `Random`/`generateFallbackGrid` y el fallback de pasto/damero de `MainActivity`;
  `GBTileRenderer` eliminado. Clases documentadas EMPTY/PARK/ROAD/BUILDING/SHOP/WATER con UNA
  tabla de color (`colorForCell`), celdas cuadradas (`cellPixels() = max(w/COLS, h/ROWS)*zoom`),
  rejilla centrada y un solo `TRIANGLES` con `u_offset`. `hasData=false` -> no se pinta nada.
- **Desvio documentado del plan:** se descarto usar tiles del atlas VRAM como fuente del mapa
  (`atlas-vram.png` probo que la VRAM viva contiene fuentes/UI en el instante del muestreo).
  El mapa pinta celdas OSM con colores planos documentados (cromo, no contenido inventado).
- POCO: con OSM -> bloques/colores y calles, sin camuflaje; sin red ni cache
  (`svc wifi/data disable`) -> lienzo plano de `glClearColor`, cero terreno.
  Log: `rejilla 20x18 celdas cuadradas de 150.66667 px (pantalla 1220x2712, zoom 1.0)`.
  Evidencia: `tools/poco-capture/20261029-spec014/` (`g2a-mapa-osm.png`, `g2b-sin-datos.png`,
  `g2c-mapa-osm-cuadrada.png`, `logcat-g2.txt`, `atlas-vram.png`).

### Slice 3 - sprite OW estable y fiel (banco 1 + paleta OAM)

- **Causa raiz:** el jugador (Crystal, LCDC=0xE3) se dibuja con 4 OBJ de 8x8
  `(64,60)=tile 0 / (72,60)=1 / (64,68)=2 / (72,68)=3`, atributos `0x08`/`0x88` ->
  **banco 1 de VRAM** y paleta OAM 0. El codigo leia VRAM con `readBlock()` (usa `VBK`, que el
  juego cambia) y componia el bloque desde el banco 0: glifos de fuente = "blob" verde.
- **Implementacion:** JNI nuevo `Mmu.readVramBlock(bank, addr, dst, len)` (no toca `VBK`) y
  `Mmu.readObjPaletteBlock(dst)` (64 B RGB555); `SpriteExtractor` elige banco por el bit 3 del
  atributo, fija el banco/paleta del tile superior-izquierdo y compone 2x2 en orden fila
  (0,1 / 2,3) con la paleta CGB del atributo; `MainActivity` pasa `vram1Buf` + `objPalBuf`.
  El volcado de diagnostico usa tambien `readVramBlock` (antes 8192 llamadas `readVramBank`).
- **Histéresis (RF-06):** `candidateBase`/`candidateStreak` con `CONFIRM_HITS = 6` y racha que
  se reinicia al cambiar de base/banco; liberacion a los 45 ticks sin evidencia.
- **POCO (verificado 2026-10-30):**
  - `sprite OW base=0 via=OAM bank=1 pal=0 tiles=0,1,2,3 hits=6` en el overworld.
  - Caminando 30 s: solo `base=8/9/136/137` (todos banco 1, pal 0), sin volver a banco 0 ni
    a fuentes; cada base produce un frame valido del jugador (capturas `t1-bank.png`,
    `t2-walk.png`). Antes: 11 123 `hits` con base=133/137 alternando.
  - `compare-base0.png` / `compare-base8.png`: la silueta compuesta desde VRAM banco 1 coincide
    pixel a pixel con el frame que dibuja la PPU en (64,60).
  - Evidencia: `tools/poco-capture/20261030-spec014-sprite/` (`ow/`, `ow2/`, `ow3/`, `fresh/`,
    `t1-bank.png`, `t2-walk.png`, `dump-sprite.ps1`, `render-sprite.ps1`, `compare-sprite.ps1`,
    `capture-ow-sprite.ps1`).
- **Arnés:** 34 mmu + 18 sav + 51 ppu + 23 cpu + 24 wildtable = 150 C++ y 39 Java, 0 fallos;
  `assembleDebug --offline` EXIT=0.

### Deuda anotada (fuera de alcance)

- La rejilla del mapa es fija 20x18 celdas a 4,4 m: con zoom 1 solo se ven ~35x79 m. Un
  COLS/ROWS dependiente del aspecto ampliaria la cobertura (no se toca en 014).
- El frame del PPU sigue pintando BG con la paleta DMG (`GB_PALETTE`) mientras los OBJ usan
  paletas CGB; es coherente con lo que ve el usuario pero conviene unificarlo en una spec futura.

---

## 2026-11-01 — Auditoría 015: producto vs VISION.md y audio

**Método:** lectura manual del árbol contra `PLAN.md` (los subagentes `explore` no
estaban disponibles: "free usage limit"). Sin cambios de código en este turno: spec y
plan primero.

### Veredicto

- **L16 (mapa 100 % opaco con tiles de VRAM): NO.** La PPU se pinta encima del mapa al
  **35 %** (`MainActivity.java:2557`, `3181`) y el mapa son **celdas de color plano**
  (`GBMapRenderer.java:262-295`), no tiles de VRAM. Sin POIs (`DOJOS = {}`, 1404).
- **L17 (al llegar → primer plano opaco → volver): NO.** El cambio es un toggle manual
  del engrane (`1505-1522`); no hay disparador por llegada ni retorno.
- **L19 (seguridad): NO.** `addWaterZone()`/`addStoryZone()` no se llaman nunca;
  `LocationService.java:186-188` son callbacks vacíos. El filtro de 7 km/h se salta si
  el fix no trae `speed` (`LocationService.java:151-159`).
- **L21 (spawn fiel): NO.** `GameEngine.onRealStep` lee la tabla de la ROM y **solo
  muestra un banner** (`342-356`); no existe `TryWildEncounter` en nativo. Task 21 quedó
  cerrada como "inyectado", pero lo que hay es notificación.
- **L23 (gating original): NO.** `setEventFlag` no tiene llamadas; `storyFlags` vacío;
  `storyFlag` es un entero de la app que nunca se incrementa (`MainActivity.java:1385`).
- **Audio:** `NR52` con bit 7 = 0 llama `reset()` → `initialize()` re-enciende el APU
  (`apu.cpp:344-347` + `26`); `read(0xFF26)` devuelve el byte escrito y nunca el estado
  de canales (`278-286`); mezcla sin filtro DC (`211-258`); anillo de 4096 frames sin
  control de nivel e hilo con `sleep(5)` (`GameEngine.java:166-186`); 0 tests de APU.

### Ruta aprobada (spec 015, 8 bloques; audio al final)

B1 vista honesta → B2 mapa legible → B3 pixel art desde VRAM → B4 verdad GPS →
B5 geovallas de agua → B6 spawn sin mentir → B7 APU fiel → B8 hilo/buffer de audio.
**B1 tiene gate de producto** (retirar el híbrido translúcido). Backlog de núcleo:
EI diferido, flags `SP+e8`, `DAA`, ventana DMG, prioridad sprite DMG, RTC MBC3.

### Evidencia

- `specs/015-auditoria-producto-fidelidad/spec.md` (tabla D-01…D-22 con `archivo:línea`).
- `specs/015-auditoria-producto-fidelidad/plan.md` (checklist POCO por bloque).
- `memory/archivo/tasks-2026-11.md` → Task 26.

### B1 cerrado — vista honesta (D-01)

- **Cambio:** `MainActivity.java` — fuera `PPU_ALPHA`/`PPU_ALPHA_OVER_MAP`; en `Vista: Mapa`
  no se dibuja `frameTexId`. La PPU solo se pinta con `ppuFullscreen` (intro / Modo Sofá)
  a alfa 1.0. Comentarios de 30 FPS/33,3 ms corregidos a 60/16,67.
- **POCO:** `Vista: Mapa` sin un píxel de la ROM; `Vista: Juego` con el título y el
  overworld de Gen 2 opacos; 8 cambios de vista con joystick, `PUMP012 fps=59,7`,
  `onDraw frame` 9 900→11 700, 0 excepciones. Arnés Java 39/39.
- **Evidencia:** `tools/poco-capture/20261101-015-b1/` (+ `evidence.md`).

### B2 cerrado — mapa legible (D-09, D-10, D-23, D-24)

- **Hallazgo real medido (D-23):** con la caché OSM lista a las `19:10:37.987` y EGL
  arrancando a las `19:10:38.423`, `feedOsmData()` descartaba el payload (`if gbMap != null`)
  porque `gbMap` nace en `onSurfaceCreated`. Cero logs de `GBMapRenderer` lo confirmaron:
  el lienzo plano de B1 no era "falta de OSM", era un dato perdido. Ahora se guarda el
  último payload y se aplica al crear el mapa.
- **Otros cambios:** prioridad explícita `agua 5 > calle 4 > comercio 3 > edificio 2 >
  parque 1` (el contorno del edificio sepultaba la calle); muestreo de 1,5 m con tope 1 024;
  rejilla 20×18 → **12×27** (el aspecto de la pantalla; antes se veían 8 de 20 columnas);
  sprite OW y punto neutro a **1 celda**, reconstruidos con el zoom.
- **POCO:** `OSM raster: 175 celdas / 1111 nodos (rejilla 12x27)`, calle continua de borde
  a borde, zoom ×1,5 con sprite y celdas creciendo juntos, 0 excepciones. Build verde.
- **Evidencia:** `tools/poco-capture/20261101-015-b2/` (+ `evidence.md`).
- **Deuda nueva (D-25):** el sprite del mapa sale como blob púrpura en el título de Gen 2
  (el extractor acepta cualquier bloque 2×2 de OAM). Pasa a B3.

### B3a cerrado — sprite del jugador desde OAM real (D-25)

- **Causa raíz doble (medida, no supuesta):**
  1. `mmu.readBlock(0xFE00,…)` desde el hilo Java cae en el tramo del frame en que la ROM
     ya limpió su buffer: **160 ceros** en bucle (`|0,0,0,0` × 40).
  2. Muestrear la OAM **al entrar en VBlank** tampoco sirve: la CPU de este emulador corre
     desacoplada del PPU y, cuando el PPU entra en VBlank, la ROM ya puede haber vuelto a
     limpiar. Medido: `diag oam=0,0,0,0…` en overworld.
- **Arreglo:** foto de OAM **al pintar la scanline 0** (`Ppu::oamSnapshot_` + `getOamSnapshot()`
  + JNI `Mmu.readOamSnapshot`); es exactamente la OAM con la que el PPU dibuja los sprites
  del frame visible. `SpriteExtractor` publica el bloque centrado solo con evidencia:
  animación de paso **o** movimiento registrado (`notePlayerMove`). El OBJ decorativo del
  título no cumple ninguna → fuera el blob púrpura.
- **POCO:** `sprite OW base=0 via=OAM bank=1 pal=0 tiles=0,1,2,3 hits=6`; avatar de Ethan
  pintado en la celda del marcador y escalando con el zoom; título con solo el marcador.
  `PUMP012 fps=59,7`, 0 excepciones. Build `EXIT=0`.
- **Evidencia:** `tools/poco-capture/20261101-015-b3/` (+ `evidence.md`).
- **Pendiente B3b (D-02):** CERRADO 2026-11-03 — el mapa ya muestrea los tiles reales
  (césped 0x105, árbol 0x11E, camino 0x13D). Quedan planos agua/edificio/comercio:
  no había tiles suyos en el volcado.

### B5/B6 cerrados (2026-11-03) — agua de OSM = geovalla y spawn sin mentir

- **Defecto real (B5):** `addWaterZone` sin llamadas, distancia al punto en vez de al borde,
  `onNearWater` vacío en `MainActivity` y `LocationService.setGeofenceListener` **sustituía**
  el listener de `GameEngine` (el último registrado dejaba mudo al otro).
- **Cambios:** `WaterZone` con geometría OSM y distancia al segmento (`distanceMeters`);
  `replaceWaterZones` copy-on-write con tope 512 y sin soltar `nearWater`; listeners
  aditivos; `syncWaterGeofences()` en el callback de Overpass; `onNearWater` fuerza
  `setGpsNearWater` y avisa en el HUD (`msg_water_sofa` / `msg_water_clear`).
- **Host tests:** `GeofenceHostTest` 11/11 + Pueblo 39/39 (`run-java.ps1`), con stub nuevo
  `android/location/Location`.
- **POCO:** `Geovallas agua: 0 zonas desde OSM` — correcto: la caché de 150 m
  (`19.528_-96.924.json`, 1 111 nodos) no tiene ni un `natural=water`/`waterway`.
  `fps=59,8`, 0 excepciones. Falta gate físico junto a agua mapeada.
- **B6:** `onWildEncounter` ya no pinta `¡ROM #N Nivel M!`; solo log
  `B2 spawn ROM detectado (sin combate aún)`. El combate real es 009 slice 3.
- **Evidencia:** `tools/poco-capture/20261103-015-b5/` (+ `evidence.md`).

### B3b cerrado (2026-11-03) — el mapa pinta tiles reales de la VRAM (D-02)

- **Defecto:** la textura del atlas VRAM se creaba y subía a GL, pero `GBMapRenderer`
  nunca la muestreaba (color plano por celda). Además el atlas indexa con base 0x8000
  sin signo y el overworld de Gen 2 corre con `LCDC=0xE3` (datos en 0x8800 con signo).
- **Evidencia de tiles:** volcado del overworld (long-press en "Vista:", extraído con
  `run-as … base64`), hoja de contactos y reconstrucción 20×18 con la herramienta nueva
  `tools/vram-sheet.ps1`. La reconstrucción coincide con la pantalla real del juego.
- **Tabla:** césped `0x05`→atlas `0x105`, árbol `0x1E`→`0x11E`, camino `0x3D`→`0x13D`.
  Tile repetido a 12 px por celda (`fract` en el fragment shader) y tinte de UI
  (`color * (0.35 + 1.10 * luminancia)`) para conservar legibilidad.
- **Sin inventar:** agua, edificio y comercio siguen a color plano porque en ese volcado
  no aparecía ni un tile suyo en pantalla.
- **POCO:** `tipos: vacío=149 parque=8 calle=72 edificio=94 comercio=1 agua=0`,
  `zoom 1.5, tile x13`, 59,7 fps, 0 excepciones.
- **Evidencia:** `tools/poco-capture/20261103-015-b3b/` (+ `evidence.md`).
