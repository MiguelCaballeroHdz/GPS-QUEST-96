# spec.md — 008 Guardado real y perfiles `.pueblo`

Estado: **APROBADO** 2026-10-25.
Ejecución: Slices 1 y 2 HECHOS.

## 1. Visión y propósito

Garantizar la persistencia real de la partida guardada (`.sav`) y definir el contenedor portable `.pueblo` para exportación e importación mediante SAF, sin alterar los datos del usuario:

1. **`.sav` de memoria de guardado real** junto a los datos copiados en el almacenamiento interno de la app (escribible).
2. **Escritura atómica** con `.tmp` + `.bak` para no perder la partida si el proceso se interrumpe.
3. **`.pueblo`**: contenedor portable con la identidad de los datos, el `.sav`, el guardado geográfico y los indicadores de progreso, exportable/importable mediante SAF.

**Fidelidad:** El `.sav` es el formato estándar de la memoria persistente del cartucho o archivo de guardado. No se interpretan ni reescriben offsets arbitrarios; los datos del juego mandan.

## 2. Evidencia que motiva la spec

| Hecho medido | Dato |
|---|---|
| Carga y guardado atómico | Módulo `Mbc` calcula la ruta `.sav` a partir del archivo de datos y valida `hasBattery_` / `ramSize_`. |
| Copia en almacenamiento | La app asigna espacio escribible en `filesDir/data/` antes de ejecutar. |
| Vaciamiento | `GameEngine.pause()` invoca la persistencia al entrar en pausa. |

## 3. Requisitos funcionales

### RF-01: Cargar `.sav` al abrir los datos del usuario
- Si la cabecera indica persistencia y memoria > 0, leer `savPath_`.
- Si el archivo no existe: memoria nueva (0xFF), primera partida.
- Si el archivo es más corto: completar el resto con 0xFF.
- No cargar si no hay persistencia o `ramSize_ == 0`.
- **Criterio:** El log reporta `MBC SRAM cargada: N/M B` al reabrir.

### RF-02: Guardar `.sav` al salir
- Escribir solo si la memoria ha sido modificada (`ramDirty_`).
- Ruta: `<datos>.sav` junto al archivo de datos principal.
- Escritura atómica: `<datos>.sav.tmp` → respaldar anterior en `<datos>.sav.bak` → renombrar.
- `flush()` (al pausar) y deshabilitar la memoria guardan los datos.
- **Criterio:** Pausar la app y reabrir conserva la partida intacta.

### RF-03: No guardar si no hay batería
- Archivos de datos que no declaren persistencia por batería no generan archivos `.sav`.

### RF-04: Perfil `.pueblo`
- Contenedor (ZIP) estructurado con:
  - `manifest.json`: versión, título, código de juego, SHA-256 de los datos, fecha, tiempo de juego.
  - `battery.sav`: datos de guardado crudos.
  - `geo.sav`: guardado de estado geográfico (`saves/gpsquest_urbano.sav`).
  - `prefs.json`: banderas de progreso, nombre de usuario, última ubicación.
- Exportar por `ACTION_CREATE_DOCUMENT`; importar por `ACTION_OPEN_DOCUMENT`.
- Validar SHA-256 de los datos del usuario antes de aplicar el `.sav` (evita aplicar un guardado de otro juego).
- **Criterio:** Exportar e importar en otro dispositivo conserva partida e indicadores de progreso.

## 4. Requisitos no funcionales

- **Atomicidad:** Nunca dejar un `.sav` a medias; el `.bak` es la red de seguridad.
- **Rendimiento:** Guardar los datos en la pausa es eficiente; no guardar en cada fotograma.
- **Legalidad:** El `.sav` y el `.pueblo` son datos privados del usuario; no se suben ni comparten externamente.

## 5. Criterios de Aceptación

1. Tests de persistencia en host verdes.
2. `./gradlew assembleDebug --offline` → `EXIT=0`.
3. Dispositivo de prueba: guardar en el juego, salir a inicio, reabrir → opción de continuar con el progreso correcto.
4. El log muestra `MBC SRAM guardada` al pausar y `MBC SRAM cargada` al reabrir.
5. Perfil `.pueblo` exporta e importa conservando `.sav` + datos geográficos y rechaza archivos que no coincidan.

## 6. Riesgos y supuestos

- **Nombre de archivo:** El `.sav` se nombra igual que los datos copiados. `.pueblo` valida por SHA-256 la correspondencia de archivos.
- **SAF:** El `.pueblo` se exporta a la ubicación elegida por el usuario.

## 7. Dependencias

- Módulos nativos `Mbc` y `RomLoader`.
- Clases Java `SaveManager` y `PuebloProfile`.
