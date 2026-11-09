# Estado actual — GPS Quest '96

> **Brújula única del proyecto.** Para *producto* manda `PLAN.md`. Para *estado* manda este archivo.
> Si un documento histórico contradice lo que dice aquí, gana este archivo.
> Última verificación: **2026-11-07** (slices 1, 2 y 6a de 004; auditoría de specs
> 012-016; build `EXIT=0`).
> Último build verificado: **2026-11-07** (`assembleDebug --offline`, `EXIT=0`; APK
> 12.410.600 B, SHA-256 `F532C426…0E7AEA8`; ver §11.10).
> Spec 004 **APROBADA el 2026-11-06** con alcance POI-first + ruta maestra (ver §11.4, §11.8,
> §11.9 y §11.10).

---

## 1. Qué se está construyendo

Un emulador GB/GBC de ROM donde el overworld es tu ciudad real. Definen el producto
cuatro pilares, y **solo esos cuatro**:

1. **Extracción quirúrgica de la ROM** (tiles, sprites, diálogos, objetos, spawns).
2. **Mapeo global dinámico** (monumento/POI real → locación del juego).
   - **Navegación Pixel Art:** Mapa de la ciudad **100% OPACO en Pixel Art GBC** (con los tiles de VRAM) mostrando las calles, POIs, entrenadores PNJ y objetos.
   - **Interacción / Batallas:** Al llegar a un punto o combate, el emulador pasa a **Primer Plano Opaco a Pantalla Completa** con la escena/batalla original de la ROM.
   - **Modo Sofá:** Botón **CAMBIAR MODO** que abre el emulador tradicional 100% normal a pantalla completa con Joystick.
3. **Spawn progresivo y fiel** (solo el tramo de historia actual).
4. **Gating 100% original** (zona no desbloqueada no interactúa).

Fuente: `PLAN.md` (única fuente de producto).

---

## 2. Verificado (2026-10-23; actualizado 2026-10-30)

- **Build verde:** `./gradlew assembleDebug --offline` → `EXIT=0`.
- **Specs cerradas:** `001-nucleo-gbc` (POCO, 5/5), `002-mapa-tiles-atlas` (POCO),
  `003-sprite-ow` (sprite OW verificado en POCO 2026-10-30, ver Spec 014).
- **Spec 014 (mapa OSM + sprite) verificada en POCO:** frame del emulador limpio (CGB,
  59,7 fps, `CONTINUE`); mapa **sin terreno inventado** (con OSM pinta celdas de color
  documentadas y calles; sin red ni caché queda el lienzo plano) y **sprite OW fiel**:
  `base=0 via=OAM bank=1 pal=0`, tiles 0-3 de VRAM banco 1, silueta idéntica al frame
  de la PPU. Evidencia `tools/poco-capture/20261029-spec014/` y
  `tools/poco-capture/20261030-spec014-sprite/`.
- **Núcleo:** CPU LR35902 + MMU + MBC + SRAM; PPU con fondo, **ventana** (implementada en
  `ppu.cpp:229-268`) y sprites; DMA de OAM (`0xFF46`); ROM completa en RAM.
- **Capa real:** GPS foreground, BLE accesorio BLE, OSM (Overpass + caché), modo Sofá,
  joystick + A/B/START/SELECT, zoom, intro, 10 idiomas.
- **Evidencia POCO previa:** SRAM 32.768 B sobrevive force-stop; volcado `sram_dump.txt`;
  atlas `tiles:384` (100% VRAM); mapa OSM + HUD sin artefactos.

### Gate POCO de B4 — ejecutado el 2026-10-23: **ROJO**

Corrido en POCO X7 Pro (`2412DPC0AG`) con APK debug instalado. Resultado: **no se llega al
overworld**, así que B2 sigue sin arrancar.

- **Verde:** instalación, carga de ROM (Azul/Verde/Amarilla), intro y menús se dibujan;
  `SpriteExtractor` confirma `hits=3` (`base=28/73/143 via=OAM tiles=NN..NN+3`).
- **Rojo 1 — CAUSA ENCONTRADA Y CORREGIDA (2026-10-23, tarde):** el bloqueo no era el
  prompt ni el joypad: la ROM estaba en `Serial_ExchangeByte` (`0x1FF5` escribe `SC=0x81` y
  espera la finalización). El MMU no emulaba `0xFF01/0xFF02`, así que `SC` conservaba el bit 7
  para siempre. Ahora el reloj interno completa el byte (`SB=0xFF`, `SC` bit 7 a 0, `IRQ 3`).
  Evidencia: `out/poco/s2_serial_fix_01..03.png`, `out/poco/s2_clean_build.png`.
  Antes del arreglo, `hJoyHeld/hJoyLast/hJoyPressed` quedaban congelados en `0x01` y el texto
  se reimprimía en bucle.
- **Rojo 2 — CAUSA RAÍZ ENCONTRADA Y CORREGIDA (2026-10-23, noche):** el render roto
  (rayas verticales, fondo vacío, retrato de profesor corrupto) **no era la PPU**: era el **MMU
  mapeando mal la WRAM interna**. `read()` y `write()` de `0xD000-0xDFFF` restaban `0xD000`
  sobre un `ram_[0x2000]` continuo (0xC000-0xDFFF), así que `0xD0xx` aliaseaba con `0xC0xx`.
  El descompresor del retrato usa `0xD0A5-0xD0B8` como estado y lo pisaba con variables del
  banco 0. Ambos lados corregidos a `address - 0xC000`.
  - **Evidencia A/B (mismo arnés, misma ROM, métrica idéntica — VRAM 8 KB contra
    `out/poco/ref/vram.bin`):**
    - **Antes** (write con `- 0xD000`): VRAM **6518/8192** bytes distintos, OAM **80/160**, y el
      flujo **ni siquiera llega al retrato** (corría hasta el frame 10452 sin `03_oak`).
    - **Después** (ambos lados `- 0xC000`): VRAM **2/8192**, OAM **0/160**, retrato en el frame
      1970 y flujo completo en 2046.
    - Los 2 bytes restantes (`0x9E01-0x9E02` = `¡` y `B`) son los dos primeros caracteres de la
      segunda línea del cuadro: nuestro volcado va 2 caracteres por delante de PyBoy.
      **Diferencia de instante de volcado, no corrupción.**
    - (El 709/784 previo era del búfer descomprimido de 784 B, otra métrica; no se mezcla.)
  - **Frame del arnés:** retrato de profesor y texto correctos → `our/03_oak_harness.png`.
  - **Build verde** tras el cambio: `assembleDebug --offline` exit 0.
  - **Gate POCO repetido el 2026-10-24 (madrugada): VERDE PARCIAL, el fix se ve en el móvil.**
    - Arranque → copyright → título (la mascota del título y logo nítidos) → discurso de profesor con retrato y
      `¡Hola a todos!` correctos → cuarto de Pallet con cama, mesa/SNES y planta bien dibujados.
    - `SpriteExtractor` en dispositivo: `base=1 via=OAM hits=3`, `base=5 ... hits=136` (jugador).
    - Joystick verificado sobre el terreno: el jugador se mueve por el cuarto y la cámara sigue.
    - **Overworld verificado en el core (2026-10-24):** con la puerta del 1F (`down` desde
      `(2,6)`) el juego sale a **pueblo inicial (`0x00`, pos `5,6`)**, y PyBoy con el mismo
      teletransporte da exactamente lo mismo. El **tilemap (`0xC3A0`) coincide byte a byte**
      (0/360); VRAM 9/8192 y OAM 8/160 de diferencia, todos por frame del sprite que camina.
      Evidencia: `out/poco/decomp/our_bed/07_door.png`.
    - **Falta solo la foto en el POCO**: caminar con el joystick cuarto → derecha, arriba,
      arriba, derecha, arriba (escalera) → 1F → bajar a la puerta (2,7) → pueblo inicial.
    - **Warps verificados en el core, con PyBoy como oráculo:** 2F→1F dispara correctamente. El
      fundido tarda ~18 frames; pulsar durante el fundido lo aborta y dio un falso negativo. Las
      puertas del 1F (`destinoMap=0xFF`) **también funcionan**: `down` desde `(2,6)` lleva a
      pueblo inicial `0x00` en ambos emuladores.
    - **Instrumentación retirada (2026-10-24):** `g_currentPc` por instrucción y bloques
      `COPY`/`CPY` de `cpu.cpp`; logs `W1010` y `P1READ` de `mmu.cpp`; `logBankIfNeeded` y
      `bankLogCount_` de `mbc.cpp`/`mbc.h`. El log del móvil pasó de cientos de líneas/s a 5
      líneas de arranque. El hook `GPS96_DIAG_WRITE_HOOK` se queda: solo compila en el arnés
      (`#ifdef`), no viaja en el APK.
    - **Guardado real (spec 008 slice 1, 2026-10-25):** `Mbc::loadSav()`/`saveSav()` ya no son
      stubs. El `.sav` vive junto a la ROM copiada (`filesDir/roms/<rom>.sav`), se escribe de
      forma atómica (`.tmp` → `.bak` → rename) y solo si el cartucho tiene batería. Evidencia:
      `test_sav` 18/18 (carga, dos bancos, respaldo, ROM sin batería) + `test_mmu` 30/30.
      **Spec 008 slice 2 (`.pueblo`) hecho:** ZIP con `manifest.json` + SHA-256 + `battery.sav`
      + `geo.sav` + `prefs.json`, export/import por SAF y recarga de ROM; host Java 39/39.
      **Gate POCO del 2026-10-26 — CGB + CONTINUE + `.pueblo`: VERDE PARCIAL.**
      Gen 2 arranca en color (`CGB ON (flag 0xC0, only)`, `MMU modo CGB`), título y
      cuarto a color, joystick en Modo Sofá. Tras guardar, force-stop y reabrir, `CONTINUE`
      carga la partida (`JUGAD. AAAAAAA`, 0:14). `.pueblo` exporta `manifest.json` +
      `battery.sav` + `geo.sav` + `prefs.json`; el SHA-256 de la ROM y el hash del `.sav`
      coinciden; la importación reescribe el `.sav` byte a byte y el perfil manipulado se
      rechaza con "El perfil no corresponde a esta ROM". Regresión DMG (Azul): `CGB OFF
      (flag 0x00)`, título, profesor y ventana de texto correctos. Falta el pase de "borrar
      datos / otro equipo" y caminar el cuarto de Azul en este pase.
      Evidencia: `tools/poco-capture/20261026-continue/` + `memory/archivo/bitacora-historico.md`.
      **CGB host (2026-10-25 noche):** `test_cgb_ppu` 26/26 (paletas, banco 1, flips, prioridad
      BG/OBJ, `OPRI`, ventana, STAT/LYC) y `test_lr35902` 20/20 (STOP, DIV/TIMA en doble
      velocidad). Son lógica de core; el color y el `CONTINUE` reales ya se verificaron en
      POCO el 2026-10-26.
      **Captura POCO en un pase:** `tools/poco-test.ps1` + dumps ampliados (VRAM banco 1,
      paletas BG/OBJ, registros CGB) con pausa del motor para no corromper el frame.
- **Hack eliminado:** la inyección de `hJoyHeld/hJoyPressed` en WRAM por
  `romBank==63 && PC∈{0x5A9A,0x5AB4}` ya no existe; el core quedó limpio (`cpu.cpp`).
- **Fotos/log:** `out/poco/gate_*.png`, `out/poco/verde_*.png`, `out/poco/gate_b4_logcat_verde.txt`.
- **Operativo:** `adb shell input tap` se pierde; usar `input swipe x y x y 150` como pulsación larga.
- **Pantalla encendida (spec 010, 2026-10-26):** `MainActivity` añade `FLAG_KEEP_SCREEN_ON`
  a su ventana. En POCO, con timeout de 15 s y `stay_on_while_plugged_in=0`, la pantalla
  siguió encendida a los 35 s con la app en primer plano; al pulsar Home el sistema apagó a
  los 24 s (`Dozing`). No hay `WAKE_LOCK` ni cambios globales; el flag no desbloquea un
  dispositivo apagado. Evidencia: `tools/poco-capture/20261026-keepon/`.

- **B2/009 slice 1 (2026-10-27):** tablas de encuentro de Gen 2 localizadas
  por estructura y parseadas desde la ROM; `test_wildtable` 24 checks sintéticos
  (41 con ROM real). En POCO, ruta inicial (`24/3`) devuelve `grass tabla=0x2A5F0
  entrada=0x2AE04 n=61 tasas=25/25/25` y la casa (`24/7`) devuelve vacío. Solo
  lectura; no hay inyección ni spawn todavía. Evidencia:
  `tools/poco-capture/20261027-b2-slice1/`.

- **B2/009 slice 2 (2026-10-27):** `nativeGetCurrentMap()` lee
  `0xDCB5..0xDCB8` con guardas y el long-press de diagnostico loguea el mapa
  actual y su tabla. En POCO, ruta inicial devuelve `24/3 (y=10 x=46): kind=1
  tabla=0x2a5f0 entrada=0x2ae04 n=61 tasas=25/25/25 primer=2/16`; la sonda fija
  de la casa (`24/7`) sigue sin tabla. `CONTINUE` intacto tras reinstalar.
  Evidencia: `tools/poco-capture/20261027-b2-slice2/`. Sin spawn.

---

## 3. Los 4 pilares, medidos contra VISION

| Pilar | Qué exige VISION | Hoy | Gap real | Siguiente artefacto |
|---|---|---|---|---|
| **1. Extracción** | Tiles, sprites, diálogos, objetos y spawns leídos de la ROM en vivo | Tiles **100%** · Sprite OW **100%** (VRAM banco 1 + paleta OAM, verificado en POCO 2026-10-30, spec 014) · Spawns: tablas **100% lectura** (slices 1-2) + inyección nativa por paso real (slice 3 implementado y compilado) · Diálogos y objetos **0%** | El contenido no gráfico sigue sin extracción completa | Cerrar `specs/006` (render) → gate POCO de `specs/009` slice 3 |
| **2. Mapeo global** | Monumento/punto icónico real → locación del juego, sin hardcodear ciudades | **~35%**: OSM entrega terreno (calle, pasto, agua, edificio) y el mapa ya pinta **tiles reales de VRAM** en césped/parque/calle (B3b); agua/edificio/comercio siguen planos. La **ruta maestra ya se genera** (180 nodos, POI-first, determinista) y se pinta en el mapa | Falta **consumirla por GPS** (slices 3-5, bloqueados por 016) y tiles de agua/casa | `specs/004` con slices 0-2 hechos; 016 para desbloquear 3-5 |
| **3. Spawn progresivo** | Solo el spawn del tramo actual; nada adelantado | **Lectura de tablas 100% (slices 1-2, verificado en POCO)**. **El slice 3 NO está en el árbol** (auditado 2026-11-07): `GameEngine.onRealStep()` es un stub que devuelve `false`, `tryWildEncounter`/`nativeTryWildEncounter` no existen y `onWildEncounter` no tiene ningún llamador | Los encuentros que aparecen son los de la ROM; la app no participa ni cambia de vista | **Re-implementar 009 slice 3 sobre 016** (el comentario del stub lo dice: «espera a la arquitectura base genérica») |
| **4. Gating original** | Flags reales de la SRAM; zona bloqueada no interactúa | **~10%**: `readSramFlagBit()` existe, `SRAM_FLAG_MAP` está **vacío a propósito**; `GeofenceManager` detecta, pero `MainActivity.onEnterBlockedZone()` es un método **vacío** y `GameEngine.update()` tiene la actualización **comentada** | Detección sin efecto visible + flags inventados (`eventFlags[]`) | `specs/005` (BLOQUEADO por dump POCO) |

**Traducción:** los cimientos están (~80%), el producto VISION no (~15%). El bucle "caminas → te sale el ROM que te toca" **no está cableado**: la lectura de tablas existe (009 slices 1-2), pero el disparo por paso real es hoy un stub (`onRealStep` → `false`). Los combates que se ven son los de la ROM.

> Los porcentajes son brújula, no medida. No se usan como criterio de aceptación.

---

## 4. Deudas de fidelidad (no son features nuevas)

No salen de VISION como mecánicas, pero rompen el "100% fiel" si se ignoran:

| Deuda | Estado | Impacto |
|---|---|---|
| **Audio (APU)** | **Implementado pero roto (auditoría 015, 2026-11-01)**: NR52 off no apaga (`apu.cpp:344-347`), `read(0xFF26)` no reporta canales, sin filtro DC, anillo sin control de nivel, 0 tests | El sonido sale, pero con clics, DC y cortes; el driver de la ROM no ve canales activos | `specs/015` B7/B8 |
| **GBC real** | **Verde parcial en POCO (spec 007)** — slices 1-2 + timing parcial: detección 0x143, 2 bancos VRAM, 8 bancos WRAM, KEY1/VBK/SVBK/paletas/HDMA/GDMA, doble velocidad, render CGB (paletas RGB15, atributos banco 1, prioridad `OPRI`), DIV/TIMA y STAT/LYC. Gen 2 arranca a color (`CGB ON`, `MMU modo CGB`), `CONTINUE` funciona y la regresión DMG (Azul) da título/profesor/ventana correctos. Falta caminar el cuarto de Azul en este pase y HDMA HBlank | Si el color o DMG fallan, revisar `CGB ON`, `BCPD` y el banco de tiles, en ese orden |
| **Timing CPU/PPU** | Aproximado; `DIV`/`TIMA` corregidos en doble velocidad, `STOP` resetea `DIV`, `STAT/LYC` con IRQ por flanco. Sin retardo de EI ni HDMA troceado por HBlank | Afecta audio y efectos, no estabilidad |
| **MBC3 RTC** | Sin verificar latch/hora | Eventos temporales de Gen 2 no son fieles |
| **Persistencia `.sav`** | **Verificada en POCO (spec 008)** — carga al abrir, escritura atómica con `.bak`, solo con batería; `CONTINUE` tras force-stop; `.pueblo` export/import byte a byte y rechazo por hash | Sin esto no había partida persistente ni forma de probar sesiones |

**Cerrada el 2026-10-23:** ventana PPU (`renderWindow`), que la auditoría del 22 aún listaba como TODO.

**Cerrada el 2026-10-23 (tarde):** puerto serie `0xFF01/0xFF02`. Sin él, cualquier ROM que
hiciera `Serial_ExchangeByte` (Amarilla en la intro) se quedaba esperando el fin de la
transferencia para siempre.

---

## 5. Ruta crítica (reordenada 2026-10-25)

El usuario autorizó el spec maestro como ruta, **con el orden corregido**: primero el bucle
fiel, después el meta-juego. El orden literal del spec (`1-2-3-4-6-7-5-8-9-10`) ponía GPS y
spawns después de trofeos y shinies 1/500; se invierte a propósito.

0. **Foto del overworld en POCO** — único pendiente del gate B4 (`specs/006`). No bloquea lo
   demás, pero sin ella no se declara B4 cerrado. La hace el usuario con el dispositivo.
1. **Spec 007 — CGB real.** Slices 1-2 **hechos** (detección, bancos, registros CGB, doble
   velocidad, GDMA síncrono, paletas RGB15, atributos del banco 1 y prioridad; arnés 30/30).
   Timing parcial **hecho**: `DIV`/`TIMA` a ritmo completo en doble velocidad, `STOP` resetea
   `DIV`, `STAT/LYC` con IRQ por flanco (`test_lr35902` 20/20 + `test_cgb_ppu` 26/26).
   **Gate POCO 2026-10-26: verde parcial** — Gen 2 en color, `CONTINUE` y movimiento en
   Modo Sofá; regresión DMG (Azul) título/profesor/ventana OK. Falta caminar el cuarto de Azul en
   este pase; HDMA troceado por HBlank solo si falla el overworld.
2. **Spec 008 — guardado real + perfiles `.pueblo`.** Slice 1 **hecho**: `.sav` real junto a
   la ROM (`filesDir/roms`), atómico con `.bak`, solo con batería; `test_sav` 18/18. Slice 2
   **hecho (código + host 39/39)**: `.pueblo` con `manifest.json` + SHA-256 + `battery.sav` +
   `geo.sav` + `prefs.json`, export/import por SAF y recarga de ROM. **Gate POCO 2026-10-26:
   verde parcial** — `CONTINUE` tras force-stop, `.pueblo` export/import con `.sav` byte a
   byte y rechazo por hash. Falta el pase de borrar datos/otro equipo para cerrar 008 del todo.
3. **Spec 009 — B2 spawns desde ROM.** **Slices 1-2 hechos (2026-10-27):** lectura
   estructural de las cuatro tablas de Gen 2 y verificación en POCO (ruta inicial sí,
   casa no); host 41/41 con ROM real. **Slice 3 (inyección por paso real): NO implementado**
   (auditoría 2026-11-07). `GameEngine.onRealStep()` es un stub que devuelve `false` con el
   comentario «espera a la arquitectura base genérica (Spec 016)»; `tryWildEncounter` y
   `nativeTryWildEncounter` no existen en el árbol. La bitácora del 2026-11-04 describía una
   implementación que ya no está (ver corrección del 2026-11-07).
   
   
   
4. **005 — gating con flags reales**, cuando exista el diff de dumps POCO (`Slice 5c`).
5. **004 + fase 9 — mapeo global y AntiBug**, sobre el terreno que ya funciona. **Slices 0-2
   hechos el 2026-11-07** (POI-first, ancla confirmada y ruta maestra de 180 nodos); los
   slices 3-5 esperan 016 y el 6 (export/import) queda sin bloqueo. El "puzzle 5x5"
   inventado no entra como contenido; se resuelve con escenas de la ROM.
6. **Post-game fases 2-8** (shiny 1/500, NG+, trofeos, bosses, plumas, Rocket, co-op):
   **congelado** en `memory/archivo/POSTGAME.md`. No se planifica ni se usa como norte.

**GBA** (ARM7TDMI) no es una fase de CGB: va con spec propia, después, y en carpeta aparte.

Dependencias: 009 necesita 007+008; 005 necesita el dump; 004 no bloquea a nadie pero no
convierte el emulador en producto. La deuda de **audio (APU)** sigue abierta y se decide con
el usuario antes de prometer "100% fiel".

---

## 6. Mapa documental (qué leer y qué no)

**Canónico (leer siempre):**
- `PLAN.md` — producto (única fuente).
- `RULES.md` — leyes de ingeniería.
- `STACK.md` — stack real.
- `AGENTS.md` — flujo SDD.
- `memory/archivo/estado_actual-2026-11.md` — este archivo.
- `memory/archivo/traspaso-2026-11.md` — briefing de agente nuevo (efímero; se reescribe en cada traspaso).
- `memory/archivo/tasks-2026-11.md` — tablero de ingeniería.
- `memory/archivo/POSTGAME.md` — post-game congelado (NO MVP; solo se abre con VISION completa).

**Especificaciones:** `001`, `002`, `003` cerradas · `004` **APROBADA 2026-11-06** (POI-first +
ruta maestra; plan con slices 0-6, **0-2 hechos 2026-11-07**) · `005` en BORRADOR ·
`006` APROBADA (falta foto POCO) · `007` APROBADA (slices 1-2 + timing parcial; gate POCO
CGB verde, falta HDMA HBlank) · `008` APROBADA (slices 1-2; gate POCO verde parcial, falta
prueba tras `pm clear`/otro equipo) · `010` APROBADA y **cerrada** (pantalla encendida).

**Histórico (no usar como estado):**
- `memory/archivo/bitacora-historico.md` — registro cronológico con evidencia.
- `memory/auditoria_2026-10-22.md`, `memory/auditoria_core_2026-10-22.md` — fotos fechadas.
- `future.md` — hoja de ruta; se actualiza cuando una fase se cierra.

Los documentos retirados el 2026-10-23 (`feature.md`, `CONTEXTO_FASE_1_OFFLINE.md`,
`memory/auditoria_cableado.md` y `rules/*`) no se mantienen: su valor era histórico y está
en la bitácora. Respaldo: `Temp/opencode/gps96-cleanup-2026-10-23.zip`.

---

## 7. Definición de MVP terminado

- [ ] Intro original intacta **y con audio**.
- [ ] Salir de casa en el juego = salir a tu ciudad real.
- [ ] Tiles, sprites, diálogos, objetos y spawns salen de la ROM en vivo.
- [ ] Mismo lugar real → misma locación del juego (determinista, sin listas por ciudad).
- [ ] Solo aparece el spawn del tramo de historia actual.
- [ ] Zona no desbloqueada: no se interactúa, solo combates de ruta.
- [ ] Derrota: coordenadas intactas + tregua + restaurar.
- [ ] Cero datos inventados en el camino (`grep` de offsets/tablas propias vacío).

---

## 8. Post-game (congelado, no MVP)

Bosses 90+, varicolor 100% y legendarios repetibles quedan **fuera de fase**.
No se implementan, no se cablean a la UI y no cuentan como avance del MVP.

- Estado, inventario de código y **criterio de apertura:** `memory/archivo/POSTGAME.md`.
- Se abre solo con el checklist §7 completo **y** B2/004/005 cerradas.
- El inyector `nativeBoostBossEncounter` (escribía en `0xD000`) **no vuelve**.

---

## 9. Decisiones del usuario (2026-10-25)

El usuario autorizó el spec maestro `gps_quest_96_spec.html` como **ruta a seguir**, con estos
límites explícitos:

1. **"modo color Advance" = GBC a color**, no GBA. El objetivo inmediato es color real
   (CGB) con el variocolor **nativo** de Gen 2. GBA sigue diferido y con spec propia.
2. **Lo post-game va a post-game:** fases 2-8 del spec (shiny 1/500, NG+, trofeos, bosses,
   plumas, invasión Rocket) quedan en `memory/archivo/POSTGAME.md`, congeladas. No se implementan ahora.
3. **Lo que sirve hoy se integra:** CGB real, guardado/perfiles `.pueblo`, menú obligatorio,
   OSM/AntiBug y la parte de hardware de la pulsera entran a la ruta viva.
4. **Orden corregido:** CGB → guardado → B2 spawns → gating → mapeo → post-game. No se
   construye meta-juego sobre un emulador que aún no guarda ni saca spawns.
5. **Pruebas:** el usuario aporta el dispositivo cuando haya algo jugable; la evidencia
   (foto/log POCO) sigue siendo obligatoria antes de declarar un gate cerrado.

---

## 10. Auditoría 015 — estado (2026-11-01)

Spec y plan aprobados: `specs/015-auditoria-producto-fidelidad/`. Ruta de 8 bloques,
audio al final; **B1 es gate de producto** (retirar el híbrido translúcido y dejar el mapa
opaco, con la ROM solo a pantalla completa).

| Bloque | Qué cierra | Estado |
|---|---|---|
| B1 | D-01 vista honesta (mapa opaco, sin PPU encima) | ✅ 2026-11-01 (POCO, `20261101-015-b1/`) |
| B2 | D-09 vías completas + D-10 sprite a escala + D-23 carrera OSM + D-24 rejilla con aspecto | ✅ 2026-11-01 (POCO, `20261101-015-b2/`) |
| B3 | D-02 mapa con tiles de VRAM (Pixel Art GBC) + D-25 sprite falso | ✅ 2026-11-03: D-25 (`20261101-015-b3/`) y D-02 en césped/parque/calle (`20261103-015-b3b/`); agua/edificio/comercio planos por falta de tile verificado |
| B4 | D-06 verdad GPS (`WalkFilter`, sin asumir caminata) | pendiente |
| B5 | D-06 geovallas de agua desde OSM | 🟡 2026-11-03: OSM→geovalla + HUD + Modo Sofá + host test 11/11; falta gate físico junto a agua (`20261103-015-b5/`) |
| B6 | D-05 spawn sin mentir (quitar banner falso) | ✅ 2026-11-03 (banner retirado). **Corrección 2026-11-07:** el «combate real de 009 slice 3» **no está en el árbol** (`onRealStep` es un stub); el combate que se ve es el de la ROM |
| B7 | D-17/18/19/22 APU fiel + `test_apu.cpp` | pendiente |
| B8 | D-20/21 hilo y buffer de audio | pendiente |

**Backlog de núcleo (sin gate POCO):** EI diferido (D-11), flags `SP+e8` (D-12), `DAA`
(D-13), ventana DMG (D-14), prioridad sprite DMG (D-15), RTC MBC3 persistente (D-16).
**No tocar:** `specs/005` (gating real) sigue bloqueada por el diff de dumps; post-game §8
y GBA siguen congelados.

---

## 11. Cierre 2026-11-06 — build verde, despliegue POCO y spec 004 aprobada

### 11.1 Evidencia de build (cruda)

| Campo | Valor |
|---|---|
| Fecha | 2026-11-06 |
| Cambio | `Kind.GOODs` → `Kind.GOODS` en `OsmDataFetcher.java:327` (1 token; sin BOM, UTF-8 intacto) |
| SHA-256 fuente tras el fix | `E94AB49A…CD6F90A` (14.282 B; mtime 11/06 14:20:35) |
| Build | `./gradlew assembleDebug --offline` → **`EXIT=0`** |
| APK | **11.395.953 B**; mtime 11/06 14:20:44 (9 s después del fuente) |
| SHA-256 APK | `7C771E4874F87ECB7E10746BD9A8A585A29E33AAF7F9F2F426D3929F07A60E97` |

### 11.2 Despliegue en POCO

- Dispositivo: POCO X7 Pro `XWWSAY4LAAPZHIA6` (2412DPC0AG).
- `adb install -r` → `Success`; el APK extraído del dispositivo tiene **hash idéntico** al
  construido, así que el binario auditado y el que corre son el mismo.
- Log: `%TEMP%\opencode\gpsquest96-boot-20261106.log` · captura: `%TEMP%\opencode\poco-20261106.png`.
- Checklist: `Displayed +671 ms`, `PM_CRYSTAL`, `CGB flag 0xC0 (CGB-only)`,
  `MBC3 cartBanks=128 ram=32768B`, `SRAM 32768/32768 B` (partida del usuario intacta),
  `fps=59,7`, `sprite OW base=52 via=OAM`, `FATAL=0`, `UnsatisfiedLink=0`, `ANR=0`,
  UI `Modo Sofá`/`⚙`/`−` con acentos correctos.

### 11.3 Qué **no** demuestra este cierre

- **Nada de POI-first**: el enum nuevo no tiene consumidor; el comportamiento es idéntico
  al APK del 11/05. El fix fue de compilación, no de producto.
- **Geovallas ni paso real**: el teléfono está en interiores y en Modo Sofá (sin fix GPS).
- **Intro desde cero**: la app abrió con la partida existente.
- **Etiqueta del binario**: el log dice `BUILD 2026-11-05+spec016-slice2` (**desfasada**);
  la única atadura entre binario y árbol es el hash de §11.1.

### 11.4 Spec 004 aprobada (mismo día)

`specs/004-mapeo-global-dinamico/spec.md` pasa de BORRADOR (2026-10-23) a **APROBADA
2026-11-06**; `plan.md` se reescribe (el del 2026-10-28 queda superado). Alcance nuevo:

- POI-first con respaldo sintético; distancia por red de calles; consulta por corredor
  (no círculo único de `RADIUS_M = 150 m`).
- Ancla confirmada (no primer fix), re-anclable solo hasta resolver el nodo 0.
- Nodos tipados (`walk`, `enter`, `talk`, `use`, `battle`, `wait`) y enum OSM separado.
- Dos estados: `posicion` (puede bajar) y `resueltos[]` (no baja).
- Re-enganche solo hacia atrás.
- Decisiones: 8 m/metatile · radio 15 m · 180 nodos/perfil · un perfil por ciudad con
  confirmación · una ruta activa con varias almacenadas por `rom_key`.

Siete slices (0-6). **Slices 0, 1 y 2 HECHOS el 2026-11-07** (evidencia en §11.8 y §11.9);
los slices 3-6 siguen sin implementar, y los 3-5 dependen de 016.

### 11.5 Pendiente inmediato

1. **Slice 1 de 004: HECHO 2026-11-07** — query `node`+`way` de
   `amenity`/`tourism`/`historic`, `classify` reordenado (`building` al final), poda del
   enum a 8 valores. Host test 81 comprobaciones `EXIT=0`; build `EXIT=0`; APK
   `A67CDF78…875DC`; en POCO `Red: OK 278 elem` frente a 198 (§11.8).
2. **Slice 2 de 004: HECHO 2026-11-07** — ancla confirmada, `RouteGenerator` determinista,
   consulta por corredor y nodos `NODE` en el mapa. Host 44 comprobaciones `EXIT=0`; ruta
   final de 180 nodos (2 POI, 178 sintéticos, máximo 249.1 m); `BUILD_TAG`
   `2026-11-07+spec004-slice2` confirmado en dispositivo (§11.9).
3. **Slice 6a de 004: HECHO 2026-11-07** — export/import de ruta validado
   (`.ruta`, ZIP con manifiesto y `route.json`, checksum y `rom_key`). Host 26
   comprobaciones `EXIT=0`; APK `F532C426…0E7AEA8` (§11.10).
4. Siguiente pieza sin bloqueo: **6b (válvula de escape / R10)**, que depende del
   slice 4 (`posicion`/`resueltos[]`). Los slices 3-5 esperan 016 (identidad de mapa por
   perfil); el criterio de dispositivo §7.8 (POI real → Punto de Restauración) sigue
   pendiente de calle.

### 11.6 Prueba de campo del 2026-11-06 (auditada el 2026-11-07)

Acta del usuario: caminata en vía pública con GPS real (pasos 260→614), encuentro con
una criatura, victoria de una criatura, nivel 6, PANTALLAHUMO, guardado «Red OK, 51 elementos,
344 tiles», clima Lluvia.

| Qué | Estado |
|---|---|
| Log ADB de la prueba | **Perdido**: el buffer solo contiene 11-07; 0 líneas de la app |
| Crashes/ANR de la app el 11-06 | **Ninguno** en dropbox (los 2 `data_app_crash` son de otra app) |
| Caché OSM de la caminata | **Sí**: 51 elementos a las 15:44 (coincide con el acta), 50 a las 15:38, 72 a las 22:04 |
| Guardado | **Sí**: `.sav` 32.768 B a las 19:51, con `.sav.bak`, datos reales en bancos 0/1 |
| Modo al cerrar | `control_mode=gps` en prefs (22:06) |
| Pasos, especie, nivel, movimiento, «344 tiles», clima | **Solo vídeo**: no hay log ni parser de `.sav` Gen 2 |

**Causa del «salto de mapa»:** caché por celda `%.3f_%.3f` (≈111 m) + query de 150 m que
reemplaza todo el set; dos celdas contiguas se escribieron con 6 min de diferencia. Es el
diseño actual, no la «versión pre-spec 004»; lo corrige el slice 2 de 004 (consulta por
corredor).

**Los 6 defectos del acta no se cierran con el slice 1.** Cuatro son estructurales (fallback
sin OSM, caché por celda, snapshot OAM por frame, `WalkFilter` B4), uno es posible regresión
de 009 slice 3 (`onWildEncounter` → `applyViewMode(true)` ya existe) y uno es de UI (modo
inmersivo). Evidencia: `tools/poco-capture/20261106-campo/`.

### 11.7 Auditoría del combate (2026-11-07)

Pregunta: «la entrada a combate no fuerza el cambio de vista», ¿es una regresión?

**Respuesta: no hay nada en el árbol que pueda dispararlo.** No es una regresión; la función
no está implementada.

| Comprobación | Resultado |
|---|---|
| `GameEngine.onRealStep()` (:287-291) | **Stub**: `return false;` con el TODO «espera a la arquitectura base genérica (Spec 016)» |
| `tryWildEncounter` / `nativeTryWildEncounter` | **0 coincidencias** en `app/src` (Java y C++) |
| `onWildEncounter(` | Solo la declaración (`GameEngine:82`) y el override (`MainActivity:2507`); **0 llamadores** |
| `applyViewMode(true)` | Solo al cargar/importar ROM (`MainActivity:975`, `:1083`) y en el `onWildEncounter` muerto |

**Prueba en dispositivo del mecanismo (no del productor):** overlay ⚙ izquierdo → «Vista:
Mapa» → al pulsar, el pref `view_ppu_full` pasa `false`→`true`, la etiqueta cambia a «Vista:
Juego» y **el 65% de los píxeles muestreados cambian** (media RGB (35,61,26) con ROM a pantalla
completa frente a (85,98,46) en el mapa); al volver, el pref regresa a `false`.

**Conclusión:** `applyViewMode(true)` funciona; el productor no existe. El una criatura del acta
salió de la **ROM**, que dibuja su batalla dentro del emulador, pero la app permanece en Vista
Mapa. Re-implementar 009 slice 3 depende de 016.

**Nota lateral:** en la sesión de hoy el overlay mostró `Red: OK 198 elem · tiles:243`, frente a
«344 tiles» del acta. El conteo de tiles depende de lo que haya en VRAM, así que no es una
huella estable; el de elementos depende de la celda de caché.

### 11.8 Slice 1 de 004 cerrado (2026-11-07) — OSM POI-first

**Cambios de código:**
- `OsmDataFetcher.Kind` podado a 8 valores: `HIGHWAY, PARK, WOOD, BUILDING, WATER, SHOP,
  TOURISM, AMENITY`. Fuera `GOODS`, `GYM`, `LAB`, `POKESHOP`, `CAVE`, `HOME`, `WATERZONE`
  (0 usos y sin clave OSM de origen; los tipos de nodo de ruta viven en el modelo de ruta).
- Query: `node` **y** `way` de `highway/building/natural/leisure/shop/amenity/tourism/historic`
  con `around` común y `out geom`.
- `classify()`: `amenity` → `tourism|historic` → `shop` → `natural` → `leisure` → `highway` →
  `building` (último recurso). Ahora package-private para poder probarlo en host.

**Evidencia:**

| Comprobación | Resultado |
|---|---|
| Host test (`tools/host-test/run-java.ps1`) | **81 comprobaciones, `EXIT=0`** (39 pueblo + 11 geovalla + 31 OSM) |
| Build | `assembleDebug --offline` → **`EXIT=0`**, 45 tareas |
| APK | 11.378.265 B · mtime `2026-11-07 15:47:02` · `SHA-256 A67CDF78…590875DC` |
| Instalado en POCO | hash remoto `a67cdf78…590875dc` (idéntico) |
| `BUILD_TAG` en dispositivo | `BUILD 2026-11-07+spec004-poi-slice1` (15:47:39.696) |
| Query nueva | `OSM OK desde overpass-api.de (138203 B)` a las 15:48:14 |
| Elementos en la misma celda | **198 → 278** (+80); `AMENITY` 5→79, `TOURISM` 4→10 |
| Nodos POI nuevos | 68 nodos con `amenity`/`tourism`/`historic` (bancos, cafés, monumento, etc.) |
| Overlay en POCO | `Red: OK 278 elem · tiles:307` |

**Método del conteo:** se copió el JSON viejo de `files/osm/19.528_-96.924.json`, se borró en
el dispositivo para forzar recarga, y se comparó con el nuevo. La caché de 7 días habría
devuelto el resultado viejo.

**Lo que el slice 1 NO arregla:** el salto de mapa por celda, el pintado de
`TOURISM`/`AMENITY` en el atlas y la identidad de mapa por perfil (016). **El slice 2
(§11.9) ya corrige los dos primeros:** consulta por corredor con radio en la caché, nodos
`NODE` y `kindToCell` con `AMENITY`/`TOURISM`.

**Evidencia guardada:** `tools/poco-capture/20261107-slice1/` (logcat, overlay, JSON viejo y
nuevo, desglose por clase).

### 11.9 Slice 2 de 004 cerrado (2026-11-07) — ancla confirmada y ruta maestra

**Cambios de código:**
- Paquete nuevo `route/`: `Route` (esquema v1, `MAX_NODES=500`, validación con motivo),
  `RouteNode` (acción, POI, radio, `dest`, `blocked`/`unlock`), `RouteFormatException` y
  `RouteGenerator` (puro, host-testable).
- `RouteGenerator`: `METATILE_M=8.0`, `NODE_RADIUS_M=15.0`, `DEFAULT_NODE_TARGET=180`,
  `MAX_ANCHOR_ACCURACY_M=20.0`, `MAX_POI_RADIUS_M=250.0`,
  `MAX_SYNTHETIC_RADIUS_M=250.0`, `WATER_MARGIN_M=10.0`. POI por afinidad (`actionFor`),
  respaldo sintético a 8 m/metatile, ningún nodo sobre agua; mismo `rom_key` + ancla →
  misma ruta.
- `OsmDataFetcher.requestArea(lat, lon, radiusM)` y
  `requestCorridor(lat, lon, radiusM, spanM)`; `RADIUS_M=150` queda como defecto y la
  clave de caché incluye radio/corredor.
- `MainActivity`: ancla explícita (`confirmAnchorFlow()`: fix exterior, ≤20 m, fecha y
  precisión), `regenerateRouteIfAnchored()`, estado `Ruta: N nodos (M POI)` y persistencia
  en `filesDir/routes/route_<rom_key>.json`.
- `GBMapRenderer.setRouteNodes()` + tipo `NODE` (prioridad 8, por encima de
  agua/amenity/tourism/calle); `kindToCell` ya pinta `AMENITY` y `TOURISM`.

**Evidencia:**

| Comprobación | Resultado |
|---|---|
| Host `RouteHostTest` | **44 comprobaciones, `EXIT=0`** (determinismo, agua, 180 nodos, ancla >20 m rechazada, unión de caché) |
| Suite Java completa | **125 comprobaciones** (39 pueblo + 11 geovalla + 31 OSM + 44 ruta), `EXIT=0` |
| Build | `assembleDebug --offline` → **`EXIT=0`** |
| `BUILD_TAG` en dispositivo | `BUILD 2026-11-07+spec004-slice2` (16:03:43.175) |
| Ancla | `ANCLA OK 19.562807,-96.917928 ±3 m rom_key=PM_CRYSTAL-v0` (16:04:19.698) |
| Ruta final | **180 nodos**: 178 `walk` sintéticos + 1 `use` (`SHOP`, índice 27) + 1 `enter` (`AMENITY`, índice 51); radio 15 m; máximo **249.1 m** (nodo 178) |
| JSON de ruta | `route_PM_CRYSTAL-v0_final.json` · SHA-256 `BB7B6DD98A3F986EAE851F79028EC7EB2550DE6AD90D9C02FD0CAA88B3EF8267` |
| Mapa | `nodo_ruta=68` en la primera pasada y `estampados 180`; `AMENITY=55` en la celda |
| APK del árbol | 11.394.295 B · mtime `2026-11-07 19:12:11` · SHA-256 `58DE2CC81367E8DE87CADD324255B512419C846CA681E3D8FC93C9076E701FE0` |

**Defecto corregido dentro del slice:** la primera ruta
(`route_PM_CRYSTAL-v0.json`, SHA-256 `43999F97…F5741B1D`) salió con **111 nodos, todos POI
y hasta 4.1 km** del ancla, porque no había tope de radio. Con `MAX_POI_RADIUS_M=250` y
tramos `walk` de 8 m quedó en 180 nodos, 2 POI y máximo 249.1 m. El ancla se confirmó dos
veces antes de resolver el nodo 0 (postfix 19.5627717,-96.9179650 → final
19.5628067,-96.9179283), como permite §3.1.

**Lo que el slice 2 NO cierra:**
- Consumo de la ruta por GPS: slices 3-5, bloqueados por 016 (identidad de mapa por perfil).
- Criterio §7.8 (POI real → Punto de Restauración): necesita calle real.
- `resueltos[]` / `route_nodo0_resuelto`: sin escritor hasta el slice 4; el ancla sigue
  siendo re-anclable.
- Export/import: **hecho en el slice 6a** (§11.10); la válvula de escape (6b) sigue
  pendiente del slice 4.

**Evidencia guardada:** `tools/poco-capture/20261107-slice2/` (`logcat_slice2.txt`, JSON de
ruta previo/postfix/final y caché OSM de área y corredor).

### 11.10 Slice 6a de 004 cerrado (2026-11-07) — export/import de ruta

**Alcance ejecutable:** solo la mitad de export/import. La válvula de escape (6b) sigue
pendiente porque necesita `posicion` y `resueltos[]` (slice 4): sin nodo bloqueado no hay
nada que saltar ni que registrar.

**Cambios:**
- `route/RouteBundle.java` (nuevo, Java puro): paquete `.ruta` = ZIP con
  `manifest.json` (`format`, `version`, `rom_key`, `nodes`, `routeSha256`,
  `createdAt`) y `route.json` (esquema v1).
- Validación de importación: máximo 8 entradas; topes 64 KB (manifiesto) y 2 MB (ruta);
  rechazo de `..`, rutas absolutas, `\`, `:` y entradas desconocidas; formato y versión
  obligatorios; checksum SHA-256 opcional (si viene, se compara); `rom_key` del manifiesto
  y de la ruta deben coincidir y ser el de la partida cargada.
- `RouteBundle.save()` escribe de forma atómica (`route_<rom_key>.json`, `.tmp → .bak →
  rename`). `MainActivity.writeRouteFile()` usa ese método.
- UI SAF: “Exportar ruta (.ruta)” y “Importar ruta (.ruta)” en el engrane. Al importar se
  actualizan las prefs del ancla, el estado `Ruta: N nodos (M POI)` y los nodos del mapa.
- `BUILD_TAG` → `2026-11-07+spec004-slice6`.

**Evidencia:**

| Comprobación | Resultado |
|---|---|
| Host `RouteBundleHostTest` | **26 comprobaciones, `EXIT=0`** (round-trip, otro `rom_key`, formato/versión, checksum, traversal, entrada desconocida, tope de tamaño, guardado atómico) |
| Suite Java completa | **151 comprobaciones** (39 pueblo + 11 geovalla + 31 OSM + 44 ruta + 26 paquete), `EXIT=0` |
| Build | `assembleDebug --offline` → **`BUILD SUCCESSFUL`, `EXIT=0`** |
| APK | 12.410.600 B · mtime `2026-11-07 19:24:59` · SHA-256 `F532C426AAD0C0D589EFD2F2F4835F5D9E393765AB82CCC04EFD1FB620E7AEA8` |

**Lo que el slice 6a NO cierra:**
- Válvula de escape y estado explícito de fin (R10): 6b, bloqueado por el slice 4.
- Verificación en dispositivo del selector SAF: pendiente de gate físico; el host cubre
  el formato, no la UI.
- El paquete mueve la ruta, no el progreso (`posicion`/`resueltos[]`).
