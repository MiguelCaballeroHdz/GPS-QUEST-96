# AGENTS.md — Constitución de GPS Quest Urbano

## Las 5 reglas de oro (de `PLAN.md`, manda el producto)

1. **Generación procedural y lectura de datos 100% fiel.** Nada propio como contenido, nada inventado.
2. **Gating 100% original.** Lo no desbloqueado en el avance no interactúa.
3. **Spawn progresivo y fiel.** Solo el tramo/momento actual de los datos del usuario, nada adelantado.
4. **Fase actual: solo Motor Retro de 8 bits.** Arquitecturas de 32 bits futuras, su código congelado.
5. **Post-game (§8) no es MVP.** Congelado, sin implementar ni usar como norte. Estado y apertura: `memory/archivo/POSTGAME.md`.

## Flujo SDD (obligatorio)

Ningún código sin `spec.md` (APROBADO) → `plan.md` → tasks ejecutadas por el pipeline `agents/` (coordinator → planner → implementer por task → approver → reviewer; loop si hay rechazo). Roles en `agents/*.md`.

El `approver` es la compuerta automática: si el slice cumple los 6 puntos, el pipeline avanza al siguiente sin pedir permiso. Solo se detiene ante un gate físico de dispositivo o un cambio de alcance de producto.

**Agente nuevo:** antes de tocar nada, lee `memory/archivo/traspaso-2026-11.md` (5 minutos). El estado durable está en `memory/archivo/estado_actual-2026-11.md`; la bitácora y las auditorías son histórico, no punto de partida.

## Operativa preservada

- Un cambio verificable por turno + `./gradlew assembleDebug` verde; si rompe, repara solo ese archivo.
- Explica qué archivo tocas y por qué antes de entregar código (Big School).
- Separación drástica: render, lectura de datos de bajo nivel, geo, BLE, control. Rendimiento extremo, API 26+ → dispositivo de prueba 8 GB.
- Registra cada slice en `memory/archivo/bitacora-historico.md` con evidencia; el estado vigente vive en `memory/archivo/estado_actual-2026-11.md` y el tablero en `memory/archivo/tasks-2026-11.md`.
- Una sola fuente de producto (`PLAN.md`): no dupliques reglas ni recrees `rules/`.
- Verificación en dispositivo físico con logs/fotos antes de dar algo por hecho; ante la duda, evidencia primero, síntesis después.
