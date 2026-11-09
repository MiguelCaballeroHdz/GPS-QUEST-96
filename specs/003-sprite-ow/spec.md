# spec.md — 003 Sprite OW del Jugador desde datos de juego del usuario (B4)

Estado: APROBADO 2026-10-19.

## 1. Visión y Propósito
Reemplazar el punto blanco neutro (`drawMarker()` en `MainActivity`) con el sprite de exploración del avatar extraído en vivo de la memoria de datos del usuario, cumpliendo el principio de lectura 100% fiel: nada propio como contenido.

Fidelidad al Estándar:
- **Lectura 100% fiel:** El sprite se lee de la memoria de video (VRAM) en el momento exacto en que el juego lo ha procesado. Sin assets propios, sin sprites inventados.
- **Sin adivinar direcciones:** No se buscan offsets fijos. Se identifica el sprite observando qué tiles cambian cuando el avatar se desplaza en el mapa.
- **Sin afectar al core:** El pipeline del motor permanece intacto.

## 2. Requisitos Funcionales

### RF-01: Identificación del Sprite de Exploración
- Monitorear tiles de VRAM durante la exploración del mapa.
- Detectar qué tiles cambian cuando el avatar se mueve (diferencial entre frames consecutivos).
- Esos tiles corresponden al avatar del jugador. Sin buscar offsets fijos.

### RF-01bis: Identificación por OAM centrada
- **Mecanismo:** Leer la memoria OAM, tomar el objeto 16×16 cuyo tile superior-izquierdo cae en (72,64) de la pantalla de 160×144 (avatar centrado) con tolerancia de 16 px; sus 4 tiles consecutivos se componen desde VRAM con la paleta activa.
- **Estándar:** Sigue siendo lectura 100% fiel en vivo desde los datos del usuario; no hay offsets fijos ni assets propios.

### RF-02: Extracción del Sprite
- Componer el sprite desde los tiles identificados (típicamente 2×2 = 16×16 píxeles en motores de 8 bits).
- Subir el sprite compuesto a GPU como textura.
- Mantener las orientaciones de dirección y fotogramas de animación procesados por el motor.

### RF-03: Renderizado del Sprite
- `drawSprite()` reemplaza `drawMarker()` en `onDrawFrame`.
- El sprite se dibuja sobre el mapa (modo Mapa) o en pantalla completa (modo Juego).
- Animación: alternar fotogramas según el estado de movimiento (quieto/caminando).

### RF-04: Fallback Honesto
- Si no se puede identificar el sprite (VRAM sin datos, inicio del motor, interfaz sin avatar), se mantiene el punto blanco neutro.
- Nunca se muestra un sprite inventado ni ajeno a los datos del usuario.

## 3. Requisitos No Funcionales
- **Rendimiento:** Mantener 30+ fps. La identificación del sprite se realiza en worker optimizado, no en `onDrawFrame`.
- **Compatibilidad:** Verificado en dispositivo de prueba.

## 4. Criterios de Aceptación
1. `assembleDebug` verde (`EXIT=0`).
2. Sprite visible en dispositivo de prueba, reemplazando al punto blanco.
3. El sprite corresponde a celdas de VRAM que cambian con el movimiento.
4. Cero assets propios: el sprite sale de VRAM, no de un archivo integrado en el código.
5. Fallback a punto blanco cuando no hay sprite identificable.

## 5. Riesgos y Supuestos
- **Riesgo:** El juego puede usar sprites en regiones alternas de memoria. Mitigación: monitorear toda la región de tiles.
- **Riesgo:** Otros elementos en pantalla también pueden cambiar. Mitigación: correlacionar cambios con la entrada de control del jugador.
- **Supuesto:** El jugador está en mapa de exploración (no en menú, no en combate). Sin exploración, no hay sprite que extraer.

## 6. Dependencias
- `TileAtlas.snapshot(mmu)` — lectura de VRAM verificada.
- `drawMarker()` — punto blanco actual, se reemplaza.
- `GameRenderer` — infraestructura GL establecida.
