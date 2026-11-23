# Host test — core y `.pueblo` (sin móvil)

Arnés de host para verificar lógica que no necesita Android ni POCO. Tiene dos partes:

1. **C++ / core** (`run.ps1` en Windows, `run.sh` en Linux con `g++`): compila `mmu.cpp`, `mbc.cpp`, `rom_loader.cpp`, `ppu.cpp` y
   `cpu.cpp` con stubs de `android/log.h` y `renderer/renderer.h` (sin GLES2).
   - `test_mmu` — **34 checks**: DMG sin alias, espejo `0xE000`, bancos CGB (`SVBK`/`VBK`),
     paridad post-boot CGB a 2× (spec 013), `KEY1`+`STOP` con toggle, paletas
     `BCPS/BCPD`, `GDMA`, RGB15 y vuelta a DMG.
   - `test_sav` — **18 checks**: `.sav` MBC3+batería, escritura en dos bancos, reapertura,
     respaldo `.bak`, ROM sin batería que no genera `.sav`.
   - `test_cgb_ppu` — **51 checks**: paletas BG/OBJ RGB15, atributo del banco 1 (paleta,
     banco de tile, flips), prioridad BG↔OBJ, `OPRI` (X vs índice OAM), ventana con atributos,
     `LCDC.0` ignorado en CGB, sprite transparente, STAT modo 0/1/2 y LYC por flanco.
   - `test_lr35902` — **23 checks**: NOP/LD, paridad post-boot CGB (2× al cargar, spec
     013), `STOP` + `KEY1` con toggle (2x↔1x), reset de `DIV` al parar, y los timers
     (DIV/TIMA) en ticks de SYSCLK correctos a 1× y a 2× (hardware: NO se aceleran).
   - `test_wildtable` — **71 checks sintéticos** (con ROM real sube): localización
     estructural de las tablas grass/water de Gen 2, rangos, tabla corrupta y, si
     `GPSQUEST_ROM_GEN2` está definida, las cuatro tablas de Gen 2 y ruta inicial.
     En bases sin `wild_routine.cpp` (antes de la 009 slice 3), `run.sh` lo salta.
   - `test_apu` — **35 checks** (diagnóstico del núcleo, spec 020): tasa de muestras
     (738/frame), cadencia de drenaje, capacidad del anillo (4095 frames, muestras
     tiradas sin drenar), frame sequencer (length/envelope/sweep) y los 4 canales.
     Pincha el bug de índices `regs_[]` +0x10 con sondas (ch3/ch4 mudos, ch1/ch2
     con registros del vecino); el slice de corrección cambia los checks a su
     valor hardware.
   - `test_timing` — **19 checks** (diagnóstico del núcleo, spec 020): modos PPU
     80/172/204, VBlank a 65664 ciclos, frame completo 70224 ciclos (59,73 fps),
     sin deriva en 2 frames seguidos.
2. **Java / `.pueblo` / OSM** (`run-java.ps1`): compila las clases **reales**
   `PuebloProfile`, `SaveManager`, `GeofenceManager` y `OsmDataFetcher` contra stubs mínimos
   de `android.content`/`android.util` y el jar `org.json`.
   - `PuebloHostTest` - **39 checks**: export ZIP (manifest, SHA-256, battery, geo, prefs),
     import en "otro equipo", `.bak` con la partida anterior, rechazo por ROM distinta y
     rechazo de `geo.sav` inválido sin tocar el `.sav`.
   - `GeofenceHostTest` - **11 checks**: distancia al borde de la polilínea, umbral de 20 m,
     varios listeners, `replaceWaterZones`.
   - `OsmClassifyHostTest` - **31 checks** (spec 004 slice 1): hospital
     `building=yes`+`amenity=hospital` → `AMENITY`, monumento `historic=monument` →
     `TOURISM`, POI por delante de terreno, enum de 8 valores alcanzables, `parse()` conserva
     nodos y vías y descarta lo que no tiene tipo.

**Total C++: 180 checks** (34 + 18 + 51 + 23 + 35 + 19; sin `test_wildtable`,
que vive en el PR #4 con 71 checks).
Con `GPSQUEST_ROM_GEN2` definida, `test_wildtable` sube y el total también.

## Ejecutar

```powershell
powershell -ExecutionPolicy Bypass -File tools\host-test\run.ps1
powershell -ExecutionPolicy Bypass -File tools\host-test\run-java.ps1
```

```sh
sh tools/host-test/run.sh        # C++ en Linux (g++)
sh tools/host-test/run-java.sh   # Java en Linux
```

`run.ps1` requiere Visual Studio Build Tools con `cl.exe` (el `VsDevCmd.bat` se localiza solo).
`run-java.ps1` requiere JDK 17 (`JAVA_HOME`) y `org.json`: lo busca en la caché de Gradle y,
si no está, usa el jar vendoreado `lib/json-20231013.jar` (la app usa el `org.json` del
framework, así que la caché puede no tenerlo). Ninguno toca el APK ni el build de Gradle.

## Límites

No sustituyen el gate POCO:

- La PPU y la CPU se prueban con VRAM/paletas/ROM sintéticas, no con el juego real ni GLES2.
  El color final, el orden de boot de Gen 2 y `CONTINUE` se validan en el dispositivo.
- El arnés Java no ejercita SAF ni la UI: los streams se simulan con `ByteArrayInputStream`/
  `ByteArrayOutputStream`. La exportación/importación real se valida en el POCO.
- Para el gate POCO, `tools/poco-test.ps1` instala, espera las pruebas manuales y captura
  logcat + dumps (frame PNG, paletas, VRAM, SRAM y saves) en un solo pase.
