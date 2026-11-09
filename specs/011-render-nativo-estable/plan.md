# plan.md — 011 Render nativo estable

Estado: **APROBADO** (deriva de `specs/011-render-nativo-estable/spec.md`, APROBADA
2026-10-28 por el usuario). Ejecución: un slice por turno, build verde y bitácora.

## Reglas de ejecución

- Un slice = un turno. Si un slice rompe el build, se repara solo sus archivos.
- Arnés de host antes/después de cada slice: `tools/host-test/run.ps1`
  (debe mantener sus counts) + `run-java.ps1` (39 checks).
- **Puerta POCO** (requiere dispositivo del usuario): por slice, capturar logcat +
  pantallazos en `tools/poco-capture/<fecha>-<slice>/`. Sin puerta verde, el slice
  queda "código hecho, gate pendiente" — nunca "cerrado".
- No hay `.git`: ZIP de respaldo de los archivos a tocar antes del primer slice.
- BUILD_TAG de `native_bridge.cpp` se actualiza en cada slice que toca el core.
- Fuera de alcance (no entra en ningún slice): audio/APU, RTC MBC3, EI delay,
  desacoplar `MainActivity`, permisos Android 14+, specs 004/005, post-game.

## Slices

### Slice 1 — Doble búfer del framebuffer PPU (RF-01)
- **Archivos:** `app/src/main/cpp/core/ppu.h`, `app/src/main/cpp/core/ppu.cpp`,
  `app/src/main/cpp/jni/native_bridge.cpp` (solo BUILD_TAG).
  Java no cambia: `uploadPpuTexture()` ya relee `getFrameBufferDirect()` cada frame.
- **Cambio:** la PPU pinta cada scanline en un búfer de trabajo (`drawBuffer_`);
  al entrar en VBlank (frame completo) se intercambia el puntero de presentación
  (`presentBuffer_`, lectura atómica). `getFrameBuffer()`/`getFrameBufferDirect()`
  devuelven el búfer present vigente. Búferes fijos de la instancia; cero
  allocations por frame. Detalle: el anillo tendrá **3 slots fijos** (no 2): con
  dos, el slot reciclado podría seguir subiéndose por el hilo GL la microventana
  previa al redibujo (~1 ms); con tres, cada slot queda ≥2 frames sin escribir
  tras presentarse y la carrera desaparece. Mismo requisito RF-01, más estricto.
- **Aceptación host:** `run.ps1` sin checks nuevos rojos (26 PPU incluidos) +
  `run-java.ps1` 39/39 + `assembleDebug --offline` verde.
- **Aceptación POCO:** caminar en Gen 2 (Modo Sofá) 60 s sin rasgado/parpadeo;
  regreso Azul DMG (título/profesor/ventana); log `EMU` con velocidad no empeorada;
  `CONTINUE` tras force-stop intacto.
- **Riesgo:** el swap cambia el puntero que Java envuelve por frame con
  `NewDirectByteBuffer` — ya se re-pide cada upload, no hay referencia estancada.
- **Rollback:** restaurar los 3 archivos desde el ZIP (código aislado en ppu.*).

### Slice 2 — HDMA troceado por HBlank + ventana fiel (RF-02, RF-03)
- **Archivos:** `app/src/main/cpp/core/mmu.cpp` (+`mmu.h`),
  `app/src/main/cpp/core/ppu.cpp`(+`ppu.h` si toca interfaz), arnés host
  (`tools/host-test/`, solo checks nuevos, no tocar los existentes).
- **Cambio:** `startHdma()` deja de copiar en bloque: guarda src/dest/bloques
  pendientes; la copia va en chunks de 16 B por HBlank (modo 0, sólo LY 0..143)
  vía `Mmu::runHdmaChunk()` invocado por la PPU al entrar en modo 0. `FF55`:
  bit7=0 + (restantes−1) en vuelo, 0xFF al terminar, escritura durante vuelo
  cancela. GDMA y LCD apagado siguen síncronos (como hoy). Y `windowLine_`:
  reinicio cuando `LY==WY` (ventana activa en LCDC.5), no al fin de frame; se
  conserva el reset con LCD apagado.
- **Aceptación host:** checks nuevos de HDMA en vuelo + `LY==WY` en verde; el
  resto del arnés no baja.
- **Aceptación POCO:** mochila/menú START y una batalla silvestre en Gen 2 sin
  distorsión; diálogos con cuadro alineado; regreso Azul DMG; `CONTINUE` OK.
- **Riesgo:** la ROM de Gen 2 sondea `FF55` — el bit debe caer solo al
  completar; test sintético de sondeo evita el cuelgue.
- **Rollback:** restaurar `mmu.*`/`ppu.cpp` desde ZIP; los checks nuevos se
  retiran con el slice.

### Slice 3 — Higiene nativa condicionada a evidencia (RF-04)
- **Puerta previa (sin dato no se toca):** `dumpsys meminfo com.gpsquest96` en 3
  ciclos abrir/cerrar ROM: si el native heap no crece, se documenta como
  no-fuga; si crece, se localiza el propietario antes de liberar.
- **Archivos:** `app/src/main/cpp/jni/native_bridge.cpp` (nuevo
  `nativeDestroyPipeline()`), `app/src/main/java/com/gpsquest96/emulator/
  core/EmulationPipeline.java` (llamada en destroy).
- **Cambio:** flush de SRAM + `delete` de `g_cpu/g_mmu/g_ppu` en orden de
  invalidez seguro (sin hilos de emulación activos).
- **Aceptación:** `dumpsys meminfo` antes/después sin crecimiento; build verde;
  reabrir ROM y jugar sigue funcionando.
- **Riesgo:** delete con hilo vivo → se exige llamar tras stopFramePump().

## Puertas de dispositivo (usuario con POCO)

| Puerta | qué trae |
|---|---|
| Por slice | logcat limpio (5 líneas de arranque) + pantallazos del escenario |
| Gate final | criterios de aceptación §7 de la spec: Caminar/Menu/Batalla/Diálogo/Azul/CONTINUE/EMU |
