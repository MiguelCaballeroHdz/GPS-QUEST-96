# spec.md — 007 Modo a Color Real: paletas, doble velocidad y bancos de memoria

Estado: **APROBADO** 2026-10-25.
Ejecución: Slices 1 y 2 HECHOS + timing parcial (DIV/TIMA, STAT/LYC) HECHO.

## 1. Visión y propósito

El núcleo soporta tanto el modo retro estándar en escala de grises como el **modo a color de 8 bits**: paletas de color RGB15, 16 KB de memoria de video (VRAM), 32 KB de memoria de trabajo (WRAM) y cambio de velocidad `KEY1`.

Esta spec implementa **color real de 8 bits** sin inventar contenido:

- Color RGB15 (paletas BG/OBJ de 8×4 colores).
- Doble velocidad (`KEY1` + `STOP`).
- Bancos de memoria: VRAM 2×8 KB (`VBK`), WRAM 8×4 KB (`SVBK`).
- Transferencias por bloques HDMA/GDMA (`0xFF51-0xFF55`).
- Atributos por tile (banco 1 de VRAM): paleta, flips, prioridad, banco de tile.
- Prioridad de sprites (`OPRI`) y atributos de control.

**Fidelidad:** Las características de color e interfaz corresponden a los datos del usuario. Las mecánicas secundarias congeladas permanecen en `memory/archivo/POSTGAME.md`.

**Motores de 32 bits no entran aquí:** pertenecen a otra arquitectura y tendrán spec propia.

## 2. Evidencia que motiva la spec

| Hecho medido | Dato |
|---|---|
| Registros a color en el core | Implementación de `KEY1`, `SVBK`, `VBK` en los módulos de bajo nivel. |
| Memoria | `vram_[0x2000]` (2 bancos) y `ram_[0x1000]` (8 bancos). |
| Paletas | Registros de paleta `BCPS/BCPD` y `OCPS/OCPD`. |
| HDMA | Transferencias `0xFF51-0xFF55` integradas. |
| Cabecera | Identificación de bandera de color en cabecera de datos. |

## 3. Requisitos funcionales

### RF-01: Detección y modo
- Leer la cabecera de datos: detectar soporte a color o escala de grises.
- Configurar el modo en la MMU antes de inicializar la CPU.
- Log de arranque: `Color ON/OFF`. Sin datos cargados, modo por defecto.
- **Criterio:** El log del dispositivo identifica el modo correcto para cada archivo de datos del usuario.

### RF-02: Bancos de memoria
- VRAM: 2 bancos de 8 KB; `VBK` (`0xFF4F`) selecciona el banco activo para la CPU.
- La PPU lee **tiles del banco 0** y **atributos del banco 1**; `VBK` no altera el renderizado.
- WRAM: 8 bancos de 4 KB; `SVBK` (`0xFF70`) selecciona el banco activo.
- **Criterio:** El modo estándar se mantiene intacto; el modo a color conmuta bancos correctamente.

### RF-03: Doble velocidad
- `KEY1` (`0xFF4D`): bit 7 velocidad actual (solo lectura), bit 0 arma el cambio.
- `STOP` (`0x10`) completa el cambio si está armado.
- Con doble velocidad, los temporizadores avanzan la proporción exacta por ciclo.
- **Criterio:** La doble velocidad no acelera indebidamente el procesador de video ni el audio.

### RF-04: Paletas y atributos a color
- `BCPS/BCPD` (`0xFF68/69`) y `OCPS/OCPD` (`0xFF6A/6B`): 64 B cada uno, auto-incremento con bit 7.
- Color RGB15: `r = b & 0x1F`, `g = (b >> 5) & 0x1F`, `b = (b >> 10) & 0x1F`, expandido a 8 bits.
- Atributos del banco 1 por tile: bits 0-2 paleta, 3 banco de tile, 4-5 X/Y flip, 6 prioridad.
- **Criterio:** Los datos a color se presentan con sus paletas oficiales.

### RF-05: HDMA/GDMA
- `FF51/52` origen, `FF53/54` destino (`0x8000 | ...`), `FF55` bloques.
- Copia síncrona al escribir `FF55`.
- **Criterio:** Ninguna secuencia queda bloqueada esperando `FF55`.

### RF-06: Prioridades de visualización
- `OPRI` (`0xFF6C`): 0 = prioridad por posición X, 1 = por índice OAM.
- **Criterio:** Renderizado de sprites y fondos sin solapamientos indebidos.

## 4. Requisitos no funcionales

- **Rendimiento:** Mantener ≥ 50 fps equivalentes de emulación.
- **Estabilidad:** Compilación verde (`EXIT=0`) tras cada cambio.
- **Regresión:** Los archivos de datos en escala de grises deben verse exactamente igual que antes.
- **Cero contenido inventado:** Las paletas provienen de los datos de juego del usuario en runtime.

## 5. Criterios de aceptación

1. `./gradlew assembleDebug --offline` → `EXIT=0`.
2. Detección de color correcta en logcat: `Color ON`.
3. Pantallas a color presentadas con fidelidad sin rayas ni distorsión.
4. Archivos en escala de grises mantienen su presentación original.
5. Cero escrituras no autorizadas en memoria RAM protegida.

## 6. Riesgos y supuestos

- **Supuesto:** Archivos de datos del usuario válidos y aportados localmente.
- **Riesgo:** Ajustes de doble velocidad; verificado en el dispositivo de prueba.

## 7. Dependencias

- Módulos nativos `mmu`, `cpu`, `ppu` y `rom_loader`.
- Puente JNI `native_bridge.cpp`.
