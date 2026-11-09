# Traspaso de agente — GPS Quest Urbano

> **Efímero:** se reescribe en cada traspaso. El estado durable es `memory/archivo/estado_actual-2026-11.md`.
> **Fecha:** 2026-11-06 → **Build:** verde → **Último trabajo:** auditoría **015 B1/B2/B3a/B3b/B5/B6** y **Spec 004 Slice 1** verificados en dispositivo objetivo: vista honesta, mapa legible con POIs clasificados, sprite OW desde OAM, **tiles reales de VRAM en el mapa** (césped/parque/calle), geovallas de agua desde OSM con Modo Sofá y spawn honesto con `nativeTryWildEncounter`.

---

## 0. Lee esto en 5 minutos (y nada más)

En este orden:

1. `PLAN.md` — qué se construye y qué significa "terminado".
2. `AGENTS.md` — las 5 reglas y el flujo SDD obligatorio.
3. `memory/archivo/estado_actual-2026-11.md` — estado verificado, los 4 pilares medidos y la ruta crítica.
4. Este archivo.
5. Solo si vas a tocar una feature: `specs/00X/spec.md` correspondiente.

**NO leas de entrada:** `memory/archivo/bitacora-historico.md` (94 KB), las auditorías ni `future.md`.
Son histórico y evidencia. Si algo de ahí contradice `estado_actual.md`, **manda `estado_actual.md`**.

---

## 1. Dónde estamos (sin adornos)

- **Producto:** cuatro pilares (generación procedural y lectura de datos, mapeo global dinámico, spawn progresivo, gating original).
- **El bucle central está implementado (009 slice 3):** caminas en GPS_REAL → nativeTryWildEncounter lee tablas de datos del usuario → onWildEncounter → applyViewMode(true) abre la interfaz de batalla a pantalla completa.
- **Build verde:** `./gradlew assembleDebug --offline -q`.
- **No hay `.git`.** El único "deshacer" es un ZIP manual.

---

## 2. Qué hizo la sesión anterior (no lo "arregles")

- Creó `memory/archivo/estado_actual-2026-11.md` (brújula única) y `memory/archivo/POSTGAME.md` (post-game congelado con gate).
- Alineó documentos con el código: ventana PPU implementada, `nativeBoostBossEncounter` retirado, datos cargados en RAM.
- **No tocó código de features inaprobadas.** El build sigue verde.

---

## 3. Siguiente acción única (no hay debate)

**Spec 004 Slice 2 — Consulta por corredor/tramo OSM para mapa dinámico.**

**Pantalla encendida (spec 010) — HECHO:** `MainActivity` tiene `FLAG_KEEP_SCREEN_ON`.
**B2/009 slices 1-3 — HECHOS:** `WildTableReader` localiza por estructura las tablas de datos, `nativeGetCurrentMap()` lee las coordenadas WRAM y `nativeTryWildEncounter()` ejecuta la inyección por paso real con auto-switch a pantalla completa (`applyViewMode(true)`).

---

## 4. Trampas: cosas que parecen bugs y son decisiones

| Parece bug | Realidad |
|---|---|
| El juego se ve en gris / colores raros | **CGB slices 1-2 implementados** (detección, paletas, atributos, prioridad). Revisa `CGB ON`, `BCPD` y el banco de tiles |
| El juego no arranca | Es **CGB-only**: sin modo a color real no corre. El log debe decir `CGB ON` |
| No hay CONTINUE / no guarda | **Resuelto:** `Mbc::loadSav()/saveSav()` leen y escriben `<datos>.sav`. Revisa `hasBattery_`/`ramSize_`, no los offsets |
| `.pueblo` no existe / falla | **Implementado (008 slice 2)**. Si falla, revisa que el SHA-256 del manifest coincida con el archivo de datos cargado |
| Zona bloqueada no bloquea | `onEnterBlockedZone()` notifica al usuario. Deuda de 005 |
| `SRAM_FLAG_MAP` vacío | **A propósito.** Direcciones solo con dump de dispositivo real |
| No existe `nativeBoostBossEncounter` | Retirado en C3 porque escribía `0xD000`. **No lo reimplementes** |

**Prohibido:** tocar post-game (`memory/archivo/POSTGAME.md` tiene el gate), escribir en `0xD000`, inventar offsets de datos o crear assets propios como contenido.

---

## 5. Reglas de trabajo

- **SDD:** sin `spec.md` APROBADO no hay código. Roles en `agents/`.
- **Un cambio verificable por turno** + build verde. Si rompe, repara solo ese archivo.
- **Evidencia antes de conclusión:** logcat/foto del dispositivo.
- **Una sola fuente de producto:** `PLAN.md`. No dupliques reglas ni crees `rules/`.
- **Bitácora:** cada slice en `memory/archivo/bitacora-historico.md` con evidencia.
