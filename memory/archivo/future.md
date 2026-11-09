# future.md — Ruta Autónoma Épica de GPS Quest '96 (Fusión Retro GB/GBC + Mundo Real)

## 📌 Estado de la Hoja de Ruta Épica

### [x] FASE 0 - ESTABILIZAR MAPA TEMPORAL Y LIMPIEZA
- Fix sello anti-bandas sub-píxel en GPU para cuadrícula 3x3.
- Limpieza de nombres de ROMs comerciales hardcodeadas; migración a ranura genérica `slot1.gb`.
- Verificación MMU con cabecera de ROM hardware de 8 bits 1MB (`ROM Header OK: ROM GEN 1`, `MMU Read [0x0104]=0xCE`).

### [x] FASE 1 - BOOT MÍNIMO GB (Opcodes LR35902 + IRQ VBlank)
- Implementación de opcodes críticos: `LD A,(HL)`, `LD (HL),A`, `LD A,(HL+)`, `LD (HL+)`, `LD A,(HL-)`, `LD (HL-)`, `INC/DEC HL`, saltos condicionales `JR`/`JP`, `CALL`/`RET` y prefijo 0xCB completo.
- Sincronización de interrupción VBlank (`0x40`) en modo 1 de PPU.
- Avance de PC en ejecuciones de prueba en POCO X7 Pro.

---

## 🔮 Ruta viva (integrada 2026-10-25)

Norte (`PLAN.md`): nada propio como contenido — tiles, spawns y gating salen de la ROM en vivo.
El spec maestro `gps_quest_96_spec.html` se adoptó como ruta, **con este orden** (no el
`1-2-3-4-6-7-5-8-9-10` literal, que pone el GPS después de todo el meta-juego):

| # | Fase | Estado | Gate de salida |
|---|---|---|---|
| 1 | **CGB real** — color RGB15, KEY1, SVBK/VBK, HDMA, paletas (spec 007) | **Slices 1-2 + timing parcial verdes** (host PPU 26/26, CPU 20/20); falta gate POCO | Gen 2 en color en POCO + Azul DMG sin regresión |
| 2 | **Guardado real + perfiles `.pueblo`** (spec 008) | **Slices 1-2 hechos** (host 39/39); falta gate POCO | `CONTINUE` en POCO + ida/vuelta `.pueblo` con hash válido |
| 3 | **B2 — spawns desde ROM** (spec 009) | Bloqueada por 1-2 | Caminas y sale el ROM del tramo actual |
| 4 | **005 — gating con flags reales** | Bloqueada por dump POCO | Zona no desbloqueada no interactúa |
| 5 | **004 + fase 9 — mapeo global y AntiBug** | En borrador | 6 POIs de Xalapa mapean; OSM universal con fallback |
| 6 | **Post-game fases 2-8** (shiny 1/500, NG+, trofeos, bosses, plumas, Rocket, co-op) | **Congelado** | `memory/archivo/POSTGAME.md` completo |
| 7 | **GBA** — core ARM7TDMI | Futuro | Spec propia; no se mezcla con CGB |

**Por qué este orden:** el spec construye trofeos y shinies 1/500 sobre un emulador que hasta
hoy no guardaba partida (ya resuelto en 008 slice 1) y no saca spawns (B2 = 0%). Primero el
bucle fiel; después el meta-juego. La fase 1 es la única del spec que es *fidelidad pura* y
además desbloquea Gen 2 (Gen 2), donde el variocolor **nativo** ya existe.

### Fases históricas ya cerradas

- [x] **Fase 0 (antigua):** estabilizar mapa temporal y limpieza de ROMs hardcodeadas.
- [x] **Fase 1 (antigua):** boot mínimo GB (opcodes LR35902 + IRQ VBlank).
- [x] **Fase 2 (antigua):** extracción de tileset (spec 002, cerrada en POCO 2026-10-19).
- [x] **Fase 3 (antigua):** vector OSM (`OsmDataFetcher` + caché 7 días).
- [~] **Fase 4 (antigua):** mapeador OSM→GB (terreno listo; falta monumento→locación = spec 004).
- [~] **Fase 5 (antigua):** renderizador del mundo GB (GL base y cámara listos; ciclo día/noche sin verificar).

### Post-game diferido

Fuera de fase. Código existente congelado. No es MVP. **Marcado como actualización futura:**
ver `memory/archivo/POSTGAME.md` (criterio de apertura: `PLAN.md` completa). Incluye las fases 2-8 del
spec maestro y la parte de spawns de la fase 10 (boost de Abra).

## GBA (core ARM 32 bits)

Diferido. los títulos de GBA (Gen 3). La visión las incluye; el core
LR35902 no las ejecuta. No es "GBC Advance": GBC y GBA son consolas distintas. Cuando entre,
será con spec propia y carpeta de core separada.
