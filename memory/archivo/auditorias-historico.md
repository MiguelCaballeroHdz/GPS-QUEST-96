# Auditoría integral — 2026-10-22

Alcance: estructura, Gradle/Manifest, Java, C++/JNI, concurrencia, residuos y documentación.
Método: lectura completa de núcleo (CPU/MMU/MBC/PPU/loader/JNI), ciclo de vida Android,
servicios, red/OSM, builds verdes y verificación de la huella nativa en el `.so`.

Resultado: **14 defectos corregidos** (build verde), **9 deudas abiertas** documentadas.
Ningún cambio introduce features: es mantenimiento correctivo de specs 001/002/003 ya aprobadas.

> **Vigencia 2026-10-23:** foto histórica, no estado vigente (ver `memory/archivo/estado_actual-2026-11.md`).
> Correcciones posteriores: §5.1 (ventana PPU) ya **no** es deuda — `renderWindow()` está
> implementada en `ppu.cpp:229-268`; §5.7: `nativeBoostBossEncounter` ya no existe (retirado
> en C3, la API Java permanece declarada sin uso).

---

## 1. Críticos corregidos

| # | Defecto | Evidencia | Corrección |
|---|---------|-----------|------------|
| C1 | **Sin OAM DMA (0xFF46)**: Gen 1/2 copian su shadow OAM de WRAM a 0xFE00 por DMA. La OAM quedaba a cero → el juego no dibuja sprites y B4 nunca podía identificar al jugador. | `mmu.cpp` escribía 0xFF46 como I/O muerto. | `Mmu::dmaOam()` copia 160 B al escribir 0xFF46 (síncrono, equivalente neto). |
| C2 | **ROM leída del disco byte a byte**: `RomLoader` era streaming; cada fetch de CPU hacía `fseek`+`fread` (~500k syscalls/s), con batería y frametime degradados. | `rom_loader.cpp` `readBytes()`; `Mbc::readRom()` por byte. | ROM completa en RAM al abrir (máx. 16 MB) + `memcpy` en `readBytes()`. Fetch O(1). |
| C3 | **Código post-game inventado y activo**: `nativeBoostBossEncounter` escalaba stats y escribía en `0xD000` (zona de party/box de Gen 1). Viola RULES §8 y regla 5, con riesgo de corromper partida. | `native_bridge.cpp` + llamada en `GameEngine.startBossEncounter`. | Método nativo eliminado; `startBossEncounter()` devuelve `false` (congelado). |
| C4 | **Crash en Android 14 al denegar permisos**: FGS tipo location/BLE se arrancaba sin permiso → `SecurityException` en `startForeground`. | `MainActivity.startBackgroundServices` arrancaba ambos siempre; servicios sin try/catch. | Guardas por permiso + try/catch en arranque y en `startForeground`; `stopSelf()` limpio. |
| C5 | **Carrera de datos en el snapshot VRAM/OAM**: lo llaman el hilo de emulación y el worker de OSM; compartían `vramBuf`/`oamBuf` y el estado de `SpriteExtractor`. | `MainActivity.refreshAtlasSnapshot()` sin sincronización. | `synchronized` + log de error con throttle 5 s (antes se tragaba todo). |

## 2. Altos corregidos

| # | Defecto | Corrección |
|---|---------|------------|
| A1 | PPU usaba dirección sin signo sobre 0x8800 (modo de tiles con signo) → tiles equivocados. | `0x9000 + (int8_t)index * 16` para modo 0x8800. |
| A2 | Prioridad de sprites invertida: se pintaban en orden OAM y el índice 39 quedaba encima. | Selección de 10 por scanline + orden por X ascendente y pintado inverso (prioridad DMG). |
| A3 | `makeSavPath` podía escribir fuera del buffer (`dot[4]` tras `strncpy` corto). | Reescrito con `std::string` y truncado seguro. |
| A4 | SRAM fuera de rango hacía `%` (wrap silencioso) en lectura y escritura. | Lectura `0xFF`, escritura ignorada. |
| A5 | HUD congelado tras volver de background: `onPause` quitaba el loop y `onResume` no lo reponía. | `onResume` reinicia el updater; `setupHudUpdater()` idempotente. |
| A6 | `allowBackup=true` + ROM/SRAM en `filesDir` → ROMs de usuario y partidas podían salir por backup; tráfico cleartext permitido sin uso. | `allowBackup=false`; `usesCleartextTraffic` fuera; `READ_MEDIA_IMAGES` innecesario fuera (SAF no lo requiere). |
| A7 | Coordenadas OSM `NaN` producían geometrías inválidas (líneas/bloques rotos). | Filtro `Double.isNaN` en nodos y geometría. |

## 3. Residuos y limpieza

- **Código muerto peligroso eliminado**: `EmulationPipeline.executeFrame/renderFrame/getFrameBuffer`
  llamaba GL nativo desde el hilo de emulación (crash si alguien lo reactivaba) y asignaba 92 KB/frame.
- **Duplicados borrados**: `sram_dump.txt` de raíz era idéntico (SHA-256) a `out/poco/sram_dump_fresco.txt`.
- **Dumps movidos a `out/poco/`**: `arranque.txt`, `campo.txt`, `trace.txt`, `forense.txt` (evidencia, no fuente).
- **Colisión de numeración resuelta**: `specs/002-mapeo-global-dinamico` → `004`, `specs/003-gating` → `005`;
  referencias cruzadas actualizadas. `002-mapa-tiles-atlas` y `003-sprite-ow` quedan intactas.
- `local.properties` y `.idea/` no se tocan (config local, sin secretos en repo).

## 4. Verificación

- `:app:assembleDebug --offline` → **BUILD SUCCESSFUL** (4 ABIs).
- `:app:assembleDebugAndroidTest --offline` → **BUILD SUCCESSFUL** (Kotlin de test compila).
- `BUILD_TAG` `2026-10-22+audit1+dma+romcache` presente en `libgpsquest96.so` (arm64).
- `:app:lintDebug --offline` **no ejecutable en este entorno**: falta `com.android.tools.lint:lint-gradle:31.7.3`
  en caché. No es un fallo del proyecto; pendiente correr lint con red o Android Studio.
- POCO pendiente (dispositivo no conectado). La prueba decisiva ahora es: intro → overworld → sprites visibles
  en pantalla y `SpriteExtractor: sprite OW ... hits=3`.

## 5. Deudas abiertas (requieren spec/plan o POCO)

1. ~~**Ventana PPU (`renderWindow`)**: sigue en TODO.~~ **RESUELTA (verificada 2026-10-23):** implementada en `ppu.cpp:229-268` (WX-7, WY, LCDC.6, contador `windowLine_`), invocada desde `renderScanline()`. Falta solo comparación visual en POCO.
2. **Audio (APU)**: no existe. Los registros NRxx se almacenan pero no suenan; Gen 1 depende del timer para su motor de audio. Silencio total, sin crash.
3. **Timing fino de CPU/PPU**: `getOpcodeCycles` aproxima la mayoría de opcodes; falta retardo de EI, semántica exacta de HALT y STAT/LYC IRQ. Afecta timing y audio, no la estabilidad.
4. **GBC real**: sin doble velocidad, HDMA, bancos de VRAM ni paletas CGB. Crystal correrá en modo DMG degradado.
5. **MBC3 RTC**: revisar latch/hora real antes de dar por fieles los eventos temporales de Crystal.
6. **`Ppu_getFrameBuffer`** (JNI que asigna 92 KB por llamada) quedó sin uso; eliminar en el próximo pase.
7. **API post-game congelada**: `canEncounterBoss/isLeagueComplete/tryCalculateCapture` siguen declaradas (sin uso). Se pueden retirar cuando se cierre la deuda §8.
8. **`arm7.h`** (GBA) permanece congelado y sin compilar, conforme a la regla 4.
9. **`ACCESS_BACKGROUND_LOCATION`** declarado y nunca solicitado: revisar política de Play antes de publicar.

## 6. Observaciones de diseño (no bugs)

- `MainActivity` ~2.7k líneas: concentra UI, GL, worker, sensores y OSM. Deuda de mantenibilidad; dividir por responsabilidad cuando el flujo B4/B2 esté cerrado.
- El toque sobre el mapa inyecta A+START; en menús puede abrir/cerrar sin intención. Es cromo de control, no core.
- `forense`/`trace` siguen en el puente nativo como diagnóstico; útiles hasta cerrar B4, luego retirar.
