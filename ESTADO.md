# GPS Quest '96 - Estado Actual

## Dónde estamos
- App: GPS Quest '96 (com.gpsquest96), emulador genérico, sin referencias a terceros
- Lotes COMPLETOS: 1 (strings), 6+6b (repo limpio, 51 archivos en carpeta privada), 2 (BLE genérico), 3 (comentarios cosméticos)
- Build: assembleDebug verde, APK verificado
- Limpieza legal: 7/9 cambios Lote 3 ya estaban en objetivo

## Estado vivo
Resumen de `memory/archivo/estado_actual-2026-11.md` + `tasks-2026-11.md`. Solo lo que sigue abierto.

**Motor**
- 007 CGB: slices 1-2 + timing parcial verdes; gate POCO parcial (Gen 2 en color, `CONTINUE` OK). Falta caminar el cuarto de Gen 1 en DMG; HDMA HBlank solo si el overworld falla.
- 008 Guardado real + `.pueblo`: slices 1-2 hechos; falta la prueba destructiva (`pm clear` u otro equipo) y guardar en Gen 1 (DMG).
- 009 B2 spawns: slices 1-2 hechos (tablas + mapa actual); el slice 3 (inyección por paso real) no está en el árbol y depende de 016.
- B4 sprite OW: código y DMA de OAM listos; falta la foto del overworld en POCO (`hits=3` ya confirmado). Fix flips 2026-11-10 (rama `fix-sprite-flips`): flips OAM de la entrada TL cableados a `composeSprite` + giro inmediato sin esperar confirmación; host 33 comprobaciones, 0 fallos. El sprite ROM ya no se dibuja en el mapa desde 017 slice 1 (avatar propio de Miguel); la verificación de los flips en POCO queda sustituida por la del avatar.
- Deudas abiertas: APU (audio), timing fino CPU/PPU, RTC MBC3.

**Mapeo y avance**
- 004 POI-first: slices 0, 1, 2, 3 y 6a hechos; slice 4 desbloqueado (dependía del 3); slice 5 pendiente; 6b bloqueado por el slice 4.
- 004 slice 3 (2026-11-10): `RouteValidator` mide R3 en modo lectura (llegada/consumo/destino por fix GPS, cero escrituras); host 36 comprobaciones, 0 fallos. Sin destino declarado la señal 3 es N-A (hoy ningún nodo tiene destino). Dispositivo pendiente de sesión de calle en POCO.
- 004 radio (2026-11-08): recorte de geometría a la unión de círculos, poda del acumulador a 1 km, corredor 150/120 en cada actualización y seed sin fix usando el ancla. POCO: 180 nodos (2 POI, máx. 249,4 m), raster de 159 nodos (antes 1290).
- 004 mapa (2026-11-08): rejilla de 63×139 celdas de 8 m (504×1.112 m) en `OsmRasterGrid`, con buffer GL reutilizado. POCO: 619→676 celdas pintadas (antes 13), 605 de calle, 12 de amenity, 1 de comercio, 70 de nodo de ruta (antes 3) y 59,7 fps.
- Criterio §7.8 (POI real → Punto de Restauración) pendiente de prueba en calle.
- 005 gating: bloqueado por el dump POCO (slice 5c, `SRAM_FLAG_MAP`).
- 016 arquitectura base genérica: spec APROBADA 2026-11-09, slices 1-4 en el árbol (estado genérico + corredor diagonal); ya no bloquea 004 (3-5) ni 009 (3).
- 017 mapa real y avatar propio: spec APROBADA 2026-11-10 (decisión de Miguel: "Mapa normal + sprite propio"). Slice 1 hecho en host (rama `017-slice-1`): avatar propio de Miguel en el mapa (12 cuadros 32x56, chico/chica, frente/espalda/lado, caminata por movimiento); sprite ROM retirado del mapa. Host 167 comprobaciones nuevas, total 457, 0 fallos. Slice 2 hecho en host (rama `017-slice-2`): capa de tiles OSM (descarga + caché 7 días + atribución, rejilla 3x3, una sola capa base: tiles o raster, nunca ambas). Host 93 comprobaciones nuevas (`TileMathHostTest`), total 550, 0 fallos. Pendiente: build en PC + verificar en POCO (mapa real con calles y nombres, avatar encima; sin conexión, respaldo raster). Slice 3 (integración) pendiente.

**Defectos de campo (Task 28, caminata 2026-11-06)**
- B. Notificaciones del sistema sobre la UI → modo inmersivo estable.
- C. Sprite del entrenador cortado/dividido → el camino ROM quedó retirado en 017 slice 1 (avatar propio de Miguel, 167 checks host); pendiente verificar en POCO que el avatar camina sin dividirse.
- D. Movimiento a saltos por drift GPS → `WalkFilter` (015 B4).
- E. Salto de mapa y recarga de tiles → caché por celda; lo corrige 004 slice 2.
- F. Bloque verde oscuro al inicio → fallback sin datos OSM + carga en frío.
- G. Repetir la caminata con log en vivo para cerrar el acta.

**Alcance**
- Solo Motor Retro de 8 bits. GBA (ARM7TDMI) diferido, con spec y carpeta propias.
- Post-game §8 congelado: no es MVP (`memory/archivo/POSTGAME.md`).

## Últimos 5 cambios
1. Lote 3 - Comentarios cosméticos completos
2. Lote 2 - BLE genérico (AccessoryGattService)
3. Lote 6b - tools/ limpio, evidence.md sin metadatos ROM
4. Lote 6 - Repo limpio, ROMs fuera en C:\Dev\gps-quest-96-private\
5. Lote 1 - Strings visibles genéricos

## Enlaces
- Bitácora histórica: memory/archivo/bitacora-historico.md
- Auditorías: memory/archivo/auditorias-historico.md
- Estado detallado: memory/archivo/estado_actual-2026-11.md
- Tablero: memory/archivo/tasks-2026-11.md
- Specs: specs/
