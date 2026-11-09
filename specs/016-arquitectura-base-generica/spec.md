# spec.md — 016 Arquitectura base genérica del emulador

Estado: APROBADO — aprobado por Miguel el 9 de noviembre de 2026.

## 1. Objetivo
Establecer una arquitectura base genérica y agnóstica a los datos del juego original del usuario para el emulador del modo retro de 8 bits y del modo a color. El emulador debe exponer una API pública de estado genérico (`BOOT`, `INTRO`, `MAP`, `BATTLE`, `MENU`, `PAUSE`) sin leer direcciones de memoria específicas de una sola partida (como `0xDCB5` del juego del usuario). Toda comunicación entre el emulador y la UI debe ser por eventos de estado genéricos, no por offsets hardcodeados.

## 2. Alcance
- **Máquina de estados del emulador** basada en registros de hardware del modo retro de 8 bits (`LCDC` 0xFF40, `STAT` 0xFF41, `SCX` 0xFF43, `SCY` 0xFF42, `WY` 0xFF4A, `WX` 0xFF4B) que existen en cualquier dispositivo de la familia.
- **API JNI nativa** para consultar el estado actual del emulador desde Java.
- **Listener en `GameEngine`** para notificar cambios de estado (`onEmulatorStateChanged`).
- **`MainActivity` reacciona automáticamente** a cambios de estado llamando a `applyViewMode(true/false)`.
- **Eliminar todo código específico de los datos del juego original del usuario** (`nativeTryWildEncounter`, `callRoutine`, offsets hardcodeados de esa partida).

## 3. Fuera de alcance
- Mapas vectoriales (Bloqueo 2).
- Sprites propios PNG para exploración (Bloqueo 3).
- Capa JSON de POIs (Bloqueo 4).
- Audio APU (fase final, diferido).
- Spawns de batalla por paso real (009 slice 3, se rehace sobre esta base genérica).

## 4. Criterios de aceptación
1. `./gradlew assembleDebug --offline` → `EXIT=0`.
2. No hay referencias a `0xDCB5`, `0xDCB6`, `0xD04E`, `0xD04F`, `0xDCFA` en el código fuente (salvo comentarios explicativos).
3. `GameEngine` expone un `EmulatorState` genérico (`BOOT`, `INTRO`, `MAP`, `BATTLE`, `MENU`, `PAUSE`).
4. `MainActivity` cambia automáticamente de vista cuando el estado cambia a `BATTLE` o `MAP`.
5. El emulador es agnóstico a los datos del juego original del usuario: funciona con cualquier título de la familia cubierta sin cambiar código.

## 5. Riesgos y mitigaciones
| Riesgo | Mitigación |
|--------|------------|
| Detección de estado por hardware tiene falsos positivos | Observar múltiples registros (LCDC + STAT + SCX/SCY + WY/WX) en combinación, no uno solo. |
| El emulador no reporta `MAP` correctamente tras la intro | Fallback: si pasa un tiempo razonable en `INTRO` sin transición, forzar `MAP` como estado seguro. |
