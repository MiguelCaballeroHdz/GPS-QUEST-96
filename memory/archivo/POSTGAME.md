# POSTGAME.md — Actualización futura (NO MVP)

> ## 🧊 ESTADO: CONGELADO — FUTURO
>
> **No es MVP. No se implementa ahora. No se usa como norte.**
>
> Se retoma **solo cuando `PLAN.md` esté completa**, es decir, cuando se cumplan
> **todas** estas condiciones:
>
> 1. El checklist de MVP de `memory/archivo/estado_actual-2026-11.md` §7 esté entero.
> 2. **B2** (spawn progresivo desde datos del usuario) esté cerrada.
> 3. **004** (mapeo global monumento→locación) esté cerrada.
> 4. **005** (gating con flags reales de datos de guardado) esté cerrada.
> 5. Exista una spec nueva aprobada para post-game (flujo SDD normal).
>
> Mientras el gate esté cerrado: no se amplía, no se cablea a la UI, no se usa para
> medir avance y **nunca** se escribe en zonas de memoria RAM protegidas.

Fuente original: `RULES.md` §8 y `PLAN.md` ("Fuera de fase"). Este archivo no cambia
la ley: la conserva en un solo lugar y le pone puerta.

---

## 1. Por qué está congelado

- **Regla 5 de `AGENTS.md`:** el post-game no es MVP.
- **`PLAN.md`:** "Desafíos de alto nivel, captura modificada, encuentros especiales repetibles y el resto de RULES §8 **no forman parte del MVP**".
- El post-game se apoya en piezas que **todavía no existen** (spawn fiel, gating real).
  Construirlo antes sería inventar sobre cimientos vacíos: exactamente lo que la
  Regla 1 prohíbe.

---

## 2. Qué existe hoy en el código (sin uso)

| Pieza | Estado | Nota |
|---|---|---|
| `GameEngine.canEncounterBoss()` / `isLeagueComplete()` | Declaradas, **sin uso** | No se llaman desde la UI |
| `GameEngine.tryCalculateCapture()` / `isShinyVariant()` | Declaradas, **sin uso** | Sin efecto en captura real |
| `GameEngine.startBossEncounter()` | Devuelve `false` | Congelado a propósito |
| `nativeBoostBossEncounter` (JNI/C++) | **Eliminado** | Escribía en memoria RAM protegida; **no se reimplementa** |
| Tablas `creatures` / `dojo_medals` | Inertes / vacías | Sin escritura desde el juego |

Nada de esto se borra ni se amplía en esta fase.

---

## 3. Qué se retoma cuando se abra (contenido original de §8)

1. **Desafíos de nivel alto** bloqueados hasta completar el arco principal (`canEncounterBoss`).
2. **Encuentros especiales repetibles** de baja probabilidad en POIs geolocalizados.
3. **Variantes raras de apariencia** con modificadores de probabilidad.

Estas mecánicas **no pueden contradecir** los cuatro pilares de `PLAN.md`:
spawn progresivo, gating original, lectura de datos del usuario y mapeo global mandan.

### 3.1 Fases secundarias diferidas

Las funciones diferidas se quedan aquí, congeladas, hasta que se abra el gate. No se implementan ahora:

| Fase | Contenido | Por qué queda aquí |
|---|---|---|
| 2 | Modificadores de apariencia por RNG propio y bandera visual | Sustituye la tasa nativa de los datos del usuario |
| 3 | **Modo Avanzado (NG+)**: nivel de oponentes escalado | Altera la curva original |
| 4 | **Logros adicionales** | Contenido secundario |
| 5 | **Encuentros ocultos especiales** | Encuentros inventados |
| 6 | **Recompensas por geolocalización** | Economía secundaria |
| 7 | **Reciclaje de elementos** | Economía secundaria |
| 8 | **Eventos narrativos finales** | Cierre narrativo secundario |

---

## 4. Cómo se reabre (proceso, no ocurrencia)

1. Confirmar el gate de arriba (checklist completo).
2. Escribir `specs/00X-postgame/spec.md` con alcance, criterios de aceptación en dispositivo real y riesgos. **Sin APROBADO no hay código.**
3. `plan.md` → slices → implementación por el pipeline `agents/`.
4. Cualquier inyección en RAM debe ser **direcciones reales verificadas con dump**, nunca adivinadas. Prohibido volver a tocar RAM protegida a ciegas.

---

## 5. Prohibiciones mientras esté congelado

- ❌ Implementar, ampliar o "aprovechar" estas APIs.
- ❌ Llamarlas desde `MainActivity` o cualquier flujo de UI.
- ❌ Usarlas como criterio de éxito o como norte de priorización.
- ❌ Escribir en zonas de RAM del juego sin evidencia de dump.
