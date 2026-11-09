# GPS Quest '96

Emulador retro de 8 bits con geolocalización: los datos de juego que aporta el usuario
son la única fuente de contenido y el GPS sustituye el overworld por un mapa pixel art
de la ciudad real.

## Qué hace
- **Sin contenido propio:** tiles, diálogos, objetos y encuentros se leen de los datos
  del usuario en el momento exacto de la aventura.
- **Mapeo global:** la ciudad real se dibuja opaca en pixel art; POIs, PNJs y objetos se
  visitan caminando.
- **Spawn progresivo y gating original:** solo el tramo de historia actual; una zona no
  desbloqueada no interactúa.
- **Control dual:** GPS real o Modo Sofá con joystick virtual.
- **Seguridad:** geovallas de agua fuerzan Modo Sofá y la velocidad alta pausa la marcha.
- **Accesorio BLE opcional:** botón y LED; no altera captura ni progresión.

## Estado
- Fase actual: motor de 8 bits a color (CGB parcial: paletas, doble velocidad, bancos,
  HDMA). GBA diferido, con spec y core propios.
- Build: `./gradlew assembleDebug --offline` verde.
- Estado vigente: `ESTADO.md`.
- Visión, post-juego y ruta: `PLAN.md`.
- Leyes de ingeniería: `RULES.md`.

## Limpieza legal
- El repositorio no incluye ni distribuye ROMs, saves ni assets de terceros.
- Las ROMs las aporta el usuario en su dispositivo (selector SAF); en desarrollo, solo
  desde `C:\Dev\gps-quest-96-private\`, nunca en `assets/`.
- Tests con ROMs sintéticas de 16 KB.
- Ejecutar `./audit-legal.ps1` antes de cada build compartido.

## Documentación
- Estado detallado y tablero: `memory/archivo/estado_actual-2026-11.md`,
  `memory/archivo/tasks-2026-11.md`.
- Bitácora y auditorías: `memory/archivo/bitacora-historico.md`,
  `memory/archivo/auditorias-historico.md`.
- Specs y flujo SDD: `specs/` y `agents/`.
- Licencia: ver `LICENSE`.
