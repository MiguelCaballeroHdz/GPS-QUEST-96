# plan.md — 001 Núcleo GBC

Estado: CERRADO 2026-10-16. Slices 1, 2, 3, 4 y 5 ACEPTADOS de forma concluyente en POCO X7 Pro (stepFrame 30 fps, 5.8M banks/60s, tiles:384 100% VRAM, persistencia MBC SRAM load 32768/32768 B). Habilitado B1-2 (celdas del mapa con tiles del atlas).

## Slice 1 — Confirmar APK vigente en POCO

- Archivos: ninguno (solo lectura de evidencia).
- Hacer: rebuild + install desde este árbol; abrir el engrane y leer la línea de guardados.
- Aceptación: la línea contiene `tiles:` (prueba que el APK trae B1-1) y el boot loguea `MBC ready` + header válido con Azul.
- Riesgo: UID nuevo = reinstall que borra ROMs/prefs; reimportar Azul si hace falta.
- Puerta POCO: sí (este slice ES la puerta).

## Slice 2 — Recolectar trace del hang

- Archivos: ninguno (diagnóstico).
- Hacer: 60 s con Azul sin tocar + `adb logcat | grep -E 'trace PC|Opcode no implementado|FATAL|signal'`.
- Aceptación: ~8 líneas `trace PC=` que muestran PC quieto (bucle exacto) o PC avanzando lento (velocidad, no hang) + cero opcodes raros o la lista exacta de ellos.
- Riesgo: sin este dato todo fix es adivinanza; prohibido codificar el fix antes.
- Puerta POCO: sí.

## Slice 3 — Fix exacto del bucle (condicional al slice 2)

- Archivos: solo `app/src/main/cpp/core/cpu.cpp` (o el que el trace señale; nunca más de dos).
- Hacer: implementar lo mínimo que el PC quieto indique (opcode faltante, condición de salida, registro post-boot). Sin tocar tabla sana, escape throttled ni VBlank.
- Aceptación: `assembleDebug` verde + nuevo trace con PC avanzando + `MBC bank=` apareciendo.
- Riesgo: fix equivocado rompe boot; rollback = revert del slice (el árbol compila sin él).
- Puerta POCO: sí (verificación del avance).

## Slice 4 — Verificar intro visible + tiles vivos

- Archivos: ninguno (observación).
- Hacer: foto a 30/120/180 s + leer overlay.
- Aceptación: logo/título del juego visible avanzando; `tiles:M` con M>0; 60 s con cero `Opcode no implementado` y cero FATAL.
- Puerta POCO: sí.

## Slice 5 — Verificar persistencia y ruta sin ROM

- Archivos: ninguno (observación).
- Hacer: jugar 2 min → home + kill por recents → reabrir (buscar `MBC SRAM load N/N B` + progreso intacto); desinstalar/reinstalar → primer arranque debe ofrecer la ROM, cancelar lleva a Demo.
- Aceptación: las cuatro evidencias en log + foto del prompt.
- Puerta POCO: sí.

## Cierre

- `reviewer`: veredicto contra spec 001 + este plan. Sin APROBADO suyo no hay Task 10/B-fases con este núcleo.

## Fuera de alcance (rechazar si aparece)

Spawns, gating por flags, tiles en celdas (B1-2), sprite OW, GBA, post-game, refactor de stack, assets propios.
