# plan.md — 003 Sprite OW del Jugador desde ROM (B4)

Estado: APROBADO 2026-10-19. Slice 1 ejecutado. Prerequisite 2026-10-20: input joystick→ROM cableado (joypad 0xFF00) para que el personaje se mueva y el sprite sea detectable. Enmienda RF-01bis 2026-10-22 (OAM centrada). **Slice 2 ejecutado 2026-10-22** (pendiente foto POCO).

## Slice 1 — Monitoreo VRAM para Identificar Sprite

- Archivos: nuevo `app/src/main/java/com/gpsquest96/render/SpriteExtractor.java`.
- Hacer: Clase que compara snapshots de VRAM consecutivos (en worker throttled) y detecta tiles que cambian cuando el jugador se mueve. Sin offsets de ROM: solo observación de comportamiento. Output: lista de índices de tile candidatos.
- Aceptación: `assembleDebug` verde + en POCO, mover al jugador y ver log `SpriteExtractor` con candidatos no vacíos.
- Riesgo: muchos tiles cambian (animaciones del juego). Mitigación: filtrar por tiles que cambian consistentemente con el input.
- Puerta POCO: sí (log de candidatos).

## Slice 2 — Extracción y Composición del Sprite

- Archivos: `SpriteExtractor.java`, `app/src/main/java/com/gpsquest96/ui/MainActivity.java` (GameRenderer).
- Hacer: Componer sprite 2×2 (o tamaño detectado) desde tiles candidatos. Subir a GL como textura (similar a atlas). Fallback: punto blanco si no hay candidatos.
- Aceptación: `assembleDebug` verde + sprite visible en POCO (foto).
- Riesgo: Tamaño del sprite desconocido (2×2 vs 2×1). Mitigación: verificar en POCO y ajustar.
- Puerta POCO: sí (foto del sprite).
- **Ejecutado 2026-10-22:** `identifySprite()` (VRAM-diff + OAM centrada), `composeSprite()` 16×16 con índice 0 transparente y OBP0, textura GL 16×16 (`spriteTexId`), `drawSprite()` con V invertida y fallback a `drawMarker()`.

## Slice 3 — Verificación en POCO X7 Pro

- Archivos: ninguno (observación).
- Hacer: Cargar Azul, ir a overworld, mover al jugador en 4 direcciones, foto por dirección. Verificar que el sprite cambia con el movimiento.
- Aceptación: Foto con sprite visible + sprite distinto por dirección (o evidencia de animación) + `tiles:M>0` + cero FATAL.
- Puerta POCO: sí.

## Fuera de alcance

Animación por frame individual, sprites de NPCs, sprites de combate, GBA, post-game, assets propios, búsqueda de offsets en ROM.

## Rollback

Cada slice es independiente. Revertir el slice que rompa. El punto blanco (`drawMarker()`) se conserva como fallback permanente.
