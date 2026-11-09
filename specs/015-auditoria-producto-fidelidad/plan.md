# plan.md — 015 Auditoría integral de producto, fidelidad y arquitectura

Estado: APROBADO 2026-11-01.
Spec: `specs/015-auditoria-producto-fidelidad/spec.md`.

## Bloques de Ejecución

### Bloque B1 — Vista honesta (D-01) ✅
- Eliminación de alfa y superposición translúcida entre el emulador y el mapa.
- Vista Juego: datos del juego original del usuario al 100% de opacidad a pantalla
  completa.
- **Dispositivo de prueba:** Verificado a 59.7 FPS.

### Bloque B2 — Mapa legible (D-09, D-10, D-23, D-24) ✅
- Celdas cuadradas de 101 px (rejilla 12×27).
- Prioridad de capas OSM: Agua 5 > Calle 4 > Comercio 3 > Edificio 2 > Parque 1.
- Sprite del jugador escalado a 1 celda.
- **Dispositivo de prueba:** Verificado con 198 elementos rasterizados.

### Bloque B3 — Atlas VRAM + D-25 + Corrección de Eje Y
#### B3a — Sprite del jugador desde OAM real (D-25) ✅
- Inversión del eje Y del Joystick corregida en `GBMapRenderer.java`.
- Atributos OAM `flipX` y `flipY` aplicados en `SpriteExtractor.composeSprite()`.
- **Causa raíz B3a:** leer `0xFE00` desde Java devolvía 160 ceros (ventana en que los
  datos del juego original del usuario ya limpiaron su buffer); muestrear al entrar en
  VBlank tampoco sirve porque la CPU corre desacoplada del PPU. Se toma la foto **al
  pintar la scanline 0** (`Ppu::oamSnapshot_` + `Mmu.readOamSnapshot`), y el extractor
  publica el bloque centrado solo con evidencia (animación de paso **o** movimiento del
  jugador registrado), lo que elimina el blob púrpura del título.
- **Dispositivo de prueba:** `sprite OW base=0 via=OAM bank=1`, avatar en el mapa
  escalado, título limpio. Evidencia: carpeta de capturas del dispositivo
  correspondiente a `20261101-015-b3/evidence.md` (bajo `tools/`).

#### B3b — Pixel art por tile en el mapa (D-02) ✅
- El atlas VRAM (384 tiles) ya se muestrea: `GBMapRenderer` lleva `a_tile` (base uv en
  el atlas) y `a_rep` (repeticiones por celda, `fract` en el fragment shader). El tile
  se repite a 12 px de pantalla, con tinte de UI (`color * (0.35 + 1.10 * luminancia)`)
  para no perder legibilidad (calle beige, césped verde, arbolado oscuro).
- **Dirección de tiles:** `LCDC=0xE3` ⇒ tilemap `0x9800` y datos con signo
  (`0x9000+i*16`). El atlas usa la base sin signo, así que el índice es `256 + tilemap`:
  césped `0x05` → atlas `0x105`, árbol `0x1E` → `0x11E`, camino `0x3D` → `0x13D`.
- Herramienta nueva `tools/vram-sheet.ps1` (hoja de contactos + reconstrucción de la
  ventana 20×18) para elegir índices con evidencia visual.
- Agua, edificio y comercio siguen a color plano: en el volcado no había tiles suyos.
- **Dispositivo de prueba:** `tipos: vacío=149 parque=8 calle=72 edificio=94 comercio=1
  agua=0`, `zoom 1.5, tile x13`, 59,7 fps. Evidencia en la carpeta de capturas del
  dispositivo correspondiente a `20261103-015-b3b/` (bajo `tools/`).

### Bloque B4 — Gating con flags reales de SRAM (Spec 005) 🟡
- Evaluación de restricciones de historia en `GeofenceManager.java`.
- Notificación visual de zona bloqueada en `MainActivity.java`.

### Bloque B5 — Geovallas de agua desde OSM 🟡
- `syncWaterGeofences()` en el callback de Overpass: cada `Kind.WATER` se registra con su
  geometría completa; `WaterZone.distanceMeters()` mide al borde (vértice o segmento).
- `GeofenceManager` con listeners aditivos y listas copy-on-write (el worker de OSM escribe
  mientras el GPS lee). `onNearWater` fuerza Modo Sofá y avisa en el HUD.
- Host tests 11/11 (`GeofenceHostTest`). **Dispositivo de prueba:** `Geovallas agua: 0
  zonas desde OSM` (la caché de 150 m no tiene agua mapeada). Falta gate físico junto a
  un río/lago.

### Bloque B6 — Spawn honesto ✅
- `onWildEncounter` deja de mostrar el banner con la especie, el número y el nivel
  mientras no exista `nativeTryWildEncounter` (009 slice 3); queda solo el log y la
  tregua de 180 s.

### Bloque B7 — Emulación de Audio APU (Canales NR10..NR52) ⏳
- Canales 1, 2, 3 y 4 de sonido del modo retro de 8 bits en C++ (`apu.cpp`).
- Hilo `AudioTrack` en Java a 44100 Hz PCM Estéreo.
