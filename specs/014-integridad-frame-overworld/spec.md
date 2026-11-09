# Spec 014 — Integridad del mapa OSM y del frame (modo a color)

**Estado: APROBADO** (2026-10-29, autorizado por el usuario tras auditoría en el
dispositivo de prueba)

## Problema (medido, no supuesto)

Auditoría en el dispositivo de prueba del 2026-10-29 (carpeta de capturas
correspondiente a `20261029-auditoria-map-juego/evidence.md`, bajo `tools/`):

- **El emulador está sano.** Frame nativo 160×144 limpio, `70 238 ciclos/frame` (hardware
  70 224 = 1×), `fps=59,7`, scroll X/Y sin rasgado ni rastro, menús y batalla correctos.
  El "overworld corrupto" de `walk012c.mp4` **no se reproduce en `Vista: Juego`**.
- **La capa de mapa es la que está rota:**
  - **M-1** `OsmDataFetcher.TIMEOUT_MS = 10000`; la consulta real medida tarda
    **25 943–33 764 ms** ⇒ los 3 intentos siempre agotan el read-timeout.
  - **M-2** Sin datos entra `GBMapRenderer.generateFallbackGrid()`: `java.util.Random`
    sembrado por geohash fabrica 70 % pasto / 15 % tierra / 5 % casa / 10 % agua.
    **Terreno inventado** (viola la regla fundamental 1).
  - **M-3** `T_PASTO=0 / T_CALLE=1 / T_EDIFICIO=2 / T_AGUA=3` apuntan a los 4 primeros
    tiles de la VRAM **viva** (en el juego del usuario: pasto y árboles) ⇒ camuflaje y
    "árboles negros".
  - **M-4** Celdas no cuadradas: `2/20` ancho vs `2/18` alto ⇒ cada tile se estira 2,5:1.
  - **M-5** `files/osm/19.528_-96.932.json` contiene 28 elementos válidos pero su
    `mtime = 1969-12-31`; el TTL de 7 días lo descarta y borra ⇒ se pierde caché buena.
- **Sprite OW inestable:** `SpriteExtractor` oscila entre `base=0/6/19/52/123/133/136/137`
  con `hits` de 3 a 11 123; sobre el mapa dibuja una mancha en vez del jugador.

## Requisitos funcionales

- **RF-01 (red utilizable):** la descarga OSM debe tolerar la latencia real de Overpass
  (≥ 10 s) sin agotar intentos; el `timeout` del cliente debe ser mayor que el del servidor
  (`[timeout:10]` ya viaja en la query). Objetivo: `read/connect timeout = 40 s`.
- **RF-02 (espejo):** si el endpoint principal falla, intentar un segundo endpoint
  documentado, sin cambiar la query ni los datos.
- **RF-03 (cero terreno inventado):** `GBMapRenderer` **no genera** ninguna rejilla propia.
  Sin datos OSM devuelve `false` y la pantalla muestra un lienzo neutro liso (sin pasto
  procedural, sin damero, sin random). El `glClearColor` declarado es el fondo válido.
- **RF-04 (celdas cuadradas):** cada celda del mapa debe ser un cuadrado en píxeles de
  pantalla (misma relación ancho:alto), para no deformar el tile de los datos del juego
  original del usuario.
- **RF-05 (caché honesta):** un `mtime <= 0` (época) no puede invalidar un JSON que parsea
  correctamente; solo se descarta si el contenido no es JSON OSM válido o si el `mtime`
  real supera el TTL.
- **RF-06 (sprite estable):** el sprite OW mostrado sobre el mapa debe ser el mismo durante
  al menos N ticks consecutivos antes de publicarse (histéresis); un candidato con `hits`
  desproporcionado no reemplaza al actual.
- **RF-07 (sin regresión):** PPU, HDMA HBlank, ventana, `CONTINUE`, `.sav` y `.pueblo`
  quedan intactos. El frame nativo sigue siendo la única fuente del juego.

## Criterios de aceptación

- **Host:** arnés C++ (≥ 150 checks) y Java (39) correctos; `assembleDebug --offline` OK.
- **Dispositivo de prueba:** con red real, el overlay deja de mostrar `Red: timeout`; el mapa pinta terreno
  OSM (calles/edificios/agua) con celdas cuadradas; sin datos, fondo liso neutro
  (nunca camuflaje); caminata sin artefactos; `CONTINUE` intacto.
- **Evidencia:** carpeta de capturas del dispositivo correspondiente a `<fecha>-spec014/`
  (bajo `tools/`) con capturas y logcat.

## Anti-metas

- No tocar la PPU ni el render del modo a color (verificado sano en esta auditoría).
- No tocar spawns, gating, SRAM ni post-game.
- No inventar tiles ni "arreglar" el mapa con patrones procedurales.
- No cachear datos de otra coordenada para "tener algo".
