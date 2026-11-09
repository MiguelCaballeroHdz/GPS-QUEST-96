# plan.md — 008 Guardado real y perfiles `.pueblo`

Spec: `specs/008-guardado-real/spec.md` (APROBADA 2026-10-25).
Regla: un slice verificable por turno; build verde; si rompe, se repara ese archivo.

## Slice 1 — `.sav` real (HECHO 2026-10-25)

**Objetivo:** que `CONTINUE` exista y que matar la app en pausa no pierda la partida.

Archivos:
- `core/mbc.cpp`:
  - `loadSav()`: abre `savPath_` en `rb`, lee hasta `sram_.size()`, rellena el resto con
    `0xFF`, marca `ramDirty_ = false` y loguea `MBC SRAM cargada: N/M B`.
  - `saveSav()`: solo si `hasBattery_ && ramDirty_`; escribe `savPath_.tmp`, respalda el
    anterior en `savPath_.bak` y promueve con `rename`; loguea `MBC SRAM guardada`.
  - Guardas: sin batería, sin SRAM o sin `savPath_` → no hace nada.
- `tools/host-test/test_sav.cpp`: ROM sintética MBC3+batería de 32 KB; escribe dos bancos,
  `flush`, reabre y verifica; crea `.bak` en la segunda escritura; comprueba que una ROM
  sin batería no genera `.sav`.
- `tools/host-test/run.ps1`: compila y ejecuta `test_mmu` y `test_sav`.

Verificación:
- `test_sav` → `OK: 18 checks`.
- `test_mmu` → `OK: 30 checks`.
- `.\gradlew assembleDebug --offline` → `BUILD SUCCESSFUL`.

Evidencia del log del arnés:
- `MBC SRAM nueva (32768 B)` en la primera sesión.
- `MBC SRAM guardada: 32768 B`.
- `MBC SRAM cargada: 32768/32768 B` en la segunda.
- `.bak` de 32768 B con el byte anterior (`0x42`).
- ROM MBC1 sin batería: `sin .sav` y no se crea archivo.

Riesgo residual: el `.sav` se llama como la ROM copiada; dos ROMs distintas con el mismo
nombre compartirían save. `.pueblo` lo corrige con SHA-256.

## Slice 2 — Perfil `.pueblo` (HECHO 2026-10-25 · falta gate POCO)

**Objetivo:** mover una partida entre dispositivos sin tocar la ROM.

Archivos:
- `app/src/main/java/com/gpsquest96/emulator/PuebloProfile.java` (nuevo):
  - `export(OutputStream, romPath, title, gameCode)`: ZIP con `manifest.json`
    (formato, versión, título, game code, SHA-256, `batterySize`, `hasGeo`), `battery.sav`
    (si existe), `geo.sav` (si existe) y `prefs.json`.
  - `importFrom(InputStream, romPath)`: lee el ZIP con límites (64 entradas; 64 KB manifest,
    1 MB battery, 1 MB geo, 256 KB prefs), valida formato/versión y **SHA-256** de la ROM
    cargada; rechaza sin escribir si no coincide. Escribe `.sav` y geo con
    `.tmp` → `.bak` → rename y aplica prefs.
  - `sha256(File)`: identidad estable; no se guarda la ROM en el perfil.
- `SaveManager`: `exportPrefs()` / `importPrefs()` con nombre, edad, género, flags de evento,
  tregua y última ubicación (lat/lon como bits crudos). No exporta `rom_path`.
- `GameEngine`: `getRomInfo()`, `flushSram()`, `reloadRom()` (cierra y reabre para que el core
  lea el `.sav` importado) y `reloadState()` (reemplaza flags, no mezcla).
- `MainActivity`: entradas "Exportar perfil (.pueblo)" e "Importar perfil (.pueblo)" en el
  engrane, con `ACTION_CREATE_DOCUMENT` / `ACTION_OPEN_DOCUMENT`; pausa el motor, escribe en
  hilo de fondo y recarga la ROM al importar.
- `res/layout/activity_main.xml`: `tv_opt_export` y `tv_opt_import`.

Verificación:
- `powershell -File tools\host-test\run-java.ps1` → **`OK: 39 checks`**: export ZIP
  (manifest, SHA-256, battery, geo, prefs), import en otro directorio, respaldo `.bak`,
  rechazo por ROM distinta y rechazo de `geo.sav` inválido sin tocar el `.sav`.
- `assembleDebug --offline` → **BUILD SUCCESSFUL** (compila API 34, ZIP/JSON/SAF).
- **Pendiente POCO:** exportar Gen 2, borrar datos de la app, importar el `.pueblo` y
  abrir `CONTINUE`; comprobar rechazo con una ROM distinta (hash no coincide).
- El arnés Java no ejercita SAF/UI: los streams se simulan en memoria. El gate real es el POCO.

Criterio de salida: `.pueblo` ida y vuelta en el POCO + log de hash válido.

## Slice 3 — Gate POCO del guardado

- Guardar en el juego (punto de curación), volver al menú, matar la app desde recientes,
  reabrir y entrar con `CONTINUE`.
- Revisar log: `MBC SRAM guardada` al pausar y `MBC SRAM cargada` al reabrir.
- Probar con Azul (MBC5+batería) y Gen 2 (MBC3+batería+RTC) si están disponibles.
- Fotos/log en `out/poco/` y línea en `memory/archivo/bitacora-historico.md`.

## Orden de dependencias

```
Slice 1 (hecho) → Slice 2 (hecho, falta POCO) → Slice 3 (gate POCO de CONTINUE + .pueblo)
```

El slice 2 no bloquea B2; ya está implementado y solo le falta la prueba de ida y vuelta en
el POCO. Después de 008: **009 B2 spawns** (necesita ROM y datos del POCO).
