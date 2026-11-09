# plan.md — 012 Velocidad real

Estado: **APROBADO** (deriva de `specs/012-velocidad-real/spec.md`, APROBADA
2026-10-28 por el usuario en la sesión del dispositivo de prueba). Ejecución:
un slice por turno, build correcto y bitácora.

## Reglas de ejecución

- Un slice = un turno. Si un slice rompe el build, se repara solo sus archivos.
- Arnés de host antes/después de cada slice: `tools/host-test/run.ps1`
  (30+18+51+20+24 sin bajar) + `run-java.ps1` (39 checks).
- **Puerta del dispositivo de prueba** (requiere dispositivo del usuario):
  logcat (log `EMU`) + pantallazos en la carpeta de capturas del dispositivo
  correspondiente a `20261028-spec012-velocidad/` (bajo `tools/`). Sin puerta
  correcta, el slice queda "código hecho, gate pendiente" — nunca "cerrado".
- No hay `.git`: ZIP de respaldo de los archivos a tocar antes del slice.
- **TOQUE MÍNIMO:** solo `MainActivity.java` (framePumper + TARGET_FPS). El core
  C++ NO se toca (no hay BUILD_TAG nuevo salvo que el gate lo pida).

## Slices

### Slice 1 — Catch-up de ciclos + GL a 60 (RF-01, RF-02)
- **Archivos:** `app/src/main/java/com/gpsquest96/ui/MainActivity.java`
  (framePumper, `startFramePump()`, TARGET_FPS).
- **Cambio:**
  1) `startFramePump()` cambia el Runnable del `HandlerThread` a un bucle con
     **acumulador**: guarda `lastNs`; en cada vuelta mide `dt = now - lastNs`
     (clamp a 100 ms), calcula
     `ciclos = dt × 4194304 × (doubleSpeed ? 2 : 1)`, y llama
     `gameEngine.update()` (que hace 1 `stepFrame()` cada vez; ver "detalle de
     integración") tantas veces como frames GB completos quepan en `ciclos`,
     con **tope 3** — el remanente se descarta (no se arrastra deuda infinita).
     `lastNs = now` SIEMPRE (aunque se descarte): el reloj se re-ancla en cada
     tick.
  2) Dosificación: `FRAME_INTERVAL_MS` baja de 33 a **16 ms**, de modo que
     "despertar + trabajo + deriva" rodean 17-18 ms y el acumulador normalmente
     ejecuta 1 frame GB y a veces 2 (el catch-up absorbe la deriva).
     Concreción final del intervalo se decide en código según el log `EMU`
     (criterio: fps sostenido, no el valor del intervalo).
  3) Pausa/reanudar (Sofa Mode off / background): reset de `lastNs` y del
     acumulador → sin catch-up retroactivo (RF-04).
  4) `TARGET_FPS: 30 → 60` (GLRenderer).
- **Detalle de integración:** `GameEngine.update()` hoy envuelve
  `emulationPipeline.getCpu().stepFrame()` + atlas; el acumulador llama a
  update() N veces por tick (N ≤ 3). El refresh de atlas se conserva dentro de
  update() (barato: un solo JNI ya sin cruce byte a byte, según bitácora
  2026-10-16). Si el refresh por frame pesa, se limita a 1 de cada 2 frames en
  el siguiente slice, no en este.
- **Aceptación host:** `run.ps1` (30+18+51+20+24 — el core no cambia) +
  `run-java.ps1` 39/39 + `assembleDebug --offline` correcto.
- **Aceptación en el dispositivo de prueba:**
  - log `EMU`: ≥ **55 fps sostenidos** (150+ s caminando en el juego del
    usuario con el mismo `.sav`), 70226-70228 ciclos/frame.
  - Percepción usuario: "ya no cámara lenta"; el parpadeo-lag desaparece o
    queda como fluidez perceptible.
  - Sin rasgado/parpadeo nuevo (anillo 011 preservado); batalla/menús sin
    regreso de la distorsión; `CONTINUE` tras force-stop intacto; reporte de
    calor físico por el usuario (el dispositivo de prueba tibio pero sostenible
    = OK, se registra).
- **Riesgo principal:** deriva de `postDelayed` distorsionando el catch-up; si
  el log `EMU` muestra fps oscilantes/erráticos, se prueba primero un tope de 2
  antes que el 3.
- **Rollback:** restaurar `MainActivity.java` desde el ZIP (un solo archivo).

### Slice 2 — Regresión de cadencia (solo si el gate del slice 1 falla o desvía)
- Ajustar tope/período/atribución de catch-up según el log (a definir con
  evidencia en mano). Mismas puertas.

## Orden de trabajo

1. ZIP respaldo de `MainActivity.java`.
2. Código del slice 1 → host (3 comandos) correcto.
3. APK al dispositivo de prueba → sesión: 150 s caminar + percepción + logcat
   `EMU`/`top -H`.
4. Bitácora/tasks/estado con evidencia.
