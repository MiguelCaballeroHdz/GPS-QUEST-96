# spec.md — 015 Auditoría integral de producto, fidelidad y arquitectura

Estado: APROBADO 2026-11-01.
Fuente de verdad de producto: `PLAN.md`.

## 1. Visión y propósito

Garantizar que la emulación del modo a color, el renderizado de mapas, la captura de geovallas y la gestión de memoria cumplan al 100% con los principios de fidelidad de `PLAN.md` sin parches propios ni regresiones de estabilidad.

## 2. Tabla de Auditoría de Código y Defectos

| Severidad | Archivo : Línea | Efecto | Verificación en el dispositivo de prueba |
|---|---|---|---|
| **ALTA** | `app/src/main/cpp/core/ppu.h:60` | Desgarro (*tearing*) y titileo por lectura asíncrona de `drawBuffer_` activo. | **Resuelto:** Doble búfer (`displayBuffer_`) atómico en VBlank. PUMP012 a 59.7 FPS continuos. |
| **ALTA** | `app/src/main/cpp/core/mmu.cpp:145` | Desincronización de VRAM en transiciones de batalla/menús por HDMA síncrono. | **Resuelto:** Transferencia `runHdmaChunk()` por HBlank. Menús y combates sin distorsión. |
| **ALTA** | `app/src/main/java/com/gpsquest96/ui/MainActivity.java:1850` | Corrupción de paletas del modo a color en volcados de diagnóstico por escrituras a `0xFF68/0xFF6A`. | **Resuelto:** Invocación de getters nativos de solo lectura. Long-press limpio. |
| **MEDIA** | `app/src/main/java/com/gpsquest96/render/GBMapRenderer.java:235` | Inversión del eje $Y$ en el desplazamiento por Joystick (`rasterLat - lat`). | **Resuelto:** Ajustado a `(lat - rasterLat) / cellH`. Dirección 1:1. |
| **MEDIA** | `app/src/main/java/com/gpsquest96/render/SpriteExtractor.java:335` | Fragmentación/troceado del sprite de 16×16 al caminar por omitir flips OAM. | **Corregido 2026-11-10 (rama `fix-sprite-flips`):** los flips se leen del atributo OAM de la entrada TL del bloque 2x2, se publican con el sprite y `MainActivity` los pasa a `composeSprite` (9 args); además el giro se aplica en el tick si el base ya estaba publicado. Antes `composeSprite` soportaba flips pero el llamador fijaba `false, false` y `pendingFlipX/Y` nunca se asignaban, por eso la fila decía "Resuelto" sin estar cableado. Falta verificar caminando en POCO. |
| **MEDIA** | `app/src/main/java/com/gpsquest96/gps/OsmDataFetcher.java:180` | Fuga de conexiones o cierres no manejados en `SocketTimeoutException`. | **Resuelto:** *Try-with-resources* + `disconnect()` en `finally`. Cero congelamientos. |
| **BAJA** | `app/src/main/cpp/jni/native_bridge.cpp:120` | Consumo residual en Heap nativo por omitir `delete` en instancias globales C++. | Memoria nativa estable en ~25.9 MB tras re-aperturas. |
| **BAJA** | `app/src/main/java/com/gpsquest96/gps/LocationService.java:45` | Coordenadas obsoletas persistidas en `AppDomain` al reiniciar el servicio. | **Resuelto:** Getters de instancia gestionados mediante `LocalBinder`. |

## 3. Bloques de Trabajo Organizados por Urgencia

- **B1 (Vista honesta):** Renderizado 100% opaco a pantalla completa sobre los datos del juego original del usuario y vista limpia de mapa. ✅
- **B2 (Mapa legible):** Rejilla 12×27 con celdas cuadradas, orden de capas OSM y entrega segura de payload. ✅
- **B3 (Atlas VRAM + D-25):** B3a ✅ sprite del jugador desde OAM real en el mapa (snapshot de OAM en scanline 0) y gate D-25 sin blob de título. B3b ✅ pixel art real de césped/arbolado/calle en el mapa (tiles 0x105/0x11E/0x13D de la VRAM, tilemap 0x9800 con signo); agua/edificio/comercio siguen planos por falta de tile verificado.
- **B4 (Gating con flags de SRAM - Spec 005):** Evaluación de la historia sobre `eventFlags` de la partida. 🟡
- **B5 (Mapeo Global Dinámico - Spec 004):** Mapeo determinista de POIs y resiliencia offline por GeoHash. ✅
- **B5b (Geovallas de agua - Spec 004/015):** OSM `Kind.WATER` → `GeofenceManager`, distancia al borde, `<20 m` fuerza Modo Sofá. 🟡 (falta gate físico junto a agua)
- **B6 (Audio APU - Canales NR10..NR52):** Emulación de sonido estéreo PCM @ 44.1 kHz. ⏳

## 4. Criterios de Aceptación
1. `./gradlew assembleDebug --offline` finaliza con `EXIT=0`.
2. Las pruebas en vivo sobre el dispositivo de prueba confirman renderizado fluido a 59.7 FPS sin cierres ni excepciones.
