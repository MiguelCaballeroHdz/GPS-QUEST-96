# B2/009 slice 2 — mapa actual (POCO)

Fecha: 2026-10-27 14:51–14:52
Dispositivo: POCO X7 Pro `XWWSAY4LAAPZHIA6`
Build: `2026-10-27+b2-currentmap` (`assembleDebug --offline` verde)

## Objetivo

Leer `wMapGroup`/`wMapNumber` reales y loguear la tabla de encuentro del mapa
actual. Solo lectura: sin spawn, sin escrituras de estado.

## Resultado

Partida cargada con `CONTINUAR` (starter ya obtenido). El jugador está en
ruta inicial. Long-press en `Vista: Juego` (diagnóstico existente) produjo:

```
B2 mapa actual 24/3 (y=10 x=46)
B2 mapa actual 24/3 (y=10 x=46): kind=1 tabla=0x2a5f0 entrada=0x2ae04 n=61 tasas=25/25/25 primer=2/16
```

Sondas fijas de regresión en el mismo volcado:

```
B2 probe 24/3 (ruta inicial): kind=1 tabla=0x2a5f0 entrada=0x2ae04 n=61 tasas=25/25/25 primer=2/16
B2 probe 24/7 (Casa jugador): sin tabla
```

El motor se pausó para el volcado y se reanudó:
`GameEngine pausado` → `PPU dump OK` → `GameEngine reanudado`.

## Criterio de salida

- [x] ruta inicial devuelve `24/3` y tabla grass válida.
- [x] La casa (`24/7`) no tiene tabla (sonda fija).
- [x] Sin cambios de gameplay: el juego sigue en ruta inicial y responde.
- [x] `CONTINUE` sigue funcionando tras reinstalar el APK.
- [x] Build verde.

## Evidencia

- `logcat.txt` — log filtrado `GPSQuest96`/`GameEngine`.
- `route29_clean.png` — ruta inicial limpia, tras cerrar el panel.
- `route29.png` — panel abierto con el volcado ya ejecutado.
- `route29_loaded.png` — primera captura tras cargar `CONTINUAR`.

## Notas

- El frame PPU del volcado no se usa para este gate; el criterio es el log del
  mapa actual y la tabla.
- No se disparó ningún encuentro. El slice 3 sigue pendiente.
- El tirón de ~26 fps y el frame roto transitorio observados durante la sesión
  quedan fuera de este slice; no se tocó el render.
