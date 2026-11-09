# 2026-11-07 — Vista Mapa ↔ Vista Juego (prueba del mecanismo)

Objetivo: comprobar si `applyViewMode(true)` tiene efecto real, antes de atribuir el fallo de
«el combate no cambia de vista» a una regresión.

## Resultado

| Estado | Captura | Media RGB | Colores muestreados |
|---|---|---|---|
| Vista Juego (ROM a pantalla completa) | `vista-juego.png` (164.646 B) | (35, 61, 26) | 152 |
| Vista Mapa (OSM) | `vista-mapa.png` (222.978 B) | (85, 98, 46) | 184 |

Diferencia: **65% de los píxeles muestreados** (paso 16 px) con distancia RGB > 30.

El pref `view_ppu_full` cambió `false → true → false` y la etiqueta del overlay
`Vista: Mapa ↔ Vista: Juego`, con el dispositivo en `control_mode=gps`.

## Conclusión

`applyViewMode(true)` **sí tiene efecto**. El defecto del acta no está en la vista: está en que
**nadie llama** a esa función cuando empieza una batalla.

- `GameEngine.onRealStep()` (:287-291) es un stub `return false;`.
- `tryWildEncounter` / `nativeTryWildEncounter`: 0 coincidencias en `app/src`.
- `onWildEncounter(`: solo declaración y override, sin llamadores.

## Cómo se capturó (importante)

Las capturas se tomaron con redirección binaria:

```powershell
cmd /c "`"$adb`" exec-out screencap -p > archivo.png"
```

Con `& $adb exec-out screencap -p > archivo.png` (redirección de PowerShell) el PNG sale
corrupto: `System.Drawing` responde «Memoria insuficiente» y WPF «no hay decodificador». Es la
misma trampa que corrompió el dump del `.sav` del 11-06.
